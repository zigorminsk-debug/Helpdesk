package com.helpdesk.jiraagent.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val JiraBlue = Color(0xFF0052CC)
val JiraBlueDark = Color(0xFF4C9AFF)
val CallGreen = Color(0xFF1B873F)
val CallGreenContainerLight = Color(0xFFE3FCEF)
val CallGreenContainerDark = Color(0xFF0E3B20)

private val LightColors = lightColorScheme(
    primary = JiraBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDEEBFF),
    onPrimaryContainer = Color(0xFF0747A6),
    secondary = Color(0xFF006644),
    onSecondary = Color.White,
    secondaryContainer = CallGreenContainerLight,
    onSecondaryContainer = Color(0xFF004028),
    background = Color(0xFFF7F8FA),
    surface = Color.White,
    error = Color(0xFFBF2600)
)

private val DarkColors = darkColorScheme(
    primary = JiraBlueDark,
    onPrimary = Color(0xFF002966),
    primaryContainer = Color(0xFF0747A6),
    onPrimaryContainer = Color(0xFFDEEBFF),
    secondary = Color(0xFF57D9A3),
    onSecondary = Color(0xFF003822),
    secondaryContainer = CallGreenContainerDark,
    onSecondaryContainer = Color(0xFFABF5D1)
)

@Composable
fun JiraHelpdeskTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors
    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
