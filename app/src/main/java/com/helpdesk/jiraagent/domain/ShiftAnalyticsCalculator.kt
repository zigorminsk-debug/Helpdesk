package com.helpdesk.jiraagent.domain

import com.helpdesk.jiraagent.model.HelpdeskTicket
import com.helpdesk.jiraagent.model.TicketStage
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

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
}
