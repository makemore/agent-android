package com.makemore.agentfrontend.voice

import android.content.Context
import com.makemore.agentfrontend.configuration.ChatWidgetConfig
import com.makemore.agentfrontend.networking.APIClient
import com.makemore.agentfrontend.voice.providers.AndroidTTSProvider
import com.makemore.agentfrontend.voice.providers.ElevenLabsTTSProvider

/**
 * Factory for building a default [TTSProvider] from the widget config +
 * [APIClient].
 *
 * Resolution order:
 *   1. ElevenLabs proxy when `apiPaths.voiceToken` is set.
 *   2. [AndroidTTSProvider] (always available on Android API 21+).
 */
object VoiceFactory {
    fun makeDefaultProvider(
        context: Context,
        config: ChatWidgetConfig,
        apiClient: APIClient,
        voiceId: String? = null,
        modelId: String? = null,
    ): TTSProvider {
        return if (config.apiPaths.voiceToken != null) {
            ElevenLabsTTSProvider(
                apiClient = apiClient,
                defaultVoiceId = voiceId,
                defaultModelId = modelId,
            )
        } else {
            AndroidTTSProvider(context = context, defaultVoiceId = voiceId)
        }
    }

    /**
     * Build a configured [VoiceController] ready to wire into a
     * `ChatViewModel`. `enableTTS` is mirrored into the controller so
     * the widget's toggle drives playback on/off.
     */
    fun makeController(
        context: Context,
        config: ChatWidgetConfig,
        apiClient: APIClient,
        voiceId: String? = null,
        modelId: String? = null,
    ): VoiceController {
        val provider = makeDefaultProvider(context, config, apiClient, voiceId, modelId)
        return VoiceController(provider = provider, enabled = config.enableTTS)
    }
}
