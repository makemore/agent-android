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

    /**
     * True while the playback in flight came from an explicit per-message tap
     * ([speakOnce]) rather than from auto-play.
     *
     * The composer reads [isSpeaking] to turn its send button into "Stop
     * speaking" and to arm barge-in monitoring — both of which are about the
     * AGENT'S TURN being read aloud. A one-off tap on a scrollback message is
     * not that, and hijacking the composer for it makes the two buttons feel
     * wired together.
     */
    val isOneOffPlayback = mutableStateOf(false)

    /** Whether voice playback is possible at all — provider present, mode
     *  healthy, not muted by the host. Capability, NOT the user's auto-play
     *  preference; see [autoSpeakReplies]. */
    val isEnabled = mutableStateOf(enabled && provider != null && canEnable(initialVoiceMode))

    /**
     * Whether assistant turns should be read aloud AS THEY STREAM.
     *
     * Separate from [isEnabled] on purpose, mirroring iOS. Conflating the two
     * is what wired the composer's "read replies aloud" button to the
     * per-message speaker button: the per-message tap needed the controller
     * enabled, enabling it turned on auto-play, and the next turn started
     * reading itself. Capability and preference are different questions.
     *
     * Defaults off, as on iOS — replies are read aloud only when asked for.
     */
    val autoSpeakReplies = mutableStateOf(false)

    /** Remote/local/unavailable/disabled status for host UI. */
    val voiceMode = mutableStateOf(initialVoiceMode)

    /**
     * The mode this controller was built with, kept so an explicit re-enable
     * can restore it after a provider failure latched [VoiceMode.Unavailable].
     * Held rather than re-derived so a remote provider is not silently
     * downgraded to local on recovery.
     */
    private val healthyVoiceMode: VoiceMode = initialVoiceMode

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
        // Streaming playback is the auto-play path, so it needs the
        // preference as well as the capability. [speakOnce] deliberately
        // bypasses this — an explicit tap is not auto-play.
        if (!isEnabled.value || !autoSpeakReplies.value || delta.isEmpty()) return
        if (emotion != null) currentEmotion = emotion
        chunker.push(delta)
    }

    /**
     * Signal the assistant turn is complete. Flushes the chunker so any
     * trailing fragment gets spoken. Pass [finalText] to play the
     * authoritative content when no deltas were received.
     */
    fun finishTurn(finalText: String? = null, emotion: Emotion? = null) {
        if (!isEnabled.value || !autoSpeakReplies.value) return
        if (emotion != null) currentEmotion = emotion
        if (finalText != null && queue.isEmpty() && !isSpeaking.value && drainJob == null) {
            chunker.reset()
            val cleaned = SentenceChunker.sanitizeForSpeech(finalText)
            if (cleaned.isNotEmpty()) enqueue(cleaned)
        } else {
            chunker.flush()
        }
    }

    /**
     * Speak one message on explicit request, leaving [isEnabled] alone.
     *
     * The per-message speaker button is an instruction about THIS message. It
     * must not flip the composer's "read replies aloud" toggle, or tapping it
     * silently signs the user up for every future turn being read aloud — the
     * symptom being that the big speaker lights up and the agent's next turn
     * starts playing on its own.
     *
     * iOS has no such global toggle (its play button is free to call
     * `setEnabled(true)`), which is why the wiring could not be copied across
     * verbatim.
     */
    fun speakOnce(text: String) {
        if (provider == null) {
            android.util.Log.w(LOG_TAG, "speakOnce: no TTS provider resolved")
            return
        }
        // An explicit play is also the "try again" after a provider failure
        // latched Unavailable — same reasoning as [setEnabled], without the
        // global side effect.
        if (voiceMode.value is VoiceMode.Unavailable && canEnable(healthyVoiceMode)) {
            voiceMode.value = healthyVoiceMode
        }
        stop()
        val cleaned = SentenceChunker.sanitizeForSpeech(text)
        if (cleaned.isEmpty()) return
        // After stop(), which clears it.
        isOneOffPlayback.value = true
        enqueue(cleaned)
    }

    /** Stop in-flight playback and clear pending chunks. */
    fun stop() {
        queue.clear()
        chunker.reset()
        drainJob?.cancel()
        drainJob = null
        provider?.cancel()
        if (isSpeaking.value) isSpeaking.value = false
        if (isOneOffPlayback.value) isOneOffPlayback.value = false
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

    /**
     * Toggle speech on/off. Disabling stops any current playback.
     *
     * Enabling also clears a [VoiceMode.Unavailable] latch. A provider failure
     * in [runDrainLoop] sets that mode and drops [isEnabled], and because
     * [canEnable] refuses `Unavailable`, every later enable attempt was
     * recomputing to `false` — so once a single utterance failed, playback
     * could not come back for the rest of the process. An explicit enable is
     * the user asking to try again, which is how iOS treats it.
     */
    fun setEnabled(enabled: Boolean) {
        if (enabled && voiceMode.value is VoiceMode.Unavailable && canEnable(healthyVoiceMode)) {
            voiceMode.value = healthyVoiceMode
        }
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
                    android.util.Log.w(LOG_TAG, "playback aborted: provider went null")
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
                    // Logged, not swallowed: this used to fail silently, so a
                    // backend refusal, an unusable key and a broken button all
                    // looked identical from the outside.
                    android.util.Log.w(LOG_TAG, "TTS playback failed: ${t.javaClass.simpleName}: ${t.message}")
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
            if (isOneOffPlayback.value) isOneOffPlayback.value = false
            drainJob = null
        }
    }

    private fun canEnable(mode: VoiceMode): Boolean = when (mode) {
        VoiceMode.Remote, VoiceMode.Local -> true
        VoiceMode.Disabled -> false
        is VoiceMode.Unavailable -> false
    }
}

private const val LOG_TAG = "AgentVoice"
