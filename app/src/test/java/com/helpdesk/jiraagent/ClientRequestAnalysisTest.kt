package com.helpdesk.jiraagent

import com.helpdesk.jiraagent.data.JiraApiClient
import com.helpdesk.jiraagent.data.JiraJsonParser
import com.helpdesk.jiraagent.domain.ClientRequestAnalysis
import com.helpdesk.jiraagent.model.AppSettings
import com.helpdesk.jiraagent.model.FilterSettings
import com.helpdesk.jiraagent.model.JiraOrganizationRef
import com.helpdesk.jiraagent.model.JiraServerType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class ClientRequestAnalysisTest {
    private val settings = AppSettings(serverUrl = "https://jira.example")
    private val filters = FilterSettings(clientOnlyFilterEnabled = false)
    private fun issue(key: String, summary: String, account: String = "a", orgId: String = "7") = """
        {"key":"$key","fields":{"summary":"$summary","reporter":{"displayName":"Иван","accountId":"$account"},
        "customfield_10001":[{"id":"$orgId","name":"Клиент"}],"created":"2026-01-01T00:00:00.000+0300"}}
    """.trimIndent()
    private fun parse(json: String) = JiraJsonParser.parseSearchResponse(json, settings, filters)
    private fun response(vararg issues: String) = """{"names":{"customfield_10001":"Organizations"},"total":${issues.size},"issues":[${issues.joinToString(",")}]}"""

    @Test fun explicitOrganizationAndReporterIdentityAreParsedWithoutGuessing() {
        val tickets = parse(response(issue("A-1", "VPN", "a", "7"), issue("A-2", "VPN", "b", "8")))
        assertEquals(JiraOrganizationRef("customfield_10001", "7", "Клиент"), tickets[0].organizations!!.single())
        assertEquals("accountId:a", tickets[0].reporterIdentity)
        assertFalse(ClientRequestAnalysis.sameReporter(tickets[0], tickets[1]))
        assertFalse(ClientRequestAnalysis.sameOrganization(tickets[0].organizations!!.single(), tickets[1]))
        val noNames = parse("""{"issues":[${issue("A-3", "VPN")}]}""").single()
        assertTrue(noNames.organizations.orEmpty().isEmpty())
        val fallback = noNames.copy(reporterIdentity = null)
        assertTrue(ClientRequestAnalysis.sameReporter(tickets[0], fallback))
        assertFalse(ClientRequestAnalysis.sameReporter(tickets[0].copy(reporterName = "Клиент", reporterIdentity = null), fallback.copy(reporterName = "Клиент")))
    }

    @Test fun topicsCountUniqueTicketsAndShowRealSources() {
        val first = parse(response(issue("A-1", "Нет доступа к VPN"))).single()
        val second = first.copy(key = "A-2", summary = "Неизвестная проблема")
        val groups = ClientRequestAnalysis.groups(listOf(first, first, second))
        assertEquals(listOf("A-1"), groups["Доступ и учётные записи"]!!.map { it.key })
        assertEquals("Нет доступа к VPN", groups["Сеть, интернет и VPN"]!!.single().summary)
        assertEquals("A-2", groups[ClientRequestAnalysis.OTHER]!!.single().key)
    }

    private class FixtureServer(private val code: Int, private val body: String) : AutoCloseable {
        private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        private val running = AtomicBoolean(true)
        val query = AtomicReference("")
        val port: Int get() = server.localPort
        private val worker = thread(isDaemon = true) {
            while (running.get()) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                socket.use { client ->
                    client.soTimeout = 3000
                    val reader = client.getInputStream().bufferedReader()
                    val line = reader.readLine().orEmpty()
                    query.set(URLDecoder.decode(line, "UTF-8"))
                    while (!reader.readLine().isNullOrEmpty()) { /* consume headers */ }
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    val headers = "HTTP/1.1 $code Test\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                    client.getOutputStream().apply { write(headers.toByteArray(Charsets.UTF_8)); write(bytes); flush() }
                }
            }
        }
        override fun close() {
            running.set(false)
            server.close()
            worker.join(1000)
        }
    }

    @Test fun organizationSearchUsesIdProjectsAndVerifiesReturnedIdentity() = runBlocking {
        FixtureServer(200, response(issue("A-1", "VPN", orgId = "7"), issue("A-2", "Почта", orgId = "8"))).use { server ->
            val config = settings.copy(serverUrl = "http://127.0.0.1:${server.port}", serverType = JiraServerType.SERVER_DC)
            val result = JiraApiClient().fetchOrganizationHistory(JiraOrganizationRef("customfield_10001", "7", "Клиент"), config, filters.copy(projectKeys = "A")).getOrThrow()
            assertEquals(listOf("A-1"), result.map { it.key })
            assertTrue(server.query.get().contains("cf[10001] = 7"))
            assertTrue(server.query.get().contains("project in (\"A\")"))
            assertTrue(server.query.get().contains("expand=names"))
            assertTrue(JiraApiClient().fetchOrganizationHistory(JiraOrganizationRef("customfield_10001", "7 OR 1=1", "x"), config, filters).isFailure)
        }
    }

    @Test fun searchFailuresAreNotEmptyHistories() = runBlocking {
        FixtureServer(403, "").use { server ->
            val config = settings.copy(serverUrl = "http://127.0.0.1:${server.port}", serverType = JiraServerType.SERVER_DC)
            val api = JiraApiClient()
            assertTrue(api.fetchClientHistory("Иван", "A-1", config, filters).isFailure)
            assertTrue(api.fetchOrganizationHistory(JiraOrganizationRef("customfield_10001", "7", "Клиент"), config, filters).isFailure)
        }
    }
}
