package com.helpdesk.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val CslMidnight = Color(0xFF04091E)
val CslNavySurface = Color(0xFF0B132B)
val CslOrange = Color(0xFFF7631B)
val CslOrangeContainer = Color(0xFFFFEDE3)
val CslSuccessGreen = Color(0xFF2EB85C)
val CslWarningAmber = Color(0xFFD97706)
val CslDangerRed = Color(0xFFF44A40)
val CslDeletedRed = Color(0xFFDC2626)

object DesktopColors {
    val CslMidnight = com.helpdesk.desktop.CslMidnight
    val CslNavySurface = com.helpdesk.desktop.CslNavySurface
    val CslOrange = com.helpdesk.desktop.CslOrange
    val CslOrangeContainer = com.helpdesk.desktop.CslOrangeContainer
    val StatusGreen = CslSuccessGreen
    val StatusAmber = CslWarningAmber
    val StatusRed = CslDangerRed
    val DeletedRed = CslDeletedRed
}

private val DesktopLightColors = lightColorScheme(
    primary = CslOrange,
    onPrimary = Color.White,
    primaryContainer = CslOrangeContainer,
    onPrimaryContainer = Color(0xFF5C1E00),
    secondary = CslMidnight,
    onSecondary = Color.White,
    background = Color(0xFFF3F5F9),
    onBackground = Color(0xFF0F172A),
    surface = Color.White,
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFE8ECF4),
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
