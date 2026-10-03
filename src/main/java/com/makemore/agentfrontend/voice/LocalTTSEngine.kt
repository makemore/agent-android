package com.makemore.agentfrontend.voice

import android.content.Context

/**
 * A pluggable on-device speech engine used in place of Android
 * `TextToSpeech` whenever [VoiceFactory] resolves a local voice.
 *
 * Optional artifacts implement this so the core widget does not have to
 * ship their native libraries — for example `agent-kokoro`'s
 * `KokoroTTS.engine(context)` (engine name `"kokoro"`). Set it on
 * `ChatWidgetConfig.localTtsEngine`.
 *
 * Contract: an engine is *local*. Its provider must never send assistant
 * text off the device; it may fetch its own model files. When it cannot
 * speak it should hand the utterance to [LocalTTSEngineRequest.fallback].
 */
interface LocalTTSEngine {
    /** Stable engine identifier shared across platforms, e.g. `"kokoro"`. */
    val name: String

    /** Build a provider for one [VoiceController]. Called on the main thread; must not block. */
    fun makeProvider(context: Context, request: LocalTTSEngineRequest): TTSProvider
}

/** What [VoiceFactory] hands a [LocalTTSEngine] when building a provider. */
class LocalTTSEngineRequest(
    /** `ChatWidgetConfig.voiceId`. Engines ignore ids they do not recognise. */
    val voiceId: String?,
    /**
     * Builds the system-voice provider ([com.makemore.agentfrontend.voice.providers.AndroidTTSProvider],
     * restricted to voices that need no network) used when the engine is
     * unavailable. Called lazily, at most once per provider is expected.
     */
    val fallback: () -> TTSProvider,
)
