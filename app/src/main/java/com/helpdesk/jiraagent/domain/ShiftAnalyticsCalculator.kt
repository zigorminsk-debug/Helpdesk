package com.helpdesk.jiraagent.domain

import com.helpdesk.jiraagent.model.HelpdeskTicket
import com.helpdesk.jiraagent.model.TicketStage
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

enum class ShiftReportPeriod(val title: String, val daysBack: Long) {
    TODAY("За сегодня", 0L),
    WEEK_7D("За 7 дней", 6L),
    MONTH_30D("За 30 дней", 29L)
}

data class PeriodAnalyticsSummary(
    val period: ShiftReportPeriod,
    val periodRangeDisplay: String,
    val createdInPeriod: Int,
    val closedInPeriod: Int,
    val closedByMeInPeriod: Int,
    val activeNewCount: Int,
    val activeInProgressCount: Int,
    val avgQueueWaitMinutes: Long,
    val slaGreenPercent: Int,
    val engineerStats: List<EngineerStatItem>,
    val topClients: List<ClientStatItem>,
    val matchingTickets: List<HelpdeskTicket>
)

data class EngineerStatItem(
    val engineerName: String,
    val inProgressCount: Int,
    val closedCount: Int
)

data class ClientStatItem(
    val clientName: String,
    val totalCount: Int,
    val openCount: Int
)

data class ShiftAnalyticsReport(
    val createdToday: Int,
    val closedToday: Int,
    val closedByMeToday: Int,
    val createdLast7Days: Int,
    val closedLast7Days: Int,
    val activeNewCount: Int,
    val activeInProgressCount: Int,
    val avgQueueWaitMinutes: Long,
    val slaGreenPercent: Int,
    val engineerStats: List<EngineerStatItem>,
    val topClients: List<ClientStatItem>
)

object ShiftAnalyticsCalculator {

    private val MINSK_ZONE = ZoneId.of("Europe/Minsk")

    fun compute(
        tickets: List<HelpdeskTicket>,
        connectedUserDisplayName: String?,
        usernameOrEmail: String,
        nowMillis: Long = System.currentTimeMillis()
    ): ShiftAnalyticsReport {
        val clientTickets = tickets.filter { it.isClientTicket }
        val today = Instant.ofEpochMilli(nowMillis).atZone(MINSK_ZONE).toLocalDate()
        val startOfTodayMillis = today.atStartOfDay(MINSK_ZONE).toInstant().toEpochMilli()
        val startOf7DaysMillis = today.minusDays(6).atStartOfDay(MINSK_ZONE).toInstant().toEpochMilli()

        val createdToday = clientTickets.count { it.createdEpochMillis >= startOfTodayMillis }
        val closedTickets = clientTickets.filter { it.stage == TicketStage.CLOSED }
        val closedTodayList = closedTickets.filter {
            val ts = if (it.updatedEpochMillis > 0L) it.updatedEpochMillis else it.createdEpochMillis
            ts >= startOfTodayMillis
        }
        val closedToday = closedTodayList.size
        val closedByMeToday = closedTodayList.count {
            it.isAssignedToAgent(connectedUserDisplayName, usernameOrEmail)
        }

        val created7d = clientTickets.count { it.createdEpochMillis >= startOf7DaysMillis }
        val closed7d = closedTickets.count {
            val ts = if (it.updatedEpochMillis > 0L) it.updatedEpochMillis else it.createdEpochMillis
            ts >= startOf7DaysMillis
        }

        val newQueue = clientTickets.filter { it.stage == TicketStage.NEW }
        val inWorkQueue = clientTickets.filter { it.stage == TicketStage.IN_PROGRESS }

        val waitMinutesList = newQueue.mapNotNull { t ->
            if (t.createdEpochMillis <= 0L) null
            else ((nowMillis - t.createdEpochMillis).coerceAtLeast(0L) / 60_000L)
        }
        val avgWait = if (waitMinutesList.isNotEmpty()) {
            waitMinutesList.sum() / waitMinutesList.size
        } else {
            0L
        }

        val slaGreenPercent = if (waitMinutesList.isNotEmpty()) {
            ((waitMinutesList.count { it < 15 } * 100.0) / waitMinutesList.size).toInt()
        } else {
            100
        }

        val engineerMap = linkedMapOf<String, Pair<Int, Int>>()
        for (t in clientTickets) {
            val name = t.assigneeName?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            val prev = engineerMap[name] ?: (0 to 0)
            when (t.stage) {
                TicketStage.IN_PROGRESS -> engineerMap[name] = (prev.first + 1) to prev.second
                TicketStage.CLOSED -> engineerMap[name] = prev.first to (prev.second + 1)
                else -> Unit
            }
        }
        val engineerStats = engineerMap.entries
            .map { EngineerStatItem(it.key, it.value.first, it.value.second) }
            .sortedByDescending { it.inProgressCount * 2 + it.closedCount }
            .take(6)

        val clientMap = linkedMapOf<String, Pair<Int, Int>>()
        for (t in clientTickets) {
            val name = t.reporterName.trim().ifEmpty { "Клиент" }
            val prev = clientMap[name] ?: (0 to 0)
            val isOpen = if (t.stage != TicketStage.CLOSED) 1 else 0
            clientMap[name] = (prev.first + 1) to (prev.second + isOpen)
        }
        val topClients = clientMap.entries
            .map { ClientStatItem(it.key, it.value.first, it.value.second) }
            .sortedByDescending { it.totalCount }
            .take(5)

        return ShiftAnalyticsReport(
            createdToday = createdToday,
            closedToday = closedToday,
            closedByMeToday = closedByMeToday,
            createdLast7Days = created7d,
            closedLast7Days = closed7d,
            activeNewCount = newQueue.size,
            activeInProgressCount = inWorkQueue.size,
            avgQueueWaitMinutes = avgWait,
            slaGreenPercent = slaGreenPercent,
            engineerStats = engineerStats,
            topClients = topClients
        )
    }

    fun computeForPeriod(
        tickets: List<HelpdeskTicket>,
        period: ShiftReportPeriod,
        connectedUserDisplayName: String?,
        usernameOrEmail: String,
        nowMillis: Long = System.currentTimeMillis()
    ): PeriodAnalyticsSummary {
        val base = compute(tickets, connectedUserDisplayName, usernameOrEmail, nowMillis)
        val clientTickets = tickets.filter { it.isClientTicket }
        val today = Instant.ofEpochMilli(nowMillis).atZone(MINSK_ZONE).toLocalDate()
        val fromDate = today.minusDays(period.daysBack)
        val fromEpochMs = fromDate.atStartOfDay(MINSK_ZONE).toInstant().toEpochMilli()
        val dateFmt = DateTimeFormatter.ofPattern("dd.MM.yyyy")
        val rangeLabel = if (period == ShiftReportPeriod.TODAY) {
            today.format(dateFmt)
        } else {
            "${fromDate.format(dateFmt)} – ${today.format(dateFmt)}"
        }

        val inWindow = clientTickets.filter { t ->
            val updated = if (t.updatedEpochMillis > 0L) t.updatedEpochMillis else t.createdEpochMillis
            t.createdEpochMillis >= fromEpochMs || updated >= fromEpochMs
        }

        val createdInPeriod = clientTickets.count { it.createdEpochMillis >= fromEpochMs }
        val closedInPeriodList = clientTickets.filter { t ->
            val ts = if (t.updatedEpochMillis > 0L) t.updatedEpochMillis else t.createdEpochMillis
            t.stage == TicketStage.CLOSED && ts >= fromEpochMs
        }
        val closedByMe = closedInPeriodList.count {
            it.isAssignedToAgent(connectedUserDisplayName, usernameOrEmail)
        }

        val engMap = linkedMapOf<String, Pair<Int, Int>>()
        for (t in inWindow) {
            val rawAssignee = t.assigneeName?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            val human = EngineerNameResolver.resolve(
                rawEngineer = rawAssignee,
                connectedUserDisplayName = connectedUserDisplayName,
                currentUsernameOrEmail = usernameOrEmail,
                knownTickets = clientTickets
            )
            val prev = engMap[human] ?: (0 to 0)
            when (t.stage) {
                TicketStage.IN_PROGRESS -> engMap[human] = (prev.first + 1) to prev.second
                TicketStage.CLOSED -> engMap[human] = prev.first to (prev.second + 1)
                else -> Unit
            }
        }
        val engStats = engMap.entries
            .map { EngineerStatItem(it.key, it.value.first, it.value.second) }
            .sortedByDescending { it.closedCount * 2 + it.inProgressCount }
            .take(12)

        val clMap = linkedMapOf<String, Pair<Int, Int>>()
        for (t in inWindow) {
            val name = t.reporterName.trim().ifEmpty { "Клиент" }
            val prev = clMap[name] ?: (0 to 0)
            val isOpen = if (t.stage != TicketStage.CLOSED) 1 else 0
            clMap[name] = (prev.first + 1) to (prev.second + isOpen)
        }
        val topCl = clMap.entries
            .map { ClientStatItem(it.key, it.value.first, it.value.second) }
            .sortedByDescending { it.totalCount }
            .take(10)

        return PeriodAnalyticsSummary(
            period = period,
            periodRangeDisplay = rangeLabel,
            createdInPeriod = createdInPeriod,
            closedInPeriod = closedInPeriodList.size,
            closedByMeInPeriod = closedByMe,
            activeNewCount = base.activeNewCount,
            activeInProgressCount = base.activeInProgressCount,
            avgQueueWaitMinutes = base.avgQueueWaitMinutes,
            slaGreenPercent = base.slaGreenPercent,
            engineerStats = engStats,
            topClients = topCl,
            matchingTickets = inWindow.sortedByDescending { it.updatedEpochMillis.coerceAtLeast(it.createdEpochMillis) }
        )
    }

    fun buildExcelCsvBytes(summary: PeriodAnalyticsSummary): ByteArray {
        val sb = StringBuilder()
        sb.append('\uFEFF')
        val generatedAt = ZonedDateTime.now(MINSK_ZONE)
            .format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))

        sb.appendLine("Отчёт дежурной смены Help-CSL (${CslCompanyContacts.LEGAL_NAME});${csvSafe(summary.period.title)} (${summary.periodRangeDisplay})")
        sb.appendLine("Сформировано;$generatedAt")
        sb.appendLine()

        sb.appendLine("Показатель;Значение")
        sb.appendLine("Поступило обращений за период;${summary.createdInPeriod}")
        sb.appendLine("Закрыто обращений за период;${summary.closedInPeriod}")
        sb.appendLine("Закрыто мной за период;${summary.closedByMeInPeriod}")
        sb.appendLine("Сейчас в очереди «Новые»;${summary.activeNewCount}")
        sb.appendLine("Сейчас «В работе»;${summary.activeInProgressCount}")
        sb.appendLine("Среднее ожидание реакции (мин);${summary.avgQueueWaitMinutes}")
        sb.appendLine("Соблюдение SLA (< 15 мин);${summary.slaGreenPercent}%")
        sb.appendLine()

        sb.appendLine("Инженер техподдержки;Закрыто за период;Сейчас в работе")
        summary.engineerStats.forEach { e ->
            sb.appendLine("${csvSafe(e.engineerName)};${e.closedCount};${e.inProgressCount}")
        }
        sb.appendLine()

        sb.appendLine("Топ клиентов по обращениям;Всего заявок;Из них открыто")
        summary.topClients.forEach { c ->
            sb.appendLine("${csvSafe(c.clientName)};${c.totalCount};${c.openCount}")
        }
        sb.appendLine()

        val tsFmt = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(MINSK_ZONE)
        sb.appendLine("Детализация заявок;Клиент;Тема обращения;Статус;Исполнитель;Создано")
        summary.matchingTickets.take(150).forEach { t ->
            val createdStr = if (t.createdEpochMillis > 0L) {
                runCatching { tsFmt.format(Instant.ofEpochMilli(t.createdEpochMillis)) }.getOrDefault("")
            } else ""
            sb.appendLine(
                listOf(
                    csvSafe(t.key),
                    csvSafe(t.reporterName),
                    csvSafe(t.summary),
                    csvSafe(t.status),
                    csvSafe(t.assigneeName ?: "Не назначен"),
                    csvSafe(createdStr)
                ).joinToString(";")
            )
        }

        return sb.toString().replace("\n", "\r\n").toByteArray(StandardCharsets.UTF_8)
    }

    private fun csvSafe(raw: String): String {
        val cleaned = raw.replace('\r', ' ').replace('\n', ' ').trim()
        val guarded = if (cleaned.startsWith("=") || cleaned.startsWith("@")) "'$cleaned" else cleaned
        return if (';' in guarded || '"' in guarded) {
            "\"${guarded.replace("\"", "\"\"")}\""
        } else {
            guarded
        }
    }
}
