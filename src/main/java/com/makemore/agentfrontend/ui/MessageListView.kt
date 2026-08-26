package com.makemore.agentfrontend.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.launch
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
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
 * Message list view: a right-side-up transcript in a **plain, eagerly
 * measured `Column`** — deliberately not a `LazyColumn`.
 *
 * Every scroll defect this file has had — overshooting scroll-to-bottom,
 * landing on blank space, streaming bounce, a jump button that flickered —
 * came from one cause: lazy containers *estimate* the height of off-screen
 * rows, so any scroll target computed from that geometry lands wherever the
 * estimate happens to be wrong. With every row measured for real,
 * `scrollTo(maxValue)` is exact. Mirrors the iOS `MessageListView`, which
 * made the same move for the same reason.
 *
 * Eager rendering is affordable because the product bounds conversation
 * length; rendering a few hundred text rows up front is well within budget.
 * Profile before raising that bound materially.
 *
 * Scroll behaviour:
 *  * **Opening a conversation** lands on the newest message, without
 *    animating in from the top.
 *  * **Sending a message** pins the list to the bottom.
 *  * **Streaming** follows only while the reader is already at the bottom.
 *    The scroll position itself is the state — there is no latch to get
 *    stuck: scrolling up disengages, scrolling back down re-engages.
 *  * **A jump-to-bottom button** appears once the reader is away from the
 *    newest message, driven by that same derived state, so the button
 *    appearing is exactly the signal that following has stopped.
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
    /// Begin editing a sent user message: `(index, currentContent)`.
    /// The list no longer hosts the edit UI — the host presents its edit card
    /// in place of the composer and owns the commit, same split as iOS.
    /// `null` hides the Edit affordance entirely.
    onBeginEdit: ((Int, String) -> Unit)? = null,
    /// Speak a message aloud, or stop it if already playing. `null` hides the
    /// affordance entirely (TTS disabled for this host).
    onSpeak: ((Message) -> Unit)? = null,
    /// Id of the message currently playing, so only that row shows stop.
    speakingMessageId: String? = null,
    /// Fired after a row copies its content, so the host can confirm it.
    onCopy: (() -> Unit)? = null,
    // `true` when the agent's TTS playback is in flight. Propagated
    // down to the latest assistant `MessageView` so its avatar can
    // glow without recomputing per-row.
    agentIsSpeaking: Boolean = false,
    // Transient sub-agent activity. In pill mode and while a bracket is
    // open, the activity pill renders in place of the "Thinking…" spinner.
    // Empty/inactive in bubbles mode — falls back to the spinner.
    subAgentActivity: SubAgentActivityState = SubAgentActivityState(),
) {
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()

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

    // Follow state is derived from position, not latched — but it has to be
    // read against the height the content had BEFORE it grew. `maxValue`
    // updates as soon as a row gets taller, while `value` does not move, so
    // comparing the two after growth always reports "not at the bottom" and
    // following would never engage. `previousMax` is that pre-growth height.
    var previousMax by remember { mutableIntStateOf(0) }

    // Live position, for the jump button. Once following works this stays
    // true throughout a stream, so the button does not flicker.
    val atBottom by remember {
        derivedStateOf { scrollState.value >= scrollState.maxValue - BOTTOM_SLOP_PX }
    }

    // Landing and following are ONE long-lived collector, not two effects
    // keyed on `maxValue`. A keyed `LaunchedEffect` is cancelled and
    // relaunched every time its key changes, and during a stream that is
    // every frame — so the `scrollTo` in flight gets cancelled before it
    // lands, `value` falls behind `maxValue`, and the next pass reads that
    // gap as "the reader scrolled up" and detaches. That is the failure
    // where following holds for a moment and then drops out mid-reply.
    // A single collector processes every height change in order and is
    // never cancelled underneath itself.
    var hasLanded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        snapshotFlow { scrollState.maxValue }.collect { max ->
            if (max == 0) return@collect
            val grewFrom = previousMax
            previousMax = max

            // Opening a conversation lands on the newest message. Jumps
            // rather than animates: animating from the top would play the
            // whole history past the reader on every open.
            if (!hasLanded) {
                scrollState.scrollTo(max)
                hasLanded = true
                return@collect
            }

            if (scrollState.value < grewFrom - BOTTOM_SLOP_PX) return@collect
            scrollState.scrollTo(max)
        }
    }

    // Sending pins the list to the bottom, so the just-sent message settles
    // above the composer. Instant rather than animated: the reply starts
    // growing the content immediately, and an in-flight animation would be
    // fighting the follow below for the same scroll position.
    val lastDisplayMessage = displayMessages.lastOrNull()?.second
    var lastSeenTailId by remember { mutableStateOf(lastDisplayMessage?.id) }
    LaunchedEffect(lastDisplayMessage?.id) {
        val previousTailId = lastSeenTailId
        lastSeenTailId = lastDisplayMessage?.id
        if (lastDisplayMessage?.role != MessageRole.USER) return@LaunchedEffect
        if (lastDisplayMessage.id == previousTailId) return@LaunchedEffect
        // Skip the transition out of an empty list (conversation restore /
        // first message of a brand-new conversation) — the open-scroll above
        // already put us at the bottom.
        if (previousTailId == null) return@LaunchedEffect
        scrollState.scrollTo(scrollState.maxValue)
        // Re-arm following even if the reader had scrolled up before sending.
        previousMax = scrollState.maxValue
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
        Box(modifier = Modifier.fillMaxSize()) {
            // Single message row, shared by the head of the list and
            // the current-turn group so both render identically.
            val messageRow: @Composable (Pair<Int, Message>) -> Unit = { (originalIndex, message) ->
                run {
                    MessageView(
                        message = message,
                        config = config,
                        showDebug = config.enableDebugMode,
                        onRetry = if (message.role == MessageRole.USER || message.role == MessageRole.ASSISTANT) {
                            { onRetry(originalIndex) }
                        } else null,
                        onEdit = if (message.role == MessageRole.USER && onBeginEdit != null) {
                            { onBeginEdit(originalIndex, message.content) }
                        } else null,
                        onSpeak = onSpeak?.let { speak -> { speak(message) } },
                        isSpeaking = message.id == speakingMessageId,
                        onCopy = onCopy,
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
            // The spinner covers the wait BEFORE the first token, and stops
            // there. Once the reply has text, the text is itself the progress
            // — a spinner beneath a visibly typing answer reads as a second,
            // stalled request.
            //
            // Deliberately keyed on the reply being empty rather than on
            // `isStreaming`: the typewriter drain keeps that flag set after
            // the run's terminal event, so a spinner tied to it outlives the
            // reply it was reporting on.
            val awaitingFirstToken = displayMessages.lastOrNull()?.second.let { tail ->
                tail == null || tail.role != MessageRole.ASSISTANT || tail.content.isBlank()
            }
            val statusIndicator: @Composable () -> Unit = {
                if (pillActive) {
                    SubAgentActivityPillView(
                        activity = subAgentActivity,
                        appearance = config.appearance,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                    )
                } else if (isLoading && awaitingFirstToken) {
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

            // Plain Column: every row measured, every scroll target exact.
            // See the file comment for why this is the load-bearing decision.
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (hasMoreMessages) {
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

                // Flat: no viewport-height "current turn" group. That existed
                // only so a scroll-to-top could not clamp; pinning to the
                // bottom needs no reserved space, and the reserved space was
                // itself what made a scroll to the tail land at its top.
                displayMessages.forEach { pair ->
                    key(pair.second.id) { messageRow(pair) }
                }
                statusIndicator()
            }

            // Jump-to-bottom, shown only once the reader has scrolled away
            // from the newest message — the same condition that detaches
            // stream-following, so the button appearing is exactly the
            // signal that the list has stopped following.
            AnimatedVisibility(
                visible = !atBottom,
                enter = fadeIn() + scaleIn(initialScale = 0.85f),
                exit = fadeOut() + scaleOut(targetScale = 0.85f),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 12.dp),
            ) {
                val appearance = config.appearance
                Surface(
                    onClick = { scope.launch { scrollState.animateScrollTo(scrollState.maxValue) } },
                    shape = CircleShape,
                    color = appearance.surface,
                    shadowElevation = 4.dp,
                    modifier = Modifier
                        .size(36.dp)
                        .semantics { contentDescription = "Scroll to latest message" },
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.KeyboardArrowDown,
                            contentDescription = null,
                            tint = appearance.textSecondary,
                            modifier = Modifier.size(20.dp),
                        )
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

/**
 * How close to the bottom still counts as "at the bottom" when deciding
 * whether to follow a streaming reply. A couple of pixels of rounding in the
 * layout should not detach following.
 */
private const val BOTTOM_SLOP_PX = 8
