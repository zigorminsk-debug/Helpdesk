package com.helpdesk.jiraagent.domain

import com.helpdesk.jiraagent.model.AppSettings
import com.helpdesk.jiraagent.model.HelpdeskTicket
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.max

enum class TicketAgeFilter {
    ALL,
    FRESH_TODAY,
    PAST_HANGING
}

enum class TicketHangingSeverity {
    FRESH_TODAY,
    PAST_RECENT, // yesterday .. 2 days
    PAST_LONG    // 3+ days
}

data class TicketFreshnessInfo(
    val isFreshToday: Boolean,
    val arrivedDuringWorkday: Boolean,
    val calendarDaysAgo: Long,
    val elapsedHours: Long,
    val severity: TicketHangingSeverity,
    val fullBadgeLabel: String,
    val compactBadgeLabel: String
)

object TicketFreshnessClassifier {

    private val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private val SHORT_DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM")

    /**
     * Returns true if [ticket] arrived on today's calendar date in [zoneId]
     * (including slight server clock drift forward).
     */
    fun isFreshToday(
        ticket: HelpdeskTicket,
        nowMillis: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): Boolean {
        val today = Instant.ofEpochMilli(nowMillis).atZone(zoneId).toLocalDate()
        val createdDate = resolveCreatedDate(ticket, zoneId) ?: return false
        return !createdDate.isBefore(today)
    }

    /**
     * Splits [tickets] into `(freshTodayTickets, pastHangingTickets)` preserving order.
     */
    fun partitionFreshAndPast(
        tickets: List<HelpdeskTicket>,
        nowMillis: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): Pair<List<HelpdeskTicket>, List<HelpdeskTicket>> {
        val today = Instant.ofEpochMilli(nowMillis).atZone(zoneId).toLocalDate()
        val fresh = ArrayList<HelpdeskTicket>(tickets.size)
        val past = ArrayList<HelpdeskTicket>(tickets.size)
        for (t in tickets) {
            val createdDate = resolveCreatedDate(t, zoneId)
            if (createdDate != null && !createdDate.isBefore(today)) {
                fresh.add(t)
            } else {
                past.add(t)
            }
        }
        return fresh to past
    }

    /**
     * Computes human-readable Russian freshness / hanging badge info for [ticket]
     * in «Новые» and «В работе».
     */
    fun classify(
        ticket: HelpdeskTicket,
        settings: AppSettings? = null,
        nowMillis: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): TicketFreshnessInfo {
        val today = Instant.ofEpochMilli(nowMillis).atZone(zoneId).toLocalDate()
        val createdMs = ticket.createdEpochMillis

        if (createdMs <= 0L) {
            val isoDate = resolveCreatedDate(ticket, zoneId)
            if (isoDate != null && !isoDate.isBefore(today)) {
                return TicketFreshnessInfo(
                    isFreshToday = true,
                    arrivedDuringWorkday = false,
                    calendarDaysAgo = 0L,
                    elapsedHours = 0L,
                    severity = TicketHangingSeverity.FRESH_TODAY,
                    fullBadgeLabel = "Свежая · сегодня",
                    compactBadgeLabel = "Сегодня"
                )
            }
            val days = isoDate?.let { max(1L, ChronoUnit.DAYS.between(it, today)) } ?: 1L
            val daysLabel = formatDaysRu(days)
            val sev = if (days >= 3L) TicketHangingSeverity.PAST_LONG else TicketHangingSeverity.PAST_RECENT
            return TicketFreshnessInfo(
                isFreshToday = false,
                arrivedDuringWorkday = false,
                calendarDaysAgo = days,
                elapsedHours = days * 24L,
                severity = sev,
                fullBadgeLabel = "Прошлая · висит $daysLabel",
                compactBadgeLabel = "Висит $days дн."
            )
        }

        val createdInstant = Instant.ofEpochMilli(createdMs)
        val createdZdt = createdInstant.atZone(zoneId)
        val createdDate = createdZdt.toLocalDate()
        val elapsedMs = max(0L, nowMillis - createdMs)
        val elapsedHours = elapsedMs / 3_600_000L

        if (!createdDate.isBefore(today)) {
            val timeStr = createdZdt.format(TIME_FMT)
            val duringWorkday = settings?.let {
                runCatching { BelarusWorkCalendar.isCalendarWorkingHoursNow(it, createdInstant) }
                    .getOrDefault(false)
            } ?: false

            val full = if (duringWorkday) {
                "Свежая · сегодня в $timeStr (раб. день)"
            } else {
                "Свежая · сегодня в $timeStr"
            }
            val compact = "Сегодня $timeStr"
            return TicketFreshnessInfo(
                isFreshToday = true,
                arrivedDuringWorkday = duringWorkday,
                calendarDaysAgo = 0L,
                elapsedHours = elapsedHours,
                severity = TicketHangingSeverity.FRESH_TODAY,
                fullBadgeLabel = full,
                compactBadgeLabel = compact
            )
        }

        val daysAgo = max(1L, ChronoUnit.DAYS.between(createdDate, today))
        val dateStr = createdZdt.format(SHORT_DATE_FMT)
        val severity = if (daysAgo >= 3L) TicketHangingSeverity.PAST_LONG else TicketHangingSeverity.PAST_RECENT

        val fullLabel = if (daysAgo == 1L) {
            "Прошлая · со вчера ($dateStr)"
        } else {
            "Прошлая · висит ${formatDaysRu(daysAgo)} (с $dateStr)"
        }
        val compactLabel = if (daysAgo == 1L) {
            "Со вчера ($dateStr)"
        } else {
            "Висит $daysAgo дн. (с $dateStr)"
        }

        return TicketFreshnessInfo(
            isFreshToday = false,
            arrivedDuringWorkday = false,
            calendarDaysAgo = daysAgo,
            elapsedHours = elapsedHours,
            severity = severity,
            fullBadgeLabel = fullLabel,
            compactBadgeLabel = compactLabel
        )
    }

    private fun resolveCreatedDate(ticket: HelpdeskTicket, zoneId: ZoneId): LocalDate? {
        if (ticket.createdEpochMillis > 0L) {
            return runCatching {
                Instant.ofEpochMilli(ticket.createdEpochMillis).atZone(zoneId).toLocalDate()
            }.getOrNull()
        }
        val raw = ticket.createdIso.trim()
        if (raw.length >= 10) {
            return runCatching { LocalDate.parse(raw.substring(0, 10)) }.getOrNull()
        }
        return null
    }

    fun formatDaysRu(days: Long): String {
        val mod100 = (days % 100L).toInt()
        val mod10 = (days % 10L).toInt()
        val word = when {
            mod100 in 11..19 -> "дней"
            mod10 == 1 -> "день"
            mod10 in 2..4 -> "дня"
            else -> "дней"
        }
        return "$days $word"
    }
}
