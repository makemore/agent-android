package com.makemore.agentfrontend.voice

import android.content.Context
import com.makemore.agentfrontend.configuration.ChatWidgetConfig
import com.makemore.agentfrontend.networking.APIClient
import com.makemore.agentfrontend.voice.providers.AndroidTTSProvider
import com.makemore.agentfrontend.voice.providers.ElevenLabsTTSProvider

enum class VoiceProviderKind { REMOTE, LOCAL, NONE }

data class VoiceProviderPlan(
    val kind: VoiceProviderKind,
    val mode: VoiceMode,
)

data class VoiceProviderResolution(
    val provider: TTSProvider?,
    val mode: VoiceMode,
)

/**
 * Factory for building a default [TTSProvider] from the widget config +
 * [APIClient].
 *
 * Resolution order:
 *   1. ElevenLabs proxy when `apiPaths.voiceToken` is set.
 *   2. [AndroidTTSProvider] (always available on Android API 21+).
 */
object VoiceFactory {
    fun plan(config: ChatWidgetConfig, apiClientAvailable: Boolean = true): VoiceProviderPlan {
        if (config.ttsProviderPolicy == TTSProviderPolicy.DISABLED) {
            return VoiceProviderPlan(VoiceProviderKind.NONE, VoiceMode.Disabled)
        }
        if (config.privateOnly && config.ttsProviderPolicy == TTSProviderPolicy.REMOTE) {
            return VoiceProviderPlan(
                VoiceProviderKind.NONE,
                VoiceMode.Unavailable("Remote voice is disabled in Protected AI Mode"),
            )
        }
        return when (config.effectiveTtsProviderPolicy) {
            TTSProviderPolicy.LOCAL_ONLY -> VoiceProviderPlan(VoiceProviderKind.LOCAL, VoiceMode.Local)
            TTSProviderPolicy.REMOTE -> {
                if (apiClientAvailable && config.apiPaths.voiceToken != null) {
                    VoiceProviderPlan(VoiceProviderKind.REMOTE, VoiceMode.Remote)
                } else {
                    VoiceProviderPlan(
                        VoiceProviderKind.NONE,
                        VoiceMode.Unavailable("Remote voice endpoint is not configured"),
                    )
                }
            }
            TTSProviderPolicy.AUTOMATIC -> {
                if (apiClientAvailable && config.apiPaths.voiceToken != null) {
                    VoiceProviderPlan(VoiceProviderKind.REMOTE, VoiceMode.Remote)
                } else {
                    VoiceProviderPlan(VoiceProviderKind.LOCAL, VoiceMode.Local)
                }
            }
            TTSProviderPolicy.DISABLED -> VoiceProviderPlan(VoiceProviderKind.NONE, VoiceMode.Disabled)
        }
    }

    fun resolveProvider(
        context: Context,
        config: ChatWidgetConfig,
        apiClient: APIClient?,
        voiceId: String? = null,
        modelId: String? = null,
    ): VoiceProviderResolution {
        val plan = plan(config, apiClientAvailable = apiClient != null)
        val provider = when (plan.kind) {
            VoiceProviderKind.REMOTE -> ElevenLabsTTSProvider(
                apiClient = requireNotNull(apiClient),
                defaultVoiceId = voiceId,
                defaultModelId = modelId,
            )
            VoiceProviderKind.LOCAL -> AndroidTTSProvider(
                context = context,
                defaultVoiceId = voiceId,
                localOnly = config.effectiveTtsProviderPolicy == TTSProviderPolicy.LOCAL_ONLY,
                enginePackageName = config.localTtsEnginePackageName,
                preferredLocale = config.localVoiceLocale ?: java.util.Locale.getDefault(),
                genderPreference = config.localVoiceGenderPreference,
            )
            VoiceProviderKind.NONE -> null
        }
        return VoiceProviderResolution(provider = provider, mode = plan.mode)
    }

    fun makeDefaultProvider(
        context: Context,
        config: ChatWidgetConfig,
        apiClient: APIClient?,
        voiceId: String? = null,
        modelId: String? = null,
    ): TTSProvider? {
        return resolveProvider(context, config, apiClient, voiceId, modelId).provider
    }

    /**
     * Build a configured [VoiceController] ready to wire into a
     * `ChatViewModel`. `enableTTS` is mirrored into the controller so
     * the widget's toggle drives playback on/off.
     */
    fun makeController(
        context: Context,
        config: ChatWidgetConfig,
        apiClient: APIClient?,
        voiceId: String? = null,
        modelId: String? = null,
    ): VoiceController {
        val resolved = resolveProvider(context, config, apiClient, voiceId, modelId)
        return VoiceController(
            provider = resolved.provider,
            enabled = config.enableTTS,
            initialVoiceMode = resolved.mode,
        )
    }
}
