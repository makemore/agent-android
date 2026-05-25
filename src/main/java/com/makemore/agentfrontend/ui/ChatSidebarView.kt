package com.makemore.agentfrontend.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.makemore.agentfrontend.configuration.ChatWidgetConfig
import com.makemore.agentfrontend.models.Conversation
import com.makemore.agentfrontend.networking.APIClient
import com.makemore.agentfrontend.networking.loadConversations

/**
 * Slide-in conversation sidebar shown by the bundled [ChatWidgetView]
 * when [ChatWidgetConfig.sidebar].enabled is `true`. Layout: serif
 * wordmark, static nav rows, scrollable "Recents" list, footer with
 * user avatar and a "New chat" pill. Mirrors the iOS
 * `ChatSidebarView` view.
 *
 * The panel takes ~80% of the available width so the open sidebar
 * feels like the primary surface; the remaining ~20% is a dim
 * backdrop that dismisses the panel when tapped.
 */
@Composable
fun ChatSidebarView(
    config: ChatWidgetConfig,
    apiClient: APIClient?,
    onDismiss: () -> Unit,
    onNewChat: () -> Unit,
    onSelectConversation: (Conversation) -> Unit,
) {
    var conversations by remember { mutableStateOf<List<Conversation>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }

    LaunchedEffect(apiClient, config.sidebar.showRecents) {
        if (!config.sidebar.showRecents || apiClient == null) return@LaunchedEffect
        isLoading = true
        try {
            val fetched = apiClient.loadConversations()
            conversations = fetched.take(config.sidebar.recentsLimit)
        } catch (_: Exception) {
            // Network errors are silently swallowed \u2014 the panel
            // shows "No conversations yet" so the user can still
            // start a new chat. The host's error banner handles
            // the broader failure surface.
            conversations = emptyList()
        } finally {
            isLoading = false
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val panelWidthDp = maxOf(280.dp, maxWidth * 0.8f)
        Row(modifier = Modifier.fillMaxSize()) {
            SidebarPanel(
                config = config,
                conversations = conversations,
                isLoading = isLoading,
                onSelectConversation = onSelectConversation,
                onNewChat = onNewChat,
                modifier = Modifier
                    .width(panelWidthDp)
                    .fillMaxHeight()
                    .background(config.appearance.background),
            )
            // Dim backdrop \u2014 tap to dismiss.
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .weight(1f)
                    .background(Color.Black.copy(alpha = 0.35f))
                    .clickable(onClick = onDismiss),
            )
        }
    }
}

@Composable
private fun SidebarPanel(
    config: ChatWidgetConfig,
    conversations: List<Conversation>,
    isLoading: Boolean,
    onSelectConversation: (Conversation) -> Unit,
    onNewChat: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        SidebarHeader(config)
        HorizontalDivider(color = config.appearance.divider)
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp),
        ) {
            SidebarNavItems(config)
            if (config.sidebar.showRecents) {
                SidebarRecentsSection(
                    config = config,
                    conversations = conversations,
                    isLoading = isLoading,
                    onSelectConversation = onSelectConversation,
                )
            }
        }
        HorizontalDivider(color = config.appearance.divider)
        SidebarFooter(config, onNewChat)
    }
}


@Composable
private fun SidebarHeader(config: ChatWidgetConfig) {
    if (config.sidebar.wordmark.isEmpty()) {
        Spacer(modifier = Modifier.padding(top = 20.dp))
        return
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = config.sidebar.wordmark,
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.Normal,
            fontSize = 28.sp,
            color = config.appearance.textPrimary,
        )
    }
}

@Composable
private fun SidebarNavItems(config: ChatWidgetConfig) {
    config.sidebar.items.forEach { item ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = item.onClick)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(
                imageVector = item.icon,
                contentDescription = null,
                tint = config.appearance.textPrimary,
                modifier = Modifier.size(22.dp),
            )
            Text(
                text = item.title,
                color = config.appearance.textPrimary,
                fontSize = 16.sp,
                modifier = Modifier.weight(1f),
            )
            item.badge?.let { badge ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(config.appearance.surface)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = badge,
                        color = config.appearance.textSecondary,
                        fontSize = 11.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun SidebarRecentsSection(
    config: ChatWidgetConfig,
    conversations: List<Conversation>,
    isLoading: Boolean,
    onSelectConversation: (Conversation) -> Unit,
) {
    if (config.sidebar.recentsTitle.isNotEmpty()) {
        Text(
            text = config.sidebar.recentsTitle,
            color = config.appearance.textSecondary,
            fontSize = 11.sp,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 6.dp),
        )
    }
    when {
        isLoading && conversations.isEmpty() -> {
            Row(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    color = config.appearance.textSecondary,
                    strokeWidth = 2.dp,
                )
                Text(
                    text = "Loading\u2026",
                    color = config.appearance.textSecondary,
                    fontSize = 11.sp,
                )
            }
        }
        conversations.isEmpty() -> {
            Text(
                text = "No conversations yet",
                color = config.appearance.textSecondary,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            )
        }
        else -> {
            conversations.forEach { conv ->
                Text(
                    text = conv.title ?: "Untitled conversation",
                    color = config.appearance.textPrimary,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectConversation(conv) }
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun SidebarFooter(config: ChatWidgetConfig, onNewChat: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(config.appearance.surface),
            contentAlignment = Alignment.Center,
        ) {
            config.sidebar.footerInitials?.let { initials ->
                Text(
                    text = initials,
                    color = config.appearance.textPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        config.sidebar.footerCaption?.let { caption ->
            Text(
                text = caption,
                color = config.appearance.textPrimary,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        } ?: Spacer(modifier = Modifier.weight(1f))
        if (config.sidebar.newChatLabel.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(config.appearance.surface)
                    .clickable(onClick = onNewChat)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Edit,
                    contentDescription = null,
                    tint = config.appearance.textPrimary,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = config.sidebar.newChatLabel,
                    color = config.appearance.textPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

