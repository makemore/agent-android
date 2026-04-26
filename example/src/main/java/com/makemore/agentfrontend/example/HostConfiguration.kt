package com.makemore.agentfrontend.example

import com.makemore.agentfrontend.configuration.AuthStrategy
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
    val authToken: String?
) {
    data class FollowUp(val prompt: String, val delayMs: Long)

    fun makeWidgetConfig(): ChatWidgetConfig {
        val base = ChatWidgetConfig(
            backendUrl = backendUrl,
            agentKey = agentKey,
            title = "Agent Example",
            subtitle = "Streaming UI test host",
            showSystemPicker = false,
            showTasksTab = false,
            showTTSButton = false,
            enableTTS = false,
            enableVoice = false,
            enableFiles = false,
            followStreamingEnabled = true
        )
        return if (authToken != null) {
            base.copy(
                authStrategy = AuthStrategy.TOKEN,
                authToken = authToken,
                metadata = emptyMap()
            )
        } else {
            base.copy(metadata = mapOf("test_fixture" to testFixture))
        }
    }
}
