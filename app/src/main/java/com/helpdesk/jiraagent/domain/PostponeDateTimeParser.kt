package com.helpdesk.jiraagent.domain

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object PostponeDateTimeParser {

    val DISPLAY_DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
    val DISPLAY_TIME_DASH_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH-mm")
    val DISPLAY_FULL_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")

    /**
     * Parses date strings like "22.11.2026", "22/11/2026", "2026-11-22", "22.11.26".
     */
    fun parseDate(raw: String): LocalDate? {
        val cleaned = raw.trim().replace('/', '.').replace('-', '.')
        if (cleaned.isEmpty()) return null
        val parts = cleaned.split('.').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size != 3) return null

        val p0 = parts[0].toIntOrNull() ?: return null
        val p1 = parts[1].toIntOrNull() ?: return null
        val p2 = parts[2].toIntOrNull() ?: return null

        return runCatching {
            if (parts[0].length == 4) {
                LocalDate.of(p0, p1, p2)
            } else {
                val fullYear = if (p2 in 0..99) 2000 + p2 else p2
                LocalDate.of(fullYear, p1, p0)
            }
        }.getOrNull()
    }

    /**
     * Parses time strings like "12-00", "12:00", "9-30", "09:05", "12.00".
     */
    fun parseTime(raw: String): LocalTime? {
        val cleaned = raw.trim().replace('-', ':').replace('.', ':').replace(' ', ':')
        if (cleaned.isEmpty()) return null
        val parts = cleaned.split(':').filter { it.isNotEmpty() }
        if (parts.size < 2) return null

        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts[1].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return LocalTime.of(hour, minute)
    }

    fun parseToEpochMillis(
        dateInput: String,
        timeInput: String,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): Pair<Long, String>? {
        val date = parseDate(dateInput) ?: return null
        val time = parseTime(timeInput) ?: return null
        val zdt = LocalDateTime.of(date, time).atZone(zoneId)
        val formatted = zdt.format(DISPLAY_FULL_FMT)
        return zdt.toInstant().toEpochMilli() to formatted
    }

    fun formatEpochMillis(
        millis: Long,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): String {
        return Instant.ofEpochMilli(millis).atZone(zoneId).format(DISPLAY_FULL_FMT)
    }
}
