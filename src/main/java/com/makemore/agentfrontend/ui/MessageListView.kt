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
import androidx.compose.ui.platform.LocalDensity
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

    // Auto-scroll behaviour:
    // - A `followStream` flag tracks whether the list is pinned to the
    //   bottom. It flips off the moment the user drags upward and flips
    //   back on only when they scroll to (or past) the last item again.
    // - A new row (new bubble / tool call / thinking spinner) animates
    //   scroll when `followStream` is true.
    // - Streaming content deltas fire at ~30 Hz; while `followStream` is
    //   true they snap with a non-animated `scrollToItem` anchored to the
    //   bottom of the last item (scrollOffset = Int.MAX_VALUE). While
    //   `followStream` is false the snap is skipped entirely so the user
    //   can read earlier content without being yanked back down.
    val targetIndex = (displayMessages.size - 1 + (if (isLoading) 1 else 0)).coerceAtLeast(0)

    var followStream by remember { mutableStateOf(true) }

    // Convert the dp threshold to px once per density so the snapshotFlow
    // body stays cheap. Mirrors iOS ChatWidgetConfig.nearBottomThresholdPt:
    // the user can scroll up to this many dp away from the bottom and we
    // still consider the list "at bottom" for streaming auto-follow.
    val density = LocalDensity.current
    val thresholdPx = remember(density, config.nearBottomThresholdPt) {
        with(density) { config.nearBottomThresholdPt.dp.roundToPx() }
    }

    LaunchedEffect(listState, thresholdPx) {
        snapshotFlow {
            val layout = listState.layoutInfo
            if (layout.totalItemsCount == 0) return@snapshotFlow true
            val lastVisible = layout.visibleItemsInfo.lastOrNull()
                ?: return@snapshotFlow false
            // Last item must be the tail item — if the user has scrolled
            // far enough that even the last index has dropped off the
            // visible window, we are clearly not near bottom.
            if (lastVisible.index < layout.totalItemsCount - 1) return@snapshotFlow false
            // Overflow: how many px the last item extends below the
            // viewport bottom. ≤0 means it fits fully (true bottom);
            // 0..thresholdPx means the user has scrolled up a little but
            // still wants to follow; >thresholdPx means stop pulling.
            val lastBottom = lastVisible.offset + lastVisible.size
            val overflow = lastBottom - layout.viewportEndOffset
            overflow <= thresholdPx
        }.collect { atBottom -> followStream = atBottom }
    }

    LaunchedEffect(displayMessages.size, isLoading) {
        if ((displayMessages.isNotEmpty() || isLoading) && followStream) {
            listState.animateScrollToItem(targetIndex, scrollOffset = Int.MAX_VALUE)
        }
    }

    val lastContent = displayMessages.lastOrNull()?.second?.content
    LaunchedEffect(lastContent) {
        if (displayMessages.isEmpty()) return@LaunchedEffect
        // Host-app opt-out: when streaming follow is disabled the list
        // stays put and the user controls scrolling while the reply
        // generates. New rows (count change, isLoading) are still pinned
        // by the other LaunchedEffect.
        if (!config.followStreamingEnabled) return@LaunchedEffect
        if (followStream && !listState.isScrollInProgress) {
            listState.scrollToItem(targetIndex, scrollOffset = Int.MAX_VALUE)
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

