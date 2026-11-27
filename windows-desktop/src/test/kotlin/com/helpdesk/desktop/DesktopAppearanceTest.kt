package com.helpdesk.desktop

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import com.helpdesk.jiraagent.model.DarkThemePreset
import com.helpdesk.jiraagent.model.LightThemePreset
import com.helpdesk.jiraagent.model.AppSettings
import com.helpdesk.jiraagent.model.FilterSettings
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
        for (scheme in listOf(light, DesktopWarmLightColors, dark)) {
            assertTrue(contrast(scheme.onSurface, scheme.surface) >= 4.5f)
            assertTrue(contrast(scheme.onSurfaceVariant, scheme.surfaceVariant) >= 4.5f)
            assertTrue(contrast(scheme.onPrimary, scheme.primary) >= 4.5f)
            assertTrue(contrast(scheme.primary, scheme.surface) >= 4.5f)
        }
        assertTrue(contrast(Color.White, Color(0xFF86513A)) >= 4.5f)
    }

    @Test fun workspaceStackClosesOnlyTopThenAllInReverseOrder() {
        val host = DesktopWorkspaceOverlayHost()
        val closed = mutableListOf<String>()
        val content: @Composable () -> Unit = { }
        fun add(name: String) {
            lateinit var entry: DesktopWorkspaceOverlayEntry
            entry = DesktopWorkspaceOverlayEntry(Any(), mutableStateOf(name), mutableStateOf({ closed.add(name); host.entries.remove(entry); Unit }), mutableStateOf(content))
            host.entries.add(entry)
        }
        add("Инструменты")
        add("Подтверждение")
        host.dismissTop()
        assertEquals(listOf("Подтверждение"), closed)
        assertEquals(1, host.entries.size)
        host.dismissAll()
        assertEquals(listOf("Подтверждение", "Инструменты"), closed)
        assertTrue(!host.hasOverlay)
    }
    @Test fun calmPalettesSurviveQrSettingsTransfer() {
        val original = AppSettings(lightThemePreset = LightThemePreset.SOFT, darkThemePreset = DarkThemePreset.SOFT_SLATE)
        val payload = DesktopQrTransferCodec.encode(original, FilterSettings(), includeCredentials = false, pinCode = "1234")
        val restored = DesktopQrTransferCodec.decode(payload, "1234").getOrThrow().appSettings
        assertEquals(LightThemePreset.SOFT, restored.lightThemePreset)
        assertEquals(DarkThemePreset.SOFT_SLATE, restored.darkThemePreset)
        val warm = original.copy(lightThemePreset = LightThemePreset.WARM)
        val warmPayload = DesktopQrTransferCodec.encode(warm, FilterSettings(), includeCredentials = false)
        assertEquals(LightThemePreset.WARM, DesktopQrTransferCodec.decode(warmPayload).getOrThrow().appSettings.lightThemePreset)
    }

}
