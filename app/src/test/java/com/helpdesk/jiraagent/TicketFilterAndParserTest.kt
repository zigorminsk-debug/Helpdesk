package com.helpdesk.jiraagent

import com.helpdesk.jiraagent.data.JiraJsonParser
import com.helpdesk.jiraagent.domain.TicketFilterEngine
import com.helpdesk.jiraagent.model.AppSettings
import com.helpdesk.jiraagent.model.FilterSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TicketFilterAndParserTest {

    @Test
    fun jsmServiceRequestFromClientPassesWhileZabbixSenderGoesToService() {
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
                      "statusCategory": { "key": "new" }
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
                  "id": "37367",
                  "key": "CSL-37367",
                  "fields": {
                    "summary": "High CPU usage on srv-db-01",
                    "description": "Problem started at 12:30:00",
                    "customfield_10010": {
                      "requestType": { "name": "Проблемы с ПК/Ноутбуком" }
                    },
                    "issuetype": { "name": "Service Request", "subtask": false },
                    "status": {
                      "name": "Ожидаие поддержки",
                      "statusCategory": { "key": "new" }
                    },
                    "priority": { "name": "High" },
                    "project": { "key": "CSL", "name": "Customer Support Line" },
                    "reporter": {
                      "displayName": "Zabbix",
                      "name": "zabbix",
                      "emailAddress": "zabbix@company.by"
                    },
                    "created": "2026-11-21T12:30:00.000+0300",
                    "updated": "2026-11-21T12:30:00.000+0300",
                    "labels": []
                  }
                },
                {
                  "id": "37368",
                  "key": "CSL-37368",
                  "fields": {
                    "summary": "Host unreachable",
                    "description": "Alert via creator field",
                    "issuetype": { "name": "Сервисный запрос", "subtask": false },
                    "status": {
                      "name": "Open",
                      "statusCategory": { "key": "new" }
                    },
                    "priority": { "name": "High" },
                    "project": { "key": "CSL", "name": "Customer Support Line" },
                    "reporter": {
                      "displayName": "Система оповещений",
                      "name": "zabbix_notify"
                    },
                    "created": "2026-11-21T12:31:00.000+0300",
                    "updated": "2026-11-21T12:31:00.000+0300",
                    "labels": []
                  }
                }
              ]
            }
        """.trimIndent()

        val settings = AppSettings(serverUrl = "https://jira.company.by")
        val filter = FilterSettings()

        val parsed = JiraJsonParser.parseSearchResponse(sampleSearchJson, settings, filter)
        assertEquals(3, parsed.size)

        // 1. CSL-37366 ("Сейлзмодерн", "Проблемы с ПК/Ноутбуком", "Service Request") MUST be a client ticket!
        val clientTicket = parsed[0]
        assertEquals("CSL-37366", clientTicket.key)
        assertTrue("Client JSM ticket must pass filter", clientTicket.isClientTicket)
        assertNull(clientTicket.filterReason)
        assertEquals(2, clientTicket.phoneNumbers.size)
        assertEquals("+375291234567", clientTicket.phoneNumbers[0].e164)
        assertEquals("+79162223344", clientTicket.phoneNumbers[1].e164)

        // 2. CSL-37367 from sender "Zabbix" MUST go to service tickets even if requestType is JSM!
        val zabbixTicket1 = parsed[1]
        assertEquals("CSL-37367", zabbixTicket1.key)
        assertFalse("Zabbix ticket must be classified as service", zabbixTicket1.isClientTicket)
        assertTrue(zabbixTicket1.filterReason!!.contains("Сервисный отправитель"))

        // 3. CSL-37368 where login name contains "zabbix" MUST also go to service tickets!
        val zabbixTicket2 = parsed[2]
        assertEquals("CSL-37368", zabbixTicket2.key)
        assertFalse("Zabbix login ticket must be classified as service", zabbixTicket2.isClientTicket)
        assertTrue(zabbixTicket2.filterReason!!.contains("Сервисный отправитель"))
    }

    @Test
    fun buildsExpectedSafeJql() {
        val filter = FilterSettings(
            projectKeys = "CSL",
            excludeResolvedClosed = true,
            customJqlCondition = "priority in (High, Highest)"
        )
        val jql = TicketFilterEngine.buildJql(filter)
        assertEquals(
            "project in (\"CSL\") AND statusCategory != Done AND (priority in (High, Highest)) ORDER BY created DESC",
            jql
        )
    }
}
