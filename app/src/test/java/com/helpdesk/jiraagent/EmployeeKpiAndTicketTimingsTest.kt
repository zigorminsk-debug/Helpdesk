package com.helpdesk.jiraagent

import com.helpdesk.jiraagent.data.JiraJsonParser
import com.helpdesk.jiraagent.domain.EmployeeActivityStatus
import com.helpdesk.jiraagent.domain.ShiftAnalyticsCalculator
import com.helpdesk.jiraagent.domain.ShiftReportPeriod
import com.helpdesk.jiraagent.domain.TicketStopwatchEntry
import com.helpdesk.jiraagent.model.AppSettings
import com.helpdesk.jiraagent.model.FilterSettings
import com.helpdesk.jiraagent.model.HelpdeskTicket
import com.helpdesk.jiraagent.model.TicketStage
import com.helpdesk.jiraagent.model.TicketTimingFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZonedDateTime
import java.time.ZoneId

class EmployeeKpiAndTicketTimingsTest {

    @Test
    fun parsesJiraResolutionDateAndComputesClosureAndDurationAccurately() {
        val sampleJson = """
            {
              "issues": [
                {
                  "id": "50001",
                  "key": "CSL-50001",
                  "fields": {
                    "summary": "Не печатает сетевой принтер HP LaserJet",
                    "description": "Принтер в бухгалтерии",
                    "issuetype": { "name": "Service Request", "subtask": false },
                    "status": { "name": "Выполнена", "statusCategory": { "key": "done" } },
                    "priority": { "name": "High" },
                    "project": { "key": "CSL", "name": "CSL Helpdesk" },
                    "reporter": { "displayName": "ООО Белстрой", "name": "belstroy" },
                    "assignee": { "displayName": "Захаревич Игорь", "name": "ziv" },
                    "created": "2026-11-21T09:10:00.000+0300",
                    "updated": "2026-11-21T10:05:00.000+0300",
                    "resolutiondate": "2026-11-21T09:52:00.000+0300",
                    "labels": []
                  }
                }
              ]
            }
        """.trimIndent()

        val parsed = JiraJsonParser.parseSearchResponse(sampleJson, AppSettings(serverUrl = "https://jira.csl.by"), FilterSettings())
        assertEquals(1, parsed.size)
        val ticket = parsed.first()
        assertEquals(TicketStage.CLOSED, ticket.stage)
        assertEquals("2026-11-21T09:52:00.000+0300", ticket.resolvedIso)
        assertTrue(ticket.resolvedEpochMillis > ticket.createdEpochMillis)
        assertEquals(ticket.resolvedEpochMillis, ticket.effectiveClosedEpochMillis)
        assertEquals(42L, ticket.resolutionDurationMinutes)

        val formattedCreated = TicketTimingFormatter.formatFullMinsk(ticket.createdEpochMillis)
        val formattedClosed = TicketTimingFormatter.formatFullMinsk(ticket.effectiveClosedEpochMillis)
        assertEquals("21.11.2026 09:10", formattedCreated)
        assertEquals("21.11.2026 09:52", formattedClosed)

        val cardLine = TicketTimingFormatter.buildCardTimingSummary(
            ticket = ticket,
            nowMillis = ticket.resolvedEpochMillis + 600_000L
        )
        assertTrue("Card timing summary must include arrival: $cardLine", cardLine.contains("09:10"))
        assertTrue("Card timing summary must include closure: $cardLine", cardLine.contains("09:52"))
        assertTrue("Card timing summary must include duration: $cardLine", cardLine.contains("42 мин"))
    }

    @Test
    fun openTicketDoesNotReportFakeClosureTimestamp() {
        val openTicket = buildSampleTicket(
            key = "CSL-50002",
            status = "В работе",
            statusCategoryKey = "indeterminate",
            assignee = "Захаревич Игорь",
            createdEpochMillis = 1795244400000L,
            updatedEpochMillis = 1795246200000L,
            resolvedEpochMillis = 0L
        )
        assertEquals(TicketStage.IN_PROGRESS, openTicket.stage)
        assertEquals(0L, openTicket.effectiveClosedEpochMillis)
        assertNull(openTicket.resolutionDurationMinutes)
    }

    @Test
    fun computesEmployeeKpisActivityStatusAndCsvColumns() {
        val minsk = ZoneId.of("Europe/Minsk")
        val nowZdt = ZonedDateTime.of(2026, 11, 21, 14, 0, 0, 0, minsk)
        val nowMs = nowZdt.toInstant().toEpochMilli()
        val created1 = nowZdt.minusMinutes(90).toInstant().toEpochMilli()
        val closed1 = nowZdt.minusMinutes(50).toInstant().toEpochMilli() // 40 min (< 1h)
        val created2 = nowZdt.minusMinutes(180).toInstant().toEpochMilli()
        val closed2 = nowZdt.minusMinutes(60).toInstant().toEpochMilli() // 120 min
        val created3 = nowZdt.minusMinutes(25).toInstant().toEpochMilli()

        val tickets = listOf(
            buildSampleTicket(
                key = "CSL-50101",
                status = "Выполнена",
                statusCategoryKey = "done",
                assignee = "Захаревич Игорь",
                createdEpochMillis = created1,
                updatedEpochMillis = closed1,
                resolvedEpochMillis = closed1
            ),
            buildSampleTicket(
                key = "CSL-50102",
                status = "Выполнена",
                statusCategoryKey = "done",
                assignee = "Захаревич Игорь",
                createdEpochMillis = created2,
                updatedEpochMillis = closed2,
                resolvedEpochMillis = closed2
            ),
            buildSampleTicket(
                key = "CSL-50103",
                status = "В работе",
                statusCategoryKey = "indeterminate",
                assignee = "Захаревич Игорь",
                createdEpochMillis = created3,
                updatedEpochMillis = nowMs - 5 * 60_000L,
                resolvedEpochMillis = 0L
            ),
            buildSampleTicket(
                key = "CSL-50104",
                status = "Выполнена",
                statusCategoryKey = "done",
                assignee = "Иванов Алексей",
                createdEpochMillis = created1,
                updatedEpochMillis = closed1 - 10 * 60_000L,
                resolvedEpochMillis = closed1 - 10 * 60_000L // 30 min
            )
        )

        val summary = ShiftAnalyticsCalculator.computeForPeriod(
            tickets = tickets,
            period = ShiftReportPeriod.TODAY,
            connectedUserDisplayName = "Захаревич Игорь",
            usernameOrEmail = "ziv@csl.by",
            stopwatches = mapOf(
                "CSL-50101" to TicketStopwatchEntry(
                    ticketKey = "CSL-50101",
                    accumulatedMillis = 25 * 60_000L,
                    runningSinceEpochMs = null
                )
            ),
            nowMillis = nowMs
        )

        assertEquals(3, summary.closedInPeriod)
        assertEquals(2, summary.closedByMeInPeriod)
        assertEquals(2, summary.employeeKpis.size)

        val topEmp = summary.employeeKpis.first()
        assertEquals("Захаревич Игорь", topEmp.engineerName)
        assertTrue(topEmp.isCurrentUser)
        assertEquals(2, topEmp.closedCount)
        assertEquals(66, topEmp.shareOfTeamClosedPercent)
        assertEquals(1, topEmp.inProgressCount)
        assertEquals(80L, topEmp.avgResolutionMinutes) // (40 + 120) / 2
        assertEquals(50, topEmp.fastCloseUnder1hPercent) // 1 of 2 <= 60m
        assertEquals(25, topEmp.stopwatchTrackedMinutes)
        assertEquals(EmployeeActivityStatus.ACTIVE_NOW, topEmp.activityStatus)

        val csvText = String(ShiftAnalyticsCalculator.buildExcelCsvBytes(summary), Charsets.UTF_8)
        assertTrue(csvText.contains("KPI по сотрудникам"))
        assertTrue(csvText.contains("Время поступления;Время закрытия;Время решения (мин)"))
        assertTrue(csvText.contains("Захаревич Игорь"))
    }

    private fun buildSampleTicket(
        key: String,
        status: String,
        statusCategoryKey: String,
        assignee: String?,
        createdEpochMillis: Long,
        updatedEpochMillis: Long,
        resolvedEpochMillis: Long
    ): HelpdeskTicket = HelpdeskTicket(
        id = key.substringAfter("-"),
        key = key,
        summary = "Тестовая заявка $key",
        description = "Описание",
        issueType = "Service Request",
        isSubtask = false,
        customerRequestType = null,
        hasCustomerPortalInfo = true,
        status = status,
        statusCategoryKey = statusCategoryKey,
        priority = "Normal",
        projectKey = "CSL",
        projectName = "CSL Helpdesk",
        reporterName = "ООО Клиент",
        reporterLoginOrEmail = "client",
        assigneeName = assignee,
        createdIso = "2026-11-21T09:00:00.000+0300",
        createdEpochMillis = createdEpochMillis,
        updatedIso = "2026-11-21T10:00:00.000+0300",
        updatedEpochMillis = updatedEpochMillis,
        resolvedIso = if (resolvedEpochMillis > 0L) "2026-11-21T10:00:00.000+0300" else "",
        resolvedEpochMillis = resolvedEpochMillis,
        labels = emptyList(),
        phoneNumbers = emptyList(),
        comments = emptyList(),
        webUrl = "https://jira.csl.by/browse/$key"
    )
}
