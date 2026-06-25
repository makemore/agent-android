package com.makemore.agentfrontend.voice

/** Policy used to choose how assistant text is converted to speech. */
enum class TTSProviderPolicy {
    /** Backwards-compatible behavior; private/protected mode biases local-only. */
    AUTOMATIC,
    /** Remote/provider-backed TTS only. Blocked in private-only mode. */
    REMOTE,
    /** Android TextToSpeech/local voices only; never sends text to the network. */
    LOCAL_ONLY,
    /** Do not create a voice provider. */
    DISABLED,
}

/** Policy used to choose speech-recognition privacy behavior for mic input. */
enum class SpeechInputPolicy {
    /** Backwards-compatible behavior; private/protected mode biases local-only. */
    AUTOMATIC,
    /** Allow platform remote speech recognition when the OS chooses it. */
    REMOTE,
    /** Require on-device/offline recognition. Disable mic if unavailable. */
    LOCAL_ONLY,
    /** Hide/disable mic input. */
    DISABLED,
}

/** Best-effort gender preference for Android on-device TTS voices. */
enum class LocalVoiceGenderPreference {
    /** Do not prefer voices by gender. */
    ANY,
    /** Prefer local voices whose engine metadata/name suggests a male voice. */
    MALE,
    /** Prefer local voices whose engine metadata/name suggests a female voice. */
    FEMALE,
}

/** Host-visible voice output status. */
sealed class VoiceMode {
    data object Remote : VoiceMode()
    data object Local : VoiceMode()
    data class Unavailable(val reason: String) : VoiceMode()
    data object Disabled : VoiceMode()
}

/**
 * Affective metadata attached to an assistant message or delta.
 *
 * Mirrors `agent_runtime_core.interfaces.Emotion` on the Python side and
 * the iOS / web `Emotion` types. Voice providers map [name] / [intensity]
 * onto their own controls (ElevenLabs voice settings, Android
 * `TextToSpeech` rate/pitch, etc.).
 */
data class Emotion(
    /** Provider-neutral label, e.g. `"happy"`, `"sad"`, `"excited"`. */
    val name: String,
    /** 0.0 - 1.0 strength hint. Defaults to 0.5 when not supplied. */
    val intensity: Double = 0.5,
    /** Free-form provider-specific extras (SSML hints, style ids, etc.). */
    val metadata: Map<String, Any?> = emptyMap()
) {
    companion object {
        /**
         * Build from the map carried in the SSE payload's `emotion` key.
         * Returns `null` when [payload] is not a map or the `name` is
         * missing/empty.
         */
        @Suppress("UNCHECKED_CAST")
        fun from(payload: Any?): Emotion? {
            val dict = payload as? Map<String, Any?> ?: return null
            val name = (dict["name"] as? String)?.takeIf { it.isNotEmpty() } ?: return null
            val intensity = when (val v = dict["intensity"]) {
                is Number -> v.toDouble().coerceIn(0.0, 1.0)
                else -> 0.5
            }
            val metadata = (dict["metadata"] as? Map<String, Any?>) ?: emptyMap()
            return Emotion(name = name, intensity = intensity, metadata = metadata)
        }
    }
}

/**
 * Descriptor for a voice that the configured provider can speak in.
 *
 * Returned by [TTSProvider.listVoices] and the backend `/voice/voices/`
 * endpoint.
 */
data class VoiceDescriptor(
    val id: String,
    val name: String,
    /** Optional provider-specific labels (gender, accent, age, ...). */
    val labels: Map<String, String>? = null
)

/**
 * Short-lived bearer + URL pair returned by the Django voice token mint.
 */
data class VoiceToken(
    val token: String,
    val ttsUrl: String,
    /** Epoch millis at which the bearer becomes invalid. */
    val expiresAtMillis: Long
)

/**
 * Per-utterance overrides passed into [TTSProvider.speak].
 *
 * Any field left `null` falls back to the provider's constructor default.
 */
data class TTSSpeakOptions(
    val voiceId: String? = null,
    val modelId: String? = null,
    val voiceSettings: Map<String, Any?>? = null,
    val emotion: Emotion? = null
)
