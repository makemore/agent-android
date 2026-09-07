package com.makemore.agentfrontend.ui

import kotlin.math.roundToInt

/** Uses an existing row, not total height: live tail growth must not move the reader. */
internal data class HistoryScrollAnchor(val messageId: String, val contentY: Float) {
    fun target(currentScroll: Int, newContentY: Float): Int =
        (currentScroll + newContentY - contentY).roundToInt().coerceAtLeast(0)
}