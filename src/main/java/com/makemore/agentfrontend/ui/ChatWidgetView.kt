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

            // Messages list. `agentIsSpeaking` propagates the TTS
            // playback state to the latest assistant row so its avatar
            // glows while audio is in flight. The S'Ai orb is rendered
            // as a per-message avatar inside `MessageView` (gated by
            // `config.showPresenceOrb`) rather than as a fixed row
            // above the list, so it stays anchored to the assistant's
            // identity in the scrollback instead of floating at the
            // top of the chrome.
            Box(modifier = Modifier.weight(1f)) {
                MessageListView(
                    messages = viewModel.messages,
                    isLoading = viewModel.isLoading.value,
                    hasMoreMessages = viewModel.hasMoreMessages.value,
                    loadingMoreMessages = viewModel.loadingMoreMessages.value,
                    config = config,
                    onLoadMore = { viewModel.loadMoreMessages() },
                    onRetry = { index -> viewModel.retryMessage(index) },
                    onEdit = { index, content -> viewModel.editMessage(index, content) },
                    agentIsSpeaking = controller.isSpeaking.value,
                )
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
            InputView(
                config = config,
                isLoading = viewModel.isLoading.value,
                isAgentSpeaking = controller.isSpeaking.value,
                voiceController = controller,
                onSend = { content, files ->
                    viewModel.sendMessage(content, files)
                },
                onCancel = { viewModel.cancelRun() },
                viewModel = viewModel,
            )
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

        // TTS toggle: speaker icon, fills/animates while playback is
        // active. Tapping toggles `isEnabled` on the controller —
        // disabling cuts off any in-flight audio so the user isn't
        // trapped listening to the rest.
        if (config.showTTSButton) {
            VoiceToggleButton(
                controller = voiceController,
                primaryColor = config.primaryColor,
            )
            Spacer(modifier = Modifier.size(8.dp))
        }

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


