# Template App Setup Guide (Android)

> **Purpose**: This document describes how to scaffold a complete ChatGPT-style Android app using the `AgentFrontend` library (Jetpack Compose). It is designed to be read by an LLM (or developer) and followed step-by-step to generate a fully working project. Every file, package, build variant, and configuration is described explicitly.
>
> This is the Android counterpart of the iOS [`TEMPLATE_APP_SETUP.md`](https://github.com/makemore/agent-ios/blob/main/TEMPLATE_APP_SETUP.md). Both apps target the same `django_agent_studio` backend and share the same auth strategies.

---

## Table of Contents

1. [Project Structure](#1-project-structure)
2. [Gradle Project Setup — consume the library](#2-gradle-project-setup--consume-the-library)
3. [Build Variants — Production & Development](#3-build-variants--production--development)
4. [Environment Configuration](#4-environment-configuration)
5. [Secure Token Storage](#5-secure-token-storage)
6. [Authentication Repository](#6-authentication-repository)
7. [API Service Layer](#7-api-service-layer)
8. [App Entry Point](#8-app-entry-point)
9. [Main App Shell — ChatGPT-Style Layout](#9-main-app-shell--chatgpt-style-layout)
10. [Sidebar — Conversation List](#10-sidebar--conversation-list)
11. [Chat Screen — Main Content Area](#11-chat-screen--main-content-area)
12. [Settings Screen](#12-settings-screen)
13. [Login Screen](#13-login-screen)
14. [Putting It All Together](#14-putting-it-all-together)

---

## 1. Project Structure

Create the following package structure inside your app module (`app/src/main/java/com/example/myapp/`):

```
app/
├── build.gradle.kts                      # App module — adds AgentFrontend dependency
├── src/main/
│   ├── AndroidManifest.xml
│   └── java/com/example/myapp/
│       ├── MyApp.kt                       # Application subclass
│       ├── MainActivity.kt                # @AndroidEntryPoint / setContent host
│       ├── config/
│       │   └── AppEnvironment.kt          # Reads BuildConfig (base URL, agent key)
│       ├── data/
│       │   ├── TokenStore.kt              # EncryptedSharedPreferences token storage
│       │   ├── AuthRepository.kt          # Login, logout, token state
│       │   └── ApiService.kt              # Generic HTTP client (app-specific calls)
│       ├── ui/
│       │   ├── AppViewModel.kt            # Root app state (auth, drawer, selection)
│       │   ├── RootView.kt                # Auth gate — login vs. main shell
│       │   ├── MainShellView.kt           # ChatGPT-style shell (drawer + chat)
│       │   ├── AppSidebarView.kt          # Conversation list drawer
│       │   ├── ChatContainerView.kt       # Wraps AgentFrontend chat widget
│       │   ├── SettingsView.kt            # User settings / logout
│       │   └── LoginView.kt               # Email + password login form
│       └── theme/
│           └── Theme.kt                    # Material 3 theme
└── settings.gradle.kts                    # Declares the JitPack repo
```

---

## 2. Gradle Project Setup — consume the library

The library is published via **[JitPack](https://jitpack.io)** from the public
`makemore/agent-android` repository — **no GitHub token or credentials needed**.
Full details (artifacts, version coordinates) live in the
[README Installation section](README.md#installation). The essential steps:

### 2.1 Declare the repository

In your app's `settings.gradle.kts`, inside `dependencyResolutionManagement`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

### 2.2 Add the dependency

In your **app module's** `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.github.makemore.agent-android:agent-frontend:3.0.1")   // Compose UI + headless core
    // or, headless only:
    // implementation("com.github.makemore.agent-android:agent-client:3.0.1")
}
```

> Use the latest tag from
> [makemore/agent-android/tags](https://github.com/makemore/agent-android/tags).
> The widget UI requires Jetpack Compose; your app module must apply the
> Compose Gradle plugin and enable `buildFeatures { compose = true }`.

**Minimum requirements:** Android API 26+ (Android 8.0) · Kotlin 2.1 · JDK 17 · Compose BOM 2025.04.

---

## 3. Build Variants — Production & Development

The Android equivalent of iOS schemes is **build types / product flavors**. Use two
build types so the app points at different API servers. In your app module's
`build.gradle.kts`:

```kotlin
android {
    buildFeatures {
        buildConfig = true
        compose = true
    }

    defaultConfig {
        // Shared default — overridden per build type below.
        buildConfigField("String", "API_BASE_URL", "\"http://10.0.2.2:8000\"")
        buildConfigField("String", "AGENT_KEY", "\"default-agent\"")
    }

    buildTypes {
        debug {
            // Development — Android emulator reaches host localhost via 10.0.2.2
            buildConfigField("String", "API_BASE_URL", "\"http://10.0.2.2:8000\"")
        }
        release {
            isMinifyEnabled = true
            buildConfigField("String", "API_BASE_URL", "\"https://api.yourapp.com\"")
        }
    }
}
```

> **Note:** `10.0.2.2` is the emulator's alias for the host machine's `localhost`.
> On a physical device, use your machine's LAN IP instead.

Switch the **Build Variant** in Android Studio (View → Tool Windows → Build Variants)
to flip between `debug` (development) and `release` (production).

---

## 4. Environment Configuration

### `config/AppEnvironment.kt`

A thin wrapper over the generated `BuildConfig` so the rest of the app reads strongly-typed values.

```kotlin
package com.example.myapp.config

import com.example.myapp.BuildConfig

object AppEnvironment {
    /** Base URL for all API requests, set per build type. */
    val apiBaseUrl: String = BuildConfig.API_BASE_URL

    /** Agent key from django_agent_studio. */
    val agentKey: String = BuildConfig.AGENT_KEY

    /** True for debug builds. */
    val isDebug: Boolean = BuildConfig.DEBUG
}
```

---

## 5. Secure Token Storage

Store the auth token in `EncryptedSharedPreferences` rather than plain `SharedPreferences`.
Add the dependency in your app module:

```kotlin
dependencies {
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
}
```

### `data/TokenStore.kt`

```kotlin
package com.example.myapp.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class TokenStore(context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "auth_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    var authToken: String?
        get() = prefs.getString(KEY_AUTH, null)
        set(value) = prefs.edit().apply {
            if (value == null) remove(KEY_AUTH) else putString(KEY_AUTH, value)
        }.apply()

    var refreshToken: String?
        get() = prefs.getString(KEY_REFRESH, null)
        set(value) = prefs.edit().apply {
            if (value == null) remove(KEY_REFRESH) else putString(KEY_REFRESH, value)
        }.apply()

    fun clear() = prefs.edit().clear().apply()

    private companion object {
        const val KEY_AUTH = "auth_token"
        const val KEY_REFRESH = "refresh_token"
    }
}
```

---

## 6. Authentication Repository

Handles login against `django_agent_studio`'s token endpoint and exposes auth state as
Compose state. Adjust the endpoint and response shape to match your backend.

### `data/AuthRepository.kt`

```kotlin
package com.example.myapp.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class AuthRepository(
    private val tokenStore: TokenStore,
    private val api: ApiService,
) {
    var token by mutableStateOf(tokenStore.authToken)
        private set
    var isAuthenticated by mutableStateOf(tokenStore.authToken != null)
        private set
    var isLoading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** DRF Token auth — POST /api/accounts/token/ → { "token": "..." } */
    suspend fun login(email: String, password: String) {
        isLoading = true
        error = null
        try {
            val response = api.post(
                path = "/api/accounts/token/",
                body = mapOf("email" to email, "password" to password),
            )
            val newToken = response.optString("token").takeIf { it.isNotEmpty() }
                ?: throw IllegalStateException("No token in response")
            tokenStore.authToken = newToken
            token = newToken
            isAuthenticated = true
        } catch (e: Exception) {
            error = e.message ?: "Login failed"
        } finally {
            isLoading = false
        }
    }

    fun logout() {
        tokenStore.clear()
        token = null
        isAuthenticated = false
    }
}
```

> **Auth endpoint:** `django_agent_studio` typically exposes `/api/accounts/token/` for
> DRF Token auth or `/api/accounts/jwt/` for JWT. For JWT you receive `access` + `refresh`
> tokens — store both via `TokenStore` and use `AuthStrategy.JWT` in the chat config (Section 9).

---

## 7. API Service Layer

A lightweight HTTP client for your own app-specific calls (login, profile, etc.). The
chat widget itself does **not** use this — it talks to the backend through the library's
own `APIClient`. This is only for endpoints your app owns.

### `data/ApiService.kt`

```kotlin
package com.example.myapp.data

import com.example.myapp.config.AppEnvironment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class ApiService {
    /** POST a JSON body and return the parsed JSON object. */
    suspend fun post(
        path: String,
        body: Map<String, Any?>,
        token: String? = null,
    ): JSONObject = withContext(Dispatchers.IO) {
        val url = URL("${AppEnvironment.apiBaseUrl}$path")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            // DRF Token auth — use "Bearer $token" for JWT instead.
            token?.let { setRequestProperty("Authorization", "Token $it") }
        }
        conn.outputStream.use { it.write(JSONObject(body).toString().toByteArray()) }

        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream.bufferedReader().use { it.readText() }
        if (code !in 200..299) {
            throw ApiException(code, text)
        }
        JSONObject(text)
    }
}

class ApiException(val statusCode: Int, message: String) : Exception(
    when (statusCode) {
        401 -> "Unauthorized — please log in again"
        403 -> "Access denied"
        404 -> "Not found"
        else -> "HTTP error $statusCode: $message"
    }
)
```

> For anything beyond a couple of endpoints, prefer **Retrofit + OkHttp** or **Ktor**.
> The hand-rolled client above keeps the template dependency-free.

---

## 8. App Entry Point

### `MyApp.kt`

```kotlin
package com.example.myapp

import android.app.Application
import com.example.myapp.data.ApiService
import com.example.myapp.data.AuthRepository
import com.example.myapp.data.TokenStore

class MyApp : Application() {
    lateinit var authRepository: AuthRepository
        private set

    override fun onCreate() {
        super.onCreate()
        val tokenStore = TokenStore(this)
        authRepository = AuthRepository(tokenStore, ApiService())
    }
}
```

Register it in `AndroidManifest.xml`:

```xml
<application
    android:name=".MyApp"
    ... >
    <activity android:name=".MainActivity" android:exported="true">
        <intent-filter>
            <action android:name="android.intent.action.MAIN" />
            <category android:name="android.intent.category.LAUNCHER" />
        </intent-filter>
    </activity>
</application>
```

### `MainActivity.kt`

```kotlin
package com.example.myapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.example.myapp.theme.MyAppTheme
import com.example.myapp.ui.RootView

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val auth = (application as MyApp).authRepository
        setContent {
            MyAppTheme {
                RootView(authRepository = auth)
            }
        }
    }
}
```

---

## 9. Main App Shell — ChatGPT-Style Layout

The layout mirrors the iOS app: a top bar, a scrollable message area provided by
`AgentFrontend`, and an input bar. A hamburger opens a slide-in sidebar with the
conversation list.

```
┌─────────────────────────────────────────┐
│ ☰  My Assistant                ✎        │  ← Top bar
├─────────────────────────────────────────┤
│         Message bubbles area            │  ← from AgentFrontend
│         (ChatWidgetView)                │
├─────────────────────────────────────────┤
│ [Type your message...]          [Send]  │  ← Input bar (built in)
└─────────────────────────────────────────┘
```

> **Two integration styles.** The library ships its **own** top bar + slide-in sidebar
> (`ChatSidebarConfig`). The simplest integration is the one-liner in the
> [Simple Alternative](#simple-alternative). The sections below instead show the
> **custom-shell** approach (host owns the chrome) to match the iOS template — set
> `showInternalTopBar = false` and `sidebar = ChatSidebarConfig(enabled = false)`.

### `ui/AppViewModel.kt`

Holds the single `ChatViewModel` and drawer/selection state. `ChatViewModel` exposes
its observable fields as Jetpack Compose state (`mutableStateOf` / `mutableStateListOf`),
so Composables that read them recompose automatically.

```kotlin
package com.example.myapp.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.myapp.config.AppEnvironment
import com.makemore.agentfrontend.AgentFrontend
import com.makemore.agentfrontend.configuration.AuthStrategy
import com.makemore.agentfrontend.configuration.ChatSidebarConfig
import com.makemore.agentfrontend.configuration.ChatWidgetConfig
import com.makemore.agentfrontend.viewmodels.ChatViewModel
import com.makemore.agentfrontend.voice.TTSProviderPolicy

class AppViewModel(context: Context, token: String?) {
    var showSidebar by mutableStateOf(false)
    var selectedConversationId by mutableStateOf<String?>(null)

    /** Config built from the current auth token. */
    val config: ChatWidgetConfig = ChatWidgetConfig(
        backendUrl = AppEnvironment.apiBaseUrl,
        agentKey = AppEnvironment.agentKey,
        title = "My Assistant",
        showInternalTopBar = false,                 // host provides the top bar
        sidebar = ChatSidebarConfig(enabled = false), // host provides the sidebar
        showTasksTab = false,
        enableFiles = true,
        // Voice output:
        // Normal mode: enableTTS = true, ttsProviderPolicy = TTSProviderPolicy.AUTOMATIC
        // Protected mode: privateOnly = true, enableTTS = true, ttsProviderPolicy = TTSProviderPolicy.LOCAL_ONLY
        // Local/system TTS quality depends on the OS/device and will not match ElevenLabs.
        // Android local TTS defaults to a best-effort male voice when the engine exposes one.
        // Auth — AuthStrategy.TOKEN for DRF, AuthStrategy.JWT for Bearer.
        authStrategy = token?.let { AuthStrategy.TOKEN },
        authToken = token,
    )

    /** Created once and reused for the app's lifetime. */
    val chatViewModel: ChatViewModel =
        AgentFrontend.createViewModel(context, config)

    fun newConversation() {
        chatViewModel.clearMessages()
        selectedConversationId = null
        showSidebar = false
    }

    fun selectConversation(id: String) {
        selectedConversationId = id
        chatViewModel.loadConversation(id)
        showSidebar = false
    }
}
```

### `ui/RootView.kt`

The auth gate — shows login or the main shell.

```kotlin
package com.example.myapp.ui

import androidx.compose.runtime.Composable
import com.example.myapp.data.AuthRepository

@Composable
fun RootView(authRepository: AuthRepository) {
    if (authRepository.isAuthenticated) {
        MainShellView(authRepository = authRepository)
    } else {
        LoginView(authRepository = authRepository)
    }
}
```

### `ui/MainShellView.kt`

```kotlin
package com.example.myapp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.example.myapp.data.AuthRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainShellView(authRepository: AuthRepository) {
    val context = LocalContext.current
    val app = remember { AppViewModel(context, authRepository.token) }
    var showSettings by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(app.config.title) },
                navigationIcon = {
                    IconButton(onClick = { app.showSidebar = true }) {
                        Icon(Icons.Filled.Menu, contentDescription = "Open conversations")
                    }
                },
                actions = {
                    IconButton(onClick = { app.newConversation() }) {
                        Icon(Icons.Filled.Edit, contentDescription = "New chat")
                    }
                },
            )
        },
    ) { padding ->
        ChatContainerView(
            app = app,
            modifier = Modifier.padding(padding).fillMaxSize(),
        )
    }

    if (app.showSidebar) {
        AppSidebarView(
            app = app,
            authRepository = authRepository,
            onOpenSettings = { showSettings = true },
            onDismiss = { app.showSidebar = false },
        )
    }

    if (showSettings) {
        SettingsView(
            authRepository = authRepository,
            onDismiss = { showSettings = false },
        )
    }
}
```

---

## 10. Sidebar — Conversation List

A `ModalNavigationDrawer`-style overlay that lists the user's conversations. It fetches
them through the library's own `APIClient.loadConversations()` extension, so the host
doesn't re-implement the networking.

### `ui/AppSidebarView.kt`

```kotlin
package com.example.myapp.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.myapp.data.AuthRepository
import com.makemore.agentfrontend.models.Conversation
import com.makemore.agentfrontend.networking.APIClient
import com.makemore.agentfrontend.networking.loadConversations
import com.makemore.agentfrontend.services.InMemoryStorage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSidebarView(
    app: AppViewModel,
    authRepository: AuthRepository,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var conversations by remember { mutableStateOf<List<Conversation>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    // Fetch recents via the library's APIClient when the drawer opens.
    LaunchedEffect(Unit) {
        try {
            val client = APIClient(app.config, InMemoryStorage())
            conversations = client.loadConversations()
        } catch (_: Exception) {
            // Surface to the user as needed.
        } finally {
            isLoading = false
        }
    }

    ModalNavigationDrawer(
        drawerState = rememberDrawerState(DrawerValue.Open),
        drawerContent = {
            ModalDrawerSheet {
                Spacer(Modifier.height(16.dp))
                NavigationDrawerItem(
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    label = { Text("New Chat") },
                    selected = false,
                    onClick = { app.newConversation(); onDismiss() },
                )
                HorizontalDivider()

                if (isLoading) {
                    Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    LazyColumn(Modifier.weight(1f)) {
                        items(conversations) { conversation ->
                            NavigationDrawerItem(
                                label = { Text(conversation.title ?: "Untitled") },
                                selected = app.selectedConversationId == conversation.id,
                                onClick = { app.selectConversation(conversation.id); onDismiss() },
                                modifier = Modifier.padding(horizontal = 12.dp),
                            )
                        }
                    }
                }

                HorizontalDivider()
                NavigationDrawerItem(
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    label = { Text("Settings") },
                    selected = false,
                    onClick = { onOpenSettings(); onDismiss() },
                )
                NavigationDrawerItem(
                    icon = { Icon(Icons.Filled.Logout, contentDescription = null) },
                    label = { Text("Log out") },
                    selected = false,
                    onClick = { authRepository.logout(); onDismiss() },
                )
            }
        },
        content = {
            // Tap-scrim to dismiss.
            Box(Modifier.fillMaxSize().clickable(onClick = onDismiss))
        },
    )
}
```

> **Note on auth for recents:** the snippet reuses `app.config` (which already carries
> the auth token) with an `InMemoryStorage`, so the recents request is authenticated the
> same way the chat is. The model fields are: `Conversation(id, title?, messages?,
> hasMore?, createdAt?, updatedAt?)` — `createdAt`/`updatedAt` are ISO-8601 `String?`.

---

## 11. Chat Screen — Main Content Area

`ChatWidgetView` is the bundled chat flow (message list + error banner + input). Because
the host owns the top bar and sidebar (Section 9), we render `ChatWidgetView` directly
and let it manage messages, streaming, and the composer.

### `ui/ChatContainerView.kt`

```kotlin
package com.example.myapp.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.makemore.agentfrontend.ui.ChatWidgetView

@Composable
fun ChatContainerView(app: AppViewModel, modifier: Modifier = Modifier) {
    ChatWidgetView(
        viewModel = app.chatViewModel,
        config = app.config,
        modifier = modifier,
    )
}
```

> **Building a fully custom composer?** Drop `ChatWidgetView` and compose the primitives
> directly. `ChatViewModel` exposes Compose-observable state you read in your own UI:
>
> ```kotlin
> MessageListView(
>     messages = app.chatViewModel.messages,
>     isLoading = app.chatViewModel.isLoading.value,
>     hasMoreMessages = app.chatViewModel.hasMoreMessages.value,
>     loadingMoreMessages = app.chatViewModel.loadingMoreMessages.value,
>     config = app.config,
>     onLoadMore = { app.chatViewModel.loadMoreMessages() },
>     onRetry = { index -> app.chatViewModel.retryMessage(index) },
>     onEdit = { index, content -> app.chatViewModel.editMessage(index, content) },
> )
> InputView(
>     config = app.config,
>     isLoading = app.chatViewModel.isLoading.value,
>     onSend = { content, files -> app.chatViewModel.sendMessage(content, files) },
>     onCancel = { app.chatViewModel.cancelRun() },
> )
> ```

---

## 12. Settings Screen

### `ui/SettingsView.kt`

```kotlin
package com.example.myapp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.myapp.config.AppEnvironment
import com.example.myapp.data.AuthRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsView(authRepository: AuthRepository, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("Settings", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))

            ListItem(
                headlineContent = { Text("Environment") },
                trailingContent = {
                    Text(if (AppEnvironment.isDebug) "Development" else "Production")
                },
            )
            ListItem(
                headlineContent = { Text("API URL") },
                supportingContent = { Text(AppEnvironment.apiBaseUrl) },
            )

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { authRepository.logout(); onDismiss() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                ),
            ) { Text("Log out") }
            Spacer(Modifier.height(16.dp))
        }
    }
}
```

---

## 13. Login Screen

### `ui/LoginView.kt`

```kotlin
package com.example.myapp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.myapp.data.AuthRepository
import kotlinx.coroutines.launch

@Composable
fun LoginView(authRepository: AuthRepository) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("My Assistant", style = MaterialTheme.typography.headlineLarge)
        Text("Sign in to continue", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(32.dp))

        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("Email") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )

        authRepository.error?.let { error ->
            Spacer(Modifier.height(8.dp))
            Text(error, color = MaterialTheme.colorScheme.error)
        }

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = { scope.launch { authRepository.login(email, password) } },
            enabled = email.isNotEmpty() && password.isNotEmpty() && !authRepository.isLoading,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (authRepository.isLoading) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Text("Sign In")
            }
        }
    }
}
```

---

## 14. Putting It All Together

### Checklist

After creating all the files above, verify:

- [ ] **`settings.gradle.kts`** declares the JitPack Maven repo (Section 2.1)
- [ ] **App module** depends on `com.github.makemore.agent-android:agent-frontend:<version>`
- [ ] **`buildFeatures`** enables `compose = true` and `buildConfig = true`
- [ ] **`API_BASE_URL`** / `AGENT_KEY` `buildConfigField`s are set per build type
- [ ] **`MyApp`** is registered as `android:name` in the manifest
- [ ] **`INTERNET`** permission is in `AndroidManifest.xml`
- [ ] **Build succeeds** on both `debug` and `release` variants

### Customisation Points

| What | Where | How |
|------|-------|-----|
| App name / title | `AppViewModel.kt` | Change `title = "My Assistant"` in `config` |
| Primary colour | `AppViewModel.kt` | Add `primaryColor = Color(0xFF0066CC)` to `config` |
| Agent key | Build type | Change the `AGENT_KEY` `buildConfigField` |
| Auth endpoint | `AuthRepository.kt` | Change `/api/accounts/token/` path |
| Auth strategy | `AppViewModel.kt` | Change `AuthStrategy.TOKEN` to `AuthStrategy.JWT` |
| Auth header prefix | `ApiService.kt` | Change `"Token $it"` to `"Bearer $it"` for JWT |
| API base URLs | Build types | Edit `API_BASE_URL` `buildConfigField` per type |
| Built-in vs custom chrome | `AppViewModel.kt` | Toggle `showInternalTopBar` / `sidebar.enabled` |
| Feature flags | `AppViewModel.kt` `config` | Toggle `enableFiles`, `showTasksTab`, etc. |

### Simple Alternative

If you don't need a custom top bar and sidebar, skip `AppViewModel`, `MainShellView`,
`AppSidebarView`, and `ChatContainerView` entirely and drop the widget in directly —
the library renders its **own** top bar and slide-in sidebar:

```kotlin
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.makemore.agentfrontend.AgentFrontend
import com.makemore.agentfrontend.configuration.AuthStrategy
import com.makemore.agentfrontend.configuration.ChatWidgetConfig

@Composable
fun ChatScreen(token: String?) {
    AgentFrontend.ChatWidget(
        context = LocalContext.current,
        config = ChatWidgetConfig.make(
            backendUrl = AppEnvironment.apiBaseUrl,
            agentKey = AppEnvironment.agentKey,
            title = "My Assistant",
        ).withAuth(AuthStrategy.TOKEN, token = token),
    )
}
```

This gives you the full chat experience (messages, input, error handling, built-in
sidebar) with no app shell to maintain.

---

### AgentFrontend API Quick Reference

Key types from the `AgentFrontend` library (Android):

| Type | Purpose |
|------|---------|
| `AgentFrontend.ChatWidget(context, config, modifier)` | Composable — complete chat widget |
| `AgentFrontend.ChatWidget(context, config, storage, modifier)` | Same, with custom `StorageService` |
| `AgentFrontend.createViewModel(context, config)` | Factory — returns a `ChatViewModel` for custom UI |
| `ChatWidgetConfig` | All configuration (URLs, auth, UI flags, API paths) |
| `ChatWidgetConfig.make(backendUrl, agentKey, title, primaryColor)` | Convenience config builder |
| `ChatWidgetConfig.withAuth(strategy, token)` | Returns a copy with auth set |
| `AuthStrategy` | Enum: `TOKEN`, `JWT`, `SESSION`, `ANONYMOUS`, `NONE` |
| `APICaseStyle` | Enum: `CAMEL`, `SNAKE`, `AUTO` |
| `ChatViewModel` | Compose-state holder — messages, send, cancel, load, edit, retry |
| `ChatWidgetView` | Chat flow Composable (messages + error banner + input) |
| `MessageListView` | Just the scrollable message list |
| `InputView` | Just the composer (text input + file picker + send/cancel) |
| `ChatSidebarView` | The built-in slide-in conversation sidebar |
| `APIClient` | HTTP + SSE client for django_agent_studio |
| `APIClient.loadConversations()` | `suspend` extension — returns `List<Conversation>` |
| `StorageService` | Interface for key-value storage (`get` / `set`) |
| `SharedPreferencesStorage` | Default `StorageService` implementation |
| `InMemoryStorage` | In-memory `StorageService` (for previews/tests) |
| `Conversation` | Model — `id`, `title`, `messages`, `hasMore`, `createdAt`, `updatedAt` |
| `Message` | Model — `id`, `role`, `content`, `timestamp`, `type`, `metadata`, `files` |

### ChatViewModel Observable State

`ChatViewModel` uses Jetpack Compose state, not `StateFlow`. Read `.value` on the
single-value fields; `messages` and `localConversations` are `SnapshotStateList`s.

```kotlin
val messages: SnapshotStateList<Message>
val isLoading: MutableState<Boolean>
val error: MutableState<String?>
val conversationId: MutableState<String?>
val hasMoreMessages: MutableState<Boolean>
val loadingMoreMessages: MutableState<Boolean>
val runState: MutableState<RunState>   // IDLE, SENDING, STREAMING, WAITING, CANCELLING, CANCELLED, FAILED, SUCCEEDED
```

### ChatViewModel Methods

These are regular (non-suspend) functions — they launch their own coroutines internally.

```kotlin
fun sendMessage(content: String, files: List<FileAttachment> = emptyList())
fun cancelRun()
fun clearMessages()
fun loadConversation(convId: String)
fun loadMoreMessages()
fun editMessage(index: Int, newContent: String, model: String? = null, thinking: Boolean = false)
fun retryMessage(index: Int, model: String? = null, thinking: Boolean = false)
fun restoreConversationIfNeeded()
```

---

> **End of setup guide.** Follow sections 1–13 in order to scaffold the complete app.
> Customise using the table in section 14. For installation/versioning details see
> [README.md](README.md); for cutting a release see [RELEASING.md](RELEASING.md).
