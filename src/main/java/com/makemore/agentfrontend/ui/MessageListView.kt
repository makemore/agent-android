package com.makemore.agentfrontend.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.makemore.agentfrontend.configuration.ChatWidgetConfig
import com.makemore.agentfrontend.models.Message
import com.makemore.agentfrontend.models.MessageRole
import com.makemore.agentfrontend.models.MessageType

/**
 * Message list view with auto-scroll behavior.
 * Mirrors the iOS MessageListView struct.
 */
@Composable
fun MessageListView(
    messages: List<Message>,
    isLoading: Boolean,
    hasMoreMessages: Boolean,
    loadingMoreMessages: Boolean,
    config: ChatWidgetConfig,
    onLoadMore: () -> Unit,
    onRetry: (Int) -> Unit,
    onEdit: (Int, String) -> Unit
) {
    val listState = rememberLazyListState()
    var editingIndex by remember { mutableStateOf<Int?>(null) }
    var editText by remember { mutableStateOf("") }

    // Filter out tool/system messages when showToolMessages is off
    // Keep a list of (originalIndex, message) so retry/edit callbacks use the right index
    val displayMessages = remember(messages.size, messages.lastOrNull()?.content, config.showToolMessages) {
        messages.mapIndexed { index, msg -> index to msg }.filter { (_, msg) ->
            if (!config.showToolMessages) {
                val isToolMsg = msg.type == MessageType.TOOL_CALL || msg.type == MessageType.TOOL_RESULT
                val isSystemMsg = msg.role == MessageRole.SYSTEM && !isToolMsg
                !isToolMsg && !isSystemMsg
            } else true
        }
    }

    // Auto-scroll to bottom when new messages arrive
    LaunchedEffect(displayMessages.size) {
        if (displayMessages.isNotEmpty()) {
            listState.animateScrollToItem(displayMessages.size - 1 + (if (isLoading) 1 else 0))
        }
    }

    // Auto-scroll when streaming content updates
    val lastContent = displayMessages.lastOrNull()?.second?.content
    LaunchedEffect(lastContent) {
        if (displayMessages.isNotEmpty()) {
            listState.animateScrollToItem(displayMessages.size - 1 + (if (isLoading) 1 else 0))
        }
    }

    if (displayMessages.isEmpty() && !isLoading) {
        // Empty state
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyStateView(config)
        }
    } else {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Load more button
            if (hasMoreMessages) {
                item {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        if (loadingMoreMessages) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp).padding(8.dp))
                        } else {
                            TextButton(onClick = onLoadMore) {
                                Icon(Icons.Default.KeyboardArrowUp, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Load earlier messages", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }

            // Messages
            itemsIndexed(displayMessages, key = { _, pair -> pair.second.id }) { _, (originalIndex, message) ->
                if (editingIndex == originalIndex) {
                    EditMessageView(
                        text = editText,
                        onTextChange = { editText = it },
                        onSave = {
                            onEdit(originalIndex, editText)
                            editingIndex = null
                        },
                        onCancel = { editingIndex = null }
                    )
                } else {
                    MessageView(
                        message = message,
                        config = config,
                        showDebug = config.enableDebugMode,
                        onRetry = if (message.role == MessageRole.USER || message.role == MessageRole.ASSISTANT) {
                            { onRetry(originalIndex) }
                        } else null,
                        onEdit = if (message.role == MessageRole.USER) {
                            {
                                editText = message.content
                                editingIndex = originalIndex
                            }
                        } else null
                    )
                }
            }

            // Loading indicator
            if (isLoading) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Thinking...", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyStateView(config: ChatWidgetConfig) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("💬", style = MaterialTheme.typography.displayMedium)
        Spacer(modifier = Modifier.height(16.dp))
        Text(config.emptyStateTitle, style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            config.emptyStateMessage,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun EditMessageView(
    text: String,
    onTextChange: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier.fillMaxWidth(),
                minLines = 2
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onCancel) { Text("Cancel") }
                Button(onClick = onSave) { Text("Save & Resend") }
            }
        }
    }
}

