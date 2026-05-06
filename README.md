# AgentFrontend (Android)

A Jetpack Compose chat widget library for AI agents. Android equivalent of [`agent-ios`](https://github.com/makemore/agent-ios) and the `agent-frontend` JavaScript library.

**Requires:** Android API 26+ (Android 8.0) · Kotlin 2.1 · JDK 17 · Compose BOM 2025.04

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
