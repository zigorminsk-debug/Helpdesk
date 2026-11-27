package com.helpdesk.jiraagent

import com.helpdesk.jiraagent.data.JiraApiClient
import com.helpdesk.jiraagent.model.AppSettings
import com.helpdesk.jiraagent.model.FilterSettings
import com.helpdesk.jiraagent.model.JiraServerType
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class SystemRoomsNoAutoCreateAndCleanupTest {

    private fun readHttpRequest(input: InputStream): Pair<String, String> {
        val headerBuf = ByteArrayOutputStream()
        var state = 0
        while (true) {
            val b = input.read()
            if (b == -1) break
            headerBuf.write(b)
            state = when {
                state == 0 && b == '\r'.code -> 1
                state == 1 && b == '\n'.code -> 2
                state == 2 && b == '\r'.code -> 3
                state == 3 && b == '\n'.code -> 4
                else -> 0
            }
            if (state == 4) break
        }
        val headersText = headerBuf.toString(StandardCharsets.UTF_8.name())
        val firstLine = headersText.lineSequence().firstOrNull().orEmpty()
        val contentLength = headersText.lineSequence()
            .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
            ?.substringAfter(":")
            ?.trim()
            ?.toIntOrNull() ?: 0
        val bodyBytes = ByteArray(contentLength)
        var offset = 0
        while (offset < contentLength) {
            val n = input.read(bodyBytes, offset, contentLength - offset)
            if (n <= 0) break
            offset += n
        }
        return firstLine to String(bodyBytes, 0, offset, StandardCharsets.UTF_8)
    }

    @Test
    fun neverAutoCreatesSystemRoomIssuesWhenManualKeyBlank() = runBlocking {
        val recordedRequests = CopyOnWriteArrayList<String>()
        val serverSocket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val running = AtomicBoolean(true)
        val acceptThread = thread(start = true) {
            while (running.get()) {
                val sock = runCatching { serverSocket.accept() }.getOrNull() ?: break
                sock.use { client ->
                    val (reqLine, _) = readHttpRequest(client.getInputStream())
                    if (reqLine.isNotBlank()) recordedRequests.add(reqLine)
                    val resp = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}"
                    client.getOutputStream().write(resp.toByteArray(StandardCharsets.UTF_8))
                }
            }
        }

        try {
            val port = serverSocket.localPort
            val jira = JiraApiClient()
            val settings = AppSettings(
                serverUrl = "http://127.0.0.1:$port",
                serverType = JiraServerType.SERVER_DC,
                usernameOrEmail = "agent",
                passwordOrToken = "secret",
                adminChatIssueKey = ""
            )

            val chatRes = jira.resolveOrCreateAdminChatRoom(settings, FilterSettings(), cachedRoomKey = "CSL-37733")
            val repairRes = jira.resolveOrCreateRepairRegistryRoom(settings, FilterSettings(), cachedRoomKey = "CSL-37729")

            assertTrue(chatRes.isFailure)
            assertTrue(repairRes.isFailure)
            assertFalse(
                "Must never POST /rest/api/2/issue automatically",
                recordedRequests.any { it.startsWith("POST /rest/api/2/issue ") }
            )
            assertEquals(0, recordedRequests.size)
        } finally {
            running.set(false)
            runCatching { serverSocket.close() }
            acceptThread.join(1000L)
        }
    }

    @Test
    fun cleanupDeletesOrMultiHopClosesDuplicateRoomIssuesAndNeverTouchesClientTickets() = runBlocking {
        val deletedKeys = CopyOnWriteArrayList<String>()
        val csl3702Stage = AtomicInteger(0) // 0 = Waiting for support, 1 = In Progress, 2 = Done
        val serverSocket = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
        val running = AtomicBoolean(true)

        val acceptThread = thread(start = true) {
            while (running.get()) {
                val sock = runCatching { serverSocket.accept() }.getOrNull() ?: break
                sock.use { client ->
                    val (reqLine, body) = readHttpRequest(client.getInputStream())
                    var status = "HTTP/1.1 200 OK"
                    val jsonBody: String = when {
                        reqLine.startsWith("GET /rest/api/2/search") -> """
                            {
                              "issues": [
                                {
                                  "key": "CSL-100",
                                  "fields": {
                                    "summary": "Не печатает принтер в бухгалтерии",
                                    "status": { "name": "Ожидание поддержки", "statusCategory": { "key": "new" } }
                                  }
                                },
                                {
                                  "key": "CSL-3701",
                                  "fields": {
                                    "summary": "[HELPCSL_REPAIR_REGISTRY_ROOM] Журнал техники в ремонте",
                                    "status": { "name": "Ожидание поддержки", "statusCategory": { "key": "new" } }
                                  }
                                },
                                {
                                  "key": "CSL-3702",
                                  "fields": {
                                    "summary": "[HELPCSL_ADMIN_CHAT_ROOM] Внутренний чат админов техподдержки",
                                    "status": { "name": "Ожидание поддержки", "statusCategory": { "key": "new" } }
                                  }
                                }
                              ]
                            }
                        """.trimIndent()

                        reqLine.startsWith("DELETE /rest/api/2/issue/CSL-3701") -> {
                            deletedKeys.add("CSL-3701")
                            status = "HTTP/1.1 204 No Content"
                            ""
                        }

                        reqLine.startsWith("DELETE /rest/api/2/issue/CSL-3702") -> {
                            status = "HTTP/1.1 403 Forbidden"
                            """{"errorMessages":["No delete permission"]}"""
                        }

                        reqLine.startsWith("GET /rest/api/2/issue/CSL-3702?fields=status") -> {
                            val catKey = when (csl3702Stage.get()) {
                                0 -> "new"
                                1 -> "indeterminate"
                                else -> "done"
                            }
                            """{"fields":{"status":{"statusCategory":{"key":"$catKey"}}}}"""
                        }

                        reqLine.startsWith("GET /rest/api/2/issue/CSL-3702/transitions") -> {
                            if (csl3702Stage.get() == 0) {
                                """
                                {
                                  "transitions": [
                                    {
                                      "id": "11",
                                      "name": "Взять в работу",
                                      "to": { "name": "В работе", "statusCategory": { "key": "indeterminate" } }
                                    }
                                  ]
                                }
                                """.trimIndent()
                            } else {
                                """
                                {
                                  "transitions": [
                                    {
                                      "id": "31",
                                      "name": "Отменить запрос",
                                      "to": { "name": "Отменено", "statusCategory": { "key": "done" } }
                                    }
                                  ]
                                }
                                """.trimIndent()
                            }
                        }

                        reqLine.startsWith("POST /rest/api/2/issue/CSL-3702/transitions") -> {
                            if (body.contains("\"11\"")) csl3702Stage.set(1)
                            if (body.contains("\"31\"")) csl3702Stage.set(2)
                            status = "HTTP/1.1 204 No Content"
                            ""
                        }

                        else -> "{}"
                    }

                    val bytes = jsonBody.toByteArray(StandardCharsets.UTF_8)
                    val hdr = "$status\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                    val out = client.getOutputStream()
                    out.write(hdr.toByteArray(StandardCharsets.UTF_8))
                    if (bytes.isNotEmpty()) out.write(bytes)
                    out.flush()
                }
            }
        }

        try {
            val port = serverSocket.localPort
            val jira = JiraApiClient()
            val settings = AppSettings(
                serverUrl = "http://127.0.0.1:$port",
                serverType = JiraServerType.SERVER_DC,
                usernameOrEmail = "agent",
                passwordOrToken = "secret"
            )
            val report = jira.cleanupSystemRoomIssues(settings, FilterSettings(projectKeys = "CSL")).getOrThrow()

            assertEquals(2, report.foundTotal)
            assertEquals(1, report.deletedCount)
            assertEquals(1, report.closedCount)
            assertEquals(0, report.failedCount)
            assertEquals(listOf("CSL-3701"), deletedKeys.toList())
            assertEquals(2, csl3702Stage.get())
        } finally {
            running.set(false)
            runCatching { serverSocket.close() }
            acceptThread.join(1000L)
        }
    }
}
