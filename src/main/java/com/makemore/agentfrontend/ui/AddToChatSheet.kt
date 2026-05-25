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
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.NoteAdd
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Style
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.UploadFile
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

/**
 * Bottom sheet presented by the composer's `+` button. Mirrors the
 * iOS `AddToChatSheet`: camera / recents tiles at the top, a list
 * of attachment + configuration rows, two feature toggles, and a
 * connectors row. Everything except "Add files" is a stub today \u2014
 * state stays local so host apps get a usable preview without any
 * wiring.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToChatSheet(
    config: ChatWidgetConfig,
    onAddFiles: () -> Unit,
    onDismiss: () -> Unit,
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
                TopTiles(config)
                RowsCard(config, onAddFiles = onAddFiles)
                TogglesCard(config)
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
        Text(
            text = "All photos",
            color = config.appearance.textPrimary,
            fontSize = 14.sp,
            modifier = Modifier.clickable { /* stub */ },
        )
    }
}


@Composable
private fun TopTiles(config: ChatWidgetConfig) {
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
            onClick = { /* stub */ },
            modifier = Modifier.weight(1f),
        )
        RecentsTile(config, modifier = Modifier.weight(1f))
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

/** Decorative preview of the user's recent items \u2014 visual stub so
 *  the tile looks populated. Matches the iOS reference layout. */
@Composable
private fun RecentsTile(config: ChatWidgetConfig, modifier: Modifier = Modifier) {
    val lines = listOf(
        "Artifacts", "Code", "Dispatch", "Recents",
        "Building a sports car on a bu\u2026",
        "Data lakes vs databases exp\u2026",
        "Mervin the Paranoid Androi\u2026",
    )
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(config.appearance.surface)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        lines.forEach { line ->
            Text(
                text = line,
                color = config.appearance.textSecondary,
                fontSize = 9.sp,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun RowsCard(config: ChatWidgetConfig, onAddFiles: () -> Unit) {
    var addToProject by remember { mutableStateOf("None") }
    var chosenStyle by remember { mutableStateOf("Normal") }
    var toolAccess by remember { mutableStateOf("Auto") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(config.appearance.surface),
    ) {
        ActionRow(config, icon = Icons.Outlined.UploadFile, label = "Add files", onClick = onAddFiles)
        RowDivider(config)
        ValueRow(config, icon = Icons.Outlined.Inbox, label = "Add to project", value = addToProject) {
            addToProject = if (addToProject == "None") "Personal" else "None"
        }
        RowDivider(config)
        ValueRow(config, icon = Icons.Outlined.Style, label = "Choose style", value = chosenStyle) {
            chosenStyle = if (chosenStyle == "Normal") "Concise" else "Normal"
        }
        RowDivider(config)
        ValueRow(config, icon = Icons.Outlined.Tune, label = "Tool access", value = toolAccess) {
            toolAccess = if (toolAccess == "Auto") "Manual" else "Auto"
        }
    }
}

@Composable
private fun TogglesCard(config: ChatWidgetConfig) {
    var researchEnabled by remember { mutableStateOf(false) }
    var webSearchEnabled by remember { mutableStateOf(true) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(config.appearance.surface),
    ) {
        ToggleRow(config, icon = Icons.Outlined.Search, label = "Research", checked = researchEnabled) {
            researchEnabled = it
        }
        RowDivider(config)
        ToggleRow(config, icon = Icons.Outlined.Language, label = "Web search", checked = webSearchEnabled) {
            webSearchEnabled = it
        }
    }
}

@Composable
private fun ConnectorsCard(config: ChatWidgetConfig) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(config.appearance.surface)
            .clickable { /* stub */ }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        RowIcon(config, Icons.Outlined.GridView)
        Text(
            text = "Connectors",
            color = config.appearance.textPrimary,
            fontSize = 15.sp,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.Send,
            contentDescription = null,
            tint = config.appearance.textSecondary,
            modifier = Modifier.size(14.dp),
        )
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

@Composable
private fun ValueRow(
    config: ChatWidgetConfig,
    icon: ImageVector,
    label: String,
    value: String,
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
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            color = config.appearance.textSecondary,
            fontSize = 14.sp,
        )
    }
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
