package com.helpdesk.jiraagent.model

enum class TicketStage {
    NEW,
    IN_PROGRESS,
    CLOSED;

    companion object {
        private val CLOSED_KEYWORDS = listOf(
            "закрыт", "решен", "выполнен", "завершен", "готов",
            "отменен", "отклонен", "closed", "resolved", "done",
            "completed", "cancelled", "canceled", "declined"
        )

        private val NEW_KEYWORDS = listOf(
            "ожидание поддержки", "ожидаие поддержки", "ожидает поддержки", "ждет поддержки",
            "waiting for support", "waiting for triage", "triage",
            "новая", "новый", "новые", "new",
            "открыт", "open", "reopened", "переоткрыт",
            "создан", "created",
            "к выполнению", "сделать", "to do", "todo", "backlog", "очередь", "в очереди"
        )

        private val IN_PROGRESS_KEYWORDS = listOf(
            "в работе", "в процессе", "выполняется", "исполняется", "обрабатывается", "обработка",
            "на рассмотрении", "рассмотрение", "диагностика", "анализ",
            "ожидание клиента", "ожидает клиента", "ожидание ответа", "запрос информации", "уточнение",
            "на согласовании", "согласование", "ожидание поставщика", "ожидание третьей", "подрядчик",
            "эскалац", "передан", "назначен", "отложен", "пауза", "приостановлен", "тестирован", "проверка",
            "in progress", "in work", "working", "assigned", "investigating", "under review",
            "waiting for customer", "waiting for info", "pending", "escalated", "on hold", "testing"
        )

        fun fromJiraStatus(statusName: String, statusCategoryKey: String): TicketStage {
            val cat = statusCategoryKey.trim().lowercase()
            val normalizedStatus = statusName.trim().lowercase().replace('ё', 'е')

            if (cat == "done" || CLOSED_KEYWORDS.any { normalizedStatus.contains(it) }) {
                return CLOSED
            }

            if (NEW_KEYWORDS.any { normalizedStatus.contains(it) }) {
                return NEW
            }

            if (IN_PROGRESS_KEYWORDS.any { normalizedStatus.contains(it) }) {
                return IN_PROGRESS
            }

            if (cat == "indeterminate") {
                return IN_PROGRESS
            }

            return NEW
        }
    }
}

data class PhoneMatch(
    val raw: String,
    val e164: String,
    val formattedDisplay: String,
    val source: String
)

data class TicketComment(
    val id: String,
    val author: String,
    val body: String,
    val createdIso: String
)

data class HelpdeskTicket(
    val id: String,
    val key: String,
    val summary: String,
    val description: String,
    val issueType: String,
    val isSubtask: Boolean,
    val customerRequestType: String?,
    val hasCustomerPortalInfo: Boolean,
    val status: String,
    val statusCategoryKey: String,
    val priority: String,
    val projectKey: String,
    val projectName: String,
    val reporterName: String,
    val reporterLoginOrEmail: String,
    val assigneeName: String?,
    val createdIso: String,
    val createdEpochMillis: Long,
    val updatedIso: String,
    val updatedEpochMillis: Long = 0L,
    val labels: List<String>,
    val phoneNumbers: List<PhoneMatch>,
    val telegramHandles: List<String>? = emptyList(),
    val comments: List<TicketComment>,
    val webUrl: String,
    val isClientTicket: Boolean = true,
    val filterReason: String? = null
) {
    val stage: TicketStage
        get() = TicketStage.fromJiraStatus(status, statusCategoryKey)
}

data class SyncStatus(
    val isServiceRunning: Boolean = false,
    val isSyncing: Boolean = false,
    val lastSyncEpochMillis: Long? = null,
    val lastError: String? = null,
    val connectedUser: String? = null
)
