package com.makemore.agentfrontend.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import com.makemore.agentfrontend.configuration.ChatAppearance
import com.makemore.agentfrontend.configuration.ChatWidgetConfig
import com.makemore.agentfrontend.networking.APIClient
import com.makemore.agentfrontend.viewmodels.ChatViewModel
import com.makemore.agentfrontend.voice.VoiceController
import com.makemore.agentfrontend.voice.VoiceFactory

/**
 * Main chat widget composable — provides the chat flow (messages, error banner, input).
 * Navigation, headers, and sidebars are app-level concerns.
 * Mirrors the iOS ChatWidgetView struct.
 */
@Composable
fun ChatWidgetView(
    viewModel: ChatViewModel,
    config: ChatWidgetConfig,
    modifier: Modifier = Modifier,
    voiceController: VoiceController? = null,
    /** Used by the slide-in sidebar to populate "Recents". Defaults
     *  to the viewModel's own client so direct callers don't have
     *  to pass a second copy; hosts that build their own client can
     *  inject it explicitly. */
    apiClient: APIClient? = null,
) {
    var showSystemPicker by remember { mutableStateOf(false) }
    var showSidebar by remember { mutableStateOf(false) }
    // Index and working text of the user message being edited, or null. While
    // set, the edit card replaces the composer; saving commits through
    // `ChatViewModel.editMessage`, which truncates the transcript and
    // restarts the conversation from that turn. Mirrors the iOS split, where
    // the list reports the intent and the host owns the UI and the commit.
    // Id of the message the per-row speaker button last handed to the voice
    // controller. Only meaningful while the controller is speaking; cleared
    // when playback ends so the row's stop button reverts to a speaker on its
    // own. Mirrors the iOS `speakingMessageId`.
    var speakingMessageId by remember { mutableStateOf<String?>(null) }
    var editingMessageIndex by remember { mutableStateOf<Int?>(null) }
    var editingText by remember { mutableStateOf("") }
    val context = LocalContext.current
    val resolvedApiClient = apiClient ?: viewModel.apiClient

    // Build the controller lazily on first composition. Host apps that
    // need a custom provider pass one in via [voiceController]. The
    // controller mirrors `config.enableTTS` so the button toggles cleanly.
    val controller = remember(viewModel, config) {
        voiceController ?: VoiceFactory.makeController(
            context = context,
            config = config,
            apiClient = viewModel.apiClient,
            voiceId = config.voiceId,
            modelId = config.voiceModelId,
        )
    }

    // Bind to the viewModel so SSE deltas flow into TTS playback. Tear
    // down the native engine when the composable leaves the tree.
    DisposableEffect(controller) {
        viewModel.voiceController = controller
        onDispose {
            viewModel.voiceController = null
            controller.dispose()
        }
    }

    // Playback ending on its own has to clear the latch, or the row that was
    // playing keeps showing a stop button with nothing to stop.
    LaunchedEffect(controller.isSpeaking.value) {
        if (!controller.isSpeaking.value) speakingMessageId = null
    }

    // Restore conversation on first composition
    LaunchedEffect(Unit) {
        viewModel.restoreConversationIfNeeded()
        if (config.showSystemPicker) {
            viewModel.loadSystems()
        }
    }

    val focusManager = LocalFocusManager.current

    // `imePadding()` lifts the input row above the soft keyboard when the
    // host Activity uses edge-to-edge (in which case `adjustResize` no
    // longer auto-shrinks the layout and the IME inset must be consumed
    // by Compose).
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(config.appearance.background)
            .imePadding()
            .pointerInput(Unit) { detectTapGestures(onTap = { focusManager.clearFocus() }) }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Built-in top bar (hamburger + new-chat pencil). Hosts
            // that provide their own navigation chrome set
            // `config.showInternalTopBar = false` and surface
            // equivalents themselves.
            if (config.showInternalTopBar) {
                AnthropicTopBar(
                    appearance = config.appearance,
                    showSidebarButton = config.sidebar.enabled,
                    showNewChatButton = config.showNewChatButton,
                    onOpenSidebar = { showSidebar = true },
                    onNewChat = { viewModel.clearMessages() },
                )
            }

            // Context-usage banner. Server-driven: renders only when
            // the runtime has shipped at least one `context.usage`
            // event for this conversation (i.e. `contextTokens` is
            // non-null on the view model). The denominator and
            // progress bar appear when the runtime also shipped a
            // `context_window` for the active model. There is no
            // client-side estimation — the banner stays hidden until
            // the server has something to show.
            viewModel.contextTokens.value?.let { tokens ->
                ContextUsageBanner(
                    totalTokens = tokens,
                    contextWindow = viewModel.contextWindow.value,
                    modelId = viewModel.contextModelId.value,
                )
            }

            // Messages list. `agentIsSpeaking` propagates the TTS
            // playback state to the latest assistant row so its avatar
            // glows while audio is in flight. The S'Ai orb is rendered
            // as a per-message avatar inside `MessageView` (gated by
            // `config.showPresenceOrb`) rather than as a fixed row
            // above the list, so it stays anchored to the assistant's
            // identity in the scrollback instead of floating at the
            // top of the chrome.
            Box(modifier = Modifier.weight(1f)) {
                // Keyed on the conversation so switching chats remounts the
                // list: its "land on the newest message" is a once-per-mount
                // effect, and without the key a second conversation would
                // open wherever the previous one was scrolled to.
                key(viewModel.conversationId.value) {
                MessageListView(
                    messages = viewModel.messages,
                    isLoading = viewModel.isLoading.value,
                    hasMoreMessages = viewModel.hasMoreMessages.value,
                    loadingMoreMessages = viewModel.loadingMoreMessages.value,
                    config = config,
                    onLoadMore = { viewModel.loadMoreMessages() },
                    onRetry = { index -> viewModel.retryMessage(index) },
                    onBeginEdit = { index, content ->
                        editingText = content
                        editingMessageIndex = index
                    },
                    // Toggle: tapping the speaker on the playing message
                    // stops it; tapping any other switches playback to that
                    // one. An explicit play tap also re-enables the
                    // controller, so playback recovers after a provider
                    // failure without restarting the app.
                    onSpeak = if (config.enableTTS) { message ->
                        if (speakingMessageId == message.id && controller.isSpeaking.value) {
                            controller.stop()
                            speakingMessageId = null
                        } else {
                            speakingMessageId = message.id
                            // speakOnce, not setEnabled + finishTurn: this
                            // must not switch on the composer's read-replies
                            // toggle as a side effect.
                            controller.speakOnce(message.content)
                        }
                    } else null,
                    speakingMessageId = speakingMessageId.takeIf { controller.isSpeaking.value },
                    // Same reasoning as the composer: the halo marks the
                    // LATEST assistant message, so a one-off tap on an older
                    // message would glow the wrong row.
                    agentIsSpeaking = controller.isSpeaking.value && !controller.isOneOffPlayback.value,
                    subAgentActivity = viewModel.subAgentActivity.value,
                )
                }
            }

            // Error display
            viewModel.error.value?.let { errorMessage ->
                ErrorBanner(
                    message = errorMessage,
                    onDismiss = { viewModel.error.value = null }
                )
            }

            // System picker / TTS toggle row — bottom-right, above input.
            if (config.showSystemPicker || config.showTTSButton) {
                SystemAndVoiceRow(
                    viewModel = viewModel,
                    config = config,
                    voiceController = controller,
                    onShowPicker = { showSystemPicker = true },
                )
            }

            // Input form
            val editIndex = editingMessageIndex
            if (editIndex != null) {
                EditMessageView(
                    text = editingText,
                    onTextChange = { editingText = it },
                    onSave = {
                        val content = editingText
                        editingMessageIndex = null
                        editingText = ""
                        // Truncates the transcript at that turn and restarts
                        // the conversation from it — same contract as the iOS
                        // `editMessage(at:newContent:)` commit.
                        viewModel.editMessage(editIndex, content)
                    },
                    onCancel = {
                        editingMessageIndex = null
                        editingText = ""
                    },
                )
            } else {
            InputView(
                config = config,
                isLoading = viewModel.isLoading.value,
                // A per-message tap must not turn the send button into
                // "Stop speaking" or arm barge-in — the composer's speaking
                // state is about the agent's turn, not scrollback playback.
                isAgentSpeaking = controller.isSpeaking.value && !controller.isOneOffPlayback.value,
                voiceController = controller,
                onSend = { content, files ->
                    viewModel.sendMessage(content, files)
                },
                onCancel = { viewModel.cancelRun() },
                viewModel = viewModel,
            )
            }
        }

        // Slide-in sidebar overlay. Matches the iOS implementation: the
        // panel covers ~80% of the screen width, animates in from the
        // left, and is dismissed by tapping the scrim, picking a
        // conversation, or starting a new chat.
        if (config.sidebar.enabled) {
            AnimatedVisibility(
                visible = showSidebar,
                enter = slideInHorizontally(initialOffsetX = { -it }),
                exit = slideOutHorizontally(targetOffsetX = { -it }),
            ) {
                ChatSidebarView(
                    config = config,
                    apiClient = resolvedApiClient,
                    onDismiss = { showSidebar = false },
                    onNewChat = {
                        viewModel.clearMessages()
                        showSidebar = false
                    },
                    onSelectConversation = { conv ->
                        viewModel.loadConversation(conv.id)
                        showSidebar = false
                    },
                )
            }
        }
    }

    // System picker bottom sheet
    if (showSystemPicker) {
        SystemPickerDialog(
            systems = viewModel.systems,
            selectedSystemSlug = viewModel.selectedSystemSlug.value,
            selectedVersion = viewModel.selectedSystemVersion.value,
            isLoading = viewModel.isLoadingSystems.value,
            onSelectSystem = { viewModel.selectSystem(it) },
            onSelectVersion = { viewModel.selectSystemVersion(it) },
            onDismiss = { showSystemPicker = false }
        )
    }
}

@Composable
private fun ErrorBanner(message: String, onDismiss: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("⚠️", style = MaterialTheme.typography.bodySmall)
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                Text("✕", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun SystemAndVoiceRow(
    viewModel: ChatViewModel,
    config: ChatWidgetConfig,
    voiceController: VoiceController,
    onShowPicker: () -> Unit,
) {
    val slug = viewModel.selectedSystemSlug.value
    val system = slug?.let { s -> viewModel.systems.firstOrNull { it.slug == s } }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (config.showSystemPicker) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                system?.let {
                    Text(it.name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    it.activeVersion?.let { v ->
                        Text("v$v", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                    }
                }
            }
        }
        Spacer(modifier = Modifier.weight(1f))

        // The speak-aloud toggle lives in the composer (see
        // InputView.SpeakRepliesButton), matching iOS — a floating copy
        // here as well put two speaker buttons on screen doing the same
        // thing. `showTTSButton` still gates the composer control.

        if (config.showSystemPicker) {
            IconButton(onClick = onShowPicker, modifier = Modifier.size(36.dp)) {
                Text("⚙️")
            }
        }
    }
}

/**
 * Top bar shown when `config.showInternalTopBar` is `true`. Left
 * circular button opens the sidebar (only rendered when the sidebar
 * overlay is enabled). Right circular button starts a fresh
 * conversation (gated by `config.showNewChatButton`). Both are drawn
 * on the surface colour so they pop against the warm-dark background
 * without an outline.
 */
@Composable
private fun AnthropicTopBar(
    appearance: ChatAppearance,
    showSidebarButton: Boolean,
    showNewChatButton: Boolean,
    onOpenSidebar: () -> Unit,
    onNewChat: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showSidebarButton) {
            IconButton(
                onClick = onOpenSidebar,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(appearance.surface),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Menu,
                    contentDescription = "Open conversations",
                    tint = appearance.textPrimary,
                )
            }
        }
        Spacer(modifier = Modifier.weight(1f))
        if (showNewChatButton) {
            IconButton(
                onClick = onNewChat,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(appearance.surface),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Edit,
                    contentDescription = "New conversation",
                    tint = appearance.textPrimary,
                )
            }
        }
    }
}

@Composable
private fun VoiceToggleButton(
    controller: VoiceController,
    primaryColor: Color,
) {
    val enabled = controller.isEnabled.value
    val speaking = controller.isSpeaking.value
    val tint = if (enabled) primaryColor else MaterialTheme.colorScheme.onSurfaceVariant
    val description = if (enabled) "Disable voice output" else "Enable voice output"

    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        IconButton(
            onClick = { controller.setEnabled(!enabled) },
            modifier = Modifier.size(36.dp),
        ) {
            val icon = when {
                !enabled -> Icons.AutoMirrored.Filled.VolumeOff
                speaking -> Icons.Filled.GraphicEq
                else -> Icons.AutoMirrored.Filled.VolumeUp
            }
            Icon(imageVector = icon, contentDescription = description, tint = tint)
        }
    }
}


