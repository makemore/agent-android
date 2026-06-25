package com.makemore.agentfrontend.configuration

import androidx.compose.ui.graphics.Color
import com.makemore.agentfrontend.voice.LocalVoiceGenderPreference
import com.makemore.agentfrontend.voice.SpeechInputPolicy
import com.makemore.agentfrontend.voice.TTSProviderPolicy
import java.util.Locale

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
    /** Primary theme color. Default matches the warm-dark coral
     *  accent so the send button blends with `appearance.accent`. */
    val primaryColor: Color = Color(0xFFD97757),
    /** Input placeholder text */
    val placeholder: String = "How can I help you today?",
    /** Empty state heading */
    val emptyStateTitle: String = "Start a Conversation",
    /** Empty state description */
    val emptyStateMessage: String = "Send a message to get started.",

    // -- Appearance / Branding --
    /** Visual tokens (palette, typography, composer style, brand
     *  mark). Defaults to the warm-dark `anthropic` look so the
     *  bundled widget renders the high-end baseline out of the box.
     *  Set to [ChatAppearance.classic] to restore the pre-redesign
     *  look. */
    val appearance: ChatAppearance = ChatAppearance.anthropic(),
    /** Empty-state greeting (e.g. "Good afternoon, Chris"). Opt-in
     *  on the data type so direct consumers of `MessageListView`
     *  see no change; the bundled `ChatWidgetView` enables it. */
    val greeting: ChatGreetingConfig = ChatGreetingConfig(enabled = true),
    /** Slide-in conversation sidebar. Same opt-in pattern as
     *  [greeting]. */
    val sidebar: ChatSidebarConfig = ChatSidebarConfig(enabled = true),

    /**
     * Render the library's built-in top bar (hamburger + new-chat
     * pencil) above the message list. Set to `false` when the host
     * app provides its own navigation chrome; in that case the host
     * is responsible for surfacing equivalents (open sidebar, start
     * new chat) via its own UI. Default `true`.
     *
     * Note: when this is `false` there is no built-in affordance to
     * open the slide-in sidebar, so hosts that hide the top bar
     * typically also set `sidebar.enabled = false` and render their
     * own drawer using `ChatAppearance` tokens for cohesion.
     */
    val showInternalTopBar: Boolean = true,
    /**
     * Render the pencil "new chat" button on the right of the
     * internal top bar. Only meaningful when [showInternalTopBar]
     * is `true`. Set to `false` so the host owns the "new chat"
     * placement entirely — its own button should call
     * [com.makemore.agentfrontend.viewmodels.ChatViewModel.clearMessages].
     * Default `true`.
     */
    val showNewChatButton: Boolean = true,
    /**
     * Render the S'Ai presence orb as a small avatar at the leading
     * edge of each assistant message. The latest assistant message
     * glows softly while TTS playback is in flight. Set to `false`
     * when the host wants to place the orb in its own chrome (top
     * bar, splash, etc.) using the public `PresenceOrbView` directly,
     * or when no agent-identity affordance is wanted in the
     * scrollback at all. Default `true`.
     */
    val showPresenceOrb: Boolean = true,

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
    /** Policy for choosing remote vs local/system TTS. In private-only mode,
     *  [TTSProviderPolicy.AUTOMATIC] resolves to [TTSProviderPolicy.LOCAL_ONLY]. */
    val ttsProviderPolicy: TTSProviderPolicy = TTSProviderPolicy.AUTOMATIC,
    /** Policy for speech input privacy. In private-only mode,
     *  [SpeechInputPolicy.AUTOMATIC] resolves to [SpeechInputPolicy.LOCAL_ONLY]. */
    val speechInputPolicy: SpeechInputPolicy = SpeechInputPolicy.AUTOMATIC,
    /** Enable file attachments */
    val enableFiles: Boolean = true,
    /** Gates the composer model selector. When `true` the Anthropic
     *  composer renders the model pill — the entry point to the model
     *  selector. When `false` (the default) the pill, and therefore the
     *  whole model selector, is hidden; hosts that want it must opt in. */
    val showModelSelector: Boolean = false,
    /** Show tasks tab */
    val showTasksTab: Boolean = true,
    /** Show system picker (settings cog) */
    val showSystemPicker: Boolean = true,
    /** Show tool call/result and sub-agent orchestration messages in the
     *  chat thread. When `false` (the default, matching iOS and the web
     *  frontend) these events are processed internally but not rendered as
     *  visible messages; hosts that want the verbose thread opt in. */
    val showToolMessages: Boolean = false,
    /**
     * Follow the assistant's streaming reply by auto-scrolling to the
     * bottom on every token. When `false` the list stays put while the
     * reply is being generated and the user controls scrolling. The
     * pin-to-bottom on user submit and on initial render is unaffected.
     * Default is `true` for backwards compatibility.
     */
    /** Ephemeral mode: conversation history stays on the client.
     * The server only holds run data for a short pickup window. */
    val ephemeral: Boolean = false,
    /** Private-only egress: when true, every run is flagged `private_only`
     * so the server routes it ONLY to the configured private model endpoint
     * (fail-closed). Set this for data-sovereignty-restricted users. */
    val privateOnly: Boolean = false,
    /** Allow cleartext HTTP to the backend. Default false: the client refuses
     * to send over a non-HTTPS connection (except to local dev hosts). Only
     * enable for local development against an http:// backend. */
    val allowInsecureHTTP: Boolean = false,
    val followStreamingEnabled: Boolean = true,
    /**
     * How close to the bottom (in dp) the list must be before streaming
     * deltas are allowed to pull the scroll position down. Once the user
     * drags more than this distance away from the bottom the auto-follow
     * pauses until they scroll back. Drop it close to `0f` for strict
     * follow ("any scroll up disables it"), raise it to ~300f for a
     * looser feel that ignores small drags. Default `100f` matches iOS.
     */
    val nearBottomThresholdPt: Float = 100f,

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
    /** Voice identifier passed to the configured `TTSProvider`. Used by
     *  the ElevenLabs proxy to select a specific voice (e.g. S'Ai's
     *  branded voice). `null` falls back to the provider/proxy default. */
    val voiceId: String? = null,
    /** Optional ElevenLabs model override (e.g. `eleven_turbo_v2_5`).
     *  `null` lets the provider/proxy pick. */
    val voiceModelId: String? = null,
    /** Preferred Android TTS engine package for local speech (for example, Google Speech Services). */
    val localTtsEnginePackageName: String? = null,
    /** Preferred locale for Android on-device TTS voices. Used only by the local TextToSpeech provider; unavailable locales fall back safely. */
    val localVoiceLocale: Locale? = null,
    /** Best-effort gender preference for Android on-device TTS voices. Android engines expose gender inconsistently, so this is applied as a hint. */
    val localVoiceGenderPreference: LocalVoiceGenderPreference = LocalVoiceGenderPreference.MALE,

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
    val onVideoFullScreenChange: ((Boolean) -> Unit)? = null,
    /**
     * Fires exactly once per conversation lifetime, the moment the runtime
     * mints a fresh `conversationId` (i.e. the first `createRun` response
     * carries one and `messages` was empty). Does **not** fire when an
     * existing conversation is restored from local storage or loaded via
     * [com.makemore.agentfrontend.viewmodels.ChatViewModel.loadConversation].
     * Useful for analytics, first-launch coach marks, or kicking off
     * host-side flows that should bind to a stable conversation id.
     */
    val onConversationStart: ((String) -> Unit)? = null,
    /**
     * Fires exactly once per conversation lifetime, when the first
     * assistant message becomes visible in `messages` — whether it arrives
     * via streaming deltas, a non-streaming `assistant.message` snap, or a
     * host call to
     * [com.makemore.agentfrontend.viewmodels.ChatViewModel.appendAssistantMessage].
     * Suppressed when an existing conversation already containing assistant
     * messages is restored. Useful for dismissing splash screens or
     * chaining onboarding steps once S'Ai has actually spoken.
     */
    val onFirstAssistantMessage: ((String) -> Unit)? = null
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

    val effectiveTtsProviderPolicy: TTSProviderPolicy
        get() = if (privateOnly && ttsProviderPolicy == TTSProviderPolicy.AUTOMATIC) {
            TTSProviderPolicy.LOCAL_ONLY
        } else {
            ttsProviderPolicy
        }

    val effectiveSpeechInputPolicy: SpeechInputPolicy
        get() = if (privateOnly && speechInputPolicy == SpeechInputPolicy.AUTOMATIC) {
            SpeechInputPolicy.LOCAL_ONLY
        } else {
            speechInputPolicy
        }
}

