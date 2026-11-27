package com.helpdesk.jiraagent

import com.helpdesk.jiraagent.data.JiraJsonParser
import com.helpdesk.jiraagent.domain.TicketFilterEngine
import com.helpdesk.jiraagent.model.AppSettings
import com.helpdesk.jiraagent.model.FilterSettings
import com.helpdesk.jiraagent.model.TicketStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TicketFilterAndParserTest {

    @Test
    fun classifiesNewInProgressClosedAndZabbixServiceTicketsAccurately() {
        val sampleSearchJson = """
            {
              "names": {
                "customfield_10200": "Телефон клиента",
                "customfield_10010": "Customer Request Type"
              },
              "issues": [
                {
                  "id": "37366",
                  "key": "CSL-37366",
                  "fields": {
                    "summary": "test",
                    "description": {
                      "type": "doc",
                      "version": 1,
                      "content": [
                        {
                          "type": "paragraph",
                          "content": [
                            { "type": "text", "text": "Позвоните мне на 8 (916) 222-33-44 после 14:00." }
                          ]
                        }
                      ]
                    },
                    "customfield_10200": "8 (029) 123-45-67",
                    "customfield_10010": {
                      "requestType": { "name": "Проблемы с ПК/Ноутбуком" }
                    },
                    "issuetype": { "name": "Service Request", "subtask": false },
                    "status": {
                      "name": "Ожидаие поддержки",
                      "statusCategory": { "key": "indeterminate" }
                    },
                    "priority": { "name": "Medium" },
                    "project": { "key": "CSL", "name": "Customer Support Line" },
                    "reporter": {
                      "displayName": "Сейлзмодерн",
                      "name": "salesmodern",
                      "emailAddress": "rabota@belservice.by"
                    },
                    "created": "2026-11-21T12:29:00.000+0300",
                    "updated": "2026-11-21T12:29:00.000+0300",
                    "labels": []
                  }
                },
                {
                  "id": "37365",
                  "key": "CSL-37365",
                  "fields": {
                    "summary": "Настройка VPN на ноутбуке",
                    "description": "Тел +375 44 555-66-77",
                    "customfield_10010": {
                      "requestType": { "name": "Настройка ПО" }
                    },
                    "issuetype": { "name": "Service Request", "subtask": false },
                    "status": {
                      "name": "В работе",
                      "statusCategory": { "key": "indeterminate" }
                    },
                    "assignee": { "displayName": "Иванов И.И." },
                    "priority": { "name": "Medium" },
                    "project": { "key": "CSL", "name": "Customer Support Line" },
                    "reporter": {
                      "displayName": "ООО Альфа",
                      "name": "alfa_client"
                    },
                    "created": "2026-11-21T11:00:00.000+0300",
                    "updated": "2026-11-21T12:15:00.000+0300",
                    "labels": []
                  }
                },
                {
                  "id": "37360",
                  "key": "CSL-37360",
                  "fields": {
                    "summary": "Замена картриджа",
                    "description": "Выполнено",
                    "issuetype": { "name": "Service Request", "subtask": false },
                    "status": {
                      "name": "Закрыта",
                      "statusCategory": { "key": "done" }
                    },
                    "priority": { "name": "Low" },
                    "project": { "key": "CSL", "name": "Customer Support Line" },
                    "reporter": {
                      "displayName": "ООО Бета",
                      "name": "beta_client"
                    },
                    "created": "2026-11-21T09:00:00.000+0300",
                    "updated": "2026-11-21T10:30:00.000+0300",
                    "labels": []
                  }
                },
                {
                  "id": "37367",
                  "key": "CSL-37367",
                  "fields": {
                    "summary": "High CPU usage on srv-db-01",
                    "description": "Problem started at 12:30:00",
                    "issuetype": { "name": "Service Request", "subtask": false },
                    "status": {
                      "name": "Закрыта",
                      "statusCategory": { "key": "done" }
                    },
                    "priority": { "name": "High" },
                    "project": { "key": "CSL", "name": "Customer Support Line" },
                    "reporter": {
                      "displayName": "Zabbix",
                      "name": "zabbix",
                      "emailAddress": "zabbix@company.by"
                    },
                    "created": "2026-11-21T12:30:00.000+0300",
                    "updated": "2026-11-21T12:35:00.000+0300",
                    "labels": []
                  }
                }
              ]
            }
        """.trimIndent()

        val settings = AppSettings(serverUrl = "https://jira.company.by")
        val filter = FilterSettings()

        val parsed = JiraJsonParser.parseSearchResponse(sampleSearchJson, settings, filter)
        assertEquals(4, parsed.size)

        // 1. CSL-37366 -> "Новые" (even when JSM workflow category is indeterminate!)
        val newTicket = parsed[0]
        assertTrue(newTicket.isClientTicket)
        assertNull(newTicket.filterReason)
        assertEquals(TicketStage.NEW, newTicket.stage)
        assertEquals(2, newTicket.phoneNumbers.size)
        assertEquals("+375291234567", newTicket.phoneNumbers[0].e164)

        // 2. CSL-37365 -> "В работе"
        val inProgressTicket = parsed[1]
        assertTrue(inProgressTicket.isClientTicket)
        assertEquals(TicketStage.IN_PROGRESS, inProgressTicket.stage)
        assertEquals("+375445556677", inProgressTicket.phoneNumbers[0].e164)

        // 3. CSL-37360 -> "Закрытые" (remains isClientTicket = true so it goes to Закрытые, not Сервисные!)
        val closedClientTicket = parsed[2]
        assertTrue(closedClientTicket.isClientTicket)
        assertEquals(TicketStage.CLOSED, closedClientTicket.stage)

        // 4. CSL-37367 from Zabbix -> "Сервисные" (even when closed, Zabbix stays out of Закрытые!)
        val zabbixTicket = parsed[3]
        assertFalse(zabbixTicket.isClientTicket)
        assertTrue(zabbixTicket.filterReason!!.contains("Сервисный отправитель"))
    }

    @Test
    fun buildsExpectedActiveAndClosedJql() {
        val filter = FilterSettings(
            projectKeys = "CSL",
            customJqlCondition = "priority in (High, Highest)"
        )
        assertEquals(
            "project in (\"CSL\") AND (priority in (High, Highest)) AND statusCategory != Done ORDER BY created DESC",
            TicketFilterEngine.buildActiveJql(filter)
        )
        assertEquals(
            "project in (\"CSL\") AND (priority in (High, Highest)) AND statusCategory = Done ORDER BY updated DESC",
            TicketFilterEngine.buildClosedJql(filter)
        )
    }

    @Test
    fun selectsOnlyHumanActiveSupportJsmQueuesAndBuildsInProgressJql() {
        assertTrue(TicketFilterEngine.isHumanActiveSupportQueueName("В работе"))
        assertTrue(TicketFilterEngine.isHumanActiveSupportQueueName("Ремонт техники"))
        assertTrue(TicketFilterEngine.isHumanActiveSupportQueueName("Заправки и ремонт принтеров"))

        assertFalse(TicketFilterEngine.isHumanActiveSupportQueueName("ALL"))
        assertFalse(TicketFilterEngine.isHumanActiveSupportQueueName("Готовые Заявки"))
        assertFalse(TicketFilterEngine.isHumanActiveSupportQueueName("RAID PROBLEM"))
        assertFalse(TicketFilterEngine.isHumanActiveSupportQueueName("BACKUP ERRORS"))
        assertFalse(TicketFilterEngine.isHumanActiveSupportQueueName("Zabbix"))
        assertFalse(TicketFilterEngine.isHumanActiveSupportQueueName("Закупки"))
        assertFalse(TicketFilterEngine.isHumanActiveSupportQueueName("Назначенные мне"))
        assertFalse(TicketFilterEngine.isHumanActiveSupportQueueName("Решенные за неделю"))
        assertFalse(TicketFilterEngine.isHumanActiveSupportQueueName("Chat"))
        assertFalse(TicketFilterEngine.isHumanActiveSupportQueueName("Выполнено"))

        val inProgJql = TicketFilterEngine.buildInProgressJql(
            filter = FilterSettings(projectKeys = "CSL"),
            inProgressStatusIds = listOf("3", "10002")
        )
        assertTrue(inProgJql!!.contains("status in (3, 10002)"))
        assertTrue(inProgJql.endsWith("ORDER BY updated DESC"))

        val scoped = TicketFilterEngine.scopeJsmQueueJql(
            rawQueueJql = "resolution = Unresolved AND reporter != zabbix ORDER BY created DESC",
            projectKey = "CSL"
        )
        assertEquals(
            """project = "CSL" AND (resolution = Unresolved AND reporter != zabbix) ORDER BY created DESC""",
            scoped
        )
    }
}
