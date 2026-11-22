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
import com.helpdesk.jiraagent.data.ConfluenceSyncOutcome
import com.helpdesk.jiraagent.data.JiraApiClient
import com.helpdesk.jiraagent.data.SettingsRepository
import com.helpdesk.jiraagent.domain.BelarusWorkCalendar
import com.helpdesk.jiraagent.domain.SysadminToolboxEngine
import com.helpdesk.jiraagent.model.AdminChatCodec
import com.helpdesk.jiraagent.model.TicketStage
import com.helpdesk.jiraagent.notification.AlertSoundPlayer
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
    private var watchlistPingJob: Job? = null
    private var wikiDailySyncJob: Job? = null

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
                if (!shouldKeepServiceAlive(repository)) {
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
                    if (!shouldKeepServiceAlive(repository)) {
                        stopPollingAndService()
                    }
                }
                ensureBackgroundLoopsRunning()
                return START_STICKY
            }
            ACTION_POLL_NOW -> {
                ensureForegroundStarted("Обновление заявок…")
                serviceScope.launch {
                    PostponeScheduler.processDuePostponedTickets(applicationContext, apiClient)
                    pollOnce()
                }
                ensureBackgroundLoopsRunning()
                return START_STICKY
            }
        }

        if (!shouldKeepServiceAlive(repository)) {
            stopPollingAndService()
            return START_NOT_STICKY
        }
        ensureForegroundStarted("Ожидание заявок · мониторинг SSL/узлов…")
        ensureBackgroundLoopsRunning()
        return START_STICKY
    }

    private fun ensureBackgroundLoopsRunning() {
        if (pollingJob?.isActive != true) {
            startPollingLoop()
        }
        if (watchlistPingJob?.isActive != true) {
            startWatchlistBackgroundLoop()
        }
        if (wikiDailySyncJob?.isActive != true) {
            startWikiDailySyncLoop()
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
                if (!shouldKeepServiceAlive(repository)) {
                    stopPollingAndService()
                    break
                }
                val settings = repository.appSettings.value

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
                if (settings.stormProtectionEnabled && newClientTickets.size >= 3) {
                    NotificationHelper.showTicketStormBanner(applicationContext, newClientTickets)
                } else {
                    for (ticket in newClientTickets) {
                        NotificationHelper.showClientTicketBanner(applicationContext, ticket)
                    }
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
            var playedMentionAlert = false
            for (msg in newColleagueMessages) {
                val mentionedMe = AdminChatCodec.isUserMentioned(
                    mentionedHandles = msg.mentionedHandles,
                    displayName = repository.syncStatus.value.connectedUser,
                    usernameOrEmail = settings.usernameOrEmail
                )
                if (mentionedMe || settings.adminChatNotificationsEnabled) {
                    NotificationHelper.showAdminChatMessageNotification(applicationContext, msg)
                }
                if (mentionedMe && !playedMentionAlert) {
                    playedMentionAlert = true
                    AlertSoundPlayer.playTicketAlert(applicationContext, settings)
                }
            }
        }
    }

    /**
     * Background loop that pings all entered `SSL / Узлы` hosts every 5 minutes
     * and posts a notification with the host's name whenever a host is offline.
     */
    private fun startWatchlistBackgroundLoop() {
        watchlistPingJob?.cancel()
        watchlistPingJob = serviceScope.launch {
            // Brief initial warmup so service startup completes first
            delay(5_000L)
            while (isActive) {
                runCatching {
                    val hosts = repository.watchlistHosts.value
                    val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                    val hasAnyNetwork = cm == null || cm.activeNetwork != null
                    if (hosts.isNotEmpty() && hasAnyNetwork) {
                        val labels = repository.watchlistLabels.value
                        val statuses = SysadminToolboxEngine.checkWatchlistHostsWithOfflineRetry(hosts)
                        repository.updateWatchlistStatuses(statuses)
                        var playedOfflineSound = false
                        for (st in statuses) {
                            val hostKey = SysadminToolboxEngine.normalizeHostKey(st.host)
                            if (!st.isAlive) {
                                val shortName = SysadminToolboxEngine.resolveWatchlistShortTitle(st.host, labels)
                                val fullName = SysadminToolboxEngine.resolveWatchlistDisplayName(st.host, labels)
                                NotificationHelper.showHostOfflineNotification(
                                    context = applicationContext,
                                    hostKey = hostKey,
                                    shortTitle = shortName,
                                    fullDisplayName = fullName
                                )
                                if (!playedOfflineSound && BelarusWorkCalendar.isWorkingTimeNow(repository.appSettings.value)) {
                                    playedOfflineSound = true
                                    AlertSoundPlayer.playTicketAlert(applicationContext, repository.appSettings.value)
                                }
                            } else {
                                NotificationHelper.cancelHostOfflineNotification(applicationContext, hostKey)
                            }
                        }
                    }
                }
                delay(SysadminToolboxEngine.WATCHLIST_BACKGROUND_PING_INTERVAL_MS)
            }
        }
    }

    /**
     * Background loop that synchronizes the offline Confluence `Читалка` database once per 24 hours.
     * If `w.csl.by` is reachable and rejects account authentication, wipes the local database
     * and all records created in the program.
     */
    private fun startWikiDailySyncLoop() {
        wikiDailySyncJob?.cancel()
        wikiDailySyncJob = serviceScope.launch {
            delay(15_000L)
            while (isActive) {
                runCatching {
                    val settings = repository.appSettings.value
                    if (settings.isConfluenceReady && repository.wikiOfflineStore.isDailySyncDue()) {
                        val outcome = repository.wikiOfflineStore.syncFromServer(
                            wikiClient = repository.wikiClient,
                            settings = settings
                        )
                        if (outcome is ConfluenceSyncOutcome.AccountRevokedWipeTriggered) {
                            repository.wipeAllProgramDataOnAccountDisabled(outcome.reason)
                            NotificationHelper.showAccountWipedNotification(applicationContext, outcome.reason)
                        }
                    }
                }
                // Re-check every 30 minutes whether the 24-hour sync window is due
                delay(30L * 60_000L)
            }
        }
    }

    private fun stopPollingAndService() {
        pollingJob?.cancel()
        watchlistPingJob?.cancel()
        wikiDailySyncJob?.cancel()
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

        private fun shouldKeepServiceAlive(repo: SettingsRepository): Boolean {
            val settings = repo.appSettings.value
            val hasPostponed = repo.postponedTickets.value.isNotEmpty()
            val hasWatchlistHosts = repo.watchlistHosts.value.isNotEmpty()
            return (settings.isConfigured && settings.pollingEnabled) ||
                hasPostponed ||
                hasWatchlistHosts ||
                settings.isConfluenceReady
        }

        fun syncServiceState(context: Context) {
            val repo = SettingsRepository.getInstance(context)
            PostponeScheduler.scheduleNextAlarm(context)

            if (shouldKeepServiceAlive(repo)) {
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
