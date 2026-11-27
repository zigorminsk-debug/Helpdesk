package com.helpdesk.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.helpdesk.jiraagent.model.DarkThemePreset
import com.helpdesk.jiraagent.model.LightThemePreset
import com.helpdesk.jiraagent.shared.LocalSharedConfirmationPresenter
import kotlinx.coroutines.launch
import kotlin.math.abs

val LocalDesktopDarkTheme = compositionLocalOf { false }
val LocalDesktopDarkPreset = compositionLocalOf { DarkThemePreset.CSL_NAVY }
val LocalDesktopLightPreset = compositionLocalOf { LightThemePreset.CLASSIC }

val CslMidnight = Color(0xFF070E24)
val CslNavySurface = Color(0xFF111C38)
val CslNavyBorder = Color(0xFF1E2D52)
val CslOrange = Color(0xFFF26419)
val CslOrangeDarkAccent = Color(0xFFFF7A33)
val CslOrangeContainerLight = Color(0xFFFFF1E8)
val CslOrangeContainerDark = Color(0xFF5C280E)
val CslSuccessGreen = Color(0xFF16A34A)
val CslSuccessGreenDark = Color(0xFF22C55E)
val CslInfoBlue = Color(0xFF2563EB)
val CslInfoBlueDark = Color(0xFF60A5FA)
val CslInfoBlueContainerLight = Color(0xFFEFF6FF)
val CslInfoBlueContainerDark = Color(0xFF1B3560)
val CslWarningAmber = Color(0xFFD97706)
val CslDangerRed = Color(0xFFEA3829)
val CslDeletedRed = Color(0xFFDC2626)

data class DesktopDarkPalette(
    val headerBg: Color,
    val headerSurface: Color,
    val headerBorder: Color,
    val subHeaderBg: Color,
    val workspaceBg: Color,
    val listPaneBg: Color,
    val cardBg: Color,
    val subtleBg: Color,
    val surfaceVariantBg: Color,
    val borderSubtle: Color,
    val borderStrong: Color,
    val primaryAccent: Color,
    val selectedContainer: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color
)

fun resolveDesktopDarkPalette(preset: DarkThemePreset): DesktopDarkPalette = when (preset) {
    DarkThemePreset.CSL_NAVY -> DesktopDarkPalette(
        headerBg = Color(0xFF060C1F),
        headerSurface = Color(0xFF111C38),
        headerBorder = Color(0xFF243763),
        subHeaderBg = Color(0xFF0A132C),
        workspaceBg = Color(0xFF090F1E),
        listPaneBg = Color(0xFF0E1629),
        cardBg = Color(0xFF131D35),
        subtleBg = Color(0xFF1A2744),
        surfaceVariantBg = Color(0xFF1E2D4F),
        borderSubtle = Color(0xFF2A3C63),
        borderStrong = Color(0xFF3B5284),
        primaryAccent = Color(0xFFFF7A33),
        selectedContainer = Color(0xFF6B2D10),
        textPrimary = Color(0xFFF8FAFC),
        textSecondary = Color(0xFFD8E2F0),
        textMuted = Color(0xFFA3B3CC)
    )
    DarkThemePreset.GRAPHITE_OLED -> DesktopDarkPalette(
        headerBg = Color(0xFF07080B),
        headerSurface = Color(0xFF14171F),
        headerBorder = Color(0xFF2A2F3D),
        subHeaderBg = Color(0xFF0D0F14),
        workspaceBg = Color(0xFF0A0B0E),
        listPaneBg = Color(0xFF101218),
        cardBg = Color(0xFF161922),
        subtleBg = Color(0xFF1E222E),
        surfaceVariantBg = Color(0xFF242938),
        borderSubtle = Color(0xFF2C3242),
        borderStrong = Color(0xFF3E465C),
        primaryAccent = Color(0xFFFF782E),
        selectedContainer = Color(0xFF662B0E),
        textPrimary = Color(0xFFF8FAFC),
        textSecondary = Color(0xFFE2E8F0),
        textMuted = Color(0xFFA1AAB8)
    )
    DarkThemePreset.NORD_SLATE -> DesktopDarkPalette(
        headerBg = Color(0xFF141820),
        headerSurface = Color(0xFF212734),
        headerBorder = Color(0xFF343E52),
        subHeaderBg = Color(0xFF1A1F2A),
        workspaceBg = Color(0xFF181C24),
        listPaneBg = Color(0xFF1D222C),
        cardBg = Color(0xFF232936),
        subtleBg = Color(0xFF2B3343),
        surfaceVariantBg = Color(0xFF313A4D),
        borderSubtle = Color(0xFF384359),
        borderStrong = Color(0xFF4C5A75),
        primaryAccent = Color(0xFFFF8243),
        selectedContainer = Color(0xFF264B6E),
        textPrimary = Color(0xFFECEFF4),
        textSecondary = Color(0xFFD8DEE9),
        textMuted = Color(0xFFAAB4C6)
    )
    DarkThemePreset.EMERALD_NOC -> DesktopDarkPalette(
        headerBg = Color(0xFF050E0C),
        headerSurface = Color(0xFF0F221D),
        headerBorder = Color(0xFF1E4037),
        subHeaderBg = Color(0xFF081613),
        workspaceBg = Color(0xFF081310),
        listPaneBg = Color(0xFF0C1B17),
        cardBg = Color(0xFF11241F),
        subtleBg = Color(0xFF173029),
        surfaceVariantBg = Color(0xFF1D3B33),
        borderSubtle = Color(0xFF244A40),
        borderStrong = Color(0xFF316356),
        primaryAccent = Color(0xFF10B981),
        selectedContainer = Color(0xFF0F523A),
        textPrimary = Color(0xFFF0FDF4),
        textSecondary = Color(0xFFD1FAE5),
        textMuted = Color(0xFF94C6B2)
    )
    DarkThemePreset.CYBER_PLUM -> DesktopDarkPalette(
        headerBg = Color(0xFF0C0914),
        headerSurface = Color(0xFF1A142B),
        headerBorder = Color(0xFF332854),
        subHeaderBg = Color(0xFF110D1E),
        workspaceBg = Color(0xFF110E1C),
        listPaneBg = Color(0xFF161224),
        cardBg = Color(0xFF1C172E),
        subtleBg = Color(0xFF251E3D),
        surfaceVariantBg = Color(0xFF2D254A),
        borderSubtle = Color(0xFF392F5C),
        borderStrong = Color(0xFF4F417D),
        primaryAccent = Color(0xFFA78BFA),
        selectedContainer = Color(0xFF432C7A),
        textPrimary = Color(0xFFF5F3FF),
        textSecondary = Color(0xFFE0DAFA),
        textMuted = Color(0xFFB1A6D4)
    )
    DarkThemePreset.SOFT_SLATE -> DesktopDarkPalette(
        headerBg = Color(0xFF1B2021), headerSurface = Color(0xFF303638), headerBorder = Color(0xFF4C5553),
        subHeaderBg = Color(0xFF252B2C), workspaceBg = Color(0xFF202427), listPaneBg = Color(0xFF262C2D),
        cardBg = Color(0xFF2C3134), subtleBg = Color(0xFF343A3C), surfaceVariantBg = Color(0xFF3A4142),
        borderSubtle = Color(0xFF515A57), borderStrong = Color(0xFF77817C),
        primaryAccent = Color(0xFFD6B5A6), selectedContainer = Color(0xFF60453A),
        textPrimary = Color(0xFFE7E6E2), textSecondary = Color(0xFFD3D7D2), textMuted = Color(0xFFBBC4BE)
    )
    // Colors sampled from the user's 1C Server Watchdog reference.
    DarkThemePreset.WATCHDOG_NAVY -> DesktopDarkPalette(
        headerBg = Color(0xFF151F30), headerSurface = Color(0xFF1D2A3F), headerBorder = Color(0xFF273449),
        subHeaderBg = Color(0xFF0D1422), workspaceBg = Color(0xFF111A29), listPaneBg = Color(0xFF111A29),
        cardBg = Color(0xFF151F30), subtleBg = Color(0xFF1C2C3E), surfaceVariantBg = Color(0xFF243B55),
        borderSubtle = Color(0xFF273449), borderStrong = Color(0xFF526A85),
        primaryAccent = Color(0xFF9BC8FF), selectedContainer = Color(0xFF243B55),
        textPrimary = Color(0xFFF0F4F9), textSecondary = Color(0xFFD0D9E4), textMuted = Color(0xFFA9B9CB)
    )
    DarkThemePreset.WARM_ESPRESSO -> DesktopDarkPalette(
        headerBg = Color(0xFF0F0C0A),
        headerSurface = Color(0xFF211B17),
        headerBorder = Color(0xFF3B3029),
        subHeaderBg = Color(0xFF16120F),
        workspaceBg = Color(0xFF14110F),
        listPaneBg = Color(0xFF1A1613),
        cardBg = Color(0xFF221C18),
        subtleBg = Color(0xFF2C2520),
        surfaceVariantBg = Color(0xFF362D27),
        borderSubtle = Color(0xFF423730),
        borderStrong = Color(0xFF594A41),
        primaryAccent = Color(0xFFF59E0B),
        selectedContainer = Color(0xFF63370E),
        textPrimary = Color(0xFFFAF6F2),
        textSecondary = Color(0xFFE7DED6),
        textMuted = Color(0xFFB8AAA0)
    )
}

object DesktopColors {
    val CslMidnight: Color
        @Composable get() = if (LocalDesktopDarkTheme.current) {
            resolveDesktopDarkPalette(LocalDesktopDarkPreset.current).headerBg
        } else {
            when (LocalDesktopLightPreset.current) {
                LightThemePreset.SOFT -> Color(0xFF34464B)
                LightThemePreset.WARM -> Color(0xFF504B43)
                LightThemePreset.CLASSIC -> com.helpdesk.desktop.CslMidnight
            }
        }

    val CslNavySurface: Color
        @Composable get() = if (LocalDesktopDarkTheme.current) {
            resolveDesktopDarkPalette(LocalDesktopDarkPreset.current).headerSurface
        } else {
            when (LocalDesktopLightPreset.current) {
                LightThemePreset.SOFT -> Color(0xFF465B60)
                LightThemePreset.WARM -> Color(0xFF645E54)
                LightThemePreset.CLASSIC -> com.helpdesk.desktop.CslNavySurface
            }
        }

    val CslNavyBorder: Color
        @Composable get() = if (LocalDesktopDarkTheme.current) {
            resolveDesktopDarkPalette(LocalDesktopDarkPreset.current).headerBorder
        } else {
            when (LocalDesktopLightPreset.current) {
                LightThemePreset.SOFT -> Color(0xFF687C80)
                LightThemePreset.WARM -> Color(0xFF827C70)
                LightThemePreset.CLASSIC -> com.helpdesk.desktop.CslNavyBorder
            }
        }

    val SubHeaderBg: Color
        @Composable get() = if (LocalDesktopDarkTheme.current) {
            resolveDesktopDarkPalette(LocalDesktopDarkPreset.current).subHeaderBg
        } else {
            when (LocalDesktopLightPreset.current) {
                LightThemePreset.SOFT -> Color(0xFF3C4F53)
                LightThemePreset.WARM -> Color(0xFF5B554C)
                LightThemePreset.CLASSIC -> Color(0xFF0A132C)
            }
        }

    // Foreground accent and filled-button accent are separate to preserve contrast in both modes.
    val CslOrange: Color
        @Composable get() = if (LocalDesktopDarkTheme.current) {
            when (LocalDesktopDarkPreset.current) {
                DarkThemePreset.WATCHDOG_NAVY -> Color(0xFF9BC8FF)
                DarkThemePreset.SOFT_SLATE -> Color(0xFFD6B5A6)
                DarkThemePreset.EMERALD_NOC -> Color(0xFF92D1B4)
                DarkThemePreset.CYBER_PLUM -> Color(0xFFCABAF2)
                DarkThemePreset.WARM_ESPRESSO -> Color(0xFFE9BD82)
                else -> Color(0xFFFFAF83)
            }
        } else when (LocalDesktopLightPreset.current) {
            LightThemePreset.SOFT -> Color(0xFF3D626E)
            LightThemePreset.WARM -> Color(0xFF6D5D4F)
            LightThemePreset.CLASSIC -> Color(0xFFA94F29)
        }

    val ButtonAccent: Color
        @Composable get() = if (LocalDesktopDarkTheme.current) {
            when (LocalDesktopDarkPreset.current) {
                DarkThemePreset.WATCHDOG_NAVY -> Color(0xFF1F6FEB)
                DarkThemePreset.EMERALD_NOC -> Color(0xFF176B4D)
                DarkThemePreset.CYBER_PLUM -> Color(0xFF66518E)
                DarkThemePreset.WARM_ESPRESSO -> Color(0xFF855A2D)
                DarkThemePreset.SOFT_SLATE -> Color(0xFF86513A)
                else -> Color(0xFFA94F29)
            }
        } else when (LocalDesktopLightPreset.current) {
            LightThemePreset.SOFT -> Color(0xFF3D626E)
            LightThemePreset.WARM -> Color(0xFF6D5D4F)
            LightThemePreset.CLASSIC -> Color(0xFFA94F29)
        }

    val HeaderAccent: Color
        @Composable get() = when {
            LocalDesktopDarkTheme.current && LocalDesktopDarkPreset.current == DarkThemePreset.WATCHDOG_NAVY -> Color(0xFFB9D8F8)
            !LocalDesktopDarkTheme.current && LocalDesktopLightPreset.current != LightThemePreset.CLASSIC -> Color(0xFFE5EFEC)
            else -> Color(0xFFF2C6AD)
        }

    val CslOrangeContainer: Color
        @Composable get() = if (LocalDesktopDarkTheme.current) {
            resolveDesktopDarkPalette(LocalDesktopDarkPreset.current).selectedContainer
        } else {
            when (LocalDesktopLightPreset.current) {
                LightThemePreset.SOFT -> Color(0xFFDCE8E8)
                LightThemePreset.WARM -> Color(0xFFE9E0D3)
                LightThemePreset.CLASSIC -> CslOrangeContainerLight
            }
        }

    val StatusGreen: Color
        @Composable get() = if (LocalDesktopDarkTheme.current) CslSuccessGreenDark else Color(0xFF31745A)

    val StatusBlue: Color
        @Composable get() = if (LocalDesktopDarkTheme.current) CslInfoBlueDark else Color(0xFF3D6782)

    val StatusBlueContainer: Color
        @Composable get() = if (LocalDesktopDarkTheme.current) CslInfoBlueContainerDark else CslInfoBlueContainerLight

    val StatusAmber: Color
        @Composable get() = if (LocalDesktopDarkTheme.current) Color(0xFFFBBF24) else Color(0xFF80602F)

    val StatusRed: Color
        @Composable get() = if (LocalDesktopDarkTheme.current) Color(0xFFF87171) else Color(0xFFA63F3B)

    val DeletedRed = CslDeletedRed

    val ListPaneBg: Color
        @Composable get() = if (LocalDesktopDarkTheme.current) {
            resolveDesktopDarkPalette(LocalDesktopDarkPreset.current).listPaneBg
        } else {
            when (LocalDesktopLightPreset.current) { LightThemePreset.SOFT -> Color(0xFFE8EEEC); LightThemePreset.WARM -> Color(0xFFECE6DD); LightThemePreset.CLASSIC -> Color(0xFFF8FAFC) }
        }

    val WorkspaceBg: Color
        @Composable get() = if (LocalDesktopDarkTheme.current) {
            resolveDesktopDarkPalette(LocalDesktopDarkPreset.current).workspaceBg
        } else {
            when (LocalDesktopLightPreset.current) { LightThemePreset.SOFT -> Color(0xFFEFF2F1); LightThemePreset.WARM -> Color(0xFFF1EDE6); LightThemePreset.CLASSIC -> Color(0xFFF1F5F9) }
        }

    val CardBg: Color
        @Composable get() = if (LocalDesktopDarkTheme.current) {
            resolveDesktopDarkPalette(LocalDesktopDarkPreset.current).cardBg
        } else {
            when (LocalDesktopLightPreset.current) { LightThemePreset.SOFT -> Color(0xFFF7F8F6); LightThemePreset.WARM -> Color(0xFFF7F3EC); LightThemePreset.CLASSIC -> Color.White }
        }

    val SubtleBg: Color
        @Composable get() = if (LocalDesktopDarkTheme.current) {
            resolveDesktopDarkPalette(LocalDesktopDarkPreset.current).subtleBg
        } else {
            Color(0xFFF8FAFC)
        }

    val BorderSubtle: Color
        @Composable get() = if (LocalDesktopDarkTheme.current) {
            resolveDesktopDarkPalette(LocalDesktopDarkPreset.current).borderSubtle
        } else {
            Color(0xFFE2E8F0)
        }

    val BorderStrong: Color
        @Composable get() = if (LocalDesktopDarkTheme.current) {
            resolveDesktopDarkPalette(LocalDesktopDarkPreset.current).borderStrong
        } else {
            Color(0xFFCBD5E1)
        }

    val TextPrimary: Color
        @Composable get() = if (LocalDesktopDarkTheme.current) {
            resolveDesktopDarkPalette(LocalDesktopDarkPreset.current).textPrimary
        } else {
            Color(0xFF0F172A)
        }

    val TextSecondary: Color
        @Composable get() = if (LocalDesktopDarkTheme.current) {
            resolveDesktopDarkPalette(LocalDesktopDarkPreset.current).textSecondary
        } else {
            Color(0xFF334155)
        }

    val TextMuted: Color
        @Composable get() = if (LocalDesktopDarkTheme.current) {
            resolveDesktopDarkPalette(LocalDesktopDarkPreset.current).textMuted
        } else {
            Color(0xFF64748B)
        }
}

private val DesktopLightColors = lightColorScheme(
    primary = Color(0xFFA94F29),
    onPrimary = Color.White,
    primaryContainer = CslOrangeContainerLight,
    onPrimaryContainer = Color(0xFF7C2D12),
    secondary = Color(0xFF1E293B),
    onSecondary = Color.White,
    secondaryContainer = CslOrangeContainerLight,
    onSecondaryContainer = Color(0xFF9A3412),
    tertiary = CslInfoBlue,
    onTertiary = Color.White,
    tertiaryContainer = CslInfoBlueContainerLight,
    onTertiaryContainer = Color(0xFF1E3A8A),
    background = Color(0xFFF1F5F9),
    onBackground = Color(0xFF0F172A),
    surface = Color.White,
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFF1F5F9),
    onSurfaceVariant = Color(0xFF475569),
    outline = Color(0xFFCBD5E1),
    outlineVariant = Color(0xFFE2E8F0),
    error = CslDangerRed,
    onError = Color.White
)

internal val DesktopSoftLightColors = lightColorScheme(
    primary = Color(0xFF3D626E), onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE8E8), onPrimaryContainer = Color(0xFF20373D),
    secondary = Color(0xFF52645F), onSecondary = Color.White,
    secondaryContainer = Color(0xFFDFE8E1), onSecondaryContainer = Color(0xFF263B31),
    tertiary = Color(0xFF52645F), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFDFE8E1), onTertiaryContainer = Color(0xFF263B31),
    background = Color(0xFFEFF2F1), onBackground = Color(0xFF283537),
    surface = Color(0xFFF7F8F6), onSurface = Color(0xFF283537),
    surfaceVariant = Color(0xFFE3EAE8), onSurfaceVariant = Color(0xFF445853),
    outline = Color(0xFF7C918A), outlineVariant = Color(0xFFCAD7D2),
    error = Color(0xFFA13E3A), onError = Color.White,
    errorContainer = Color(0xFFF5DEDA), onErrorContainer = Color(0xFF501C1B)
)
internal val DesktopWarmLightColors = lightColorScheme(
    primary = Color(0xFF6D5D4F), onPrimary = Color.White,
    primaryContainer = Color(0xFFE9E0D3), onPrimaryContainer = Color(0xFF3C3126),
    secondary = Color(0xFF5C6557), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE1E7DA), onSecondaryContainer = Color(0xFF303B2C),
    background = Color(0xFFF1EDE6), onBackground = Color(0xFF332F2A),
    surface = Color(0xFFF7F3EC), onSurface = Color(0xFF332F2A),
    surfaceVariant = Color(0xFFEAE4DB), onSurfaceVariant = Color(0xFF585148),
    outline = Color(0xFF8F8679), outlineVariant = Color(0xFFD5CDC1),
    error = Color(0xFFA13E3A), onError = Color.White
)

internal fun buildDesktopDarkColorScheme(preset: DarkThemePreset) = resolveDesktopDarkPalette(preset).let { p ->
    darkColorScheme(
        primary = p.primaryAccent,
        onPrimary = when (preset) {
            DarkThemePreset.WATCHDOG_NAVY -> Color(0xFF10263D)
            DarkThemePreset.SOFT_SLATE -> Color(0xFF35231C)
            else -> Color(0xFF21150E)
        },
        primaryContainer = p.selectedContainer,
        onPrimaryContainer = Color.White,
        secondary = p.textSecondary,
        onSecondary = p.workspaceBg,
        secondaryContainer = p.selectedContainer,
        onSecondaryContainer = Color.White,
        tertiary = CslInfoBlueDark,
        onTertiary = Color.White,
        tertiaryContainer = CslInfoBlueContainerDark,
        onTertiaryContainer = Color(0xFFDBEAFE),
        background = p.workspaceBg,
        onBackground = p.textPrimary,
        surface = p.cardBg,
        onSurface = p.textPrimary,
        surfaceVariant = p.surfaceVariantBg,
        onSurfaceVariant = p.textSecondary,
        outline = p.borderStrong,
        outlineVariant = p.borderSubtle,
        error = Color(0xFFF87171),
        onError = Color.White,
        errorContainer = Color(0xFF4C161A),
        onErrorContainer = Color(0xFFFEE2E2)
    )
}

@Composable
fun HelpCslDesktopTheme(
    darkTheme: Boolean = LocalDesktopDarkTheme.current,
    darkPreset: DarkThemePreset = LocalDesktopDarkPreset.current,
    lightPreset: LightThemePreset = LocalDesktopLightPreset.current,
    content: @Composable () -> Unit
) {
    val palette = resolveDesktopDarkPalette(darkPreset)
    val scrollbarStyle = ScrollbarStyle(
        minimalHeight = 28.dp,
        thickness = 8.dp,
        shape = RoundedCornerShape(4.dp),
        hoverDurationMillis = 150,
        unhoverColor = if (darkTheme) Color.White.copy(alpha = 0.28f) else Color(0xFF475569).copy(alpha = 0.36f),
        hoverColor = if (darkTheme) palette.primaryAccent else com.helpdesk.desktop.CslOrange
    )
    CompositionLocalProvider(
        LocalDesktopDarkTheme provides darkTheme,
        LocalDesktopDarkPreset provides darkPreset,
        LocalDesktopLightPreset provides lightPreset,
        LocalSharedConfirmationPresenter provides DesktopConfirmationPresenter,
        LocalScrollbarStyle provides scrollbarStyle
    ) {
        val colors = if (darkTheme) buildDesktopDarkColorScheme(darkPreset) else when (lightPreset) { LightThemePreset.SOFT -> DesktopSoftLightColors; LightThemePreset.WARM -> DesktopWarmLightColors; LightThemePreset.CLASSIC -> DesktopLightColors }
        MaterialTheme(colorScheme = colors) {
            // background() does not provide a content color; set a safe default for every window.
            CompositionLocalProvider(LocalContentColor provides colors.onSurface, content = content)
        }
    }
}

/**
 * Adds mouse-wheel -> horizontal scrolling on Windows/Desktop whenever [scrollState] overflows.
 */
fun Modifier.desktopWheelHorizontalScroll(scrollState: ScrollState): Modifier =
    this.pointerInput(scrollState) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                if (event.type == PointerEventType.Scroll && scrollState.maxValue > 0) {
                    val delta = event.changes.fold(0f) { acc, c ->
                        val d = if (abs(c.scrollDelta.x) > abs(c.scrollDelta.y)) c.scrollDelta.x else c.scrollDelta.y
                        acc + d
                    }
                    if (delta != 0f) {
                        val consumed = scrollState.dispatchRawDelta(delta * 56f)
                        if (consumed != 0f) {
                            event.changes.forEach { it.consume() }
                        }
                    }
                }
            }
        }
    }

/**
 * Horizontal chip/tab/button strip tailored for Windows Desktop:
 * - Supports standard vertical mouse-wheel scrolling left/right when overflowing
 * - Shows compact `◀` / `▶` arrow buttons whenever items overflow left or right (so off-screen tabs like `CSL` are 1 click away)
 * - Optionally renders a crisp draggable `HorizontalScrollbar` underneath when overflowing
 */
@Composable
fun DesktopHorizontalScrollRow(
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
    horizontalArrangement: Arrangement.Horizontal = Arrangement.spacedBy(6.dp),
    verticalAlignment: Alignment.Vertical = Alignment.CenterVertically,
    showArrowButtons: Boolean = true,
    showScrollbar: Boolean = true,
    arrowDarkSurface: Boolean = false,
    buttonHeight: Dp = 28.dp,
    content: @Composable RowScope.() -> Unit
) {
    val scope = rememberCoroutineScope()
    val canBack = scrollState.canScrollBackward
    val canFwd = scrollState.canScrollForward
    val isOverflowing = scrollState.maxValue > 0

    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .desktopWheelHorizontalScroll(scrollState),
            verticalAlignment = verticalAlignment,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (showArrowButtons && canBack) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (arrowDarkSurface) DesktopColors.CslNavySurface else DesktopColors.CardBg,
                    border = BorderStroke(1.dp, DesktopColors.CslOrange.copy(alpha = 0.75f)),
                    modifier = Modifier
                        .height(buttonHeight)
                        .width(24.dp)
                        .clickable {
                            scope.launch {
                                scrollState.animateScrollTo((scrollState.value - 240).coerceAtLeast(0))
                            }
                        }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "◀",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (arrowDarkSurface) Color.White else DesktopColors.CslOrange
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(scrollState),
                horizontalArrangement = horizontalArrangement,
                verticalAlignment = verticalAlignment,
                content = content
            )

            if (showArrowButtons && canFwd) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (arrowDarkSurface) DesktopColors.CslNavySurface else DesktopColors.CardBg,
                    border = BorderStroke(1.dp, DesktopColors.CslOrange.copy(alpha = 0.75f)),
                    modifier = Modifier
                        .height(buttonHeight)
                        .width(24.dp)
                        .clickable {
                            scope.launch {
                                scrollState.animateScrollTo((scrollState.value + 240).coerceAtMost(scrollState.maxValue))
                            }
                        }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "▶",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (arrowDarkSurface) Color.White else DesktopColors.CslOrange
                        )
                    }
                }
            }
        }

        if (showScrollbar && scrollState.maxValue > 12) {
            Spacer(Modifier.height(2.dp))
            HorizontalScrollbar(
                adapter = rememberScrollbarAdapter(scrollState),
                modifier = Modifier.fillMaxWidth().height(4.dp)
            )
        }
    }
}
