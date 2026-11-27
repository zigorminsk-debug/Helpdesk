package com.helpdesk.jiraagent

import com.helpdesk.jiraagent.data.JiraApiException
import com.helpdesk.jiraagent.data.JiraApiClient
import com.helpdesk.jiraagent.model.AppSettings
import com.helpdesk.jiraagent.model.FilterSettings
import com.helpdesk.jiraagent.model.JiraServerType
import kotlinx.coroutines.runBlocking
import java.net.ServerSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import com.helpdesk.jiraagent.domain.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException

class JiraConnectionAlarmTest {
    @Test fun classifiesTransportServerAndAuthorizationSeparately() {
        assertEquals(JiraConnectionObservation.UNREACHABLE, JiraConnectionAlarm.classify(JiraApiException("network", isNetworkError = true)))
        assertEquals(JiraConnectionObservation.UNREACHABLE, JiraConnectionAlarm.classify(SocketTimeoutException()))
        assertEquals(JiraConnectionObservation.UNREACHABLE, JiraConnectionAlarm.classify(JiraApiException("unavailable", 503)))
        assertEquals(JiraConnectionObservation.REACHABLE, JiraConnectionAlarm.classify(JiraApiException("login", 401)))
        assertEquals(JiraConnectionObservation.REACHABLE, JiraConnectionAlarm.classify(JiraApiException("jql", 400)))
        assertEquals(JiraConnectionObservation.UNKNOWN, JiraConnectionAlarm.classify(SSLException("certificate")))
    }
    @Test fun incidentSignalsOnceAndClearsOnlyWhenReachable() {
        val tracker = JiraConnectionAlarmTracker()
        assertEquals(JiraConnectionAlarmChange.STARTED, tracker.observe(JiraConnectionObservation.UNREACHABLE))
        assertEquals(JiraConnectionAlarmChange.NONE, tracker.observe(JiraConnectionObservation.UNREACHABLE))
        assertEquals(JiraConnectionAlarmChange.NONE, tracker.observe(JiraConnectionObservation.UNKNOWN))
        assertTrue(tracker.isUnavailable())
        assertEquals(JiraConnectionAlarmChange.RECOVERED, tracker.observe(JiraConnectionObservation.REACHABLE))
        assertFalse(tracker.isUnavailable())
        assertEquals(JiraConnectionAlarmChange.STARTED, tracker.observe(JiraConnectionObservation.UNREACHABLE))
        tracker.reset()
        assertFalse(tracker.isUnavailable())
    }
    @Test fun actualTicketPollDistinguishesServiceFailureAndAuthorization() = runBlocking {
        for ((code, expected) in listOf(503 to JiraConnectionObservation.UNREACHABLE, 401 to JiraConnectionObservation.REACHABLE)) {
            ServerSocket(0, 16, InetAddress.getByName("127.0.0.1")).use { server ->
                val running = AtomicBoolean(true)
                val worker = thread(isDaemon = true) {
                    while (running.get()) {
                        val socket = runCatching { server.accept() }.getOrNull() ?: break
                        socket.use { client ->
                            val input = client.getInputStream().bufferedReader()
                            while (!input.readLine().isNullOrEmpty()) { }
                            val body = "{}"
                            val response = "HTTP/1.1 $code Test\r\nContent-Length: ${body.length}\r\nContent-Type: application/json\r\nConnection: close\r\n\r\n$body"
                            client.getOutputStream().apply { write(response.toByteArray()); flush() }
                        }
                    }
                }
                try {
                    val settings = AppSettings(serverUrl = "http://127.0.0.1:${server.localPort}", usernameOrEmail = "test", passwordOrToken = "test", serverType = JiraServerType.SERVER_DC)
                    val failure = JiraApiClient().fetchRecentTickets(settings, FilterSettings(customJqlCondition = "ORDER BY created DESC")).exceptionOrNull()
                    assertTrue("Expected poll failure for HTTP $code", failure != null)
                    assertEquals(expected, JiraConnectionAlarm.classify(failure!!))
                } finally {
                    running.set(false)
                    server.close()
                    worker.join(1000)
                }
            }
        }
    }
}
