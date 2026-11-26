package com.helpdesk.jiraagent.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import com.helpdesk.jiraagent.data.JiraApiException
import com.helpdesk.jiraagent.data.SettingsRepository
import com.helpdesk.jiraagent.data.TicketWorkflowAction
import com.helpdesk.jiraagent.domain.PhoneNumberNormalizer
import com.helpdesk.jiraagent.domain.TicketStopwatchTracker
import com.helpdesk.jiraagent.model.OfflineQueuedAction
import com.helpdesk.jiraagent.model.PhoneRegion
import com.helpdesk.jiraagent.model.QueuedActionType
import com.helpdesk.jiraagent.model.TicketStage
import com.helpdesk.jiraagent.notification.AlertSoundPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant

object DialerLauncher {

    private val backgroundCallScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Opens the Android phone's default system dialer ("штатную звонилку по умолчанию")
     * with the phone number converted to international E.164 format (e.g. tel:+375291234567).
     *
     * Whenever [ticketKey] is supplied, Help-CSL also automatically transitions the ticket
     * into «В работе» (`TAKE_IN_WORK`), starts its work stopwatch, silences any active alert,
     * and falls back to the offline action queue if there is temporarily no network.
     */
    fun openSystemDialer(
        context: Context,
        phoneInput: String,
        region: PhoneRegion = PhoneRegion.AUTO_BY_RU,
        customCountryCode: String = "+375",
        ticketKey: String? = null,
        clientName: String? = null
    ) {
        val e164 = if (phoneInput.startsWith("+") && phoneInput.drop(1).all { it.isDigit() }) {
            phoneInput
        } else {
            PhoneNumberNormalizer.normalizeSingle(
                rawInput = phoneInput,
                region = region,
                customCountryCode = customCountryCode,
                strictForFreeText = false
            )?.first ?: run {
                val digits = phoneInput.filter { it.isDigit() }
                if (digits.isNotEmpty()) "+$digits" else return
            }
        }

        val cleanKey = ticketKey?.trim()?.takeIf { it.isNotEmpty() }
        val appContext = context.applicationContext

        val dialIntent = Intent(Intent.ACTION_DIAL).apply {
            data = Uri.parse("tel:${Uri.encode(e164)}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        runCatching {
            if (cleanKey != null) {
                val repo = SettingsRepository.getInstance(appContext)
                repo.recordOutgoingTicketCall(
                    ticketKey = cleanKey,
                    clientName = clientName.orEmpty().ifBlank { "Клиент" },
                    phoneNumber = e164
                )
                autoTakeTicketInWorkOnCall(appContext, cleanKey)
            }
            context.startActivity(dialIntent)
        }.onFailure {
            Toast.makeText(
                context,
                "Не удалось открыть приложение телефона для номера $e164",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun autoTakeTicketInWorkOnCall(appContext: Context, ticketKey: String) {
        AlertSoundPlayer.stopCurrent()
        val repo = SettingsRepository.getInstance(appContext)
        repo.dismissInAppBanner()
        runCatching {
            val notificationId = 2000 + (ticketKey.hashCode() and 0x7FFFFFFF) % 100_000
            NotificationManagerCompat.from(appContext).cancel(notificationId)
        }

        val existing = repo.tickets.value.firstOrNull { it.key.equals(ticketKey, ignoreCase = true) }
        // Start/resume work stopwatch on outgoing call if ticket isn't already closed
        if (existing?.stage != TicketStage.CLOSED) {
            val currentMap = repo.ticketStopwatches.value
            val nextMap = TicketStopwatchTracker.startOrResume(currentMap, ticketKey)
            if (nextMap !== currentMap) {
                repo.saveTicketStopwatches(nextMap)
            }
        }

        // Skip Jira workflow transition if ticket is already in progress or closed
        if (existing != null && (existing.stage == TicketStage.IN_PROGRESS || existing.stage == TicketStage.CLOSED)) {
            return
        }

        backgroundCallScope.launch {
            val settings = repo.appSettings.value
            val nowMs = System.currentTimeMillis()
            val nowIso = Instant.ofEpochMilli(nowMs).toString()
            val agentDisplay = repo.syncStatus.value.connectedUser
                ?: settings.usernameOrEmail.ifBlank { "Инженер" }

            if (ticketKey.startsWith("DEMO-")) {
                if (existing != null) {
                    repo.upsertSingleTicket(
                        existing.copy(
                            status = "В работе",
                            statusCategoryKey = "indeterminate",
                            assigneeName = agentDisplay,
                            updatedIso = nowIso,
                            updatedEpochMillis = nowMs
                        )
                    )
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(appContext, "📞 Звонок: заявка $ticketKey взята в работу", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            if (!settings.isConfigured) return@launch

            // Optimistically reflect "В работе" in UI immediately while dialer opens
            if (existing != null) {
                repo.upsertSingleTicket(
                    existing.copy(
                        status = "В работе",
                        statusCategoryKey = "indeterminate",
                        assigneeName = existing.assigneeName ?: agentDisplay,
                        updatedIso = nowIso,
                        updatedEpochMillis = nowMs
                    )
                )
            }

            repo.jiraApiClient.performWorkflowAction(
                issueKey = ticketKey,
                action = TicketWorkflowAction.TAKE_IN_WORK,
                commentText = null,
                explicitTransitionId = null,
                settings = settings,
                filterSettings = repo.filterSettings.value
            ).onSuccess { outcome ->
                outcome.updatedTicket?.let { repo.upsertSingleTicket(it) }
                withContext(Dispatchers.Main) {
                    Toast.makeText(appContext, "📞 Звонок: заявка $ticketKey взята в работу", Toast.LENGTH_SHORT).show()
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
                    if (existing != null) {
                        repo.upsertSingleTicket(
                            existing.copy(
                                status = "В работе (офлайн)",
                                statusCategoryKey = "indeterminate",
                                assigneeName = agentDisplay,
                                updatedIso = nowIso,
                                updatedEpochMillis = nowMs
                            )
                        )
                    }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            appContext,
                            "📞 $ticketKey переведена «В работе» (офлайн-очередь)",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }
    }

    fun openDialer(
        context: Context,
        phoneInput: String,
        ticketKey: String? = null,
        clientName: String? = null
    ) {
        openSystemDialer(
            context = context,
            phoneInput = phoneInput,
            ticketKey = ticketKey,
            clientName = clientName
        )
    }

    fun openBrowser(context: Context, url: String) {
        openExternalUrl(context = context, url = url)
    }

    fun openExternalUrl(context: Context, url: String) {
        if (url.isBlank()) return
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching {
            context.startActivity(intent)
        }.onFailure {
            Toast.makeText(context, "Не удалось открыть ссылку", Toast.LENGTH_SHORT).show()
        }
    }
}
