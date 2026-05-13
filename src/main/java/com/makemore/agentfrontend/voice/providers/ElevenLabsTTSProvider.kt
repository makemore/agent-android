package com.makemore.agentfrontend.voice.providers

import android.media.MediaDataSource
import android.media.MediaPlayer
import com.makemore.agentfrontend.networking.APIClient
import com.makemore.agentfrontend.networking.await
import com.makemore.agentfrontend.networking.voiceToken
import com.makemore.agentfrontend.networking.voices
import com.makemore.agentfrontend.voice.TTSProvider
import com.makemore.agentfrontend.voice.TTSSpeakOptions
import com.makemore.agentfrontend.voice.VoiceDescriptor
import com.makemore.agentfrontend.voice.VoiceToken
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Streams MP3 audio from the Django voice proxy and plays it via
 * [MediaPlayer].
 *
 * Security: never sees the ElevenLabs API key. Calls
 * [APIClient.voiceToken] to mint a short-lived signed bearer, then POSTs
 * to the proxy's `ttsUrl` with that bearer in the `Authorization`
 * header. Tokens are cached in-memory and refreshed on 401 / near-expiry.
 *
 * Latency: each [speak] call awaits the full MP3 byte stream before
 * playback starts. The chunker keeps each request short (1-2 sentences)
 * so end-to-end latency stays acceptable on cellular.
 */
class ElevenLabsTTSProvider(
    private val apiClient: APIClient,
    private val defaultVoiceId: String? = null,
    private val defaultModelId: String? = null,
    private val defaultVoiceSettings: Map<String, Any?>? = null,
) : TTSProvider {
    override val name: String = "elevenlabs"

    private val tokenMutex = Mutex()
    private var cachedToken: VoiceToken? = null
    private var currentPlayer: MediaPlayer? = null

    override suspend fun speak(text: String, options: TTSSpeakOptions) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return

        var (token, ttsUrl) = ensureToken()
        var audio = fetchAudio(trimmed, options, token, ttsUrl, allowRetry = true)
        if (audio == null) {
            // 401 → drop cache, retry once with a freshly-minted bearer.
            tokenMutex.withLock { cachedToken = null }
            val refreshed = ensureToken()
            token = refreshed.first; ttsUrl = refreshed.second
            audio = fetchAudio(trimmed, options, token, ttsUrl, allowRetry = false)
        }
        val bytes = audio ?: throw IOException("ElevenLabs proxy returned empty audio")
        play(bytes)
    }

    override fun cancel() {
        currentPlayer?.let {
            runCatching { if (it.isPlaying) it.stop() }
            runCatching { it.release() }
        }
        currentPlayer = null
    }

    override suspend fun listVoices(): List<VoiceDescriptor> = try {
        apiClient.voices()
    } catch (_: Throwable) {
        emptyList()
    }

    override fun shutdown() = cancel()

    // -- HTTP ------------------------------------------------------

    private suspend fun fetchAudio(
        text: String, options: TTSSpeakOptions,
        token: String, ttsUrl: String, allowRetry: Boolean,
    ): ByteArray? = withContext(Dispatchers.IO) {
        val body = buildBody(text, options).toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(ttsUrl)
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .header("Accept", "audio/mpeg")
            .post(body)
            .build()

        val response = apiClient.httpClient.newCall(request).await()
        try {
            if (response.code == 401) {
                if (allowRetry) return@withContext null
                throw IOException("ElevenLabs proxy 401 after refresh")
            }
            if (response.code !in 200..299) {
                throw IOException("ElevenLabs proxy HTTP ${response.code}")
            }
            response.body?.bytes() ?: throw IOException("Empty audio body")
        } finally {
            response.close()
        }
    }

    private fun buildBody(text: String, options: TTSSpeakOptions): String {
        val obj = JSONObject().apply {
            put("text", text)
            (options.voiceId ?: defaultVoiceId)?.let { put("voice_id", it) }
            (options.modelId ?: defaultModelId)?.let { put("model_id", it) }
            (options.voiceSettings ?: defaultVoiceSettings)?.let {
                put("voice_settings", JSONObject(it))
            }
            options.emotion?.let { e ->
                val em = JSONObject().apply {
                    put("name", e.name)
                    put("intensity", e.intensity)
                    if (e.metadata.isNotEmpty()) put("metadata", JSONObject(e.metadata))
                }
                put("emotion", em)
            }
        }
        return obj.toString()
    }

    // -- Token ----------------------------------------------------

    private suspend fun ensureToken(): Pair<String, String> = tokenMutex.withLock {
        val now = System.currentTimeMillis()
        cachedToken?.let { c ->
            if (c.expiresAtMillis - now > REFRESH_LEEWAY_MS) return@withLock c.token to c.ttsUrl
        }
        val minted = apiClient.voiceToken()
            ?: throw IOException("Voice token endpoint returned nothing — is voice enabled on the backend?")
        cachedToken = minted
        minted.token to minted.ttsUrl
    }

    // -- Playback -------------------------------------------------

    private suspend fun play(audio: ByteArray) = suspendCancellableCoroutine { cont: CancellableContinuation<Unit> ->
        val player = MediaPlayer()
        currentPlayer = player

        val source = ByteArrayMediaDataSource(audio)
        try {
            player.setDataSource(source)
        } catch (t: Throwable) {
            currentPlayer = null
            runCatching { player.release() }
            cont.resumeWithException(t)
            return@suspendCancellableCoroutine
        }

        player.setOnPreparedListener { it.start() }
        player.setOnCompletionListener {
            currentPlayer = null
            runCatching { it.release() }
            if (cont.isActive) cont.resume(Unit)
        }
        player.setOnErrorListener { mp, what, extra ->
            currentPlayer = null
            runCatching { mp.release() }
            if (cont.isActive) cont.resumeWithException(IOException("MediaPlayer error $what/$extra"))
            true
        }

        cont.invokeOnCancellation {
            runCatching { if (player.isPlaying) player.stop() }
            runCatching { player.release() }
            if (currentPlayer === player) currentPlayer = null
        }

        try {
            player.prepareAsync()
        } catch (t: Throwable) {
            currentPlayer = null
            runCatching { player.release() }
            if (cont.isActive) cont.resumeWithException(t)
        }
    }

    /** In-memory MP3 source for [MediaPlayer]. Required since SDK 23+. */
    private class ByteArrayMediaDataSource(private val data: ByteArray) : MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= data.size) return -1
            val end = minOf(position.toInt() + size, data.size)
            val len = end - position.toInt()
            System.arraycopy(data, position.toInt(), buffer, offset, len)
            return len
        }
        override fun getSize(): Long = data.size.toLong()
        override fun close() { /* no-op */ }
    }

    companion object {
        private const val REFRESH_LEEWAY_MS = 30_000L
    }
}
