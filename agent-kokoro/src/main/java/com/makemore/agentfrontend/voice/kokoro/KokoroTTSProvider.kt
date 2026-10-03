package com.makemore.agentfrontend.voice.kokoro

import android.util.Log
import com.makemore.agentfrontend.voice.Emotion
import com.makemore.agentfrontend.voice.TTSProvider
import com.makemore.agentfrontend.voice.TTSSpeakOptions
import com.makemore.agentfrontend.voice.VoiceDescriptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * On-device neural TTS: Kokoro-82M (v1.0) through sherpa-onnx.
 *
 * - Text never leaves the device. The only network access is the one-time
 *   model download done by [KokoroModelManager] (a GET of the model URL).
 * - Each [speak] call (one [com.makemore.agentfrontend.voice.VoiceController]
 *   sentence chunk) is synthesised on a dedicated background thread and
 *   streamed to an [android.media.AudioTrack] as audio is produced; chunks
 *   play strictly in order. [prefetch] renders the next chunk while the
 *   current one plays so consecutive chunks join without a gap.
 * - [cancel] stops playback and abandons queued synthesis promptly.
 * - Falls back to [fallback] (the local-only system voice) while the model
 *   is not downloaded, if the engine cannot load, and — for the rest of the
 *   turn — after a synthesis/playback error. Logs carry reason codes only,
 *   never text.
 */
class KokoroTTSProvider internal constructor(
    private val modelManager: KokoroModelManager,
    private val options: KokoroOptions,
    defaultVoiceId: String?,
    private val fallbackFactory: () -> TTSProvider,
    private val loader: KokoroSynthesizerLoader,
    private val audioOutput: PcmAudioOutput,
    private val synthExecutor: ExecutorService,
    private val playbackDispatcher: CoroutineDispatcher,
    private val clock: () -> Long,
    private val log: (String) -> Unit,
) : TTSProvider {

    /**
     * @param modelManager usually [KokoroTTS.modelManager].
     * @param defaultVoiceId a Kokoro voice id (e.g. `"bm_george"`); unknown
     *   ids use [KokoroOptions.defaultVoiceId].
     * @param fallback builds the provider used when Kokoro cannot speak — it
     *   must itself keep text on the device (e.g.
     *   `AndroidTTSProvider(context, localOnly = true)`).
     */
    constructor(
        modelManager: KokoroModelManager,
        options: KokoroOptions = KokoroOptions(),
        defaultVoiceId: String? = null,
        fallback: () -> TTSProvider,
    ) : this(
        modelManager = modelManager,
        options = options,
        defaultVoiceId = defaultVoiceId,
        fallbackFactory = fallback,
        loader = SherpaKokoroSynthesizer.loader(options.numThreads),
        audioOutput = AudioTrackOutput,
        synthExecutor = newSynthesisExecutor(),
        playbackDispatcher = Dispatchers.IO,
        clock = System::currentTimeMillis,
        log = { message -> Log.i(LOG_TAG, message) },
    )

    override val name: String = KokoroTTS.ENGINE_NAME

    private val synthDispatcher = synthExecutor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob())
    private val lock = Any()
    private val defaultVoice: KokoroVoice

    // Guarded by [lock].
    private var engine: KokoroSynthesizer? = null
    private var engineLoad: Deferred<KokoroSynthesizer?>? = null
    private var engineUnavailable = false
    private var shutDown = false
    private val prefetched = LinkedHashMap<String, Utterance>()
    private var current: Utterance? = null
    private var playback: PcmPlayback? = null
    private var fallbackProvider: TTSProvider? = null

    /** Set when Kokoro could not speak this turn; cleared by [onTurnStart] / [cancel]. */
    @Volatile private var fallbackForTurn = false

    init {
        val requested = KokoroVoices.find(defaultVoiceId)
        if (defaultVoiceId != null && requested == null) {
            log("kokoro: configured voice id is not a Kokoro voice; using the default voice")
        }
        defaultVoice = requested ?: KokoroVoices.find(options.defaultVoiceId) ?: KokoroVoices.default
    }

    // -- TTSProvider ------------------------------------------------

    override suspend fun speak(text: String, options: TTSSpeakOptions) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val synth = engineForSpeech()
        if (synth == null) {
            fallback().speak(trimmed, options)
            return
        }
        val request = request(trimmed, options)
        val utterance = synchronized(lock) {
            val u = takePrefetchedLocked(request) ?: Utterance(request)
            current = u
            // This chunk first, then anything prefetched behind it, so the
            // single synthesis thread renders in playback order.
            launchLocked(u, synth)
            prefetched.values.forEach { launchLocked(it, synth) }
            u
        }
        try {
            withContext(playbackDispatcher) { play(utterance, synth.sampleRate) }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            var reason: Throwable = e
            while (reason is KokoroEngineFailure && reason.cause != null) reason = reason.cause!!
            log("kokoro: synthesis failed (${reason.javaClass.simpleName}); using the system voice for this turn")
            fallbackForTurn = true
            dropPrefetched()
            fallback().speak(trimmed, options)
        } finally {
            utterance.cancel()
            synchronized(lock) { if (current === utterance) current = null }
        }
    }

    override fun prefetch(text: String, options: TTSSpeakOptions) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || fallbackForTurn || !modelManager.isAvailable) return
        synchronized(lock) {
            if (shutDown || engineUnavailable) return
            if (prefetched.size >= MAX_PREFETCH || prefetched.containsKey(trimmed)) return
            val u = Utterance(request(trimmed, options))
            prefetched[trimmed] = u
            // Start now only if a chunk is already speaking (it was submitted
            // first); otherwise the next speak() starts it in order.
            val synth = engine
            if (current != null && synth != null) launchLocked(u, synth)
        }
    }

    override fun cancel() {
        val (utterances, activePlayback) = synchronized(lock) {
            val all = prefetched.values.toList() + listOfNotNull(current)
            prefetched.clear()
            current = null
            (all to playback).also { playback = null }
        }
        utterances.forEach { it.cancel() }
        activePlayback?.stop()
        synchronized(lock) { fallbackProvider }?.cancel()
        fallbackForTurn = false
    }

    override fun onTurnStart() {
        fallbackForTurn = false
        synchronized(lock) { fallbackProvider }?.onTurnStart()
    }

    override suspend fun listVoices(): List<VoiceDescriptor> = KokoroVoices.all.map { it.toDescriptor() }

    override fun shutdown() {
        cancel()
        val (loaded, fallback) = synchronized(lock) {
            shutDown = true
            (engine to fallbackProvider).also { engine = null }
        }
        runCatching { synthExecutor.execute { loaded?.release() } }
        synthExecutor.shutdown()
        scope.cancel()
        fallback?.shutdown()
    }

    // -- Host helpers -------------------------------------------------

    /**
     * Load the engine now (e.g. when the chat opens) so the first reply does
     * not wait 1–3 s for it. Returns true when Kokoro is ready to speak.
     * Does not download; see [KokoroModelManager.download].
     */
    suspend fun prepare(): Boolean = modelManager.isAvailable && engineForSpeech() != null

    // -- Internals ------------------------------------------------------

    private suspend fun engineForSpeech(): KokoroSynthesizer? {
        if (fallbackForTurn) return null
        if (!modelManager.isAvailable) {
            releaseEngine()
            maybeStartDownload()
            // Keep one voice for the whole reply even if the download
            // finishes part-way through it.
            fallbackForTurn = true
            log("kokoro: model not on the device yet; using the system voice for this turn")
            return null
        }
        val load = synchronized(lock) {
            if (engineUnavailable || shutDown) return null
            engine?.let { return it }
            engineLoad ?: scope.async(synthDispatcher) { loadEngine() }.also { engineLoad = it }
        }
        return load.await()
    }

    private fun loadEngine(): KokoroSynthesizer? {
        val files = modelManager.modelFiles()
        if (files == null) {
            synchronized(lock) { engineLoad = null }
            return null
        }
        return try {
            val loaded = loader.load(files)
            synchronized(lock) {
                engineLoad = null
                if (shutDown) {
                    loaded.release()
                    return null
                }
                engine = loaded
            }
            loaded
        } catch (t: Throwable) {
            // Includes UnsatisfiedLinkError (no native lib for this ABI).
            log("kokoro: engine failed to load (${t.javaClass.simpleName}); using the system voice")
            synchronized(lock) {
                engineUnavailable = true
                engineLoad = null
            }
            null
        }
    }

    private fun releaseEngine() {
        val loaded = synchronized(lock) { engine.also { engine = null } } ?: return
        runCatching { synthExecutor.execute { loaded.release() } }
    }

    private fun maybeStartDownload() {
        if (!options.autoDownload) return
        when (modelManager.state.value) {
            KokoroModelState.NotDownloaded -> modelManager.startDownload()
            is KokoroModelState.Failed ->
                if (clock() - modelManager.lastFailureAtMillis >= DOWNLOAD_RETRY_AFTER_MS) {
                    modelManager.startDownload()
                }
            else -> Unit
        }
    }

    private fun fallback(): TTSProvider = synchronized(lock) {
        fallbackProvider ?: fallbackFactory().also { fallbackProvider = it }
    }

    private fun dropPrefetched() {
        val dropped = synchronized(lock) { prefetched.values.toList().also { prefetched.clear() } }
        dropped.forEach { it.cancel() }
    }

    private fun request(text: String, speakOptions: TTSSpeakOptions): SpeechRequest {
        val voice = KokoroVoices.find(speakOptions.voiceId) ?: defaultVoice
        val speed = (options.speed * emotionSpeed(speakOptions.emotion)).coerceIn(0.5f, 2.0f)
        return SpeechRequest(text, voice, speed, KokoroVoices.espeakLanguage(voice))
    }

    /** Remove and return the prefetched entry for [request]; entries queued before it are stale. */
    private fun takePrefetchedLocked(request: SpeechRequest): Utterance? {
        val match = prefetched[request.text] ?: return null
        val iterator = prefetched.values.iterator()
        while (iterator.hasNext()) {
            val u = iterator.next()
            iterator.remove()
            if (u === match) break
            u.cancel()
        }
        if (match.request != request) {
            match.cancel()
            return null
        }
        return match
    }

    private fun launchLocked(u: Utterance, synth: KokoroSynthesizer) {
        if (u.job != null || u.cancelled) return
        val r = u.request
        val speakerId = if (r.voice.speakerId < synth.numSpeakers) r.voice.speakerId else defaultSpeaker(synth)
        u.job = scope.launch(synthDispatcher) {
            try {
                synth.generate(r.text, speakerId, r.speed, r.language) { samples ->
                    if (u.cancelled) {
                        false
                    } else {
                        if (samples.isNotEmpty()) u.chunks.trySend(samples)
                        true
                    }
                }
                u.chunks.close()
            } catch (t: Throwable) {
                u.chunks.close(KokoroEngineFailure(t))
            }
        }.also { job ->
            // Cancelled before it ever ran: still end the stream.
            job.invokeOnCompletion { cause -> if (cause != null) u.chunks.close(cause) }
        }
    }

    private fun defaultSpeaker(synth: KokoroSynthesizer): Int =
        if (KokoroVoices.default.speakerId < synth.numSpeakers) KokoroVoices.default.speakerId else 0

    private suspend fun play(u: Utterance, sampleRate: Int) {
        var out: PcmPlayback? = null
        try {
            for (samples in u.chunks) {
                val p = out ?: openPlayback(u, sampleRate).also { out = it }
                p.write(samples)
            }
            out?.finish()
        } finally {
            out?.let { p ->
                p.stop()
                synchronized(lock) { if (playback === p) playback = null }
            }
        }
    }

    private fun openPlayback(u: Utterance, sampleRate: Int): PcmPlayback {
        val p = audioOutput.open(sampleRate)
        synchronized(lock) {
            if (u.cancelled || shutDown) {
                p.stop()
                throw CancellationException("cancelled")
            }
            playback = p
        }
        return p
    }

    private fun emotionSpeed(emotion: Emotion?): Float {
        if (emotion == null) return 1f
        val i = emotion.intensity.toFloat().coerceIn(0f, 1f)
        return when (emotion.name.lowercase(Locale.US)) {
            "happy", "excited" -> 1f + 0.08f * i
            "sad", "concerned" -> 1f - 0.08f * i
            else -> 1f
        }
    }

    private data class SpeechRequest(
        val text: String,
        val voice: KokoroVoice,
        val speed: Float,
        val language: String?,
    )

    private class Utterance(val request: SpeechRequest) {
        val chunks = Channel<FloatArray>(Channel.UNLIMITED)

        @Volatile var cancelled = false
        @Volatile var job: Job? = null

        fun cancel() {
            cancelled = true
            job?.cancel()
            chunks.cancel()
        }
    }

    private class KokoroEngineFailure(cause: Throwable) : Exception(cause)

    internal companion object {
        const val LOG_TAG = "AgentVoice"
        const val MAX_PREFETCH = 4
        const val DOWNLOAD_RETRY_AFTER_MS = 5 * 60 * 1000L

        fun newSynthesisExecutor(): ExecutorService = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "agent-kokoro-tts").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY + 1
            }
        }
    }
}
