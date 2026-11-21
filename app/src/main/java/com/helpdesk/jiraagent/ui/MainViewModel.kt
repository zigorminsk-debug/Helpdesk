package com.helpdesk.jiraagent.ui

import android.app.Application
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.helpdesk.jiraagent.data.InAppBannerAlert
import com.helpdesk.jiraagent.data.JiraApiClient
import com.helpdesk.jiraagent.data.JiraTransitionOption
import com.helpdesk.jiraagent.data.SettingsRepository
import com.helpdesk.jiraagent.data.TicketWorkflowAction
import com.helpdesk.jiraagent.domain.BelarusWorkCalendar
import com.helpdesk.jiraagent.domain.PhoneNumberNormalizer
import com.helpdesk.jiraagent.model.AppSettings
import com.helpdesk.jiraagent.model.FilterSettings
import com.helpdesk.jiraagent.model.HelpdeskTicket
import com.helpdesk.jiraagent.model.PhoneMatch
import com.helpdesk.jiraagent.model.PostponedTicket
import com.helpdesk.jiraagent.model.SyncStatus
import com.helpdesk.jiraagent.model.TicketComment
import com.helpdesk.jiraagent.notification.NotificationHelper
import com.helpdesk.jiraagent.service.JiraMonitorService
import com.helpdesk.jiraagent.service.PostponeScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant

data class ConnectionTestState(
    val isTesting: Boolean = false,
    val isSuccess: Boolean? = null,
    val message: String? = null
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

    init {
        JiraMonitorService.syncServiceState(appContext)
    }

    fun openTicket(ticketKey: String) {
        _selectedTicketKey.value = ticketKey
        _ticketActionState.value = TicketActionUiState()
        val settings = appSettings.value
        if (!settings.isConfigured || ticketKey.startsWith("DEMO-")) return

        viewModelScope.launch {
            _isLoadingTicketDetail.value = true
            apiClient.fetchTicketByKey(ticketKey, settings, filterSettings.value)
                .onSuccess { freshTicket ->
                    repository.upsertSingleTicket(freshTicket)
                }
            _isLoadingTicketDetail.value = false
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
                _ticketActionState.value = TicketActionUiState(
                    isBusy = false,
                    statusMessage = err.message ?: "Не удалось выполнить действие в Jira",
                    isError = true
                )
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
}
