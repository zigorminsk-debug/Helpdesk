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
import com.helpdesk.jiraagent.model.ZabbixMutedRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant

class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == ACTION_MUTE_ZABBIX_PROBLEM) {
            val ruleKey = intent.getStringExtra(EXTRA_ZABBIX_MUTE_RULE_KEY)?.trim().orEmpty()
            if (ruleKey.isEmpty()) return
            val signatureKey = intent.getStringExtra(EXTRA_ZABBIX_SIGNATURE_KEY).orEmpty()
            val triggerId = intent.getStringExtra(EXTRA_ZABBIX_TRIGGER_ID).orEmpty()
            val hostLabel = intent.getStringExtra(EXTRA_ZABBIX_HOST_LABEL).orEmpty()
            val problemName = intent.getStringExtra(EXTRA_ZABBIX_PROBLEM_NAME).orEmpty()
            val severityLevel = intent.getIntExtra(EXTRA_ZABBIX_SEVERITY_LEVEL, 3)
            val alertKey = intent.getStringExtra(EXTRA_ZABBIX_ALERT_KEY).orEmpty()
            val notifId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)

            AlertSoundPlayer.stopCurrent()
            if (notifId != -1) {
                NotificationManagerCompat.from(context).cancel(notifId)
            }
            val appCtx = context.applicationContext
            val repo = SettingsRepository.getInstance(appCtx)
            repo.dismissInAppBanner()
            repo.upsertMutedZabbixRule(
                rule = ZabbixMutedRule(
                    ruleKey = ruleKey,
                    signatureKey = signatureKey,
                    triggerId = triggerId,
                    hostLabel = hostLabel,
                    problemName = problemName,
                    severityLevel = severityLevel
                ),
                currentAlertKey = alertKey
            )
            Toast.makeText(
                appCtx,
                "🔕 Проблема отмечена как нерешаемая — уведомления отключены",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        if (action == ACTION_ACK_ZABBIX_PROBLEM) {
            val eventId = intent.getStringExtra(EXTRA_ZABBIX_EVENT_ID)?.trim().orEmpty()
            if (eventId.isEmpty()) return
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
                    if (!settings.isZabbixConfigured) return@launch
                    val engineer = repo.syncStatus.value.connectedUser
                        ?: settings.zabbixLogin.ifBlank { "Инженер" }
                    val msg = "Принято в работу ($engineer · Help-CSL)"
                    repo.zabbixClient.acknowledgeProblem(settings, eventId, msg)
                        .onSuccess {
                            repo.markZabbixProblemAcknowledgedLocally(eventId, msg)
                            withContext(Dispatchers.Main) {
                                Toast.makeText(appCtx, "Проблема Zabbix #$eventId подтверждена", Toast.LENGTH_SHORT).show()
                            }
                        }
                        .onFailure { err ->
                            withContext(Dispatchers.Main) {
                                Toast.makeText(
                                    appCtx,
                                    err.message ?: "Не удалось подтвердить событие Zabbix",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                } finally {
                    pendingResult.finish()
                }
            }
            return
        }

        if (action != ACTION_TAKE_TICKET_IN_WORK) return
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
        const val ACTION_ACK_ZABBIX_PROBLEM = "com.helpdesk.jiraagent.action.ACK_ZABBIX_PROBLEM"
        const val ACTION_MUTE_ZABBIX_PROBLEM = "com.helpdesk.jiraagent.action.MUTE_ZABBIX_PROBLEM"
        const val EXTRA_TICKET_KEY = "extra_ticket_key"
        const val EXTRA_ZABBIX_EVENT_ID = "extra_zabbix_event_id"
        const val EXTRA_ZABBIX_MUTE_RULE_KEY = "extra_zabbix_mute_rule_key"
        const val EXTRA_ZABBIX_SIGNATURE_KEY = "extra_zabbix_signature_key"
        const val EXTRA_ZABBIX_TRIGGER_ID = "extra_zabbix_trigger_id"
        const val EXTRA_ZABBIX_HOST_LABEL = "extra_zabbix_host_label"
        const val EXTRA_ZABBIX_PROBLEM_NAME = "extra_zabbix_problem_name"
        const val EXTRA_ZABBIX_SEVERITY_LEVEL = "extra_zabbix_severity_level"
        const val EXTRA_ZABBIX_ALERT_KEY = "extra_zabbix_alert_key"
        const val EXTRA_NOTIFICATION_ID = "extra_notification_id"
    }
}
