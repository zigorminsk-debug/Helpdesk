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
import kotlinx.coroutines.launch
import kotlin.math.abs

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
    val scrollbarStyle = ScrollbarStyle(
        minimalHeight = 28.dp,
        thickness = 8.dp,
        shape = RoundedCornerShape(4.dp),
        hoverDurationMillis = 150,
        unhoverColor = if (darkTheme) Color.White.copy(alpha = 0.28f) else Color(0xFF475569).copy(alpha = 0.36f),
        hoverColor = CslOrange
    )
    CompositionLocalProvider(
        LocalDesktopDarkTheme provides darkTheme,
        LocalScrollbarStyle provides scrollbarStyle
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DesktopDarkColors else DesktopLightColors,
            content = content
        )
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

        if (showScrollbar && isOverflowing) {
            Spacer(Modifier.height(3.dp))
            HorizontalScrollbar(
                adapter = rememberScrollbarAdapter(scrollState),
                modifier = Modifier.fillMaxWidth().height(7.dp)
            )
        }
    }
}
