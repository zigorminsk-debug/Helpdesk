package com.helpdesk.jiraagent.domain

import com.helpdesk.jiraagent.model.HelpdeskTicket
import com.helpdesk.jiraagent.model.TicketStage
import com.helpdesk.jiraagent.model.TicketTimingFormatter
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

enum class ShiftReportPeriod(val title: String, val daysBack: Long, val singleDayOnly: Boolean = false) {
    TODAY("За сегодня", 0L, singleDayOnly = false),
    YESTERDAY("За вчера", 1L, singleDayOnly = true),
    WEEK_7D("За 7 дней", 6L, singleDayOnly = false),
    MONTH_30D("За 30 дней", 29L, singleDayOnly = false)
}

enum class EmployeeActivityStatus(val badgeLabel: String) {
    ACTIVE_NOW("🟢 Активен сейчас"),
    ACTIVE_RECENT("🟡 Активен за период"),
    IDLE("⚪ Нет активности")
}

data class EmployeeKpiItem(
    val engineerName: String,
    val isCurrentUser: Boolean,
    val closedCount: Int,
    val inProgressCount: Int,
    val assignedNewCount: Int,
    val totalHandledCount: Int,
    val shareOfTeamClosedPercent: Int,
    val avgResolutionMinutes: Long?,
    val medianResolutionMinutes: Long?,
    val fastCloseUnder1hPercent: Int,
    val stopwatchTrackedMinutes: Int,
    val lastActivityEpochMs: Long,
    val activityStatus: EmployeeActivityStatus,
    val tickets: List<HelpdeskTicket>
)

data class PeriodAnalyticsSummary(
    val period: ShiftReportPeriod,
    val periodRangeDisplay: String,
    val createdInPeriod: Int,
    val closedInPeriod: Int,
    val closedByMeInPeriod: Int,
    val activeNewCount: Int,
    val activeInProgressCount: Int,
    val avgQueueWaitMinutes: Long,
    val avgTeamResolutionMinutes: Long?,
    val slaGreenPercent: Int,
    val engineerStats: List<EngineerStatItem>,
    val employeeKpis: List<EmployeeKpiItem>,
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
            it.effectiveClosedEpochMillis >= startOfTodayMillis
        }
        val closedToday = closedTodayList.size
        val closedByMeToday = closedTodayList.count {
            it.isAssignedToAgent(connectedUserDisplayName, usernameOrEmail)
        }

        val created7d = clientTickets.count { it.createdEpochMillis >= startOf7DaysMillis }
        val closed7d = closedTickets.count {
            it.effectiveClosedEpochMillis >= startOf7DaysMillis
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
        stopwatches: Map<String, TicketStopwatchEntry> = emptyMap(),
        nowMillis: Long = System.currentTimeMillis()
    ): PeriodAnalyticsSummary {
        val base = compute(tickets, connectedUserDisplayName, usernameOrEmail, nowMillis)
        val clientTickets = tickets.filter { it.isClientTicket }.distinctBy { it.key.uppercase() }
        val today = Instant.ofEpochMilli(nowMillis).atZone(MINSK_ZONE).toLocalDate()
        val fromDate = today.minusDays(period.daysBack)
        val windowStartMs = fromDate.atStartOfDay(MINSK_ZONE).toInstant().toEpochMilli()
        val windowEndExclusiveMs = if (period.singleDayOnly) {
            fromDate.plusDays(1).atStartOfDay(MINSK_ZONE).toInstant().toEpochMilli()
        } else {
            Long.MAX_VALUE
        }

        val dateFmt = DateTimeFormatter.ofPattern("dd.MM.yyyy")
        val rangeLabel = when {
            period == ShiftReportPeriod.TODAY -> today.format(dateFmt)
            period.singleDayOnly -> fromDate.format(dateFmt)
            else -> "${fromDate.format(dateFmt)} – ${today.format(dateFmt)}"
        }

        fun inPeriod(epochMs: Long): Boolean = epochMs in windowStartMs until windowEndExclusiveMs

        val inWindow = clientTickets.filter { t ->
            val closedTs = t.effectiveClosedEpochMillis
            val updatedTs = if (t.updatedEpochMillis > 0L) t.updatedEpochMillis else t.createdEpochMillis
            inPeriod(t.createdEpochMillis) ||
                (closedTs > 0L && inPeriod(closedTs)) ||
                inPeriod(updatedTs) ||
                (!period.singleDayOnly && t.stage == TicketStage.IN_PROGRESS)
        }

        val createdInPeriod = clientTickets.count { inPeriod(it.createdEpochMillis) }
        val closedInPeriodList = clientTickets.filter { t ->
            t.stage == TicketStage.CLOSED && t.effectiveClosedEpochMillis > 0L && inPeriod(t.effectiveClosedEpochMillis)
        }
        val closedByMe = closedInPeriodList.count {
            it.isAssignedToAgent(connectedUserDisplayName, usernameOrEmail)
        }

        val teamDurations = closedInPeriodList.mapNotNull { it.resolutionDurationMinutes }
        val avgTeamResolution = if (teamDurations.isNotEmpty()) {
            teamDurations.sum() / teamDurations.size
        } else null

        val myResolvedName = EngineerNameResolver.resolve(
            rawEngineer = connectedUserDisplayName ?: usernameOrEmail,
            connectedUserDisplayName = connectedUserDisplayName,
            currentUsernameOrEmail = usernameOrEmail,
            knownTickets = clientTickets
        )

        // Group all staff activity across tickets in window + currently active IN_PROGRESS/NEW assigned tickets
        data class MutableEmpBucket(
            val name: String,
            var isMe: Boolean = false,
            val closedList: MutableList<HelpdeskTicket> = mutableListOf(),
            val inProgressList: MutableList<HelpdeskTicket> = mutableListOf(),
            val assignedNewList: MutableList<HelpdeskTicket> = mutableListOf(),
            val allTicketsByKey: LinkedHashMap<String, HelpdeskTicket> = linkedMapOf(),
            var lastActivityMs: Long = 0L
        )

        val buckets = linkedMapOf<String, MutableEmpBucket>()

        fun bucketFor(rawAssignee: String, isMeHint: Boolean): MutableEmpBucket {
            val human = EngineerNameResolver.resolve(
                rawEngineer = rawAssignee,
                connectedUserDisplayName = connectedUserDisplayName,
                currentUsernameOrEmail = usernameOrEmail,
                knownTickets = clientTickets
            )
            val normKey = human.trim().lowercase()
            val existing = buckets[normKey]
            if (existing != null) {
                if (isMeHint) existing.isMe = true
                return existing
            }
            val created = MutableEmpBucket(
                name = human,
                isMe = isMeHint || (myResolvedName.isNotBlank() && human.equals(myResolvedName, ignoreCase = true))
            )
            buckets[normKey] = created
            return created
        }

        // 1. Live active tickets across the whole board (IN_PROGRESS & assigned NEW)
        for (t in clientTickets) {
            val rawAssignee = t.assigneeName?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            val isMe = t.isAssignedToAgent(connectedUserDisplayName, usernameOrEmail)
            when (t.stage) {
                TicketStage.IN_PROGRESS -> {
                    val b = bucketFor(rawAssignee, isMe)
                    b.inProgressList.add(t)
                    b.allTicketsByKey[t.key] = t
                    val actTs = maxOf(t.updatedEpochMillis, t.createdEpochMillis)
                    if (actTs > b.lastActivityMs) b.lastActivityMs = actTs
                }
                TicketStage.NEW -> {
                    if (!period.singleDayOnly || inPeriod(t.createdEpochMillis) || inPeriod(t.updatedEpochMillis)) {
                        val b = bucketFor(rawAssignee, isMe)
                        b.assignedNewList.add(t)
                        b.allTicketsByKey[t.key] = t
                        val actTs = maxOf(t.updatedEpochMillis, t.createdEpochMillis)
                        if (actTs > b.lastActivityMs) b.lastActivityMs = actTs
                    }
                }
                TicketStage.CLOSED -> Unit
            }
        }

        // 2. Closed tickets in the selected period
        for (t in closedInPeriodList) {
            val rawAssignee = t.assigneeName?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            val isMe = t.isAssignedToAgent(connectedUserDisplayName, usernameOrEmail)
            val b = bucketFor(rawAssignee, isMe)
            b.closedList.add(t)
            b.allTicketsByKey[t.key] = t
            val actTs = maxOf(t.effectiveClosedEpochMillis, t.updatedEpochMillis, t.createdEpochMillis)
            if (actTs > b.lastActivityMs) b.lastActivityMs = actTs
        }

        val totalTeamClosed = closedInPeriodList.count { !it.assigneeName.isNullOrBlank() }.coerceAtLeast(1)

        val employeeKpis = buckets.values
            .map { b ->
                val durations = b.closedList.mapNotNull { it.resolutionDurationMinutes }.sorted()
                val avgRes = if (durations.isNotEmpty()) durations.sum() / durations.size else null
                val medRes = if (durations.isNotEmpty()) {
                    val mid = durations.size / 2
                    if (durations.size % 2 == 1) durations[mid] else (durations[mid - 1] + durations[mid]) / 2L
                } else null
                val fastClosePct = if (durations.isNotEmpty()) {
                    ((durations.count { it <= 60L } * 100.0) / durations.size).toInt()
                } else 0

                val sharePct = if (b.closedList.isNotEmpty()) {
                    ((b.closedList.size * 100.0) / totalTeamClosed).toInt().coerceIn(1, 100)
                } else 0

                val trackedMins = if (b.isMe && stopwatches.isNotEmpty()) {
                    b.allTicketsByKey.keys.sumOf { key ->
                        val sw = stopwatches[key]
                        if (sw != null) TicketStopwatchTracker.roundedMinutesForWorklog(sw, nowMillis) else 0
                    }
                } else 0

                val status = when {
                    b.inProgressList.isNotEmpty() || (b.lastActivityMs > 0L && (nowMillis - b.lastActivityMs) <= 90L * 60_000L) ->
                        EmployeeActivityStatus.ACTIVE_NOW
                    b.closedList.isNotEmpty() || b.assignedNewList.isNotEmpty() ->
                        EmployeeActivityStatus.ACTIVE_RECENT
                    else ->
                        EmployeeActivityStatus.IDLE
                }

                val sortedTickets = b.allTicketsByKey.values.sortedByDescending {
                    maxOf(it.effectiveClosedEpochMillis, it.updatedEpochMillis, it.createdEpochMillis)
                }

                EmployeeKpiItem(
                    engineerName = b.name,
                    isCurrentUser = b.isMe,
                    closedCount = b.closedList.size,
                    inProgressCount = b.inProgressList.size,
                    assignedNewCount = b.assignedNewList.size,
                    totalHandledCount = b.allTicketsByKey.size,
                    shareOfTeamClosedPercent = sharePct,
                    avgResolutionMinutes = avgRes,
                    medianResolutionMinutes = medRes,
                    fastCloseUnder1hPercent = fastClosePct,
                    stopwatchTrackedMinutes = trackedMins,
                    lastActivityEpochMs = b.lastActivityMs,
                    activityStatus = status,
                    tickets = sortedTickets
                )
            }
            .sortedWith(
                compareByDescending<EmployeeKpiItem> { it.closedCount }
                    .thenByDescending { it.inProgressCount }
                    .thenByDescending { it.assignedNewCount }
                    .thenBy { it.engineerName }
            )

        val engStats = employeeKpis
            .map { EngineerStatItem(it.engineerName, it.inProgressCount, it.closedCount) }
            .take(20)

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
            avgTeamResolutionMinutes = avgTeamResolution,
            slaGreenPercent = base.slaGreenPercent,
            engineerStats = engStats,
            employeeKpis = employeeKpis,
            topClients = topCl,
            matchingTickets = inWindow.sortedByDescending {
                maxOf(it.effectiveClosedEpochMillis, it.updatedEpochMillis, it.createdEpochMillis)
            }
        )
    }

    fun buildExcelCsvBytes(summary: PeriodAnalyticsSummary): ByteArray {
        val sb = StringBuilder()
        sb.append('\uFEFF')
        val generatedAt = ZonedDateTime.now(MINSK_ZONE)
            .format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))

        sb.appendLine("Отчёт KPI и активности сотрудников Help-CSL (${CslCompanyContacts.LEGAL_NAME});${csvSafe(summary.period.title)} (${summary.periodRangeDisplay})")
        sb.appendLine("Сформировано;$generatedAt")
        sb.appendLine()

        sb.appendLine("Показатель;Значение")
        sb.appendLine("Поступило обращений за период;${summary.createdInPeriod}")
        sb.appendLine("Закрыто обращений за период;${summary.closedInPeriod}")
        sb.appendLine("Закрыто мной за период;${summary.closedByMeInPeriod}")
        sb.appendLine("Сейчас в очереди «Новые»;${summary.activeNewCount}")
        sb.appendLine("Сейчас «В работе»;${summary.activeInProgressCount}")
        sb.appendLine("Среднее ожидание реакции (мин);${summary.avgQueueWaitMinutes}")
        sb.appendLine("Среднее время решения по команде;${TicketTimingFormatter.formatDurationMinutes(summary.avgTeamResolutionMinutes)}")
        sb.appendLine("Соблюдение SLA (< 15 мин);${summary.slaGreenPercent}%")
        sb.appendLine()

        sb.appendLine("KPI по сотрудникам;Закрыто за период;Доля команды (%);Сейчас в работе;Назначено новых;Ср. время решения;Медиана решения;Закрыто < 1 ч (%);Последняя активность")
        summary.employeeKpis.forEach { e ->
            val lastAct = if (e.lastActivityEpochMs > 0L) TicketTimingFormatter.formatFullMinsk(e.lastActivityEpochMs) else "—"
            sb.appendLine(
                listOf(
                    csvSafe(e.engineerName),
                    e.closedCount.toString(),
                    "${e.shareOfTeamClosedPercent}%",
                    e.inProgressCount.toString(),
                    e.assignedNewCount.toString(),
                    csvSafe(TicketTimingFormatter.formatDurationMinutes(e.avgResolutionMinutes)),
                    csvSafe(TicketTimingFormatter.formatDurationMinutes(e.medianResolutionMinutes)),
                    "${e.fastCloseUnder1hPercent}%",
                    csvSafe(lastAct)
                ).joinToString(";")
            )
        }
        sb.appendLine()

        sb.appendLine("Топ клиентов по обращениям;Всего заявок;Из них открыто")
        summary.topClients.forEach { c ->
            sb.appendLine("${csvSafe(c.clientName)};${c.totalCount};${c.openCount}")
        }
        sb.appendLine()

        sb.appendLine("Номер заявки;Клиент;Тема обращения;Статус;Исполнитель;Время поступления;Время закрытия;Время решения (мин)")
        summary.matchingTickets.take(300).forEach { t ->
            val createdStr = if (t.createdEpochMillis > 0L) TicketTimingFormatter.formatFullMinsk(t.createdEpochMillis) else ""
            val closedMs = t.effectiveClosedEpochMillis
            val closedStr = if (t.stage == TicketStage.CLOSED && closedMs > 0L) TicketTimingFormatter.formatFullMinsk(closedMs) else ""
            val durMins = t.resolutionDurationMinutes?.toString().orEmpty()
            val humanAssignee = t.assigneeName?.takeIf { it.isNotBlank() }?.let {
                EngineerNameResolver.resolve(rawEngineer = it, knownTickets = summary.matchingTickets)
            } ?: "Не назначен"
            sb.appendLine(
                listOf(
                    csvSafe(t.key),
                    csvSafe(t.reporterName),
                    csvSafe(t.summary),
                    csvSafe(t.status),
                    csvSafe(humanAssignee),
                    csvSafe(createdStr),
                    csvSafe(closedStr),
                    durMins
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
