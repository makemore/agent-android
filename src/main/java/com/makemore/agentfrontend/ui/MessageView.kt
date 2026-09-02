package com.makemore.agentfrontend.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.makemore.agentfrontend.configuration.ChatAppearance
import com.makemore.agentfrontend.configuration.ChatWidgetConfig
import com.makemore.agentfrontend.models.Message
import com.makemore.agentfrontend.models.MessageRole
import com.makemore.agentfrontend.models.MessageType
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownHeader
import com.mikepenz.markdown.compose.elements.MarkdownHighlightedCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownHighlightedCodeFence
import com.mikepenz.markdown.model.markdownPadding
import com.mikepenz.markdown.model.rememberMarkdownState
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.SyntaxThemes
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Individual message view.
 * Mirrors the iOS MessageView struct.
 */
@Composable
fun MessageView(
    message: Message,
    config: ChatWidgetConfig,
    showDebug: Boolean = false,
    onRetry: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
    /// Fired after the content is on the clipboard, so the host can confirm
    /// the copy. The copy itself happens here — the hook is notification only,
    /// same contract as iOS `onCopy`.
    onCopy: (() -> Unit)? = null,
    /// Speak this message aloud, or stop it if it is already playing.
    /// `null` hides the affordance (TTS disabled for this host).
    onSpeak: (() -> Unit)? = null,
    /// `true` when THIS message is the one currently playing, so the button
    /// shows stop rather than play.
    isSpeaking: Boolean = false,
    onBlockAction: ((com.makemore.agentfrontend.models.BlockAction) -> Unit)? = null,
    // When `true` and this is an assistant text message, render the S'Ai
    // presence orb as a small avatar at the leading edge of the row. The
    // parent list decides per-message whether to paint an avatar
    // (typically gated by `config.showPresenceOrb`).
    showAgentAvatar: Boolean = false,
    // Drives the avatar's halo glow. Only the latest assistant message
    // should receive `true` so the scrollback doesn't bloom every row
    // when the agent speaks.
    agentAvatarSpeaking: Boolean = false,
) {
    val clipboard = LocalClipboardManager.current
    val isUser = message.role == MessageRole.USER
    val isSystem = message.role == MessageRole.SYSTEM
    val isToolMessage = message.type == MessageType.TOOL_CALL || message.type == MessageType.TOOL_RESULT
    // `true` when this row is an assistant reply and the host asked for
    // the PLAIN assistant style: no bubble fill, no bubble padding, no
    // avatar, no right gutter — the reply is just text sitting on the
    // chat background. Tool, system and content-block rows keep their
    // own treatments, which is what makes them legible as *not* prose.
    val isPlainAssistant = config.appearance.assistantMessageStyle ==
        ChatAppearance.AssistantMessageStyle.PLAIN &&
        !isUser && !isSystem && !isToolMessage
    // Avatar gating mirrors the bubble visibility — we only paint the
    // orb next to an actual assistant text bubble, not next to tool /
    // system / content-block rows (each of which has its own visual
    // treatment that already conveys "this isn't a chat reply from
    // the agent"). Content blocks return early above so we never
    // reach here for them. In the PLAIN style there is no per-message
    // avatar at all: the presence orb above the list already carries
    // agent identity, and an orb beside bubble-less prose reads as a
    // second participant, which is the thing the style removes.
    val shouldShowAvatar = showAgentAvatar && !isPlainAssistant &&
        !isUser && !isSystem && !isToolMessage

    // Bubble-side identifier shared by both render paths so UI tests can
    // assert content (regular text, callouts, action buttons) lives inside
    // the correct user/assistant container. Mirrors iOS chat.message.*.
    val bubbleTag = if (isUser) "chat.message.user" else "chat.message.assistant"

    // Content blocks: render as standalone rich content
    if (message.type == MessageType.CONTENT_BLOCKS) {
        val blocks = message.metadata?.contentBlocks
        if (!blocks.isNullOrEmpty()) {
            ContentBlockRenderer(
                blocks = blocks,
                onAction = onBlockAction,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
                    .semantics(mergeDescendants = false) {}
                    .testTag(bubbleTag),
                config = config,
            )
            return
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .semantics(mergeDescendants = false) {}
            .testTag(bubbleTag),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top
    ) {
        if (isUser) Spacer(modifier = Modifier.weight(0.2f))

        if (shouldShowAvatar) {
            // Per-message S'Ai avatar. Sits at the bubble's leading
            // edge so the assistant identity is anchored in the
            // scrollback. Compact mode keeps the silhouette stable;
            // only the latest message receives `agentAvatarSpeaking`
            // so just that one glows when audio is in flight.
            PresenceOrbView(
                isSpeaking = agentAvatarSpeaking,
                baseSize = 32.dp,
                compact = true,
            )
            Spacer(modifier = Modifier.width(2.dp))
        }

        Column(
            // Bubbles leave a 20% gutter on the far side so the two
            // speakers stay visibly opposed. Plain prose has no opposite
            // speaker to lean away from, so it takes the full width.
            modifier = Modifier.weight(if (isPlainAssistant) 1f else 0.8f, fill = false),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
        ) {
            // Message bubble. Colours, link tint, and corner radius are
            // driven by the host's `appearance` tokens; the `?:` / takeOrElse
            // fall-backs preserve the pre-token behaviour (primaryColor for
            // the user bubble + links, adaptive system grey for assistant /
            // tool bubbles) so `ChatAppearance.classic()` is unchanged.
            val appearance = config.appearance
            val bubbleColor = when {
                isUser -> appearance.userBubble ?: config.primaryColor
                isToolMessage || isSystem -> appearance.systemBubble ?: AgentColors.systemGray6
                else -> appearance.assistantBubble ?: AgentColors.systemGray5
            }
            val textColor = if (isUser) {
                appearance.userBubbleText
                    ?: appearance.textOnAccent.takeOrElse { bubbleColor.contrastingTextColor }
            } else {
                appearance.textPrimary.takeOrElse { bubbleColor.contrastingTextColor }
            }
            val linkColor = appearance.link ?: config.primaryColor

            Column(
                modifier = if (isPlainAssistant) {
                    Modifier
                } else {
                    Modifier
                        .clip(RoundedCornerShape(appearance.bubbleCornerRadius))
                        .background(bubbleColor)
                        .padding(12.dp)
                }
            ) {
                // SelectionContainer gives the bubble the native Android
                // long-press → drag-handles → copy flow on the *inner text*,
                // so the user can pick a specific range rather than copying
                // the whole message. The bubble's background, padding, and
                // surrounding layout are outside the container so they
                // don't become part of the selection region. The
                // `contextMenuBuilder` arg is intentionally left at its
                // default — the existing context menu in the host layer
                // (long-press on a chat row) is unaffected.
                SelectionContainer {
                    Column {
                        // Tool/system message icon + name
                        if (isToolMessage || isSystem) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                MessageIcon(message)
                                message.metadata?.toolName?.let {
                                    Text(it, style = MaterialTheme.typography.labelSmall, color = textColor.copy(alpha = 0.7f))
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                        }

                        // Assistant messages render as markdown from the first
                        // delta, not just once the stream finishes, so headings,
                        // lists and emphasis appear as they arrive rather than
                        // snapping into shape at the end.
                        //
                        // The reason this used to wait was partial syntax: a
                        // half-arrived `**bold` flashes literal asterisks and
                        // then reflows when the closing pair lands.
                        // `stabiliseStreamingMarkdown` closes the dangling
                        // markers instead, so the text renders as
                        // bold-in-progress and never reflows.
                        if (!isUser && !isToolMessage && !isSystem && message.content.isNotBlank()) {
                            val clipboard = LocalClipboardManager.current
    val isDark = isSystemInDarkTheme()
                            val highlights = remember(isDark) {
                                Highlights.Builder().theme(SyntaxThemes.atom(darkMode = isDark))
                            }

                            // One base style for the whole reply, built from
                            // the host's message tokens; every other block
                            // sizes off it so raising `messageTextSize` lifts
                            // the reply coherently instead of flattening the
                            // hierarchy. Code keeps the Material style — it
                            // must stay monospaced whatever face the prose is
                            // set in, and a serif code block is unreadable.
                            val prose = appearance.proseStyle(
                                base = MaterialTheme.typography.bodyMedium,
                                color = textColor,
                            )
                            // Re-stabilised per delta; the parse itself is the
                            // library's, and it already re-parses on content
                            // change, so this adds a scan of the buffer and no
                            // extra parse.
                            val markdownSource = remember(message.content, message.isStreaming) {
                                val raw = if (message.isStreaming) {
                                    stabiliseStreamingMarkdown(message.content)
                                } else {
                                    message.content
                                }
                                stripThematicBreaks(raw)
                            }

                            // Parsed synchronously. The library's default
                            // path builds a fresh state per content change
                            // and parses it off-thread, rendering an empty
                            // `loading` box in between — so during a stream
                            // the bubble collapsed to nothing and popped back
                            // on every delta. Immediate mode keeps the old
                            // tree on screen until the new one is ready; the
                            // parse is a scan of a few KB, well within a frame.
                            val markdownState = rememberMarkdownState(
                                content = markdownSource,
                                immediate = true,
                            )

                            Markdown(
                                markdownState = markdownState,
                                colors = markdownColor(
                                    text = textColor,
                                    linkText = linkColor,
                                    codeBackground = textColor.copy(alpha = 0.08f),
                                    inlineCodeBackground = textColor.copy(alpha = 0.08f),
                                    dividerColor = textColor.copy(alpha = 0.2f),
                                ),
                                typography = markdownTypography(
                                    h1 = prose.heading(1),
                                    h2 = prose.heading(2),
                                    h3 = prose.heading(3),
                                    h4 = prose.heading(4),
                                    h5 = prose.heading(5),
                                    h6 = prose.heading(6),
                                    text = prose,
                                    paragraph = prose,
                                    ordered = prose,
                                    bullet = prose,
                                    list = prose,
                                    link = prose,
                                    table = prose,
                                    quote = prose.copy(fontStyle = FontStyle.Italic),
                                    // Code stays monospaced whatever face the
                                    // prose is set in — a serif code block is
                                    // unreadable — but takes the prose size so
                                    // an inline span doesn't shrink mid-
                                    // sentence. These are spelt out because the
                                    // library derives its defaults from *its*
                                    // `text`, not the one passed above.
                                    inlineCode = prose.copy(fontFamily = FontFamily.Monospace),
                                    code = MaterialTheme.typography.bodySmall.copy(color = textColor),
                                ),
                                padding = markdownPadding(block = appearance.messageBlockSpacing),
                                components = markdownComponents(
                                    codeBlock = {
                                        MarkdownHighlightedCodeBlock(
                                            content = it.content,
                                            node = it.node,
                                            highlights = highlights,
                                        )
                                    },
                                    codeFence = {
                                        MarkdownHighlightedCodeFence(
                                            content = it.content,
                                            node = it.node,
                                            highlights = highlights,
                                        )
                                    },
                                    // A heading belongs to what follows it, so
                                    // it wants more air above than the uniform
                                    // block gap gives. Half a gap again reads
                                    // as a section break rather than as one
                                    // more paragraph that happens to be bold.
                                    heading1 = { HeadingBlock(it, prose.heading(1), appearance) },
                                    heading2 = { HeadingBlock(it, prose.heading(2), appearance) },
                                    heading3 = { HeadingBlock(it, prose.heading(3), appearance) },
                                    heading4 = { HeadingBlock(it, prose.heading(4), appearance) },
                                    heading5 = { HeadingBlock(it, prose.heading(5), appearance) },
                                    heading6 = { HeadingBlock(it, prose.heading(6), appearance) },
                                    // A thematic break renders as one blank
                                    // line, not a drawn rule: in a transcript
                                    // a hairline reads as UI chrome. The agent
                                    // shouldn't emit these, but when one slips
                                    // through it must not shout.
                                    // Draws nothing. `stripThematicBreaks`
                                    // removes these before the parser sees
                                    // them, so this only catches the rare
                                    // form it deliberately leaves alone (a
                                    // rule not preceded by a blank line).
                                    // It used to be a full blank line, which
                                    // is fine for a stray rule and ruinous
                                    // for what the agent actually emits — a
                                    // rule between every sentence.
                                    horizontalRule = { },
                                ),
                            )
                        } else {
                            Text(
                                text = message.content,
                                color = textColor,
                                // Tool and system rows stay at the Material
                                // body size on purpose — they are metadata,
                                // and scaling them with the user's own
                                // messages makes a tool header shout.
                                style = when {
                                    isUser -> appearance.userStyle(MaterialTheme.typography.bodyMedium)
                                    isToolMessage || isSystem -> MaterialTheme.typography.bodyMedium
                                    // Streaming assistant text takes the same
                                    // face, size and pitch as the finished
                                    // markdown, so the reply doesn't resize
                                    // under the reader when the stream ends.
                                    else -> appearance.proseStyle(
                                        base = MaterialTheme.typography.bodyMedium,
                                        color = textColor,
                                    )
                                },
                            )
                        }

                        if (message.type == MessageType.REQUIRED_ACTION) {
                            message.metadata?.actionLabel?.let { label ->
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = label,
                                    color = linkColor,
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        }
                    }
                }

                // Debug info (outside SelectionContainer — it's metadata
                // and we don't want it to be part of any selection range
                // from the message body).
                val meta = message.metadata
                if (showDebug && meta?.arguments != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Args: ${meta.arguments}",
                        style = MaterialTheme.typography.labelSmall,
                        color = textColor.copy(alpha = 0.6f),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // File attachments
                message.files?.let { files ->
                    files.forEach { file ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(Icons.Default.AttachFile, contentDescription = null, modifier = Modifier.size(14.dp), tint = textColor.copy(alpha = 0.7f))
                            Text(file.name, style = MaterialTheme.typography.labelSmall, maxLines = 1, color = textColor.copy(alpha = 0.7f))
                        }
                    }
                }
            }

            // Actions row
            if (!isSystem && !isToolMessage) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 2.dp)
                ) {
                    // Copy is offered on the agent's messages only — the
                    // user's own text is already theirs, and iOS gates it the
                    // same way.
                    val actionTint = config.appearance.textSecondary.takeIf {
                        it != Color.Unspecified
                    } ?: MaterialTheme.colorScheme.onSurfaceVariant

                    if (!isUser) {
                        IconButton(
                            onClick = {
                                clipboard.setText(AnnotatedString(message.content))
                                onCopy?.invoke()
                            },
                            modifier = Modifier.size(24.dp),
                        ) {
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = "Copy message",
                                tint = actionTint,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                    onSpeak?.let {
                        IconButton(onClick = it, modifier = Modifier.size(24.dp)) {
                            Icon(
                                if (isSpeaking) Icons.Default.Stop else Icons.AutoMirrored.Filled.VolumeUp,
                                contentDescription = if (isSpeaking) "Stop playback" else "Play message",
                                tint = actionTint,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                    onRetry?.let {
                        IconButton(onClick = it, modifier = Modifier.size(24.dp)) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = "Retry",
                                tint = actionTint,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                    onEdit?.let {
                        IconButton(onClick = it, modifier = Modifier.size(24.dp)) {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = "Edit",
                                tint = actionTint,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                    Text(
                        text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(message.timestamp),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = actionTint
                    )
                }
            }
        }

        if (!isUser && !isPlainAssistant) Spacer(modifier = Modifier.weight(0.2f))
    }
}

/**
 * A markdown heading with the extra half-gap of air above it that makes
 * it read as a section break rather than as a bold paragraph. The
 * renderer's own `block` padding is uniform, so the difference has to be
 * added here.
 */
@Composable
private fun HeadingBlock(
    model: MarkdownComponentModel,
    style: TextStyle,
    appearance: ChatAppearance,
) {
    Box(modifier = Modifier.padding(top = appearance.messageBlockSpacing / 2)) {
        MarkdownHeader(content = model.content, node = model.node, style = style)
    }
}

@Composable
private fun MessageIcon(message: Message) {
    val (icon, tint) = when (message.type) {
        MessageType.TOOL_CALL -> Icons.Default.Build to Color(0xFFFF9800)
        MessageType.TOOL_RESULT ->
            if (message.content.contains("❌")) Icons.Default.Cancel to Color.Red
            else Icons.Default.CheckCircle to Color(0xFF4CAF50)
        MessageType.ERROR -> Icons.Default.Warning to Color.Red
        MessageType.CANCELLED -> Icons.Default.StopCircle to Color(0xFFFF9800)
        MessageType.REQUIRED_ACTION -> Icons.Default.Info to Color(0xFF2196F3)
        MessageType.SUB_AGENT_START, MessageType.SUB_AGENT_END, MessageType.AGENT_CONTEXT ->
            Icons.Default.Link to Color(0xFF2196F3)
        else -> return
    }
    Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = tint)
}

/**
 * Close the inline markers a partially-arrived reply hasn't closed yet.
 *
 * Without this, streaming markdown shows the raw marker for as long as it
 * takes the closing pair to arrive — `**bo` reads as literal asterisks — and
 * then reflows once it does. Closing them optimistically means the span
 * renders in its final form from the first character.
 *
 * Deliberately shallow: it balances fenced code blocks, `**` and backticks,
 * which covers what an agent actually streams. It does not try to parse
 * nesting, and a marker that legitimately appears an odd number of times
 * (a lone asterisk in prose) gets one closing marker appended for the
 * duration of the stream — invisible in the rendered output, and corrected
 * the moment the stream finishes and the raw content is used verbatim.
 */
internal fun stabiliseStreamingMarkdown(raw: String): String {
    var out = raw

    // An unterminated fence would otherwise swallow the rest of the reply
    // into a code block as it arrives.
    val fenceCount = Regex("^\\s*```", RegexOption.MULTILINE).findAll(out).count()
    if (fenceCount % 2 == 1) out += "\n```"

    // Longest marker first, so the `**` pairs are consumed before the
    // single-backtick pass looks at what is left.
    for (marker in listOf("**", "`")) {
        var index = 0
        var count = 0
        while (true) {
            val at = out.indexOf(marker, index)
            if (at < 0) break
            count++
            index = at + marker.length
        }
        if (count % 2 == 1) out += marker
    }
    return out
}

/**
 * Drop thematic-break lines (`---`, `***`, `___`) before the markdown
 * parser ever sees them.
 *
 * A horizontal rule has no place in a conversational transcript — a
 * hairline reads as UI chrome — so the renderer never drew one. It drew a
 * blank line instead, which is right for the stray rule the agent
 * occasionally slips in and ruinous for what it actually emits: a rule
 * between every single sentence. At that density each break costs a blank
 * line plus a block gap either side, roughly triple the intended paragraph
 * spacing, and the reply reads as a poem.
 *
 * Stripping the line beats rendering it as nothing: the block would still
 * take its place in the layout and collect `messageBlockSpacing` on both
 * sides. Removing it leaves the neighbouring paragraphs exactly one block
 * gap apart, which is what a paragraph break was always meant to look like.
 *
 * Two deliberate exemptions:
 *  - Lines inside a fenced code block, where `---` is content.
 *  - A rule not preceded by a blank line. In CommonMark `text` followed by
 *    `---` is a setext *heading*, not a rule, and quietly demoting someone's
 *    heading to a paragraph is a bigger change than this is entitled to
 *    make. The `horizontalRule` component handles whatever reaches it.
 *
 * Mirrors iOS `MarkdownTextView.renderableBlocks`, which filters the same
 * blocks out after parsing (its parser's block vocabulary is pinned by
 * tests, so it filters rather than pre-processes).
 */
internal fun stripThematicBreaks(raw: String): String {
    if (!raw.contains('-') && !raw.contains('*') && !raw.contains('_')) return raw

    val lines = raw.split("\n")
    val out = ArrayList<String>(lines.size)
    var inFence = false
    // Start of input counts as a blank line: a reply opening with a rule
    // is still a rule.
    var prevBlank = true

    for (line in lines) {
        if (line.trimStart().startsWith("```")) {
            inFence = !inFence
            out.add(line)
            prevBlank = false
            continue
        }
        // `prevBlank` is intentionally left alone when a rule is dropped,
        // so a run of blank-separated rules is removed whole.
        if (!inFence && prevBlank && isThematicBreak(line)) continue
        out.add(line)
        prevBlank = line.isBlank()
    }
    return out.joinToString("\n")
}

/**
 * Three or more of the same marker (`-`, `*`, `_`) alone on a line, with
 * optional spaces between them ("---", "***", "- - -").
 */
private fun isThematicBreak(line: String): Boolean {
    val marks = line.filter { it != ' ' && it != '\t' }
    if (marks.length < 3) return false
    val first = marks[0]
    if (first != '-' && first != '*' && first != '_') return false
    return marks.all { it == first }
}
