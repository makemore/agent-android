package com.makemore.agentfrontend.voice

import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Orchestrates streaming TTS for an assistant turn.
 *
 * Responsibilities:
 *   1. Buffer assistant deltas through a [SentenceChunker].
 *   2. Pipe each emitted sentence into the configured [TTSProvider],
 *      enqueued so utterances play in order.
 *   3. Publish [isSpeaking] / [isEnabled] as Compose state.
 *   4. Allow [stop] (user interrupt) and [reset] (new turn).
 *
 * The controller is provider-agnostic — pass any object implementing
 * [TTSProvider]. Build via [VoiceFactory.makeController] from a config.
 */
class VoiceController(
    private val provider: TTSProvider?,
    enabled: Boolean = true,
    initialVoiceMode: VoiceMode = if (provider == null) VoiceMode.Disabled else VoiceMode.Local,
    minChars: Int = 40,
    maxChars: Int = 240,
    private val scope: CoroutineScope = MainScope(),
) {
    /** True while a TTS utterance is actively playing. Compose-observable. */
    val isSpeaking = mutableStateOf(false)

    /** Whether voice playback is enabled. Compose-observable. */
    val isEnabled = mutableStateOf(enabled && provider != null && canEnable(initialVoiceMode))

    /** Remote/local/unavailable/disabled status for host UI. */
    val voiceMode = mutableStateOf(initialVoiceMode)

    /**
     * Rolling buffer of text recently queued for TTS playback. Used by
     * the [com.makemore.agentfrontend.ui.InputView] barge-in monitor to
     * filter out the agent's own voice leak-back from speech-recognizer
     * partials. Cap keeps memory bounded (~5 sentences). Cleared on
     * [stop] / [reset].
     */
    val recentSpokenText = mutableStateOf("")
    private val recentSpokenCapacity = 1500

    private val queue = ArrayDeque<String>()
    private var drainJob: Job? = null
    private var currentEmotion: Emotion? = null
    private val chunker = SentenceChunker(minChars, maxChars) { text -> enqueue(text) }

    // -- Public API ------------------------------------------------

    /** Push a delta from `assistant.delta`. */
    fun pushDelta(delta: String, emotion: Emotion? = null) {
        if (!isEnabled.value || delta.isEmpty()) return
        if (emotion != null) currentEmotion = emotion
        chunker.push(delta)
    }

    /**
     * Signal the assistant turn is complete. Flushes the chunker so any
     * trailing fragment gets spoken. Pass [finalText] to play the
     * authoritative content when no deltas were received.
     */
    fun finishTurn(finalText: String? = null, emotion: Emotion? = null) {
        if (!isEnabled.value) return
        if (emotion != null) currentEmotion = emotion
        if (finalText != null && queue.isEmpty() && !isSpeaking.value && drainJob == null) {
            chunker.reset()
            val cleaned = SentenceChunker.sanitizeForSpeech(finalText)
            if (cleaned.isNotEmpty()) enqueue(cleaned)
        } else {
            chunker.flush()
        }
    }

    /** Stop in-flight playback and clear pending chunks. */
    fun stop() {
        queue.clear()
        chunker.reset()
        drainJob?.cancel()
        drainJob = null
        provider?.cancel()
        if (isSpeaking.value) isSpeaking.value = false
        recentSpokenText.value = ""
    }

    /**
     * Clear emotion + buffer at the start of a new assistant turn. Does
     * not stop in-flight playback — call [stop] for that.
     */
    fun reset() {
        currentEmotion = null
        chunker.reset()
        recentSpokenText.value = ""
    }

    /** Toggle speech on/off. Disabling stops any current playback. */
    fun setEnabled(enabled: Boolean) {
        isEnabled.value = enabled && provider != null && canEnable(voiceMode.value)
        if (!enabled) stop()
    }

    /** Tear down the provider's native resources (TextToSpeech etc.). */
    fun dispose() {
        stop()
        provider?.shutdown()
        scope.cancel()
    }

    // -- Internals -------------------------------------------------

    private fun enqueue(text: String) {
        if (text.isEmpty()) return
        queue.addLast(text)
        if (drainJob == null) startDrain()
    }

    private fun startDrain() {
        drainJob = scope.launch { runDrainLoop() }
    }

    /**
     * Append [text] to the rolling buffer of recently queued speech.
     * Called from the drain loop *before* [TTSProvider.speak] so the
     * barge-in filter has the matching tokens by the time the audio
     * leaks back into the mic.
     */
    private fun appendRecentSpoken(text: String) {
        val combined = (recentSpokenText.value + " " + text).trimStart()
        recentSpokenText.value = if (combined.length > recentSpokenCapacity) {
            combined.takeLast(recentSpokenCapacity)
        } else {
            combined
        }
    }

    private suspend fun runDrainLoop() {
        try {
            while (queue.isNotEmpty()) {
                val text = queue.removeFirst()
                appendRecentSpoken(text)
                if (!isSpeaking.value) isSpeaking.value = true
                val activeProvider = provider
                if (activeProvider == null) {
                    queue.clear()
                    break
                }
                val opts = TTSSpeakOptions(emotion = currentEmotion)
                try {
                    activeProvider.speak(text, opts)
                } catch (ce: CancellationException) {
                    queue.clear()
                    throw ce
                } catch (t: Throwable) {
                    // Non-cancel error: drop the queue so the user isn't
                    // bombarded by stale audio after recovery, but keep
                    // the controller usable for the next turn.
                    voiceMode.value = VoiceMode.Unavailable(t.message ?: "Voice output unavailable")
                    isEnabled.value = false
                    queue.clear()
                    break
                }
            }
        } finally {
            if (isSpeaking.value) isSpeaking.value = false
            drainJob = null
        }
    }

    private fun canEnable(mode: VoiceMode): Boolean = when (mode) {
        VoiceMode.Remote, VoiceMode.Local -> true
        VoiceMode.Disabled -> false
        is VoiceMode.Unavailable -> false
    }
}
