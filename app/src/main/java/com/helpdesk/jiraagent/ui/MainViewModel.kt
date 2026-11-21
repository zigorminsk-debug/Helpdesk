package com.helpdesk.jiraagent.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.helpdesk.jiraagent.data.JiraApiClient
import com.helpdesk.jiraagent.data.SettingsRepository
import com.helpdesk.jiraagent.domain.PhoneNumberNormalizer
import com.helpdesk.jiraagent.model.AppSettings
import com.helpdesk.jiraagent.model.FilterSettings
import com.helpdesk.jiraagent.model.HelpdeskTicket
import com.helpdesk.jiraagent.model.PhoneMatch
import com.helpdesk.jiraagent.model.SyncStatus
import com.helpdesk.jiraagent.model.TicketComment
import com.helpdesk.jiraagent.notification.NotificationHelper
import com.helpdesk.jiraagent.service.JiraMonitorService
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

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val repository = SettingsRepository.getInstance(appContext)
    private val apiClient = JiraApiClient()

    val appSettings: StateFlow<AppSettings> = repository.appSettings
    val filterSettings: StateFlow<FilterSettings> = repository.filterSettings
    val tickets: StateFlow<List<HelpdeskTicket>> = repository.tickets
    val syncStatus: StateFlow<SyncStatus> = repository.syncStatus

    private val _selectedTicketKey = MutableStateFlow<String?>(null)
    val selectedTicketKey: StateFlow<String?> = _selectedTicketKey.asStateFlow()

    private val _isLoadingTicketDetail = MutableStateFlow(false)
    val isLoadingTicketDetail: StateFlow<Boolean> = _isLoadingTicketDetail.asStateFlow()

    private val _connectionTest = MutableStateFlow(ConnectionTestState())
    val connectionTest: StateFlow<ConnectionTestState> = _connectionTest.asStateFlow()

    init {
        JiraMonitorService.syncServiceState(appContext)
    }

    fun openTicket(ticketKey: String) {
        _selectedTicketKey.value = ticketKey
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
    }

    fun refreshNow() {
        val settings = appSettings.value
        if (!settings.isConfigured) return
        if (settings.pollingEnabled) {
            JiraMonitorService.triggerImmediatePoll(appContext)
        } else {
            viewModelScope.launch {
                repository.updateSyncStatus { it.copy(isSyncing = true) }
                apiClient.fetchRecentTickets(settings, filterSettings.value)
                    .onSuccess { fetched ->
                        val newOnes = repository.processPolledTicketsAndFindNewClientOnes(fetched)
                        newOnes.forEach { NotificationHelper.showClientTicketBanner(appContext, it) }
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

    /**
     * Generates a test client ticket with a local phone number converted to international format
     * and fires a real Android Heads-Up Notification Banner so the agent can verify banners & dialing.
     */
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
                "или резервному номеру 8 (916) 555-01-99. Срочная заявка от клиента.",
            issueType = "Обращение клиента",
            isSubtask = false,
            customerRequestType = "Техническая поддержка",
            hasCustomerPortalInfo = true,
            status = "Открыта",
            statusCategoryKey = "new",
            priority = "Высокий",
            projectKey = "DEMO",
            projectName = "Helpdesk Demo",
            reporterName = "Иван Петров (Клиент)",
            reporterLoginOrEmail = "client@example.com",
            assigneeName = null,
            createdIso = Instant.ofEpochMilli(nowMillis).toString(),
            createdEpochMillis = nowMillis,
            updatedIso = Instant.ofEpochMilli(nowMillis).toString(),
            labels = listOf("клиент"),
            phoneNumbers = listOf(
                PhoneMatch(
                    raw = sampleRawPhone,
                    e164 = normalized.first,
                    formattedDisplay = normalized.second,
                    source = "Поле «Телефон клиента»"
                ),
                PhoneMatch(
                    raw = "8 (916) 555-01-99",
                    e164 = "+79165550199",
                    formattedDisplay = "+7 916 555-01-99",
                    source = "Описание"
                )
            ),
            comments = listOf(
                TicketComment(
                    id = "1",
                    author = "Иван Петров (Клиент)",
                    body = "Жду звонка на $sampleRawPhone, спасибо!",
                    createdIso = Instant.ofEpochMilli(nowMillis).toString()
                )
            ),
            webUrl = "",
            isClientTicket = true,
            filterReason = null
        )

        repository.upsertSingleTicket(demoTicket)
        NotificationHelper.showClientTicketBanner(appContext, demoTicket)
    }
}
