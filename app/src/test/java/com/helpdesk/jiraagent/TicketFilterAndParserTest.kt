package com.helpdesk.jiraagent

import com.helpdesk.jiraagent.data.JiraJsonParser
import com.helpdesk.jiraagent.domain.TicketFilterEngine
import com.helpdesk.jiraagent.model.AppSettings
import com.helpdesk.jiraagent.model.FilterSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TicketFilterAndParserTest {

    @Test
    fun parsesServerV2AndCloudV3TicketsAndFiltersOutServiceOnes() {
        val sampleSearchJson = """
            {
              "names": {
                "customfield_10200": "Телефон клиента",
                "customfield_10010": "Customer Request Type"
              },
              "issues": [
                {
                  "id": "10001",
                  "key": "HD-501",
                  "fields": {
                    "summary": "Не работает автообновление прайс-листа у клиента",
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
                    "customfield_10200": "8 (029) 765-43-21 доб. 12",
                    "customfield_10010": {
                      "requestType": { "name": "Обращение клиента" }
                    },
                    "issuetype": { "name": "Support", "subtask": false },
                    "status": {
                      "name": "Ожидание поддержки",
                      "statusCategory": { "key": "new" }
                    },
                    "priority": { "name": "High" },
                    "project": { "key": "HD", "name": "Helpdesk" },
                    "reporter": {
                      "displayName": "Анна Ковалёва",
                      "emailAddress": "anna.client@gmail.com"
                    },
                    "created": "2026-11-21T09:10:00.000+0300",
                    "updated": "2026-11-21T09:10:00.000+0300",
                    "labels": ["vip"]
                  }
                },
                {
                  "id": "10002",
                  "key": "HD-502",
                  "fields": {
                    "summary": "[monitoring] High CPU usage on node-03",
                    "description": "Automatic Zabbix alert",
                    "issuetype": { "name": "Сервисная", "subtask": false },
                    "status": {
                      "name": "Open",
                      "statusCategory": { "key": "new" }
                    },
                    "priority": { "name": "Medium" },
                    "project": { "key": "HD", "name": "Helpdesk" },
                    "reporter": {
                      "displayName": "Zabbix Monitoring Bot",
                      "name": "zabbix-bot"
                    },
                    "created": "2026-11-21T09:12:00.000+0300",
                    "updated": "2026-11-21T09:12:00.000+0300",
                    "labels": ["internal"]
                  }
                }
              ]
            }
        """.trimIndent()

        val settings = AppSettings(serverUrl = "https://jira.company.by")
        val filter = FilterSettings()

        val parsed = JiraJsonParser.parseSearchResponse(sampleSearchJson, settings, filter)
        assertEquals(2, parsed.size)

        val clientTicket = parsed[0]
        assertEquals("HD-501", clientTicket.key)
        assertTrue("Client ticket must pass filter", clientTicket.isClientTicket)
        assertEquals(2, clientTicket.phoneNumbers.size)
        // Custom phone field comes first, normalized to +375
        assertEquals("+375297654321", clientTicket.phoneNumbers[0].e164)
        // ADF description phone comes second, normalized to +7
        assertEquals("+79162223344", clientTicket.phoneNumbers[1].e164)

        val serviceTicket = parsed[1]
        assertEquals("HD-502", serviceTicket.key)
        assertFalse("Service ticket must be filtered out", serviceTicket.isClientTicket)
        assertTrue(serviceTicket.filterReason!!.isNotBlank())
    }

    @Test
    fun buildsExpectedSafeJql() {
        val filter = FilterSettings(
            projectKeys = "HD, SUP",
            excludeResolvedClosed = true,
            customJqlCondition = "priority in (High, Highest)"
        )
        val jql = TicketFilterEngine.buildJql(filter)
        assertEquals(
            "project in (\"HD\", \"SUP\") AND statusCategory != Done AND (priority in (High, Highest)) ORDER BY created DESC",
            jql
        )
    }
}
