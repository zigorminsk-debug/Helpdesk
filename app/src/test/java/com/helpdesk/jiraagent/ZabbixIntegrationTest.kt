package com.helpdesk.jiraagent

import com.helpdesk.jiraagent.data.ZabbixApiClient
import com.helpdesk.jiraagent.model.AppSettings
import com.helpdesk.jiraagent.model.ZabbixProblem
import com.helpdesk.jiraagent.model.ZabbixSeverity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

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
    fun dismissesExistingProblemsAndMutesUnsolvableTriggersForever() {
        val oldStanding = ZabbixProblem(
            eventId = "500",
            triggerId = "77",
            name = "SPAMLIST mail.technograd.by is listed on zen.spamhaus.org",
            severityLevel = 4,
            hostDisplayName = "SPAMLIST mail.technograd.by",
            clockEpochSeconds = 1_763_600_000L
        )
        val newEventSameTrigger = oldStanding.copy(
            eventId = "501",
            clockEpochSeconds = 1_763_900_000L
        )

        // 1. Closing a current card hides ONLY that active occurrence; a brand-new occurrence appears!
        val dismissedKeys = setOf(oldStanding.alertKey, oldStanding.fallbackOccurrenceKey)
        assertTrue(oldStanding.isHiddenFromActiveFeed(dismissedKeys, emptySet()))
        assertFalse(newEventSameTrigger.isHiddenFromActiveFeed(dismissedKeys, emptySet()))

        // 2. Marking a problem as "Не решаема" mutes the trigger permanently, even for future new eventIds!
        val unsolvableIlo = ZabbixProblem(
            eventId = "600",
            triggerId = "88",
            name = "HP iLO: System status is in critical state",
            severityLevel = 4,
            hostDisplayName = "CSL.HP DL 380",
            clockEpochSeconds = 1_763_500_000L
        )
        val mutedRule = unsolvableIlo.toMutedRule()
        val mutedKeySet = mutedRule.allMatchKeys()

        val futureIloEvent = unsolvableIlo.copy(
            eventId = "999",
            clockEpochSeconds = 1_764_000_000L
        )
        val recreatedTriggerSameSignature = unsolvableIlo.copy(
            eventId = "1000",
            triggerId = "9999",
            clockEpochSeconds = 1_764_100_000L
        )

        assertTrue(unsolvableIlo.isHiddenFromActiveFeed(emptySet(), mutedKeySet))
        assertTrue(futureIloEvent.isHiddenFromActiveFeed(emptySet(), mutedKeySet))
        assertTrue(recreatedTriggerSameSignature.isHiddenFromActiveFeed(emptySet(), mutedKeySet))
    }

    private fun readHttpPacket(ins: InputStream): Triple<String, Map<String, String>, String>? {
        val headerBuf = ByteArrayOutputStream()
        var state = 0 // 0, 1=\r, 2=\r\n, 3=\r\n\r, 4=\r\n\r\n
        while (state < 4) {
            val b = ins.read()
            if (b < 0) return null
            headerBuf.write(b)
            state = when {
                b == '\r'.code && (state == 0 || state == 2) -> state + 1
                b == '\n'.code && (state == 1 || state == 3) -> state + 1
                b == '\r'.code -> 1
                else -> 0
            }
        }
        val rawHeaders = String(headerBuf.toByteArray(), StandardCharsets.UTF_8)
        val lines = rawHeaders.split("\r\n").filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null
        val path = lines.first().split(" ").getOrNull(1).orEmpty()
        val headers = HashMap<String, String>()
        for (i in 1 until lines.size) {
            val idx = lines[i].indexOf(':')
            if (idx > 0) {
                headers[lines[i].substring(0, idx).trim().lowercase()] = lines[i].substring(idx + 1).trim()
            }
        }
        val contentLen = headers["content-length"]?.toIntOrNull() ?: 0
        val bodyBytes = ByteArray(contentLen)
        var offset = 0
        while (offset < contentLen) {
            val r = ins.read(bodyBytes, offset, contentLen - offset)
            if (r <= 0) break
            offset += r
        }
        val bodyStr = String(bodyBytes, 0, offset, StandardCharsets.UTF_8)
        return Triple(path, headers, bodyStr)
    }

    @Test
    fun authenticatesFollowsPostRedirectsAndFetchesProblemsZabbix7() = runBlocking {
        val ackReceived = AtomicBoolean(false)
        val running = AtomicBoolean(true)
        val serverSocket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))

        val acceptThread = thread(start = true, isDaemon = true) {
            while (running.get()) {
                val sock = runCatching { serverSocket.accept() }.getOrNull() ?: break
                thread(start = true, isDaemon = true) {
                    sock.use { clientSock ->
                        clientSock.soTimeout = 5000
                        val packet = readHttpPacket(clientSock.getInputStream()) ?: return@use
                        val (path, headers, reqBody) = packet
                        val authHeader = headers["authorization"].orEmpty()
                        val out = clientSock.getOutputStream()

                        if (path == "/api_jsonrpc.php") {
                            val redirectHttp = "HTTP/1.1 301 Moved Permanently\r\n" +
                                "Location: /zbx7/api_jsonrpc.php\r\n" +
                                "Content-Length: 0\r\n" +
                                "Connection: close\r\n\r\n"
                            out.write(redirectHttp.toByteArray(StandardCharsets.UTF_8))
                            out.flush()
                        } else {
                            val responseJson = when {
                                reqBody.contains("\"apiinfo.version\"") ->
                                    """{"jsonrpc":"2.0","result":"7.0.5","id":1}"""
                                reqBody.contains("\"user.login\"") ->
                                    """{"jsonrpc":"2.0","result":"tok_zbx7_abc","id":2}"""
                                reqBody.contains("\"trigger.get\"") -> {
                                    if (authHeader != "Bearer tok_zbx7_abc" || reqBody.contains("\"auth\":")) {
                                        """{"jsonrpc":"2.0","error":{"code":-32602,"message":"Invalid params","data":"unexpected auth"},"id":3}"""
                                    } else {
                                        """{"jsonrpc":"2.0","result":[{"triggerid":"55","description":"Unavailable by ICMP","priority":"4","value":"1","lastchange":"1763800000","comments":"Gateway check","url":"","hosts":[{"hostid":"10","host":"gw.csl.by","name":"Шлюз CSL"}],"lastEvent":{"eventid":"9001","name":"Шлюз недоступен","clock":"1763800000","acknowledged":"0","severity":"4"}}],"id":3}"""
                                    }
                                }
                                reqBody.contains("\"problem.get\"") ->
                                    """{"jsonrpc":"2.0","result":[{"eventid":"9001","objectid":"55","name":"Шлюз недоступен","opdata":"Loss: 100%","acknowledged":"0","suppressed":"0","clock":"1763800000","severity":"4","r_eventid":"0","acknowledges":[]}],"id":4}"""
                                reqBody.contains("\"hostinterface.get\"") ->
                                    """{"jsonrpc":"2.0","result":[{"hostid":"10","ip":"10.10.0.1","dns":"gw.csl.by","main":"1"}],"id":5}"""
                                reqBody.contains("\"host.get\"") ->
                                    """{"jsonrpc":"2.0","result":"42","id":6}"""
                                reqBody.contains("\"event.acknowledge\"") -> {
                                    ackReceived.set(true)
                                    """{"jsonrpc":"2.0","result":{"eventids":[9001]},"id":7}"""
                                }
                                else -> """{"jsonrpc":"2.0","result":[],"id":99}"""
                            }
                            val payloadBytes = responseJson.toByteArray(StandardCharsets.UTF_8)
                            val httpResp = "HTTP/1.1 200 OK\r\n" +
                                "Content-Type: application/json\r\n" +
                                "Content-Length: ${payloadBytes.size}\r\n" +
                                "Connection: close\r\n\r\n"
                            out.write(httpResp.toByteArray(StandardCharsets.UTF_8))
                            out.write(payloadBytes)
                            out.flush()
                        }
                    }
                }
            }
        }

        try {
            val port = serverSocket.localPort
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
            assertTrue(ackReceived.get())
        } finally {
            running.set(false)
            runCatching { serverSocket.close() }
            acceptThread.join(1000L)
        }
    }
}
