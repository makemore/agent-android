package com.makemore.agentfrontend.configuration

import androidx.compose.ui.graphics.Color

/**
 * Configuration for the chat widget.
 * Mirrors the iOS ChatWidgetConfig struct.
 */
data class ChatWidgetConfig(
    // -- Backend Configuration --
    /** Backend API URL */
    val backendUrl: String = "http://10.0.2.2:8000",
    /** Agent identifier */
    val agentKey: String = "default-agent",

    // -- UI Configuration --
    /** Widget header title */
    val title: String = "Chat Assistant",
    /** Widget subtitle */
    val subtitle: String = "How can we help you today?",
    /** Primary theme color */
    val primaryColor: Color = Color(0xFF0066CC),
    /** Input placeholder text */
    val placeholder: String = "Type your message...",
    /** Empty state heading */
    val emptyStateTitle: String = "Start a Conversation",
    /** Empty state description */
    val emptyStateMessage: String = "Send a message to get started.",

    // -- Feature Flags --
    /** Show debug mode toggle */
    val showDebugButton: Boolean = true,
    /** Enable debug mode */
    val enableDebugMode: Boolean = true,
    /** Show TTS toggle button */
    val showTTSButton: Boolean = true,
    /** Enable text-to-speech */
    val enableTTS: Boolean = false,
    /** Enable voice input */
    val enableVoice: Boolean = true,
    /** Enable file attachments */
    val enableFiles: Boolean = true,
    /** Show model selector */
    val showModelSelector: Boolean = false,
    /** Show tasks tab */
    val showTasksTab: Boolean = true,
    /** Show system picker (settings cog) */
    val showSystemPicker: Boolean = true,
    /** Show tool call/result messages in chat (hidden when false, like web frontend) */
    val showToolMessages: Boolean = true,

    // -- Authentication --
    /** Authentication strategy */
    val authStrategy: AuthStrategy? = null,
    /** Authentication token */
    val authToken: String? = null,
    /** Custom auth header name */
    val authHeader: String? = null,
    /** Custom auth token prefix */
    val authTokenPrefix: String? = null,
    /** Anonymous token header name */
    val anonymousTokenHeader: String = "X-Anonymous-Token",

    // -- Storage Keys --
    /** Key for storing conversation ID */
    val conversationIdKey: String = "chat_widget_conversation_id",
    /** Key for storing session token */
    val sessionTokenKey: String = "chat_widget_session_token",
    /** Key for storing anonymous token */
    val anonymousTokenKey: String = "chat_widget_anonymous_token",
    /** Key for storing selected model */
    val modelKey: String = "chat_widget_selected_model",
    /** Key for storing selected system slug */
    val systemKey: String = "chat_widget_selected_system",
    /** Key for storing selected system version */
    val systemVersionKey: String = "chat_widget_selected_system_version",
    /** Key for storing selected system version ID (UUID) */
    val systemVersionIdKey: String = "chat_widget_selected_system_version_id",

    // -- API Paths --
    /** API endpoint paths */
    val apiPaths: APIPaths = APIPaths(),
    /** API case style for request/response transformation */
    val apiCaseStyle: APICaseStyle = APICaseStyle.AUTO,

    // -- Metadata --
    /** Custom metadata to send with requests */
    val metadata: Map<String, Any> = emptyMap(),
    /** Default journey type */
    val defaultJourneyType: String = "general",

    // -- TTS Configuration --
    /** TTS proxy URL for secure backend calls */
    val ttsProxyUrl: String? = null,
    /** ElevenLabs API key (direct mode only) */
    val elevenLabsApiKey: String? = null,

    // -- Callbacks --
    /** Event callback for SSE events */
    val onEvent: ((String, Map<String, Any?>) -> Unit)? = null,
    /** Auth error callback */
    val onAuthError: ((Exception) -> Unit)? = null,
    /**
     * Video full-screen toggle callback. Invoked with `true` when a `VideoBlockView`
     * enters full-screen playback and `false` when it exits. Host apps can use this
     * to manage orientation locks or other chrome. Orientation handling is
     * intentionally left to the host.
     */
    val onVideoFullScreenChange: ((Boolean) -> Unit)? = null
) {
    companion object {
        /** Create a configuration with common settings */
        fun make(
            backendUrl: String,
            agentKey: String,
            title: String = "Chat Assistant",
            primaryColor: Color = Color(0xFF0066CC)
        ): ChatWidgetConfig = ChatWidgetConfig(
            backendUrl = backendUrl,
            agentKey = agentKey,
            title = title,
            primaryColor = primaryColor
        )
    }

    /** Configure authentication */
    fun withAuth(strategy: AuthStrategy, token: String? = null): ChatWidgetConfig =
        copy(authStrategy = strategy, authToken = token)

    /** Configure UI options */
    fun withUI(showTasks: Boolean = true, showModelSelector: Boolean = false): ChatWidgetConfig =
        copy(showTasksTab = showTasks, showModelSelector = showModelSelector)
}

