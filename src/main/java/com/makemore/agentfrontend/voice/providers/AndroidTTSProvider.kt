package com.makemore.agentfrontend.voice.providers

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.makemore.agentfrontend.voice.AndroidVoiceCandidate
import com.makemore.agentfrontend.voice.Emotion
import com.makemore.agentfrontend.voice.LocalVoiceGenderPreference
import com.makemore.agentfrontend.voice.LocalVoiceSelector
import com.makemore.agentfrontend.voice.TTSProvider
import com.makemore.agentfrontend.voice.TTSSpeakOptions
import com.makemore.agentfrontend.voice.VoiceDescriptor
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * On-device TTS provider built on [TextToSpeech].
 *
 * Free, no backend round-trip. Used as a fallback when no ElevenLabs
 * proxy is configured. Voice quality varies by OEM and the user's
 * installed engines.
 *
 * Implements the same surface as [ElevenLabsTTSProvider] so the
 * controller can swap them transparently.
 */
class AndroidTTSProvider(
    context: Context,
    private val defaultVoiceId: String? = null,
    private val baseRate: Float = 1.0f,
    private val basePitch: Float = 1.0f,
    private val localOnly: Boolean = false,
    private val enginePackageName: String? = null,
    private val preferredLocale: Locale = Locale.getDefault(),
    private val genderPreference: LocalVoiceGenderPreference = LocalVoiceGenderPreference.MALE,
) : TTSProvider {
    override val name: String = "android-tts"

    private val ready = CompletableDeferred<Boolean>()
    private val tts: TextToSpeech = if (enginePackageName.isNullOrBlank()) {
        TextToSpeech(context.applicationContext) { status ->
            ready.complete(status == TextToSpeech.SUCCESS)
        }
    } else {
        TextToSpeech(context.applicationContext, { status ->
            ready.complete(status == TextToSpeech.SUCCESS)
        }, enginePackageName)
    }
    private val nextId = AtomicLong(1)

    /**
     * Continuation for the in-flight utterance, keyed by its TTS engine
     * `utteranceId`. Stored separately so a stale callback for a
     * cancelled utterance doesn't resume the new one.
     */
    private var pending: Pair<String, CancellableContinuation<Unit>>? = null

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { /* no-op */ }
            override fun onDone(utteranceId: String?) {
                resume(utteranceId, error = null)
            }
            @Deprecated("Required override on older API levels")
            override fun onError(utteranceId: String?) {
                resume(utteranceId, error = RuntimeException("TTS error"))
            }
            override fun onError(utteranceId: String?, errorCode: Int) {
                resume(utteranceId, error = RuntimeException("TTS error code=$errorCode"))
            }
            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                resume(utteranceId, error = if (interrupted) kotlinx.coroutines.CancellationException("interrupted") else null)
            }
        })
    }

    override suspend fun speak(text: String, options: TTSSpeakOptions) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        if (!ready.await()) throw RuntimeException("TextToSpeech engine failed to initialise")

        applyEmotion(options.emotion)
        applyVoice(options.voiceId ?: defaultVoiceId)

        val id = "utt-${nextId.getAndIncrement()}"
        suspendCancellableCoroutine { cont: CancellableContinuation<Unit> ->
            pending = id to cont
            cont.invokeOnCancellation { runCatching { tts.stop() } }
            val params = Bundle()
            val rc = tts.speak(trimmed, TextToSpeech.QUEUE_FLUSH, params, id)
            if (rc != TextToSpeech.SUCCESS) {
                pending = null
                cont.resumeWithException(RuntimeException("TextToSpeech.speak() returned $rc"))
            }
        }
    }

    override fun cancel() {
        runCatching { tts.stop() }
        // onStop callback delivers cancellation to any pending continuation.
    }

    override suspend fun listVoices(): List<VoiceDescriptor> {
        if (!ready.await()) return emptyList()
        return tts.voices
            ?.filter { !localOnly || !it.isNetworkConnectionRequired }
            ?.map { v ->
            VoiceDescriptor(
                id = v.name,
                name = v.name,
                labels = mapOf(
                    "lang" to v.locale.toLanguageTag(),
                    "quality" to v.quality.toString(),
                    "networkRequired" to v.isNetworkConnectionRequired.toString(),
                ),
            )
        } ?: emptyList()
    }

    override fun shutdown() {
        runCatching { tts.stop() }
        runCatching { tts.shutdown() }
    }

    // -- Internals ----------------------------------------------

    private fun resume(utteranceId: String?, error: Throwable?) {
        val (id, cont) = pending ?: return
        if (utteranceId != id) return
        pending = null
        if (cont.isActive) {
            if (error == null) cont.resume(Unit) else cont.resumeWithException(error)
        }
    }

    private fun applyVoice(voiceId: String?) {
        if (localOnly) {
            val voices = tts.voices?.map { v ->
                AndroidVoiceCandidate(
                    name = v.name,
                    locale = v.locale,
                    quality = v.quality,
                    isNetworkConnectionRequired = v.isNetworkConnectionRequired,
                    features = v.features.orEmpty(),
                )
            }.orEmpty()
            val selected = LocalVoiceSelector.chooseLocalVoice(voices, voiceId, preferredLocale, genderPreference)
                ?: throw NoLocalTTSVoiceException()
            tts.voices?.firstOrNull { it.name == selected.name }?.let { tts.voice = it }
            return
        }
        if (voiceId == null) {
            tts.language = Locale.getDefault()
            return
        }
        val match = tts.voices?.firstOrNull { it.name == voiceId }
        if (match != null) tts.voice = match
        else tts.language = Locale.getDefault()
    }

    class NoLocalTTSVoiceException : RuntimeException("Voice unavailable because no local voice is installed")

    /**
     * Coarse mapping — bigger pitch + slight rate bump for upbeat
     * emotions, calmer + slower for downbeat. Android TTS doesn't
     * expose anything richer than rate/pitch.
     */
    private fun applyEmotion(emotion: Emotion?) {
        if (emotion == null) {
            tts.setSpeechRate(baseRate)
            tts.setPitch(basePitch)
            return
        }
        val i = emotion.intensity.toFloat()
        when (emotion.name.lowercase(Locale.US)) {
            "happy", "excited" -> {
                tts.setPitch((basePitch + 0.3f * i).coerceIn(0.5f, 2.0f))
                tts.setSpeechRate((baseRate + 0.1f * i).coerceIn(0.5f, 2.0f))
            }
            "sad", "concerned" -> {
                tts.setPitch((basePitch - 0.2f * i).coerceIn(0.5f, 2.0f))
                tts.setSpeechRate((baseRate - 0.1f * i).coerceIn(0.5f, 2.0f))
            }
            else -> { tts.setPitch(basePitch); tts.setSpeechRate(baseRate) }
        }
    }
}
