package com.helpdesk.desktop

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.helpdesk.jiraagent.model.DarkThemePreset
import java.awt.Insets
import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopAppearanceTest {
    private fun contrast(a: Color, b: Color): Float {
        val l1 = a.luminance()
        val l2 = b.luminance()
        return (maxOf(l1, l2) + 0.05f) / (minOf(l1, l2) + 0.05f)
    }

    @Test fun calmPaletteHasLegibleTextAndButtons() {
        val light = DesktopSoftLightColors
        val dark = buildDesktopDarkColorScheme(DarkThemePreset.SOFT_SLATE)
        for (scheme in listOf(light, dark)) {
            assertTrue(contrast(scheme.onSurface, scheme.surface) >= 4.5f)
            assertTrue(contrast(scheme.onSurfaceVariant, scheme.surfaceVariant) >= 4.5f)
            assertTrue(contrast(scheme.onPrimary, scheme.primary) >= 4.5f)
            assertTrue(contrast(scheme.primary, scheme.surface) >= 4.5f)
        }
        assertTrue(contrast(Color.White, Color(0xFF86513A)) >= 4.5f)
    }

    @Test fun dialogsUseOwnerMonitorAndRespectTaskbarInsets() {
        assertEquals(Rectangle(-1920, 0, 1920, 1040), usableMonitorBounds(Rectangle(-1920, 0, 1920, 1080), Insets(0, 0, 40, 0)))
        assertEquals(Rectangle(10, 30, 1880, 1030), usableMonitorBounds(Rectangle(0, 0, 1920, 1080), Insets(30, 10, 20, 30)))
        assertEquals(Rectangle(0, 0, 1, 1), usableMonitorBounds(Rectangle(0, 0, 1, 1), Insets(20, 20, 20, 20)))
    }
}
