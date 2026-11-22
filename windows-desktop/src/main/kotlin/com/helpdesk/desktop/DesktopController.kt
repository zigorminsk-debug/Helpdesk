package com.helpdesk.desktop

import com.helpdesk.jiraagent.data.HabrArticleDetail
import com.helpdesk.jiraagent.data.HabrFeedCategory
import com.helpdesk.jiraagent.data.HabrNewsItem
import com.helpdesk.jiraagent.data.HabrNewsRepository
import com.helpdesk.jiraagent.data.JiraApiClient
import com.helpdesk.jiraagent.data.JiraAssignableUser
import com.helpdesk.jiraagent.data.JiraTransitionOption
import com.helpdesk.jiraagent.data.TicketWorkflowAction
import com.helpdesk.jiraagent.domain.BelarusWorkCalendar
import com.helpdesk.jiraagent.domain.EngineerNameResolver
import com.helpdesk.jiraagent.domain.HostDiagnosticReport
import com.helpdesk.jiraagent.domain.NetworkDiagnosticsTool
import com.helpdesk.jiraagent.model.AdminChatMessage
import com.helpdesk.jiraagent.model.AppSettings
import com.helpdesk.jiraagent.model.ClientDirectoryCodec
import com.helpdesk.jiraagent.model.ClientDirectoryEntry
import com.helpdesk.jiraagent.model.FilterSettings
import com.helpdesk.jiraagent.model.HelpdeskTicket
import com.helpdesk.jiraagent.model.PostponedTicket
import com.helpdesk.jiraagent.model.RepairEquipmentCodec
import com.helpdesk.jiraagent.model.RepairEquipmentItem
import com.helpdesk.jiraagent.model.RepairStatus
import com.helpdesk.jiraagent.model.TicketStage
import com.helpdesk.jiraagent.model.TtsReadoutMode
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

enum class DesktopMainTab(val title: String) {
    TICKETS("Заявки"),
    REPAIR("В ремонт"),
    CHAT("Чат"),
    FILTER("Фильтр"),
    SETTINGS("Настройки")
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
    val settings: AppSettings = AppSettings(),
    val filterSettings: FilterSettings = FilterSettings(),
    val minimizeToTray: Boolean = true,
    val tickets: List<HelpdeskTicket> = emptyList(),
    val postponedTickets: List<PostponedTicket> = emptyList(),
    val selectedTicketKey: String? = null,
    val isRefreshing: Boolean = false,
    val isPerformingAction: Boolean = false,
    val statusBanner: String? = null,
    val errorBanner: String? = null,
    val connectedUserName: String? = null,
    val lastSyncDisplay: String = "ещё не синхронизировано",
    val pendingTransitionPrompt: PendingTransitionPrompt? = null,

    // Client history & S/N search
    val clientHistoryForKey: String? = null,
    val clientHistoryTickets: List<HelpdeskTicket> = emptyList(),
    val isLoadingClientHistory: Boolean = false,
    val serialSearchResults: List<HelpdeskTicket> = emptyList(),
    val isSearchingSerial: Boolean = false,
    val diagnosticReport: HostDiagnosticReport? = null,
    val isRunningDiagnostics: Boolean = false,

    // Empty state GIF (#1 Roy & #7 Moss) + Habr.com RSS & built-in article reader
    val emptyGifIndex: Int = 0,
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
    private val storage = DesktopStorage()

    private val persisted = storage.loadState()
    private val notifiedTicketKeys = persisted.seenTicketKeys.toMutableSet()
    private var baselineInitialized: Boolean = persisted.baselineInitialized
    private var seenChatIds: Set<String> = persisted.cachedChatMessages.map { it.id }.toSet()

    private val _state = MutableStateFlow(
        DesktopUiState(
            settings = persisted.appSettings,
            filterSettings = persisted.filterSettings,
            minimizeToTray = persisted.minimizeToTray,
            connectedUserName = persisted.connectedUserName,
            tickets = persisted.cachedTickets,
            postponedTickets = persisted.postponedTickets,
            repairItems = persisted.repairItems,
            clientsDirectory = persisted.clientDirectory,
            repairRoomKey = persisted.resolvedRepairRoomKey,
            chatRoomKey = persisted.resolvedChatRoomKey,
            chatMessages = persisted.cachedChatMessages,
            chatPinnedNote = persisted.pinnedShiftNote
        )
    )
    val state: StateFlow<DesktopUiState> = _state.asStateFlow()

    private var pollingJob: Job? = null
    private var onBringWindowToFront: (() -> Unit)? = null

    init {
        loadHabrFeed(force = false)
        restartPollingLoop()
        if (persisted.appSettings.isConfigured) {
            scope.launch {
                refreshTickets(silent = true, isInitialBoot = true)
                syncRepairRegistry(silent = true)
                refreshClientsDirectory(silent = true)
                refreshChat(silent = true)
            }
        }
        if (persisted.appSettings.autoCheckUpdates) {
            scope.launch {
                delay(4_000L)
                checkUpdates(manual = false)
            }
        }
    }

    fun bindWindowActivator(activator: () -> Unit) {
        onBringWindowToFront = activator
    }

    fun setMinimizeToTray(enabled: Boolean) {
        _state.update { it.copy(minimizeToTray = enabled) }
        persistCurrentState()
    }

    fun selectTab(tab: DesktopMainTab) {
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
        } else if (tab == DesktopMainTab.CHAT && _state.value.settings.isConfigured) {
            refreshChat(silent = true)
        }
    }

    fun selectTicket(ticketKey: String?) {
        _state.update { st ->
            val previewFromHistory = if (ticketKey != null && st.tickets.none { it.key == ticketKey }) {
                st.clientHistoryTickets.firstOrNull { it.key == ticketKey }
                    ?: st.serialSearchResults.firstOrNull { it.key == ticketKey }
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
                isLoadingClientHistory = ticketKey != null && st.settings.isConfigured
            )
        }
        if (ticketKey != null && _state.value.settings.isConfigured) {
            scope.launch {
                val cur = _state.value
                apiClient.fetchTicketByKey(ticketKey, cur.settings, cur.filterSettings).onSuccess { fresh ->
                    replaceTicketInMemory(fresh)
                    if (fresh.reporterName.isNotBlank()) {
                        apiClient.fetchClientHistory(
                            reporterName = fresh.reporterName,
                            excludeTicketKey = fresh.key,
                            settings = cur.settings,
                            filterSettings = cur.filterSettings,
                            reporterLoginOrEmail = fresh.reporterLoginOrEmail
                        ).onSuccess { history ->
                            _state.update { st ->
                                if (st.selectedTicketKey == fresh.key) {
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
                                if (st.selectedTicketKey == fresh.key) {
                                    st.copy(isLoadingClientHistory = false)
                                } else st
                            }
                        }
                    } else {
                        _state.update { st ->
                            if (st.selectedTicketKey == fresh.key) {
                                st.copy(isLoadingClientHistory = false)
                            } else st
                        }
                    }
                }.onFailure {
                    _state.update { st ->
                        if (st.selectedTicketKey == ticketKey) {
                            st.copy(isLoadingClientHistory = false)
                        } else st
                    }
                }
            }
        }
    }

    fun cycleEmptyGif() {
        _state.update { it.copy(emptyGifIndex = (it.emptyGifIndex + 1) % 2) }
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
        _state.update { it.copy(activePopupAlert = null) }
    }

    private fun persistCurrentState() {
        val cur = _state.value
        scope.launch(Dispatchers.IO) {
            storage.saveState(
                DesktopPersistedState(
                    appSettings = cur.settings,
                    filterSettings = cur.filterSettings,
                    minimizeToTray = cur.minimizeToTray,
                    connectedUserName = cur.connectedUserName,
                    resolvedChatRoomKey = cur.chatRoomKey,
                    resolvedRepairRoomKey = cur.repairRoomKey,
                    pinnedShiftNote = cur.chatPinnedNote,
                    baselineInitialized = baselineInitialized,
                    seenTicketKeys = synchronized(notifiedTicketKeys) { notifiedTicketKeys.toSet() },
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

    private fun restartPollingLoop() {
        pollingJob?.cancel()
        pollingJob = scope.launch {
            while (isActive) {
                val intervalSec = _state.value.settings.pollIntervalSeconds.coerceIn(15, 900)
                delay(intervalSec * 1_000L)
                if (_state.value.settings.isConfigured && _state.value.settings.pollingEnabled) {
                    processDuePostponedTickets()
                    refreshTickets(silent = true, isInitialBoot = false)
                    syncRepairRegistry(silent = true)
                    refreshChat(silent = true)
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
                    synchronized(notifiedTicketKeys) {
                        if (!baselineInitialized || (isInitialBoot && notifiedTicketKeys.isEmpty())) {
                            fetched.forEach { notifiedTicketKeys.add(it.key) }
                            baselineInitialized = true
                        } else {
                            for (t in fetched) {
                                if (t.isClientTicket &&
                                    t.stage == TicketStage.NEW &&
                                    notifiedTicketKeys.add(t.key)
                                ) {
                                    freshNewClientTickets.add(t)
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
                        st.copy(
                            tickets = fetched,
                            clientsDirectory = grownDirectory,
                            isRefreshing = false,
                            lastSyncDisplay = "обновлено в $nowStr",
                            emptyGifIndex = if (fetched.none { it.isClientTicket && it.stage == TicketStage.NEW }) {
                                (st.emptyGifIndex + 1) % 2
                            } else st.emptyGifIndex
                        )
                    }
                    persistCurrentState()

                    if (freshNewClientTickets.isNotEmpty()) {
                        triggerNewTicketAlerts(freshNewClientTickets)
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

    private fun triggerNewTicketAlerts(freshTickets: List<HelpdeskTicket>) {
        val settings = _state.value.settings
        val schedule = BelarusWorkCalendar.evaluate(settings)
        if (!schedule.isAllowedNow) return

        val alert = if (settings.stormProtectionEnabled && freshTickets.size >= 4) {
            DesktopToastAlert(
                id = "storm-${System.currentTimeMillis()}",
                title = "⚡ Шторм заявок: +${freshTickets.size} новых",
                subtitle = freshTickets.take(3).joinToString(" · ") { "${it.reporterName} (${it.key})" },
                ticketKey = freshTickets.first().key,
                isStormSummary = true
            )
        } else {
            val top = freshTickets.first()
            DesktopToastAlert(
                id = "ticket-${top.key}-${System.currentTimeMillis()}",
                title = "🔔 ${top.reporterName.ifBlank { "Новая заявка" }} · ${top.key}",
                subtitle = top.summary,
                ticketKey = top.key,
                isStormSummary = false
            )
        }

        _state.update { it.copy(activePopupAlert = alert) }
        DesktopSoundPlayer.playAlert(settings.alertSoundPreset, settings.customAlertSoundUri)
        if (settings.ttsReadoutMode == TtsReadoutMode.ALWAYS) {
            val top = freshTickets.first()
            DesktopSoundPlayer.speakTicketSummaryWindows(top.reporterName, top.summary)
        }
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
                        st.copy(
                            isPerformingAction = false,
                            pendingTransitionPrompt = null,
                            selectedTicketKey = if (action == TicketWorkflowAction.COMPLETE || action == TicketWorkflowAction.REJECT) {
                                null
                            } else st.selectedTicketKey,
                            statusBanner = outcome.statusMessage
                        )
                    }
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

    fun syncRepairRegistry(silent: Boolean = false) {
        val cur = _state.value
        if (!cur.settings.isConfigured) return
        _state.update { it.copy(isSyncingRepair = true) }
        scope.launch {
            apiClient.resolveOrCreateRepairRegistryRoom(
                settings = cur.settings,
                filterSettings = cur.filterSettings,
                cachedRoomKey = cur.repairRoomKey
            ).onSuccess { roomKey ->
                apiClient.fetchRepairRegistryRemoteEntries(roomKey, cur.settings)
                    .onSuccess { remoteItems ->
                        val merged = RepairEquipmentCodec.mergeItems(_state.value.repairItems, remoteItems)
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
                                clientsDirectory = grownClients,
                                isSyncingRepair = false,
                                statusBanner = if (!silent) "Журнал «В ремонт» синхронизирован ($roomKey)" else it.statusBanner
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
                        if (unseenForeign.isNotEmpty() &&
                            _state.value.activeTab != DesktopMainTab.CHAT &&
                            _state.value.settings.adminChatNotificationsEnabled
                        ) {
                            val lastMsg = unseenForeign.last()
                            if (BelarusWorkCalendar.evaluate(_state.value.settings).isAllowedNow) {
                                DesktopSoundPlayer.playAlert(_state.value.settings.alertSoundPreset)
                            }
                            _state.update {
                                it.copy(
                                    activePopupAlert = DesktopToastAlert(
                                        id = "chat-${lastMsg.id}",
                                        title = "💬 Чат инженеров · ${lastMsg.authorDisplayName}",
                                        subtitle = lastMsg.text.take(120),
                                        ticketKey = null
                                    )
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
            val exists = st.tickets.any { it.key == updated.key }
            val nextList = if (exists) {
                st.tickets.map { if (it.key == updated.key) updated else it }
            } else {
                listOf(updated) + st.tickets
            }
            st.copy(tickets = nextList)
        }
    }
}
