package com.helpdesk.jiraagent.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.helpdesk.jiraagent.model.DarkThemePreset
import com.helpdesk.jiraagent.model.LightThemePreset

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

internal val LightColors = lightColorScheme(
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

internal val SoftLightColors = lightColorScheme(
    primary = Color(0xFF3D626E), onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE8E8), onPrimaryContainer = Color(0xFF20373D),
    secondary = Color(0xFF52645F), onSecondary = Color.White,
    secondaryContainer = Color(0xFFDFE8E1), onSecondaryContainer = Color(0xFF263B31),
    tertiary = Color(0xFF52645F), onTertiary = Color.White,
    background = Color(0xFFEFF2F1), onBackground = Color(0xFF283537),
    surface = Color(0xFFF7F8F6), onSurface = Color(0xFF283537),
    surfaceVariant = Color(0xFFE3EAE8), onSurfaceVariant = Color(0xFF445853),
    outline = Color(0xFF7C918A), outlineVariant = Color(0xFFCAD7D2),
    error = Color(0xFFA13E3A), onError = Color.White
)
internal val WarmLightColors = lightColorScheme(
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

internal fun buildAndroidDarkColors(preset: DarkThemePreset) = when (preset) {
    DarkThemePreset.CSL_NAVY -> darkColorScheme(
        primary = CslOrangeLight,
        onPrimary = Color.White,
        primaryContainer = Color(0xFF6B2D10),
        onPrimaryContainer = Color.White,
        secondary = Color(0xFFD8E2F0),
        onSecondary = CslMidnight,
        secondaryContainer = Color(0xFF5C280E),
        onSecondaryContainer = Color.White,
        tertiary = Color(0xFF57D996),
        onTertiary = Color.White,
        tertiaryContainer = CallGreenContainerDark,
        onTertiaryContainer = Color(0xFFD1FAE5),
        background = CslMidnight,
        onBackground = Color(0xFFF8FAFC),
        surface = CslMidnightCard,
        onSurface = Color(0xFFF8FAFC),
        surfaceVariant = Color(0xFF1E2952),
        onSurfaceVariant = Color(0xFFD0D9EE),
        outline = Color(0xFF4A5885),
        outlineVariant = CslOutlineDark,
        error = Color(0xFFFF8980),
        onError = Color.White,
        errorContainer = Color(0xFF5C1914),
        onErrorContainer = Color(0xFFFFDAD6)
    )
    DarkThemePreset.GRAPHITE_OLED -> darkColorScheme(
        primary = Color(0xFFFF782E),
        onPrimary = Color.White,
        primaryContainer = Color(0xFF662B0E),
        onPrimaryContainer = Color.White,
        secondary = Color(0xFFE2E8F0),
        onSecondary = Color(0xFF0A0B0E),
        secondaryContainer = Color(0xFF59260D),
        onSecondaryContainer = Color.White,
        tertiary = Color(0xFF34D399),
        onTertiary = Color.White,
        tertiaryContainer = Color(0xFF0E3B24),
        onTertiaryContainer = Color(0xFFD1FAE5),
        background = Color(0xFF090A0D),
        onBackground = Color(0xFFF8FAFC),
        surface = Color(0xFF151820),
        onSurface = Color(0xFFF8FAFC),
        surfaceVariant = Color(0xFF222734),
        onSurfaceVariant = Color(0xFFD8E0EC),
        outline = Color(0xFF454E66),
        outlineVariant = Color(0xFF2C3242),
        error = Color(0xFFF87171),
        onError = Color.White,
        errorContainer = Color(0xFF541616),
        onErrorContainer = Color(0xFFFEE2E2)
    )
    DarkThemePreset.NORD_SLATE -> darkColorScheme(
        primary = Color(0xFFFF8243),
        onPrimary = Color.White,
        primaryContainer = Color(0xFF264B6E),
        onPrimaryContainer = Color.White,
        secondary = Color(0xFFD8DEE9),
        onSecondary = Color(0xFF181C24),
        secondaryContainer = Color(0xFF2B5278),
        onSecondaryContainer = Color.White,
        tertiary = Color(0xFF38BDF8),
        onTertiary = Color.White,
        tertiaryContainer = Color(0xFF1E3A5F),
        onTertiaryContainer = Color(0xFFE0F2FE),
        background = Color(0xFF181C24),
        onBackground = Color(0xFFECEFF4),
        surface = Color(0xFF222834),
        onSurface = Color(0xFFECEFF4),
        surfaceVariant = Color(0xFF2F3849),
        onSurfaceVariant = Color(0xFFD8DEE9),
        outline = Color(0xFF4F5E7A),
        outlineVariant = Color(0xFF384359),
        error = Color(0xFFF87171),
        onError = Color.White,
        errorContainer = Color(0xFF541E24),
        onErrorContainer = Color(0xFFFEE2E2)
    )
    DarkThemePreset.EMERALD_NOC -> darkColorScheme(
        primary = Color(0xFF10B981),
        onPrimary = Color.White,
        primaryContainer = Color(0xFF0F523A),
        onPrimaryContainer = Color.White,
        secondary = Color(0xFFD1FAE5),
        onSecondary = Color(0xFF081310),
        secondaryContainer = Color(0xFF115E42),
        onSecondaryContainer = Color.White,
        tertiary = Color(0xFFFF8A50),
        onTertiary = Color.White,
        tertiaryContainer = Color(0xFF144535),
        onTertiaryContainer = Color(0xFFD1FAE5),
        background = Color(0xFF081310),
        onBackground = Color(0xFFF0FDF4),
        surface = Color(0xFF11241F),
        onSurface = Color(0xFFF0FDF4),
        surfaceVariant = Color(0xFF1B3830),
        onSurfaceVariant = Color(0xFFC6F0DC),
        outline = Color(0xFF346B5C),
        outlineVariant = Color(0xFF244A40),
        error = Color(0xFFF87171),
        onError = Color.White,
        errorContainer = Color(0xFF521818),
        onErrorContainer = Color(0xFFFEE2E2)
    )
    DarkThemePreset.CYBER_PLUM -> darkColorScheme(
        primary = Color(0xFFA78BFA),
        onPrimary = Color.White,
        primaryContainer = Color(0xFF432C7A),
        onPrimaryContainer = Color.White,
        secondary = Color(0xFFE0DAFA),
        onSecondary = Color(0xFF110E1C),
        secondaryContainer = Color(0xFF4C328A),
        onSecondaryContainer = Color.White,
        tertiary = Color(0xFFFF8A50),
        onTertiary = Color.White,
        tertiaryContainer = Color(0xFF2F2254),
        onTertiaryContainer = Color(0xFFEDE9FE),
        background = Color(0xFF110E1C),
        onBackground = Color(0xFFF5F3FF),
        surface = Color(0xFF1C172E),
        onSurface = Color(0xFFF5F3FF),
        surfaceVariant = Color(0xFF2B2347),
        onSurfaceVariant = Color(0xFFDDD6FE),
        outline = Color(0xFF564787),
        outlineVariant = Color(0xFF392F5C),
        error = Color(0xFFF87171),
        onError = Color.White,
        errorContainer = Color(0xFF541826),
        onErrorContainer = Color(0xFFFEE2E2)
    )
    DarkThemePreset.SOFT_SLATE -> darkColorScheme(
        primary = Color(0xFFD6B5A6), onPrimary = Color(0xFF35231C),
        primaryContainer = Color(0xFF60453A), onPrimaryContainer = Color(0xFFF9E6DB),
        secondary = Color(0xFFB8C7BD), onSecondary = Color(0xFF25362D),
        secondaryContainer = Color(0xFF3D4A43), onSecondaryContainer = Color(0xFFE1EAE3),
        tertiary = Color(0xFFB8C7BD), onTertiary = Color(0xFF25362D),
        tertiaryContainer = Color(0xFF3D4A43), onTertiaryContainer = Color(0xFFE1EAE3),
        background = Color(0xFF202427), onBackground = Color(0xFFE7E6E2),
        surface = Color(0xFF2C3134), onSurface = Color(0xFFE7E6E2),
        surfaceVariant = Color(0xFF3A4142), onSurfaceVariant = Color(0xFFD3D7D2),
        outline = Color(0xFF88918D), outlineVariant = Color(0xFF515A57),
        error = Color(0xFFF2B6AC), onError = Color(0xFF4D211B),
        errorContainer = Color(0xFF623B36), onErrorContainer = Color(0xFFFFDAD4)
    )
    DarkThemePreset.WATCHDOG_NAVY -> darkColorScheme(
        primary = Color(0xFF9BC8FF), onPrimary = Color(0xFF10263D),
        primaryContainer = Color(0xFF243B55), onPrimaryContainer = Color(0xFFF0F4F9),
        secondary = Color(0xFFD0D9E4), onSecondary = Color(0xFF111A29),
        secondaryContainer = Color(0xFF243B55), onSecondaryContainer = Color(0xFFF0F4F9),
        tertiary = Color(0xFF9BC8FF), onTertiary = Color(0xFF10263D),
        tertiaryContainer = Color(0xFF243B55), onTertiaryContainer = Color(0xFFF0F4F9),
        background = Color(0xFF111A29), onBackground = Color(0xFFF0F4F9),
        surface = Color(0xFF151F30), onSurface = Color(0xFFF0F4F9),
        surfaceVariant = Color(0xFF243B55), onSurfaceVariant = Color(0xFFD0D9E4),
        outline = Color(0xFF8295AC), outlineVariant = Color(0xFF273449),
        error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
        errorContainer = Color(0xFF70252A), onErrorContainer = Color(0xFFFFDAD6)
    )
    DarkThemePreset.WARM_ESPRESSO -> darkColorScheme(
        primary = Color(0xFFF59E0B),
        onPrimary = Color.White,
        primaryContainer = Color(0xFF63370E),
        onPrimaryContainer = Color.White,
        secondary = Color(0xFFE7DED6),
        onSecondary = Color(0xFF14110F),
        secondaryContainer = Color(0xFF6E3D10),
        onSecondaryContainer = Color.White,
        tertiary = Color(0xFF34D399),
        onTertiary = Color.White,
        tertiaryContainer = Color(0xFF3B2E25),
        onTertiaryContainer = Color(0xFFFEF3C7),
        background = Color(0xFF14110F),
        onBackground = Color(0xFFFAF6F2),
        surface = Color(0xFF221C18),
        onSurface = Color(0xFFFAF6F2),
        surfaceVariant = Color(0xFF332A24),
        onSurfaceVariant = Color(0xFFE5DBD2),
        outline = Color(0xFF635247),
        outlineVariant = Color(0xFF423730),
        error = Color(0xFFF87171),
        onError = Color.White,
        errorContainer = Color(0xFF541C18),
        onErrorContainer = Color(0xFFFEE2E2)
    )
}

@Composable
fun JiraHelpdeskTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    darkPreset: DarkThemePreset = DarkThemePreset.CSL_NAVY,
    lightPreset: LightThemePreset = LightThemePreset.CLASSIC,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) buildAndroidDarkColors(darkPreset) else when (lightPreset) { LightThemePreset.SOFT -> SoftLightColors; LightThemePreset.WARM -> WarmLightColors; LightThemePreset.CLASSIC -> LightColors }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                val barBg = if (darkTheme) colorScheme.background else CslMidnight
                window.statusBarColor = barBg.toArgb()
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
            }
        }
    }

    MaterialTheme(colorScheme = colorScheme) {
        CompositionLocalProvider(LocalContentColor provides colorScheme.onSurface, content = content)
    }
}
