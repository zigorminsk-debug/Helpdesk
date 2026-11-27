package com.helpdesk.jiraagent

import com.helpdesk.jiraagent.domain.NetworkDiagnosticsTool
import com.helpdesk.jiraagent.domain.ProbeStageState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.security.KeyStore
import java.security.SecureRandom
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import kotlin.concurrent.thread

class NetworkProbeStagesTest {
    @Test
    fun stagesExplicitlyMarkChecksSkippedWhenNoPortsAreRequested() = runBlocking {
        val report = NetworkDiagnosticsTool.runDiagnostics("127.0.0.1", timeoutMs = 200, portsToScan = emptyList(), includeHttpProbe = false)
        val stages = report.stages!!
        assertEquals(ProbeStageState.NOT_CHECKED, stages.dns.state)
        assertEquals(ProbeStageState.NOT_CHECKED, stages.tcp.state)
        assertEquals(ProbeStageState.NOT_CHECKED, stages.tls.state)
        assertEquals(ProbeStageState.NOT_CHECKED, stages.http.state)
        assertEquals(ProbeStageState.NOT_CHECKED, stages.https.state)
        assertEquals(5, stages.lines().size)
        assertTrue(stages.http.detail.contains("отключены"))
    }

    @Test
    fun emptyHostLeavesAllNetworkChecksUnattempted() = runBlocking {
        val report = NetworkDiagnosticsTool.runDiagnostics("", portsToScan = emptyList())
        val stages = report.stages!!
        assertEquals(ProbeStageState.NOT_CHECKED, stages.dns.state)
        assertEquals(ProbeStageState.NOT_CHECKED, stages.tcp.state)
        assertEquals(ProbeStageState.NOT_CHECKED, stages.tls.state)
        assertEquals(ProbeStageState.NOT_CHECKED, stages.http.state)
        assertEquals(ProbeStageState.NOT_CHECKED, stages.https.state)
    }

    @Test
    fun tcpReportsActualCheckedPortWithoutCallingItServiceHealth() = runBlocking {
        ServerSocket(0, 5, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 5000
            val worker = thread(isDaemon = true) { server.accept().use { } }
            val report = NetworkDiagnosticsTool.runDiagnostics("127.0.0.1", timeoutMs = 500, portsToScan = listOf(server.localPort to "test"), includeHttpProbe = false)
            worker.join(5000)
            assertTrue(report.portStatuses.single().isOpen)
            assertTrue(report.stages!!.tcp.detail.contains("1 из 1"))
            assertEquals(ProbeStageState.NOT_CHECKED, report.stages!!.tls.state)
        }
    }

    @Test
    fun httpErrorCodeIsAnHttpResponseNotHealthyApplication() {
        ServerSocket(0, 5, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 5000
            val worker = thread(isDaemon = true) {
                server.accept().use { client ->
                    val input = client.getInputStream().bufferedReader()
                    while (!input.readLine().isNullOrEmpty()) { }
                    client.getOutputStream().write("HTTP/1.1 500 Internal Server Error\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    client.getOutputStream().flush()
                }
            }
            val (report, stage) = NetworkDiagnosticsTool.probeHttp("http", "127.0.0.1", server.localPort, 3000)
            worker.join(5000)
            assertEquals(500, report!!.statusCode)
            assertEquals(ProbeStageState.SUCCESS, stage.state)
            assertTrue(stage.detail.contains("не означает исправность"))
        }
    }

    @Test
    fun tlsRequiresBothTrustAndMatchingHostname() {
        // Test-only keypair; not used by the application. One SAN: localhost.
        val password = "testonly".toCharArray()
        val store = KeyStore.getInstance("PKCS12")
        javaClass.getResourceAsStream("/fixtures/network-test-only.p12")!!.use { store.load(it, password) }
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, password) }
        val serverContext = SSLContext.getInstance("TLS").apply { init(keys.keyManagers, null, SecureRandom()) }
        val trustStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry("test", store.getCertificate("test")) }
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trustStore) }
        val trustedContext = SSLContext.getInstance("TLS").apply { init(null, trust.trustManagers, SecureRandom()) }
        fun probe(host: String, trusted: Boolean): ProbeStageState {
            val server = serverContext.serverSocketFactory.createServerSocket(0, 5, InetAddress.getByName("127.0.0.1")) as SSLServerSocket
            server.use {
                server.soTimeout = 5000
                val worker = thread(isDaemon = true) {
                    runCatching { (server.accept() as SSLSocket).use { it.soTimeout = 5000; it.startHandshake() } }
                }
                val factory = if (trusted) trustedContext.socketFactory else SSLContext.getDefault().socketFactory
                val result = NetworkDiagnosticsTool.probeTls(host, InetAddress.getByName("127.0.0.1"), server.localPort, 3000, factory)
                worker.join(5000)
                return result.second.state
            }
        }
        assertEquals(ProbeStageState.SUCCESS, probe("localhost", true))
        assertEquals(ProbeStageState.FAILED, probe("wrong.example", true))
        assertEquals(ProbeStageState.FAILED, probe("localhost", false))
    }
}
