package com.helpdesk.jiraagent.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.helpdesk.jiraagent.data.ConfluenceSyncOutcome
import com.helpdesk.jiraagent.data.JiraApiClient
import com.helpdesk.jiraagent.data.JiraApiException
import com.helpdesk.jiraagent.data.SettingsRepository
import com.helpdesk.jiraagent.data.ZabbixApiClient
import com.helpdesk.jiraagent.domain.BelarusWorkCalendar
import com.helpdesk.jiraagent.domain.SysadminToolboxEngine
import com.helpdesk.jiraagent.model.AdminChatCodec
import com.helpdesk.jiraagent.model.AppSettings
import com.helpdesk.jiraagent.model.TicketStage
import com.helpdesk.jiraagent.notification.AlertSoundPlayer
import com.helpdesk.jiraagent.notification.NotificationHelper
import com.helpdesk.jiraagent.update.AppUpdateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

class JiraMonitorService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollingJob: Job? = null
    private var watchlistPingJob: Job? = null
    private var wikiDailySyncJob: Job? = null
    private val pollWakeupChannel = Channel<Unit>(Channel.CONFLATED)
    private val jiraPollMutex = Mutex()
    private val zabbixPollMutex = Mutex()

    private lateinit var repository: SettingsRepository
    private val apiClient: JiraApiClient
        get() = repository.jiraApiClient

    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private var lastUpdateCheckEpochMillis: Long = 0L
    private var lastAdminChatPollEpochMillis: Long = 0L
    private var lastNotifiedUpdateTag: String? = null
    private var lastForegroundCoreKey: String? = null
    private var lastForegroundUpdatedAtMs: Long = 0L
    private var consecutiveJiraNetworkErrors: Int = 0
    private var consecutiveZabbixErrors: Int = 0
    @Volatile
    private var lastNetworkHandle: Long = 0L
    @Volatile
    private var skippedPollWhileOffline: Boolean = false
    private val notifiedOfflineHostKeys: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        repository = SettingsRepository.getInstance(applicationContext)
        PostponeScheduler.scheduleNextAlarm(applicationContext)
        registerOfflineSyncNetworkCallback()
    }

    private fun hasActiveInternetConnection(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        val active = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(active) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun registerOfflineSyncNetworkCallback() {
        runCatching {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val prevHandle = lastNetworkHandle
                    lastNetworkHandle = network.networkHandle
                    if (prevHandle != 0L && prevHandle != network.networkHandle) {
                        // Network interface changed (e.g. Wi-Fi <-> 4G+ or Dual-SIM switch): flush stale pooled sockets!
                        JiraApiClient.evictAllConnections()
                        ZabbixApiClient.evictAllConnections()
                    }
                    PostponeScheduler.clearRetryBackoff()
                    if (repository.offlineQueuedActions.value.isNotEmpty() ||
                        repository.postponedTickets.value.any { it.scheduledAtMillis <= System.currentTimeMillis() }
                    ) {
                        serviceScope.launch {
                            OfflineQueueProcessor.flushPending(applicationContext, apiClient)
                            PostponeScheduler.processDuePostponedTickets(applicationContext, apiClient)
                        }
                    }
                    if (skippedPollWhileOffline || repository.syncStatus.value.lastError != null) {
                        skippedPollWhileOffline = false
                        pollWakeupChannel.trySend(Unit)
                    }
                }

                override fun onLost(network: Network) {
                    JiraApiClient.evictAllConnections()
                    ZabbixApiClient.evictAllConnections()
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
                ensureForegroundStarted("Обновление заявок и Zabbix…")
                serviceScope.launch {
                    PostponeScheduler.processDuePostponedTickets(applicationContext, apiClient)
                    if (repository.appSettings.value.isConfigured) {
                        pollOnce(manual = true)
                    }
                    if (repository.appSettings.value.isZabbixConfigured && repository.appSettings.value.zabbixEnabled) {
                        pollZabbixSafely(manual = true)
                    }
                }
                ensureBackgroundLoopsRunning()
                return START_STICKY
            }
        }

        if (!shouldKeepServiceAlive(repository)) {
            stopPollingAndService()
            return START_NOT_STICKY
        }
        if (pollingJob?.isActive != true) {
            ensureForegroundStarted("Ожидание заявок · мониторинг SSL/узлов…")
        } else {
            // Wake sleeping loop immediately when user updates polling / off-hours intervals in Settings
            pollWakeupChannel.trySend(Unit)
        }
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

    private fun updateForegroundText(statusText: String, coreStateKey: String = statusText) {
        if (!NotificationHelper.hasNotificationPermission(this)) return
        val now = System.currentTimeMillis()
        // Avoid waking SystemUI via Binder IPC every 30s when counts/mode haven't changed
        if (coreStateKey == lastForegroundCoreKey && (now - lastForegroundUpdatedAtMs) < 15L * 60_000L) {
            return
        }
        lastForegroundCoreKey = coreStateKey
        lastForegroundUpdatedAtMs = now
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
                val nowInstant = Instant.now()
                val (scheduledWaitMs, isOffHoursPaused) = BelarusWorkCalendar.computeEffectivePollSchedule(
                    settings = settings,
                    nowInstant = nowInstant
                )
                val isWorkingHours = BelarusWorkCalendar.isCalendarWorkingHoursNow(settings, nowInstant)

                if (!hasActiveInternetConnection()) {
                    skippedPollWhileOffline = true
                    updateForegroundText(
                        statusText = "Ожидание сети · офлайн-режим энергосбережения",
                        coreStateKey = "offline_no_net"
                    )
                } else {
                    skippedPollWhileOffline = false
                    // Always process due postponed tickets (with exponential backoff if errored)
                    PostponeScheduler.processDuePostponedTickets(applicationContext, apiClient)

                    val jiraPollActive = settings.isConfigured && settings.pollingEnabled && !isOffHoursPaused
                    val zabbixPollActive = settings.isZabbixConfigured && settings.zabbixEnabled && settings.pollingEnabled && !isOffHoursPaused

                    if (jiraPollActive) {
                        pollOnce()

                        // Throttle background Admin Chat check to >= 2 min in working hours (and along with off-hours sync)
                        val nowMs = System.currentTimeMillis()
                        val minChatGapMs = if (isWorkingHours) 120_000L else 300_000L
                        if (nowMs - lastAdminChatPollEpochMillis >= minChatGapMs) {
                            lastAdminChatPollEpochMillis = nowMs
                            pollAdminChatSafely()
                        }
                    }

                    if (zabbixPollActive) {
                        pollZabbixSafely()
                    }

                    if (isOffHoursPaused) {
                        val label = AppSettings.formatOffHoursIntervalLabel(settings.normalizedOffHoursPollMinutes)
                        updateForegroundText(
                            statusText = "Нерабочее время РБ · $label",
                            coreStateKey = "offhours_paused_${settings.normalizedOffHoursPollMinutes}"
                        )
                    }

                    // Check GitHub Releases only during working hours (or at most every 4 hours) to save radio wakeups
                    if (settings.autoCheckUpdates) {
                        val now = System.currentTimeMillis()
                        val updateCheckGapMs = if (isWorkingHours) 2L * 3600_000L else 6L * 3600_000L
                        if (now - lastUpdateCheckEpochMillis >= updateCheckGapMs) {
                            lastUpdateCheckEpochMillis = now
                            AppUpdateManager.checkForUpdate().onSuccess { update ->
                                if (update != null && update.tagName != lastNotifiedUpdateTag) {
                                    lastNotifiedUpdateTag = update.tagName
                                    NotificationHelper.showAppUpdateNotification(applicationContext, update)
                                }
                            }
                        }
                    }
                }

                // Re-read latest settings in case user adjusted interval while poll was running
                val latestSettings = repository.appSettings.value
                val (baseWaitMs, _) = BelarusWorkCalendar.computeEffectivePollSchedule(
                    settings = latestSettings,
                    nowInstant = Instant.now()
                )
                val nextPostponedTrigger = PostponeScheduler.nextEffectiveTriggerMillis(applicationContext)
                val waitMs = if (nextPostponedTrigger != null) {
                    (nextPostponedTrigger - System.currentTimeMillis()).coerceIn(5_000L, baseWaitMs)
                } else {
                    baseWaitMs
                }

                // Sleep cleanly; wakes immediately if user edits settings or network comes back after offline skip
                withTimeoutOrNull(waitMs) {
                    pollWakeupChannel.receive()
                }
            }
        }
    }

    private suspend fun pollOnce(manual: Boolean = false) = jiraPollMutex.withLock {
        val settings = repository.appSettings.value
        if (!settings.isConfigured) return@withLock
        val filter = repository.filterSettings.value

        repository.updateSyncStatus { it.copy(isSyncing = true) }

        if (repository.offlineQueuedActions.value.isNotEmpty()) {
            runCatching { OfflineQueueProcessor.flushPending(applicationContext, apiClient) }
        }

        val result = apiClient.fetchRecentTickets(settings, filter)
        result.onSuccess { tickets ->
            consecutiveJiraNetworkErrors = 0
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

            val isOffHours = !BelarusWorkCalendar.isCalendarWorkingHoursNow(settings, Instant.ofEpochMilli(nowMillis))
            val offHoursBadge = if (isOffHours) {
                "Эко (${AppSettings.formatOffHoursIntervalLabel(settings.normalizedOffHoursPollMinutes).lowercase()}) · "
            } else if (!schedule.isAllowedNow) {
                "Тихий режим РБ · "
            } else {
                ""
            }
            val postponedSuffix = if (postponedCount > 0) " · Отложено: $postponedCount" else ""
            val coreKey = "${isOffHours}_${schedule.isAllowedNow}_${newCount}_${inProgressCount}_$postponedCount"
            updateForegroundText(
                statusText = "${offHoursBadge}Новые: $newCount · В работе: $inProgressCount$postponedSuffix · $timeStr",
                coreStateKey = coreKey
            )
            repository.updateSyncStatus {
                it.copy(
                    isSyncing = false,
                    lastSyncEpochMillis = nowMillis,
                    lastError = null
                )
            }
        }.onFailure { err ->
            val isTransientNet = (err as? JiraApiException)?.isNetworkError == true
            if (isTransientNet) {
                consecutiveJiraNetworkErrors++
            }
            val hasCachedTickets = repository.tickets.value.isNotEmpty()
            // Avoid flashing scary red banner on a single transient cellular handoff when cached queue is fresh
            if (!manual && isTransientNet && consecutiveJiraNetworkErrors < 2 && hasCachedTickets) {
                repository.updateSyncStatus { it.copy(isSyncing = false) }
            } else {
                val msg = err.message ?: "Ошибка подключения к Jira"
                updateForegroundText(
                    statusText = "Ошибка связи: $msg",
                    coreStateKey = "err_$msg"
                )
                repository.updateSyncStatus {
                    it.copy(
                        isSyncing = false,
                        lastError = msg
                    )
                }
            }
        }
    }

    private suspend fun pollZabbixSafely(manual: Boolean = false) = zabbixPollMutex.withLock {
        runCatching {
            val settings = repository.appSettings.value
            if (!settings.isZabbixConfigured || !settings.zabbixEnabled) return@withLock

            repository.zabbixClient.fetchSnapshot(settings)
                .onSuccess { snapshot ->
                    consecutiveZabbixErrors = 0
                    val isNotifWindowOpen = BelarusWorkCalendar.isWorkingTimeNow(settings)
                    val newAlerts = repository.processPolledZabbixSnapshotAndFindNewAlerts(
                        snapshot = snapshot,
                        isNotificationWindowOpen = isNotifWindowOpen
                    )
                    if (newAlerts.isNotEmpty() && isNotifWindowOpen) {
                        if (settings.stormProtectionEnabled && newAlerts.size > 3) {
                            NotificationHelper.showZabbixStormBanner(applicationContext, newAlerts, playSound = true)
                        } else {
                            for (prob in newAlerts) {
                                NotificationHelper.showZabbixAlertBanner(applicationContext, prob)
                            }
                        }
                    }
                    if (!settings.isConfigured) {
                        val timeStr = DateTimeFormatter.ofPattern("HH:mm")
                            .withZone(ZoneId.systemDefault())
                            .format(Instant.now())
                        val dismissed = repository.dismissedZabbixKeys.value
                        val muted = repository.currentMutedZabbixKeySet()
                        val minSev = settings.normalizedZabbixMinSeverity
                        val critCount = snapshot.problems.count {
                            it.severityLevel >= minSev && !it.isHiddenFromActiveFeed(dismissed, muted)
                        }
                        updateForegroundText(
                            statusText = "Zabbix активен · Disaster: $critCount · $timeStr",
                            coreStateKey = "zbx_only_$critCount"
                        )
                    }
                }
                .onFailure { err ->
                    consecutiveZabbixErrors++
                    if (manual || consecutiveZabbixErrors >= 2 || repository.zabbixSnapshot.value == null) {
                        repository.updateZabbixError(err.message ?: "Ошибка подключения к Zabbix")
                    }
                }
        }
    }

    private suspend fun pollAdminChatSafely() {
        runCatching {
            val settings = repository.appSettings.value
            if (!settings.isConfigured || settings.adminChatIssueKey.isBlank()) return
            val filter = repository.filterSettings.value
            val cachedRoomKey = repository.resolvedChatRoomKey.value
            val roomKey = apiClient.resolveOrCreateAdminChatRoom(
                settings = settings,
                filterSettings = filter,
                cachedRoomKey = cachedRoomKey
            ).getOrNull() ?: return
            if (roomKey != cachedRoomKey) {
                repository.setResolvedChatRoomKey(roomKey)
            }

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
            delay(5_000L)
            while (isActive) {
                // Suspend with 0 CPU/battery wakeups if the watchlist is empty
                val hosts = repository.watchlistHosts.first { it.isNotEmpty() }
                runCatching {
                    if (hasActiveInternetConnection()) {
                        val labels = repository.watchlistLabels.value
                        val statuses = SysadminToolboxEngine.checkWatchlistHostsWithOfflineRetry(
                            hosts = hosts,
                            lightweightBackground = true
                        )
                        repository.updateWatchlistStatuses(statuses)
                        val activeKeys = statuses.map { SysadminToolboxEngine.normalizeHostKey(it.host) }.toSet()
                        notifiedOfflineHostKeys.retainAll(activeKeys)

                        var playedOfflineSound = false
                        for (st in statuses) {
                            val hostKey = SysadminToolboxEngine.normalizeHostKey(st.host)
                            if (!st.isAlive) {
                                val newlyOffline = notifiedOfflineHostKeys.add(hostKey)
                                if (newlyOffline) {
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
                                }
                            } else if (notifiedOfflineHostKeys.remove(hostKey)) {
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
     * Sleeps for the exact remaining window (bounded to 1..6 hours) to avoid unnecessary wakeups.
     */
    private fun startWikiDailySyncLoop() {
        wikiDailySyncJob?.cancel()
        wikiDailySyncJob = serviceScope.launch {
            delay(20_000L)
            while (isActive) {
                var nextSleepMs = 2L * 3600_000L
                runCatching {
                    val settings = repository.appSettings.value
                    val store = repository.wikiOfflineStore
                    if (settings.isConfluenceReady && hasActiveInternetConnection()) {
                        if (store.isDailySyncDue()) {
                            val outcome = store.syncFromServer(
                                wikiClient = repository.wikiClient,
                                settings = settings
                            )
                            if (outcome is ConfluenceSyncOutcome.AccountRevokedWipeTriggered) {
                                repository.wipeAllProgramDataOnAccountDisabled(outcome.reason)
                                NotificationHelper.showAccountWipedNotification(applicationContext, outcome.reason)
                            }
                        }
                        val snap = store.getSnapshot()
                        if (snap != null && snap.pagesById.isNotEmpty()) {
                            val remainingMs = (snap.syncedAtEpochMs + 24L * 3600_000L) - System.currentTimeMillis()
                            nextSleepMs = remainingMs.coerceIn(60L * 60_000L, 6L * 3600_000L)
                        }
                    }
                }
                delay(nextSleepMs)
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
                (settings.isZabbixConfigured && settings.zabbixEnabled && settings.pollingEnabled) ||
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
            val s = repo.appSettings.value
            if (!s.isConfigured && !(s.isZabbixConfigured && s.zabbixEnabled)) return
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
