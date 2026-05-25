# AgentFrontend (Android)

A Jetpack Compose chat widget library for AI agents. Android equivalent of [`agent-ios`](https://github.com/makemore/agent-ios) and the `agent-frontend` JavaScript library.

**Requires:** Android API 26+ (Android 8.0) · Kotlin 2.1 · JDK 17 · Compose BOM 2025.04

## Headless/API surface and reusable primitives

The Gradle project exposes:

- `:agent-client`: product-neutral models, auth, API client, SSE transport, local history, pagination, cancellation, and voice helpers.
- `:agent-frontend`: reusable Compose primitives plus the bundled widget. Host apps can reuse `MessageListView`, `MessageView`, `InputView`, `ContentBlockViews`, `TaskListView`, and `SystemPickerView` directly inside their own shell.

`ChatViewModel.runState` exposes the canonical lifecycle: `IDLE`, `SENDING`, `STREAMING`, `WAITING`, `CANCELLING`, `CANCELLED`, `FAILED`, `SUCCEEDED`. `WAITING` is used for `run.suspended` and `client.action.required` so mobile UI does not remain stuck in a loading state.

Supported visible event primitives include assistant deltas/messages, tool calls/results, content blocks, cancellations/failures/success, memory updates, sub-agent markers, and generic required-action cards. `AgentStreamEvent` and `AgentRunReducerState` provide headless typed parsing/reducer primitives for custom clients that do not want the bundled `ChatViewModel`. The shared backend contract is documented in `agent/docs/mobile-protocol-contract.md`.

The library boundary is intentionally generic: `agent-client` owns agent stream events, SSE lifecycle, reducer state, tool/required-action semantics, fixtures, and tests. Host products own navigation, push notifications, integrations UI, branding, terminal sessions, and app-specific persistence.

## Installation

### Local Gradle subproject (recommended for development)

In your app's `settings.gradle.kts`:

```kotlin
includeBuild("/path/to/agent-android") {
    dependencySubstitution {
        substitute(module("com.makemore:agent-client"))
            .using(project(":agent-client"))
        substitute(module("com.makemore:agent-frontend"))
            .using(project(":"))
    }
}
```

Or copy the repo into your project and add to `settings.gradle.kts`:

```kotlin
include(":agent-android", ":agent-android:agent-client")
project(":agent-android").projectDir = file("../agent-android")
```

In your app's `build.gradle.kts`:

```kotlin
dependencies {
    implementation(project(":agent-android"))           // Compose UI + headless core
    // or, headless only:
    // implementation(project(":agent-android:agent-client"))
}
```

## Quick Start

```kotlin
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.makemore.agentfrontend.AgentFrontend
import com.makemore.agentfrontend.configuration.ChatWidgetConfig

@Composable
fun ChatScreen() {
    AgentFrontend.ChatWidget(
        context = LocalContext.current,
        config = ChatWidgetConfig.make(
            backendUrl = "https://your-api.com",
            agentKey = "your-agent-key"
        )
    )
}
```

The default `backendUrl` is `http://10.0.2.2:8000` — the standard Android-emulator loopback to your dev machine.

## Configuration

```kotlin
import androidx.compose.ui.graphics.Color
import com.makemore.agentfrontend.configuration.AuthStrategy
import com.makemore.agentfrontend.configuration.APIPaths

val config = ChatWidgetConfig(
    backendUrl = "https://your-api.com",
    agentKey   = "your-agent-key",

    // UI
    title         = "My Assistant",
    subtitle      = "How can I help?",
    primaryColor  = Color(0xFFFF6600),
    placeholder   = "Ask me anything…",

    // Features
    showTasksTab     = true,
    showModelSelector = false,
    enableFiles      = true,
    enableVoice      = true,

    // Authentication
    authStrategy = AuthStrategy.JWT,
    authToken    = "your-jwt-token",

    // Custom API paths
    apiPaths = APIPaths(
        conversations = "/api/v2/conversations/",
        runs          = "/api/v2/runs/"
    )
)
```

### Auth Strategies

| Strategy            | Description                          |
|---------------------|--------------------------------------|
| `AuthStrategy.TOKEN`     | Django REST `Token {token}` header   |
| `AuthStrategy.JWT`       | Bearer token `Bearer {token}` header |
| `AuthStrategy.SESSION`   | Cookie-based session auth            |
| `AuthStrategy.ANONYMOUS` | Auto-fetched anonymous session token |
| `AuthStrategy.NONE`      | No authentication                    |

## Voice (Live Mic)

When `enableVoice = true` and `enableTTS = true`, the input row exposes a "Live Mic" experience matching `agent-ios`:

- **Always-on mic** — tap the mic once to enable; it stays live across submits and through agent playback. Tap again to fully stop. Internally `SpeechRecognizer` is recycled on every result/error so its single-shot model still feels continuous.
- **Auto-send (hands-free)** — when the auto-renew icon next to the mic is on (default), 3 s of silence after the last partial auto-submits the text and re-engages the mic after the agent finishes speaking. The toggle persists across launches via `SharedPreferences`.
- **Barge-in** — while the agent is speaking the recognizer runs in *monitor* mode: partials are not written to the input field but diffed against `VoiceController.recentSpokenText` (the rolling buffer of text recently queued for TTS). A partial containing two or more words *not* in the agent's recently-spoken text triggers `VoiceController.stop()`, interrupting playback. Hardware AEC plus the leak-back filter keep self-interruption rare.
- **Manual stop** — the send button becomes a Stop button while the agent is speaking, so the user can always interrupt by tapping.

The `RECORD_AUDIO` permission is requested at runtime on first mic tap; the manifest entry is provided by the library.

## Custom ViewModel (Advanced)

For building your own UI on top of the chat logic:

```kotlin
@Composable
fun CustomChatScreen(config: ChatWidgetConfig) {
    val context = LocalContext.current
    val viewModel = remember { AgentFrontend.createViewModel(context, config) }
    val messages by viewModel.messages.collectAsState()

    LaunchedEffect(Unit) { viewModel.loadInitialData() }

    Column {
        messages.forEach { Text(it.content) }
        Button(onClick = {
            viewModel.viewModelScope.launch { viewModel.sendMessage("Hello") }
        }) { Text("Send") }
    }
}
```

## Custom Storage

Implement `StorageService` to replace the default `SharedPreferences` persistence:

```kotlin
class KeystoreStorage(context: Context) : StorageService {
    override fun get(key: String): String? { /* read from EncryptedSharedPreferences */ }
    override fun set(key: String, value: String?) { /* write to EncryptedSharedPreferences */ }
}

AgentFrontend.ChatWidget(
    context = LocalContext.current,
    config  = config,
    storage = KeystoreStorage(LocalContext.current)
)
```

## Two Modules

| Module          | What it contains                                               | Depends on   |
|-----------------|----------------------------------------------------------------|--------------|
| `agent-client`  | Models, networking, SSE, configuration, storage                | OkHttp, kotlinx-serialization |
| `agent-frontend` (root) | Compose chat widget + view layer                              | `agent-client`, Compose BOM |

The Compose module re-exports `agent-client` via `api(project(":agent-client"))`, so existing consumers that depend on the root module continue to work unchanged. New consumers can depend on `agent-client` alone to build a custom UI without pulling in Compose.

## Project Structure

```
agent-client/src/main/java/com/makemore/agentfrontend/
├── configuration/   # AuthStrategy, APIPaths, APICaseStyle
├── models/          # Message, Conversation, AgentModel, ContentBlock
├── networking/      # SSEClient, OkHttpExtensions, APIError
└── services/        # StorageService, LocalHistoryStore

src/main/java/com/makemore/agentfrontend/
├── AgentFrontend.kt              # Public API entry point
├── configuration/ChatWidgetConfig.kt
├── networking/APIClient.kt       # Wraps agent-client SSE + REST
├── viewmodels/ChatViewModel.kt
└── ui/                           # ChatWidgetView, MessageView, InputView, etc.

example/                          # Sample host app — open in Android Studio
                                  # and run the :example configuration.
```

## Sample App

The `:example` module is a manual scenario launcher for the chat widget. Open this repo in Android Studio, select the `example` run configuration, and deploy to a device or emulator. Mirrors the layout of `clients/agent-ios/Example`.

## Changelog

### 0.7.0

**Warm-dark "S'Ai" shell** (parity with `agent-ios` 0.8.0)

- **New configuration types** — `ChatAppearance` (palette, typography, composer style, brand-mark style), `ChatGreetingConfig` (time-of-day greeting + optional user name), and `ChatSidebarConfig` (slide-in drawer items, wordmark, footer). `ChatWidgetConfig` now exposes `appearance`, `greeting`, and `sidebar` properties; defaults flipped to the warm-dark baseline (`#0E0E0E` background, `#D97757` coral accent, `composerStyle = ANTHROPIC`, greeting + sidebar enabled). Set `ChatAppearance.classic()` and `greeting.copy(enabled = false)` / `sidebar.copy(enabled = false)` to restore the pre-redesign look.
- **`GreetingView`** — new centered empty-state composable: brand starburst + system-serif `"Good {morning|afternoon|evening}, {name}"`. `MessageListView` swaps to it when `config.greeting.enabled`.
- **`ChatSidebarView`** — slide-in conversation drawer (~80% of screen width, floored at 280 dp). Header wordmark, nav rows, Recents loaded via the existing `APIClient.loadConversations`, footer avatar + "New chat" pill wired to `ChatViewModel.clearMessages()` / `loadConversation(id)`. Dim backdrop, tap-outside to dismiss.
- **`AnthropicTopBar` + sidebar overlay in `ChatWidgetView`** — bundled widget now mounts a top bar with a circular hamburger button (opens the sidebar) and a "+" new-chat button. When `sidebar.enabled = false` the widget renders exactly as before.
- **`InputView` composer styles** — `ComposerStyle.ANTHROPIC` renders a two-row rounded card (text row + action row with `+` attach, model pill, mic, send circle); `ComposerStyle.CLASSIC` keeps the legacy single-row layout. Voice/STT logic is unchanged and shared between both styles via extracted `MicButton`, `RightActionButton`, and `ModelPill` composables.
- **`AddToChatSheet`** — `ModalBottomSheet` presented by the composer `+` button. Camera + Recents preview tiles, action rows (Add files, Add to project, Choose style…), tool toggles, and connectors list. Re-skins automatically for hosts using `.classic`.
- **Example app** — `HostConfiguration` gains `anthropicShell`, `userName`, `enableTTS`, `enableVoice`. `ScenarioLauncherScreen` adds a "S'Ai shell (warm-dark baseline)" section with `S'Ai home (empty chat)` and `S'Ai home (streaming demo)` scenarios. Legacy scenarios explicitly opt out so they keep the classic look for A/B comparison.
