package com.makemore.agentfrontend.example

import androidx.compose.ui.graphics.Color
import com.makemore.agentfrontend.configuration.AuthStrategy
import com.makemore.agentfrontend.configuration.ChatAppearance
import com.makemore.agentfrontend.configuration.ChatWidgetConfig

/**
 * Mirror of `HostConfiguration` in the iOS Example app. Bundles the bits
 * of state a single chat session needs — backend URL, agent key, optional
 * stub fixture name, optional DRF token — and turns them into a
 * [ChatWidgetConfig] for the library's `ChatWidgetView` composable.
 *
 * The Android library doesn't read process environment the way the iOS
 * one does (the host app drives everything explicitly through Composable
 * params), so this is a plain data class — no fromEnvironment helper.
 */
data class HostConfiguration(
    val backendUrl: String,
    val agentKey: String,
    /** When non-empty, sent as `metadata.test_fixture` so the local stub
     *  server replays a canned SSE script for that name. */
    val testFixture: String,
    val autoSendOnLaunch: Boolean,
    val autoSendPrompt: String,
    /** Scripted user turns fired one at a time, after the previous run
     *  finishes streaming. Mirrors the iOS `autoSendFollowUps` field. */
    val autoSendFollowUps: List<FollowUp>,
    /** When non-null the widget is configured for DRF token auth against
     *  a real Django backend. Otherwise it falls back to the unauth-stub
     *  flow (anonymous + `metadata.test_fixture`). */
    val authToken: String?,
    /** Turn TTS playback on by default (mirrors `ChatWidgetConfig.enableTTS`).
     *  The speaker icon in the chat header still lets the user toggle it. */
    val enableTTS: Boolean = false,
    /** Show the mic button in the input row and route speech results
     *  into the text field (mirrors `ChatWidgetConfig.enableVoice`). */
    val enableVoice: Boolean = false,
    /** Drives the warm-dark shell: rounded composer card, greeting
     *  empty state, slide-in sidebar. When `false` the scenario uses
     *  the legacy classic composer + plain empty state so we can A/B
     *  old vs new. */
    val anthropicShell: Boolean = true,
    /** Optional first name woven into the greeting (e.g. "Chris" →
     *  "Good afternoon, Chris"). When nil the greeting omits the name. */
    val userName: String? = null,
) {
    data class FollowUp(val prompt: String, val delayMs: Long)

    fun makeWidgetConfig(): ChatWidgetConfig {
        var cfg = ChatWidgetConfig(
            backendUrl = backendUrl,
            agentKey = agentKey,
            title = "Agent Example",
            subtitle = "Streaming UI test host",
            showSystemPicker = false,
            showTasksTab = false,
            // Show the speaker toggle whenever TTS is enabled for this host
            // so the developer can flick playback off mid-stream from the header.
            showTTSButton = enableTTS,
            enableTTS = enableTTS,
            enableVoice = enableVoice,
            enableFiles = true,
            followStreamingEnabled = true,
        )
        if (anthropicShell) {
            // Library default already enables the warm-dark appearance,
            // greeting, and sidebar — just personalise so the demo
            // picks up the user's name and shows the S'Ai placeholder.
            cfg = cfg.copy(
                appearance = cfg.appearance.copy(
                    composerStyle = ChatAppearance.ComposerStyle.ANTHROPIC,
                    modelPillLabel = "S'Ai",
                ),
                greeting = cfg.greeting.copy(enabled = true, userName = userName),
                sidebar = cfg.sidebar.copy(
                    enabled = true,
                    footerInitials = userName?.take(1)?.uppercase(),
                    footerCaption = userName,
                ),
                placeholder = "Chat with S'Ai",
            )
        } else {
            // Opt out of the new baseline so the legacy scenarios keep
            // their original look while we iterate on the redesign.
            cfg = cfg.copy(
                appearance = ChatAppearance.classic(),
                greeting = cfg.greeting.copy(enabled = false),
                sidebar = cfg.sidebar.copy(enabled = false),
                placeholder = "Type your message...",
                primaryColor = Color(0xFF4A6B8E),
            )
        }
        return if (authToken != null) {
            cfg.copy(
                authStrategy = AuthStrategy.TOKEN,
                authToken = authToken,
                metadata = emptyMap(),
            )
        } else {
            cfg.copy(metadata = mapOf("test_fixture" to testFixture))
        }
    }
}
