package com.makemore.agentfrontend.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.makemore.agentfrontend.configuration.ChatWidgetConfig
import com.makemore.agentfrontend.models.Message
import com.makemore.agentfrontend.models.MessageRole
import com.makemore.agentfrontend.models.MessageType
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownHighlightedCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownHighlightedCodeFence
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
    onBlockAction: ((com.makemore.agentfrontend.models.BlockAction) -> Unit)? = null
) {
    val isUser = message.role == MessageRole.USER
    val isSystem = message.role == MessageRole.SYSTEM
    val isToolMessage = message.type == MessageType.TOOL_CALL || message.type == MessageType.TOOL_RESULT

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
                    .padding(horizontal = 12.dp)
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
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .semantics(mergeDescendants = false) {}
            .testTag(bubbleTag),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        if (isUser) Spacer(modifier = Modifier.weight(0.2f))

        Column(
            modifier = Modifier.weight(0.8f, fill = false),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
        ) {
            // Message bubble
            val bubbleColor = when {
                isUser -> config.primaryColor
                isToolMessage || isSystem -> AgentColors.systemGray6
                else -> AgentColors.systemGray5
            }
            val textColor = bubbleColor.contrastingTextColor

            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(bubbleColor)
                    .padding(12.dp)
            ) {
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

                // Render assistant messages as markdown once the stream
                // finishes; during streaming use plain Text so the bubble
                // doesn't reflow on every delta as the Markdown parser
                // re-interprets partial syntax (e.g. `**hello` -> `**hello**`).
                if (!isUser && !isToolMessage && !isSystem && message.content.isNotBlank() && !message.isStreaming) {
                    val isDark = isSystemInDarkTheme()
                    val highlights = remember(isDark) {
                        Highlights.Builder().theme(SyntaxThemes.atom(darkMode = isDark))
                    }

                    Markdown(
                        content = message.content,
                        colors = markdownColor(
                            text = textColor,
                            codeBackground = textColor.copy(alpha = 0.08f),
                            inlineCodeBackground = textColor.copy(alpha = 0.08f),
                            dividerColor = textColor.copy(alpha = 0.2f),
                        ),
                        typography = markdownTypography(
                            h1 = MaterialTheme.typography.titleLarge.copy(color = textColor),
                            h2 = MaterialTheme.typography.titleMedium.copy(color = textColor),
                            h3 = MaterialTheme.typography.titleSmall.copy(color = textColor),
                            h4 = MaterialTheme.typography.bodyLarge.copy(color = textColor),
                            h5 = MaterialTheme.typography.bodyMedium.copy(color = textColor),
                            h6 = MaterialTheme.typography.bodySmall.copy(color = textColor),
                            text = MaterialTheme.typography.bodyMedium.copy(color = textColor),
                            paragraph = MaterialTheme.typography.bodyMedium.copy(color = textColor),
                            ordered = MaterialTheme.typography.bodyMedium.copy(color = textColor),
                            bullet = MaterialTheme.typography.bodyMedium.copy(color = textColor),
                            list = MaterialTheme.typography.bodyMedium.copy(color = textColor),
                            code = MaterialTheme.typography.bodySmall.copy(color = textColor),
                        ),
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
                        ),
                    )
                } else {
                    Text(
                        text = message.content,
                        color = textColor,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                // Debug info
                if (showDebug && message.metadata?.arguments != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Args: ${message.metadata.arguments}",
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
                    onRetry?.let {
                        IconButton(onClick = it, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Refresh, contentDescription = "Retry", modifier = Modifier.size(14.dp))
                        }
                    }
                    onEdit?.let {
                        IconButton(onClick = it, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit", modifier = Modifier.size(14.dp))
                        }
                    }
                    Text(
                        text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(message.timestamp),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (!isUser) Spacer(modifier = Modifier.weight(0.2f))
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
        MessageType.SUB_AGENT_START, MessageType.SUB_AGENT_END, MessageType.AGENT_CONTEXT ->
            Icons.Default.Link to Color(0xFF2196F3)
        else -> return
    }
    Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = tint)
}

