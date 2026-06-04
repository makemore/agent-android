package com.makemore.agentfrontend.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Style
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.makemore.agentfrontend.configuration.ChatWidgetConfig
import com.makemore.agentfrontend.viewmodels.ChatViewModel

/**
 * Bottom sheet presented by the composer's `+` button. Mirrors the
 * iOS `AddToChatSheet`: camera / recents tiles at the top, a list
 * of attachment + configuration rows, two feature toggles, and a
 * connectors row.
 *
 * When a [ChatViewModel] is provided, the behaviour rows (Style,
 * Tool access, Research, Web search) bind directly to its persisted
 * preferences and the recents tile lists local conversation history.
 * When `null` (preview / headless usage) the sheet falls back to
 * local stub state so it still renders standalone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToChatSheet(
    config: ChatWidgetConfig,
    onAddFiles: () -> Unit,
    onDismiss: () -> Unit,
    viewModel: ChatViewModel? = null,
    onCaptureImage: (() -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = config.appearance.background,
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            AddToChatHeader(config, onDismiss)
            Column(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                TopTiles(
                    config = config,
                    viewModel = viewModel,
                    onCaptureImage = onCaptureImage,
                    onDismiss = onDismiss,
                )
                RowsCard(config = config, viewModel = viewModel, onAddFiles = onAddFiles)
                TogglesCard(config = config, viewModel = viewModel)
                ConnectorsCard(config)
            }
        }
    }
}

@Composable
private fun AddToChatHeader(config: ChatWidgetConfig, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(config.appearance.surface)
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = "Close",
                tint = config.appearance.textPrimary,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = "Add to Chat",
            color = config.appearance.textPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.weight(1f))
        // Spacer mirror so the title stays centred under the close pill.
        Spacer(modifier = Modifier.size(32.dp))
    }
}


@Composable
private fun TopTiles(
    config: ChatWidgetConfig,
    viewModel: ChatViewModel?,
    onCaptureImage: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(130.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TileButton(
            config = config,
            icon = Icons.Outlined.CameraAlt,
            label = "Camera",
            // Dismiss first so the system camera intent doesn't have to
            // fight with the modal sheet for focus — matches the iOS
            // behaviour where the picker is presented after the sheet
            // closes.
            onClick = {
                onCaptureImage?.invoke()
                onDismiss()
            },
            modifier = Modifier.weight(1f),
        )
        RecentsTile(
            config = config,
            viewModel = viewModel,
            onSelect = { id ->
                onDismiss()
                viewModel?.loadLocalConversation(id)
            },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun TileButton(
    config: ChatWidgetConfig,
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(config.appearance.surface)
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = config.appearance.textPrimary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = label,
            color = config.appearance.textPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** Preview of the user's three most-recent local conversations.
 *  Falls back to placeholder copy when nothing is recorded yet so the
 *  tile keeps its visual weight. Tapping a row hands the id off to
 *  [onSelect] (the parent restores it via [ChatViewModel.loadLocalConversation]). */
@Composable
private fun RecentsTile(
    config: ChatWidgetConfig,
    viewModel: ChatViewModel?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val recents = viewModel?.localConversations?.take(3) ?: emptyList()
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(config.appearance.surface)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "Recents",
            color = config.appearance.textPrimary,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 2.dp),
        )
        if (recents.isEmpty()) {
            listOf("No history yet", "Start chatting to", "see recent items").forEach { line ->
                Text(
                    text = line,
                    color = config.appearance.textSecondary,
                    fontSize = 10.sp,
                    maxLines = 1,
                )
            }
        } else {
            recents.forEach { item ->
                Text(
                    text = item.title.ifEmpty { "Untitled" },
                    color = config.appearance.textSecondary,
                    fontSize = 10.sp,
                    maxLines = 1,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(item.id) },
                )
            }
        }
    }
}

@Composable
private fun RowsCard(
    config: ChatWidgetConfig,
    viewModel: ChatViewModel?,
    onAddFiles: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(config.appearance.surface),
    ) {
        ActionRow(config, icon = Icons.Outlined.UploadFile, label = "Add files", onClick = onAddFiles)
        RowDivider(config)
        // Project linking isn't wired to the runtime yet — render the
        // row so the layout matches the iOS reference, but tag it as
        // Soon to set expectations.
        SoonRow(config, icon = Icons.Outlined.Inbox, label = "Add to project")
        RowDivider(config)
        StyleMenuRow(config, viewModel)
        RowDivider(config)
        ToolAccessMenuRow(config, viewModel)
    }
}

@Composable
private fun TogglesCard(
    config: ChatWidgetConfig,
    viewModel: ChatViewModel?,
) {
    // Fallback state used only when no ChatViewModel is wired (e.g.
    // previews). Mirrors the iOS sheet's behaviour.
    var fallbackResearch by remember { mutableStateOf(false) }
    var fallbackWebSearch by remember { mutableStateOf(true) }

    val research = viewModel?.researchEnabled?.value ?: fallbackResearch
    val webSearch = viewModel?.webSearchEnabled?.value ?: fallbackWebSearch

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(config.appearance.surface),
    ) {
        ToggleRow(config, icon = Icons.Outlined.Search, label = "Research", checked = research) {
            if (viewModel != null) viewModel.setResearchEnabled(it) else fallbackResearch = it
        }
        RowDivider(config)
        ToggleRow(config, icon = Icons.Outlined.Language, label = "Web search", checked = webSearch) {
            if (viewModel != null) viewModel.setWebSearchEnabled(it) else fallbackWebSearch = it
        }
    }
}

@Composable
private fun ConnectorsCard(config: ChatWidgetConfig) {
    // Disabled-looking row with a Soon badge — connectors aren't wired
    // to the runtime yet. Matches the iOS reference layout.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(config.appearance.surface)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        RowIcon(config, Icons.Outlined.GridView)
        Text(
            text = "Connectors",
            color = config.appearance.textPrimary.copy(alpha = 0.55f),
            fontSize = 15.sp,
            modifier = Modifier.weight(1f),
        )
        SoonBadge(config)
    }
}


@Composable
private fun ActionRow(
    config: ChatWidgetConfig,
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        RowIcon(config, icon)
        Text(
            text = label,
            color = config.appearance.textPrimary,
            fontSize = 15.sp,
        )
    }
}

/**
 * Tappable row that pops a [DropdownMenu] of [options] anchored at
 * the row itself. Used by both the response-style and tool-access
 * pickers — they only differ in label / icon / option set / current
 * value, so the menu mechanics live here. Falls back to a noop label
 * row when [onSelect] does nothing useful (host hasn't wired a vm).
 */
@Composable
private fun <T> MenuRow(
    config: ChatWidgetConfig,
    icon: ImageVector,
    label: String,
    options: List<T>,
    selected: T,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = true }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            RowIcon(config, icon)
            Text(
                text = label,
                color = config.appearance.textPrimary,
                fontSize = 15.sp,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = optionLabel(selected),
                color = config.appearance.textSecondary,
                fontSize = 14.sp,
            )
            Icon(
                imageVector = Icons.Outlined.UnfoldMore,
                contentDescription = null,
                tint = config.appearance.textSecondary,
                modifier = Modifier.size(14.dp),
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(config.appearance.surfaceElevated),
        ) {
            options.forEach { opt ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = optionLabel(opt),
                            color = config.appearance.textPrimary,
                        )
                    },
                    leadingIcon = if (opt == selected) {
                        {
                            Icon(
                                imageVector = Icons.Outlined.Check,
                                contentDescription = null,
                                tint = config.appearance.textPrimary,
                            )
                        }
                    } else null,
                    onClick = {
                        onSelect(opt)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun StyleMenuRow(config: ChatWidgetConfig, viewModel: ChatViewModel?) {
    var fallback by remember { mutableStateOf(ChatViewModel.ResponseStyle.NORMAL) }
    val selected = viewModel?.responseStyle?.value ?: fallback
    MenuRow(
        config = config,
        icon = Icons.Outlined.Style,
        label = "Choose style",
        options = ChatViewModel.ResponseStyle.entries,
        selected = selected,
        optionLabel = { it.displayName },
        onSelect = { if (viewModel != null) viewModel.setResponseStyle(it) else fallback = it },
    )
}

@Composable
private fun ToolAccessMenuRow(config: ChatWidgetConfig, viewModel: ChatViewModel?) {
    var fallback by remember { mutableStateOf(ChatViewModel.ToolAccess.AUTO) }
    val selected = viewModel?.toolAccess?.value ?: fallback
    MenuRow(
        config = config,
        icon = Icons.Outlined.Tune,
        label = "Tool access",
        options = ChatViewModel.ToolAccess.entries,
        selected = selected,
        optionLabel = { it.displayName },
        onSelect = { if (viewModel != null) viewModel.setToolAccess(it) else fallback = it },
    )
}

/** Disabled-looking row with a small "Soon" badge. Used for features
 *  that aren't wired to the runtime yet so the layout matches the
 *  reference design without misleading users into thinking the row
 *  does something. */
@Composable
private fun SoonRow(
    config: ChatWidgetConfig,
    icon: ImageVector,
    label: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        RowIcon(config, icon)
        Text(
            text = label,
            color = config.appearance.textPrimary.copy(alpha = 0.55f),
            fontSize = 15.sp,
            modifier = Modifier.weight(1f),
        )
        SoonBadge(config)
    }
}

@Composable
private fun SoonBadge(config: ChatWidgetConfig) {
    Text(
        text = "Soon",
        color = config.appearance.textSecondary,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(config.appearance.background.copy(alpha = 0.6f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun ToggleRow(
    config: ChatWidgetConfig,
    icon: ImageVector,
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        RowIcon(config, icon)
        Text(
            text = label,
            color = config.appearance.textPrimary,
            fontSize = 15.sp,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = config.appearance.accent,
                uncheckedThumbColor = config.appearance.textSecondary,
                uncheckedTrackColor = config.appearance.surfaceElevated,
            ),
        )
    }
}

@Composable
private fun RowIcon(config: ChatWidgetConfig, icon: ImageVector) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = config.appearance.textPrimary,
        modifier = Modifier.size(20.dp),
    )
}

@Composable
private fun RowDivider(config: ChatWidgetConfig) {
    HorizontalDivider(
        color = config.appearance.divider,
        modifier = Modifier.padding(start = 50.dp),
    )
}
