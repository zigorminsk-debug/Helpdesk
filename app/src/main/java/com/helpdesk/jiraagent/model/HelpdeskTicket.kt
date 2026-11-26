package com.helpdesk.jiraagent.model

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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

data class TicketAttachment(
    val id: String,
    val filename: String,
    val mimeType: String,
    val sizeBytes: Long,
    val contentUrl: String,
    val thumbnailUrl: String? = null,
    val authorName: String = "",
    val createdIso: String = ""
) {
    val isImage: Boolean
        get() {
            val lowerMime = mimeType.lowercase()
            if (lowerMime.startsWith("image/")) return true
            val lowerName = filename.lowercase()
            return lowerName.endsWith(".png") ||
                lowerName.endsWith(".jpg") ||
                lowerName.endsWith(".jpeg") ||
                lowerName.endsWith(".webp") ||
                lowerName.endsWith(".gif") ||
                lowerName.endsWith(".bmp")
        }
}

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
    val assigneeLogin: String? = null,
    val createdIso: String,
    val createdEpochMillis: Long,
    val updatedIso: String,
    val updatedEpochMillis: Long = 0L,
    val resolvedIso: String = "",
    val resolvedEpochMillis: Long = 0L,
    val labels: List<String>,
    val phoneNumbers: List<PhoneMatch>,
    val telegramHandles: List<String>? = emptyList(),
    val comments: List<TicketComment>,
    val attachments: List<TicketAttachment>? = emptyList(),
    val webUrl: String,
    val isClientTicket: Boolean = true,
    val filterReason: String? = null
) {
    val stage: TicketStage
        get() = TicketStage.fromJiraStatus(status, statusCategoryKey)

    /**
     * Exact closure timestamp in epoch milliseconds when the ticket is closed:
     * prefers Jira's `resolutiondate` ([resolvedEpochMillis]), falling back to [updatedEpochMillis]
     * (or [createdEpochMillis]) for workflows that close issues without setting a Jira Resolution.
     */
    val effectiveClosedEpochMillis: Long
        get() = when {
            resolvedEpochMillis > 0L -> resolvedEpochMillis
            stage == TicketStage.CLOSED -> if (updatedEpochMillis > 0L) updatedEpochMillis else createdEpochMillis
            else -> 0L
        }

    /**
     * Resolution duration in whole minutes from arrival ([createdEpochMillis]) to closure ([effectiveClosedEpochMillis]),
     * or `null` if the ticket is not yet closed or timestamps are unavailable.
     */
    val resolutionDurationMinutes: Long?
        get() {
            val closedAt = effectiveClosedEpochMillis
            if (closedAt <= 0L || createdEpochMillis <= 0L || closedAt < createdEpochMillis) return null
            return (closedAt - createdEpochMillis) / 60_000L
        }

    fun isAssignedToAgent(connectedUserDisplayName: String?, usernameOrEmail: String): Boolean {
        val display = assigneeName?.trim().orEmpty()
        val login = assigneeLogin?.trim().orEmpty()
        if (display.isEmpty() && login.isEmpty()) return false

        val candidates = buildList {
            connectedUserDisplayName?.trim()?.takeIf { it.isNotEmpty() }?.let { add(it.lowercase()) }
            val userClean = usernameOrEmail.trim().lowercase()
            if (userClean.isNotEmpty()) {
                add(userClean)
                val beforeAt = userClean.substringBefore('@')
                if (beforeAt.length >= 3) add(beforeAt)
            }
        }.distinct()

        if (candidates.isEmpty()) return false
        val haystack = "$display | $login".lowercase()
        return candidates.any { token -> haystack.contains(token) }
    }
}

data class SyncStatus(
    val isServiceRunning: Boolean = false,
    val isSyncing: Boolean = false,
    val lastSyncEpochMillis: Long? = null,
    val lastError: String? = null,
    val connectedUser: String? = null
)

/**
 * Formats ticket arrival (`Поступила`), closure (`Закрыта`), and resolution duration in Minsk time (`Europe/Minsk`).
 */
object TicketTimingFormatter {

    private val MINSK_ZONE: ZoneId = ZoneId.of("Europe/Minsk")
    private val FULL_DATE_TIME_FMT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(MINSK_ZONE)
    private val SHORT_DATE_TIME_FMT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(MINSK_ZONE)
    private val TIME_ONLY_FMT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("HH:mm").withZone(MINSK_ZONE)

    fun formatFullMinsk(epochMillis: Long): String {
        if (epochMillis <= 0L) return "—"
        return runCatching { FULL_DATE_TIME_FMT.format(Instant.ofEpochMilli(epochMillis)) }
            .getOrDefault("—")
    }

    fun formatCompactMinsk(epochMillis: Long, nowMillis: Long = System.currentTimeMillis()): String {
        if (epochMillis <= 0L) return "—"
        return runCatching {
            val dt = Instant.ofEpochMilli(epochMillis).atZone(MINSK_ZONE)
            val today = Instant.ofEpochMilli(nowMillis).atZone(MINSK_ZONE).toLocalDate()
            when (dt.toLocalDate()) {
                today -> "сегодня ${TIME_ONLY_FMT.format(dt)}"
                today.minusDays(1) -> "вчера ${TIME_ONLY_FMT.format(dt)}"
                else -> SHORT_DATE_TIME_FMT.format(dt)
            }
        }.getOrDefault("—")
    }

    fun formatDurationMinutes(minutes: Long?): String {
        if (minutes == null || minutes < 0L) return "—"
        return when {
            minutes < 1L -> "< 1 мин"
            minutes < 60L -> "$minutes мин"
            minutes < 24L * 60L -> {
                val h = minutes / 60L
                val m = minutes % 60L
                if (m == 0L) "$h ч" else "$h ч $m мин"
            }
            else -> {
                val d = minutes / (24L * 60L)
                val remH = (minutes % (24L * 60L)) / 60L
                if (remH == 0L) "$d дн." else "$d дн. $remH ч"
            }
        }
    }

    fun buildCardTimingSummary(ticket: HelpdeskTicket, nowMillis: Long = System.currentTimeMillis()): String {
        val arrived = formatCompactMinsk(ticket.createdEpochMillis, nowMillis)
        val closedMs = ticket.effectiveClosedEpochMillis
        return if (ticket.stage == TicketStage.CLOSED && closedMs > 0L) {
            val closed = formatCompactMinsk(closedMs, nowMillis)
            val dur = ticket.resolutionDurationMinutes?.let { " (${formatDurationMinutes(it)})" }.orEmpty()
            "📥 $arrived  ·  ✅ Закрыта: $closed$dur"
        } else {
            "📥 Поступила: $arrived"
        }
    }
}

