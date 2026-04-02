package com.makemore.agentfrontend.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import com.makemore.agentfrontend.configuration.ChatWidgetConfig
import com.makemore.agentfrontend.viewmodels.ChatViewModel

/**
 * Main chat widget composable — provides the chat flow (messages, error banner, input).
 * Navigation, headers, and sidebars are app-level concerns.
 * Mirrors the iOS ChatWidgetView struct.
 */
@Composable
fun ChatWidgetView(
    viewModel: ChatViewModel,
    config: ChatWidgetConfig,
    modifier: Modifier = Modifier
) {
    var showSystemPicker by remember { mutableStateOf(false) }

    // Restore conversation on first composition
    LaunchedEffect(Unit) {
        viewModel.restoreConversationIfNeeded()
        if (config.showSystemPicker) {
            viewModel.loadSystems()
        }
    }

    val focusManager = LocalFocusManager.current

    Column(modifier = modifier
        .fillMaxSize()
        .pointerInput(Unit) { detectTapGestures(onTap = { focusManager.clearFocus() }) }
    ) {
        // Messages list
        Box(modifier = Modifier.weight(1f)) {
            MessageListView(
                messages = viewModel.messages,
                isLoading = viewModel.isLoading.value,
                hasMoreMessages = viewModel.hasMoreMessages.value,
                loadingMoreMessages = viewModel.loadingMoreMessages.value,
                config = config,
                onLoadMore = { viewModel.loadMoreMessages() },
                onRetry = { index -> viewModel.retryMessage(index) },
                onEdit = { index, content -> viewModel.editMessage(index, content) }
            )
        }

        // Error display
        viewModel.error.value?.let { errorMessage ->
            ErrorBanner(
                message = errorMessage,
                onDismiss = { viewModel.error.value = null }
            )
        }

        // System picker button
        if (config.showSystemPicker) {
            SystemPickerButton(
                viewModel = viewModel,
                onShowPicker = { showSystemPicker = true }
            )
        }

        // Input form
        InputView(
            config = config,
            isLoading = viewModel.isLoading.value,
            onSend = { content, files ->
                viewModel.sendMessage(content, files)
            },
            onCancel = { viewModel.cancelRun() }
        )
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
private fun SystemPickerButton(
    viewModel: ChatViewModel,
    onShowPicker: () -> Unit
) {
    val slug = viewModel.selectedSystemSlug.value
    val system = slug?.let { s -> viewModel.systems.firstOrNull { it.slug == s } }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            system?.let {
                Text(it.name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                it.activeVersion?.let { v ->
                    Text("v$v", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                }
            }
        }
        Spacer(modifier = Modifier.weight(1f))
        IconButton(onClick = onShowPicker, modifier = Modifier.size(32.dp)) {
            Text("⚙️")
        }
    }
}


