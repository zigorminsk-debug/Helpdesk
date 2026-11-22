package com.helpdesk.jiraagent

import com.helpdesk.jiraagent.data.ZabbixApiClient
import com.helpdesk.jiraagent.model.AppSettings
import com.helpdesk.jiraagent.model.ZabbixProblem
import com.helpdesk.jiraagent.model.ZabbixSeverity
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

class ZabbixIntegrationTest {

    @Test
    fun defaultsToZCslByAndBuildsRpcCandidates() {
        val defaults = AppSettings()
        assertEquals("http://z.csl.by", defaults.zabbixUrl)
        assertFalse(defaults.isZabbixConfigured)

        val configured = defaults.copy(zabbixLogin = "admin", zabbixPassword = "secret")
        assertTrue(configured.isZabbixConfigured)

        val urls = ZabbixApiClient.buildCandidateRpcUrls("http://z.csl.by")
        assertTrue(urls.contains("http://z.csl.by/api_jsonrpc.php"))
        assertTrue(urls.contains("https://z.csl.by/api_jsonrpc.php"))
        assertTrue(urls.contains("http://z.csl.by/zabbix/api_jsonrpc.php"))

        assertEquals("http://z.csl.by", ZabbixApiClient.normalizeWebBaseUrl("z.csl.by/index.php"))
    }

    @Test
    fun triggersClientLikeBannerForErrorsAndCriticalWarningsOnly() {
        val infoProb = ZabbixProblem(
            eventId = "101",
            triggerId = "11",
            name = "Zabbix agent restarted",
            severityLevel = ZabbixSeverity.INFORMATION.level
        )
        val ordinaryWarn = ZabbixProblem(
            eventId = "102",
            triggerId = "12",
            name = "CPU load slightly elevated",
            severityLevel = ZabbixSeverity.WARNING.level
        )
        val errorProb = ZabbixProblem(
            eventId = "103",
            triggerId = "13",
            name = "Ошибка службы резервного копирования",
            severityLevel = ZabbixSeverity.AVERAGE.level,
            hostDisplayName = "Backup-Srv",
            hostIp = "192.168.10.50"
        )
        val critWarnProb = ZabbixProblem(
            eventId = "104",
            triggerId = "14",
            name = "High memory utilization (>95%)",
            severityLevel = ZabbixSeverity.HIGH.level,
            hostDisplayName = "1C-Prod"
        )
        val disasterProb = ZabbixProblem(
            eventId = "105",
            triggerId = "15",
            name = "Host unavailable by ICMP ping",
            severityLevel = ZabbixSeverity.DISASTER.level,
            hostDisplayName = "Core-GW"
        )

        val defaultThreshold = AppSettings().normalizedZabbixMinSeverity
        assertFalse(infoProb.shouldTriggerBanner(defaultThreshold))
        assertFalse(ordinaryWarn.shouldTriggerBanner(defaultThreshold))
        assertTrue(errorProb.shouldTriggerBanner(defaultThreshold))
        assertTrue(critWarnProb.shouldTriggerBanner(defaultThreshold))
        assertTrue(disasterProb.shouldTriggerBanner(defaultThreshold))

        val ticket = errorProb.toBannerDisplayTicket("http://z.csl.by")
        assertEquals("ZBX-103", ticket.key)
        assertTrue(ticket.reporterName.contains("Backup-Srv"))
        assertTrue(ticket.isClientTicket)
    }

    @Test
    fun authenticatesFollowsPostRedirectsAndFetchesProblemsAcrossZabbix7And5() = runBlocking {
        var ackReceived = false
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api_jsonrpc.php") { ex ->
            ex.responseHeaders.add("Location", "/zbx7/api_jsonrpc.php")
            ex.sendResponseHeaders(301, -1)
            ex.close()
        }
        server.createContext("/zbx7/api_jsonrpc.php") { ex ->
            val reqBody = String(ex.requestBody.readBytes(), StandardCharsets.UTF_8)
            val authHeader = ex.requestHeaders.getFirst("Authorization").orEmpty()
            val responseJson = when {
                reqBody.contains("\"apiinfo.version\"") ->
                    """{"jsonrpc":"2.0","result":"7.0.5","id":1}"""
                reqBody.contains("\"user.login\"") -> {
                    assertTrue(reqBody.contains("\"username\":\"igor\""))
                    """{"jsonrpc":"2.0","result":"tok_zbx7_abc","id":2}"""
                }
                reqBody.contains("\"trigger.get\"") -> {
                    // Zabbix 7.0 requires Bearer header and forbids "auth" inside body!
                    assertEquals("Bearer tok_zbx7_abc", authHeader)
                    assertFalse(reqBody.contains("\"auth\":"))
                    """{"jsonrpc":"2.0","result":[{"triggerid":"55","description":"Unavailable by ICMP","priority":"4","value":"1","lastchange":"1763800000","comments":"Gateway check","url":"","hosts":[{"hostid":"10","host":"gw.csl.by","name":"Шлюз CSL"}],"lastEvent":{"eventid":"9001","name":"Шлюз недоступен","clock":"1763800000","acknowledged":"0","severity":"4"}}],"id":3}"""
                }
                reqBody.contains("\"problem.get\"") ->
                    """{"jsonrpc":"2.0","result":[{"eventid":"9001","objectid":"55","name":"Шлюз недоступен","opdata":"Loss: 100%","acknowledged":"0","suppressed":"0","clock":"1763800000","severity":"4","r_eventid":"0","acknowledges":[]}],"id":4}"""
                reqBody.contains("\"hostinterface.get\"") ->
                    """{"jsonrpc":"2.0","result":[{"hostid":"10","ip":"10.10.0.1","dns":"gw.csl.by","main":"1"}],"id":5}"""
                reqBody.contains("\"host.get\"") ->
                    """{"jsonrpc":"2.0","result":"42","id":6}"""
                reqBody.contains("\"event.acknowledge\"") -> {
                    ackReceived = true
                    """{"jsonrpc":"2.0","result":{"eventids":[9001]},"id":7}"""
                }
                else -> """{"jsonrpc":"2.0","result":[],"id":99}"""
            }
            val bytes = responseJson.toByteArray(StandardCharsets.UTF_8)
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()

        try {
            val port = server.address.port
            val client = ZabbixApiClient()
            val settings = AppSettings(
                zabbixUrl = "http://127.0.0.1:$port",
                zabbixLogin = "igor",
                zabbixPassword = "pw"
            )
            val snap = client.fetchSnapshot(settings).getOrThrow()
            assertEquals("7.0.5", snap.serverVersion)
            assertEquals(42, snap.monitoredHostsCount)
            assertEquals(1, snap.problems.size)
            val prob = snap.problems.first()
            assertEquals("9001", prob.eventId)
            assertEquals(4, prob.severityLevel)
            assertEquals("Шлюз CSL (10.10.0.1)", prob.hostWithIpLabel)
            assertEquals("Loss: 100%", prob.opData)

            client.acknowledgeProblem(settings, "9001", "Проверяем шлюз").getOrThrow()
            assertTrue(ackReceived)
        } finally {
            server.stop(0)
        }
    }
}
