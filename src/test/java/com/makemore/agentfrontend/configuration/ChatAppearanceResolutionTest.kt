package com.makemore.agentfrontend.configuration

import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.makemore.agentfrontend.resolveAgainst
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatAppearanceResolutionTest {
    @Test
    fun neutralColorsResolveAgainstMaterialTheme() {
        val colors = lightColorScheme()

        val resolved = ChatAppearance.neutral().resolveAgainst(colors)

        assertEquals(colors.background, resolved.background)
        assertEquals(colors.surface, resolved.surface)
        assertEquals(colors.surfaceContainerHigh, resolved.surfaceElevated)
        assertEquals(colors.onSurface, resolved.textPrimary)
        assertEquals(colors.onSurfaceVariant, resolved.textSecondary)
    }

    @Test
    fun explicitHostColorsArePreserved() {
        val custom = Color(0xFF123456)

        val resolved = ChatAppearance.neutral()
            .copy(background = custom, textPrimary = custom)
            .resolveAgainst(lightColorScheme())

        assertEquals(custom, resolved.background)
        assertEquals(custom, resolved.textPrimary)
    }
}