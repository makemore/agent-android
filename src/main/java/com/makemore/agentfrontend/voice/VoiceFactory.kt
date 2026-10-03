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
    /**
     * Name of the on-device engine that will speak a [VoiceProviderKind.LOCAL]
     * plan (`"kokoro"`), or `null` for Android `TextToSpeech`.
     */
    val localEngine: String? = null,
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
 *   2. Local: `config.localTtsEngine` (e.g. Kokoro) when set, falling back to
 *      local-only [AndroidTTSProvider]; otherwise [AndroidTTSProvider].
 */
object VoiceFactory {
    fun plan(config: ChatWidgetConfig, apiClientAvailable: Boolean = true): VoiceProviderPlan {
        val base = basePlan(config, apiClientAvailable)
        return if (base.kind == VoiceProviderKind.LOCAL && config.localTtsEngine != null) {
            base.copy(localEngine = config.localTtsEngine.name)
        } else {
            base
        }
    }

    /**
     * Whether the Android `TextToSpeech` provider must refuse voices that need
     * the network. Always true when it stands in for a local engine: that
     * fallback exists so text never leaves the device.
     */
    internal fun androidTtsLocalOnly(config: ChatWidgetConfig, asEngineFallback: Boolean): Boolean =
        asEngineFallback || config.effectiveTtsProviderPolicy == TTSProviderPolicy.LOCAL_ONLY

    private fun basePlan(config: ChatWidgetConfig, apiClientAvailable: Boolean): VoiceProviderPlan {
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
            VoiceProviderKind.LOCAL -> {
                val engine = config.localTtsEngine
                fun androidTts(asEngineFallback: Boolean) = AndroidTTSProvider(
                    context = context,
                    // A local engine's voice ids (e.g. Kokoro's "af_heart")
                    // mean nothing to TextToSpeech; let it pick a local voice.
                    defaultVoiceId = if (asEngineFallback) null else voiceId,
                    localOnly = androidTtsLocalOnly(config, asEngineFallback),
                    enginePackageName = config.localTtsEnginePackageName,
                    preferredLocale = config.localVoiceLocale ?: java.util.Locale.getDefault(),
                    genderPreference = config.localVoiceGenderPreference,
                )
                engine?.makeProvider(
                    context,
                    LocalTTSEngineRequest(voiceId = voiceId, fallback = { androidTts(asEngineFallback = true) }),
                ) ?: androidTts(asEngineFallback = false)
            }
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
