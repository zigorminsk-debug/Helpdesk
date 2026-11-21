package com.helpdesk.jiraagent.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.helpdesk.jiraagent.data.JiraApiClient
import com.helpdesk.jiraagent.data.SettingsRepository
import com.helpdesk.jiraagent.domain.BelarusWorkCalendar
import com.helpdesk.jiraagent.model.TicketStage
import com.helpdesk.jiraagent.notification.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class JiraMonitorService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollingJob: Job? = null

    private lateinit var repository: SettingsRepository
    private val apiClient = JiraApiClient()

    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

    override fun onCreate() {
        super.onCreate()
        repository = SettingsRepository.getInstance(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopPollingAndService()
                return START_NOT_STICKY
            }
            ACTION_POLL_NOW -> {
                ensureForegroundStarted("Обновление заявок…")
                serviceScope.launch { pollOnce() }
                if (pollingJob?.isActive != true) {
                    startPollingLoop()
                }
                return START_STICKY
            }
            else -> {
                val settings = repository.appSettings.value
                if (!settings.isConfigured || !settings.pollingEnabled) {
                    stopPollingAndService()
                    return START_NOT_STICKY
                }
                ensureForegroundStarted("Ожидание новых заявок клиентов…")
                startPollingLoop()
                return START_STICKY
            }
        }
    }

    private fun ensureForegroundStarted(statusText: String) {
        val notification = NotificationHelper.buildForegroundStatusNotification(this, statusText)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        runCatching {
            ServiceCompat.startForeground(
                this,
                NotificationHelper.SERVICE_NOTIFICATION_ID,
                notification,
                type
            )
            repository.updateSyncStatus { it.copy(isServiceRunning = true) }
        }
    }

    private fun updateForegroundText(statusText: String) {
        if (!NotificationHelper.hasNotificationPermission(this)) return
        runCatching {
            val notification = NotificationHelper.buildForegroundStatusNotification(this, statusText)
            NotificationManagerCompat.from(this)
                .notify(NotificationHelper.SERVICE_NOTIFICATION_ID, notification)
        }
    }

    private fun startPollingLoop() {
        pollingJob?.cancel()
        pollingJob = serviceScope.launch {
            while (isActive) {
                val settings = repository.appSettings.value
                if (!settings.isConfigured || !settings.pollingEnabled) {
                    stopPollingAndService()
                    break
                }
                pollOnce()
                val intervalSec = repository.appSettings.value.pollIntervalSeconds.coerceIn(10, 3600)
                delay(intervalSec * 1000L)
            }
        }
    }

    private suspend fun pollOnce() {
        val settings = repository.appSettings.value
        if (!settings.isConfigured) return
        val filter = repository.filterSettings.value

        repository.updateSyncStatus { it.copy(isSyncing = true) }

        val result = apiClient.fetchRecentTickets(settings, filter)
        result.onSuccess { tickets ->
            val nowMillis = System.currentTimeMillis()
            val schedule = BelarusWorkCalendar.evaluate(settings, Instant.ofEpochMilli(nowMillis))
            val newClientTickets = repository.processPolledTicketsAndFindNewClientOnes(
                polled = tickets,
                isNotificationWindowOpen = schedule.isAllowedNow
            )
            if (schedule.isAllowedNow) {
                for (ticket in newClientTickets) {
                    NotificationHelper.showClientTicketBanner(applicationContext, ticket)
                }
            }

            val newCount = tickets.count { it.isClientTicket && it.stage == TicketStage.NEW }
            val inProgressCount = tickets.count { it.isClientTicket && it.stage == TicketStage.IN_PROGRESS }
            val timeStr = Instant.ofEpochMilli(nowMillis)
                .atZone(ZoneId.systemDefault())
                .toLocalTime()
                .format(timeFormatter)

            val modePrefix = if (schedule.isAllowedNow) "" else "Тихий режим РБ · "
            updateForegroundText("${modePrefix}Новые: $newCount · В работе: $inProgressCount · $timeStr")
            repository.updateSyncStatus {
                it.copy(
                    isSyncing = false,
                    lastSyncEpochMillis = nowMillis,
                    lastError = null
                )
            }
        }.onFailure { err ->
            val msg = err.message ?: "Ошибка подключения к Jira"
            updateForegroundText("Ошибка связи: $msg")
            repository.updateSyncStatus {
                it.copy(
                    isSyncing = false,
                    lastError = msg
                )
            }
        }
    }

    private fun stopPollingAndService() {
        pollingJob?.cancel()
        repository.updateSyncStatus { it.copy(isServiceRunning = false, isSyncing = false) }
        runCatching {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }
        stopSelf()
    }

    override fun onDestroy() {
        repository.updateSyncStatus { it.copy(isServiceRunning = false, isSyncing = false) }
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START_OR_RESTART = "com.helpdesk.jiraagent.action.START_OR_RESTART"
        const val ACTION_POLL_NOW = "com.helpdesk.jiraagent.action.POLL_NOW"
        const val ACTION_STOP = "com.helpdesk.jiraagent.action.STOP"

        fun syncServiceState(context: Context) {
            val repo = SettingsRepository.getInstance(context)
            val settings = repo.appSettings.value
            if (settings.isConfigured && settings.pollingEnabled) {
                val intent = Intent(context, JiraMonitorService::class.java).apply {
                    action = ACTION_START_OR_RESTART
                }
                runCatching {
                    ContextCompat.startForegroundService(context, intent)
                }
            } else {
                val intent = Intent(context, JiraMonitorService::class.java).apply {
                    action = ACTION_STOP
                }
                runCatching {
                    context.startService(intent)
                }
            }
        }

        fun triggerImmediatePoll(context: Context) {
            val repo = SettingsRepository.getInstance(context)
            if (!repo.appSettings.value.isConfigured) return
            val intent = Intent(context, JiraMonitorService::class.java).apply {
                action = ACTION_POLL_NOW
            }
            runCatching {
                ContextCompat.startForegroundService(context, intent)
            }
        }
    }
}
