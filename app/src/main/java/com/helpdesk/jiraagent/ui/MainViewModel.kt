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
import com.helpdesk.jiraagent.data.JiraTransitionOption
import com.helpdesk.jiraagent.data.SettingsRepository
import com.helpdesk.jiraagent.data.TicketWorkflowAction
import com.helpdesk.jiraagent.domain.BelarusWorkCalendar
import com.helpdesk.jiraagent.domain.HostDiagnosticReport
import com.helpdesk.jiraagent.domain.NetworkDiagnosticsTool
import com.helpdesk.jiraagent.domain.PhoneNumberNormalizer
import com.helpdesk.jiraagent.model.AdminChatMessage
import com.helpdesk.jiraagent.model.AppSettings
import com.helpdesk.jiraagent.model.FilterSettings
import com.helpdesk.jiraagent.model.HelpdeskTicket
import com.helpdesk.jiraagent.model.OfflineQueuedAction
import com.helpdesk.jiraagent.model.PhoneMatch
import com.helpdesk.jiraagent.model.PostponedTicket
import com.helpdesk.jiraagent.model.QueuedActionType
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
            .take(5)

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
                filterSettings = filterSettings.value
            ).onSuccess { remoteItems ->
                val merged = (remoteItems + localMatches)
                    .distinctBy { it.key.uppercase() }
                    .sortedByDescending { it.createdEpochMillis }
                    .take(5)
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

    fun addTicketComment(ticketKey: String, commentText: String) {
        val clean = commentText.trim()
        if (clean.isEmpty()) return
        val settings = appSettings.value

        if (ticketKey.startsWith("DEMO-")) {
            val existing = tickets.value.firstOrNull { it.key == ticketKey } ?: return
            val nowIso = Instant.now().toString()
            val author = syncStatus.value.connectedUser
                ?: settings.usernameOrEmail.ifBlank { "Агент Help-CSL" }
            val newComment = TicketComment(
                id = System.currentTimeMillis().toString(),
                author = author,
                body = clean,
                createdIso = nowIso
            )
            repository.upsertSingleTicket(
                existing.copy(comments = existing.comments + newComment)
            )
            _ticketActionState.value = TicketActionUiState(
                isBusy = false,
                statusMessage = "Комментарий добавлен",
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
                filterSettings = filterSettings.value
            ).onSuccess { refreshed ->
                refreshed?.let { repository.upsertSingleTicket(it) }
                _ticketActionState.value = TicketActionUiState(
                    isBusy = false,
                    statusMessage = "Комментарий отправлен в Jira",
                    isError = false
                )
            }.onFailure { err ->
                if ((err as? JiraApiException)?.isNetworkError == true) {
                    repository.enqueueOfflineAction(
                        OfflineQueuedAction(
                            type = QueuedActionType.ADD_COMMENT,
                            ticketKey = ticketKey,
                            commentText = clean
                        )
                    )
                    val existing = tickets.value.firstOrNull { it.key.equals(ticketKey, ignoreCase = true) }
                    if (existing != null) {
                        val author = syncStatus.value.connectedUser
                            ?: settings.usernameOrEmail.ifBlank { "Агент Help-CSL" }
                        val offlineComment = TicketComment(
                            id = "offline_${System.currentTimeMillis()}",
                            author = "$author (в очереди)",
                            body = clean,
                            createdIso = Instant.now().toString()
                        )
                        repository.upsertSingleTicket(existing.copy(comments = existing.comments + offlineComment))
                    }
                    _ticketActionState.value = TicketActionUiState(
                        isBusy = false,
                        statusMessage = "Нет сети: комментарий в офлайн-очереди и отправится автоматически",
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
}
