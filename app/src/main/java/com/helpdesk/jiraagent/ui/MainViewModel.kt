package com.helpdesk.jiraagent.ui

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.LruCache
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.helpdesk.jiraagent.data.InAppBannerAlert
import com.helpdesk.jiraagent.data.JiraApiClient
import com.helpdesk.jiraagent.data.JiraApiException
import com.helpdesk.jiraagent.data.JiraAssignableUser
import com.helpdesk.jiraagent.data.JiraTransitionOption
import com.helpdesk.jiraagent.data.PendingCallSession
import com.helpdesk.jiraagent.data.SettingsRepository
import com.helpdesk.jiraagent.data.TicketWorkflowAction
import com.helpdesk.jiraagent.domain.BelarusWorkCalendar
import com.helpdesk.jiraagent.domain.HostDiagnosticReport
import com.helpdesk.jiraagent.domain.NetworkDiagnosticsTool
import com.helpdesk.jiraagent.domain.PhoneNumberNormalizer
import com.helpdesk.jiraagent.domain.SettingsTransferCodec
import com.helpdesk.jiraagent.util.PdfServiceActGenerator
import com.helpdesk.jiraagent.util.ServiceActInput
import com.helpdesk.jiraagent.model.AdminChatMessage
import com.helpdesk.jiraagent.model.AppSettings
import com.helpdesk.jiraagent.model.ClientDirectoryEntry
import com.helpdesk.jiraagent.model.FilterSettings
import com.helpdesk.jiraagent.model.HelpdeskTicket
import com.helpdesk.jiraagent.model.OfflineQueuedAction
import com.helpdesk.jiraagent.model.PhoneMatch
import com.helpdesk.jiraagent.model.PostponedTicket
import com.helpdesk.jiraagent.model.QueuedActionType
import com.helpdesk.jiraagent.model.RepairEquipmentItem
import com.helpdesk.jiraagent.model.RepairStatus
import com.helpdesk.jiraagent.model.SyncStatus
import com.helpdesk.jiraagent.model.TicketAttachment
import com.helpdesk.jiraagent.model.TicketComment
import com.helpdesk.jiraagent.notification.NotificationHelper
import com.helpdesk.jiraagent.service.JiraMonitorService
import com.helpdesk.jiraagent.service.OfflineQueueProcessor
import com.helpdesk.jiraagent.service.PostponeScheduler
import com.helpdesk.jiraagent.update.AppUpdateManager
import com.helpdesk.jiraagent.update.GitHubReleaseUpdate
import com.helpdesk.jiraagent.update.InstallLaunchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant

data class ConnectionTestState(
    val isTesting: Boolean = false,
    val isSuccess: Boolean? = null,
    val message: String? = null
)

data class AppUpdateUiState(
    val isChecking: Boolean = false,
    val availableUpdate: GitHubReleaseUpdate? = null,
    val bannerDismissed: Boolean = false,
    val isDownloading: Boolean = false,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val readyApkPath: String? = null,
    val needsInstallPermission: Boolean = false,
    val statusMessage: String? = null,
    val isError: Boolean = false
)

data class TicketActionUiState(
    val isBusy: Boolean = false,
    val statusMessage: String? = null,
    val isError: Boolean = false,
    val manualTransitionPrompt: Boolean = false,
    val pendingAction: TicketWorkflowAction? = null,
    val pendingComment: String? = null,
    val pendingPostponeAtMillis: Long? = null,
    val pendingPostponeDisplay: String? = null,
    val availableTransitions: List<JiraTransitionOption> = emptyList()
)

data class ClientHistoryUiState(
    val isLoading: Boolean = false,
    val forTicketKey: String = "",
    val items: List<HelpdeskTicket> = emptyList()
)

data class HostDiagnosticUiState(
    val isRunning: Boolean = false,
    val targetHost: String = "",
    val report: HostDiagnosticReport? = null
)

data class AdminChatUiState(
    val isLoading: Boolean = false,
    val isSending: Boolean = false,
    val isSavingNote: Boolean = false,
    val errorMessage: String? = null,
    val draftText: String = "",
    val replyTo: AdminChatMessage? = null
)

data class SerialSearchUiState(
    val isSearching: Boolean = false,
    val serialQuery: String = "",
    val results: List<HelpdeskTicket> = emptyList(),
    val errorMessage: String? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val repository = SettingsRepository.getInstance(appContext)
    private val apiClient = JiraApiClient()

    val appSettings: StateFlow<AppSettings> = repository.appSettings
    val filterSettings: StateFlow<FilterSettings> = repository.filterSettings
    val tickets: StateFlow<List<HelpdeskTicket>> = repository.tickets
    val postponedTickets: StateFlow<List<PostponedTicket>> = repository.postponedTickets
    val syncStatus: StateFlow<SyncStatus> = repository.syncStatus
    val latestInAppBanner: StateFlow<InAppBannerAlert?> = repository.latestInAppBanner
    val offlineQueuedActions: StateFlow<List<OfflineQueuedAction>> = repository.offlineQueuedActions
    val clientDossierNotes: StateFlow<Map<String, String>> = repository.clientDossierNotes
    val pendingCallSession: StateFlow<PendingCallSession?> = repository.pendingCallSession
    val repairItems: StateFlow<List<RepairEquipmentItem>> = repository.repairItems
    val clientDirectory: StateFlow<List<ClientDirectoryEntry>> = repository.clientDirectory

    private val _isLoadingClientDirectory = MutableStateFlow(false)
    val isLoadingClientDirectory: StateFlow<Boolean> = _isLoadingClientDirectory.asStateFlow()

    private val _isSyncingRepair = MutableStateFlow(false)
    val isSyncingRepair: StateFlow<Boolean> = _isSyncingRepair.asStateFlow()

    private val _repairSyncError = MutableStateFlow<String?>(null)
    val repairSyncError: StateFlow<String?> = _repairSyncError.asStateFlow()

    private val _pendingRepairDraftFromTicket = MutableStateFlow<RepairEquipmentItem?>(null)
    val pendingRepairDraftFromTicket: StateFlow<RepairEquipmentItem?> = _pendingRepairDraftFromTicket.asStateFlow()

    private val _openRepairTabStamp = MutableStateFlow(0L)
    val openRepairTabStamp: StateFlow<Long> = _openRepairTabStamp.asStateFlow()

    private val _assignableColleagues = MutableStateFlow<List<JiraAssignableUser>>(emptyList())
    val assignableColleagues: StateFlow<List<JiraAssignableUser>> = _assignableColleagues.asStateFlow()

    private val _isLoadingColleagues = MutableStateFlow(false)
    val isLoadingColleagues: StateFlow<Boolean> = _isLoadingColleagues.asStateFlow()

    private val _serialSearchState = MutableStateFlow(SerialSearchUiState())
    val serialSearchState: StateFlow<SerialSearchUiState> = _serialSearchState.asStateFlow()

    private val _isBatchBusy = MutableStateFlow(false)
    val isBatchBusy: StateFlow<Boolean> = _isBatchBusy.asStateFlow()

    val chatMessages: StateFlow<List<AdminChatMessage>> = repository.chatMessages
    val unreadChatCount: StateFlow<Int> = repository.unreadChatCount
    val resolvedChatRoomKey: StateFlow<String> = repository.resolvedChatRoomKey
    val pinnedShiftNote: StateFlow<String> = repository.pinnedShiftNote

    private val attachmentByteCache = object : LruCache<String, ByteArray>(20 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ByteArray): Int = value.size.coerceAtLeast(1)
    }

    private val _clientHistoryState = MutableStateFlow(ClientHistoryUiState())
    val clientHistoryState: StateFlow<ClientHistoryUiState> = _clientHistoryState.asStateFlow()

    private val _hostDiagnosticState = MutableStateFlow(HostDiagnosticUiState())
    val hostDiagnosticState: StateFlow<HostDiagnosticUiState> = _hostDiagnosticState.asStateFlow()

    private val _adminChatUiState = MutableStateFlow(AdminChatUiState())
    val adminChatUiState: StateFlow<AdminChatUiState> = _adminChatUiState.asStateFlow()

    private val _openChatTabStamp = MutableStateFlow(0L)
    val openChatTabStamp: StateFlow<Long> = _openChatTabStamp.asStateFlow()

    private val _isCreatingTicket = MutableStateFlow(false)
    val isCreatingTicket: StateFlow<Boolean> = _isCreatingTicket.asStateFlow()

    private var liveChatPollJob: Job? = null

    fun dismissInAppBanner() {
        repository.dismissInAppBanner()
    }

    private val _selectedTicketKey = MutableStateFlow<String?>(null)
    val selectedTicketKey: StateFlow<String?> = _selectedTicketKey.asStateFlow()

    private val _returnToMainScreenStamp = MutableStateFlow(0L)
    val returnToMainScreenStamp: StateFlow<Long> = _returnToMainScreenStamp.asStateFlow()

    private fun navigateToMainScreen() {
        _selectedTicketKey.value = null
        _ticketActionState.value = TicketActionUiState()
        _returnToMainScreenStamp.value = System.currentTimeMillis()
    }

    private val _isLoadingTicketDetail = MutableStateFlow(false)
    val isLoadingTicketDetail: StateFlow<Boolean> = _isLoadingTicketDetail.asStateFlow()

    private val _ticketActionState = MutableStateFlow(TicketActionUiState())
    val ticketActionState: StateFlow<TicketActionUiState> = _ticketActionState.asStateFlow()

    private val _connectionTest = MutableStateFlow(ConnectionTestState())
    val connectionTest: StateFlow<ConnectionTestState> = _connectionTest.asStateFlow()

    private val _appUpdateState = MutableStateFlow(AppUpdateUiState())
    val appUpdateState: StateFlow<AppUpdateUiState> = _appUpdateState.asStateFlow()

    init {
        JiraMonitorService.syncServiceState(appContext)
        if (repository.appSettings.value.autoCheckUpdates) {
            checkForAppUpdate(manual = false)
        }
        if (repository.appSettings.value.isConfigured) {
            syncRepairRegistry()
            refreshClientsDirectory(manual = false)
        }
    }

    fun checkForAppUpdate(manual: Boolean, autoStartDownload: Boolean = false) {
        if (_appUpdateState.value.isChecking || _appUpdateState.value.isDownloading) return

        viewModelScope.launch {
            _appUpdateState.update {
                it.copy(
                    isChecking = true,
                    statusMessage = if (manual) "Проверка обновлений на GitHub…" else it.statusMessage,
                    isError = false
                )
            }

            AppUpdateManager.checkForUpdate().onSuccess { update ->
                if (update != null) {
                    _appUpdateState.update {
                        it.copy(
                            isChecking = false,
                            availableUpdate = update,
                            bannerDismissed = false,
                            statusMessage = "Найдена новая версия v${update.versionName}",
                            isError = false
                        )
                    }
                    if (autoStartDownload) {
                        downloadAndInstallAppUpdate()
                    }
                } else {
                    _appUpdateState.update {
                        it.copy(
                            isChecking = false,
                            availableUpdate = null,
                            statusMessage = if (manual) "Установлена актуальная версия программы" else null,
                            isError = false
                        )
                    }
                    if (manual) {
                        Toast.makeText(appContext, "У вас уже установлена последняя версия", Toast.LENGTH_SHORT).show()
                    }
                }
            }.onFailure { err ->
                _appUpdateState.update {
                    it.copy(
                        isChecking = false,
                        statusMessage = if (manual) {
                            "Не удалось проверить обновления: ${err.message ?: "ошибка сети"}"
                        } else {
                            null
                        },
                        isError = manual
                    )
                }
            }
        }
    }

    fun downloadAndInstallAppUpdate() {
        val update = _appUpdateState.value.availableUpdate ?: return
        if (_appUpdateState.value.isDownloading) return

        viewModelScope.launch {
            _appUpdateState.update {
                it.copy(
                    isDownloading = true,
                    downloadedBytes = 0L,
                    totalBytes = update.apkSizeBytes,
                    needsInstallPermission = false,
                    statusMessage = "Скачивание Help-CSL v${update.versionName}…",
                    isError = false
                )
            }

            AppUpdateManager.downloadUpdateApk(
                context = appContext,
                update = update,
                onProgress = { done, total ->
                    _appUpdateState.update {
                        it.copy(downloadedBytes = done, totalBytes = total)
                    }
                }
            ).onSuccess { apkFile ->
                val result = AppUpdateManager.launchApkInstaller(appContext, apkFile)
                when (result) {
                    InstallLaunchResult.LAUNCHED -> {
                        _appUpdateState.update {
                            it.copy(
                                isDownloading = false,
                                readyApkPath = apkFile.absolutePath,
                                needsInstallPermission = false,
                                statusMessage = "Подтвердите установку обновления в окне Android",
                                isError = false
                            )
                        }
                    }

                    InstallLaunchResult.NEEDS_INSTALL_PERMISSION -> {
                        _appUpdateState.update {
                            it.copy(
                                isDownloading = false,
                                readyApkPath = apkFile.absolutePath,
                                needsInstallPermission = true,
                                statusMessage = "Разрешите установку обновлений для Help-CSL и нажмите «Установить APK»",
                                isError = false
                            )
                        }
                        Toast.makeText(
                            appContext,
                            "Включите разрешение «Установка неизвестных приложений» для Help-CSL",
                            Toast.LENGTH_LONG
                        ).show()
                    }

                    InstallLaunchResult.ERROR -> {
                        _appUpdateState.update {
                            it.copy(
                                isDownloading = false,
                                readyApkPath = apkFile.absolutePath,
                                statusMessage = "Не удалось открыть системный установщик APK",
                                isError = true
                            )
                        }
                    }
                }
            }.onFailure { err ->
                _appUpdateState.update {
                    it.copy(
                        isDownloading = false,
                        statusMessage = err.message ?: "Ошибка скачивания обновления с GitHub",
                        isError = true
                    )
                }
            }
        }
    }

    fun installReadyUpdateApk() {
        val path = _appUpdateState.value.readyApkPath ?: return
        val file = File(path)
        if (!file.exists()) {
            downloadAndInstallAppUpdate()
            return
        }
        val result = AppUpdateManager.launchApkInstaller(appContext, file)
        if (result == InstallLaunchResult.LAUNCHED) {
            _appUpdateState.update {
                it.copy(
                    needsInstallPermission = false,
                    statusMessage = "Подтвердите установку обновления в окне Android",
                    isError = false
                )
            }
        }
    }

    fun dismissAppUpdateBanner() {
        _appUpdateState.update { it.copy(bannerDismissed = true) }
    }

    fun openTicket(ticketKey: String) {
        _selectedTicketKey.value = ticketKey
        _ticketActionState.value = TicketActionUiState()
        _hostDiagnosticState.value = HostDiagnosticUiState()
        val cached = tickets.value.firstOrNull { it.key.equals(ticketKey, ignoreCase = true) }
        if (cached != null) {
            loadClientHistoryFor(cached)
        } else {
            _clientHistoryState.value = ClientHistoryUiState(forTicketKey = ticketKey)
        }

        val settings = appSettings.value
        if (!settings.isConfigured || ticketKey.startsWith("DEMO-")) return

        viewModelScope.launch {
            _isLoadingTicketDetail.value = true
            apiClient.fetchTicketByKey(ticketKey, settings, filterSettings.value)
                .onSuccess { freshTicket ->
                    repository.upsertSingleTicket(freshTicket)
                    loadClientHistoryFor(freshTicket)
                }
            _isLoadingTicketDetail.value = false
        }
    }

    private fun loadClientHistoryFor(ticket: HelpdeskTicket) {
        val reporterClean = ticket.reporterName.trim()
        val localMatches = tickets.value
            .filter {
                !it.key.equals(ticket.key, ignoreCase = true) &&
                    it.isClientTicket &&
                    it.reporterName.equals(reporterClean, ignoreCase = true)
            }
            .sortedByDescending { it.createdEpochMillis }

        _clientHistoryState.value = ClientHistoryUiState(
            isLoading = appSettings.value.isConfigured && !ticket.key.startsWith("DEMO-"),
            forTicketKey = ticket.key,
            items = localMatches
        )

        val settings = appSettings.value
        if (!settings.isConfigured || ticket.key.startsWith("DEMO-") || reporterClean.length < 2) return

        viewModelScope.launch {
            apiClient.fetchClientHistory(
                reporterName = reporterClean,
                excludeTicketKey = ticket.key,
                settings = settings,
                filterSettings = filterSettings.value,
                reporterLoginOrEmail = ticket.reporterLoginOrEmail
            ).onSuccess { remoteItems ->
                val merged = (remoteItems + localMatches)
                    .distinctBy { it.key.uppercase() }
                    .sortedByDescending { it.createdEpochMillis }
                if (_selectedTicketKey.value.equals(ticket.key, ignoreCase = true)) {
                    _clientHistoryState.value = ClientHistoryUiState(
                        isLoading = false,
                        forTicketKey = ticket.key,
                        items = merged
                    )
                }
            }.onFailure {
                if (_selectedTicketKey.value.equals(ticket.key, ignoreCase = true)) {
                    _clientHistoryState.update { it.copy(isLoading = false) }
                }
            }
        }
    }

    fun closeTicketDetail() {
        _selectedTicketKey.value = null
        _ticketActionState.value = TicketActionUiState()
    }

    fun dismissTicketActionFeedback() {
        _ticketActionState.value = TicketActionUiState()
    }

    fun executeTicketAction(
        ticketKey: String,
        action: TicketWorkflowAction,
        commentText: String? = null,
        explicitTransitionId: String? = null
    ) {
        val settings = appSettings.value

        // Handle local DEMO tickets offline for quick UI verification
        if (ticketKey.startsWith("DEMO-")) {
            val existing = tickets.value.firstOrNull { it.key == ticketKey } ?: return
            val nowMillis = System.currentTimeMillis()
            val nowIso = Instant.ofEpochMilli(nowMillis).toString()
            val agentDisplay = syncStatus.value.connectedUser
                ?: settings.usernameOrEmail.ifBlank { "Текущий агент" }
            val updated = when (action) {
                TicketWorkflowAction.TAKE_IN_WORK -> existing.copy(
                    status = "В работе",
                    statusCategoryKey = "indeterminate",
                    assigneeName = agentDisplay,
                    updatedIso = nowIso,
                    updatedEpochMillis = nowMillis
                )
                TicketWorkflowAction.COMPLETE -> existing.copy(
                    status = "Выполнена",
                    statusCategoryKey = "done",
                    updatedIso = nowIso,
                    updatedEpochMillis = nowMillis
                )
                TicketWorkflowAction.REJECT -> existing.copy(
                    status = "Отклонена",
                    statusCategoryKey = "done",
                    updatedIso = nowIso,
                    updatedEpochMillis = nowMillis
                )
            }
            repository.upsertSingleTicket(updated)
            if (action == TicketWorkflowAction.COMPLETE) {
                Toast.makeText(appContext, "Заявка $ticketKey завершена", Toast.LENGTH_SHORT).show()
                navigateToMainScreen()
                return
            }
            _ticketActionState.value = TicketActionUiState(
                isBusy = false,
                statusMessage = when (action) {
                    TicketWorkflowAction.TAKE_IN_WORK -> "Взята в работу · Исполнитель: $agentDisplay"
                    TicketWorkflowAction.COMPLETE -> "Заявка завершена"
                    TicketWorkflowAction.REJECT -> "Заявка отклонена"
                },
                isError = false
            )
            return
        }

        if (!settings.isConfigured) {
            _ticketActionState.value = TicketActionUiState(
                isBusy = false,
                statusMessage = "Сначала настройте подключение к Jira",
                isError = true
            )
            return
        }

        viewModelScope.launch {
            _ticketActionState.value = TicketActionUiState(isBusy = true)
            apiClient.performWorkflowAction(
                issueKey = ticketKey,
                action = action,
                commentText = commentText,
                explicitTransitionId = explicitTransitionId,
                settings = settings,
                filterSettings = filterSettings.value
            ).onSuccess { outcome ->
                outcome.updatedTicket?.let { repository.upsertSingleTicket(it) }
                if (action == TicketWorkflowAction.COMPLETE && !outcome.needsManualTransitionChoice) {
                    Toast.makeText(appContext, outcome.statusMessage, Toast.LENGTH_SHORT).show()
                    navigateToMainScreen()
                    return@onSuccess
                }
                _ticketActionState.value = TicketActionUiState(
                    isBusy = false,
                    statusMessage = outcome.statusMessage,
                    isError = false,
                    manualTransitionPrompt = outcome.needsManualTransitionChoice,
                    pendingAction = outcome.pendingAction,
                    pendingComment = if (outcome.needsManualTransitionChoice) commentText else null,
                    availableTransitions = outcome.availableTransitions
                )
            }.onFailure { err ->
                if ((err as? JiraApiException)?.isNetworkError == true) {
                    enqueueOfflineWorkflowActionOptimistically(ticketKey, action, commentText, explicitTransitionId)
                    return@onFailure
                }
                _ticketActionState.value = TicketActionUiState(
                    isBusy = false,
                    statusMessage = err.message ?: "Не удалось выполнить действие в Jira",
                    isError = true
                )
            }
        }
    }

    private fun enqueueOfflineWorkflowActionOptimistically(
        ticketKey: String,
        action: TicketWorkflowAction,
        commentText: String?,
        explicitTransitionId: String?
    ) {
        repository.enqueueOfflineAction(
            OfflineQueuedAction(
                type = QueuedActionType.WORKFLOW_ACTION,
                ticketKey = ticketKey,
                workflowActionName = action.name,
                commentText = commentText,
                explicitTransitionId = explicitTransitionId
            )
        )
        val existing = tickets.value.firstOrNull { it.key.equals(ticketKey, ignoreCase = true) }
        if (existing != null) {
            val nowMs = System.currentTimeMillis()
            val nowIso = Instant.ofEpochMilli(nowMs).toString()
            val agentDisplay = syncStatus.value.connectedUser
                ?: appSettings.value.usernameOrEmail.ifBlank { "Текущий агент" }
            val optimistic = when (action) {
                TicketWorkflowAction.TAKE_IN_WORK -> existing.copy(
                    status = "В работе (офлайн)",
                    statusCategoryKey = "indeterminate",
                    assigneeName = agentDisplay,
                    updatedIso = nowIso,
                    updatedEpochMillis = nowMs
                )
                TicketWorkflowAction.COMPLETE -> existing.copy(
                    status = "Выполнена (офлайн)",
                    statusCategoryKey = "done",
                    updatedIso = nowIso,
                    updatedEpochMillis = nowMs
                )
                TicketWorkflowAction.REJECT -> existing.copy(
                    status = "Отклонена (офлайн)",
                    statusCategoryKey = "done",
                    updatedIso = nowIso,
                    updatedEpochMillis = nowMs
                )
            }
            repository.upsertSingleTicket(optimistic)
        }

        Toast.makeText(
            appContext,
            "Нет связи: действие сохранено в офлайн-очередь и отправится автоматически",
            Toast.LENGTH_LONG
        ).show()

        if (action == TicketWorkflowAction.COMPLETE) {
            navigateToMainScreen()
        } else {
            _ticketActionState.value = TicketActionUiState(
                isBusy = false,
                statusMessage = "В офлайн-очереди (отправится автоматически при появлении сети)",
                isError = false
            )
        }
    }

    fun quickTakeTicketInWork(ticketKey: String) {
        val settings = appSettings.value
        dismissInAppBanner()

        if (ticketKey.startsWith("DEMO-")) {
            executeTicketAction(ticketKey, TicketWorkflowAction.TAKE_IN_WORK)
            Toast.makeText(appContext, "Заявка $ticketKey взята в работу", Toast.LENGTH_SHORT).show()
            return
        }

        if (!settings.isConfigured) {
            Toast.makeText(appContext, "Сначала настройте подключение к Jira", Toast.LENGTH_SHORT).show()
            return
        }

        viewModelScope.launch {
            apiClient.performWorkflowAction(
                issueKey = ticketKey,
                action = TicketWorkflowAction.TAKE_IN_WORK,
                commentText = null,
                explicitTransitionId = null,
                settings = settings,
                filterSettings = filterSettings.value
            ).onSuccess { outcome ->
                outcome.updatedTicket?.let { repository.upsertSingleTicket(it) }
                Toast.makeText(appContext, "Заявка $ticketKey взята в работу", Toast.LENGTH_SHORT).show()
            }.onFailure { err ->
                if ((err as? JiraApiException)?.isNetworkError == true) {
                    enqueueOfflineWorkflowActionOptimistically(
                        ticketKey = ticketKey,
                        action = TicketWorkflowAction.TAKE_IN_WORK,
                        commentText = null,
                        explicitTransitionId = null
                    )
                } else {
                    Toast.makeText(
                        appContext,
                        err.message ?: "Не удалось взять $ticketKey в работу",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    /**
     * Postpones [ticketKey] until [scheduledAtMillis]: closes it in Jira now and schedules
     * automatic recreation + notification at the specified date & time.
     */
    fun postponeTicket(
        ticketKey: String,
        scheduledAtMillis: Long,
        scheduledDisplay: String,
        explicitTransitionId: String? = null
    ) {
        val settings = appSettings.value
        val existingTicket = tickets.value.firstOrNull { it.key.equals(ticketKey, ignoreCase = true) }

        if (!ticketKey.startsWith("DEMO-") && !settings.isConfigured) {
            _ticketActionState.value = TicketActionUiState(
                isBusy = false,
                statusMessage = "Сначала настройте подключение к Jira",
                isError = true
            )
            return
        }

        viewModelScope.launch {
            _ticketActionState.value = TicketActionUiState(isBusy = true)
            apiClient.postponeAndCloseTicket(
                issueKey = ticketKey,
                fallbackTicket = existingTicket,
                scheduledAtMillis = scheduledAtMillis,
                scheduledDisplay = scheduledDisplay,
                explicitTransitionId = explicitTransitionId,
                settings = settings,
                filterSettings = filterSettings.value
            ).onSuccess { outcome ->
                outcome.closedTicket?.let { repository.upsertSingleTicket(it) }

                if (outcome.needsManualTransitionChoice) {
                    _ticketActionState.value = TicketActionUiState(
                        isBusy = false,
                        statusMessage = outcome.statusMessage,
                        isError = false,
                        manualTransitionPrompt = true,
                        pendingAction = TicketWorkflowAction.COMPLETE,
                        pendingPostponeAtMillis = scheduledAtMillis,
                        pendingPostponeDisplay = scheduledDisplay,
                        availableTransitions = outcome.availableTransitions
                    )
                    return@onSuccess
                }

                outcome.postponedTicket?.let { draft ->
                    repository.addPostponedTicket(draft)
                    JiraMonitorService.syncServiceState(appContext)
                }

                _ticketActionState.value = TicketActionUiState(
                    isBusy = false,
                    statusMessage = outcome.statusMessage,
                    isError = false
                )
                Toast.makeText(
                    appContext,
                    "Отложено до $scheduledDisplay. Заявка закрыта и пересоздастся автоматически.",
                    Toast.LENGTH_LONG
                ).show()
            }.onFailure { err ->
                _ticketActionState.value = TicketActionUiState(
                    isBusy = false,
                    statusMessage = err.message ?: "Не удалось отложить заявку",
                    isError = true
                )
            }
        }
    }

    fun cancelPostponedTicket(postponedId: String) {
        repository.removePostponedTicket(postponedId)
        PostponeScheduler.scheduleNextAlarm(appContext)
        Toast.makeText(appContext, "Отложенное пересоздание отменено", Toast.LENGTH_SHORT).show()
    }

    fun addTicketComment(ticketKey: String, commentText: String, internalOnly: Boolean = false) {
        val clean = commentText.trim()
        if (clean.isEmpty()) return
        val settings = appSettings.value
        val displayBody = if (internalOnly) "🔒 [Внутренняя заметка] $clean" else clean

        if (ticketKey.startsWith("DEMO-")) {
            val existing = tickets.value.firstOrNull { it.key == ticketKey } ?: return
            val nowIso = Instant.now().toString()
            val author = syncStatus.value.connectedUser
                ?: settings.usernameOrEmail.ifBlank { "Агент Help-CSL" }
            val newComment = TicketComment(
                id = System.currentTimeMillis().toString(),
                author = author,
                body = displayBody,
                createdIso = nowIso
            )
            repository.upsertSingleTicket(
                existing.copy(comments = existing.comments + newComment)
            )
            _ticketActionState.value = TicketActionUiState(
                isBusy = false,
                statusMessage = if (internalOnly) "Внутренняя заметка добавлена" else "Комментарий добавлен",
                isError = false
            )
            return
        }

        if (!settings.isConfigured) {
            _ticketActionState.value = TicketActionUiState(
                isBusy = false,
                statusMessage = "Сначала настройте подключение к Jira",
                isError = true
            )
            return
        }

        viewModelScope.launch {
            _ticketActionState.value = TicketActionUiState(isBusy = true)
            apiClient.postIssueComment(
                issueKey = ticketKey,
                commentText = clean,
                settings = settings,
                filterSettings = filterSettings.value,
                internalOnly = internalOnly
            ).onSuccess { refreshed ->
                refreshed?.let { repository.upsertSingleTicket(it) }
                _ticketActionState.value = TicketActionUiState(
                    isBusy = false,
                    statusMessage = if (internalOnly) "Внутренняя заметка сохранена в Jira" else "Комментарий отправлен в Jira",
                    isError = false
                )
            }.onFailure { err ->
                if ((err as? JiraApiException)?.isNetworkError == true) {
                    repository.enqueueOfflineAction(
                        OfflineQueuedAction(
                            type = QueuedActionType.ADD_COMMENT,
                            ticketKey = ticketKey,
                            commentText = clean,
                            internalComment = internalOnly
                        )
                    )
                    val existing = tickets.value.firstOrNull { it.key.equals(ticketKey, ignoreCase = true) }
                    if (existing != null) {
                        val author = syncStatus.value.connectedUser
                            ?: settings.usernameOrEmail.ifBlank { "Агент Help-CSL" }
                        val offlineComment = TicketComment(
                            id = "offline_${System.currentTimeMillis()}",
                            author = "$author (в очереди)",
                            body = displayBody,
                            createdIso = Instant.now().toString()
                        )
                        repository.upsertSingleTicket(existing.copy(comments = existing.comments + offlineComment))
                    }
                    _ticketActionState.value = TicketActionUiState(
                        isBusy = false,
                        statusMessage = "Нет сети: сохранено в офлайн-очередь и отправится автоматически",
                        isError = false
                    )
                    return@onFailure
                }
                _ticketActionState.value = TicketActionUiState(
                    isBusy = false,
                    statusMessage = err.message ?: "Не удалось отправить комментарий",
                    isError = true
                )
            }
        }
    }

    fun refreshNow() {
        val settings = appSettings.value
        if (settings.pollingEnabled && settings.isConfigured) {
            JiraMonitorService.triggerImmediatePoll(appContext)
        } else {
            viewModelScope.launch {
                PostponeScheduler.processDuePostponedTickets(appContext, apiClient)
                if (!settings.isConfigured) return@launch
                repository.updateSyncStatus { it.copy(isSyncing = true) }
                apiClient.fetchRecentTickets(settings, filterSettings.value)
                    .onSuccess { fetched ->
                        val allowedNow = BelarusWorkCalendar.isWorkingTimeNow(settings)
                        val newOnes = repository.processPolledTicketsAndFindNewClientOnes(
                            polled = fetched,
                            isNotificationWindowOpen = allowedNow
                        )
                        if (allowedNow) {
                            newOnes.forEach { NotificationHelper.showClientTicketBanner(appContext, it) }
                        }
                        repository.updateSyncStatus {
                            it.copy(
                                isSyncing = false,
                                lastSyncEpochMillis = System.currentTimeMillis(),
                                lastError = null
                            )
                        }
                    }
                    .onFailure { err ->
                        repository.updateSyncStatus {
                            it.copy(isSyncing = false, lastError = err.message ?: "Ошибка сети")
                        }
                    }
            }
        }
    }

    fun saveAppSettings(updated: AppSettings) {
        repository.saveAppSettings(updated)
        JiraMonitorService.syncServiceState(appContext)
        if (updated.isConfigured) {
            refreshNow()
        }
    }

    fun saveFilterSettings(updated: FilterSettings) {
        repository.saveFilterSettings(updated)
        if (appSettings.value.isConfigured) {
            refreshNow()
        }
    }

    fun testConnection(candidateSettings: AppSettings) {
        viewModelScope.launch {
            _connectionTest.value = ConnectionTestState(isTesting = true)
            apiClient.testConnection(candidateSettings)
                .onSuccess { userName ->
                    repository.updateSyncStatus { it.copy(connectedUser = userName, lastError = null) }
                    _connectionTest.value = ConnectionTestState(
                        isTesting = false,
                        isSuccess = true,
                        message = "Подключено! Профиль Jira: $userName"
                    )
                }
                .onFailure { err ->
                    _connectionTest.value = ConnectionTestState(
                        isTesting = false,
                        isSuccess = false,
                        message = err.message ?: "Не удалось подключиться"
                    )
                }
        }
    }

    fun clearConnectionTestMessage() {
        _connectionTest.value = ConnectionTestState()
    }

    fun triggerTestBannerNotification() {
        val settings = appSettings.value
        val sampleRawPhone = "8 (029) 654-32-10"
        val normalized = PhoneNumberNormalizer.normalizeSingle(
            rawInput = sampleRawPhone,
            region = settings.phoneRegion,
            customCountryCode = settings.customCountryCode
        ) ?: ("+375296543210" to "+375 29 654-32-10")

        val nowMillis = System.currentTimeMillis()
        val demoKey = "DEMO-${(nowMillis / 1000) % 1000}"

        val demoTicket = HelpdeskTicket(
            id = demoKey,
            key = demoKey,
            summary = "Не работает вход в личный кабинет клиента",
            description = "Добрый день! Прошу связаться со мной по телефону $sampleRawPhone " +
                "или в Telegram @ivan_belarus. Срочная заявка от клиента.",
            issueType = "Обращение клиента",
            isSubtask = false,
            customerRequestType = "Техническая поддержка",
            hasCustomerPortalInfo = true,
            status = "Ожидание поддержки",
            statusCategoryKey = "new",
            priority = "Высокий",
            projectKey = "DEMO",
            projectName = "Help-CSL Demo",
            reporterName = "Иван Петров (Клиент)",
            reporterLoginOrEmail = "client@example.com",
            assigneeName = null,
            createdIso = Instant.ofEpochMilli(nowMillis).toString(),
            createdEpochMillis = nowMillis,
            updatedIso = Instant.ofEpochMilli(nowMillis).toString(),
            updatedEpochMillis = nowMillis,
            labels = listOf("клиент"),
            phoneNumbers = listOf(
                PhoneMatch(
                    raw = sampleRawPhone,
                    e164 = normalized.first,
                    formattedDisplay = normalized.second,
                    source = "Поле «Телефон клиента»"
                )
            ),
            telegramHandles = listOf("@ivan_belarus"),
            comments = listOf(
                TicketComment(
                    id = "1",
                    author = "Иван Петров (Клиент)",
                    body = "Жду звонка или сообщения в мессенджер на $sampleRawPhone, спасибо!",
                    createdIso = Instant.ofEpochMilli(nowMillis).toString()
                )
            ),
            webUrl = "",
            isClientTicket = true,
            filterReason = null
        )

        repository.upsertSingleTicket(demoTicket)
        NotificationHelper.showClientTicketBanner(
            context = appContext,
            ticket = demoTicket,
            bypassWorkSchedule = true,
            customTitleSuffix = "Тестовое уведомление"
        )
        Toast.makeText(appContext, "Тестовый баннер и звуковой сигнал отправлены", Toast.LENGTH_SHORT).show()
    }

    // --- Offline Queue Controls (#12) ---

    fun flushOfflineQueueNow() {
        viewModelScope.launch {
            val synced = OfflineQueueProcessor.flushPending(appContext, apiClient)
            if (synced > 0) {
                Toast.makeText(
                    appContext,
                    "Синхронизировано действий из офлайн-очереди: $synced",
                    Toast.LENGTH_SHORT
                ).show()
            } else if (offlineQueuedActions.value.isNotEmpty()) {
                Toast.makeText(
                    appContext,
                    "Сервер Jira пока недоступен — повторим автоматически",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    fun cancelOfflineAction(actionId: String) {
        repository.removeOfflineAction(actionId)
    }

    // --- Attachments & Camera Upload (#5) ---

    suspend fun loadAttachmentBytes(url: String): Result<ByteArray> {
        attachmentByteCache.get(url)?.let { return Result.success(it) }
        val res = apiClient.downloadAttachmentBytes(url, appSettings.value)
        res.onSuccess { bytes ->
            if (bytes.size <= 8 * 1024 * 1024) {
                attachmentByteCache.put(url, bytes)
            }
        }
        return res
    }

    fun uploadAttachmentFromUri(
        ticketKey: String,
        uri: Uri,
        explicitFileName: String? = null
    ) {
        val settings = appSettings.value
        if (!settings.isConfigured && !ticketKey.startsWith("DEMO-")) {
            _ticketActionState.value = TicketActionUiState(
                isBusy = false,
                statusMessage = "Сначала настройте подключение к Jira",
                isError = true
            )
            return
        }

        viewModelScope.launch {
            _ticketActionState.value = TicketActionUiState(
                isBusy = true,
                statusMessage = "Подготовка и отправка вложения…"
            )

            val prepared = withContext(Dispatchers.IO) {
                runCatching {
                    val resolver = appContext.contentResolver
                    val rawMime = resolver.getType(uri).orEmpty().ifBlank { "image/jpeg" }
                    val resolvedName = explicitFileName?.takeIf { it.isNotBlank() }
                        ?: queryDisplayName(uri)
                        ?: "attachment_${System.currentTimeMillis()}.jpg"
                    val rawBytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw IllegalStateException("Не удалось прочитать выбранный файл")

                    if (rawMime.startsWith("image/") && rawBytes.size > 1_200_000) {
                        val compressed = downscaleImageIfNeeded(rawBytes)
                        if (compressed != null) {
                            val jpgName = resolvedName.substringBeforeLast('.', resolvedName) + ".jpg"
                            Triple(jpgName, "image/jpeg", compressed)
                        } else {
                            Triple(resolvedName, rawMime, rawBytes)
                        }
                    } else {
                        Triple(resolvedName, rawMime, rawBytes)
                    }
                }
            }

            prepared.onFailure { err ->
                _ticketActionState.value = TicketActionUiState(
                    isBusy = false,
                    statusMessage = err.message ?: "Ошибка чтения файла",
                    isError = true
                )
                return@launch
            }

            val (fileName, mimeType, bytes) = prepared.getOrThrow()

            if (ticketKey.startsWith("DEMO-")) {
                val existing = tickets.value.firstOrNull { it.key == ticketKey }
                if (existing != null) {
                    val demoAtt = TicketAttachment(
                        id = System.currentTimeMillis().toString(),
                        filename = fileName,
                        mimeType = mimeType,
                        sizeBytes = bytes.size.toLong(),
                        contentUrl = "demo://$fileName",
                        authorName = "Агент Help-CSL",
                        createdIso = Instant.now().toString()
                    )
                    attachmentByteCache.put(demoAtt.contentUrl, bytes)
                    repository.upsertSingleTicket(
                        existing.copy(attachments = existing.attachments.orEmpty() + demoAtt)
                    )
                }
                _ticketActionState.value = TicketActionUiState(
                    isBusy = false,
                    statusMessage = "Файл $fileName прикреплён",
                    isError = false
                )
                return@launch
            }

            apiClient.uploadIssueAttachment(
                issueKey = ticketKey,
                fileName = fileName,
                mimeType = mimeType,
                fileBytes = bytes,
                settings = settings,
                filterSettings = filterSettings.value
            ).onSuccess { refreshed ->
                refreshed?.let { repository.upsertSingleTicket(it) }
                _ticketActionState.value = TicketActionUiState(
                    isBusy = false,
                    statusMessage = "Вложение «$fileName» загружено в Jira",
                    isError = false
                )
            }.onFailure { err ->
                _ticketActionState.value = TicketActionUiState(
                    isBusy = false,
                    statusMessage = err.message ?: "Не удалось загрузить вложение",
                    isError = true
                )
            }
        }
    }

    fun openExternalAttachment(attachment: TicketAttachment) {
        viewModelScope.launch {
            Toast.makeText(appContext, "Скачивание «${attachment.filename}»…", Toast.LENGTH_SHORT).show()
            loadAttachmentBytes(attachment.contentUrl).onSuccess { bytes ->
                val savedFile = withContext(Dispatchers.IO) {
                    val dir = File(appContext.cacheDir, "shared_attachments").apply { mkdirs() }
                    val safeName = attachment.filename.replace(Regex("[^a-zA-Zа-яА-ЯёЁ0-9._-]"), "_")
                    val target = File(dir, safeName)
                    target.writeBytes(bytes)
                    target
                }
                runCatching {
                    val uri = FileProvider.getUriForFile(
                        appContext,
                        "${appContext.packageName}.fileprovider",
                        savedFile
                    )
                    val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, attachment.mimeType.ifBlank { "*/*" })
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    appContext.startActivity(viewIntent)
                }.onFailure {
                    Toast.makeText(
                        appContext,
                        "На телефоне нет приложения для открытия файла ${attachment.filename}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }.onFailure { err ->
                Toast.makeText(
                    appContext,
                    err.message ?: "Не удалось скачать вложение",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return runCatching {
            appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
        }.getOrNull()
    }

    private fun downscaleImageIfNeeded(rawBytes: ByteArray): ByteArray? {
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, bounds)
            val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
            if (maxDim <= 0) return null
            var sample = 1
            while (maxDim / sample > 1920) {
                sample *= 2
            }
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, decodeOpts) ?: return null
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 84, out)
            bmp.recycle()
            out.toByteArray()
        }.getOrNull()
    }

    // --- Network Diagnostics (#6) ---

    fun runHostDiagnostics(host: String) {
        val clean = host.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch {
            _hostDiagnosticState.value = HostDiagnosticUiState(
                isRunning = true,
                targetHost = clean,
                report = null
            )
            val report = NetworkDiagnosticsTool.runDiagnostics(clean)
            _hostDiagnosticState.value = HostDiagnosticUiState(
                isRunning = false,
                targetHost = clean,
                report = report
            )
        }
    }

    fun dismissHostDiagnostics() {
        _hostDiagnosticState.value = HostDiagnosticUiState()
    }

    // --- Create New Ticket (#8) ---

    fun createNewTicket(
        projectKey: String,
        issueType: String,
        summary: String,
        description: String,
        assignToSelf: Boolean,
        onCreated: (String) -> Unit
    ) {
        val settings = appSettings.value
        if (!settings.isConfigured) {
            Toast.makeText(appContext, "Сначала настройте подключение к Jira", Toast.LENGTH_SHORT).show()
            return
        }
        if (_isCreatingTicket.value) return

        viewModelScope.launch {
            _isCreatingTicket.value = true
            apiClient.createManualTicket(
                projectKeyHint = projectKey,
                issueTypeHint = issueType,
                summary = summary,
                description = description,
                assignToSelf = assignToSelf,
                settings = settings,
                filterSettings = filterSettings.value
            ).onSuccess { created ->
                _isCreatingTicket.value = false
                repository.upsertSingleTicket(created)
                Toast.makeText(appContext, "Создана заявка ${created.key}", Toast.LENGTH_SHORT).show()
                onCreated(created.key)
            }.onFailure { err ->
                _isCreatingTicket.value = false
                Toast.makeText(
                    appContext,
                    err.message ?: "Не удалось создать заявку",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // --- Internal Admin Chat & Pinned Shift Note (#10) ---

    fun requestOpenAdminChatTab() {
        _selectedTicketKey.value = null
        _openChatTabStamp.value = System.currentTimeMillis()
    }

    fun discussTicketInChat(ticket: HelpdeskTicket) {
        val prefix = "По заявке #${ticket.key} (${ticket.reporterName} — ${ticket.summary.take(55)}): "
        _adminChatUiState.update { state ->
            val current = state.draftText.trim()
            state.copy(
                draftText = if (current.contains(ticket.key, ignoreCase = true)) state.draftText else prefix
            )
        }
        _selectedTicketKey.value = null
        _openChatTabStamp.value = System.currentTimeMillis()
    }

    fun setChatTabActive(active: Boolean) {
        repository.isChatTabVisible = active
        if (active) {
            repository.markAdminChatRead()
            startLiveChatPolling()
        } else {
            liveChatPollJob?.cancel()
            liveChatPollJob = null
        }
    }

    private fun startLiveChatPolling() {
        liveChatPollJob?.cancel()
        liveChatPollJob = viewModelScope.launch {
            var firstPass = true
            while (isActive && repository.isChatTabVisible) {
                if (appSettings.value.isConfigured) {
                    refreshAdminChatInternal(showSpinner = firstPass && chatMessages.value.isEmpty())
                }
                firstPass = false
                delay(4_500L)
            }
        }
    }

    fun refreshAdminChat() {
        if (!appSettings.value.isConfigured) return
        viewModelScope.launch {
            refreshAdminChatInternal(showSpinner = true)
        }
    }

    private suspend fun refreshAdminChatInternal(showSpinner: Boolean) {
        if (showSpinner) {
            _adminChatUiState.update { it.copy(isLoading = true, errorMessage = null) }
        }
        val settings = appSettings.value
        val filter = filterSettings.value

        val roomKeyResult = apiClient.resolveOrCreateAdminChatRoom(
            settings = settings,
            filterSettings = filter,
            cachedRoomKey = repository.resolvedChatRoomKey.value
        )
        val roomKey = roomKeyResult.getOrElse { err ->
            _adminChatUiState.update {
                it.copy(
                    isLoading = false,
                    errorMessage = err.message ?: "Не удалось подключиться к комнате чата"
                )
            }
            return
        }

        repository.setResolvedChatRoomKey(roomKey)
        apiClient.fetchAdminChatRoomSnapshot(roomKey, settings)
            .onSuccess { snapshot ->
                repository.recordPolledChatMessages(
                    roomIssueKey = roomKey,
                    messages = snapshot.messages,
                    pinnedNote = snapshot.pinnedNote
                )
                if (repository.isChatTabVisible) {
                    repository.markAdminChatRead()
                }
                _adminChatUiState.update { it.copy(isLoading = false, errorMessage = null) }
            }
            .onFailure { err ->
                _adminChatUiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = err.message ?: "Ошибка обновления чата"
                    )
                }
            }
    }

    fun updateChatDraft(text: String) {
        _adminChatUiState.update { it.copy(draftText = text) }
    }

    fun setChatReplyTarget(message: AdminChatMessage?) {
        _adminChatUiState.update { it.copy(replyTo = message) }
    }

    fun sendAdminChatMessage(overrideText: String? = null) {
        val state = _adminChatUiState.value
        val rawText = (overrideText ?: state.draftText).trim()
        if (rawText.isEmpty() || state.isSending) return
        val settings = appSettings.value
        if (!settings.isConfigured) {
            _adminChatUiState.update { it.copy(errorMessage = "Сначала настройте подключение к Jira") }
            return
        }

        val replyAuthor = if (overrideText == null) state.replyTo?.authorDisplayName else null
        val replyPreview = if (overrideText == null) state.replyTo?.text else null

        viewModelScope.launch {
            _adminChatUiState.update { it.copy(isSending = true, errorMessage = null) }

            val roomKeyRes = apiClient.resolveOrCreateAdminChatRoom(
                settings = settings,
                filterSettings = filterSettings.value,
                cachedRoomKey = repository.resolvedChatRoomKey.value
            )
            val roomKey = roomKeyRes.getOrElse { err ->
                if ((err as? JiraApiException)?.isNetworkError == true) {
                    enqueueOfflineChatMessage(rawText, replyAuthor, replyPreview, clearDraft = overrideText == null)
                    return@launch
                }
                _adminChatUiState.update {
                    it.copy(isSending = false, errorMessage = err.message ?: "Комната чата недоступна")
                }
                return@launch
            }
            repository.setResolvedChatRoomKey(roomKey)

            apiClient.sendAdminChatMessage(
                roomIssueKey = roomKey,
                rawText = rawText,
                replyToAuthor = replyAuthor,
                replyToPreview = replyPreview,
                settings = settings
            ).onSuccess { sent ->
                repository.appendSentChatMessage(sent)
                _adminChatUiState.update {
                    it.copy(
                        isSending = false,
                        draftText = if (overrideText == null) "" else it.draftText,
                        replyTo = if (overrideText == null) null else it.replyTo,
                        errorMessage = null
                    )
                }
            }.onFailure { err ->
                if ((err as? JiraApiException)?.isNetworkError == true) {
                    enqueueOfflineChatMessage(rawText, replyAuthor, replyPreview, clearDraft = overrideText == null)
                    return@onFailure
                }
                _adminChatUiState.update {
                    it.copy(isSending = false, errorMessage = err.message ?: "Ошибка отправки сообщения")
                }
            }
        }
    }

    private fun enqueueOfflineChatMessage(
        rawText: String,
        replyAuthor: String?,
        replyPreview: String?,
        clearDraft: Boolean
    ) {
        repository.enqueueOfflineAction(
            OfflineQueuedAction(
                type = QueuedActionType.CHAT_MESSAGE,
                commentText = rawText,
                chatReplyAuthor = replyAuthor,
                chatReplyPreview = replyPreview
            )
        )
        val nowMs = System.currentTimeMillis()
        val meName = syncStatus.value.connectedUser
            ?: appSettings.value.usernameOrEmail.ifBlank { "Вы" }
        val localMsg = AdminChatMessage(
            id = "offline_$nowMs",
            authorDisplayName = "$meName (в очереди)",
            authorIdentifier = meName,
            isOwnMessage = true,
            text = rawText,
            replyToAuthor = replyAuthor,
            replyToPreview = replyPreview,
            createdIso = Instant.ofEpochMilli(nowMs).toString(),
            createdEpochMillis = nowMs
        )
        repository.appendSentChatMessage(localMsg)
        _adminChatUiState.update {
            it.copy(
                isSending = false,
                draftText = if (clearDraft) "" else it.draftText,
                replyTo = if (clearDraft) null else it.replyTo,
                errorMessage = null
            )
        }
        Toast.makeText(
            appContext,
            "Сообщение сохранено в офлайн-очередь и отправится при появлении сети",
            Toast.LENGTH_SHORT
        ).show()
    }

    fun deleteAdminChatMessage(commentId: String) {
        if (commentId.startsWith("offline_")) {
            repository.removeDeletedChatMessage(commentId)
            return
        }
        val roomKey = repository.resolvedChatRoomKey.value
        if (roomKey.isBlank() || !appSettings.value.isConfigured) return

        viewModelScope.launch {
            apiClient.deleteAdminChatMessage(roomKey, commentId, appSettings.value)
                .onSuccess {
                    repository.removeDeletedChatMessage(commentId)
                    Toast.makeText(appContext, "Сообщение удалено", Toast.LENGTH_SHORT).show()
                }
                .onFailure { err ->
                    Toast.makeText(
                        appContext,
                        err.message ?: "Не удалось удалить сообщение",
                        Toast.LENGTH_SHORT
                    ).show()
                }
        }
    }

    fun savePinnedShiftNote(noteText: String) {
        val settings = appSettings.value
        if (!settings.isConfigured) return

        viewModelScope.launch {
            _adminChatUiState.update { it.copy(isSavingNote = true) }
            val roomKey = apiClient.resolveOrCreateAdminChatRoom(
                settings = settings,
                filterSettings = filterSettings.value,
                cachedRoomKey = repository.resolvedChatRoomKey.value
            ).getOrNull()

            if (roomKey == null) {
                _adminChatUiState.update {
                    it.copy(isSavingNote = false, errorMessage = "Комната чата ещё не инициализирована")
                }
                return@launch
            }

            apiClient.updateAdminChatPinnedNote(roomKey, noteText, settings)
                .onSuccess { saved ->
                    repository.updatePinnedShiftNote(saved)
                    _adminChatUiState.update { it.copy(isSavingNote = false, errorMessage = null) }
                    Toast.makeText(appContext, "Дежурная заметка смены сохранена", Toast.LENGTH_SHORT).show()
                }
                .onFailure { err ->
                    _adminChatUiState.update {
                        it.copy(
                            isSavingNote = false,
                            errorMessage = err.message ?: "Не удалось сохранить заметку смены"
                        )
                    }
                }
        }
    }

    fun saveChatRoomSettings(manualIssueKey: String, notificationsEnabled: Boolean) {
        val updated = appSettings.value.copy(
            adminChatIssueKey = manualIssueKey.trim().uppercase(),
            adminChatNotificationsEnabled = notificationsEnabled
        )
        saveAppSettings(updated)
        refreshAdminChat()
    }

    // =========================================================================
    // Stage 2: Worklog, Caller Wrap-up, Client Dossier, Colleague Handoff,
    // PDF Service Act, Serial Scanner History, Batch Actions & QR Transfer
    // =========================================================================

    fun dismissPendingCallSession() {
        repository.clearPendingCallSession()
    }

    fun saveClientDossierNote(clientName: String, noteText: String) {
        repository.saveClientDossierNote(clientName, noteText)
        Toast.makeText(appContext, "Досье клиента сохранено", Toast.LENGTH_SHORT).show()
    }

    fun logTicketWorklog(
        ticketKey: String,
        minutes: Int,
        commentText: String,
        alsoCompleteTicket: Boolean = false
    ) {
        repository.clearPendingCallSession()
        val safeMinutes = minutes.coerceIn(1, 1440)
        val seconds = safeMinutes * 60
        val cleanComment = commentText.trim()
        val settings = appSettings.value

        if (ticketKey.startsWith("DEMO-") || !settings.isConfigured) {
            Toast.makeText(appContext, "Списано $safeMinutes мин по $ticketKey (демо)", Toast.LENGTH_SHORT).show()
            if (alsoCompleteTicket) {
                executeTicketAction(ticketKey, TicketWorkflowAction.COMPLETE, cleanComment.ifBlank { null })
            }
            return
        }

        viewModelScope.launch {
            _ticketActionState.value = TicketActionUiState(isBusy = true, statusMessage = "Списание $safeMinutes мин в Worklog…")
            apiClient.addIssueWorklog(
                issueKey = ticketKey,
                timeSpentSeconds = seconds,
                commentText = cleanComment.ifBlank { null },
                settings = settings
            ).onSuccess {
                if (cleanComment.isNotBlank() && !alsoCompleteTicket) {
                    runCatching {
                        apiClient.postIssueComment(
                            issueKey = ticketKey,
                            commentText = "📞 Итог / Worklog ($safeMinutes мин): $cleanComment",
                            settings = settings,
                            filterSettings = filterSettings.value,
                            internalOnly = true
                        ).getOrNull()?.let { repository.upsertSingleTicket(it) }
                    }
                }
                _ticketActionState.value = TicketActionUiState(
                    isBusy = false,
                    statusMessage = "Списано $safeMinutes мин в Worklog Jira",
                    isError = false
                )
                Toast.makeText(appContext, "Worklog: списано $safeMinutes мин ($ticketKey)", Toast.LENGTH_SHORT).show()
                if (alsoCompleteTicket) {
                    executeTicketAction(ticketKey, TicketWorkflowAction.COMPLETE, cleanComment.ifBlank { null })
                }
            }.onFailure { err ->
                if ((err as? JiraApiException)?.isNetworkError == true) {
                    repository.enqueueOfflineAction(
                        OfflineQueuedAction(
                            type = QueuedActionType.ADD_WORKLOG,
                            ticketKey = ticketKey,
                            worklogSeconds = seconds,
                            commentText = cleanComment.ifBlank { null }
                        )
                    )
                    _ticketActionState.value = TicketActionUiState(
                        isBusy = false,
                        statusMessage = "Нет сети: Worklog ($safeMinutes мин) поставлен в офлайн-очередь",
                        isError = false
                    )
                    if (alsoCompleteTicket) {
                        executeTicketAction(ticketKey, TicketWorkflowAction.COMPLETE, cleanComment.ifBlank { null })
                    }
                    return@onFailure
                }
                _ticketActionState.value = TicketActionUiState(
                    isBusy = false,
                    statusMessage = err.message ?: "Не удалось списать время в Worklog",
                    isError = true
                )
            }
        }
    }

    fun loadAssignableColleagues(issueKey: String) {
        val settings = appSettings.value
        if (!settings.isConfigured || issueKey.startsWith("DEMO-")) {
            _assignableColleagues.value = listOf(
                JiraAssignableUser("Алексей Дежурный", "alexey", null, "alexey@csl.by"),
                JiraAssignableUser("Дмитрий Выездной", "dmitry", null, "dmitry@csl.by"),
                JiraAssignableUser("Павел Сетевой", "pavel", null, "pavel@csl.by")
            )
            return
        }
        viewModelScope.launch {
            _isLoadingColleagues.value = true
            apiClient.fetchAssignableUsers(issueKey, settings)
                .onSuccess { list -> _assignableColleagues.value = list }
                .onFailure { _assignableColleagues.value = emptyList() }
            _isLoadingColleagues.value = false
        }
    }

    fun assignTicketToColleague(issueKey: String, colleague: JiraAssignableUser) {
        val settings = appSettings.value
        if (issueKey.startsWith("DEMO-") || !settings.isConfigured) {
            val existing = tickets.value.firstOrNull { it.key == issueKey } ?: return
            repository.upsertSingleTicket(existing.copy(assigneeName = colleague.displayName))
            _ticketActionState.value = TicketActionUiState(
                isBusy = false,
                statusMessage = "Заявка передана: ${colleague.displayName}",
                isError = false
            )
            return
        }
        viewModelScope.launch {
            _ticketActionState.value = TicketActionUiState(
                isBusy = true,
                statusMessage = "Передача заявки на ${colleague.displayName}…"
            )
            apiClient.assignTicketToColleague(issueKey, colleague, settings, filterSettings.value)
                .onSuccess { refreshed ->
                    if (refreshed != null) {
                        repository.upsertSingleTicket(refreshed)
                    } else {
                        tickets.value.firstOrNull { it.key == issueKey }?.let {
                            repository.upsertSingleTicket(it.copy(assigneeName = colleague.displayName))
                        }
                    }
                    _ticketActionState.value = TicketActionUiState(
                        isBusy = false,
                        statusMessage = "Назначено на: ${colleague.displayName}",
                        isError = false
                    )
                }
                .onFailure { err ->
                    _ticketActionState.value = TicketActionUiState(
                        isBusy = false,
                        statusMessage = err.message ?: "Не удалось назначить коллегу",
                        isError = true
                    )
                }
        }
    }

    fun generateAndAttachServiceAct(input: ServiceActInput, openPreviewAfter: Boolean = true) {
        viewModelScope.launch {
            _ticketActionState.value = TicketActionUiState(
                isBusy = true,
                statusMessage = "Формирование PDF-акта выполненных работ…"
            )
            val pdfResult = withContext(Dispatchers.IO) {
                runCatching { PdfServiceActGenerator.generatePdfFile(appContext, input) }
            }
            val pdfFile = pdfResult.getOrElse { err ->
                _ticketActionState.value = TicketActionUiState(
                    isBusy = false,
                    statusMessage = "Ошибка создания PDF: ${err.message}",
                    isError = true
                )
                return@launch
            }

            val settings = appSettings.value
            if (settings.isConfigured && !input.ticket.key.startsWith("DEMO-")) {
                val bytes = withContext(Dispatchers.IO) { pdfFile.readBytes() }
                apiClient.uploadIssueAttachment(
                    issueKey = input.ticket.key,
                    fileName = pdfFile.name,
                    fileBytes = bytes,
                    mimeType = "application/pdf",
                    settings = settings,
                    filterSettings = filterSettings.value
                ).onSuccess { refreshed ->
                    refreshed?.let { repository.upsertSingleTicket(it) }
                    _ticketActionState.value = TicketActionUiState(
                        isBusy = false,
                        statusMessage = "PDF-акт (${pdfFile.name}) прикреплён к заявке в Jira!",
                        isError = false
                    )
                }.onFailure { err ->
                    _ticketActionState.value = TicketActionUiState(
                        isBusy = false,
                        statusMessage = "PDF создан локально, но ошибка отправки в Jira: ${err.message}",
                        isError = true
                    )
                }
            } else {
                _ticketActionState.value = TicketActionUiState(
                    isBusy = false,
                    statusMessage = "PDF-акт сформирован: ${pdfFile.name}",
                    isError = false
                )
            }

            if (openPreviewAfter) {
                runCatching {
                    val uri = FileProvider.getUriForFile(
                        appContext,
                        "${appContext.packageName}.fileprovider",
                        pdfFile
                    )
                    val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "application/pdf")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    appContext.startActivity(viewIntent)
                }
            }
        }
    }

    fun searchByHardwareSerial(rawSerial: String) {
        val clean = rawSerial.trim()
        if (clean.isEmpty()) return
        val settings = appSettings.value
        val localMatches = tickets.value.filter { t ->
            t.summary.contains(clean, ignoreCase = true) ||
                t.description.contains(clean, ignoreCase = true) ||
                t.comments.any { it.body.contains(clean, ignoreCase = true) }
        }
        if (!settings.isConfigured) {
            _serialSearchState.value = SerialSearchUiState(
                isSearching = false,
                serialQuery = clean,
                results = localMatches
            )
            return
        }
        viewModelScope.launch {
            _serialSearchState.value = SerialSearchUiState(
                isSearching = true,
                serialQuery = clean,
                results = localMatches
            )
            apiClient.searchTicketsBySerialOrText(clean, settings, filterSettings.value)
                .onSuccess { remote ->
                    val merged = (remote + localMatches).distinctBy { it.key }
                    _serialSearchState.value = SerialSearchUiState(
                        isSearching = false,
                        serialQuery = clean,
                        results = merged
                    )
                }
                .onFailure { err ->
                    _serialSearchState.value = SerialSearchUiState(
                        isSearching = false,
                        serialQuery = clean,
                        results = localMatches,
                        errorMessage = err.message
                    )
                }
        }
    }

    fun clearSerialSearch() {
        _serialSearchState.value = SerialSearchUiState()
    }

    fun performBatchTicketsAction(
        ticketKeys: Set<String>,
        workflowAction: TicketWorkflowAction?,
        commentText: String?,
        onFinished: () -> Unit
    ) {
        if (ticketKeys.isEmpty() || _isBatchBusy.value) return
        val settings = appSettings.value
        val cleanComment = commentText?.trim()?.takeIf { it.isNotEmpty() }

        viewModelScope.launch {
            _isBatchBusy.value = true
            var okCount = 0
            for (key in ticketKeys) {
                if (workflowAction != null) {
                    if (key.startsWith("DEMO-") || !settings.isConfigured) {
                        okCount++
                    } else {
                        val res = apiClient.performWorkflowAction(
                            issueKey = key,
                            action = workflowAction,
                            commentText = cleanComment,
                            explicitTransitionId = null,
                            settings = settings,
                            filterSettings = filterSettings.value
                        )
                        res.onSuccess { out ->
                            out.updatedTicket?.let { repository.upsertSingleTicket(it) }
                            okCount++
                        }.onFailure { err ->
                            if ((err as? JiraApiException)?.isNetworkError == true) {
                                repository.enqueueOfflineAction(
                                    OfflineQueuedAction(
                                        type = QueuedActionType.WORKFLOW_ACTION,
                                        ticketKey = key,
                                        workflowActionName = workflowAction.name,
                                        commentText = cleanComment
                                    )
                                )
                                okCount++
                            }
                        }
                    }
                } else if (cleanComment != null) {
                    val res = apiClient.postIssueComment(
                        issueKey = key,
                        commentText = cleanComment,
                        settings = settings,
                        filterSettings = filterSettings.value,
                        internalOnly = false
                    )
                    if (res.isSuccess) {
                        res.getOrNull()?.let { repository.upsertSingleTicket(it) }
                        okCount++
                    }
                }
            }
            _isBatchBusy.value = false
            Toast.makeText(
                appContext,
                "Массовое действие выполнено для $okCount из ${ticketKeys.size} заявок",
                Toast.LENGTH_SHORT
            ).show()
            onFinished()
        }
    }

    fun importSettingsBundle(encodedCode: String, pinCode: String): Result<String> {
        return SettingsTransferCodec.decode(encodedCode, pinCode).map { bundle ->
            val current = appSettings.value
            val mergedApp = if (bundle.includesCredentials) {
                bundle.appSettings
            } else {
                bundle.appSettings.copy(
                    usernameOrEmail = current.usernameOrEmail,
                    passwordOrToken = current.passwordOrToken
                )
            }
            saveAppSettings(mergedApp)
            saveFilterSettings(bundle.filterSettings)
            if (bundle.includesCredentials) {
                "Настройки, фильтры и учётные данные успешно импортированы!"
            } else {
                "Настройки сервера, РБ-графика и фильтров импортированы (введите свой логин/пароль)."
            }
        }
    }

    // --- Workshop Equipment Repair Registry («В ремонт») ---

    fun sendTicketToRepairIntake(ticket: HelpdeskTicket, scannedSerial: String = "") {
        val engineerName = syncStatus.value.connectedUser ?: appSettings.value.usernameOrEmail
        val firstPhone = ticket.phoneNumbers.firstOrNull()?.e164.orEmpty()
        val lowerSummary = "${ticket.summary} ${ticket.description}".lowercase()
        val guessedCategory = when {
            lowerSummary.contains("ноутбук") || lowerSummary.contains("laptop") -> "Ноутбук"
            lowerSummary.contains("принтер") || lowerSummary.contains("мфу") || lowerSummary.contains("kyocera") -> "Принтер / МФУ"
            lowerSummary.contains("сервер") || lowerSummary.contains("схд") -> "Сервер / СХД"
            lowerSummary.contains("ибп") || lowerSummary.contains("ups") -> "ИБП (UPS)"
            lowerSummary.contains("mikrotik") || lowerSummary.contains("роутер") || lowerSummary.contains("коммутатор") -> "Сетевое (MikroTik/Cisco)"
            lowerSummary.contains("монитор") -> "Монитор"
            lowerSummary.contains("пк") || lowerSummary.contains("компьютер") || lowerSummary.contains("системн") -> "ПК / Системный блок"
            else -> "Ноутбук"
        }

        _pendingRepairDraftFromTicket.value = RepairEquipmentItem(
            deviceCategory = guessedCategory,
            deviceModel = ticket.summary.take(70),
            serialNumber = scannedSerial,
            clientName = ticket.reporterName,
            clientPhone = firstPhone,
            linkedTicketKey = ticket.key,
            reportedDefect = ticket.description.ifBlank { ticket.summary }.take(400),
            assignedEngineer = engineerName,
            status = RepairStatus.RECEIVED
        )
        _selectedTicketKey.value = null
        _openRepairTabStamp.value = System.currentTimeMillis()
    }

    fun consumePendingRepairDraft() {
        _pendingRepairDraftFromTicket.value = null
    }

    fun saveRepairEquipmentItem(
        item: RepairEquipmentItem,
        postNoteToLinkedTicket: Boolean = false
    ) {
        val saved = repository.upsertRepairItem(item)
        Toast.makeText(
            appContext,
            "Сохранено во вкладку «В ремонт»: ${saved.intakeNumber}",
            Toast.LENGTH_SHORT
        ).show()

        val settings = appSettings.value
        if (!settings.isConfigured) return

        viewModelScope.launch {
            if (postNoteToLinkedTicket && saved.linkedTicketKey.isNotBlank() && !saved.isDeleted) {
                val snPart = if (saved.serialNumber.isNotBlank()) ", S/N: ${saved.serialNumber}" else ""
                val accPart = if (saved.accessories.isNotBlank()) ", комплект: ${saved.accessories}" else ""
                val note = "🔧 Техника в ремонте (${saved.intakeNumber}): ${saved.deviceCategory} ${saved.deviceModel}$snPart$accPart — статус: ${saved.status.title}."
                val commentRes = apiClient.postIssueComment(
                    issueKey = saved.linkedTicketKey,
                    commentText = note,
                    settings = settings,
                    filterSettings = filterSettings.value,
                    internalOnly = true
                )
                commentRes.getOrNull()?.let { repository.upsertSingleTicket(it) }
                if (commentRes.isFailure && (commentRes.exceptionOrNull() as? JiraApiException)?.isNetworkError == true) {
                    repository.enqueueOfflineAction(
                        OfflineQueuedAction(
                            type = QueuedActionType.ADD_COMMENT,
                            ticketKey = saved.linkedTicketKey,
                            commentText = note,
                            internalComment = true
                        )
                    )
                }
            }

            syncSingleRepairItemAndPull(saved)
        }
    }

    private fun resolveCurrentEngineerAuditLabel(): String {
        val display = syncStatus.value.connectedUser?.trim().orEmpty()
        val login = appSettings.value.usernameOrEmail.trim()
        return when {
            display.isNotEmpty() && login.isNotEmpty() && !display.equals(login, ignoreCase = true) ->
                "$display ($login)"
            display.isNotEmpty() -> display
            login.isNotEmpty() -> login
            else -> "Локальный инженер"
        }
    }

    fun deleteRepairEquipmentItem(item: RepairEquipmentItem, reason: String = "") {
        val now = System.currentTimeMillis()
        val whoDeleted = resolveCurrentEngineerAuditLabel()
        val deleted = repository.upsertRepairItem(
            item.copy(
                isDeleted = true,
                deletedBy = whoDeleted,
                deletedAtEpochMs = now,
                deleteReason = reason.trim().ifEmpty { null }
            )
        )
        Toast.makeText(
            appContext,
            "Перемещено в «Удалённые»: ${deleted.intakeNumber} (удалил: $whoDeleted)",
            Toast.LENGTH_LONG
        ).show()
        if (appSettings.value.isConfigured) {
            viewModelScope.launch {
                syncSingleRepairItemAndPull(deleted)
            }
        }
    }

    fun restoreRepairEquipmentItem(item: RepairEquipmentItem) {
        val restored = repository.upsertRepairItem(
            item.copy(
                isDeleted = false,
                deletedBy = null,
                deletedAtEpochMs = null,
                deleteReason = null
            )
        )
        Toast.makeText(
            appContext,
            "Карточка ${restored.intakeNumber} восстановлена из «Удалённых»",
            Toast.LENGTH_SHORT
        ).show()
        if (appSettings.value.isConfigured) {
            viewModelScope.launch {
                syncSingleRepairItemAndPull(restored)
            }
        }
    }

    fun syncRepairRegistry() {
        val settings = appSettings.value
        if (!settings.isConfigured || _isSyncingRepair.value) return
        if (!_isLoadingClientDirectory.value) {
            refreshClientsDirectory(manual = false)
        }
        viewModelScope.launch {
            _isSyncingRepair.value = true
            _repairSyncError.value = null
            val roomRes = apiClient.resolveOrCreateRepairRegistryRoom(
                settings = settings,
                filterSettings = filterSettings.value,
                cachedRoomKey = repository.resolvedRepairRoomKey.value
            )
            roomRes.onSuccess { roomKey ->
                repository.setResolvedRepairRoomKey(roomKey)
                // First push any unsynced local entries
                val unsynced = repository.repairItems.value.filter { it.pendingRemoteSync }
                for (pending in unsynced) {
                    val postRes = apiClient.postRepairRegistryEntry(roomKey, pending, settings)
                    if (postRes.isSuccess) {
                        repository.markRepairItemSynced(pending.id, pending.updatedAtEpochMs)
                    }
                }
                // Then pull remote entries from colleagues
                apiClient.fetchRepairRegistryRemoteEntries(roomKey, settings)
                    .onSuccess { remoteList ->
                        repository.mergeRemoteRepairEvents(roomKey, remoteList)
                    }
                    .onFailure { err ->
                        _repairSyncError.value = err.message
                    }
            }.onFailure { err ->
                _repairSyncError.value = err.message
            }
            _isSyncingRepair.value = false
        }
    }

    fun refreshClientsDirectory(manual: Boolean = false) {
        val settings = appSettings.value
        if (!settings.isConfigured || _isLoadingClientDirectory.value) return
        viewModelScope.launch {
            _isLoadingClientDirectory.value = true
            apiClient.fetchHelpdeskClientsDirectory(settings, filterSettings.value)
                .onSuccess { fetched ->
                    repository.mergeClientDirectoryEntries(fetched)
                    if (manual) {
                        Toast.makeText(
                            appContext,
                            "Справочник клиентов обновлён (${repository.clientDirectory.value.size})",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                .onFailure { err ->
                    if (manual) {
                        Toast.makeText(
                            appContext,
                            err.message ?: "Не удалось загрузить клиентов из Helpdesk",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            _isLoadingClientDirectory.value = false
        }
    }

    private suspend fun syncSingleRepairItemAndPull(item: RepairEquipmentItem) {
        val settings = appSettings.value
        if (!settings.isConfigured) return
        val roomKey = apiClient.resolveOrCreateRepairRegistryRoom(
            settings = settings,
            filterSettings = filterSettings.value,
            cachedRoomKey = repository.resolvedRepairRoomKey.value
        ).getOrElse { err ->
            _repairSyncError.value = err.message
            return
        }
        repository.setResolvedRepairRoomKey(roomKey)
        val postRes = apiClient.postRepairRegistryEntry(roomKey, item, settings)
        if (postRes.isSuccess) {
            repository.markRepairItemSynced(item.id, item.updatedAtEpochMs)
            apiClient.fetchRepairRegistryRemoteEntries(roomKey, settings).onSuccess { remote ->
                repository.mergeRemoteRepairEvents(roomKey, remote)
            }
        } else {
            _repairSyncError.value = postRes.exceptionOrNull()?.message
        }
    }

    fun attachRepairPdfToTicket(
        ticketKey: String,
        pdfBytes: ByteArray,
        fileName: String,
        noteText: String
    ) {
        val settings = appSettings.value
        if (!settings.isConfigured) {
            Toast.makeText(appContext, "Сначала настройте подключение к Jira", Toast.LENGTH_SHORT).show()
            return
        }
        viewModelScope.launch {
            val uploadRes = apiClient.uploadIssueAttachment(
                issueKey = ticketKey,
                fileName = fileName,
                mimeType = "application/pdf",
                fileBytes = pdfBytes,
                settings = settings,
                filterSettings = filterSettings.value
            )
            uploadRes.onSuccess { updated ->
                updated?.let { repository.upsertSingleTicket(it) }
                if (noteText.isNotBlank()) {
                    apiClient.postIssueComment(
                        issueKey = ticketKey,
                        commentText = noteText,
                        settings = settings,
                        filterSettings = filterSettings.value,
                        internalOnly = true
                    ).getOrNull()?.let { repository.upsertSingleTicket(it) }
                }
                Toast.makeText(appContext, "PDF прикреплён к заявке $ticketKey", Toast.LENGTH_LONG).show()
            }.onFailure { err ->
                Toast.makeText(
                    appContext,
                    err.message ?: "Ошибка загрузки PDF в Jira",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
