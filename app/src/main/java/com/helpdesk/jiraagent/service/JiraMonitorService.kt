package com.helpdesk.jiraagent.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
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
import com.helpdesk.jiraagent.update.AppUpdateManager
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
    private var lastUpdateCheckEpochMillis: Long = 0L
    private var lastNotifiedUpdateTag: String? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        repository = SettingsRepository.getInstance(applicationContext)
        PostponeScheduler.scheduleNextAlarm(applicationContext)
        registerOfflineSyncNetworkCallback()
    }

    private fun registerOfflineSyncNetworkCallback() {
        runCatching {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (repository.offlineQueuedActions.value.isNotEmpty()) {
                        serviceScope.launch {
                            OfflineQueueProcessor.flushPending(applicationContext, apiClient)
                        }
                    }
                }
            }
            cm.registerDefaultNetworkCallback(cb)
            networkCallback = cb
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                if (repository.postponedTickets.value.isEmpty()) {
                    stopPollingAndService()
                    return START_NOT_STICKY
                }
            }
            ACTION_PROCESS_POSTPONED -> {
                ensureForegroundStarted("Пересоздание отложенной заявки…")
                serviceScope.launch {
                    PostponeScheduler.processDuePostponedTickets(applicationContext, apiClient)
                    if (repository.appSettings.value.isConfigured) {
                        pollOnce()
                    }
                    if (!repository.appSettings.value.pollingEnabled &&
                        repository.postponedTickets.value.isEmpty()
                    ) {
                        stopPollingAndService()
                    }
                }
                if (pollingJob?.isActive != true) {
                    startPollingLoop()
                }
                return START_STICKY
            }
            ACTION_POLL_NOW -> {
                ensureForegroundStarted("Обновление заявок…")
                serviceScope.launch {
                    PostponeScheduler.processDuePostponedTickets(applicationContext, apiClient)
                    pollOnce()
                }
                if (pollingJob?.isActive != true) {
                    startPollingLoop()
                }
                return START_STICKY
            }
        }

        val settings = repository.appSettings.value
        val hasPostponed = repository.postponedTickets.value.isNotEmpty()
        if ((!settings.isConfigured || !settings.pollingEnabled) && !hasPostponed) {
            stopPollingAndService()
            return START_NOT_STICKY
        }
        ensureForegroundStarted("Ожидание новых заявок клиентов…")
        startPollingLoop()
        return START_STICKY
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
                val hasPostponed = repository.postponedTickets.value.isNotEmpty()
                if ((!settings.isConfigured || !settings.pollingEnabled) && !hasPostponed) {
                    stopPollingAndService()
                    break
                }

                // Always process due postponed tickets first
                PostponeScheduler.processDuePostponedTickets(applicationContext, apiClient)

                if (settings.isConfigured && settings.pollingEnabled) {
                    pollOnce()
                    pollAdminChatSafely()
                }

                // Periodically check GitHub Releases for new APK versions (every 90 minutes)
                if (settings.autoCheckUpdates) {
                    val now = System.currentTimeMillis()
                    if (now - lastUpdateCheckEpochMillis >= 90L * 60_000L) {
                        lastUpdateCheckEpochMillis = now
                        AppUpdateManager.checkForUpdate().onSuccess { update ->
                            if (update != null && update.tagName != lastNotifiedUpdateTag) {
                                lastNotifiedUpdateTag = update.tagName
                                NotificationHelper.showAppUpdateNotification(applicationContext, update)
                            }
                        }
                    }
                }

                val intervalMs = repository.appSettings.value.pollIntervalSeconds.coerceIn(10, 3600) * 1000L
                val nextPostponedAt = repository.postponedTickets.value
                    .minByOrNull { it.scheduledAtMillis }
                    ?.scheduledAtMillis
                val waitMs = if (nextPostponedAt != null) {
                    (nextPostponedAt - System.currentTimeMillis()).coerceIn(1_000L, intervalMs)
                } else {
                    intervalMs
                }
                delay(waitMs)
            }
        }
    }

    private suspend fun pollOnce() {
        val settings = repository.appSettings.value
        if (!settings.isConfigured) return
        val filter = repository.filterSettings.value

        repository.updateSyncStatus { it.copy(isSyncing = true) }

        if (repository.offlineQueuedActions.value.isNotEmpty()) {
            runCatching { OfflineQueueProcessor.flushPending(applicationContext, apiClient) }
        }

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
                if (settings.staleTicketReminderEnabled) {
                    val staleDue = repository.findDueStaleUnassignedTickets(
                        polled = repository.tickets.value,
                        reminderMinutes = settings.staleTicketReminderMinutes,
                        nowMillis = nowMillis
                    )
                    for (stale in staleDue) {
                        if (newClientTickets.none { it.key == stale.key }) {
                            NotificationHelper.showClientTicketBanner(
                                context = applicationContext,
                                ticket = stale,
                                customTitleSuffix = "Не взята > ${settings.staleTicketReminderMinutes} мин!"
                            )
                        }
                    }
                }
            }

            val allCurrent = repository.tickets.value
            val newCount = allCurrent.count { it.isClientTicket && it.stage == TicketStage.NEW }
            val inProgressCount = allCurrent.count { it.isClientTicket && it.stage == TicketStage.IN_PROGRESS }
            val postponedCount = repository.postponedTickets.value.size
            val timeStr = Instant.ofEpochMilli(nowMillis)
                .atZone(ZoneId.systemDefault())
                .toLocalTime()
                .format(timeFormatter)

            val modePrefix = if (schedule.isAllowedNow) "" else "Тихий режим РБ · "
            val postponedSuffix = if (postponedCount > 0) " · Отложено: $postponedCount" else ""
            updateForegroundText(
                "${modePrefix}Новые: $newCount · В работе: $inProgressCount$postponedSuffix · $timeStr"
            )
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

    private suspend fun pollAdminChatSafely() {
        runCatching {
            val settings = repository.appSettings.value
            if (!settings.isConfigured) return
            val filter = repository.filterSettings.value
            val cachedRoomKey = repository.resolvedChatRoomKey.value
            val roomKey = apiClient.resolveOrCreateAdminChatRoom(
                settings = settings,
                filterSettings = filter,
                cachedRoomKey = cachedRoomKey
            ).getOrNull() ?: return

            val snapshot = apiClient.fetchAdminChatRoomSnapshot(roomKey, settings).getOrNull() ?: return
            val newColleagueMessages = repository.recordPolledChatMessages(
                roomIssueKey = roomKey,
                messages = snapshot.messages,
                pinnedNote = snapshot.pinnedNote
            )
            if (settings.adminChatNotificationsEnabled) {
                for (msg in newColleagueMessages) {
                    NotificationHelper.showAdminChatMessageNotification(applicationContext, msg)
                }
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
        runCatching {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            networkCallback?.let { cm?.unregisterNetworkCallback(it) }
        }
        networkCallback = null
        repository.updateSyncStatus { it.copy(isServiceRunning = false, isSyncing = false) }
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START_OR_RESTART = "com.helpdesk.jiraagent.action.START_OR_RESTART"
        const val ACTION_POLL_NOW = "com.helpdesk.jiraagent.action.POLL_NOW"
        const val ACTION_PROCESS_POSTPONED = "com.helpdesk.jiraagent.action.PROCESS_POSTPONED"
        const val ACTION_STOP = "com.helpdesk.jiraagent.action.STOP"

        fun syncServiceState(context: Context) {
            val repo = SettingsRepository.getInstance(context)
            val settings = repo.appSettings.value
            val hasPostponed = repo.postponedTickets.value.isNotEmpty()
            PostponeScheduler.scheduleNextAlarm(context)

            if ((settings.isConfigured && settings.pollingEnabled) || hasPostponed) {
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

        fun triggerPostponedCheck(context: Context) {
            val intent = Intent(context, JiraMonitorService::class.java).apply {
                action = ACTION_PROCESS_POSTPONED
            }
            runCatching {
                ContextCompat.startForegroundService(context, intent)
            }
        }
    }
}
