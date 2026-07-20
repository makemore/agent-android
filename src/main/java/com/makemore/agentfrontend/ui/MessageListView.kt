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
import com.makemore.agentfrontend.configuration.ChatAppearance
import com.makemore.agentfrontend.configuration.ChatWidgetConfig
import com.makemore.agentfrontend.models.Message
import com.makemore.agentfrontend.models.MessageRole
import com.makemore.agentfrontend.models.MessageType
import com.makemore.agentfrontend.models.SubAgentActivityState

/**
 * Message list view. One automated scroll only: when the user sends a
 * message, the list scrolls so the just-sent user message lands at the
 * top of the viewport, and the agent's reply streams into the reserved
 * space below it (ChatGPT-style). No other auto-scroll — streaming
 * text growth never moves the list, so the user stays in control.
 *
 * The mechanism mirrors the iOS `MessageListView`: the "current turn"
 * (last user message + everything after it + the status indicator) is
 * rendered as a single item with `minHeight == viewport height`, so
 * the LazyColumn always has enough content below the user message to
 * bring it to the top (otherwise the scroll clamps at the bottom of
 * the content and visibly no-ops).
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
    onEdit: (Int, String) -> Unit,
    // `true` when the agent's TTS playback is in flight. Propagated
    // down to the latest assistant `MessageView` so its avatar can
    // glow without recomputing per-row.
    agentIsSpeaking: Boolean = false,
    // Transient sub-agent activity. In pill mode and while a bracket is
    // open, the activity pill renders in place of the "Thinking…" spinner.
    // Empty/inactive in bubbles mode — falls back to the spinner.
    subAgentActivity: SubAgentActivityState = SubAgentActivityState(),
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

    // The "current turn" starts at the last user message. Everything
    // from there down (plus the status indicator) renders as ONE
    // LazyColumn item whose minHeight is the viewport height — see
    // the class doc. Head rows keep their own per-message items.
    val turnStart = displayMessages.indexOfLast { it.second.role == MessageRole.USER }

    // User-submit scroll-to-top. When the new tail message is a user
    // message, scroll so the turn group (whose top edge is the new
    // user message) lands at the top of the viewport. The agent's
    // reply then streams in below — no further auto-scroll.
    // Pagination is the only other automated scroll action and is
    // wired by the host via `onLoadMore`.
    val lastDisplayMessage = displayMessages.lastOrNull()?.second
    var lastSeenTailId by remember { mutableStateOf(lastDisplayMessage?.id) }
    LaunchedEffect(lastDisplayMessage?.id) {
        val previousTailId = lastSeenTailId
        lastSeenTailId = lastDisplayMessage?.id
        if (lastDisplayMessage?.role != MessageRole.USER) return@LaunchedEffect
        if (lastDisplayMessage.id == previousTailId) return@LaunchedEffect
        // Skip the transition out of an empty list (conversation
        // restore / first message of a brand-new conversation) —
        // mirrors the iOS guard on `previousTailId == nil`.
        if (previousTailId == null) return@LaunchedEffect
        if (turnStart < 0) return@LaunchedEffect
        // The turn group is one item preceded by the head rows and
        // the optional load-more item. `scrollOffset = 0` anchors
        // its top edge (the just-sent user message) to the top of
        // the viewport; the group's minHeight guarantees there is
        // enough content below for the scroll not to clamp.
        val turnItemIndex = (if (hasMoreMessages) 1 else 0) + turnStart
        listState.animateScrollToItem(turnItemIndex, scrollOffset = 0)
    }

    // Resolve once per render: the id of the most recent assistant
    // text message so only its avatar gets the speaking-halo
    // treatment. Skips tool / sub-agent / context / content-block
    // rows so the glow always lands on a real reply.
    val latestAssistantId: String? = remember(messages.size, messages.lastOrNull()?.id) {
        messages.lastOrNull { msg ->
            msg.role == MessageRole.ASSISTANT &&
                msg.type != MessageType.TOOL_CALL &&
                msg.type != MessageType.TOOL_RESULT &&
                msg.type != MessageType.SUB_AGENT_START &&
                msg.type != MessageType.SUB_AGENT_END &&
                msg.type != MessageType.AGENT_CONTEXT &&
                msg.type != MessageType.CONTENT_BLOCKS
        }?.id
    }

    if (displayMessages.isEmpty() && !isLoading) {
        // Empty state — when the host opts in via `greeting.enabled`
        // we render the warm-dark serif greeting; otherwise we fall
        // back to the legacy speech-bubble placeholder so existing
        // consumers see no change.
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (config.greeting.enabled) {
                GreetingView(config = config)
            } else {
                EmptyStateView(config)
            }
        }
    } else {
        // BoxWithConstraints supplies the viewport height used as the
        // current turn's minHeight — see the class doc.
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val viewportHeight = maxHeight

            // Single message row, shared by the head of the list and
            // the current-turn group so both render identically.
            val messageRow: @Composable (Pair<Int, Message>) -> Unit = { (originalIndex, message) ->
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
                        } else null,
                        showAgentAvatar = config.showPresenceOrb,
                        agentAvatarSpeaking = agentIsSpeaking && message.id == latestAssistantId,
                    )
                }
            }

            // Activity indicator. In pill mode, an open sub-agent bracket
            // renders the activity pill in place of the generic spinner so
            // the user sees which specialist is running and a tail of its
            // narration. Otherwise fall back to the "Thinking…" spinner.
            val pillActive = subAgentActivity.isActive &&
                config.appearance.subAgentActivityStyle == ChatAppearance.SubAgentActivityStyle.PILL
            val statusIndicator: @Composable () -> Unit = {
                if (pillActive) {
                    SubAgentActivityPillView(
                        activity = subAgentActivity,
                        appearance = config.appearance,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                    )
                } else if (isLoading) {
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

                if (turnStart >= 0) {
                    // Head rows: everything before the current turn.
                    itemsIndexed(
                        displayMessages.subList(0, turnStart),
                        key = { _, pair -> pair.second.id }
                    ) { _, pair -> messageRow(pair) }

                    // Current turn: last user message + everything after
                    // it + the status indicator, as one viewport-height
                    // item so the user-submit scroll never clamps.
                    item(key = "current-turn-${displayMessages[turnStart].second.id}") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = viewportHeight - 16.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            displayMessages.subList(turnStart, displayMessages.size).forEach { pair ->
                                messageRow(pair)
                            }
                            statusIndicator()
                        }
                    }
                } else {
                    itemsIndexed(displayMessages, key = { _, pair -> pair.second.id }) { _, pair ->
                        messageRow(pair)
                    }
                    item { statusIndicator() }
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

