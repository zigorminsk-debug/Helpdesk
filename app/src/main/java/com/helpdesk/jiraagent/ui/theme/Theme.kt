package com.helpdesk.jiraagent.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Corporate palette inspired directly by https://csl.by ("Компьютерная безопасность")
val CslOrange = Color(0xFFF7631B)          // $primary-color: #f7631b
val CslOrangeDark = Color(0xFFD64D0B)
val CslOrangeLight = Color(0xFFFF8A50)
val CslOrangeContainer = Color(0xFFFFEFE7)
val CslOrangeContainerDark = Color(0xFF4A1D06)

val CslMidnight = Color(0xFF04091E)        // .header-top / .promo: #04091e
val CslMidnightElevated = Color(0xFF0E1633)
val CslMidnightCard = Color(0xFF131C3B)

val CslOffWhite = Color(0xFFF6F7FB)        // $offwhite / .section-bg (#f9f9ff / #f9fafc)
val CslTitleInk = Color(0xFF222222)        // $title-color: #222222
val CslBodyMuted = Color(0xFF636878)       // $text-color: #777777 refined for high contrast
val CslOutlineLight = Color(0xFFE2E5F0)
val CslOutlineDark = Color(0xFF2C3557)

val CslInfoBlue = Color(0xFF2684FF)        // .genric-btn.info
val CslDangerRed = Color(0xFFF44A40)       // .genric-btn.danger: #f44a40
val CslSuccessGreen = Color(0xFF159756)    // csl.by green

// Kept aliases for call/phone accents
val JiraBlue = CslOrange
val JiraBlueDark = CslOrangeLight
val CallGreen = CslSuccessGreen
val CallGreenContainerLight = Color(0xFFE5F7EE)
val CallGreenContainerDark = Color(0xFF0E3B24)

private val LightColors = lightColorScheme(
    primary = CslOrange,
    onPrimary = Color.White,
    primaryContainer = CslOrangeContainer,
    onPrimaryContainer = Color(0xFF7A2900),
    secondary = CslMidnight,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE8ECF7),
    onSecondaryContainer = CslMidnight,
    tertiary = CslSuccessGreen,
    onTertiary = Color.White,
    tertiaryContainer = CallGreenContainerLight,
    onTertiaryContainer = Color(0xFF084C28),
    background = CslOffWhite,
    onBackground = CslTitleInk,
    surface = Color.White,
    onSurface = CslTitleInk,
    surfaceVariant = Color(0xFFEEF0F7),
    onSurfaceVariant = CslBodyMuted,
    outline = Color(0xFFC8CEDF),
    outlineVariant = CslOutlineLight,
    error = Color(0xFFD9382E),
    onError = Color.White,
    errorContainer = Color(0xFFFFE9E7),
    onErrorContainer = Color(0xFF7D1610)
)

private val DarkColors = darkColorScheme(
    primary = CslOrangeLight,
    onPrimary = Color(0xFF3D1500),
    primaryContainer = CslOrangeContainerDark,
    onPrimaryContainer = Color(0xFFFFDBCB),
    secondary = Color(0xFFBAC6EA),
    onSecondary = CslMidnight,
    secondaryContainer = Color(0xFF1D274B),
    onSecondaryContainer = Color(0xFFDCE3FA),
    tertiary = Color(0xFF57D996),
    onTertiary = Color(0xFF00381E),
    tertiaryContainer = CallGreenContainerDark,
    onTertiaryContainer = Color(0xFFB5F5D3),
    background = CslMidnight,
    onBackground = Color(0xFFF2F4FA),
    surface = CslMidnightCard,
    onSurface = Color(0xFFF2F4FA),
    surfaceVariant = Color(0xFF1C264A),
    onSurfaceVariant = Color(0xFFB0B8D1),
    outline = Color(0xFF3E4970),
    outlineVariant = CslOutlineDark,
    error = Color(0xFFFF8980),
    onError = Color(0xFF600F0A),
    errorContainer = Color(0xFF5C1914),
    onErrorContainer = Color(0xFFFFDAD6)
)

@Composable
fun JiraHelpdeskTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                // Match csl.by deep midnight header (#04091E) with crisp white status bar icons
                window.statusBarColor = CslMidnight.toArgb()
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
