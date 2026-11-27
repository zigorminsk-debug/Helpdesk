package com.helpdesk.jiraagent

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.helpdesk.jiraagent.model.DarkThemePreset
import com.helpdesk.jiraagent.ui.theme.LightColors
import com.helpdesk.jiraagent.ui.theme.SoftLightColors
import com.helpdesk.jiraagent.ui.theme.WarmLightColors
import com.helpdesk.jiraagent.ui.theme.buildAndroidDarkColors
import org.junit.Assert.assertTrue
import org.junit.Test

class CalmThemeContrastTest {
    private fun contrast(a: Color, b: Color): Float {
        val x = a.luminance()
        val y = b.luminance()
        return (maxOf(x, y) + 0.05f) / (minOf(x, y) + 0.05f)
    }

    @Test fun wikiReaderSurfaceAndTextRemainReadableInAllThemes() {
        val schemes = listOf(LightColors, SoftLightColors, WarmLightColors) + DarkThemePreset.entries.map(::buildAndroidDarkColors)
        schemes.forEach { colors ->
            assertTrue("Wiki text must contrast with its painted surface", contrast(colors.onSurface, colors.surface) >= 4.5f)
            assertTrue("Wiki metadata must contrast with its painted surface", contrast(colors.onSurfaceVariant, colors.surface) >= 4.5f)
        }
    }

    @Test fun calmThemesProvideReadableCoreTextAndControls() {
        listOf(SoftLightColors, WarmLightColors, buildAndroidDarkColors(DarkThemePreset.SOFT_SLATE), buildAndroidDarkColors(DarkThemePreset.WATCHDOG_NAVY)).forEach { colors ->
            assertTrue(contrast(colors.onSurface, colors.surface) >= 4.5f)
            assertTrue(contrast(colors.onSurfaceVariant, colors.surfaceVariant) >= 4.5f)
            assertTrue(contrast(colors.onPrimary, colors.primary) >= 4.5f)
            assertTrue(contrast(colors.primary, colors.surface) >= 4.5f)
        }
    }
}
