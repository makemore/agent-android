package com.makemore.agentfrontend.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.makemore.agentfrontend.models.AgentSystem
import com.makemore.agentfrontend.models.AgentSystemVersionSummary

/**
 * Modal dialog for selecting which agent system and version the conversation uses.
 * Mirrors the iOS SystemPickerView.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SystemPickerDialog(
    systems: List<AgentSystem>,
    selectedSystemSlug: String?,
    selectedVersion: String?,
    isLoading: Boolean,
    onSelectSystem: (AgentSystem) -> Unit,
    onSelectVersion: (AgentSystemVersionSummary) -> Unit,
    onDismiss: () -> Unit
) {
    val currentSystem = selectedSystemSlug?.let { slug -> systems.firstOrNull { it.slug == slug } }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(bottom = 32.dp)) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("System Settings", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onDismiss) { Text("Done") }
            }

            HorizontalDivider()

            if (isLoading) {
                Box(modifier = Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (systems.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("📦", style = MaterialTheme.typography.displaySmall)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("No Systems Available", style = MaterialTheme.typography.headlineSmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("No agent systems have been configured.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn {
                    // Systems section
                    item {
                        Text("Systems", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp))
                    }
                    items(systems) { system ->
                        SystemRow(system, system.slug == selectedSystemSlug) { onSelectSystem(system) }
                    }

                    // Version section
                    currentSystem?.versions?.let { versions ->
                        if (versions.size > 1) {
                            item {
                                Text("Version — ${currentSystem.name}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp))
                            }
                            items(versions) { version ->
                                VersionRow(version, version.version == selectedVersion) { onSelectVersion(version) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SystemRow(system: AgentSystem, isSelected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onSelect).padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Layers, contentDescription = null, tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        Column(modifier = Modifier.weight(1f)) {
            Text(system.name, fontWeight = FontWeight.Medium)
            system.description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                system.activeVersion?.let { Text("🏷 $it", style = MaterialTheme.typography.labelSmall) }
                system.members?.let { Text("👥 ${it.size} agent${if (it.size == 1) "" else "s"}", style = MaterialTheme.typography.labelSmall) }
            }
        }
        if (isSelected) Icon(Icons.Default.CheckCircle, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun VersionRow(version: AgentSystemVersionSummary, isSelected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onSelect).padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.LocalOffer, contentDescription = null, tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(version.version)
            if (version.isActive) Badge(containerColor = Color(0xFF4CAF50).copy(alpha = 0.15f)) { Text("active", color = Color(0xFF4CAF50)) }
            if (version.isDraft) Badge(containerColor = Color(0xFFFF9800).copy(alpha = 0.15f)) { Text("draft", color = Color(0xFFFF9800)) }
        }
        if (isSelected) Icon(Icons.Default.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary)
    }
}

