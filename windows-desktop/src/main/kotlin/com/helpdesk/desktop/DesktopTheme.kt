package com.helpdesk.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val CslMidnight = Color(0xFF070E24)
val CslNavySurface = Color(0xFF111C38)
val CslNavyBorder = Color(0xFF1E2D52)
val CslOrange = Color(0xFFF26419)
val CslOrangeContainer = Color(0xFFFFF1E8)
val CslSuccessGreen = Color(0xFF16A34A)
val CslInfoBlue = Color(0xFF2563EB)
val CslInfoBlueContainer = Color(0xFFEFF6FF)
val CslWarningAmber = Color(0xFFD97706)
val CslDangerRed = Color(0xFFEA3829)
val CslDeletedRed = Color(0xFFDC2626)

object DesktopColors {
    val CslMidnight = com.helpdesk.desktop.CslMidnight
    val CslNavySurface = com.helpdesk.desktop.CslNavySurface
    val CslNavyBorder = com.helpdesk.desktop.CslNavyBorder
    val CslOrange = com.helpdesk.desktop.CslOrange
    val CslOrangeContainer = com.helpdesk.desktop.CslOrangeContainer
    val StatusGreen = CslSuccessGreen
    val StatusBlue = CslInfoBlue
    val StatusBlueContainer = CslInfoBlueContainer
    val StatusAmber = CslWarningAmber
    val StatusRed = CslDangerRed
    val DeletedRed = CslDeletedRed
    val ListPaneBg = Color(0xFFF8FAFC)
    val WorkspaceBg = Color(0xFFF1F5F9)
}

private val DesktopLightColors = lightColorScheme(
    primary = CslOrange,
    onPrimary = Color.White,
    primaryContainer = CslOrangeContainer,
    onPrimaryContainer = Color(0xFF7C2D12),
    secondary = Color(0xFF1E293B),
    onSecondary = Color.White,
    secondaryContainer = CslOrangeContainer,
    onSecondaryContainer = Color(0xFF9A3412),
    tertiary = CslInfoBlue,
    onTertiary = Color.White,
    tertiaryContainer = CslInfoBlueContainer,
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

@Composable
fun HelpCslDesktopTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DesktopLightColors,
        content = content
    )
}
