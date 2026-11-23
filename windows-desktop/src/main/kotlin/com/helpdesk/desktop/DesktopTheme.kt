package com.helpdesk.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

val LocalDesktopDarkTheme = compositionLocalOf { false }

val CslMidnight = Color(0xFF070E24)
val CslNavySurface = Color(0xFF111C38)
val CslNavyBorder = Color(0xFF1E2D52)
val CslOrange = Color(0xFFF26419)
val CslOrangeDarkAccent = Color(0xFFFF7A33)
val CslOrangeContainerLight = Color(0xFFFFF1E8)
val CslOrangeContainerDark = Color(0xFF3A1C0E)
val CslSuccessGreen = Color(0xFF16A34A)
val CslSuccessGreenDark = Color(0xFF22C55E)
val CslInfoBlue = Color(0xFF2563EB)
val CslInfoBlueDark = Color(0xFF60A5FA)
val CslInfoBlueContainerLight = Color(0xFFEFF6FF)
val CslInfoBlueContainerDark = Color(0xFF172B4D)
val CslWarningAmber = Color(0xFFD97706)
val CslDangerRed = Color(0xFFEA3829)
val CslDeletedRed = Color(0xFFDC2626)

object DesktopColors {
    val CslMidnight = com.helpdesk.desktop.CslMidnight
    val CslNavySurface = com.helpdesk.desktop.CslNavySurface
    val CslNavyBorder = com.helpdesk.desktop.CslNavyBorder

    val CslOrange = com.helpdesk.desktop.CslOrange

    val CslOrangeContainer: Color
        @Composable get() = if (LocalDesktopDarkTheme.current) CslOrangeContainerDark else CslOrangeContainerLight

    val StatusGreen = CslSuccessGreen

    val StatusBlue = Color(0xFF3B82F6)

    val StatusBlueContainer: Color
        @Composable get() = if (LocalDesktopDarkTheme.current) CslInfoBlueContainerDark else CslInfoBlueContainerLight

    val StatusAmber = CslWarningAmber
    val StatusRed = CslDangerRed
    val DeletedRed = CslDeletedRed

    val ListPaneBg: Color
        @Composable get() = if (LocalDesktopDarkTheme.current) Color(0xFF0E1629) else Color(0xFFF8FAFC)

    val WorkspaceBg: Color
        @Composable get() = if (LocalDesktopDarkTheme.current) Color(0xFF090F1E) else Color(0xFFF1F5F9)

    val CardBg: Color
        @Composable get() = if (LocalDesktopDarkTheme.current) Color(0xFF131D35) else Color.White

    val SubtleBg: Color
        @Composable get() = if (LocalDesktopDarkTheme.current) Color(0xFF1A2744) else Color(0xFFF8FAFC)

    val BorderSubtle: Color
        @Composable get() = if (LocalDesktopDarkTheme.current) Color(0xFF263659) else Color(0xFFE2E8F0)

    val BorderStrong: Color
        @Composable get() = if (LocalDesktopDarkTheme.current) Color(0xFF334770) else Color(0xFFCBD5E1)

    val TextPrimary: Color
        @Composable get() = if (LocalDesktopDarkTheme.current) Color(0xFFF1F5F9) else Color(0xFF0F172A)

    val TextSecondary: Color
        @Composable get() = if (LocalDesktopDarkTheme.current) Color(0xFFCBD5E1) else Color(0xFF334155)

    val TextMuted: Color
        @Composable get() = if (LocalDesktopDarkTheme.current) Color(0xFF94A3B8) else Color(0xFF64748B)
}

private val DesktopLightColors = lightColorScheme(
    primary = CslOrange,
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

private val DesktopDarkColors = darkColorScheme(
    primary = CslOrangeDarkAccent,
    onPrimary = Color.White,
    primaryContainer = CslOrangeContainerDark,
    onPrimaryContainer = Color(0xFFFFDBCB),
    secondary = Color(0xFFCBD5E1),
    onSecondary = Color(0xFF0F172A),
    secondaryContainer = CslOrangeContainerDark,
    onSecondaryContainer = Color(0xFFFFB591),
    tertiary = CslInfoBlueDark,
    onTertiary = Color(0xFF082F49),
    tertiaryContainer = CslInfoBlueContainerDark,
    onTertiaryContainer = Color(0xFFBFDBFE),
    background = Color(0xFF090F1E),
    onBackground = Color(0xFFF1F5F9),
    surface = Color(0xFF131D35),
    onSurface = Color(0xFFF1F5F9),
    surfaceVariant = Color(0xFF1B2847),
    onSurfaceVariant = Color(0xFF94A3B8),
    outline = Color(0xFF334770),
    outlineVariant = Color(0xFF263659),
    error = Color(0xFFF87171),
    onError = Color(0xFF450A0A),
    errorContainer = Color(0xFF3F1518),
    onErrorContainer = Color(0xFFFECACA)
)

@Composable
fun HelpCslDesktopTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(LocalDesktopDarkTheme provides darkTheme) {
        MaterialTheme(
            colorScheme = if (darkTheme) DesktopDarkColors else DesktopLightColors,
            content = content
        )
    }
}
