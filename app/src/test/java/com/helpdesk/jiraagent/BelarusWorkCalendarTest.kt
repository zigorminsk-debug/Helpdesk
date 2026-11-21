package com.helpdesk.jiraagent

import com.helpdesk.jiraagent.domain.BelarusWorkCalendar
import com.helpdesk.jiraagent.domain.PostponeDateTimeParser
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
}
