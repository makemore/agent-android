package com.makemore.agentfrontend.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Thin banner that reports the current conversation's token usage and
 * (when the active model has a known `context_window`) its progress
 * against the model's limit.
 *
 * Fully server-driven. The banner reads from
 * `ChatViewModel.contextTokens` + `contextWindow` + `contextModelId`,
 * all three of which the runtime populates via `context.usage` SSE
 * events (one per LLM call). No client-side estimation — if the
 * runtime hasn't shipped usage yet the banner stays hidden.
 */
@Composable
fun ContextUsageBanner(
    totalTokens: Int,
    contextWindow: Int?,
    modelId: String?,
    modifier: Modifier = Modifier,
) {
    val progress = contextWindow?.takeIf { it > 0 }?.let {
        (totalTokens.toDouble() / it.toDouble()).coerceAtMost(1.0)
    }
    val animatedProgress = progress?.let { p ->
        animateFloatAsState(
            targetValue = p.toFloat(),
            animationSpec = tween(durationMillis = 180),
            label = "context-usage-progress",
        ).value
    }
    val color = usageColor(progress)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(22.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .semantics { contentDescription = accessibilityLabel(totalTokens, contextWindow, progress) },
    ) {
        // Progress fill. `0f` is rendered as a zero-width sliver so
        // the bar is visibly empty at conversation start. The width
        // fraction must precede `background`: after `fillMaxSize()` the
        // incoming constraints are already fixed to the full width, so a
        // trailing `fillMaxWidth(fraction)` is coerced back to full size
        // and the fill tints the whole banner regardless of progress.
        if ((animatedProgress ?: 0f) > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction = animatedProgress ?: 0f)
                    .background(color.copy(alpha = 0.35f)),
            )
        }
        // Foreground: icon + monospace count, laid over the bar.
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Speed,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(12.dp),
            )
            Spacer(modifier = Modifier.size(6.dp))
            Text(
                text = countText(totalTokens, contextWindow, progress),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.Monospace,
                color = color,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun usageColor(progress: Double?): Color {
    if (progress == null) return MaterialTheme.colorScheme.onSurfaceVariant
    return when {
        progress < 0.6 -> MaterialTheme.colorScheme.onSurfaceVariant
        progress < 0.85 -> Color(0xFFFF9800) // orange
        else -> MaterialTheme.colorScheme.error
    }
}

private fun countText(totalTokens: Int, contextWindow: Int?, progress: Double?): String {
    if (contextWindow != null && progress != null) {
        val pct = (progress * 100).toInt()
        return "${formatCount(totalTokens)} / ${formatCount(contextWindow)} ($pct%)"
    }
    // No window known (runtime didn't ship a `context_window` for
    // the active model) — just show the count. The progress bar
    // background is still rendered but stays empty.
    return "${formatCount(totalTokens)} tokens"
}

private fun accessibilityLabel(totalTokens: Int, contextWindow: Int?, progress: Double?): String {
    return if (contextWindow != null && progress != null) {
        val pct = (progress * 100).toInt()
        "Context usage $totalTokens of $contextWindow tokens, $pct percent"
    } else {
        "Context usage $totalTokens tokens"
    }
}

/**
 * Compact token count for the banner. Preserves tenths of a k
 * across the whole range so `123_456` renders as `123.4k`, not
 * `123k` — that precision matters for the "(%)" the user sees
 * next to it, which is sensitive to the underlying number.
 */
private fun formatCount(n: Int): String = when {
    n >= 1_000 -> {
        val k = n / 1000.0
        val rounded = (k * 10).toInt() / 10.0
        if (rounded % 1.0 == 0.0) {
            "${rounded.toInt()}k"
        } else {
            String.format("%.1fk", rounded)
        }
    }
    else -> n.toString()
}
