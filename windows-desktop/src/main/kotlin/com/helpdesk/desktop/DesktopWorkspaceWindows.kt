package com.helpdesk.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.helpdesk.jiraagent.shared.SharedConfirmationPresenter

/** The overlay lives in the main Compose window, never in a native DialogWindow. */
internal class DesktopWorkspaceOverlayHost {
    internal val entries = mutableStateListOf<DesktopWorkspaceOverlayEntry>()
    val hasOverlay: Boolean get() = entries.isNotEmpty()
    fun dismissTop() { entries.lastOrNull()?.dismiss?.value?.invoke() }
    fun dismissAll() { entries.toList().asReversed().forEach { it.dismiss.value.invoke() } }
}

internal class DesktopWorkspaceOverlayEntry(
    val key: Any,
    val title: State<String>,
    val dismiss: State<() -> Unit>,
    val content: State<@Composable () -> Unit>
)

internal val LocalDesktopWorkspaceOverlayHost = staticCompositionLocalOf<DesktopWorkspaceOverlayHost?> { null }

@Composable
internal fun DesktopWorkspaceOverlayLayers(host: DesktopWorkspaceOverlayHost) {
    host.entries.toList().forEach { entry ->
        key(entry.key) {
            val focusRequester = remember { FocusRequester() }
            val interaction = remember { MutableInteractionSource() }
            // A real full-size pointer target prevents clicks reaching the workspace beneath it.
            Surface(
                modifier = Modifier.fillMaxSize().clickable(interactionSource = interaction, indication = null) { }
                    .focusRequester(focusRequester).focusable(),
                color = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.onBackground
            ) {
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        OutlinedButton(onClick = { entry.dismiss.value() }) { Text("← Назад") }
                        Text(entry.title.value, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    }
                    HorizontalDivider()
                    Box(Modifier.weight(1f).fillMaxWidth()) { entry.content.value.invoke() }
                }
            }
            LaunchedEffect(entry.key) { focusRequester.requestFocus() }
        }
    }
}

@Composable
fun DesktopWorkspaceWindow(onDismissRequest: () -> Unit, title: String, content: @Composable () -> Unit) {
    val host = LocalDesktopWorkspaceOverlayHost.current
    val currentTitle = rememberUpdatedState(title)
    val currentDismiss = rememberUpdatedState(onDismissRequest)
    val currentContent = rememberUpdatedState(content)
    val key = remember { Any() }
    if (host != null) {
        DisposableEffect(host, key) {
            val entry = DesktopWorkspaceOverlayEntry(key, currentTitle, currentDismiss, currentContent)
            host.entries.add(entry)
            onDispose { host.entries.remove(entry) }
        }
    } else {
        // Useful for previews or tests without the application host.
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() }
    }
}

/** In-app workspace page, retaining the original callbacks and scroll state. */
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
    DesktopWorkspaceWindow(onDismissRequest = onDismissRequest, title = "Рабочее место") {
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
