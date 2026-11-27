package com.helpdesk.jiraagent

import com.helpdesk.jiraagent.data.ConfluenceAttachment
import com.helpdesk.jiraagent.data.ConfluenceOfflineStore
import com.helpdesk.jiraagent.data.ConfluenceWikiClient
import com.helpdesk.jiraagent.model.AppSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class ConfluenceAttachmentCacheTest {
    private class Server : AutoCloseable {
        private val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val base = "http://127.0.0.1:${socket.localPort}"
        val downloads = AtomicInteger()
        @Volatile var rejectDownload = false
        private val worker = thread(isDaemon = true) {
            while (!socket.isClosed) {
                val connection = runCatching { socket.accept() }.getOrNull() ?: break
                connection.use { client ->
                    val input = client.getInputStream().bufferedReader()
                    val request = input.readLine().orEmpty()
                    while (!input.readLine().isNullOrEmpty()) { }
                    val isList = request.contains("child/attachment")
                    val isSecond = request.contains("start=1")
                    val body = when {
                        isList && isSecond -> """{"results":[{"id":"2","title":"b.txt","extensions":{"fileSize":3},"version":{"number":2},"_links":{"download":"/b"}}]}"""
                        isList -> """{"results":[{"id":"1","title":"a.txt","extensions":{"fileSize":3},"version":{"number":1},"_links":{"download":"/a"}}],"_links":{"next":"next"}}"""
                        else -> { downloads.incrementAndGet(); if (rejectDownload) "bad" else "abc" }
                    }.toByteArray()
                    val status = if (!isList && rejectDownload) "302 Found\r\nLocation: http://example.com/file\r\n" else "200 OK\r\n"
                    val headers = "HTTP/1.1 $status Content-Type: ${if (isList) "application/json" else "application/octet-stream"}\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".replace("\r\n Content", "\r\nContent")
                    client.getOutputStream().apply { write(headers.toByteArray()); write(body); flush() }
                }
            }
        }
        override fun close() { socket.close(); worker.join(2000) }
    }

    @Test fun paginationAndOfflineCacheWithVersionChangesAndWipe() = runBlocking {
        val dir = Files.createTempDirectory("wiki-attachment-test").toFile()
        try {
            Server().use { server ->
                val settings = AppSettings(confluenceUrl = server.base, confluenceToken = "test")
                val client = ConfluenceWikiClient()
                val files = client.fetchPageAttachments("42", settings).getOrThrow()
                assertEquals(listOf("1", "2"), files.map { it.id })
                val store = ConfluenceOfflineStore(dir)
                val first = files.first()
                assertEquals("abc", store.getOrDownloadAttachment(first, client, settings).getOrThrow().readText())
                assertEquals(1, server.downloads.get())
                val reopened = ConfluenceOfflineStore(dir)
                assertEquals("abc", reopened.getCachedAttachmentFile(first)!!.readText())
                reopened.getOrDownloadAttachment(first, client, settings).getOrThrow()
                assertEquals(1, server.downloads.get())
                server.rejectDownload = true
                val newVersion = first.copy(versionNumber = 2)
                assertTrue(reopened.getOrDownloadAttachment(newVersion, client, settings).isFailure)
                assertNull(reopened.getCachedAttachmentFile(newVersion))
                assertEquals("abc", reopened.getCachedAttachmentFile(first)!!.readText())
                assertTrue(reopened.getOrDownloadAttachment(first, client, settings, forceRefresh = true).isFailure)
                assertEquals("abc", reopened.getCachedAttachmentFile(first)!!.readText())
                reopened.clearOfflineDatabase()
                assertNull(reopened.getCachedAttachmentFile(first))
            }
        } finally { dir.deleteRecursively() }
    }

    @Test fun blocksOtherOriginsAndOversizedFiles() = runBlocking {
        val dir = Files.createTempDirectory("wiki-attachment-limits").toFile()
        try {
            val store = ConfluenceOfflineStore(dir)
            val settings = AppSettings(confluenceUrl = "http://127.0.0.1:12345", confluenceToken = "secret")
            val att = ConfluenceAttachment("1", "a.pdf", "application/pdf", 1, "http://example.com/a", 1)
            assertTrue(store.getOrDownloadAttachment(att, ConfluenceWikiClient(), settings).isFailure)
            assertTrue(store.getOrDownloadAttachment(att.copy(fileSizeBytes = 513L * 1024 * 1024), ConfluenceWikiClient(), settings).isFailure)
            assertFalse(store.isSafeAttachmentToOpen(att.copy(title = "run.cmd")))
        } finally { dir.deleteRecursively() }
    }
}
