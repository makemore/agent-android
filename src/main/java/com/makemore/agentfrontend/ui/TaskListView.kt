package com.makemore.agentfrontend.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.makemore.agentfrontend.configuration.ChatWidgetConfig
import com.makemore.agentfrontend.models.TaskItem
import com.makemore.agentfrontend.models.TaskState

/**
 * Task list view.
 * Mirrors the iOS TaskListView struct.
 */
@Composable
fun TaskListView(
    tasks: List<TaskItem>,
    isLoading: Boolean,
    error: String?,
    config: ChatWidgetConfig,
    onRetry: () -> Unit = {}
) {
    when {
        isLoading -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        error != null -> {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("⚠️", style = MaterialTheme.typography.displaySmall)
                Spacer(modifier = Modifier.height(12.dp))
                Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(onClick = onRetry) { Text("Retry") }
            }
        }
        tasks.isEmpty() -> {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("✅", style = MaterialTheme.typography.displayMedium)
                Spacer(modifier = Modifier.height(16.dp))
                Text("No tasks yet", style = MaterialTheme.typography.headlineSmall)
                Spacer(modifier = Modifier.height(8.dp))
                Text("Tasks will appear here as the agent works", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        else -> {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(tasks, key = { it.id }) { task ->
                    TaskRowView(task, config)
                }
            }
        }
    }
}

@Composable
private fun TaskRowView(task: TaskItem, config: ChatWidgetConfig) {
    val state = task.taskState
    val stateColor = when (state) {
        TaskState.NOT_STARTED -> MaterialTheme.colorScheme.onSurfaceVariant
        TaskState.IN_PROGRESS -> config.primaryColor
        TaskState.COMPLETE -> Color(0xFF4CAF50)
        TaskState.CANCELLED -> Color.Red
    }
    val isFinished = state == TaskState.COMPLETE || state == TaskState.CANCELLED

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(AgentColors.systemGray6)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(state.icon, color = stateColor, style = MaterialTheme.typography.titleMedium)
        Column {
            Text(
                text = task.name,
                style = MaterialTheme.typography.titleSmall,
                textDecoration = if (isFinished) TextDecoration.LineThrough else TextDecoration.None
            )
            task.description?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
        }
    }
}

