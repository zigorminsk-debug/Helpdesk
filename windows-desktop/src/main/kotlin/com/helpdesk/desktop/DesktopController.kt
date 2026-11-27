package com.helpdesk.desktop

import com.helpdesk.jiraagent.data.ConfluenceAccessCheckResult
import com.helpdesk.jiraagent.data.ConfluenceAttachment
import com.helpdesk.jiraagent.data.ConfluenceOfflineSnapshot
import com.helpdesk.jiraagent.data.ConfluenceOfflineStore
import com.helpdesk.jiraagent.data.ConfluencePageDetail
import com.helpdesk.jiraagent.data.ConfluencePageSummary
import com.helpdesk.jiraagent.data.ConfluenceSpace
import com.helpdesk.jiraagent.data.ConfluenceSyncOutcome
import com.helpdesk.jiraagent.data.ConfluenceWikiClient
import com.helpdesk.jiraagent.data.HabrArticleDetail
import com.helpdesk.jiraagent.data.HabrFeedCategory
import com.helpdesk.jiraagent.data.HabrNewsItem
import com.helpdesk.jiraagent.data.HabrNewsRepository
import com.helpdesk.jiraagent.data.JiraApiClient
import com.helpdesk.jiraagent.data.JiraAssignableUser
import com.helpdesk.jiraagent.data.JiraTransitionOption
import com.helpdesk.jiraagent.data.TicketWorkflowAction
import com.helpdesk.jiraagent.data.ZabbixApiClient
import com.helpdesk.jiraagent.domain.BelarusWorkCalendar
import com.helpdesk.jiraagent.domain.EngineerNameResolver
import com.helpdesk.jiraagent.domain.HostDiagnosticReport
import com.helpdesk.jiraagent.domain.NetworkDiagnosticsTool
import com.helpdesk.jiraagent.domain.PeriodAnalyticsSummary
import com.helpdesk.jiraagent.domain.ShiftAnalyticsCalculator
import com.helpdesk.jiraagent.domain.SysadminToolboxEngine
import com.helpdesk.jiraagent.domain.TicketAgeFilter
import com.helpdesk.jiraagent.domain.TicketStopwatchEntry
import com.helpdesk.jiraagent.domain.TicketStopwatchTracker
import com.helpdesk.jiraagent.domain.WatchlistHostStatus
import com.helpdesk.jiraagent.model.AdminChatCodec
import com.helpdesk.jiraagent.model.AdminChatMessage
import com.helpdesk.jiraagent.model.AppSettings
import com.helpdesk.jiraagent.model.ClientDirectoryCodec
import com.helpdesk.jiraagent.model.ClientDirectoryEntry
import com.helpdesk.jiraagent.model.ClientTechPassport
import com.helpdesk.jiraagent.model.ClientTechPassportCodec
import com.helpdesk.jiraagent.model.FilterSettings
import com.helpdesk.jiraagent.model.HelpdeskTicket
import com.helpdesk.jiraagent.model.PostponedTicket
import com.helpdesk.jiraagent.model.RepairEquipmentCodec
import com.helpdesk.jiraagent.model.RepairEquipmentItem
import com.helpdesk.jiraagent.model.RepairStatus
import com.helpdesk.jiraagent.model.TicketStage
import com.helpdesk.jiraagent.model.TtsReadoutMode
import com.helpdesk.jiraagent.model.ZabbixDashboardSnapshot
import com.helpdesk.jiraagent.model.ZabbixMutedRule
import com.helpdesk.jiraagent.model.ZabbixProblem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap

enum class DesktopMainTab(val title: String) {
    TICKETS("Заявки"),
    ZABBIX("Zabbix"),
    REPAIR("В ремонт"),
    WIKI("Wiki"),
    CHAT("Чат"),
    FILTER("Фильтр"),
    SETTINGS("Настройки")
}

enum class TicketQueueTab(val title: String) {
    NEW("Новые"),
    IN_PROGRESS("В работе"),
    CLOSED("Закрытые"),
    SERVICE("Сервисные")
}

data class DesktopToastAlert(
    val id: String,
    val title: String,
    val subtitle: String,
    val ticketKey: String?,
    val isStormSummary: Boolean = false,
    val createdAtMillis: Long = System.currentTimeMillis()
)

data class PendingTransitionPrompt(
    val ticketKey: String,
    val action: TicketWorkflowAction?,
    val isPostpone: Boolean = false,
    val postponeAtMillis: Long = 0L,
    val postponeDisplay: String = "",
    val commentText: String? = null,
    val transitions: List<JiraTransitionOption> = emptyList()
)

data class DesktopUiState(
    val activeTab: DesktopMainTab = DesktopMainTab.TICKETS,
    val ticketQueueTab: TicketQueueTab = TicketQueueTab.NEW,
    val ticketAgeFilter: TicketAgeFilter = TicketAgeFilter.FRESH_TODAY,
    val showHabrFeedPanel: Boolean = false,
    val resetTicketsViewStamp: Long = 0L,
    val settings: AppSettings = AppSettings(),
    val filterSettings: FilterSettings = FilterSettings(),
    val darkTheme: Boolean = false,
    val minimizeToTray: Boolean = true,
    val respectWorkScheduleOnDesktop: Boolean = false,
    val tickets: List<HelpdeskTicket> = emptyList(),
    val postponedTickets: List<PostponedTicket> = emptyList(),
    val selectedTicketKey: String? = null,
    val isRefreshing: Boolean = false,
    val isPerformingAction: Boolean = false,
    val isCleaningSystemRooms: Boolean = false,
    val statusBanner: String? = null,
    val errorBanner: String? = null,
    val connectedUserName: String? = null,
    val lastSyncDisplay: String = "ещё не синхронизировано",
    val pendingTransitionPrompt: PendingTransitionPrompt? = null,

    // Client history, S/N search, Knowledge Archive & Stopwatch
    val clientHistoryForKey: String? = null,
    val clientHistoryTickets: List<HelpdeskTicket> = emptyList(),
    val isLoadingClientHistory: Boolean = false,
    val clientHistoryFailed: Boolean = false,
    val organizationHistories: Map<String, com.helpdesk.jiraagent.domain.OrganizationHistory> = emptyMap(),
    val serialSearchResults: List<HelpdeskTicket> = emptyList(),
    val isSearchingSerial: Boolean = false,
    val knowledgeArchiveResults: List<HelpdeskTicket> = emptyList(),
    val isSearchingKnowledge: Boolean = false,
    val kpiSampleTickets: List<HelpdeskTicket> = emptyList(),
    val isLoadingKpi: Boolean = false,
    val clientPassports: Map<String, ClientTechPassport> = emptyMap(),
    val ticketStopwatches: Map<String, TicketStopwatchEntry> = emptyMap(),
    val diagnosticReport: HostDiagnosticReport? = null,
    val isRunningDiagnostics: Boolean = false,
    val watchlistHosts: List<String> = SysadminToolboxEngine.DEFAULT_WATCHLIST_HOSTS,
    val watchlistLabels: Map<String, String> = emptyMap(),
    val watchlistStatuses: List<WatchlistHostStatus> = emptyList(),
    val isCheckingWatchlist: Boolean = false,

    // Confluence Wiki (w.csl.by offline-first + daily sync)
    val wikiSpaces: List<ConfluenceSpace> = emptyList(),
    val selectedWikiSpaceKey: String? = null,
    val wikiPages: List<ConfluencePageSummary> = emptyList(),
    val wikiChildPagesByParent: Map<String, List<ConfluencePageSummary>> = emptyMap(),
    val wikiExpandedPageIds: Set<String> = emptySet(),
    val wikiSearchQuery: String = "",
    val wikiSearchResults: List<ConfluencePageSummary>? = null,
    val selectedWikiPage: ConfluencePageDetail? = null,
    val wikiPageHistoryStack: List<ConfluencePageDetail> = emptyList(),
    val isLoadingWikiList: Boolean = false,
    val isLoadingWikiPage: Boolean = false,
    val isSyncingWikiOfflineDb: Boolean = false,
    val wikiSyncProgressText: String? = null,
    val wikiSyncProgressFraction: Float = 0f,
    val wikiOfflineSyncedAtLabel: String = "",
    val wikiOfflinePagesCount: Int = 0,
    val wikiError: String? = null,
    val openWatchlistToolboxStamp: Long = 0L,

    // Empty state GIF (#1 Roy & #7 Moss) + Habr.com RSS & built-in article reader
    val emptyGifIndex: Int = 0,
    val dismissedGifDateIso: String = "",
    val habrCategory: HabrFeedCategory = HabrFeedCategory.SYSADMIN,
    val habrItems: List<HabrNewsItem> = emptyList(),
    val isLoadingHabr: Boolean = false,
    val selectedHabrItem: HabrNewsItem? = null,
    val habrArticleDetail: HabrArticleDetail? = null,
    val isLoadingHabrArticle: Boolean = false,
    val habrArticleError: String? = null,

    // Workshop «В ремонт» + Helpdesk client directory
    val repairItems: List<RepairEquipmentItem> = emptyList(),
    val clientsDirectory: List<ClientDirectoryEntry> = emptyList(),
    val repairRoomKey: String = "",
    val isSyncingRepair: Boolean = false,
    val isLoadingClientsDirectory: Boolean = false,
    val pendingRepairPrefillTicket: HelpdeskTicket? = null,

    // Internal Admin «Чат»
    val chatRoomKey: String = "",
    val chatMessages: List<AdminChatMessage> = emptyList(),
    val chatPinnedNote: String = "",
    val unreadChatCount: Int = 0,
    val isSyncingChat: Boolean = false,
    val pendingChatDraft: String = "",

    // Zabbix (z.csl.by) Monitoring
    val zabbixSnapshot: ZabbixDashboardSnapshot? = null,
    val dismissedZabbixKeys: Set<String> = emptySet(),
    val mutedZabbixRules: List<ZabbixMutedRule> = emptyList(),
    val zabbixError: String? = null,
    val isLoadingZabbix: Boolean = false,

    // Desktop popup alert & GitHub self-update
    val activePopupAlert: DesktopToastAlert? = null,
    val updateInfo: DesktopReleaseUpdate? = null,
    val isCheckingUpdate: Boolean = false,
    val updateDownloadProgress: Int? = null
)

class DesktopController(
    val currentVersion: String = DesktopBuildInfo.versionName
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val apiClient = JiraApiClient()
    val wikiClient = ConfluenceWikiClient()
    val zabbixClient = ZabbixApiClient()
    private val storage = DesktopStorage()
    private val wikiOfflineStore = ConfluenceOfflineStore(storage.offlineWikiDir())
    private val jiraAttachmentMemoryCache = ConcurrentHashMap<String, ByteArray>()

    private val persisted = storage.loadState()
    private val notifiedTicketKeys = persisted.seenTicketKeys.toMutableSet()
    private val deferredTicketKeys = persisted.deferredAlertKeys.toMutableSet()
    private val notifiedZabbixKeys = persisted.seenZabbixAlertKeys.toMutableSet()
    private val deferredZabbixKeys = persisted.deferredZabbixAlertKeys.toMutableSet()
    private val lastStaleReminderByKey = mutableMapOf<String, Long>()
    val sessionStartedEpochMs: Long = System.currentTimeMillis()
    private var baselineInitialized: Boolean = persisted.baselineInitialized
    private var zabbixBaselineInitialized: Boolean = persisted.zabbixBaselineInitialized
    private var seenChatIds: Set<String> = persisted.cachedChatMessages.map { it.id }.toSet()

    private val _state = MutableStateFlow(
        DesktopUiState(
            settings = persisted.appSettings,
            filterSettings = persisted.filterSettings,
            darkTheme = persisted.darkTheme,
            minimizeToTray = persisted.minimizeToTray,
            respectWorkScheduleOnDesktop = persisted.respectWorkScheduleOnDesktop,
            dismissedGifDateIso = persisted.dismissedGifDateIso,
            connectedUserName = persisted.connectedUserName,
            tickets = persisted.cachedTickets,
            postponedTickets = persisted.postponedTickets,
            clientPassports = persisted.clientPassports,
            watchlistHosts = persisted.watchlistHosts,
            watchlistLabels = persisted.watchlistLabels,
            ticketStopwatches = persisted.ticketStopwatches,
            repairItems = persisted.repairItems,
            clientsDirectory = persisted.clientDirectory,
            repairRoomKey = persisted.resolvedRepairRoomKey,
            chatRoomKey = persisted.resolvedChatRoomKey,
            chatMessages = persisted.cachedChatMessages,
            chatPinnedNote = persisted.pinnedShiftNote,
            zabbixSnapshot = persisted.cachedZabbixSnapshot,
            dismissedZabbixKeys = persisted.dismissedZabbixAlertKeys,
            mutedZabbixRules = persisted.mutedZabbixRules
        )
    )
    val state: StateFlow<DesktopUiState> = _state.asStateFlow()

    private var pollingJob: Job? = null
    private var watchlistBackgroundJob: Job? = null
    private var wikiDailySyncJob: Job? = null
    private var systemRoomsAutoCleanedV70: Boolean = persisted.systemRoomsAutoCleanedV70
    private var onBringWindowToFront: (() -> Unit)? = null
    private var onSystemTrayNotify: ((title: String, message: String) -> Unit)? = null

    init {
        loadHabrFeed(force = false)
        restartPollingLoop()
        startWatchlistBackgroundLoop()
        startWikiDailySyncLoop()
        if (persisted.appSettings.isConfigured) {
            scope.launch {
                if (!persisted.appSettings.pollingEnabled) {
                    refreshTickets(silent = true, isInitialBoot = true)
                }
                if (persisted.clientDirectory.isEmpty()) {
                    delay(2_000L)
                    refreshClientsDirectory(silent = true)
                }
                if (persisted.appSettings.adminChatIssueKey.isNotBlank()) {
                    delay(1_500L)
                    syncRepairRegistry(silent = true)
                    delay(1_500L)
                    refreshChat(silent = true)
                }
                if (!systemRoomsAutoCleanedV70) {
                    delay(2_500L)
                    cleanupSystemRoomsInJira(silentIfZero = true)
                }
            }
        }
        if (persisted.appSettings.isZabbixConfigured &&
            persisted.appSettings.zabbixEnabled &&
            !persisted.appSettings.pollingEnabled
        ) {
            scope.launch {
                delay(1_500L)
                refreshZabbix(silent = true)
            }
        }
        if (persisted.appSettings.autoCheckUpdates) {
            scope.launch {
                delay(4_000L)
                checkUpdates(manual = false)
            }
        }
    }

    /**
     * Background loop that pings all entered `SSL / Узлы` hosts every 5 minutes
     * (even when Help-CSL is minimized to the Windows system tray) and displays
     * a popup + tray notification with the host's name if a host is offline.
     */
    private fun startWatchlistBackgroundLoop() {
        watchlistBackgroundJob?.cancel()
        watchlistBackgroundJob = scope.launch {
            val notifiedOfflineHostKeys = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
            delay(8_000L)
            while (true) {
                runCatching {
                    val cur = _state.value
                    val hosts = cur.watchlistHosts
                    if (hosts.isNotEmpty()) {
                        val labels = cur.watchlistLabels
                        val statuses = SysadminToolboxEngine.checkWatchlistHostsWithOfflineRetry(
                            hosts = hosts,
                            lightweightBackground = true
                        )
                        _state.update { it.copy(watchlistStatuses = statuses) }
                        val activeKeys = statuses.map { SysadminToolboxEngine.normalizeHostKey(it.host) }.toSet()
                        notifiedOfflineHostKeys.retainAll(activeKeys)

                        for (st in statuses) {
                            if (st.isAlive) {
                                notifiedOfflineHostKeys.remove(SysadminToolboxEngine.normalizeHostKey(st.host))
                            }
                        }

                        val newlyOfflineStatuses = statuses.filter {
                            !it.isAlive && notifiedOfflineHostKeys.add(SysadminToolboxEngine.normalizeHostKey(it.host))
                        }
                        if (newlyOfflineStatuses.isNotEmpty()) {
                            for (off in newlyOfflineStatuses) {
                                val shortName = SysadminToolboxEngine.resolveWatchlistShortTitle(off.host, labels)
                                val fullName = SysadminToolboxEngine.resolveWatchlistDisplayName(off.host, labels)
                                onSystemTrayNotify?.invoke(
                                    "🔴 Нет ответа от узла: $shortName",
                                    "Узел «$fullName» не ответил на фоновые проверки; это не доказывает его недоступность."
                                )
                            }
                            val firstOff = newlyOfflineStatuses.first()
                            val firstShort = SysadminToolboxEngine.resolveWatchlistShortTitle(firstOff.host, labels)
                            val popupSubtitle = if (newlyOfflineStatuses.size == 1) {
                                val full = SysadminToolboxEngine.resolveWatchlistDisplayName(firstOff.host, labels)
                                "Узел «$full» не ответил на фоновые проверки; это не доказывает его недоступность."
                            } else {
                                val allNames = newlyOfflineStatuses.joinToString(", ") {
                                    SysadminToolboxEngine.resolveWatchlistDisplayName(it.host, labels)
                                }
                                "Нет ответа от узлов (${newlyOfflineStatuses.size}): $allNames"
                            }
                            val popupTitle = if (newlyOfflineStatuses.size == 1) {
                                "🔴 Нет ответа от узла: $firstShort"
                            } else {
                                "🔴 Нет ответа от узлов (${newlyOfflineStatuses.size})"
                            }
                            val toast = DesktopToastAlert(
                                id = "host_offline_${System.currentTimeMillis()}",
                                title = popupTitle,
                                subtitle = popupSubtitle,
                                ticketKey = null,
                                isStormSummary = true
                            )
                            DesktopSoundPlayer.playAlert(
                                cur.settings.alertSoundPreset,
                                cur.settings.customAlertSoundUri
                            )
                            DesktopNativePopupManager.showAlert(
                                alert = toast,
                                onTakeInWork = null,
                                onOpen = {
                                    onBringWindowToFront?.invoke()
                                    _state.update {
                                        it.copy(openWatchlistToolboxStamp = System.currentTimeMillis())
                                    }
                                },
                                onDismissed = {}
                            )
                        }
                    }
                }
                delay(SysadminToolboxEngine.WATCHLIST_BACKGROUND_PING_INTERVAL_MS)
            }
        }
    }

    /**
     * Hydrates the offline Confluence database on startup and synchronizes with `w.csl.by`
     * once per 24 hours. If `w.csl.by` is reachable and rejects account authorization,
     * deletes the local database and all records created in the program.
     */
    private fun startWikiDailySyncLoop() {
        wikiDailySyncJob?.cancel()
        wikiDailySyncJob = scope.launch(Dispatchers.IO) {
            wikiOfflineStore.getSnapshot()?.let { snap ->
                applyOfflineWikiSnapshotToUi(snap)
            }
            val initialSettings = _state.value.settings
            if (initialSettings.isConfluenceReady) {
                if (wikiOfflineStore.isDailySyncDue()) {
                    syncWikiOfflineDatabase(force = false)
                } else {
                    val access = wikiClient.verifyConfluenceAccountAccess(
                        settings = initialSettings,
                        hasExistingOfflineDatabase = wikiOfflineStore.hasOfflineSnapshot()
                    )
                    if (access is ConfluenceAccessCheckResult.AccountDisabledOrUnauthorized) {
                        wipeAllProgramDataOnAccountDisabled(access.reason)
                    }
                }
            }
            while (true) {
                delay(30L * 60_000L)
                val settings = _state.value.settings
                if (settings.isConfluenceReady && wikiOfflineStore.isDailySyncDue()) {
                    syncWikiOfflineDatabase(force = false)
                }
            }
        }
    }

    private fun wipeAllProgramDataOnAccountDisabled(reason: String) {
        val cur = _state.value
        wikiOfflineStore.clearOfflineDatabase()
        wikiClient.clearSessionCache()
        jiraAttachmentMemoryCache.clear()
        wikiPageDetailCache.clear()
        notifiedTicketKeys.clear()
        deferredTicketKeys.clear()
        lastStaleReminderByKey.clear()
        baselineInitialized = false
        seenChatIds = emptySet()

        storage.wipeAllPersistedData(
            keptServerUrl = cur.settings.serverUrl,
            keptConfluenceUrl = cur.settings.confluenceUrl
        )

        val cleanedSettings = AppSettings(
            serverUrl = cur.settings.serverUrl,
            confluenceUrl = cur.settings.confluenceUrl
        )
        val wipeMsg = "🔒 Учётная запись отключена или авторизация на w.csl.by отклонена ($reason). " +
            "Локальная офлайн-база Читалки и все записи программы удалены."

        _state.value = DesktopUiState(
            activeTab = DesktopMainTab.SETTINGS,
            settings = cleanedSettings,
            darkTheme = cur.darkTheme,
            minimizeToTray = cur.minimizeToTray,
            watchlistHosts = emptyList(),
            watchlistLabels = emptyMap(),
            watchlistStatuses = emptyList(),
            errorBanner = wipeMsg,
            wikiError = wipeMsg
        )

        onSystemTrayNotify?.invoke("🔒 База и записи удалены", wipeMsg)
        DesktopNativePopupManager.showAlert(
            alert = DesktopToastAlert(
                id = "wipe_${System.currentTimeMillis()}",
                title = "🔒 Учётная запись отключена",
                subtitle = wipeMsg,
                ticketKey = null,
                isStormSummary = true
            ),
            onTakeInWork = null,
            onOpen = { onBringWindowToFront?.invoke() },
            onDismissed = {}
        )
    }

    fun bindWindowActivator(activator: () -> Unit) {
        onBringWindowToFront = activator
    }

    fun bindTrayNotifier(notifier: (title: String, message: String) -> Unit) {
        onSystemTrayNotify = notifier
    }

    fun setMinimizeToTray(enabled: Boolean) {
        _state.update { it.copy(minimizeToTray = enabled) }
        persistCurrentState()
    }

    fun setDarkTheme(enabled: Boolean) {
        _state.update { it.copy(darkTheme = enabled) }
        persistCurrentState()
    }

    fun setDarkThemePreset(preset: com.helpdesk.jiraagent.model.DarkThemePreset) {
        _state.update {
            it.copy(
                darkTheme = true,
                settings = it.settings.copy(darkThemePreset = preset)
            )
        }
        persistCurrentState()
    }

    fun toggleDarkTheme() {
        _state.update { it.copy(darkTheme = !it.darkTheme) }
        persistCurrentState()
    }

    fun showStatusMessage(message: String) {
        if (message.isBlank()) return
        _state.update { it.copy(statusBanner = message) }
    }

    fun setRespectWorkScheduleOnDesktop(enabled: Boolean) {
        _state.update { it.copy(respectWorkScheduleOnDesktop = enabled) }
        persistCurrentState()
    }

    fun isAtFreshHome(st: DesktopUiState = _state.value): Boolean {
        return st.activeTab == DesktopMainTab.TICKETS &&
            st.ticketQueueTab == TicketQueueTab.NEW &&
            st.ticketAgeFilter == TicketAgeFilter.FRESH_TODAY &&
            st.selectedTicketKey == null &&
            st.selectedHabrItem == null &&
            !st.showHabrFeedPanel
    }

    fun navigateBackToFreshHome() {
        _state.update { st ->
            st.copy(
                activeTab = DesktopMainTab.TICKETS,
                ticketQueueTab = TicketQueueTab.NEW,
                ticketAgeFilter = TicketAgeFilter.FRESH_TODAY,
                selectedTicketKey = null,
                selectedHabrItem = null,
                habrArticleDetail = null,
                isLoadingHabrArticle = false,
                habrArticleError = null,
                showHabrFeedPanel = false,
                resetTicketsViewStamp = System.currentTimeMillis()
            )
        }
    }

    fun setTicketQueueTab(tab: TicketQueueTab) {
        _state.update {
            it.copy(
                ticketQueueTab = tab,
                showHabrFeedPanel = false
            )
        }
    }

    fun setTicketAgeFilter(filter: TicketAgeFilter) {
        _state.update {
            it.copy(
                ticketAgeFilter = filter,
                showHabrFeedPanel = false
            )
        }
    }

    fun setShowHabrFeedPanel(show: Boolean) {
        _state.update { it.copy(showHabrFeedPanel = show) }
    }

    fun selectTab(tab: DesktopMainTab) {
        if (tab == DesktopMainTab.TICKETS && _state.value.activeTab == DesktopMainTab.TICKETS) {
            navigateBackToFreshHome()
            return
        }
        _state.update {
            it.copy(
                activeTab = tab,
                unreadChatCount = if (tab == DesktopMainTab.CHAT) 0 else it.unreadChatCount
            )
        }
        if (tab == DesktopMainTab.REPAIR && _state.value.settings.isConfigured) {
            syncRepairRegistry(silent = true)
            if (_state.value.clientsDirectory.isEmpty()) {
                refreshClientsDirectory(silent = true)
            }
        } else if (tab == DesktopMainTab.WIKI) {
            loadWikiInitialIfNeeded()
        } else if (tab == DesktopMainTab.CHAT && _state.value.settings.isConfigured) {
            refreshChat(silent = true)
        }
    }

    private var ticketSelectionGeneration = 0

    fun selectTicket(ticketKey: String?) {
        val generation = ++ticketSelectionGeneration
        _state.update { st ->
            val previewFromHistory = if (ticketKey != null && st.tickets.none { it.key == ticketKey }) {
                st.clientHistoryTickets.firstOrNull { it.key == ticketKey }
                    ?: st.serialSearchResults.firstOrNull { it.key == ticketKey }
                    ?: st.knowledgeArchiveResults.firstOrNull { it.key == ticketKey }
            } else {
                null
            }
            val nextTickets = if (previewFromHistory != null) {
                listOf(previewFromHistory) + st.tickets
            } else {
                st.tickets
            }
            val selectedObj = nextTickets.firstOrNull { it.key == ticketKey }
            val reporterClean = selectedObj?.reporterName?.trim().orEmpty()
            val localMatches = if (ticketKey != null && reporterClean.length >= 2) {
                nextTickets.filter {
                    !it.key.equals(ticketKey, ignoreCase = true) &&
                        it.isClientTicket &&
                        it.reporterName.equals(reporterClean, ignoreCase = true)
                }.sortedByDescending { it.createdEpochMillis }
            } else {
                emptyList()
            }
            st.copy(
                tickets = nextTickets,
                selectedTicketKey = ticketKey,
                selectedHabrItem = if (ticketKey != null) null else st.selectedHabrItem,
                habrArticleDetail = if (ticketKey != null) null else st.habrArticleDetail,
                isLoadingHabrArticle = if (ticketKey != null) false else st.isLoadingHabrArticle,
                habrArticleError = if (ticketKey != null) null else st.habrArticleError,
                diagnosticReport = null,
                clientHistoryForKey = ticketKey,
                clientHistoryTickets = localMatches,
                isLoadingClientHistory = ticketKey != null && st.settings.isConfigured,
                clientHistoryFailed = false,
                organizationHistories = emptyMap()
            )
        }
        if (ticketKey != null && _state.value.settings.isConfigured) {
            scope.launch {
                val cur = _state.value
                apiClient.fetchTicketByKey(ticketKey, cur.settings, cur.filterSettings).onSuccess { fresh ->
                    if (generation != ticketSelectionGeneration || _state.value.selectedTicketKey != ticketKey) return@onSuccess
                    replaceTicketInMemory(fresh)
                    val organizations = fresh.organizations.orEmpty()
                    _state.update { st -> if (generation == ticketSelectionGeneration && st.selectedTicketKey == fresh.key) st.copy(
                        organizationHistories = organizations.associate { "${it.fieldId}:${it.id}" to com.helpdesk.jiraagent.domain.OrganizationHistory(loading = true) }
                    ) else st }
                    organizations.forEach { organization ->
                        scope.launch {
                            val result = apiClient.fetchOrganizationHistory(organization, cur.settings, cur.filterSettings)
                            _state.update { st ->
                                if (generation == ticketSelectionGeneration && st.selectedTicketKey == fresh.key) {
                                    val key = "${organization.fieldId}:${organization.id}"
                                    st.copy(organizationHistories = st.organizationHistories + (key to com.helpdesk.jiraagent.domain.OrganizationHistory(failed = result.isFailure, tickets = result.getOrDefault(emptyList()))))
                                } else st
                            }
                        }
                    }
                    if (fresh.reporterName.isNotBlank()) {
                        apiClient.fetchClientHistory(
                            reporterName = fresh.reporterName,
                            excludeTicketKey = fresh.key,
                            settings = cur.settings,
                            filterSettings = cur.filterSettings,
                            reporterLoginOrEmail = fresh.reporterLoginOrEmail,
                            reporterIdentity = fresh.reporterIdentity
                        ).onSuccess { history ->
                            _state.update { st ->
                                if (generation == ticketSelectionGeneration && st.selectedTicketKey == fresh.key) {
                                    val localMatches = st.tickets.filter {
                                        !it.key.equals(fresh.key, ignoreCase = true) &&
                                            it.isClientTicket &&
                                            it.reporterName.equals(fresh.reporterName.trim(), ignoreCase = true)
                                    }
                                    val merged = (history + localMatches)
                                        .distinctBy { it.key.uppercase() }
                                        .sortedByDescending { it.createdEpochMillis }
                                    st.copy(
                                        clientHistoryForKey = fresh.key,
                                        clientHistoryTickets = merged,
                                        isLoadingClientHistory = false
                                    )
                                } else st
                            }
                        }.onFailure {
                            _state.update { st ->
                                if (generation == ticketSelectionGeneration && st.selectedTicketKey == fresh.key) {
                                    st.copy(isLoadingClientHistory = false, clientHistoryFailed = true)
                                } else st
                            }
                        }
                    } else {
                        _state.update { st ->
                            if (generation == ticketSelectionGeneration && st.selectedTicketKey == fresh.key) {
                                st.copy(isLoadingClientHistory = false)
                            } else st
                        }
                    }
                }.onFailure {
                    _state.update { st ->
                        if (generation == ticketSelectionGeneration && st.selectedTicketKey == ticketKey) {
                            st.copy(isLoadingClientHistory = false, clientHistoryFailed = true)
                        } else st
                    }
                }
            }
        }
    }

    fun cycleEmptyGif() {
        _state.update { it.copy(emptyGifIndex = (it.emptyGifIndex + 1) % 2) }
    }

    fun dismissMorningGifToday() {
        val today = BelarusWorkCalendar.currentMinskDateIso()
        _state.update { it.copy(dismissedGifDateIso = today) }
        persistCurrentState()
    }

    fun addQuickReplyTemplate(rawText: String) {
        val cleaned = rawText.trim()
        if (cleaned.isEmpty()) return
        _state.update { st ->
            val current = st.settings.quickReplyTemplates
            if (current.any { it.equals(cleaned, ignoreCase = true) }) {
                st
            } else {
                st.copy(settings = st.settings.copy(quickReplyTemplates = current + cleaned))
            }
        }
        persistCurrentState()
    }

    fun removeQuickReplyTemplate(templateText: String) {
        _state.update { st ->
            st.copy(
                settings = st.settings.copy(
                    quickReplyTemplates = st.settings.quickReplyTemplates.filterNot { it == templateText }
                )
            )
        }
        persistCurrentState()
    }

    fun resetQuickReplyTemplates() {
        _state.update { st ->
            st.copy(
                settings = st.settings.copy(
                    quickReplyTemplates = AppSettings.DEFAULT_QUICK_REPLY_TEMPLATES
                )
            )
        }
        persistCurrentState()
    }

    fun exportRepairsToExcelCsv() {
        scope.launch(Dispatchers.IO) {
            runCatching {
                val cur = _state.value
                val file = DesktopRepairCsvExporter.exportToCsv(
                    items = cur.repairItems,
                    connectedUserName = cur.connectedUserName,
                    currentUsernameOrEmail = cur.settings.usernameOrEmail,
                    knownTickets = cur.tickets
                )
                WindowsExternalLaunchers.openFile(file)
                file
            }.onSuccess { file ->
                _state.update {
                    it.copy(statusBanner = "📊 Журнал мастерской выгружен в Excel: ${file.name}")
                }
            }.onFailure { err ->
                _state.update {
                    it.copy(errorBanner = "Не удалось выгрузить CSV: ${err.message}")
                }
            }
        }
    }

    fun loadHabrFeed(category: HabrFeedCategory = _state.value.habrCategory, force: Boolean = true) {
        _state.update { it.copy(habrCategory = category, isLoadingHabr = true) }
        scope.launch {
            HabrNewsRepository.loadFeed(category, forceRefresh = force)
                .onSuccess { items ->
                    _state.update { it.copy(habrItems = items, isLoadingHabr = false) }
                }
                .onFailure {
                    _state.update { it.copy(isLoadingHabr = false) }
                }
        }
    }

    fun selectHabrNews(item: HabrNewsItem, forceRefresh: Boolean = false) {
        val cached = if (forceRefresh) null else HabrNewsRepository.peekCachedArticle(item.url)
        _state.update {
            it.copy(
                selectedTicketKey = null,
                selectedHabrItem = item,
                habrArticleDetail = cached,
                isLoadingHabrArticle = cached == null,
                habrArticleError = null
            )
        }
        if (cached != null) return

        scope.launch {
            HabrNewsRepository.loadArticle(item, forceRefresh = forceRefresh)
                .onSuccess { detail ->
                    _state.update { st ->
                        if (st.selectedHabrItem?.url == item.url) {
                            st.copy(
                                habrArticleDetail = detail,
                                isLoadingHabrArticle = false,
                                habrArticleError = null
                            )
                        } else st
                    }
                }
                .onFailure { err ->
                    _state.update { st ->
                        if (st.selectedHabrItem?.url == item.url) {
                            st.copy(
                                isLoadingHabrArticle = false,
                                habrArticleError = err.message ?: "Не удалось загрузить полный текст статьи"
                            )
                        } else st
                    }
                }
        }
    }

    fun closeHabrArticle() {
        _state.update {
            it.copy(
                selectedHabrItem = null,
                habrArticleDetail = null,
                isLoadingHabrArticle = false,
                habrArticleError = null
            )
        }
    }

    fun dismissBanners() {
        _state.update { it.copy(statusBanner = null, errorBanner = null) }
    }

    fun dismissPopupAlert() {
        DesktopNativePopupManager.dismiss()
        _state.update { it.copy(activePopupAlert = null) }
    }

    private fun persistCurrentState() {
        val cur = _state.value
        scope.launch(Dispatchers.IO) {
            storage.saveState(
                DesktopPersistedState(
                    appSettings = cur.settings,
                    filterSettings = cur.filterSettings,
                    darkTheme = cur.darkTheme,
                    minimizeToTray = cur.minimizeToTray,
                    respectWorkScheduleOnDesktop = cur.respectWorkScheduleOnDesktop,
                    dismissedGifDateIso = cur.dismissedGifDateIso,
                    connectedUserName = cur.connectedUserName,
                    resolvedChatRoomKey = cur.chatRoomKey,
                    resolvedRepairRoomKey = cur.repairRoomKey,
                    pinnedShiftNote = cur.chatPinnedNote,
                    baselineInitialized = baselineInitialized,
                    seenTicketKeys = synchronized(notifiedTicketKeys) { notifiedTicketKeys.toSet() },
                    deferredAlertKeys = synchronized(notifiedTicketKeys) { deferredTicketKeys.toSet() },
                    zabbixBaselineInitialized = zabbixBaselineInitialized,
                    systemRoomsAutoCleanedV70 = systemRoomsAutoCleanedV70,
                    seenZabbixAlertKeys = synchronized(notifiedZabbixKeys) { notifiedZabbixKeys.toSet() },
                    deferredZabbixAlertKeys = synchronized(notifiedZabbixKeys) { deferredZabbixKeys.toSet() },
                    dismissedZabbixAlertKeys = cur.dismissedZabbixKeys,
                    mutedZabbixRules = cur.mutedZabbixRules,
                    cachedZabbixSnapshot = cur.zabbixSnapshot,
                    clientPassports = cur.clientPassports,
                    watchlistHosts = cur.watchlistHosts,
                    watchlistLabels = cur.watchlistLabels,
                    ticketStopwatches = cur.ticketStopwatches,
                    postponedTickets = cur.postponedTickets,
                    repairItems = cur.repairItems,
                    clientDirectory = cur.clientsDirectory,
                    cachedTickets = cur.tickets,
                    cachedChatMessages = cur.chatMessages
                )
            )
        }
    }

    fun saveSettings(newSettings: AppSettings) {
        val old = _state.value.settings
        if (old.zabbixUrl.trim() != newSettings.zabbixUrl.trim() ||
            old.zabbixLogin.trim() != newSettings.zabbixLogin.trim()
        ) {
            zabbixClient.invalidateSession()
        }
        _state.update {
            it.copy(
                settings = newSettings,
                statusBanner = "Настройки Help-CSL сохранены в защищённое хранилище Windows"
            )
        }
        persistCurrentState()
        restartPollingLoop()
        if (newSettings.isConfigured) {
            refreshTickets(silent = false)
            syncRepairRegistry(silent = true)
            refreshClientsDirectory(silent = true)
        }
        if (newSettings.isZabbixConfigured && newSettings.zabbixEnabled) {
            refreshZabbix(silent = true)
        }
    }

    fun saveFilterSettings(newFilter: FilterSettings) {
        _state.update {
            it.copy(
                filterSettings = newFilter,
                statusBanner = "Правила фильтрации JSM / Сервисные сохранены"
            )
        }
        persistCurrentState()
        if (_state.value.settings.isConfigured) {
            refreshTickets(silent = false)
        }
    }

    fun testConnection(candidateSettings: AppSettings) {
        _state.update { it.copy(isPerformingAction = true, errorBanner = null, statusBanner = null) }
        scope.launch {
            apiClient.testConnection(candidateSettings)
                .onSuccess { displayName ->
                    _state.update {
                        it.copy(
                            isPerformingAction = false,
                            connectedUserName = displayName,
                            statusBanner = "Подключение успешно: $displayName"
                        )
                    }
                    persistCurrentState()
                }
                .onFailure { err ->
                    _state.update {
                        it.copy(
                            isPerformingAction = false,
                            errorBanner = "Ошибка подключения: ${err.message}"
                        )
                    }
                }
        }
    }

    private var lastRepairSilentSyncMs: Long = 0L
    private var lastChatSilentSyncMs: Long = 0L

    private fun restartPollingLoop() {
        pollingJob?.cancel()
        pollingJob = scope.launch {
            while (isActive) {
                val curSettings = _state.value.settings
                val (waitMs, _) = BelarusWorkCalendar.computeEffectivePollSchedule(
                    settings = curSettings,
                    nowInstant = java.time.Instant.now()
                )
                delay(waitMs)
                val latest = _state.value
                val (_, isOffHoursPaused) = BelarusWorkCalendar.computeEffectivePollSchedule(
                    settings = latest.settings,
                    nowInstant = java.time.Instant.now()
                )
                if (!isOffHoursPaused && latest.settings.pollingEnabled) {
                    if (latest.settings.isConfigured) {
                        processDuePostponedTickets()
                        refreshTickets(silent = true, isInitialBoot = false)
                        val nowMs = System.currentTimeMillis()
                        if (nowMs - lastRepairSilentSyncMs >= 180_000L) {
                            lastRepairSilentSyncMs = nowMs
                            syncRepairRegistry(silent = true)
                        }
                        if (nowMs - lastChatSilentSyncMs >= 60_000L) {
                            lastChatSilentSyncMs = nowMs
                            refreshChat(silent = true)
                        }
                    }
                    if (latest.settings.isZabbixConfigured && latest.settings.zabbixEnabled) {
                        refreshZabbix(silent = true)
                    }
                }
            }
        }
    }

    fun refreshTickets(silent: Boolean = false, isInitialBoot: Boolean = false) {
        val cur = _state.value
        if (!cur.settings.isConfigured) {
            if (!silent) {
                _state.update { it.copy(errorBanner = "Укажите адрес сервера Jira и логин/токен во вкладке «Настройки»") }
            }
            return
        }
        _state.update { it.copy(isRefreshing = true, errorBanner = if (silent) it.errorBanner else null) }
        scope.launch {
            if (cur.connectedUserName == null || EngineerNameResolver.looksLikeLoginOrEmail(cur.connectedUserName)) {
                apiClient.testConnection(cur.settings).onSuccess { name ->
                    _state.update { it.copy(connectedUserName = name) }
                    persistCurrentState()
                }
            }
            apiClient.fetchRecentTickets(cur.settings, cur.filterSettings, maxResults = 65)
                .onSuccess { fetched ->
                    val nowStr = java.time.LocalTime.now()
                        .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))
                    val freshNewClientTickets = mutableListOf<HelpdeskTicket>()
                    val allowAlertsNow = isDesktopAlertAllowedNow(
                        settings = _state.value.settings,
                        respectScheduleOnDesktop = _state.value.respectWorkScheduleOnDesktop
                    )
                    synchronized(notifiedTicketKeys) {
                        if (!baselineInitialized || (isInitialBoot && notifiedTicketKeys.isEmpty())) {
                            fetched.forEach { notifiedTicketKeys.add(it.key) }
                            deferredTicketKeys.clear()
                            baselineInitialized = true
                        } else {
                            val chronological = fetched.sortedBy { it.createdEpochMillis }
                            val nowEpochMs = System.currentTimeMillis()
                            val maxAlertAgeMs = 24L * 3600_000L
                            if (!allowAlertsNow) {
                                for (t in chronological) {
                                    val isUnseen = notifiedTicketKeys.add(t.key)
                                    val isFreshNew = t.createdEpochMillis <= 0L || (nowEpochMs - t.createdEpochMillis) <= maxAlertAgeMs
                                    if (isUnseen && t.isClientTicket && t.stage == TicketStage.NEW && isFreshNew) {
                                        deferredTicketKeys.add(t.key)
                                    }
                                }
                            } else {
                                for (t in chronological) {
                                    val wasDeferred = deferredTicketKeys.remove(t.key)
                                    val isUnseen = notifiedTicketKeys.add(t.key)
                                    val isFreshNew = t.createdEpochMillis <= 0L || (nowEpochMs - t.createdEpochMillis) <= maxAlertAgeMs
                                    if (t.isClientTicket && t.stage == TicketStage.NEW) {
                                        if ((isUnseen && isFreshNew) || wasDeferred) {
                                            freshNewClientTickets.add(t)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    val ticketClients = fetched.mapNotNull { t ->
                        if (!t.isClientTicket) null
                        else ClientDirectoryEntry(
                            name = t.reporterName,
                            phone = t.phoneNumbers.firstOrNull()?.e164,
                            source = "Helpdesk",
                            lastSeenEpochMs = t.updatedEpochMillis.coerceAtLeast(t.createdEpochMillis)
                        )
                    }
                    val grownDirectory = ClientDirectoryCodec.mergeEntries(
                        existing = _state.value.clientsDirectory,
                        incoming = ticketClients,
                        filterSettings = _state.value.filterSettings
                    )

                    _state.update { st ->
                        val prevClosedTimestamps = st.tickets
                            .filter { it.resolvedEpochMillis > 0L }
                            .associateBy({ it.key.uppercase() }, { it.resolvedIso to it.resolvedEpochMillis })
                        val mergedFetched = fetched.map { t ->
                            if (t.stage == TicketStage.CLOSED && t.resolvedEpochMillis <= 0L) {
                                val prev = prevClosedTimestamps[t.key.uppercase()]
                                if (prev != null) t.copy(resolvedIso = prev.first, resolvedEpochMillis = prev.second) else t
                            } else {
                                t
                            }
                        }
                        st.copy(
                            tickets = mergedFetched,
                            clientsDirectory = grownDirectory,
                            isRefreshing = false,
                            lastSyncDisplay = "обновлено в $nowStr",
                            emptyGifIndex = if (mergedFetched.none { it.isClientTicket && it.stage == TicketStage.NEW }) {
                                (st.emptyGifIndex + 1) % 2
                            } else st.emptyGifIndex
                        )
                    }
                    persistCurrentState()

                    if (freshNewClientTickets.isNotEmpty()) {
                        triggerNewTicketAlerts(freshNewClientTickets)
                    } else if (!isInitialBoot && allowAlertsNow) {
                        checkStaleUnacceptedTickets(fetched)
                    }
                }
                .onFailure { err ->
                    _state.update {
                        it.copy(
                            isRefreshing = false,
                            errorBanner = if (!silent) "Ошибка синхронизации: ${err.message}" else it.errorBanner
                        )
                    }
                }
        }
    }

    private fun isDesktopAlertAllowedNow(
        settings: AppSettings,
        respectScheduleOnDesktop: Boolean
    ): Boolean {
        if (!respectScheduleOnDesktop) return true
        return BelarusWorkCalendar.evaluate(settings).isAllowedNow
    }

    private fun emitDesktopAlert(alert: DesktopToastAlert, playSound: Boolean = true) {
        val settings = _state.value.settings
        _state.update { it.copy(activePopupAlert = alert) }
        DesktopNativePopupManager.showAlert(
            alert = alert,
            onTakeInWork = if (alert.ticketKey != null && !alert.isStormSummary) {
                { key ->
                    _state.update { it.copy(activePopupAlert = null) }
                    performTicketAction(key, TicketWorkflowAction.TAKE_IN_WORK)
                }
            } else null,
            onOpen = {
                bringWindowToFrontAndOpenTicket(alert.ticketKey)
            },
            onDismissed = {
                _state.update { st ->
                    if (st.activePopupAlert?.id == alert.id) st.copy(activePopupAlert = null) else st
                }
            }
        )
        runCatching { onSystemTrayNotify?.invoke(alert.title, alert.subtitle) }
        if (playSound) {
            DesktopSoundPlayer.playAlert(settings.alertSoundPreset, settings.customAlertSoundUri)
        }
    }

    fun saveAndConnectZabbix(
        zabbixUrl: String,
        login: String,
        password: String,
        enabled: Boolean,
        minSeverity: Int
    ) {
        val updated = _state.value.settings.copy(
            zabbixUrl = zabbixUrl.trim().ifBlank { AppSettings.DEFAULT_ZABBIX_URL },
            zabbixLogin = login.trim(),
            zabbixPassword = password,
            zabbixEnabled = enabled,
            zabbixMinAlertSeverity = minSeverity.coerceIn(1, 5)
        )
        zabbixClient.invalidateSession()
        _state.update {
            it.copy(
                settings = updated,
                zabbixError = null,
                isLoadingZabbix = true
            )
        }
        persistCurrentState()
        restartPollingLoop()

        if (!updated.isZabbixConfigured) {
            _state.update {
                it.copy(
                    isLoadingZabbix = false,
                    zabbixError = "Укажите логин и пароль Zabbix для подключения к ${updated.zabbixUrl}"
                )
            }
            return
        }

        scope.launch {
            zabbixClient.testConnection(updated)
                .onSuccess { snapshot ->
                    handleIncomingZabbixSnapshot(snapshot)
                    val verInfo = snapshot.serverVersion.takeIf { it.isNotBlank() }?.let { " (Zabbix v$it)" }.orEmpty()
                    _state.update {
                        it.copy(
                            isLoadingZabbix = false,
                            statusBanner = "Подключено к Zabbix ${updated.zabbixUrl}$verInfo · активных проблем: ${snapshot.problems.size}"
                        )
                    }
                }
                .onFailure { err ->
                    _state.update {
                        it.copy(
                            isLoadingZabbix = false,
                            zabbixError = err.message ?: "Не удалось подключиться к Zabbix"
                        )
                    }
                }
        }
    }

    fun refreshZabbix(silent: Boolean = false) {
        val cur = _state.value
        if (!cur.settings.isZabbixConfigured) {
            if (!silent) {
                _state.update { it.copy(zabbixError = "Укажите логин и пароль Zabbix (${cur.settings.zabbixUrl})") }
            }
            return
        }
        _state.update { it.copy(isLoadingZabbix = true, zabbixError = if (silent) it.zabbixError else null) }
        scope.launch {
            zabbixClient.fetchSnapshot(cur.settings)
                .onSuccess { snapshot ->
                    handleIncomingZabbixSnapshot(snapshot)
                }
                .onFailure { err ->
                    _state.update {
                        it.copy(
                            isLoadingZabbix = false,
                            zabbixError = err.message ?: "Ошибка связи с Zabbix"
                        )
                    }
                }
        }
    }

    private fun handleIncomingZabbixSnapshot(snapshot: ZabbixDashboardSnapshot) {
        val cur = _state.value
        val minSev = cur.settings.normalizedZabbixMinSeverity
        val dismissedKeys = cur.dismissedZabbixKeys
        val mutedKeySet = cur.mutedZabbixRules.flatMap { it.allMatchKeys() }.toSet()
        val allowAlertsNow = isDesktopAlertAllowedNow(cur.settings, cur.respectWorkScheduleOnDesktop)
        val newAlerts = mutableListOf<ZabbixProblem>()

        synchronized(notifiedZabbixKeys) {
            if (!zabbixBaselineInitialized) {
                val nowSec = System.currentTimeMillis() / 1000L
                for (prob in snapshot.problems) {
                    val suppressed = prob.isHiddenFromActiveFeed(dismissedKeys, mutedKeySet)
                    if (!suppressed &&
                        !prob.acknowledged &&
                        prob.shouldTriggerBanner(minSev) &&
                        prob.clockEpochSeconds > 0L &&
                        (nowSec - prob.clockEpochSeconds) <= 1800L
                    ) {
                        notifiedZabbixKeys.add(prob.alertKey)
                        if (allowAlertsNow) {
                            newAlerts.add(prob)
                        } else {
                            deferredZabbixKeys.add(prob.alertKey)
                        }
                    } else {
                        notifiedZabbixKeys.add(prob.alertKey)
                    }
                }
                zabbixBaselineInitialized = true
            } else {
                val byKey = snapshot.problems.associateBy { it.alertKey }
                if (allowAlertsNow && deferredZabbixKeys.isNotEmpty()) {
                    val iter = deferredZabbixKeys.iterator()
                    while (iter.hasNext()) {
                        val dKey = iter.next()
                        val p = byKey[dKey]
                        if (p != null &&
                            !p.acknowledged &&
                            !p.isHiddenFromActiveFeed(dismissedKeys, mutedKeySet) &&
                            p.shouldTriggerBanner(minSev)
                        ) {
                            newAlerts.add(p)
                        }
                        iter.remove()
                    }
                }
                for (prob in snapshot.problems) {
                    val isUnseen = notifiedZabbixKeys.add(prob.alertKey)
                    if (isUnseen &&
                        !prob.acknowledged &&
                        !prob.isHiddenFromActiveFeed(dismissedKeys, mutedKeySet) &&
                        prob.shouldTriggerBanner(minSev)
                    ) {
                        if (allowAlertsNow) {
                            newAlerts.add(prob)
                        } else {
                            deferredZabbixKeys.add(prob.alertKey)
                        }
                    }
                }
            }
        }

        _state.update {
            it.copy(
                zabbixSnapshot = snapshot,
                zabbixError = null,
                isLoadingZabbix = false
            )
        }
        persistCurrentState()

        if (newAlerts.isNotEmpty() && allowAlertsNow) {
            triggerZabbixDesktopAlerts(newAlerts)
        }
    }

    private fun triggerZabbixDesktopAlerts(problems: List<ZabbixProblem>) {
        if (problems.isEmpty()) return
        val settings = _state.value.settings
        if (settings.stormProtectionEnabled && problems.size > 3) {
            val summaryLines = problems.take(4).joinToString("; ") {
                "${it.effectiveHostLabel}: ${it.name.take(40)}"
            }
            val toast = DesktopToastAlert(
                id = "zbx_storm_${System.currentTimeMillis()}",
                title = "🚨 Шторм Zabbix: +${problems.size} ошибок / аварий",
                subtitle = summaryLines,
                ticketKey = null,
                isStormSummary = true
            )
            emitZabbixDesktopPopup(toast, problem = null, playSound = true)
        } else {
            problems.forEachIndexed { idx, prob ->
                val subtitle = buildString {
                    append(prob.name)
                    if (prob.opData.isNotBlank()) append(" [").append(prob.opData).append("]")
                    append(" · ").append(prob.hostWithIpLabel)
                }
                val toast = DesktopToastAlert(
                    id = "zbx_${prob.alertKey}_${System.currentTimeMillis()}",
                    title = "🚨 Zabbix · ${prob.effectiveHostLabel} · ${prob.severity.bannerSuffix}",
                    subtitle = subtitle,
                    ticketKey = prob.eventId.takeIf { it.isNotBlank() && it != "0" }?.let { "ZBX-$it" },
                    isStormSummary = false
                )
                emitZabbixDesktopPopup(toast, problem = prob, playSound = (idx == 0))
            }
        }
    }

    private fun emitZabbixDesktopPopup(
        alert: DesktopToastAlert,
        problem: ZabbixProblem?,
        playSound: Boolean
    ) {
        val settings = _state.value.settings
        _state.update { it.copy(activePopupAlert = alert) }
        DesktopNativePopupManager.showAlert(
            alert = alert,
            onTakeInWork = if (problem != null && problem.eventId.isNotBlank() && problem.eventId != "0") {
                { _ ->
                    _state.update { it.copy(activePopupAlert = null) }
                    acknowledgeZabbixProblem(problem, null)
                }
            } else null,
            onOpen = {
                onBringWindowToFront?.invoke()
                _state.update { it.copy(activeTab = DesktopMainTab.ZABBIX, activePopupAlert = null) }
            },
            onDismissed = {
                _state.update { st ->
                    if (st.activePopupAlert?.id == alert.id) st.copy(activePopupAlert = null) else st
                }
            }
        )
        runCatching { onSystemTrayNotify?.invoke(alert.title, alert.subtitle) }
        if (playSound) {
            DesktopSoundPlayer.playAlert(settings.alertSoundPreset, settings.customAlertSoundUri)
        }
    }

    fun acknowledgeZabbixProblem(problem: ZabbixProblem, customComment: String?) {
        if (problem.eventId.startsWith("DEMO")) {
            _state.update { it.copy(statusBanner = "Тестовое событие Zabbix подтверждено") }
            return
        }
        val cur = _state.value
        if (!cur.settings.isZabbixConfigured) return
        val engineer = EngineerNameResolver.resolve(
            rawEngineer = null,
            connectedUserDisplayName = cur.connectedUserName,
            currentUsernameOrEmail = cur.settings.zabbixLogin.ifBlank { cur.settings.usernameOrEmail },
            knownTickets = cur.tickets
        )
        val comment = customComment?.trim()?.takeIf { it.isNotEmpty() }
            ?: "Принято в работу инженером $engineer (Help-CSL)"

        scope.launch {
            zabbixClient.acknowledgeProblem(cur.settings, problem.eventId, comment)
                .onSuccess {
                    _state.update { st ->
                        val updatedSnap = st.zabbixSnapshot?.let { snap ->
                            snap.copy(
                                problems = snap.problems.map { p ->
                                    if (p.eventId == problem.eventId) {
                                        p.copy(acknowledged = true, ackMessage = comment)
                                    } else p
                                }
                            )
                        }
                        st.copy(
                            zabbixSnapshot = updatedSnap,
                            statusBanner = "Событие Zabbix #${problem.eventId} подтверждено"
                        )
                    }
                    persistCurrentState()
                }
                .onFailure { err ->
                    _state.update {
                        it.copy(errorBanner = err.message ?: "Не удалось подтвердить проблему в Zabbix")
                    }
                }
        }
    }

    fun dismissZabbixProblem(problem: ZabbixProblem) {
        synchronized(notifiedZabbixKeys) {
            notifiedZabbixKeys.add(problem.alertKey)
            deferredZabbixKeys.remove(problem.alertKey)
        }
        _state.update { st ->
            st.copy(
                dismissedZabbixKeys = st.dismissedZabbixKeys + problem.alertKey + problem.fallbackOccurrenceKey,
                statusBanner = "Карточка скрыта — будут показаны только новые события"
            )
        }
        persistCurrentState()
    }

    fun dismissAllCurrentZabbixProblems(problems: List<ZabbixProblem>) {
        if (problems.isEmpty()) return
        val extraKeys = problems.flatMap { listOf(it.alertKey, it.fallbackOccurrenceKey) }.toSet()
        synchronized(notifiedZabbixKeys) {
            problems.forEach {
                notifiedZabbixKeys.add(it.alertKey)
                deferredZabbixKeys.remove(it.alertKey)
            }
        }
        _state.update { st ->
            st.copy(
                dismissedZabbixKeys = st.dismissedZabbixKeys + extraKeys,
                statusBanner = "Закрыто текущих карточек: ${problems.size}. Будут показаны только новые."
            )
        }
        persistCurrentState()
    }

    fun restoreDismissedZabbixProblem(problem: ZabbixProblem) {
        _state.update { st ->
            st.copy(
                dismissedZabbixKeys = st.dismissedZabbixKeys - problem.alertKey - problem.fallbackOccurrenceKey
            )
        }
        persistCurrentState()
    }

    fun restoreAllDismissedZabbixProblems() {
        _state.update { st ->
            st.copy(
                dismissedZabbixKeys = emptySet(),
                statusBanner = "Скрытые текущие карточки возвращены в список"
            )
        }
        persistCurrentState()
    }

    fun muteZabbixProblemForever(problem: ZabbixProblem) {
        val rule = problem.toMutedRule()
        synchronized(notifiedZabbixKeys) {
            notifiedZabbixKeys.add(problem.alertKey)
            deferredZabbixKeys.remove(problem.alertKey)
        }
        _state.update { st ->
            val updatedRules = listOf(rule) + st.mutedZabbixRules.filterNot {
                it.ruleKey == rule.ruleKey || (rule.signatureKey.isNotBlank() && it.signatureKey == rule.signatureKey)
            }
            st.copy(
                mutedZabbixRules = updatedRules,
                statusBanner = "🔕 Проблема отмечена как нерешаемая — уведомления отключены"
            )
        }
        persistCurrentState()
    }

    fun unmuteZabbixRule(ruleKey: String) {
        _state.update { st ->
            st.copy(
                mutedZabbixRules = st.mutedZabbixRules.filterNot { it.ruleKey == ruleKey },
                statusBanner = "Уведомления по триггеру включены снова"
            )
        }
        persistCurrentState()
    }

    fun clearAllMutedZabbixRules() {
        _state.update { st ->
            st.copy(
                mutedZabbixRules = emptyList(),
                statusBanner = "Список нерешаемых триггеров очищен"
            )
        }
        persistCurrentState()
    }

    fun triggerTestZabbixBanner() {
        val nowSec = System.currentTimeMillis() / 1000L
        val demo = ZabbixProblem(
            eventId = "DEMO${nowSec % 10000}",
            triggerId = "9901",
            name = "Disaster: Недоступен узел / авария сервиса 1С Продакшн",
            opData = "ICMP ping: DOWN",
            severityLevel = 5,
            hostId = "101",
            hostTechnicalName = "srv-1c-prod.csl.by",
            hostDisplayName = "Сервер 1С Продакшн",
            hostIp = "192.168.10.25",
            clockEpochSeconds = nowSec
        )
        triggerZabbixDesktopAlerts(listOf(demo))
    }

    fun triggerTestNotification() {
        val sampleTicket = _state.value.tickets.firstOrNull { it.isClientTicket }
        val alert = DesktopToastAlert(
            id = "test-${System.currentTimeMillis()}",
            title = "🔔 Тест уведомления · ${sampleTicket?.key ?: "CSL-TEST"}",
            subtitle = sampleTicket?.summary ?: "Проверка всплывающего баннера и звукового сигнала Help-CSL в системном трее Windows.",
            ticketKey = sampleTicket?.key,
            isStormSummary = true
        )
        emitDesktopAlert(alert, playSound = true)
    }

    private fun triggerNewTicketAlerts(freshTickets: List<HelpdeskTicket>) {
        val cur = _state.value
        val settings = cur.settings
        if (!isDesktopAlertAllowedNow(settings, cur.respectWorkScheduleOnDesktop)) return

        val alert = if (settings.stormProtectionEnabled && freshTickets.size >= 4) {
            DesktopToastAlert(
                id = "storm-${System.currentTimeMillis()}",
                title = "⚡ Шторм заявок: +${freshTickets.size} новых",
                subtitle = freshTickets.take(3).joinToString(" · ") { "${it.reporterName} (${it.key})" },
                ticketKey = freshTickets.first().key,
                isStormSummary = true
            )
        } else {
            val top = freshTickets.last()
            DesktopToastAlert(
                id = "ticket-${top.key}-${System.currentTimeMillis()}",
                title = "🔔 ${top.reporterName.ifBlank { "Новая заявка" }} · ${top.key}",
                subtitle = top.summary,
                ticketKey = top.key,
                isStormSummary = false
            )
        }

        emitDesktopAlert(alert, playSound = true)
        if (settings.ttsReadoutMode == TtsReadoutMode.ALWAYS) {
            val top = freshTickets.last()
            DesktopSoundPlayer.speakTicketSummaryWindows(top.reporterName, top.summary)
        }
    }

    private fun checkStaleUnacceptedTickets(tickets: List<HelpdeskTicket>) {
        val cur = _state.value
        val settings = cur.settings
        if (!settings.staleTicketReminderEnabled) return
        val thresholdMinutes = settings.staleTicketReminderMinutes.coerceIn(5, 120)
        val thresholdMs = thresholdMinutes * 60_000L
        val nowMs = System.currentTimeMillis()

        // Clean up keys no longer in NEW stage
        val activeNewKeys = tickets.asSequence()
            .filter { it.isClientTicket && it.stage == TicketStage.NEW && it.assigneeName.isNullOrBlank() }
            .map { it.key }
            .toSet()
        synchronized(lastStaleReminderByKey) {
            lastStaleReminderByKey.keys.retainAll(activeNewKeys)
        }

        val overdueCandidates = tickets.filter { t ->
            t.isClientTicket &&
                t.stage == TicketStage.NEW &&
                t.assigneeName.isNullOrBlank() &&
                t.createdEpochMillis > 0L &&
                (nowMs - t.createdEpochMillis) >= thresholdMs
        }
        if (overdueCandidates.isEmpty()) return

        val dueForReminder = synchronized(lastStaleReminderByKey) {
            overdueCandidates.filter { t ->
                val lastReminded = lastStaleReminderByKey[t.key] ?: t.createdEpochMillis
                (nowMs - lastReminded) >= thresholdMs
            }.also { list ->
                list.forEach { lastStaleReminderByKey[it.key] = nowMs }
            }
        }
        if (dueForReminder.isEmpty()) return

        val oldest = dueForReminder.minByOrNull { it.createdEpochMillis } ?: dueForReminder.first()
        val waitMins = ((nowMs - oldest.createdEpochMillis) / 60_000L).coerceAtLeast(thresholdMinutes.toLong())
        val alert = if (dueForReminder.size == 1) {
            DesktopToastAlert(
                id = "sla-${oldest.key}-$nowMs",
                title = "⏰ Ждёт реакции ${waitMins} мин · ${oldest.key}",
                subtitle = "${oldest.reporterName.ifBlank { "Клиент" }}: ${oldest.summary}",
                ticketKey = oldest.key,
                isStormSummary = false
            )
        } else {
            DesktopToastAlert(
                id = "sla-batch-$nowMs",
                title = "⏰ Не приняты в работу: ${dueForReminder.size} заявки (> $thresholdMinutes мин)",
                subtitle = dueForReminder.take(3).joinToString(" · ") { "${it.key} (${it.reporterName})" },
                ticketKey = oldest.key,
                isStormSummary = true
            )
        }
        emitDesktopAlert(alert, playSound = true)
    }

    private suspend fun processDuePostponedTickets() {
        val now = System.currentTimeMillis()
        val cur = _state.value
        val due = cur.postponedTickets.filter { it.scheduledAtMillis <= now }
        if (due.isEmpty()) return

        for (item in due) {
            apiClient.recreatePostponedTicket(item, cur.settings, cur.filterSettings)
                .onSuccess { recreated ->
                    synchronized(notifiedTicketKeys) {
                        notifiedTicketKeys.add(recreated.key)
                    }
                    _state.update { st ->
                        st.copy(
                            postponedTickets = st.postponedTickets.filterNot { it.id == item.id },
                            tickets = listOf(recreated) + st.tickets.filterNot { it.key == recreated.key },
                            statusBanner = "⏰ Возвращена отложенная заявка ${recreated.key} (исх. ${item.originalKey})"
                        )
                    }
                    persistCurrentState()
                    triggerNewTicketAlerts(listOf(recreated))
                }
        }
    }

    fun performTicketAction(
        ticketKey: String,
        action: TicketWorkflowAction,
        commentText: String? = null,
        explicitTransitionId: String? = null
    ) {
        val cur = _state.value
        _state.update { it.copy(isPerformingAction = true, errorBanner = null) }
        scope.launch {
            apiClient.performWorkflowAction(
                issueKey = ticketKey,
                action = action,
                commentText = commentText,
                explicitTransitionId = explicitTransitionId,
                settings = cur.settings,
                filterSettings = cur.filterSettings
            ).onSuccess { outcome ->
                if (outcome.needsManualTransitionChoice) {
                    _state.update {
                        it.copy(
                            isPerformingAction = false,
                            pendingTransitionPrompt = PendingTransitionPrompt(
                                ticketKey = ticketKey,
                                action = action,
                                commentText = commentText,
                                transitions = outcome.availableTransitions
                            )
                        )
                    }
                } else {
                    outcome.updatedTicket?.let { replaceTicketInMemory(it) }
                    _state.update { st ->
                        val nextStopwatches = when (action) {
                            TicketWorkflowAction.TAKE_IN_WORK ->
                                TicketStopwatchTracker.startOrResume(st.ticketStopwatches, ticketKey)
                            TicketWorkflowAction.COMPLETE, TicketWorkflowAction.REJECT ->
                                TicketStopwatchTracker.stopAndClear(st.ticketStopwatches, ticketKey)
                            else -> st.ticketStopwatches
                        }
                        val returnToFresh = action == TicketWorkflowAction.COMPLETE || action == TicketWorkflowAction.REJECT
                        st.copy(
                            isPerformingAction = false,
                            pendingTransitionPrompt = null,
                            ticketStopwatches = nextStopwatches,
                            selectedTicketKey = if (returnToFresh) null else st.selectedTicketKey,
                            ticketQueueTab = if (returnToFresh) TicketQueueTab.NEW else st.ticketQueueTab,
                            ticketAgeFilter = if (returnToFresh) TicketAgeFilter.FRESH_TODAY else st.ticketAgeFilter,
                            showHabrFeedPanel = if (returnToFresh) false else st.showHabrFeedPanel,
                            statusBanner = outcome.statusMessage
                        )
                    }
                    persistCurrentState()
                    refreshTickets(silent = true)
                }
            }.onFailure { err ->
                _state.update {
                    it.copy(
                        isPerformingAction = false,
                        errorBanner = "Не удалось выполнить переход: ${err.message}"
                    )
                }
            }
        }
    }

    fun postponeTicket(
        ticket: HelpdeskTicket,
        scheduledAtMillis: Long,
        scheduledDisplay: String,
        explicitTransitionId: String? = null
    ) {
        val cur = _state.value
        _state.update { it.copy(isPerformingAction = true, errorBanner = null) }
        scope.launch {
            apiClient.postponeAndCloseTicket(
                issueKey = ticket.key,
                fallbackTicket = ticket,
                scheduledAtMillis = scheduledAtMillis,
                scheduledDisplay = scheduledDisplay,
                explicitTransitionId = explicitTransitionId,
                settings = cur.settings,
                filterSettings = cur.filterSettings
            ).onSuccess { outcome ->
                if (outcome.needsManualTransitionChoice) {
                    _state.update {
                        it.copy(
                            isPerformingAction = false,
                            pendingTransitionPrompt = PendingTransitionPrompt(
                                ticketKey = ticket.key,
                                action = null,
                                isPostpone = true,
                                postponeAtMillis = scheduledAtMillis,
                                postponeDisplay = scheduledDisplay,
                                transitions = outcome.availableTransitions
                            )
                        )
                    }
                } else {
                    outcome.closedTicket?.let { replaceTicketInMemory(it) }
                    _state.update { st ->
                        st.copy(
                            isPerformingAction = false,
                            pendingTransitionPrompt = null,
                            selectedTicketKey = null,
                            ticketQueueTab = TicketQueueTab.NEW,
                            ticketAgeFilter = TicketAgeFilter.FRESH_TODAY,
                            showHabrFeedPanel = false,
                            postponedTickets = listOfNotNull(outcome.postponedTicket) + st.postponedTickets,
                            statusBanner = outcome.statusMessage
                        )
                    }
                    persistCurrentState()
                }
            }.onFailure { err ->
                _state.update {
                    it.copy(
                        isPerformingAction = false,
                        errorBanner = "Не удалось отложить заявку: ${err.message}"
                    )
                }
            }
        }
    }

    fun cancelTransitionPrompt() {
        _state.update { it.copy(pendingTransitionPrompt = null) }
    }

    fun cancelPostponedTicket(item: PostponedTicket) {
        _state.update { st ->
            st.copy(
                postponedTickets = st.postponedTickets.filterNot { it.id == item.id },
                statusBanner = "Отложенное пересоздание ${item.originalKey} отменено"
            )
        }
        persistCurrentState()
    }

    fun postComment(ticketKey: String, text: String, internalOnly: Boolean) {
        val cur = _state.value
        if (text.isBlank()) return
        _state.update { it.copy(isPerformingAction = true) }
        scope.launch {
            apiClient.postIssueComment(
                issueKey = ticketKey,
                commentText = text.trim(),
                settings = cur.settings,
                filterSettings = cur.filterSettings,
                internalOnly = internalOnly
            ).onSuccess {
                apiClient.fetchTicketByKey(ticketKey, cur.settings, cur.filterSettings).onSuccess { fresh ->
                    replaceTicketInMemory(fresh)
                }
                _state.update {
                    it.copy(
                        isPerformingAction = false,
                        statusBanner = if (internalOnly) "Добавлена внутренняя заметка в $ticketKey" else "Комментарий отправлен в $ticketKey"
                    )
                }
            }.onFailure { err ->
                _state.update { it.copy(isPerformingAction = false, errorBanner = "Ошибка комментария: ${err.message}") }
            }
        }
    }

    fun logWorkTime(ticketKey: String, minutes: Int, comment: String?) {
        val cur = _state.value
        _state.update { it.copy(isPerformingAction = true) }
        scope.launch {
            apiClient.addIssueWorklog(
                issueKey = ticketKey,
                timeSpentSeconds = (minutes.coerceAtLeast(1)) * 60,
                commentText = comment,
                settings = cur.settings
            ).onSuccess {
                _state.update {
                    it.copy(isPerformingAction = false, statusBanner = "Списано $minutes мин в Worklog ($ticketKey)")
                }
            }.onFailure { err ->
                _state.update { it.copy(isPerformingAction = false, errorBanner = "Ошибка Worklog: ${err.message}") }
            }
        }
    }

    suspend fun loadAssignableColleagues(ticketKey: String): Result<List<JiraAssignableUser>> {
        return apiClient.fetchAssignableUsers(ticketKey, _state.value.settings)
    }

    fun assignToColleague(ticketKey: String, colleague: JiraAssignableUser) {
        val cur = _state.value
        _state.update { it.copy(isPerformingAction = true) }
        scope.launch {
            apiClient.assignTicketToColleague(ticketKey, colleague, cur.settings, cur.filterSettings)
                .onSuccess { updated ->
                    updated?.let { replaceTicketInMemory(it) }
                    _state.update {
                        it.copy(
                            isPerformingAction = false,
                            statusBanner = "Заявка $ticketKey передана коллеге ${colleague.displayName}"
                        )
                    }
                }
                .onFailure { err ->
                    _state.update { it.copy(isPerformingAction = false, errorBanner = "Ошибка передачи: ${err.message}") }
                }
        }
    }

    fun uploadFileAttachment(ticketKey: String, file: File) {
        val cur = _state.value
        _state.update { it.copy(isPerformingAction = true) }
        scope.launch(Dispatchers.IO) {
            runCatching {
                val bytes = file.readBytes()
                val mime = java.nio.file.Files.probeContentType(file.toPath()) ?: "application/octet-stream"
                apiClient.uploadIssueAttachment(
                    issueKey = ticketKey,
                    fileName = file.name,
                    mimeType = mime,
                    fileBytes = bytes,
                    settings = cur.settings,
                    filterSettings = cur.filterSettings
                ).getOrThrow()
            }.onSuccess { updated ->
                updated?.let { replaceTicketInMemory(it) }
                _state.update {
                    it.copy(isPerformingAction = false, statusBanner = "Файл «${file.name}» прикреплён к $ticketKey")
                }
            }.onFailure { err ->
                _state.update { it.copy(isPerformingAction = false, errorBanner = "Ошибка загрузки файла: ${err.message}") }
            }
        }
    }

    fun uploadClipboardScreenshot(ticketKey: String) {
        val captured = WindowsExternalLaunchers.readClipboardImagePng()
        if (captured == null) {
            _state.update {
                it.copy(errorBanner = "В буфере обмена Windows нет изображения. Нажмите Win+Shift+S, выделите область экрана и повторите.")
            }
            return
        }
        val (fileName, pngBytes) = captured
        val cur = _state.value
        _state.update { it.copy(isPerformingAction = true) }
        scope.launch(Dispatchers.IO) {
            runCatching {
                apiClient.uploadIssueAttachment(
                    issueKey = ticketKey,
                    fileName = fileName,
                    mimeType = "image/png",
                    fileBytes = pngBytes,
                    settings = cur.settings,
                    filterSettings = cur.filterSettings
                ).getOrThrow()
            }.onSuccess { updated ->
                updated?.let { replaceTicketInMemory(it) }
                _state.update {
                    it.copy(isPerformingAction = false, statusBanner = "Скриншот из буфера обмена прикреплён к $ticketKey")
                }
            }.onFailure { err ->
                _state.update { it.copy(isPerformingAction = false, errorBanner = "Ошибка отправки скриншота: ${err.message}") }
            }
        }
    }

    fun downloadAndOpenAttachment(url: String, fileName: String) {
        val cur = _state.value
        _state.update { it.copy(isPerformingAction = true) }
        scope.launch(Dispatchers.IO) {
            apiClient.downloadAttachmentBytes(url, cur.settings)
                .onSuccess { bytes ->
                    val dir = File(System.getProperty("java.io.tmpdir"), "HelpCSL-Attachments").apply { mkdirs() }
                    val safeName = fileName.replace(Regex("""[\\/:*?"<>|]"""), "_")
                    val target = File(dir, safeName)
                    target.writeBytes(bytes)
                    WindowsExternalLaunchers.openFile(target)
                    _state.update { it.copy(isPerformingAction = false, statusBanner = "Открыто вложение: $safeName") }
                }
                .onFailure { err ->
                    _state.update { it.copy(isPerformingAction = false, errorBanner = "Не удалось скачать вложение: ${err.message}") }
                }
        }
    }

    fun createManualTicket(
        projectKey: String,
        issueType: String,
        summary: String,
        description: String,
        assignToSelf: Boolean
    ) {
        val cur = _state.value
        _state.update { it.copy(isPerformingAction = true) }
        scope.launch {
            apiClient.createManualTicket(
                projectKeyHint = projectKey,
                issueTypeHint = issueType,
                summary = summary,
                description = description,
                assignToSelf = assignToSelf,
                settings = cur.settings,
                filterSettings = cur.filterSettings
            ).onSuccess { created ->
                synchronized(notifiedTicketKeys) { notifiedTicketKeys.add(created.key) }
                _state.update { st ->
                    st.copy(
                        isPerformingAction = false,
                        tickets = listOf(created) + st.tickets,
                        selectedTicketKey = created.key,
                        statusBanner = "Создана заявка ${created.key}"
                    )
                }
                persistCurrentState()
            }.onFailure { err ->
                _state.update { it.copy(isPerformingAction = false, errorBanner = "Ошибка создания заявки: ${err.message}") }
            }
        }
    }

    fun searchBySerialOrText(query: String) {
        val cur = _state.value
        if (query.isBlank()) return
        _state.update { it.copy(isSearchingSerial = true) }
        scope.launch {
            apiClient.searchTicketsBySerialOrText(query.trim(), cur.settings, cur.filterSettings)
                .onSuccess { list ->
                    _state.update { it.copy(isSearchingSerial = false, serialSearchResults = list) }
                }
                .onFailure { err ->
                    _state.update {
                        it.copy(isSearchingSerial = false, errorBanner = "Ошибка поиска по S/N: ${err.message}")
                    }
                }
        }
    }

    fun runHostDiagnostics(host: String) {
        _state.update { it.copy(isRunningDiagnostics = true, diagnosticReport = null) }
        scope.launch {
            val report = NetworkDiagnosticsTool.runDiagnostics(host)
            _state.update { it.copy(isRunningDiagnostics = false, diagnosticReport = report) }
        }
    }

    // =========================================================================
    //  Workshop «В ремонт» + «Удалённые» Audit + Helpdesk Client Directory
    // =========================================================================

    fun currentEngineerDisplayName(): String {
        val cur = _state.value
        return EngineerNameResolver.resolve(
            rawEngineer = null,
            connectedUserDisplayName = cur.connectedUserName,
            currentUsernameOrEmail = cur.settings.usernameOrEmail,
            knownTickets = cur.tickets,
            fallbackLabel = "Сервисный инженер"
        )
    }

    fun formatEngineerName(rawEngineer: String): String {
        val cur = _state.value
        return EngineerNameResolver.resolve(
            rawEngineer = rawEngineer,
            connectedUserDisplayName = cur.connectedUserName,
            currentUsernameOrEmail = cur.settings.usernameOrEmail,
            knownTickets = cur.tickets,
            fallbackLabel = "Сервисный инженер"
        )
    }

    private suspend fun ensureEngineerHumanName(rawCandidate: String? = null): String {
        val initial = _state.value
        val quick = EngineerNameResolver.resolve(
            rawEngineer = rawCandidate,
            connectedUserDisplayName = initial.connectedUserName,
            currentUsernameOrEmail = initial.settings.usernameOrEmail,
            knownTickets = initial.tickets,
            fallbackLabel = ""
        )
        if (quick.isNotBlank() && !EngineerNameResolver.looksLikeLoginOrEmail(quick)) {
            return quick
        }
        if (initial.settings.isConfigured) {
            if (initial.connectedUserName == null || EngineerNameResolver.looksLikeLoginOrEmail(initial.connectedUserName)) {
                apiClient.testConnection(initial.settings).onSuccess { name ->
                    if (!EngineerNameResolver.looksLikeLoginOrEmail(name)) {
                        _state.update { it.copy(connectedUserName = name) }
                        persistCurrentState()
                    }
                }
            }
            val lookupQuery = rawCandidate?.takeIf { it.isNotBlank() } ?: initial.settings.usernameOrEmail
            val remoteDisplay = apiClient.resolveUserDisplayName(lookupQuery, initial.settings)
            if (!remoteDisplay.isNullOrBlank() && !EngineerNameResolver.looksLikeLoginOrEmail(remoteDisplay)) {
                return remoteDisplay
            }
        }
        val cur = _state.value
        return EngineerNameResolver.resolve(
            rawEngineer = rawCandidate,
            connectedUserDisplayName = cur.connectedUserName,
            currentUsernameOrEmail = cur.settings.usernameOrEmail,
            knownTickets = cur.tickets,
            fallbackLabel = "Сервисный инженер"
        )
    }

    fun openRepairIntakeFromTicket(ticket: HelpdeskTicket) {
        _state.update {
            it.copy(
                activeTab = DesktopMainTab.REPAIR,
                pendingRepairPrefillTicket = ticket
            )
        }
    }

    fun consumePendingRepairPrefill() {
        _state.update { it.copy(pendingRepairPrefillTicket = null) }
    }

    fun saveRepairItem(draft: RepairEquipmentItem) {
        val now = System.currentTimeMillis()
        val engineer = formatEngineerName(draft.assignedEngineer)
        val cur = _state.value
        val number = draft.intakeNumber.ifBlank { RepairEquipmentCodec.generateNextIntakeNumber(cur.repairItems) }
        val saved = draft.copy(
            intakeNumber = number,
            assignedEngineer = engineer,
            updatedAtEpochMs = now,
            issuedAtEpochMs = if (draft.status == RepairStatus.ISSUED && draft.issuedAtEpochMs == null) now else draft.issuedAtEpochMs,
            pendingRemoteSync = true
        )
        val mergedItems = RepairEquipmentCodec.mergeItems(
            localItems = cur.repairItems.filterNot { it.id == saved.id } + saved,
            remoteEvents = emptyList()
        )
        val incomingClient = if (saved.clientName.isNotBlank()) {
            listOf(
                ClientDirectoryEntry(
                    name = saved.clientName,
                    phone = saved.clientPhone.ifBlank { null },
                    source = "В ремонт",
                    lastSeenEpochMs = now
                )
            )
        } else emptyList()

        val grownClients = ClientDirectoryCodec.mergeEntries(
            existing = cur.clientsDirectory,
            incoming = incomingClient,
            filterSettings = cur.filterSettings
        )
        _state.update {
            it.copy(
                repairItems = mergedItems,
                clientsDirectory = grownClients,
                statusBanner = "Карточка ремонта $number сохранена"
            )
        }
        persistCurrentState()
        pushRepairEntryRemote(saved)
    }

    /**
     * Non-destructive soft-deletion into «Удалённые» recording who deleted it and when.
     */
    fun softDeleteRepairItem(item: RepairEquipmentItem, reason: String? = null) {
        val now = System.currentTimeMillis()
        val who = currentEngineerDisplayName()
        val softDeleted = item.copy(
            isDeleted = true,
            deletedBy = who,
            deletedAtEpochMs = now,
            deleteReason = reason?.trim()?.ifBlank { null },
            updatedAtEpochMs = now,
            pendingRemoteSync = true
        )
        val cur = _state.value
        val merged = RepairEquipmentCodec.mergeItems(
            localItems = cur.repairItems.filterNot { it.id == softDeleted.id } + softDeleted,
            remoteEvents = emptyList()
        )
        _state.update {
            it.copy(
                repairItems = merged,
                statusBanner = "Позиция ${item.intakeNumber} перемещена в «Удалённые» (удалил: $who)"
            )
        }
        persistCurrentState()
        pushRepairEntryRemote(softDeleted)
    }

    fun restoreRepairItem(item: RepairEquipmentItem) {
        val now = System.currentTimeMillis()
        val restored = item.copy(
            isDeleted = false,
            deletedBy = null,
            deletedAtEpochMs = null,
            deleteReason = null,
            updatedAtEpochMs = now,
            pendingRemoteSync = true
        )
        val cur = _state.value
        val merged = RepairEquipmentCodec.mergeItems(
            localItems = cur.repairItems.filterNot { it.id == restored.id } + restored,
            remoteEvents = emptyList()
        )
        _state.update {
            it.copy(
                repairItems = merged,
                statusBanner = "Позиция ${item.intakeNumber} восстановлена из «Удалённые»"
            )
        }
        persistCurrentState()
        pushRepairEntryRemote(restored)
    }

    private fun pushRepairEntryRemote(item: RepairEquipmentItem) {
        val cur = _state.value
        if (!cur.settings.isConfigured) return
        scope.launch {
            val roomKey = apiClient.resolveOrCreateRepairRegistryRoom(
                settings = cur.settings,
                filterSettings = cur.filterSettings,
                cachedRoomKey = _state.value.repairRoomKey
            ).getOrNull() ?: return@launch

            if (roomKey != _state.value.repairRoomKey) {
                _state.update { it.copy(repairRoomKey = roomKey) }
            }
            apiClient.postRepairRegistryEntry(roomKey, item, cur.settings)
            persistCurrentState()
        }
    }

    fun cleanupSystemRoomsInJira(silentIfZero: Boolean = false) {
        val cur = _state.value
        if (!cur.settings.isConfigured || cur.isCleaningSystemRooms) return
        _state.update {
            it.copy(
                isCleaningSystemRooms = true,
                statusBanner = if (!silentIfZero) "Поиск и очистка служебных заявок [HELPCSL_*_ROOM] в Jira…" else it.statusBanner
            )
        }
        scope.launch {
            apiClient.cleanupSystemRoomIssues(cur.settings, cur.filterSettings)
                .onSuccess { report ->
                    systemRoomsAutoCleanedV70 = true
                    val showMsg = !silentIfZero || report.cleanedCount > 0 || report.failedCount > 0
                    _state.update { st ->
                        st.copy(
                            isCleaningSystemRooms = false,
                            chatRoomKey = if (st.settings.adminChatIssueKey.isBlank()) "" else st.chatRoomKey,
                            repairRoomKey = if (st.settings.adminChatIssueKey.isBlank()) "" else st.repairRoomKey,
                            statusBanner = if (showMsg) report.formatSummaryMessage() else st.statusBanner
                        )
                    }
                    persistCurrentState()
                    if (report.cleanedCount > 0) {
                        refreshTickets(silent = true)
                    }
                }
                .onFailure { err ->
                    _state.update { st ->
                        st.copy(
                            isCleaningSystemRooms = false,
                            errorBanner = if (!silentIfZero) "Ошибка очистки служебных заявок: ${err.message}" else st.errorBanner
                        )
                    }
                }
        }
    }

    fun syncRepairRegistry(silent: Boolean = false) {
        val cur = _state.value
        if (!cur.settings.isConfigured) return
        if (cur.settings.adminChatIssueKey.isBlank()) {
            if (cur.repairRoomKey.isNotEmpty() || cur.isSyncingRepair) {
                _state.update { it.copy(repairRoomKey = "", isSyncingRepair = false) }
                persistCurrentState()
            }
            return
        }
        _state.update { it.copy(isSyncingRepair = true) }
        scope.launch {
            apiClient.resolveOrCreateRepairRegistryRoom(
                settings = cur.settings,
                filterSettings = cur.filterSettings,
                cachedRoomKey = cur.repairRoomKey
            ).onSuccess { roomKey ->
                apiClient.fetchRepairRegistryRemoteEntries(roomKey, cur.settings)
                    .onSuccess { remoteItems ->
                        val remotePassports = apiClient.fetchClientTechPassports(roomKey, cur.settings)
                            .getOrDefault(emptyList())
                        val merged = RepairEquipmentCodec.mergeItems(_state.value.repairItems, remoteItems)
                        val mergedPassports = ClientTechPassportCodec.mergePassports(
                            localMap = _state.value.clientPassports,
                            remoteEvents = remotePassports
                        )
                        val repairClients = merged.mapNotNull { r ->
                            if (r.isDeleted || r.clientName.isBlank()) null
                            else ClientDirectoryEntry(
                                name = r.clientName,
                                phone = r.clientPhone.ifBlank { null },
                                source = "В ремонт",
                                lastSeenEpochMs = r.updatedAtEpochMs
                            )
                        }
                        val grownClients = ClientDirectoryCodec.mergeEntries(
                            existing = _state.value.clientsDirectory,
                            incoming = repairClients,
                            filterSettings = _state.value.filterSettings
                        )
                        _state.update {
                            it.copy(
                                repairRoomKey = roomKey,
                                repairItems = merged,
                                clientPassports = mergedPassports,
                                clientsDirectory = grownClients,
                                isSyncingRepair = false,
                                statusBanner = if (!silent) "Журнал «В ремонт» и техпаспорта синхронизированы ($roomKey)" else it.statusBanner
                            )
                        }
                        persistCurrentState()
                    }
                    .onFailure { _state.update { it.copy(isSyncingRepair = false) } }
            }.onFailure { _state.update { it.copy(isSyncingRepair = false) } }
        }
    }

    fun refreshClientsDirectory(silent: Boolean = false) {
        val cur = _state.value
        if (!cur.settings.isConfigured) return
        _state.update { it.copy(isLoadingClientsDirectory = true) }
        scope.launch {
            apiClient.fetchHelpdeskClientsDirectory(cur.settings, cur.filterSettings)
                .onSuccess { remoteClients ->
                    val merged = ClientDirectoryCodec.mergeEntries(
                        existing = _state.value.clientsDirectory,
                        incoming = remoteClients,
                        filterSettings = _state.value.filterSettings
                    )
                    _state.update {
                        it.copy(
                            clientsDirectory = merged,
                            isLoadingClientsDirectory = false,
                            statusBanner = if (!silent) "Справочник клиентов Helpdesk обновлён (${merged.size} орг./клиентов)" else it.statusBanner
                        )
                    }
                    persistCurrentState()
                }
                .onFailure { err ->
                    _state.update {
                        it.copy(
                            isLoadingClientsDirectory = false,
                            errorBanner = if (!silent) "Не удалось загрузить клиентов: ${err.message}" else it.errorBanner
                        )
                    }
                }
        }
    }

    fun printOrOpenRepairReceiptPdf(item: RepairEquipmentItem, attachToTicket: Boolean = false) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                val resolvedEngineer = ensureEngineerHumanName(item.assignedEngineer)
                val cleanItem = if (item.assignedEngineer != resolvedEngineer) {
                    item.copy(assignedEngineer = resolvedEngineer)
                } else {
                    item
                }
                val pdf = DesktopPdfGenerator.generateRepairReceiptPdf(cleanItem, resolvedEngineer)
                WindowsExternalLaunchers.openFile(pdf)
                if (attachToTicket && item.linkedTicketKey.isNotBlank() && _state.value.settings.isConfigured) {
                    val cur = _state.value
                    apiClient.uploadIssueAttachment(
                        issueKey = item.linkedTicketKey,
                        fileName = pdf.name,
                        mimeType = "application/pdf",
                        fileBytes = pdf.readBytes(),
                        settings = cur.settings,
                        filterSettings = cur.filterSettings
                    )
                }
                pdf
            }.onSuccess { pdf ->
                _state.update {
                    it.copy(statusBanner = "Сформирована и открыта квитанция PDF: ${pdf.name}")
                }
            }.onFailure { err ->
                _state.update { it.copy(errorBanner = "Ошибка генерации PDF: ${err.message}") }
            }
        }
    }

    fun generateAndAttachActPdf(
        ticket: HelpdeskTicket,
        workSummary: String,
        partsUsed: String,
        timeSpentLabel: String,
        attachToJira: Boolean
    ) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                val engineer = ensureEngineerHumanName(ticket.assigneeName ?: ticket.assigneeLogin)
                val pdf = DesktopPdfGenerator.generateTicketServiceActPdf(
                    ticket = ticket,
                    engineerName = engineer,
                    performedWorks = listOf(
                        workSummary,
                        "Материалы/комплектующие: $partsUsed",
                        "Затраченное время: $timeSpentLabel"
                    ),
                    extraNotes = "",
                    serialNumber = ""
                )
                WindowsExternalLaunchers.openFile(pdf)
                if (attachToJira && _state.value.settings.isConfigured) {
                    val cur = _state.value
                    apiClient.uploadIssueAttachment(
                        issueKey = ticket.key,
                        fileName = pdf.name,
                        mimeType = "application/pdf",
                        fileBytes = pdf.readBytes(),
                        settings = cur.settings,
                        filterSettings = cur.filterSettings
                    ).getOrThrow()?.let { replaceTicketInMemory(it) }
                }
                pdf
            }.onSuccess { pdf ->
                _state.update { it.copy(statusBanner = "Акт выполненных работ сформирован: ${pdf.name}") }
            }.onFailure { err ->
                _state.update { it.copy(errorBanner = "Ошибка генерации акта PDF: ${err.message}") }
            }
        }
    }

    // =========================================================================
    //  Internal Engineers «Чат»
    // =========================================================================

    fun discussTicketInChat(ticket: HelpdeskTicket) {
        _state.update {
            it.copy(
                activeTab = DesktopMainTab.CHAT,
                unreadChatCount = 0,
                pendingChatDraft = "[${ticket.key}] ${ticket.reporterName}: ${ticket.summary} — "
            )
        }
        refreshChat(silent = true)
    }

    fun consumePendingChatDraft() {
        _state.update { it.copy(pendingChatDraft = "") }
    }

    fun refreshChat(silent: Boolean = false) {
        val cur = _state.value
        if (!cur.settings.isConfigured) return
        if (cur.settings.adminChatIssueKey.isBlank()) {
            if (cur.chatRoomKey.isNotEmpty() || cur.isSyncingChat) {
                _state.update { it.copy(chatRoomKey = "", isSyncingChat = false) }
                persistCurrentState()
            }
            return
        }
        _state.update { it.copy(isSyncingChat = true) }
        scope.launch {
            apiClient.resolveOrCreateAdminChatRoom(
                settings = cur.settings,
                filterSettings = cur.filterSettings,
                cachedRoomKey = cur.chatRoomKey
            ).onSuccess { roomKey ->
                apiClient.fetchAdminChatRoomSnapshot(roomKey, cur.settings)
                    .onSuccess { snap ->
                        val incomingIds = snap.messages.map { it.id }.toSet()
                        val unseenForeign = if (seenChatIds.isEmpty()) {
                            emptyList()
                        } else {
                            snap.messages.filter { it.id !in seenChatIds && !it.isOwnMessage }
                        }
                        seenChatIds = seenChatIds + incomingIds

                        _state.update { st ->
                            val newUnread = if (st.activeTab == DesktopMainTab.CHAT) {
                                0
                            } else {
                                st.unreadChatCount + unseenForeign.size
                            }
                            st.copy(
                                chatRoomKey = roomKey,
                                chatMessages = snap.messages,
                                chatPinnedNote = snap.pinnedNote,
                                unreadChatCount = newUnread,
                                isSyncingChat = false
                            )
                        }
                        persistCurrentState()
                        if (unseenForeign.isNotEmpty()) {
                            val curSt = _state.value
                            val mentionedMsg = unseenForeign.lastOrNull { msg ->
                                AdminChatCodec.isUserMentioned(
                                    mentionedHandles = msg.mentionedHandles,
                                    displayName = curSt.connectedUserName,
                                    usernameOrEmail = curSt.settings.usernameOrEmail
                                )
                            }
                            if (mentionedMsg != null) {
                                emitDesktopAlert(
                                    alert = DesktopToastAlert(
                                        id = "chat-mention-${mentionedMsg.id}",
                                        title = "📣 Вас упомянули в чате · ${mentionedMsg.authorDisplayName}",
                                        subtitle = mentionedMsg.text.ifBlank { "📎 ${mentionedMsg.attachmentName.orEmpty()}" }.take(120),
                                        ticketKey = null
                                    ),
                                    playSound = true
                                )
                            } else if (
                                curSt.activeTab != DesktopMainTab.CHAT &&
                                curSt.settings.adminChatNotificationsEnabled &&
                                isDesktopAlertAllowedNow(curSt.settings, curSt.respectWorkScheduleOnDesktop)
                            ) {
                                val lastMsg = unseenForeign.last()
                                emitDesktopAlert(
                                    alert = DesktopToastAlert(
                                        id = "chat-${lastMsg.id}",
                                        title = "💬 Чат инженеров · ${lastMsg.authorDisplayName}",
                                        subtitle = lastMsg.text.ifBlank { "📎 ${lastMsg.attachmentName.orEmpty()}" }.take(120),
                                        ticketKey = null
                                    ),
                                    playSound = true
                                )
                            }
                        }
                    }
                    .onFailure { _state.update { it.copy(isSyncingChat = false) } }
            }.onFailure { _state.update { it.copy(isSyncingChat = false) } }
        }
    }

    fun sendChatMessage(text: String, replyAuthor: String? = null, replyPreview: String? = null) {
        val cur = _state.value
        if (text.isBlank() || !cur.settings.isConfigured) return
        scope.launch {
            val roomKey = apiClient.resolveOrCreateAdminChatRoom(
                cur.settings,
                cur.filterSettings,
                cur.chatRoomKey
            ).getOrElse { err ->
                _state.update { it.copy(errorBanner = "Не удалось открыть комнату чата: ${err.message}") }
                return@launch
            }
            apiClient.sendAdminChatMessage(
                roomIssueKey = roomKey,
                rawText = text.trim(),
                replyToAuthor = replyAuthor,
                replyToPreview = replyPreview,
                settings = cur.settings
            ).onSuccess { msg ->
                seenChatIds = seenChatIds + msg.id
                _state.update { st ->
                    st.copy(chatRoomKey = roomKey, chatMessages = st.chatMessages + msg)
                }
                persistCurrentState()
            }.onFailure { err ->
                _state.update { it.copy(errorBanner = "Ошибка отправки в чат: ${err.message}") }
            }
        }
    }

    fun deleteChatMessage(messageId: String) {
        val cur = _state.value
        if (cur.chatRoomKey.isBlank()) return
        scope.launch {
            apiClient.deleteAdminChatMessage(cur.chatRoomKey, messageId, cur.settings).onSuccess {
                _state.update { st ->
                    st.copy(chatMessages = st.chatMessages.filterNot { it.id == messageId })
                }
            }
        }
    }

    fun savePinnedShiftNote(note: String) {
        val cur = _state.value
        if (!cur.settings.isConfigured) return
        scope.launch {
            val roomKey = apiClient.resolveOrCreateAdminChatRoom(
                cur.settings,
                cur.filterSettings,
                cur.chatRoomKey
            ).getOrNull() ?: return@launch
            apiClient.updateAdminChatPinnedNote(roomKey, note, cur.settings).onSuccess { saved ->
                _state.update {
                    it.copy(
                        chatRoomKey = roomKey,
                        chatPinnedNote = saved,
                        statusBanner = "Закреп дежурной смены обновлён"
                    )
                }
                persistCurrentState()
            }
        }
    }

    // =========================================================================
    //  QR Settings Import/Export & Windows `setup.exe` Auto-Update
    // =========================================================================

    fun importQrPayload(rawPayload: String, pinCode: String = "") {
        DesktopQrTransferCodec.decode(rawPayload, pinCode)
            .onSuccess { bundle ->
                val finalSettings = if (bundle.includesCredentials) {
                    bundle.appSettings
                } else {
                    bundle.appSettings.copy(passwordOrToken = _state.value.settings.passwordOrToken)
                }
                _state.update {
                    it.copy(
                        settings = finalSettings,
                        filterSettings = bundle.filterSettings,
                        statusBanner = "Профиль настроек успешно импортирован из кода HELPCSL1"
                    )
                }
                persistCurrentState()
                if (finalSettings.isConfigured) {
                    refreshTickets(silent = false)
                }
            }
            .onFailure { err ->
                _state.update { it.copy(errorBanner = "Ошибка импорта QR-кода: ${err.message}") }
            }
    }

    fun checkUpdates(manual: Boolean = true) {
        _state.update { it.copy(isCheckingUpdate = true) }
        scope.launch {
            DesktopUpdateManager.checkForWindowsUpdate(currentVersionName = currentVersion)
                .onSuccess { info ->
                    _state.update {
                        it.copy(
                            isCheckingUpdate = false,
                            updateInfo = info,
                            statusBanner = if (manual) {
                                if (info != null) "Доступна новая версия Windows: v${info.versionName}"
                                else "У вас установлена актуальная версия Help-CSL v$currentVersion"
                            } else it.statusBanner
                        )
                    }
                }
                .onFailure { err ->
                    _state.update {
                        it.copy(
                            isCheckingUpdate = false,
                            errorBanner = if (manual) "Проверка обновлений: ${err.message}" else it.errorBanner
                        )
                    }
                }
        }
    }

    fun downloadAndRunWindowsSetup(info: DesktopReleaseUpdate) {
        _state.update { it.copy(updateDownloadProgress = 0) }
        scope.launch {
            DesktopUpdateManager.downloadAndLaunchSetupExe(info) { downloaded, total ->
                val pct = if (total > 0L) ((downloaded * 100L) / total).toInt().coerceIn(0, 100) else 50
                _state.update { it.copy(updateDownloadProgress = pct) }
            }.onSuccess {
                _state.update {
                    it.copy(
                        updateDownloadProgress = null,
                        statusBanner = "Запущен установщик обновления setup.exe (v${info.versionName})"
                    )
                }
            }.onFailure { err ->
                _state.update {
                    it.copy(
                        updateDownloadProgress = null,
                        errorBanner = "Ошибка загрузки setup.exe: ${err.message}"
                    )
                }
            }
        }
    }

    fun bringWindowToFrontAndOpenTicket(ticketKey: String?) {
        dismissPopupAlert()
        if (ticketKey != null) {
            _state.update { it.copy(activeTab = DesktopMainTab.TICKETS) }
            selectTicket(ticketKey)
        } else {
            selectTab(DesktopMainTab.CHAT)
        }
        onBringWindowToFront?.invoke()
    }

    private fun replaceTicketInMemory(updated: HelpdeskTicket) {
        _state.update { st ->
            val prev = st.tickets.firstOrNull { it.key.equals(updated.key, ignoreCase = true) }
            val safeUpdated = if (
                updated.stage == com.helpdesk.jiraagent.model.TicketStage.CLOSED &&
                updated.resolvedEpochMillis <= 0L &&
                prev != null &&
                prev.resolvedEpochMillis > 0L
            ) {
                updated.copy(resolvedIso = prev.resolvedIso, resolvedEpochMillis = prev.resolvedEpochMillis)
            } else {
                updated
            }
            val exists = prev != null
            val nextList = if (exists) {
                st.tickets.map { if (it.key.equals(safeUpdated.key, ignoreCase = true)) safeUpdated else it }
            } else {
                listOf(safeUpdated) + st.tickets
            }
            st.copy(tickets = nextList)
        }
    }

    // =========================================================================
    //  v1.0.40: Attachments Cache, Drag & Drop, Stopwatch, Tech Passport,
    //  Knowledge Archive, QR Sticker, Chat Images, Toolbox & Confluence Wiki
    // =========================================================================

    suspend fun loadJiraAttachmentBytes(url: String): ByteArray? {
        if (url.isBlank()) return null
        jiraAttachmentMemoryCache[url]?.let { return it }
        val bytes = apiClient.downloadAttachmentBytes(url, _state.value.settings).getOrNull()
        if (bytes != null && bytes.size <= 15 * 1024 * 1024) {
            if (jiraAttachmentMemoryCache.size > 60) {
                jiraAttachmentMemoryCache.keys.firstOrNull()?.let { jiraAttachmentMemoryCache.remove(it) }
            }
            jiraAttachmentMemoryCache[url] = bytes
        }
        return bytes
    }

    suspend fun loadWikiImageBytes(url: String): ByteArray? {
        wikiOfflineStore.getCachedMediaBytes(url)?.let { return it }
        val bytes = wikiClient.downloadProtectedBytes(url, _state.value.settings).getOrNull()
        if (bytes != null) {
            wikiOfflineStore.saveCachedMediaBytes(url, bytes)
        }
        return bytes
    }

    fun uploadDroppedFiles(files: List<File>) {
        val valid = files.filter { it.isFile && it.exists() }
        if (valid.isEmpty()) return
        val cur = _state.value
        when (cur.activeTab) {
            DesktopMainTab.TICKETS -> {
                val key = cur.selectedTicketKey
                if (key.isNullOrBlank()) {
                    _state.update { it.copy(errorBanner = "Откройте заявку справа, чтобы перетащить в неё файлы") }
                    return
                }
                _state.update { it.copy(isPerformingAction = true) }
                scope.launch(Dispatchers.IO) {
                    var okCount = 0
                    for (file in valid) {
                        val mime = runCatching { Files.probeContentType(file.toPath()) }.getOrNull()
                            ?: "application/octet-stream"
                        apiClient.uploadIssueAttachment(
                            issueKey = key,
                            fileName = file.name,
                            mimeType = mime,
                            fileBytes = file.readBytes(),
                            settings = _state.value.settings,
                            filterSettings = _state.value.filterSettings
                        ).onSuccess { updated ->
                            updated?.let { replaceTicketInMemory(it) }
                            okCount++
                        }
                    }
                    _state.update {
                        it.copy(
                            isPerformingAction = false,
                            statusBanner = if (okCount > 0) "📎 Перетащено и прикреплено файлов к $key: $okCount" else it.statusBanner,
                            errorBanner = if (okCount == 0) "Не удалось загрузить перетащенные файлы" else null
                        )
                    }
                }
            }
            DesktopMainTab.CHAT -> {
                valid.forEach { file -> sendChatFileAttachment(file) }
            }
            else -> {
                _state.update { it.copy(statusBanner = "Перетаскивание файлов доступно во вкладках «Заявки» и «Чат»") }
            }
        }
    }

    // --- Live Ticket Stopwatch ---

    fun toggleTicketStopwatch(ticketKey: String) {
        _state.update { st ->
            val current = st.ticketStopwatches[ticketKey]
            val next = if (current?.isRunning == true) {
                TicketStopwatchTracker.pause(st.ticketStopwatches, ticketKey)
            } else {
                TicketStopwatchTracker.startOrResume(st.ticketStopwatches, ticketKey)
            }
            st.copy(ticketStopwatches = next)
        }
        persistCurrentState()
    }

    fun resetTicketStopwatch(ticketKey: String) {
        _state.update { st ->
            st.copy(ticketStopwatches = TicketStopwatchTracker.stopAndClear(st.ticketStopwatches, ticketKey))
        }
        persistCurrentState()
    }

    fun completeTicketWithOptionalWorklog(
        ticketKey: String,
        commentText: String?,
        worklogMinutes: Int?
    ) {
        val cur = _state.value
        scope.launch {
            if (worklogMinutes != null && worklogMinutes > 0 && cur.settings.isConfigured) {
                apiClient.addIssueWorklog(
                    issueKey = ticketKey,
                    timeSpentSeconds = worklogMinutes * 60,
                    commentText = commentText?.takeIf { it.isNotBlank() } ?: "Работы по заявке Helpdesk",
                    settings = cur.settings
                )
            }
            performTicketAction(
                ticketKey = ticketKey,
                action = TicketWorkflowAction.COMPLETE,
                commentText = commentText?.ifBlank { null }
            )
        }
    }

    // --- Synced Client Tech Passports ---

    fun getClientPassport(clientName: String): ClientTechPassport {
        val key = ClientTechPassportCodec.normalizeKey(clientName)
        return _state.value.clientPassports[key]
            ?: ClientTechPassport(clientKey = key, clientDisplayName = clientName.trim())
    }

    fun saveClientTechPassport(draft: ClientTechPassport) {
        val key = ClientTechPassportCodec.normalizeKey(draft.clientDisplayName.ifBlank { draft.clientKey })
        if (key.isBlank()) return
        val stamped = draft.copy(
            clientKey = key,
            updatedBy = currentEngineerDisplayName(),
            updatedAtEpochMs = System.currentTimeMillis()
        )
        _state.update { st ->
            val merged = ClientTechPassportCodec.mergePassports(st.clientPassports, listOf(stamped))
            st.copy(
                clientPassports = merged,
                statusBanner = "📋 Техпаспорт «${stamped.clientDisplayName}» сохранён и отправлен на синхронизацию"
            )
        }
        persistCurrentState()

        val cur = _state.value
        if (!cur.settings.isConfigured) return
        scope.launch {
            val roomKey = apiClient.resolveOrCreateRepairRegistryRoom(
                settings = cur.settings,
                filterSettings = cur.filterSettings,
                cachedRoomKey = _state.value.repairRoomKey
            ).getOrNull() ?: return@launch
            if (roomKey != _state.value.repairRoomKey) {
                _state.update { it.copy(repairRoomKey = roomKey) }
            }
            apiClient.postClientTechPassport(roomKey, stamped, cur.settings)
        }
    }

    // --- Knowledge Base Archive Search ---

    fun searchKnowledgeArchive(query: String, excludeTicketKey: String? = null) {
        val cur = _state.value
        if (query.isBlank() || !cur.settings.isConfigured) return
        _state.update { it.copy(isSearchingKnowledge = true) }
        scope.launch {
            apiClient.searchKnowledgeBaseArchive(
                rawQuery = query.trim(),
                excludeTicketKey = excludeTicketKey,
                settings = cur.settings,
                filterSettings = cur.filterSettings
            ).onSuccess { found ->
                _state.update { it.copy(isSearchingKnowledge = false, knowledgeArchiveResults = found) }
            }.onFailure { err ->
                _state.update {
                    it.copy(
                        isSearchingKnowledge = false,
                        errorBanner = "Ошибка поиска по базе решений: ${err.message}"
                    )
                }
            }
        }
    }

    // --- Repair QR Sticker (58x40 mm) & 1-Click Client Ready Notification ---

    fun printRepairQrSticker(item: RepairEquipmentItem) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                val pdf = DesktopPdfGenerator.generateRepairQrStickerPdf(item)
                WindowsExternalLaunchers.openFile(pdf)
                pdf
            }.onSuccess { pdf ->
                _state.update { it.copy(statusBanner = "🏷 QR-наклейка 58×40 мм сформирована: ${pdf.name}") }
            }.onFailure { err ->
                _state.update { it.copy(errorBanner = "Ошибка генерации QR-наклейки: ${err.message}") }
            }
        }
    }

    fun postRepairReadyToLinkedTicket(item: RepairEquipmentItem) {
        val cur = _state.value
        val key = item.linkedTicketKey.trim()
        if (key.isBlank() || !cur.settings.isConfigured) return
        val msg = RepairEquipmentCodec.buildClientReadyMessage(item)
        scope.launch {
            apiClient.postIssueComment(
                issueKey = key,
                commentText = msg,
                settings = cur.settings,
                filterSettings = cur.filterSettings,
                internalOnly = false
            ).onSuccess { updated ->
                updated?.let { replaceTicketInMemory(it) }
                _state.update { it.copy(statusBanner = "📣 Сообщение о готовности ${item.intakeNumber} отправлено клиенту в $key") }
            }.onFailure { err ->
                _state.update { it.copy(errorBanner = "Ошибка отправки в $key: ${err.message}") }
            }
        }
    }

    // --- Chat Clipboard Screenshot & File Upload ---

    fun sendChatClipboardScreenshot(
        caption: String = "",
        replyAuthor: String? = null,
        replyPreview: String? = null
    ): Boolean {
        val captured = WindowsExternalLaunchers.readClipboardImagePng() ?: return false
        val (fileName, pngBytes) = captured
        val cur = _state.value
        if (!cur.settings.isConfigured) return false
        _state.update { it.copy(isSyncingChat = true) }
        scope.launch(Dispatchers.IO) {
            val roomKey = apiClient.resolveOrCreateAdminChatRoom(
                cur.settings,
                cur.filterSettings,
                cur.chatRoomKey
            ).getOrElse { err ->
                _state.update { it.copy(isSyncingChat = false, errorBanner = "Ошибка комнаты чата: ${err.message}") }
                return@launch
            }
            apiClient.uploadIssueAttachment(
                issueKey = roomKey,
                fileName = fileName,
                mimeType = "image/png",
                fileBytes = pngBytes,
                settings = cur.settings,
                filterSettings = cur.filterSettings
            ).onSuccess { updatedRoomIssue ->
                val uploadedAtt = updatedRoomIssue?.attachments
                    ?.lastOrNull { it.filename.equals(fileName, ignoreCase = true) }
                    ?: updatedRoomIssue?.attachments?.lastOrNull()
                val attUrl = uploadedAtt?.contentUrl.orEmpty()
                if (attUrl.isNotBlank()) {
                    jiraAttachmentMemoryCache[attUrl] = pngBytes
                }
                apiClient.sendAdminChatMessage(
                    roomIssueKey = roomKey,
                    rawText = caption.trim(),
                    replyToAuthor = replyAuthor,
                    replyToPreview = replyPreview,
                    attachmentName = uploadedAtt?.filename ?: fileName,
                    attachmentUrl = attUrl.ifBlank { null },
                    settings = cur.settings
                ).onSuccess { msg ->
                    seenChatIds = seenChatIds + msg.id
                    _state.update { st ->
                        st.copy(
                            isSyncingChat = false,
                            chatRoomKey = roomKey,
                            chatMessages = st.chatMessages + msg,
                            statusBanner = "🖼 Скриншот отправлен в чат инженеров"
                        )
                    }
                    persistCurrentState()
                }.onFailure { err ->
                    _state.update { it.copy(isSyncingChat = false, errorBanner = "Ошибка чата: ${err.message}") }
                }
            }.onFailure { err ->
                _state.update { it.copy(isSyncingChat = false, errorBanner = "Не удалось загрузить скриншот: ${err.message}") }
            }
        }
        return true
    }

    fun sendChatFileAttachment(
        file: File,
        caption: String = "",
        replyAuthor: String? = null,
        replyPreview: String? = null
    ) {
        val cur = _state.value
        if (!file.exists() || !cur.settings.isConfigured) return
        _state.update { it.copy(isSyncingChat = true) }
        scope.launch(Dispatchers.IO) {
            val roomKey = apiClient.resolveOrCreateAdminChatRoom(
                cur.settings,
                cur.filterSettings,
                cur.chatRoomKey
            ).getOrElse { err ->
                _state.update { it.copy(isSyncingChat = false, errorBanner = "Ошибка комнаты чата: ${err.message}") }
                return@launch
            }
            val mime = runCatching { Files.probeContentType(file.toPath()) }.getOrNull() ?: "application/octet-stream"
            val bytes = file.readBytes()
            apiClient.uploadIssueAttachment(
                issueKey = roomKey,
                fileName = file.name,
                mimeType = mime,
                fileBytes = bytes,
                settings = cur.settings,
                filterSettings = cur.filterSettings
            ).onSuccess { updatedRoomIssue ->
                val uploadedAtt = updatedRoomIssue?.attachments
                    ?.lastOrNull { it.filename.equals(file.name, ignoreCase = true) }
                    ?: updatedRoomIssue?.attachments?.lastOrNull()
                val attUrl = uploadedAtt?.contentUrl.orEmpty()
                if (attUrl.isNotBlank() && bytes.size <= 10 * 1024 * 1024) {
                    jiraAttachmentMemoryCache[attUrl] = bytes
                }
                apiClient.sendAdminChatMessage(
                    roomIssueKey = roomKey,
                    rawText = caption.trim(),
                    replyToAuthor = replyAuthor,
                    replyToPreview = replyPreview,
                    attachmentName = uploadedAtt?.filename ?: file.name,
                    attachmentUrl = attUrl.ifBlank { null },
                    settings = cur.settings
                ).onSuccess { msg ->
                    seenChatIds = seenChatIds + msg.id
                    _state.update { st ->
                        st.copy(
                            isSyncingChat = false,
                            chatRoomKey = roomKey,
                            chatMessages = st.chatMessages + msg,
                            statusBanner = "📎 Файл «${file.name}» отправлен в чат"
                        )
                    }
                    persistCurrentState()
                }.onFailure { err ->
                    _state.update { it.copy(isSyncingChat = false, errorBanner = "Ошибка отправки в чат: ${err.message}") }
                }
            }.onFailure { err ->
                _state.update { it.copy(isSyncingChat = false, errorBanner = "Ошибка загрузки файла: ${err.message}") }
            }
        }
    }

    // --- Sysadmin Toolbox Watchlist ---

    fun addWatchlistHost(rawHost: String, label: String = "") {
        val clean = rawHost.trim()
        if (clean.isEmpty()) return
        val normKey = SysadminToolboxEngine.normalizeHostKey(clean)
        val cleanLabel = label.trim()
        _state.update { st ->
            val updatedHosts = if (st.watchlistHosts.any { SysadminToolboxEngine.normalizeHostKey(it) == normKey }) {
                st.watchlistHosts
            } else {
                st.watchlistHosts + clean
            }
            val updatedLabels = if (cleanLabel.isNotEmpty() && normKey.isNotEmpty()) {
                st.watchlistLabels + (normKey to cleanLabel)
            } else {
                st.watchlistLabels
            }
            st.copy(watchlistHosts = updatedHosts, watchlistLabels = updatedLabels)
        }
        persistCurrentState()
        refreshWatchlistHosts()
    }

    fun updateWatchlistHostLabel(host: String, label: String) {
        val normKey = SysadminToolboxEngine.normalizeHostKey(host)
        if (normKey.isEmpty()) return
        val cleanLabel = label.trim()
        _state.update { st ->
            val updated = st.watchlistLabels.toMutableMap()
            if (cleanLabel.isEmpty()) updated.remove(normKey) else updated[normKey] = cleanLabel
            st.copy(watchlistLabels = updated, statusBanner = "Подпись узла сохранена")
        }
        persistCurrentState()
    }

    fun removeWatchlistHost(host: String) {
        val normKey = SysadminToolboxEngine.normalizeHostKey(host)
        _state.update { st ->
            val updatedLabels = st.watchlistLabels.toMutableMap()
            updatedLabels.remove(normKey)
            st.copy(
                watchlistHosts = st.watchlistHosts.filterNot {
                    SysadminToolboxEngine.normalizeHostKey(it) == normKey || it.equals(host, ignoreCase = true)
                },
                watchlistLabels = updatedLabels,
                watchlistStatuses = st.watchlistStatuses.filterNot {
                    SysadminToolboxEngine.normalizeHostKey(it.host) == normKey || it.host.equals(host, ignoreCase = true)
                }
            )
        }
        persistCurrentState()
    }

    fun refreshWatchlistHosts() {
        val hosts = _state.value.watchlistHosts
        if (hosts.isEmpty()) return
        _state.update { it.copy(isCheckingWatchlist = true) }
        scope.launch {
            val statuses = SysadminToolboxEngine.checkWatchlistHosts(hosts)
            _state.update { it.copy(isCheckingWatchlist = false, watchlistStatuses = statuses) }
        }
    }

    // --- Shift Analytics & Employee KPI Monitoring ---

    fun refreshEmployeeKpi(daysBack: Int = 30) {
        val cur = _state.value
        if (!cur.settings.isConfigured || cur.isLoadingKpi) return
        _state.update { it.copy(isLoadingKpi = true) }
        scope.launch {
            apiClient.fetchEmployeeKpiTickets(
                daysBack = daysBack,
                settings = cur.settings,
                filterSettings = cur.filterSettings,
                maxTotal = 500
            ).onSuccess { list ->
                _state.update { st ->
                    val prevClosedTimestamps = st.tickets
                        .filter { it.resolvedEpochMillis > 0L }
                        .associateBy({ it.key.uppercase() }, { it.resolvedIso to it.resolvedEpochMillis })
                    val enriched = list.map { t ->
                        if (t.stage == TicketStage.CLOSED && t.resolvedEpochMillis <= 0L) {
                            val prev = prevClosedTimestamps[t.key.uppercase()]
                            if (prev != null) t.copy(resolvedIso = prev.first, resolvedEpochMillis = prev.second) else t
                        } else {
                            t
                        }
                    }
                    st.copy(
                        kpiSampleTickets = enriched,
                        isLoadingKpi = false
                    )
                }
            }.onFailure {
                _state.update { it.copy(isLoadingKpi = false) }
            }
        }
    }

    fun exportShiftAnalyticsPdf(summary: PeriodAnalyticsSummary) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                val pdf = DesktopPdfGenerator.generateShiftAnalyticsPdf(summary, currentEngineerDisplayName())
                WindowsExternalLaunchers.openFile(pdf)
                pdf
            }.onSuccess { pdf ->
                _state.update { it.copy(statusBanner = "📊 PDF-отчёт смены открыт: ${pdf.name}") }
            }.onFailure { err ->
                _state.update { it.copy(errorBanner = "Ошибка экспорта PDF: ${err.message}") }
            }
        }
    }

    fun exportShiftAnalyticsCsv(summary: PeriodAnalyticsSummary, targetFile: File) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                targetFile.writeBytes(ShiftAnalyticsCalculator.buildExcelCsvBytes(summary))
                WindowsExternalLaunchers.openFile(targetFile)
            }.onSuccess {
                _state.update { it.copy(statusBanner = "📊 Excel-отчёт сохранён: ${targetFile.name}") }
            }.onFailure { err ->
                _state.update { it.copy(errorBanner = "Ошибка сохранения CSV: ${err.message}") }
            }
        }
    }

    // --- Confluence Wiki Offline-First Engine (w.csl.by with daily sync + account revocation wipe) ---

    private val wikiPageDetailCache = java.util.concurrent.ConcurrentHashMap<String, ConfluencePageDetail>()

    private fun applyOfflineWikiSnapshotToUi(
        snapshot: ConfluenceOfflineSnapshot,
        spaceKeyOverride: String? = _state.value.selectedWikiSpaceKey
    ) {
        val effectiveSpace = spaceKeyOverride
            ?: snapshot.spaces.firstOrNull { it.key.equals("CSL", ignoreCase = true) }?.key
            ?: snapshot.spaces.firstOrNull { it.type == "global" }?.key
            ?: snapshot.spaces.firstOrNull()?.key
        val roots = wikiOfflineStore.getRootPagesForSpace(effectiveSpace)
        val autoSelected = _state.value.selectedWikiPage
            ?: roots.firstOrNull()?.let { wikiOfflineStore.getPageDetail(it.id) }
        _state.update { st ->
            st.copy(
                wikiSpaces = snapshot.spaces,
                selectedWikiSpaceKey = effectiveSpace,
                wikiPages = roots,
                wikiChildPagesByParent = snapshot.childPagesByParent,
                selectedWikiPage = autoSelected,
                wikiOfflineSyncedAtLabel = snapshot.syncedAtLabel,
                wikiOfflinePagesCount = snapshot.totalPagesCount,
                wikiError = null
            )
        }
    }

    fun loadWikiInitialIfNeeded() {
        scope.launch(Dispatchers.IO) {
            val snap = wikiOfflineStore.getSnapshot()
            if (snap != null && snap.pagesById.isNotEmpty()) {
                applyOfflineWikiSnapshotToUi(snap)
                if (_state.value.settings.isConfluenceReady && wikiOfflineStore.isDailySyncDue()) {
                    syncWikiOfflineDatabase(force = false)
                }
            } else if (!_state.value.isSyncingWikiOfflineDb && !_state.value.isLoadingWikiList) {
                syncWikiOfflineDatabase(force = true)
            }
        }
    }

    fun syncWikiOfflineDatabase(force: Boolean = true) {
        val cur = _state.value
        if (!cur.settings.isConfluenceReady) {
            if (!wikiOfflineStore.hasOfflineSnapshot()) {
                _state.update {
                    it.copy(
                        wikiError = "Укажите логин и пароль Jira во вкладке «Настройки», чтобы загрузить офлайн-базу Читалки (w.csl.by)."
                    )
                }
            }
            return
        }
        if (cur.isSyncingWikiOfflineDb) return
        if (!force && !wikiOfflineStore.isDailySyncDue()) return

        _state.update {
            it.copy(
                isSyncingWikiOfflineDb = true,
                isLoadingWikiList = !wikiOfflineStore.hasOfflineSnapshot(),
                wikiSyncProgressText = "Подключение к w.csl.by…",
                wikiSyncProgressFraction = 0.02f,
                wikiError = null
            )
        }
        scope.launch(Dispatchers.IO) {
            val outcome = wikiOfflineStore.syncFromServer(
                wikiClient = wikiClient,
                settings = cur.settings,
                onProgressDetail = { prog ->
                    _state.update {
                        it.copy(
                            wikiSyncProgressText = prog.message,
                            wikiSyncProgressFraction = prog.fraction
                        )
                    }
                }
            )
            when (outcome) {
                is ConfluenceSyncOutcome.Synced -> {
                    wikiPageDetailCache.clear()
                    applyOfflineWikiSnapshotToUi(outcome.snapshot)
                    _state.update {
                        it.copy(
                            isSyncingWikiOfflineDb = false,
                            isLoadingWikiList = false,
                            wikiSyncProgressText = null,
                            wikiSyncProgressFraction = 1f,
                            wikiError = null,
                            statusBanner = "✅ Офлайн-база w.csl.by загружена (${outcome.snapshot.totalPagesCount} стр.)"
                        )
                    }
                }
                is ConfluenceSyncOutcome.OfflineUsingCache -> {
                    outcome.snapshot?.let { applyOfflineWikiSnapshotToUi(it) }
                    _state.update {
                        it.copy(
                            isSyncingWikiOfflineDb = false,
                            isLoadingWikiList = false,
                            wikiSyncProgressText = null,
                            wikiSyncProgressFraction = 0f,
                            wikiError = if (outcome.snapshot != null) null else outcome.message
                        )
                    }
                }
                is ConfluenceSyncOutcome.RequiresInitialWeb2fa -> {
                    _state.update {
                        it.copy(
                            isSyncingWikiOfflineDb = false,
                            isLoadingWikiList = false,
                            wikiSyncProgressText = null,
                            wikiSyncProgressFraction = 0f,
                            wikiError = outcome.message
                        )
                    }
                }
                is ConfluenceSyncOutcome.AccountRevokedWipeTriggered -> {
                    wipeAllProgramDataOnAccountDisabled(outcome.reason)
                }
            }
        }
    }

    fun refreshWikiSpacesAndPages(spaceKeyOverride: String? = _state.value.selectedWikiSpaceKey) {
        if (spaceKeyOverride != null && wikiOfflineStore.hasOfflineSnapshot()) {
            val snap = wikiOfflineStore.getSnapshot()
            if (snap != null) {
                applyOfflineWikiSnapshotToUi(snap, spaceKeyOverride)
                return
            }
        }
        syncWikiOfflineDatabase(force = true)
    }

    fun selectWikiSpace(spaceKey: String?) {
        val snap = wikiOfflineStore.getSnapshot()
        if (snap != null && snap.pagesById.isNotEmpty()) {
            val roots = wikiOfflineStore.getRootPagesForSpace(spaceKey)
            val firstDetail = roots.firstOrNull()?.let { wikiOfflineStore.getPageDetail(it.id) }
            _state.update {
                it.copy(
                    selectedWikiSpaceKey = spaceKey,
                    wikiPages = roots,
                    selectedWikiPage = firstDetail,
                    wikiPageHistoryStack = emptyList(),
                    wikiSearchResults = null,
                    wikiSearchQuery = "",
                    wikiError = null
                )
            }
            return
        }
        _state.update {
            it.copy(
                selectedWikiSpaceKey = spaceKey,
                selectedWikiPage = null,
                wikiPageHistoryStack = emptyList(),
                wikiSearchResults = null,
                wikiSearchQuery = ""
            )
        }
        syncWikiOfflineDatabase(force = true)
    }

    fun searchWikiPages(query: String) {
        _state.update { it.copy(wikiSearchQuery = query) }
        val clean = query.trim()
        if (clean.isEmpty()) {
            _state.update { it.copy(wikiSearchResults = null) }
            return
        }
        if (wikiOfflineStore.hasOfflineSnapshot()) {
            scope.launch(Dispatchers.Default) {
                val found = wikiOfflineStore.searchOffline(clean, _state.value.selectedWikiSpaceKey)
                _state.update { it.copy(isLoadingWikiList = false, wikiSearchResults = found, wikiError = null) }
                if (found.isNotEmpty()) {
                    openWikiPage(found.first().id, resetHistory = true)
                }
            }
            return
        }
        val cur = _state.value
        if (!cur.settings.isConfluenceReady) return
        _state.update { it.copy(isLoadingWikiList = true, wikiError = null) }
        scope.launch {
            wikiClient.searchPages(
                rawQuery = clean,
                spaceKeyFilter = cur.selectedWikiSpaceKey,
                settings = cur.settings
            ).onSuccess { found ->
                _state.update { it.copy(isLoadingWikiList = false, wikiSearchResults = found, wikiError = null) }
                if (found.isNotEmpty()) {
                    openWikiPage(found.first().id, resetHistory = true)
                }
            }.onFailure { err ->
                _state.update {
                    it.copy(isLoadingWikiList = false, wikiError = err.message ?: "Ошибка поиска в Confluence")
                }
            }
        }
    }

    fun toggleWikiTreeExpand(pageId: String) {
        val cur = _state.value
        if (pageId in cur.wikiExpandedPageIds) {
            _state.update { it.copy(wikiExpandedPageIds = it.wikiExpandedPageIds - pageId) }
            return
        }
        val offlineChildren = wikiOfflineStore.getChildPages(pageId)
        _state.update {
            val nextChildren = if (offlineChildren.isNotEmpty()) {
                it.wikiChildPagesByParent + (pageId to offlineChildren)
            } else it.wikiChildPagesByParent
            it.copy(wikiExpandedPageIds = it.wikiExpandedPageIds + pageId, wikiChildPagesByParent = nextChildren)
        }
        if (offlineChildren.isEmpty() && !cur.wikiChildPagesByParent.containsKey(pageId) && cur.settings.isConfluenceReady) {
            scope.launch {
                wikiClient.fetchChildPages(pageId, _state.value.settings).onSuccess { children ->
                    _state.update { st ->
                        st.copy(wikiChildPagesByParent = st.wikiChildPagesByParent + (pageId to children))
                    }
                }
            }
        }
    }

    fun openWikiPage(
        pageId: String,
        replaceSiblingInStack: Boolean = false,
        resetHistory: Boolean = false
    ) {
        val cur = _state.value
        if (pageId.isBlank()) return
        if (cur.selectedWikiPage?.id == pageId) return

        val existingIdx = if (resetHistory) -1 else cur.wikiPageHistoryStack.indexOfFirst { it.id == pageId }
        if (existingIdx >= 0) {
            val target = cur.wikiPageHistoryStack[existingIdx]
            val trimmed = cur.wikiPageHistoryStack.subList(0, existingIdx)
            _state.update { it.copy(selectedWikiPage = target, wikiPageHistoryStack = trimmed, wikiError = null) }
            return
        }

        val nextHistory = when {
            resetHistory || cur.selectedWikiPage == null -> emptyList()
            replaceSiblingInStack -> cur.wikiPageHistoryStack
            else -> cur.wikiPageHistoryStack + cur.selectedWikiPage
        }

        val offlineDetail = wikiOfflineStore.getPageDetail(pageId) ?: wikiPageDetailCache[pageId]
        if (offlineDetail != null) {
            _state.update { st ->
                val nextChildren = if (offlineDetail.childPages.isNotEmpty()) {
                    st.wikiChildPagesByParent + (offlineDetail.id to offlineDetail.childPages)
                } else st.wikiChildPagesByParent
                val parentId = offlineDetail.breadcrumbs.lastOrNull()?.id
                val parentSiblings = if (!parentId.isNullOrBlank()) wikiOfflineStore.getChildPages(parentId) else emptyList()
                val withSiblings = if (!parentId.isNullOrBlank() && parentSiblings.isNotEmpty()) {
                    nextChildren + (parentId to parentSiblings)
                } else nextChildren
                st.copy(
                    selectedWikiPage = offlineDetail,
                    wikiPageHistoryStack = nextHistory,
                    wikiChildPagesByParent = withSiblings,
                    isLoadingWikiPage = false,
                    wikiError = null
                )
            }
            return
        }

        if (!cur.settings.isConfluenceReady) return
        _state.update { it.copy(isLoadingWikiPage = true, wikiError = null) }

        scope.launch {
            wikiClient.fetchPageDetail(pageId, cur.settings)
                .onSuccess { detail ->
                    wikiPageDetailCache[pageId] = detail
                    wikiOfflineStore.upsertPageDetailInCache(detail)
                    _state.update { st ->
                        val nextChildren = if (detail.childPages.isNotEmpty()) {
                            st.wikiChildPagesByParent + (detail.id to detail.childPages)
                        } else {
                            st.wikiChildPagesByParent
                        }
                        st.copy(
                            isLoadingWikiPage = false,
                            selectedWikiPage = detail,
                            wikiPageHistoryStack = nextHistory,
                            wikiChildPagesByParent = nextChildren,
                            wikiError = null
                        )
                    }
                }
                .onFailure { err ->
                    _state.update {
                        it.copy(
                            isLoadingWikiPage = false,
                            wikiError = err.message ?: "Не удалось загрузить страницу Confluence"
                        )
                    }
                }
        }
    }

    fun navigateBackWikiPage() {
        val cur = _state.value
        if (cur.wikiPageHistoryStack.isNotEmpty()) {
            val prev = cur.wikiPageHistoryStack.last()
            _state.update {
                it.copy(
                    selectedWikiPage = prev,
                    wikiPageHistoryStack = cur.wikiPageHistoryStack.dropLast(1),
                    wikiError = null
                )
            }
        }
    }

    fun openClientInWiki(clientName: String) {
        val clean = clientName.trim()
        _state.update {
            it.copy(
                activeTab = DesktopMainTab.WIKI,
                wikiSearchQuery = clean
            )
        }
        if (clean.isNotEmpty()) {
            searchWikiPages(clean)
        } else {
            loadWikiInitialIfNeeded()
        }
    }

    fun downloadAndOpenWikiAttachment(att: ConfluenceAttachment) {
        val cur = _state.value
        _state.update { it.copy(isPerformingAction = true) }
        scope.launch(Dispatchers.IO) {
            wikiClient.downloadProtectedBytes(att.downloadUrl, cur.settings)
                .onSuccess { bytes ->
                    val dir = File(System.getProperty("java.io.tmpdir"), "HelpCSL-Wiki").apply { mkdirs() }
                    val safeName = att.title.replace(Regex("""[\\/:*?"<>|]"""), "_").ifBlank { "wiki_file" }
                    val target = File(dir, safeName)
                    target.writeBytes(bytes)
                    WindowsExternalLaunchers.openFile(target)
                    _state.update { it.copy(isPerformingAction = false, statusBanner = "📘 Открыт файл из Wiki: $safeName") }
                }
                .onFailure { err ->
                    _state.update { it.copy(isPerformingAction = false, errorBanner = "Ошибка скачивания из Wiki: ${err.message}") }
                }
        }
    }

    fun openWikiInBrowserWithSso(url: String = "") {
        val cur = _state.value
        val rawBase = wikiClient.resolveConfiguredWikiDisplayUrl(cur.settings)
        val target = when {
            url.isNotBlank() -> url
            cur.selectedWikiPage != null -> cur.selectedWikiPage.webUrl
            cur.wikiSearchQuery.isNotBlank() ->
                "$rawBase/dosearchsite.action?queryString=${java.net.URLEncoder.encode(cur.wikiSearchQuery.trim(), "UTF-8")}"
            else -> rawBase
        }
        WindowsExternalLaunchers.openConfluenceWithAutoLogin(target, cur.settings)
    }

    fun openConfluencePatCreationPage() {
        val base = wikiClient.resolveConfiguredWikiDisplayUrl(_state.value.settings)
        WindowsExternalLaunchers.openUrl("$base/plugins/personalaccesstokens/usertokens.action")
    }

    fun saveConfluencePatToken(token: String) {
        val updated = _state.value.settings.copy(confluenceToken = token.trim())
        saveSettings(updated)
        wikiClient.clearSessionCache()
        syncWikiOfflineDatabase(force = true)
    }
}
