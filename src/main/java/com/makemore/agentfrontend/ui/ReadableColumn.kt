package com.makemore.agentfrontend.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.min

/**
 * Cap the chat's content column at a readable width and centre it, the
 * Android side of agent-ios's `ReadableColumn`. The element still reports the
 * full offered width to its parent, so backgrounds and scroll areas keep their
 * geometry; only the content is narrowed. On a phone the cap is never reached.
 */
fun Modifier.readableColumn(maxWidth: Dp = 650.dp): Modifier = layout { measurable, constraints ->
    val cap = maxWidth.roundToPx()
    val childMax = if (constraints.hasBoundedWidth) min(constraints.maxWidth, cap) else cap
    val placeable = measurable.measure(
        constraints.copy(minWidth = min(constraints.minWidth, childMax), maxWidth = childMax)
    )
    val width = if (constraints.hasBoundedWidth) constraints.maxWidth else placeable.width
    layout(width, placeable.height) {
        placeable.placeRelative((width - placeable.width) / 2, 0)
    }
}
