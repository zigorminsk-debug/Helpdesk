package com.helpdesk.jiraagent

import com.helpdesk.jiraagent.data.JiraJsonParser
import com.helpdesk.jiraagent.domain.RemoteDesktopKind
import com.helpdesk.jiraagent.domain.SlaCalculator
import com.helpdesk.jiraagent.domain.SlaUrgency
import com.helpdesk.jiraagent.domain.SysadminEntityExtractor
import com.helpdesk.jiraagent.domain.TicketFilterEngine
import com.helpdesk.jiraagent.model.AdminChatCodec
import com.helpdesk.jiraagent.model.AppSettings
import com.helpdesk.jiraagent.model.FilterSettings
import com.helpdesk.jiraagent.model.HelpdeskTicket
import com.helpdesk.jiraagent.model.OfflineQueuedAction
import com.helpdesk.jiraagent.model.QueuedActionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SysadminImprovementsTest {

    @Test
    fun `AdminChatCodec encodes and decodes reply quotes, ticket links, and pinned shift note`() {
        val encoded = AdminChatCodec.encodeMessage(
            text = "Проверяю #CSL-37368 и CSL-37366 прямо сейчас",
            replyToAuthor = "Игорь",
            replyToPreview = "Кто возьмёт ЖКХ?"
        )
        val decoded = AdminChatCodec.decodeMessage(encoded, chatRoomIssueKey = "CSL-100")
        assertEquals("Игорь", decoded.replyToAuthor)
        assertEquals("Кто возьмёт ЖКХ?", decoded.replyToPreview)
        assertEquals("Проверяю #CSL-37368 и CSL-37366 прямо сейчас", decoded.text)
        assertEquals(listOf("CSL-37368", "CSL-37366"), decoded.mentionedTicketKeys)

        val fullDesc = AdminChatCodec.encodeRoomDescriptionWithPinnedNote("Дежурит Алексей, плановые работы на АТС в 18:00")
        assertEquals(
            "Дежурит Алексей, плановые работы на АТС в 18:00",
            AdminChatCodec.extractPinnedNoteFromDescription(fullDesc)
        )
        assertEquals("", AdminChatCodec.extractPinnedNoteFromDescription(AdminChatCodec.ADMIN_CHAT_ROOM_DESCRIPTION))
    }

    @Test
    fun `AdminChat storage issue is excluded from client tickets`() {
        assertTrue(
            AdminChatCodec.isAdminChatStorageIssue(
                issueKey = "CSL-999",
                summary = AdminChatCodec.ADMIN_CHAT_ROOM_SUMMARY,
                labels = emptyList()
            )
        )

        val chatTicket = HelpdeskTicket(
            id = "999",
            key = "CSL-999",
            summary = AdminChatCodec.ADMIN_CHAT_ROOM_SUMMARY,
            description = "",
            issueType = "Task",
            isSubtask = false,
            customerRequestType = null,
            hasCustomerPortalInfo = false,
            status = "Closed",
            statusCategoryKey = "done",
            priority = "Low",
            projectKey = "CSL",
            projectName = "CSL",
            reporterName = "Admin",
            reporterLoginOrEmail = "admin",
            assigneeName = null,
            createdIso = "",
            createdEpochMillis = 0L,
            updatedIso = "",
            labels = listOf(AdminChatCodec.ADMIN_CHAT_LABEL),
            phoneNumbers = emptyList(),
            comments = emptyList(),
            webUrl = ""
        )
        val eval = TicketFilterEngine.evaluate(chatTicket, FilterSettings())
        assertFalse(eval.isClientTicket)
    }

    @Test
    fun `SysadminEntityExtractor finds AnyDesk, RustDesk, IPs, and domains while skipping phones and files`() {
        val text = """
            Добрый день! Не открывается 1С на сервере 192.168.15.20 и шлюз vpn.csl.by не пингуется.
            Мой телефон +375 29 654-32-10, почта buh@csl.by, скриншот error_1c.png во вложении.
            Подключитесь по AnyDesk: 412 839 104 или резервный RustDesk 1982736450.
        """.trimIndent()

        val result = SysadminEntityExtractor.extract(
            text = text,
            excludeNineDigitTails = setOf("296543210")
        )

        assertEquals(2, result.remoteIds.size)
        assertEquals(RemoteDesktopKind.ANYDESK, result.remoteIds[0].kind)
        assertEquals("412839104", result.remoteIds[0].normalizedId)
        assertEquals(RemoteDesktopKind.RUSTDESK, result.remoteIds[1].kind)
        assertEquals("1982736450", result.remoteIds[1].normalizedId)

        val hosts = result.networkTargets.map { it.host }
        assertTrue(hosts.contains("192.168.15.20"))
        assertTrue(hosts.contains("vpn.csl.by"))
        assertFalse(hosts.contains("csl.by")) // part of email buh@csl.by
        assertFalse(hosts.contains("error_1c.png"))
    }

    @Test
    fun `SlaCalculator computes fresh, warning, and overdue waiting badges`() {
        val base = 1_700_000_000_000L
        val fresh = SlaCalculator.computeWaitBadge(base, nowMillis = base + 7 * 60_000L)
        assertNotNull(fresh)
        assertEquals(SlaUrgency.FRESH, fresh!!.urgency)
        assertEquals("⏱ 7 мин", fresh.label)

        val warn = SlaCalculator.computeWaitBadge(base, nowMillis = base + 22 * 60_000L)
        assertEquals(SlaUrgency.WARNING, warn!!.urgency)

        val overdue = SlaCalculator.computeWaitBadge(base, nowMillis = base + 95 * 60_000L)
        assertEquals(SlaUrgency.OVERDUE, overdue!!.urgency)
        assertEquals("⏱ 1 ч 35 м", overdue.label)
    }

    @Test
    fun `JiraJsonParser parses attachments and assignee matching for Mine filter`() {
        val json = """
            {
              "id": "10500",
              "key": "CSL-37400",
              "fields": {
                "summary": "Ошибка кассы",
                "description": "См. скриншот",
                "issuetype": { "name": "Service Request", "subtask": false },
                "status": { "name": "В работе", "statusCategory": { "key": "indeterminate" } },
                "priority": { "name": "High" },
                "project": { "key": "CSL", "name": "CSL Helpdesk" },
                "reporter": { "displayName": "ООО Ромашка", "name": "romashka" },
                "assignee": { "displayName": "Игорь Сисадмин", "name": "igor.admin", "emailAddress": "igor@csl.by" },
                "created": "2026-11-21T09:15:00.000+0300",
                "updated": "2026-11-21T09:20:00.000+0300",
                "attachment": [
                  {
                    "id": "551",
                    "filename": "screen_error.png",
                    "mimeType": "image/png",
                    "size": 148200,
                    "content": "https://jira.csl.by/secure/attachment/551/screen_error.png",
                    "thumbnail": "https://jira.csl.by/secure/thumbnail/551/_thumb_551.png"
                  }
                ]
              }
            }
        """.trimIndent()

        val parsed = JiraJsonParser.parseSingleIssue(
            jsonString = json,
            appSettings = AppSettings(serverUrl = "https://jira.csl.by", usernameOrEmail = "igor@csl.by"),
            filterSettings = FilterSettings()
        )
        assertNotNull(parsed)
        assertEquals(1, parsed!!.attachments?.size)
        assertTrue(parsed.attachments!!.first().isImage)
        assertTrue(parsed.isAssignedToAgent(connectedUserDisplayName = null, usernameOrEmail = "igor@csl.by"))
        assertFalse(parsed.isAssignedToAgent(connectedUserDisplayName = "Павел", usernameOrEmail = "pavel@csl.by"))
    }

    @Test
    fun `OfflineQueuedAction builds clear Russian summary labels`() {
        val act = OfflineQueuedAction(
            type = QueuedActionType.WORKFLOW_ACTION,
            ticketKey = "CSL-37368",
            workflowActionName = "TAKE_IN_WORK"
        )
        assertEquals("CSL-37368 · В работу", act.summaryLabel)
    }
}
