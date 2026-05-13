package com.makemore.agentfrontend.voice

/**
 * Modular TTS surface — implement this to plug a new voice backend into
 * [VoiceController]. Mirrors the JS / Swift `TTSProvider` shape so the
 * three platforms behave identically.
 *
 * Implementations must be safe to call from a coroutine on
 * `Dispatchers.Main` ([VoiceController] always invokes them from the
 * main scope) but may switch to IO internally for network/audio work.
 */
interface TTSProvider {
    /** Stable identifier for logging / config selection. */
    val name: String

    /**
     * Speak [text]. Suspends until playback ends. Throws
     * [kotlinx.coroutines.CancellationException] on cooperative cancel.
     */
    suspend fun speak(text: String, options: TTSSpeakOptions = TTSSpeakOptions())

    /** Stop any in-flight or queued utterance immediately. */
    fun cancel()

    /**
     * List the voices the provider exposes. Returns an empty list when
     * the provider is local (e.g. Android `TextToSpeech`) and the host
     * should consult the system APIs directly.
     */
    suspend fun listVoices(): List<VoiceDescriptor> = emptyList()

    /**
     * Release any held native resources. The controller calls this when
     * the user closes the chat / disposes the view model. Most providers
     * can leave this as a no-op.
     */
    fun shutdown() {}
}
