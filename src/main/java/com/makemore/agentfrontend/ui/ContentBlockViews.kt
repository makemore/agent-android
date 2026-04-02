package com.makemore.agentfrontend.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.makemore.agentfrontend.models.*

// =============================================================================
// Block Renderer — top-level composable
// =============================================================================

@Composable
fun ContentBlockRenderer(
    blocks: List<ContentBlock>,
    onAction: ((BlockAction) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block ->
            when (block) {
                is CardBlock -> CardBlockView(block, onAction)
                is CardListBlock -> CardListBlockView(block, onAction)
                is ActionButtonsBlock -> ActionButtonsBlockView(block, onAction)
                is CalloutBlock -> CalloutBlockView(block)
                is ImageBlock -> ImageBlockView(block)
                is DividerBlock -> HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
                is TableBlock -> TableBlockView(block)
                is CodeBlockData -> CodeBlockView(block)
                is FileBlock -> FileBlockView(block)
                is CollapsibleBlock -> CollapsibleBlockView(block)
                is StatusBlock -> StatusBlockView(block)
                is LocationBlock -> LocationBlockView(block)
            }
        }
    }
}

// =============================================================================
// Card
// =============================================================================

@Composable
fun CardBlockView(block: CardBlock, onAction: ((BlockAction) -> Unit)? = null) {
    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column {
            block.image?.let { url ->
                AsyncImage(
                    model = url,
                    contentDescription = block.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().height(120.dp)
                )
            }
            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                block.badge?.let { badge ->
                    Text(
                        badge, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                Text(block.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                block.subtitle?.let { Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }

                block.metadata?.takeIf { it.isNotEmpty() }?.let { meta ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        meta.forEach { pair ->
                            Text("${pair.label}: ${pair.value}", fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                block.actions?.takeIf { it.isNotEmpty() }?.let { actions ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                        actions.forEach { action -> BlockActionButton(action, onAction) }
                    }
                }
            }
        }
    }
}

// =============================================================================
// Card List
// =============================================================================

@Composable
fun CardListBlockView(block: CardListBlock, onAction: ((BlockAction) -> Unit)? = null) {
    if (block.layout == "horizontal") {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            block.items.forEach { item ->
                Box(modifier = Modifier.width(220.dp)) { CardBlockView(item, onAction) }
            }
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            block.items.forEach { item -> CardBlockView(item, onAction) }
        }
    }
}

// =============================================================================
// Action Button
// =============================================================================

@Composable
fun BlockActionButton(action: BlockAction, onAction: ((BlockAction) -> Unit)? = null) {
    val context = LocalContext.current
    val isPrimary = action.style != "secondary"

    val onClick: () -> Unit = {
        when (action.type) {
            "link" -> action.url?.let { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) }
            else -> onAction?.invoke(action)
        }
    }

    if (isPrimary) {
        Button(onClick = onClick, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            shape = RoundedCornerShape(6.dp)) {
            Text(action.label, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
    } else {
        OutlinedButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            shape = RoundedCornerShape(6.dp)) {
            Text(action.label, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
    }
}

// =============================================================================
// Action Buttons Group
// =============================================================================

@Composable
fun ActionButtonsBlockView(block: ActionButtonsBlock, onAction: ((BlockAction) -> Unit)? = null) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        block.buttons.forEach { action -> BlockActionButton(action, onAction) }
    }
}

// =============================================================================
// Callout
// =============================================================================

@Composable
fun CalloutBlockView(block: CalloutBlock) {
    val (bgColor, icon) = when (block.style) {
        "success" -> Color(0xFFEEFBF3) to "✅"
        "warning" -> Color(0xFFFFF8E1) to "⚠️"
        else -> Color(0xFFEEF4FF) to "ℹ️"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor, RoundedCornerShape(8.dp))
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(icon)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            block.title?.let { Text(it, fontWeight = FontWeight.SemiBold, fontSize = 13.sp) }
            Text(block.body, fontSize = 13.sp)
        }
    }
}

// =============================================================================
// Image
// =============================================================================

@Composable
fun ImageBlockView(block: ImageBlock) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        AsyncImage(
            model = block.url,
            contentDescription = block.alt ?: "",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
        )
        block.caption?.let {
            Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// =============================================================================
// Table
// =============================================================================

@Composable
fun TableBlockView(block: TableBlock) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp))
            .clip(RoundedCornerShape(6.dp))
    ) {
        block.headers?.let { headers ->
            Row(modifier = Modifier.background(MaterialTheme.colorScheme.surfaceVariant).fillMaxWidth()) {
                headers.forEach { h ->
                    Text(h, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                        modifier = Modifier.weight(1f).padding(6.dp))
                }
            }
        }
        block.rows?.forEach { row ->
            HorizontalDivider()
            Row(modifier = Modifier.fillMaxWidth()) {
                row.forEach { cell ->
                    Text(cell, fontSize = 13.sp, modifier = Modifier.weight(1f).padding(6.dp))
                }
            }
        }
    }
}

// =============================================================================
// Code
// =============================================================================

@Composable
fun CodeBlockView(block: CodeBlockData) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1E1E2E), RoundedCornerShape(8.dp))
    ) {
        block.filename?.let { filename ->
            Text(filename, fontSize = 11.sp, color = Color.Gray,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    .background(Color.White.copy(alpha = 0.08f)))
        }
        Box(modifier = Modifier.padding(10.dp).horizontalScroll(rememberScrollState())) {
            Text(block.code, fontSize = 12.sp, color = Color(0xFFE0E0E0),
                fontFamily = FontFamily.Monospace, lineHeight = 18.sp)
        }
        if (block.copyable) {
            Text(
                if (copied) "✓" else "⎘",
                fontSize = 14.sp, color = Color.Gray,
                modifier = Modifier
                    .align(Alignment.End)
                    .padding(6.dp)
                    .clickable {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("code", block.code))
                        copied = true
                    }
            )
        }
    }

    LaunchedEffect(copied) { if (copied) { kotlinx.coroutines.delay(1500); copied = false } }
}

// =============================================================================
// File
// =============================================================================

@Composable
fun FileBlockView(block: FileBlock) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
            .clickable { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(block.url))) }
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("📄", fontSize = 18.sp)
        Column(modifier = Modifier.weight(1f)) {
            Text(block.filename, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            block.size?.let { Text("${it / 1024} KB", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Text("⬇", fontSize = 16.sp, color = MaterialTheme.colorScheme.primary)
    }
}

// =============================================================================
// Collapsible
// =============================================================================

@Composable
fun CollapsibleBlockView(block: CollapsibleBlock) {
    var isOpen by remember { mutableStateOf(block.defaultOpen) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
            .clip(RoundedCornerShape(8.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable { isOpen = !isOpen }
                .padding(10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(block.title, fontWeight = FontWeight.Medium, fontSize = 13.sp)
            Text(if (isOpen) "▲" else "▼", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AnimatedVisibility(visible = isOpen) {
            Text(block.body, fontSize = 13.sp, modifier = Modifier.padding(10.dp))
        }
    }
}

// =============================================================================
// Status
// =============================================================================

@Composable
fun StatusBlockView(block: StatusBlock) {
    val (icon, iconColor) = when (block.state) {
        "loading" -> "⏳" to Color(0xFFFF9800)
        "success" -> "✅" to Color(0xFF4CAF50)
        "error" -> "❌" to Color.Red
        "warning" -> "⚠️" to Color(0xFFFF9800)
        else -> "ℹ️" to Color(0xFF2196F3)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(icon, fontSize = 16.sp)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(block.title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            block.body?.let { Text(it, fontSize = 13.sp) }
            block.progress?.let { progress ->
                LinearProgressIndicator(
                    progress = { progress.toFloat() },
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                    color = iconColor,
                )
            }
        }
    }
}

// =============================================================================
// Location
// =============================================================================

@Composable
fun LocationBlockView(block: LocationBlock) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("📍", fontSize = 18.sp)
        Column {
            Text(block.label, fontWeight = FontWeight.Medium, fontSize = 13.sp)
            Text("(${String.format("%.4f", block.latitude)}, ${String.format("%.4f", block.longitude)})",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}


