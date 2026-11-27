package com.helpdesk.jiraagent.shared

import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

/** Desktop supplies a screen-sized host; Android keeps the platform's compact confirmation. */
interface SharedConfirmationPresenter {
    @Composable
    fun Show(onDismiss: () -> Unit, title: @Composable () -> Unit, text: @Composable () -> Unit,
             confirm: @Composable () -> Unit, dismiss: @Composable () -> Unit)
}

val LocalSharedConfirmationPresenter = staticCompositionLocalOf<SharedConfirmationPresenter?> { null }

@Composable
fun SharedConfirmationDialog(onDismissRequest: () -> Unit, title: @Composable () -> Unit,
                             text: @Composable () -> Unit, confirmButton: @Composable () -> Unit,
                             dismissButton: @Composable () -> Unit) {
    val presenter = LocalSharedConfirmationPresenter.current
    if (presenter == null) {
        AlertDialog(onDismissRequest = onDismissRequest, title = title, text = text,
            confirmButton = confirmButton, dismissButton = dismissButton)
    } else {
        presenter.Show(onDismissRequest, title, text, confirmButton, dismissButton)
    }
}
