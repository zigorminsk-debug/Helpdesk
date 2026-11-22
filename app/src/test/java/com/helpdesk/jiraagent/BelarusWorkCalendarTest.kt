package com.helpdesk.jiraagent

import com.helpdesk.jiraagent.domain.BelarusWorkCalendar
import com.helpdesk.jiraagent.domain.HostDiagnosticReport
import com.helpdesk.jiraagent.domain.PostponeDateTimeParser
import com.helpdesk.jiraagent.domain.QuickReplyTemplates
import com.helpdesk.jiraagent.domain.TcpPortStatus
import com.helpdesk.jiraagent.model.AlertSoundPreset
import com.helpdesk.jiraagent.model.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class BelarusWorkCalendarTest {

    @Test
    fun computesRadunitsaAccuratelyAcrossYears() {
        assertEquals(LocalDate.of(2025, 4, 29), BelarusWorkCalendar.computeRadunitsa(2025))
        assertEquals(LocalDate.of(2026, 4, 21), BelarusWorkCalendar.computeRadunitsa(2026))
        assertEquals(LocalDate.of(2027, 5, 11), BelarusWorkCalendar.computeRadunitsa(2027))
        assertEquals(LocalDate.of(2028, 4, 25), BelarusWorkCalendar.computeRadunitsa(2028))
    }

    @Test
    fun recognizesBelarusStateHolidaysAndTransfers() {
        // State holidays of RB are non-working
        assertFalse(BelarusWorkCalendar.isWorkingDay(LocalDate.of(2026, 1, 1)).first)
        assertFalse(BelarusWorkCalendar.isWorkingDay(LocalDate.of(2026, 1, 2)).first)
        assertFalse(BelarusWorkCalendar.isWorkingDay(LocalDate.of(2026, 1, 7)).first)
        assertFalse(BelarusWorkCalendar.isWorkingDay(LocalDate.of(2026, 4, 21)).first) // Radunitsa 2026
        assertFalse(BelarusWorkCalendar.isWorkingDay(LocalDate.of(2026, 5, 1)).first)
        assertFalse(BelarusWorkCalendar.isWorkingDay(LocalDate.of(2026, 7, 3)).first)
        assertFalse(BelarusWorkCalendar.isWorkingDay(LocalDate.of(2026, 11, 7)).first)
        assertFalse(BelarusWorkCalendar.isWorkingDay(LocalDate.of(2026, 12, 25)).first)

        // RB transfer 2026: Monday 20.04.2026 moved to working Saturday 25.04.2026
        assertFalse(BelarusWorkCalendar.isWorkingDay(LocalDate.of(2026, 4, 20)).first)
        assertTrue(BelarusWorkCalendar.isWorkingDay(LocalDate.of(2026, 4, 25)).first)

        // In Belarus, weekend holiday (Sunday 8 March 2026) does NOT shift to Monday 9 March 2026
        assertTrue(BelarusWorkCalendar.isWorkingDay(LocalDate.of(2026, 3, 9)).first)

        // Regular Saturday (21.11.2026) is non-working
        assertFalse(BelarusWorkCalendar.isWorkingDay(LocalDate.of(2026, 11, 21)).first)
    }

    @Test
    fun enforcesWorkingHours0900To1730MinskTime() {
        val settings = AppSettings(
            workScheduleEnabled = true,
            workStartTime = "09:00",
            workEndTime = "17:30"
        )

        fun checkMinsk(year: Int, month: Int, day: Int, hour: Int, minute: Int): Boolean {
            val instant = LocalDateTime.of(year, month, day, hour, minute)
                .atZone(BelarusWorkCalendar.MINSK_ZONE)
                .toInstant()
            return BelarusWorkCalendar.isWorkingTimeNow(settings, instant)
        }

        // Monday 23.11.2026 (regular RB working day)
        assertFalse(checkMinsk(2026, 11, 23, 8, 59))
        assertTrue(checkMinsk(2026, 11, 23, 9, 0))
        assertTrue(checkMinsk(2026, 11, 23, 13, 15))
        assertTrue(checkMinsk(2026, 11, 23, 17, 30))
        assertFalse(checkMinsk(2026, 11, 23, 17, 31))

        // Saturday 21.11.2026 at 13:15 -> weekend, must be silent!
        assertFalse(checkMinsk(2026, 11, 21, 13, 15))

        // Working Saturday 25.04.2026 at 11:00 -> working by RB transfer, must allow!
        assertTrue(checkMinsk(2026, 4, 25, 11, 0))

        // Holiday Friday 03.07.2026 at 11:00 -> Independence Day RB, must be silent!
        assertFalse(checkMinsk(2026, 7, 3, 11, 0))

        // When user enables notifications in non-working hours (workScheduleEnabled = false), 24/7 allowed
        val allowOffHoursSettings = settings.copy(workScheduleEnabled = false)
        val saturdayNight = LocalDateTime.of(2026, 11, 21, 22, 45)
            .atZone(BelarusWorkCalendar.MINSK_ZONE)
            .toInstant()
        assertTrue(BelarusWorkCalendar.isWorkingTimeNow(allowOffHoursSettings, saturdayNight))
    }

    @Test
    fun parsesPostponeDateAndTimeFormatsIncludingDashAndColon() {
        val parsedDash = PostponeDateTimeParser.parseToEpochMillis(
            "22.11.2026",
            "12-00",
            BelarusWorkCalendar.MINSK_ZONE
        )
        assertNotNull(parsedDash)
        assertEquals("22.11.2026 12:00", parsedDash!!.second)

        val parsedColon = PostponeDateTimeParser.parseToEpochMillis(
            "22.11.2026",
            "12:00",
            BelarusWorkCalendar.MINSK_ZONE
        )
        assertNotNull(parsedColon)
        assertEquals(parsedDash.first, parsedColon!!.first)
    }

    @Test
    fun alertSoundPresetDefaultsToAudibleCslChimeAndSupportsCustomMelody() {
        val defaults = AppSettings()
        assertEquals(AlertSoundPreset.CSL_CHIME, defaults.alertSoundPreset)

        val custom = defaults.copy(
            alertSoundPreset = AlertSoundPreset.CUSTOM_URI,
            customAlertSoundUri = "content://media/internal/audio/media/42",
            customAlertSoundTitle = "Signal_CSL.mp3"
        )
        assertEquals(AlertSoundPreset.CUSTOM_URI, custom.alertSoundPreset)
        assertEquals("Signal_CSL.mp3", custom.customAlertSoundTitle)
    }

    @Test
    fun showsEmptyQueueGifsOnlyAtBeginningOfWorkingDayAndDisablesAfterward() {
        val settings = AppSettings(workStartTime = "09:00", workEndTime = "17:30")

        fun minskInstant(year: Int, month: Int, day: Int, hour: Int, minute: Int) =
            LocalDateTime.of(year, month, day, hour, minute)
                .atZone(BelarusWorkCalendar.MINSK_ZONE)
                .toInstant()

        // Monday 23.11.2026 at 09:05 (start of working day) -> TRUE
        val morning0905 = minskInstant(2026, 11, 23, 9, 5)
        assertTrue(
            BelarusWorkCalendar.shouldShowMorningGif(
                settings = settings,
                sessionStartedEpochMs = morning0905.toEpochMilli() - 2 * 60_000L,
                nowInstant = morning0905
            )
        )

        // Monday 23.11.2026 at 08:40 (pre-shift arrival within 30 min window) -> TRUE
        val morning0840 = minskInstant(2026, 11, 23, 8, 40)
        assertTrue(BelarusWorkCalendar.shouldShowMorningGif(settings = settings, nowInstant = morning0840))

        // Monday 23.11.2026 at 10:15 (after morning window) -> FALSE
        val midday1015 = minskInstant(2026, 11, 23, 10, 15)
        assertFalse(BelarusWorkCalendar.shouldShowMorningGif(settings = settings, nowInstant = midday1015))

        // Monday 23.11.2026 at 09:15 when already shown > 10 minutes in session -> FALSE
        val morning0915 = minskInstant(2026, 11, 23, 9, 15)
        assertFalse(
            BelarusWorkCalendar.shouldShowMorningGif(
                settings = settings,
                sessionStartedEpochMs = morning0915.toEpochMilli() - 12 * 60_000L,
                maxSessionDisplayMinutes = 10L,
                nowInstant = morning0915
            )
        )

        // Monday 23.11.2026 at 09:05 when manually dismissed today -> FALSE
        assertFalse(
            BelarusWorkCalendar.shouldShowMorningGif(
                settings = settings,
                dismissedDateIso = "2026-11-23",
                nowInstant = morning0905
            )
        )

        // Saturday 21.11.2026 at 09:05 (weekend) -> FALSE
        val saturday0905 = minskInstant(2026, 11, 21, 9, 5)
        assertFalse(BelarusWorkCalendar.shouldShowMorningGif(settings = settings, nowInstant = saturday0905))
    }

    @Test
    fun expandsQuickReplyPlaceholdersAndFormatsDiagnosticReportForComment() {
        val expanded = QuickReplyTemplates.expand(
            templateBody = "Здравствуйте, {клиент}! Заявка {заявка} принята инженером {инженер}.",
            clientName = "ООО Белинвестторг (Минск)",
            ticketKey = "CSL-37401",
            engineerName = "Захаревич Игорь"
        )
        assertEquals(
            "Здравствуйте, ООО Белинвестторг! Заявка CSL-37401 принята инженером Захаревич Игорь.",
            expanded
        )

        val report = HostDiagnosticReport(
            targetHost = "mail.csl.by",
            resolvedIp = "93.84.112.10",
            icmpReachable = true,
            icmpLatencyMs = 8L,
            portStatuses = listOf(
                TcpPortStatus(443, "HTTPS", true, 12L),
                TcpPortStatus(3389, "RDP", false, null)
            )
        )
        val note = QuickReplyTemplates.formatDiagnosticReportForComment(report)
        assertTrue(note.contains("mail.csl.by"))
        assertTrue(note.contains("93.84.112.10"))
        assertTrue(note.contains("443/HTTPS"))
    }

    @Test
    fun defaultsOffHoursSyncToTwoHoursAndWakesExactlyAtShiftStart() {
        val defaults = AppSettings()
        assertEquals(120, defaults.offHoursPollIntervalMinutes)
        assertEquals(120, defaults.copy(offHoursPollIntervalMinutes = 0).normalizedOffHoursPollMinutes)
        assertEquals("Раз в 2 часа", AppSettings.formatOffHoursIntervalLabel(defaults.normalizedOffHoursPollMinutes))

        fun minsk(year: Int, month: Int, day: Int, hour: Int, minute: Int) =
            LocalDateTime.of(year, month, day, hour, minute).atZone(BelarusWorkCalendar.MINSK_ZONE).toInstant()

        // 1. Working hours (Monday 23.11.2026 at 11:00) -> 30s active interval
        val (workDelay, workPaused) = BelarusWorkCalendar.computeEffectivePollSchedule(
            settings = defaults,
            nowInstant = minsk(2026, 11, 23, 11, 0)
        )
        assertEquals(30_000L, workDelay)
        assertFalse(workPaused)

        // 2. Evening off-hours (Monday 23.11.2026 at 20:00) -> 2 hours (7,200,000 ms)
        val (eveningDelay, eveningPaused) = BelarusWorkCalendar.computeEffectivePollSchedule(
            settings = defaults,
            nowInstant = minsk(2026, 11, 23, 20, 0)
        )
        assertEquals(120L * 60_000L, eveningDelay)
        assertFalse(eveningPaused)

        // 3. Morning 25 min before shift start (Tuesday 24.11.2026 at 08:35, shift starts 09:00) -> capped to 25 min!
        val (preShiftDelay, preShiftPaused) = BelarusWorkCalendar.computeEffectivePollSchedule(
            settings = defaults,
            nowInstant = minsk(2026, 11, 24, 8, 35)
        )
        assertEquals(25L * 60_000L, preShiftDelay)
        assertFalse(preShiftPaused)

        // 4. Custom user setting (45 min in off-hours)
        val custom45 = defaults.copy(offHoursPollIntervalMinutes = 45)
        val (customDelay, _) = BelarusWorkCalendar.computeEffectivePollSchedule(
            settings = custom45,
            nowInstant = minsk(2026, 11, 23, 21, 0)
        )
        assertEquals(45L * 60_000L, customDelay)

        // 5. Off-hours sync paused until shift start (OFF_HOURS_POLL_DISABLED)
        val pausedSettings = defaults.copy(offHoursPollIntervalMinutes = AppSettings.OFF_HOURS_POLL_DISABLED)
        val (pausedDelay, isPaused) = BelarusWorkCalendar.computeEffectivePollSchedule(
            settings = pausedSettings,
            nowInstant = minsk(2026, 11, 24, 7, 0)
        )
        assertTrue(isPaused)
        assertEquals(120L * 60_000L, pausedDelay)
    }
}

