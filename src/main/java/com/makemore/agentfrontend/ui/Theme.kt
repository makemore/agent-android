package com.makemore.agentfrontend.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * Utility extensions for colors, mirroring iOS Color+Hex and PlatformColors.
 */

/** Parse a hex color string (e.g., "#0066cc" or "0066cc") */
fun Color.Companion.fromHex(hex: String): Color {
    val clean = hex.trimStart('#')
    val colorInt = clean.toLongOrNull(16) ?: 0x0066CCL
    return when (clean.length) {
        3 -> {
            val r = ((colorInt shr 8) and 0xF) * 17
            val g = ((colorInt shr 4) and 0xF) * 17
            val b = (colorInt and 0xF) * 17
            Color(r.toInt(), g.toInt(), b.toInt())
        }
        6 -> Color(
            red = ((colorInt shr 16) and 0xFF).toInt(),
            green = ((colorInt shr 8) and 0xFF).toInt(),
            blue = (colorInt and 0xFF).toInt()
        )
        8 -> Color(colorInt.toInt())
        else -> Color(0xFF0066CC)
    }
}

/** Get a contrasting text color (black or white) for this background color */
val Color.contrastingTextColor: Color
    get() = if (luminance() > 0.179f) Color.Black else Color.White

/**
 * Adaptive gray palette for message bubbles.
 * Provides dark-mode-aware colors so text remains readable regardless of the host theme.
 */
object AgentColors {
    // Light-mode values (kept for any non-composable use)
    val systemGray5Light = Color(0xFFE5E5EA)
    val systemGray6Light = Color(0xFFF2F2F7)

    // Dark-mode values (iOS-equivalent dark grays)
    val systemGray5Dark = Color(0xFF2C2C2E)
    val systemGray6Dark = Color(0xFF1C1C1E)

    /** Assistant message bubble background */
    val systemGray5: Color
        @Composable get() = if (isSystemInDarkTheme()) systemGray5Dark else systemGray5Light

    /** Tool/system message bubble background, input field, task row background */
    val systemGray6: Color
        @Composable get() = if (isSystemInDarkTheme()) systemGray6Dark else systemGray6Light

    /** General background */
    val systemBackground: Color
        @Composable get() = if (isSystemInDarkTheme()) Color.Black else Color.White
}

