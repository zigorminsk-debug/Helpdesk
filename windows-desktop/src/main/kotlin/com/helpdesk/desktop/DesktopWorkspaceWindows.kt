package com.helpdesk.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import com.helpdesk.jiraagent.shared.SharedConfirmationPresenter
import java.awt.Insets
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.Window

internal fun usableMonitorBounds(bounds: Rectangle, insets: Insets): Rectangle {
    val left = insets.left.coerceIn(0, bounds.width - 1)
    val top = insets.top.coerceIn(0, bounds.height - 1)
    val right = insets.right.coerceIn(0, bounds.width - left - 1)
    val bottom = insets.bottom.coerceIn(0, bounds.height - top - 1)
    return Rectangle(bounds.x + left, bounds.y + top, bounds.width - left - right, bounds.height - top - bottom)
}

/** Covers the usable area of the owner's monitor (not exclusive fullscreen), leaving the taskbar usable. */
@Composable
fun MaximizeDesktopDialog(window: Window) {
    LaunchedEffect(window) {
        withFrameNanos { }
        val config = window.owner?.graphicsConfiguration ?: window.graphicsConfiguration
        if (config != null) {
            val bounds = config.bounds
            val insets = runCatching { Toolkit.getDefaultToolkit().getScreenInsets(config) }.getOrNull()
            window.bounds = usableMonitorBounds(bounds, insets ?: Insets(0, 0, 0, 0))
        }
    }
}

@Composable
fun DesktopWorkspaceWindow(onDismissRequest: () -> Unit, title: String, content: @Composable () -> Unit) {
    val dark = LocalDesktopDarkTheme.current
    val preset = LocalDesktopDarkPreset.current
    val lightPreset = LocalDesktopLightPreset.current
    DialogWindow(
        onCloseRequest = onDismissRequest,
        title = title,
        state = rememberDialogState(width = 900.dp, height = 650.dp),
        onPreviewKeyEvent = { if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) { onDismissRequest(); true } else false }
    ) {
        MaximizeDesktopDialog(window)
        HelpCslDesktopTheme(darkTheme = dark, darkPreset = preset, lightPreset = lightPreset) {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface) {
                content()
            }
        }
    }
}

/** Application dialogs fill the usable screen; OS file choosers and notifications stay system-sized. */
@Composable
fun DesktopWorkspaceAlert(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    properties: DialogProperties = DialogProperties(),
    scrollBody: Boolean = true
) {
    DesktopWorkspaceWindow(onDismissRequest = onDismissRequest, title = "Help-CSL") {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (title != null) title()
            HorizontalDivider()
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (scrollBody) Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) { text?.invoke() }
                else text?.invoke()
            }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                dismissButton?.invoke()
                confirmButton()
            }
        }
    }
}

internal object DesktopConfirmationPresenter : SharedConfirmationPresenter {
    @Composable
    override fun Show(onDismiss: () -> Unit, title: @Composable () -> Unit, text: @Composable () -> Unit,
                      confirm: @Composable () -> Unit, dismiss: @Composable () -> Unit) {
        DesktopWorkspaceAlert(onDismissRequest = onDismiss, title = title, text = text,
            confirmButton = confirm, dismissButton = dismiss)
    }
}
