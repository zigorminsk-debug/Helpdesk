package com.helpdesk.jiraagent.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import com.helpdesk.jiraagent.data.JiraApiClient
import com.helpdesk.jiraagent.data.JiraApiException
import com.helpdesk.jiraagent.data.SettingsRepository
import com.helpdesk.jiraagent.data.TicketWorkflowAction
import com.helpdesk.jiraagent.model.OfflineQueuedAction
import com.helpdesk.jiraagent.model.QueuedActionType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant

class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_TAKE_TICKET_IN_WORK) return
        val ticketKey = intent.getStringExtra(EXTRA_TICKET_KEY)?.trim().orEmpty()
        if (ticketKey.isEmpty()) return

        val notifId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
        AlertSoundPlayer.stopCurrent()
        if (notifId != -1) {
            NotificationManagerCompat.from(context).cancel(notifId)
        }

        val appCtx = context.applicationContext
        val repo = SettingsRepository.getInstance(appCtx)
        repo.dismissInAppBanner()

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val settings = repo.appSettings.value
                if (ticketKey.startsWith("DEMO-")) {
                    val existing = repo.tickets.value.firstOrNull { it.key == ticketKey }
                    if (existing != null) {
                        val nowMs = System.currentTimeMillis()
                        val agentName = repo.syncStatus.value.connectedUser
                            ?: settings.usernameOrEmail.ifBlank { "Текущий агент" }
                        repo.upsertSingleTicket(
                            existing.copy(
                                status = "В работе",
                                statusCategoryKey = "indeterminate",
                                assigneeName = agentName,
                                updatedIso = Instant.ofEpochMilli(nowMs).toString(),
                                updatedEpochMillis = nowMs
                            )
                        )
                    }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(appCtx, "Заявка $ticketKey взята в работу", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                if (!settings.isConfigured) return@launch

                val apiClient = JiraApiClient()
                apiClient.performWorkflowAction(
                    issueKey = ticketKey,
                    action = TicketWorkflowAction.TAKE_IN_WORK,
                    commentText = null,
                    explicitTransitionId = null,
                    settings = settings,
                    filterSettings = repo.filterSettings.value
                ).onSuccess { outcome ->
                    outcome.updatedTicket?.let { repo.upsertSingleTicket(it) }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(appCtx, "Заявка $ticketKey взята в работу", Toast.LENGTH_SHORT).show()
                    }
                }.onFailure { err ->
                    if ((err as? JiraApiException)?.isNetworkError == true) {
                        repo.enqueueOfflineAction(
                            OfflineQueuedAction(
                                type = QueuedActionType.WORKFLOW_ACTION,
                                ticketKey = ticketKey,
                                workflowActionName = TicketWorkflowAction.TAKE_IN_WORK.name
                            )
                        )
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                appCtx,
                                "$ticketKey сохранена в офлайн-очередь (отправится при появлении сети)",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                appCtx,
                                err.message ?: "Не удалось взять $ticketKey в работу",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_TAKE_TICKET_IN_WORK = "com.helpdesk.jiraagent.action.TAKE_TICKET_IN_WORK"
        const val EXTRA_TICKET_KEY = "extra_ticket_key"
        const val EXTRA_NOTIFICATION_ID = "extra_notification_id"
    }
}
