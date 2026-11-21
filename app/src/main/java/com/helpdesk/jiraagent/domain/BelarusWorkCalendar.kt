package com.helpdesk.jiraagent.domain

import com.helpdesk.jiraagent.model.AppSettings
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

data class WorkScheduleStatus(
    val isAllowedNow: Boolean,
    val isWorkingDay: Boolean,
    val statusTitle: String,
    val detailMessage: String
)

/**
 * Production / Working Days Calendar of the Republic of Belarus (РБ):
 * - Timezone: Europe/Minsk (UTC+3).
 * - Annual official non-working public holidays per Presidential Decree No. 157:
 *   1–2 Jan, 7 Jan, 8 Mar, Radunitsa (Orthodox Easter + 9 days, Tuesday),
 *   1 May, 9 May, 3 Jul, 7 Nov, 25 Dec.
 * - Note on Belarus Labour Code: holidays falling on Saturday/Sunday do NOT shift to Monday;
 *   only official Council of Ministers RB bridge transfers apply (working Saturdays & rest bridges),
 *   plus optional custom dates configurable in app settings.
 * - Default working hours: 09:00 – 17:30.
 */
object BelarusWorkCalendar {

    val MINSK_ZONE: ZoneId = ZoneId.of("Europe/Minsk")
    private val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")

    /**
     * Computes Orthodox Pascha (Easter) in the proleptic Gregorian calendar for any [year] (1900..2199).
     */
    fun computeOrthodoxEaster(year: Int): LocalDate {
        val a = year % 4
        val b = year % 7
        val c = year % 19
        val d = (19 * c + 15) % 30
        val e = (2 * a + 4 * b - d + 34) % 7
        val julianMonth = (d + e + 114) / 31
        val julianDay = ((d + e + 114) % 31) + 1
        val centuryOffset = (year / 100) - (year / 400) - 2
        return LocalDate.of(year, julianMonth, julianDay).plusDays(centuryOffset.toLong())
    }

    /**
     * Computes Radunitsa (Радуница) — always Tuesday, 9 days after Orthodox Easter.
     */
    fun computeRadunitsa(year: Int): LocalDate = computeOrthodoxEaster(year).plusDays(9)

    /**
     * Returns all official annual non-working public holidays in the Republic of Belarus for [year].
     */
    fun officialHolidays(year: Int): Map<LocalDate, String> {
        val radunitsa = computeRadunitsa(year)
        return linkedMapOf(
            LocalDate.of(year, 1, 1) to "Новый год (1 января)",
            LocalDate.of(year, 1, 2) to "Новый год (2 января)",
            LocalDate.of(year, 1, 7) to "Рождество Христово (православное)",
            LocalDate.of(year, 3, 8) to "День женщин",
            radunitsa to "Радуница",
            LocalDate.of(year, 5, 1) to "Праздник труда",
            LocalDate.of(year, 5, 9) to "День Победы",
            LocalDate.of(year, 7, 3) to "День Независимости Республики Беларусь",
            LocalDate.of(year, 11, 7) to "День Октябрьской революции",
            LocalDate.of(year, 12, 25) to "Рождество Христово (католическое)"
        )
    }

    /**
     * Official Council of Ministers of the Republic of Belarus working-day transfers
     * (transferred non-working rest days -> working Saturdays), plus automatic annual
     * Radunitsa Monday->Saturday transfer for future years.
     */
    private fun officialTransfersForYear(year: Int): Pair<Set<LocalDate>, Set<LocalDate>> {
        return when (year) {
            // Постановление Совета Министров РБ № 839 от 14.11.2024
            2025 -> setOf(
                LocalDate.of(2025, 1, 6),
                LocalDate.of(2025, 4, 28),
                LocalDate.of(2025, 7, 4),
                LocalDate.of(2025, 12, 26)
            ) to setOf(
                LocalDate.of(2025, 1, 11),
                LocalDate.of(2025, 4, 26),
                LocalDate.of(2025, 7, 12),
                LocalDate.of(2025, 12, 20)
            )

            // Постановление Совета Министров РБ о переносе рабочих дней в 2026 году:
            // Понедельник 20 апреля (перед Радуницей 21 апреля) перенесён на субботу 25 апреля
            2026 -> setOf(
                LocalDate.of(2026, 4, 20)
            ) to setOf(
                LocalDate.of(2026, 4, 25)
            )

            // Automatic annual rule for future years: Monday before Tuesday Radunitsa -> Saturday of same week
            else -> {
                val radunitsa = computeRadunitsa(year)
                setOf(radunitsa.minusDays(1)) to setOf(radunitsa.plusDays(4))
            }
        }
    }

    fun isWorkingDay(
        date: LocalDate,
        extraWorkingDatesRaw: String = "",
        extraHolidayDatesRaw: String = ""
    ): Pair<Boolean, String> {
        val extraWorking = parseCustomDates(extraWorkingDatesRaw)
        val extraHolidays = parseCustomDates(extraHolidayDatesRaw)

        if (date in extraHolidays) {
            return false to "Дополнительный выходной день (${date.format(DATE_FMT)})"
        }
        if (date in extraWorking) {
            return true to "Рабочая суббота / перенос (${date.format(DATE_FMT)})"
        }

        val holidays = officialHolidays(date.year)
        val holidayName = holidays[date]
        if (holidayName != null) {
            return false to "Праздничный день РБ: $holidayName"
        }

        val (transferredRestDays, workingSaturdays) = officialTransfersForYear(date.year)
        if (date in transferredRestDays) {
            return false to "Перенесённый выходной день РБ (${date.format(DATE_FMT)})"
        }
        if (date in workingSaturdays) {
            return true to "Рабочая суббота по переносу Совмина РБ (${date.format(DATE_FMT)})"
        }

        return when (date.dayOfWeek) {
            DayOfWeek.SATURDAY -> false to "Выходной день (суббота)"
            DayOfWeek.SUNDAY -> false to "Выходной день (воскресенье)"
            else -> true to "Рабочий день РБ"
        }
    }

    fun isWorkingTimeNow(
        settings: AppSettings,
        nowInstant: Instant = Instant.now()
    ): Boolean {
        return evaluate(settings, nowInstant).isAllowedNow
    }

    fun evaluate(
        settings: AppSettings,
        nowInstant: Instant = Instant.now()
    ): WorkScheduleStatus {
        if (!settings.workScheduleEnabled) {
            return WorkScheduleStatus(
                isAllowedNow = true,
                isWorkingDay = true,
                statusTitle = "Круглосуточный режим (24/7)",
                detailMessage = "Ограничение по календарю РБ отключено в настройках"
            )
        }

        val minskNow: ZonedDateTime = nowInstant.atZone(MINSK_ZONE)
        val today = minskNow.toLocalDate()
        val currentTime = minskNow.toLocalTime()

        val startTime = parseLocalTimeOrDefault(settings.workStartTime, LocalTime.of(9, 0))
        val endTime = parseLocalTimeOrDefault(settings.workEndTime, LocalTime.of(17, 30))
        val windowLabel = "${startTime.format(TIME_FMT)}–${endTime.format(TIME_FMT)}"

        val (workingDay, dayReason) = isWorkingDay(
            date = today,
            extraWorkingDatesRaw = settings.extraWorkingDates,
            extraHolidayDatesRaw = settings.extraHolidayDates
        )

        if (!workingDay) {
            return WorkScheduleStatus(
                isAllowedNow = false,
                isWorkingDay = false,
                statusTitle = "Тихий режим · $dayReason",
                detailMessage = "Уведомления и звук отключены до рабочего дня РБ ($windowLabel)"
            )
        }

        val withinHours = !currentTime.isBefore(startTime) && !currentTime.isAfter(endTime)
        return if (withinHours) {
            WorkScheduleStatus(
                isAllowedNow = true,
                isWorkingDay = true,
                statusTitle = "Рабочее время РБ активно ($windowLabel)",
                detailMessage = "$dayReason · сейчас ${currentTime.format(TIME_FMT)} (Минск) — звук и баннеры включены"
            )
        } else {
            WorkScheduleStatus(
                isAllowedNow = false,
                isWorkingDay = true,
                statusTitle = "Нерабочее время РБ (${currentTime.format(TIME_FMT)})",
                detailMessage = "Звук и уведомления работают в рабочие дни РБ с ${startTime.format(TIME_FMT)} до ${endTime.format(TIME_FMT)}"
            )
        }
    }

    fun parseLocalTimeOrDefault(raw: String, fallback: LocalTime): LocalTime {
        val cleaned = raw.trim().replace('-', ':').replace('.', ':')
        val parts = cleaned.split(':')
        if (parts.size == 2) {
            val h = parts[0].toIntOrNull()
            val m = parts[1].toIntOrNull()
            if (h != null && m != null && h in 0..23 && m in 0..59) {
                return LocalTime.of(h, m)
            }
        }
        return fallback
    }

    fun parseCustomDates(raw: String): Set<LocalDate> {
        if (raw.isBlank()) return emptySet()
        val result = mutableSetOf<LocalDate>()
        val tokens = raw.split(',', ';', '\n', ' ')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        for (token in tokens) {
            runCatching {
                when {
                    Regex("""\d{2}\.\d{2}\.\d{4}""").matches(token) -> {
                        result.add(LocalDate.parse(token, DATE_FMT))
                    }
                    Regex("""\d{4}-\d{2}-\d{2}""").matches(token) -> {
                        result.add(LocalDate.parse(token))
                    }
                }
            }
        }
        return result
    }
}
