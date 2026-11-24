package com.helpdesk.jiraagent

import com.helpdesk.jiraagent.domain.TicketFreshnessClassifier
import com.helpdesk.jiraagent.domain.TicketHangingSeverity
import com.helpdesk.jiraagent.model.AppSettings
import com.helpdesk.jiraagent.model.HelpdeskTicket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class TicketFreshnessClassifierTest {

    private val minskZone: ZoneId = ZoneId.of("Europe/Minsk")

    private fun sampleTicket(key: String, createdZdt: ZonedDateTime): HelpdeskTicket {
        return HelpdeskTicket(
            id = key,
            key = key,
            summary = "Test $key",
            description = "",
            issueType = "Service Request",
            isSubtask = false,
            customerRequestType = null,
            hasCustomerPortalInfo = true,
            status = "Ожидание поддержки",
            statusCategoryKey = "new",
            priority = "Normal",
            projectKey = "CSL",
            projectName = "CSL",
            reporterName = "ООО Клиент",
            reporterLoginOrEmail = "client",
            assigneeName = null,
            createdIso = createdZdt.toOffsetDateTime().toString(),
            createdEpochMillis = createdZdt.toInstant().toEpochMilli(),
            updatedIso = createdZdt.toOffsetDateTime().toString(),
            updatedEpochMillis = createdZdt.toInstant().toEpochMilli(),
            labels = emptyList(),
            phoneNumbers = emptyList(),
            comments = emptyList(),
            webUrl = "https://jira.csl.by/browse/$key"
        )
    }

    @Test
    fun classifiesTodayWorkdayAndPastHangingTickets() {
        val now = ZonedDateTime.of(2026, 11, 24, 14, 0, 0, 0, minskZone)
        val nowMs = now.toInstant().toEpochMilli()
        val settings = AppSettings()

        val freshWorkday = sampleTicket("CSL-101", ZonedDateTime.of(2026, 11, 24, 10, 30, 0, 0, minskZone))
        val freshMorning = sampleTicket("CSL-102", ZonedDateTime.of(2026, 11, 24, 7, 50, 0, 0, minskZone))
        val yesterdayTicket = sampleTicket("CSL-95", ZonedDateTime.of(2026, 11, 23, 16, 15, 0, 0, minskZone))
        val fiveDaysOld = sampleTicket("CSL-80", ZonedDateTime.of(2026, 11, 19, 11, 0, 0, 0, minskZone))

        val infoWorkday = TicketFreshnessClassifier.classify(freshWorkday, settings, nowMs, minskZone)
        assertTrue(infoWorkday.isFreshToday)
        assertTrue(infoWorkday.arrivedDuringWorkday)
        assertEquals(TicketHangingSeverity.FRESH_TODAY, infoWorkday.severity)
        assertTrue(infoWorkday.fullBadgeLabel.contains("10:30"))

        val infoMorning = TicketFreshnessClassifier.classify(freshMorning, settings, nowMs, minskZone)
        assertTrue(infoMorning.isFreshToday)
        assertFalse(infoMorning.arrivedDuringWorkday)

        val infoYesterday = TicketFreshnessClassifier.classify(yesterdayTicket, settings, nowMs, minskZone)
        assertFalse(infoYesterday.isFreshToday)
        assertEquals(1L, infoYesterday.calendarDaysAgo)
        assertEquals(TicketHangingSeverity.PAST_RECENT, infoYesterday.severity)
        assertTrue(infoYesterday.fullBadgeLabel.contains("со вчера"))

        val infoOld = TicketFreshnessClassifier.classify(fiveDaysOld, settings, nowMs, minskZone)
        assertFalse(infoOld.isFreshToday)
        assertEquals(5L, infoOld.calendarDaysAgo)
        assertEquals(TicketHangingSeverity.PAST_LONG, infoOld.severity)
        assertTrue(infoOld.fullBadgeLabel.contains("5 дней"))

        val (freshList, pastList) = TicketFreshnessClassifier.partitionFreshAndPast(
            listOf(freshWorkday, yesterdayTicket, freshMorning, fiveDaysOld),
            nowMs,
            minskZone
        )
        assertEquals(listOf("CSL-101", "CSL-102"), freshList.map { it.key })
        assertEquals(listOf("CSL-95", "CSL-80"), pastList.map { it.key })
    }
}
