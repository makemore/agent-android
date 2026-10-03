# AgentFrontend (Android)

A Jetpack Compose chat widget library for AI agents. Android equivalent of [`agent-ios`](https://github.com/makemore/agent-ios) and the `agent-frontend` JavaScript library.

**Requires:** Android API 26+ (Android 8.0) · Kotlin 2.1 · JDK 17 · Compose BOM 2025.04

## Headless/API surface and reusable primitives

The Gradle project exposes:

- `:agent-client`: product-neutral models, auth, API client, SSE transport, local history, pagination, cancellation, and voice helpers.
- `:agent-frontend`: reusable Compose primitives plus the bundled widget. Host apps can reuse `MessageListView`, `MessageView`, `InputView`, `ContentBlockViews`, `TaskListView`, and `SystemPickerView` directly inside their own shell.

`ChatViewModel.runState` exposes the canonical lifecycle: `IDLE`, `SENDING`, `STREAMING`, `WAITING`, `CANCELLING`, `CANCELLED`, `FAILED`, `SUCCEEDED`. `WAITING` is used for `run.suspended` and `client.action.required` so mobile UI does not remain stuck in a loading state.

Supported visible event primitives include assistant deltas/messages, tool calls/results, content blocks, cancellations/failures/success, memory updates, sub-agent markers, and generic required-action cards. `AgentStreamEvent` and `AgentRunReducerState` provide headless typed parsing/reducer primitives for custom clients that do not want the bundled `ChatViewModel`. The shared backend contract is documented in `packages/python/django_agent_runtime/docs/mobile-protocol-contract.md`.

The library boundary is intentionally generic: `agent-client` owns agent stream events, SSE lifecycle, reducer state, tool/required-action semantics, fixtures, and tests. Host products own navigation, push notifications, integrations UI, branding, terminal sessions, and app-specific persistence.

## Installation

The library is published via **[JitPack](https://jitpack.io)** from the public
`makemore/agent-android` repo — **no GitHub token or credentials required**.
JitPack builds the repo on demand from a git tag and serves both modules
anonymously. Two artifacts are available:

| Coordinate | Contents |
|------------|----------|
| `com.github.makemore.agent-android:agent-client:<version>` | Headless core — models, networking, SSE, storage (no Compose) |
| `com.github.makemore.agent-android:agent-frontend:<version>` | Compose chat widget + UI primitives (depends on `agent-client`) |
| `com.github.makemore.agent-android:agent-kokoro:<version>` | *Optional* on-device neural voice (Kokoro via sherpa-onnx); see [On-device neural voice](#on-device-neural-voice-kokoro) |

The latest version is the most recent tag on
[makemore/agent-android](https://github.com/makemore/agent-android/tags).

### 1. Add the JitPack repository

In your app's `settings.gradle.kts`, inside
`dependencyResolutionManagement { repositories { … } }`:

```kotlin
maven { url = uri("https://jitpack.io") }
```

### 2. Add the dependency

In your app module's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.github.makemore.agent-android:agent-frontend:3.0.1")   // Compose UI + headless core
    // or, headless only:
    // implementation("com.github.makemore.agent-android:agent-client:3.0.1")
}
```

> The `agent-frontend` artifact declares its dependency on `agent-client`
> transitively, so you only need the one line for the full widget.

<details>
<summary><strong>Local Gradle subproject (for library development)</strong></summary>

To work against the library source instead of a published artifact, in your
app's `settings.gradle.kts`:

```kotlin
includeBuild("/path/to/agent-android") {
    dependencySubstitution {
        substitute(module("com.github.makemore.agent-android:agent-client"))
            .using(project(":agent-client"))
        substitute(module("com.github.makemore.agent-android:agent-frontend"))
            .using(project(":"))
    }
}
```

Then depend on the coordinates exactly as in step 2 — Gradle substitutes the
local build automatically.

</details>

### Publishing a new release

See [RELEASING.md](RELEASING.md) for how to cut a version — for JitPack this is
just pushing a semver git tag; the first consumer request triggers the build.

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
import com.makemore.agentfrontend.voice.TTSProviderPolicy

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
    enableTTS        = true,
    ttsProviderPolicy = TTSProviderPolicy.AUTOMATIC,

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

### Appearance presets

`ChatWidgetConfig.appearance` takes a `ChatAppearance`. New integrations should
start from the recommended preset and `.copy()` individual tokens as needed:

```kotlin
import com.makemore.agentfrontend.configuration.ChatAppearance

val config = ChatWidgetConfig(
    appearance = ChatAppearance.recommended(),
)
```

Available presets:

| Preset | Look |
|--------|------|
| `ChatAppearance.recommended()` | Entry point for new integrations — currently `neutral()`. May be re-pointed in future releases; pin a named preset for a stable look. |
| `ChatAppearance.neutral()` | Generic, system-adaptive starting point — anthropic layout, theme-resolved colours, neutral accent. |
| `ChatAppearance.anthropic()` | Warm-dark library default (equivalent to `ChatAppearance()`). |
| `ChatAppearance.classic()` | The original pre-0.8 look. |
| `ChatAppearance.resilientGold()` | Resilient Minds house style — pinned gold-on-black snapshot. |

### Privacy-safe voice output

Normal mode keeps the existing remote/provider-backed voice behavior when the
Django voice proxy is configured:

```kotlin
val normal = ChatWidgetConfig(
    enableTTS = true,
    ttsProviderPolicy = TTSProviderPolicy.AUTOMATIC,
)
```

Protected/private mode should use Android `TextToSpeech` local voices so
assistant message text never goes to ElevenLabs or another remote TTS provider:

```kotlin
val protected = ChatWidgetConfig(
    privateOnly = true,
    enableTTS = true,
    ttsProviderPolicy = TTSProviderPolicy.LOCAL_ONLY,
)
```

`privateOnly = true` also makes `AUTOMATIC` resolve to local/system TTS. In
that mode the library does not request `/voice/token/` and does not call
`/voice/tts/`. Host apps can inspect `VoiceController.voiceMode.value` to show
states such as “Using device voice in Protected AI Mode”, “Voice unavailable
because no local voice is installed”, or “Voice disabled in Protected AI Mode”.

Local/system voice quality depends on the OS, installed engines, and device; it
will not match ElevenLabs quality. Android local TTS defaults to a best-effort
male/masculine voice when the installed engine exposes one, then safely falls
back to the best local voice for the device locale. Speech input also has
`speechInputPolicy`; protected mode defaults to on-device/offline recognition
and disables the mic when Android cannot provide it.

### On-device neural voice (Kokoro)

The optional `agent-kokoro` artifact adds **Kokoro-82M** (v1.0), a
high-quality neural voice that runs entirely on the device through
[sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx). It is the same engine
(`"kokoro"`), voice ids and model as the iOS and web clients. Assistant text
never leaves the device, so it is allowed in `LOCAL_ONLY` / `privateOnly`
mode.

```kotlin
dependencies {
    implementation("com.github.makemore.agent-android:agent-frontend:<version>")
    implementation("com.github.makemore.agent-android:agent-kokoro:<version>")
}
```

```kotlin
import com.makemore.agentfrontend.voice.kokoro.KokoroTTS

val config = ChatWidgetConfig(
    enableTTS = true,
    ttsProviderPolicy = TTSProviderPolicy.LOCAL_ONLY,   // or privateOnly = true
    localTtsEngine = KokoroTTS.engine(context),          // same instance per options
    voiceId = "af_heart",                                // any Kokoro voice id
)
```

sherpa-onnx comes from JitPack too (`com.github.k2-fsa.sherpa-onnx:sherpa-onnx:1.13.8`);
if you restrict the JitPack repository with `content { includeGroup(…) }`, add
that group.

`localTtsEngine` is used whenever voice output resolves to *local* (LOCAL_ONLY,
private mode, or AUTOMATIC without a voice proxy); a configured remote voice is
unchanged. `VoiceFactory.plan(config).localEngine == "kokoro"` tells you it was
picked. Without the widget, build the provider directly:
`KokoroTTSProvider(KokoroTTS.modelManager(context)) { AndroidTTSProvider(context, localOnly = true) }`.

**Model download.** The model is *not* in the AAR/APK. On first use it is
downloaded once (a plain GET of the model URL — no text, no identifiers) from
sherpa-onnx's release
[`kokoro-int8-multi-lang-v1_0.tar.bz2`](https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-int8-multi-lang-v1_0.tar.bz2)
(**~126 MiB** download, **~186 MiB** installed under
`noBackupFilesDir/agent-kokoro`; unpacking takes up to a minute or two on
slower devices). Until it is ready, and if it fails, replies use the local-only
Android system voice for that turn. Hosts can control it:

```kotlin
val models = KokoroTTS.modelManager(context)
models.state.collect { state ->              // NotDownloaded / Downloading(bytes, total) /
    // e.g. "Downloading voice… 42%"        // Installing / Ready / Failed(reason)
    (state as? KokoroModelState.Downloading)?.fraction
}
models.download()        // prefetch (e.g. on Wi-Fi) — suspends until Ready/Failed
models.delete()          // free the space; the system voice is used until re-downloaded
```

`KokoroOptions` (passed to `KokoroTTS.engine(context, options)`) sets
`modelUrl` (HTTPS mirror), `expectedSha256` (pin it when you mirror),
`modelDirectory`, `autoDownload` (`false` = only when you call `download()`),
`defaultVoiceId`, `speed` and `numThreads`.

**Behaviour.** Each sentence chunk from `VoiceController` is synthesised on a
dedicated background thread and streamed to `AudioTrack`; the next chunk is
rendered while the current one plays; `stop()` cancels promptly. If the engine
cannot load (e.g. an ABI without native libs) or a chunk fails, that turn falls
back to the local system voice; logs carry reason codes only, never text.

**Voices** (`KokoroTTSProvider.listVoices()` / `KokoroVoices.all`, 54 voices,
default `af_heart`): American English `af_heart`, `af_bella`, `af_nicole`,
`af_sarah`, `af_sky`, `am_michael`, `am_adam`, `am_fenrir`, `am_puck` …;
British English `bf_emma`, `bf_isabella`, `bf_alice`, `bf_lily`, `bm_george`,
`bm_fable`, `bm_lewis`, `bm_daniel`; plus Spanish (`ef_`/`em_`), French
(`ff_siwis`), Hindi (`hf_`/`hm_`), Italian (`if_`/`im_`), Japanese (`jf_`/`jm_`,
limited), Brazilian Portuguese (`pf_`/`pm_`) and Mandarin (`zf_`/`zm_`).
Unknown ids fall back to the default voice.

**App size.** The sherpa-onnx AAR carries native libraries for four ABIs
(uncompressed): arm64-v8a ≈ 32 MB, armeabi-v7a ≈ 22 MB, x86_64 ≈ 35 MB,
x86 ≈ 36 MB (≈ 12 MB each compressed). Ship an App Bundle (Play delivers one
ABI) or restrict ABIs, and you can drop two libraries the Kotlin API does not
load (saves ≈ 5 MB on arm64):

```kotlin
android {
    defaultConfig { ndk { abiFilters += listOf("arm64-v8a") } }
    packaging { jniLibs { excludes += listOf("**/libsherpa-onnx-c-api.so", "**/libsherpa-onnx-cxx-api.so") } }
}
```

Synthesis is CPU-bound (int8 model, 2 threads by default); recent arm64
phones keep up with playback, older devices may pause between sentences.
`agent-kokoro`'s JVM tests use fakes; `KokoroOnDeviceSmokeTest` runs the real
model on a device/emulator once the archive is pushed (see its KDoc).

**Licences.** sherpa-onnx: Apache-2.0. ONNX Runtime (inside the AAR): MIT.
Kokoro-82M weights and the sherpa-onnx model package: Apache-2.0. **espeak-ng**
(used for phonemisation) is **GPL-3.0**: it is compiled into
`libsherpa-onnx-jni.so` and its `espeak-ng-data` is part of the downloaded
model — review this with your licensing policy before shipping `agent-kokoro`.
`agent-frontend` itself does not include any of it.

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
| `agent-kokoro` (optional) | On-device Kokoro TTS provider + model manager               | `agent-frontend`, sherpa-onnx, commons-compress |

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

### Unreleased

**Optional on-device neural voice: Kokoro (`agent-kokoro`)**

- **New optional artifact `agent-kokoro`** — `KokoroTTSProvider` speaks with Kokoro-82M v1.0 on the device via sherpa-onnx 1.13.8 (`com.github.k2-fsa.sherpa-onnx:sherpa-onnx`). Apps that do not add it ship no native libraries. Engine name `"kokoro"` and Kokoro voice ids (`af_heart` default, 54 voices) match iOS and web.
- **Model downloaded once, on first use** — `KokoroModelManager` fetches sherpa-onnx's `kokoro-int8-multi-lang-v1_0.tar.bz2` (configurable `modelUrl`, optional `expectedSha256`), reports `Downloading(bytes, total)` / `Installing` / `Ready` / `Failed(reason)` through `state`, caches it under `noBackupFilesDir/agent-kokoro`, and offers `download()` / `cancelDownload()` / `delete()`. HTTPS only; archive entries cannot escape the cache directory.
- **Falls back to the local-only system voice** while the model is missing, if the engine cannot load, or for the rest of a turn after a synthesis error — text never leaves the device; logs contain reason codes only.
- **`ChatWidgetConfig.localTtsEngine`** (new, default `null`) plugs an on-device engine into `VoiceFactory`'s local path (`KokoroTTS.engine(context)`); `VoiceProviderPlan.localEngine` reports it. When it stands in for an engine, `AndroidTTSProvider` is always local-only.
- **`TTSProvider.prefetch()` / `onTurnStart()`** — new no-op-by-default hooks. `VoiceController` offers queued chunks for prefetch while one is playing, and signals a new turn from `reset()`. Additive; existing providers are unaffected.

### 3.0.1

**`onDisconnect` callback**

- **New `onDisconnect` on `ChatWidgetConfig`** — optional `((String, DisconnectReason) -> Unit)?` fired exactly once per run when the SSE stream is torn down. The first argument is the `runId` of the stream that just closed; the second classifies the teardown so the host can distinguish a user-driven cancel (`DisconnectReason.EXPLICIT`) from a network failure (`DisconnectReason.NETWORK`) or a view/VM/OS lifecycle event (`DisconnectReason.LIFECYCLE`). The library does not perform any network call in response — this is purely a signal for the host to decide what to do (e.g. notify a backend that the user left). Default `null` preserves the existing behaviour.
- **`DisconnectReason` enum** — new public type in the `agent-client` module with cases `EXPLICIT`, `NETWORK`, `LIFECYCLE`, `ERROR`. Mirrors the iOS enum and the web `DisconnectReason` union.
- **`SSEClient.disconnect(reason:)`** — signature extended from `disconnect()` to `disconnect(reason: DisconnectReason = DisconnectReason.EXPLICIT)`. Callers (the view model) choose the reason; the SSE owner no longer has enough context to distinguish "user cancelled" from "view disappeared". A `hasFiredDisconnect` guard ensures the callback fires at most once per run. The callback is delivered on `Dispatchers.Main` under `NonCancellable` so a host cancelling its own scope in response doesn't suppress the signal.
- **`ChatViewModel`** — passes `runId` into `SSEClient.connect(url, headers, runId)`, forwards `config.onDisconnect` to the SSE owner's callback, and tags each of the four `sseClient?.disconnect()` call sites with the right reason: `EXPLICIT` from `cancelRun()` and the new-run-replaces-prior path, `LIFECYCLE` from terminal `run.suspended` / `client.action.required` and from a terminal `run.succeeded` / `run.failed` / `run.cancelled` / `run.timed_out`. A new `onCleared()` override on `ChatViewModel` calls `sseClient?.disconnect(DisconnectReason.LIFECYCLE)` so VM teardown also produces a clean lifecycle signal.
- **Additive** — no breaking changes. Default `onDisconnect = null` makes the entire feature a no-op for existing consumers; every existing call site (`sseClient?.disconnect()`) continues to compile and behave identically.

### 3.0.0

**Unified client versioning + themable transcript** (shared version line with `agent-ios` / web)

- **Synchronized versioning** — iOS, Android, and web clients now share a single version line starting at `3.0.0`. This release carries the same feature set as the prior `0.9.0` tag; the major bump signals production maturity and version alignment across platforms, not a breaking API change.
- **Message-bubble theming** — `ChatAppearance` gains `userBubble`, `assistantBubble`, `systemBubble`, and `link` tokens. `MessageView` now drives bubble background, text color, corner radius, and markdown link tint from these tokens, with fallbacks to host `primaryColor` / adaptive `AgentColors` greys, so existing integrations are unaffected.

### 0.9.0

**`showModelSelector` now gates the model selector** (parity with `agent-ios` 0.10.0)

- **Behaviour change** — `ChatWidgetConfig.showModelSelector` (default `false`) finally controls the composer model selector. The Anthropic composer's model pill is rendered **only** when `showModelSelector == true`. Previously the flag was unused and the pill appeared whenever a model label resolved. Hosts that relied on seeing the model selector must now set `showModelSelector = true` explicitly.
- Model-loading in `ChatViewModel` is unchanged; this is purely a visibility gate on the composer pill.

### 0.8.0

**Model picker, extended thinking & presence orb** (parity with `agent-ios` 0.9.0)

- **Model picker** — `ChatViewModel` gains `availableModels` / `selectedModelId` / `selectedModel` / `selectedModelDisplayName` plus `loadModels()`, populated from `GET /api/agent-runtime/models/`. `runtimeDefaultModelId` captures `ModelsResponse.default` so the composer's model pill pre-selects the runtime's configured fallback until the user picks otherwise.
- **Extended thinking** — per-conversation `extendedThinking` toggle forwarded to the runtime as `thinking: true` (see `packages/python/django_agent_runtime/docs/mobile-protocol-contract.md`). Off by default; reset when the conversation is cleared.
- **Run parameters** — `ResponseStyle` (normal/concise/…) and `ToolAccess` (auto/…) enums plus `researchEnabled` / `webSearchEnabled` flags, surfaced through `setResponseStyle` / `setToolAccess` / `setResearchEnabled` / `setWebSearchEnabled` and serialised into each turn via `runParamsSnapshot()`.
- **`PresenceOrbView`** — new public composable: a breathing, swirling presence sphere (Compose port of the iOS `PresenceOrbView` / `agent_presence_orb.svg`). Renders as a small leading avatar in the widget when `ChatWidgetConfig.showPresenceOrb` is true, or can be embedded directly in a host top bar / splash.
- **`AddToChatSheet` rework** — expanded attachment/configuration sheet (camera + Recents tiles, action rows, tool toggles, connectors), re-skinning automatically for `.classic` hosts.
- **`AgentModel` capability flags** — adds `supportsTools` / `supportsVision` alongside `supportsThinking`, mapped from the runtime's snake_case keys (`supports_tools`, `supports_vision`, `supports_thinking`) via `@SerialName`.
- **`ChatWidgetConfig` additions** — `showInternalTopBar`, `showNewChatButton`, `showPresenceOrb`, ElevenLabs `voiceId` / `voiceModelId` overrides, and `onVideoFullScreenChange` / `onConversationStart` / `onFirstAssistantMessage` host callbacks.

### 0.7.0

**Warm-dark "S'Ai" shell** (parity with `agent-ios` 0.8.0)

- **New configuration types** — `ChatAppearance` (palette, typography, composer style, brand-mark style), `ChatGreetingConfig` (time-of-day greeting + optional user name), and `ChatSidebarConfig` (slide-in drawer items, wordmark, footer). `ChatWidgetConfig` now exposes `appearance`, `greeting`, and `sidebar` properties; defaults flipped to the warm-dark baseline (`#0E0E0E` background, `#D97757` coral accent, `composerStyle = ANTHROPIC`, greeting + sidebar enabled). Set `ChatAppearance.classic()` and `greeting.copy(enabled = false)` / `sidebar.copy(enabled = false)` to restore the pre-redesign look.
- **`GreetingView`** — new centered empty-state composable: brand starburst + system-serif `"Good {morning|afternoon|evening}, {name}"`. `MessageListView` swaps to it when `config.greeting.enabled`.
- **`ChatSidebarView`** — slide-in conversation drawer (~80% of screen width, floored at 280 dp). Header wordmark, nav rows, Recents loaded via the existing `APIClient.loadConversations`, footer avatar + "New chat" pill wired to `ChatViewModel.clearMessages()` / `loadConversation(id)`. Dim backdrop, tap-outside to dismiss.
- **`AnthropicTopBar` + sidebar overlay in `ChatWidgetView`** — bundled widget now mounts a top bar with a circular hamburger button (opens the sidebar) and a "+" new-chat button. When `sidebar.enabled = false` the widget renders exactly as before.
- **`InputView` composer styles** — `ComposerStyle.ANTHROPIC` renders a two-row rounded card (text row + action row with `+` attach, model pill, mic, send circle); `ComposerStyle.CLASSIC` keeps the legacy single-row layout. Voice/STT logic is unchanged and shared between both styles via extracted `MicButton`, `RightActionButton`, and `ModelPill` composables.
- **`AddToChatSheet`** — `ModalBottomSheet` presented by the composer `+` button. Camera + Recents preview tiles, action rows (Add files, Add to project, Choose style…), tool toggles, and connectors list. Re-skins automatically for hosts using `.classic`.
- **Example app** — `HostConfiguration` gains `anthropicShell`, `userName`, `enableTTS`, `enableVoice`. `ScenarioLauncherScreen` adds a "S'Ai shell (warm-dark baseline)" section with `S'Ai home (empty chat)` and `S'Ai home (streaming demo)` scenarios. Legacy scenarios explicitly opt out so they keep the classic look for A/B comparison.
