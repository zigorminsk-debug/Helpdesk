package com.helpdesk.jiraagent.domain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.system.measureTimeMillis

data class TcpPortStatus(
    val port: Int,
    val serviceName: String,
    val isOpen: Boolean,
    val latencyMs: Long? = null
)

data class HostDiagnosticReport(
    val targetHost: String,
    val resolvedIp: String?,
    val icmpReachable: Boolean,
    val icmpLatencyMs: Long?,
    val portStatuses: List<TcpPortStatus>,
    val errorMessage: String? = null
) {
    val isHostAlive: Boolean
        get() = icmpReachable || portStatuses.any { it.isOpen }
}

object NetworkDiagnosticsTool {

    val DEFAULT_PORTS = listOf(
        80 to "HTTP",
        443 to "HTTPS",
        3389 to "RDP",
        22 to "SSH",
        8291 to "WinBox",
        445 to "SMB"
    )

    suspend fun runDiagnostics(
        rawHost: String,
        timeoutMs: Int = 1800
    ): HostDiagnosticReport = withContext(Dispatchers.IO) {
        val cleanHost = rawHost.trim()
            .removePrefix("https://")
            .removePrefix("http://")
            .substringBefore('/')
            .substringBefore(':')
            .trim()

        if (cleanHost.isEmpty()) {
            return@withContext HostDiagnosticReport(
                targetHost = rawHost,
                resolvedIp = null,
                icmpReachable = false,
                icmpLatencyMs = null,
                portStatuses = emptyList(),
                errorMessage = "Пустой адрес хоста"
            )
        }

        val resolvedAddress = runCatching { InetAddress.getByName(cleanHost) }.getOrNull()
        if (resolvedAddress == null) {
            return@withContext HostDiagnosticReport(
                targetHost = cleanHost,
                resolvedIp = null,
                icmpReachable = false,
                icmpLatencyMs = null,
                portStatuses = emptyList(),
                errorMessage = "DNS не смог разрешить имя $cleanHost"
            )
        }

        val resolvedIp = resolvedAddress.hostAddress

        var icmpOk = false
        val icmpRtt = measureTimeMillis {
            icmpOk = runCatching { resolvedAddress.isReachable(timeoutMs) }.getOrDefault(false)
        }

        val ports = coroutineScope {
            DEFAULT_PORTS.map { (port, label) ->
                async {
                    var open = false
                    val rtt = measureTimeMillis {
                        open = runCatching {
                            Socket().use { socket ->
                                socket.connect(InetSocketAddress(resolvedAddress, port), timeoutMs)
                                true
                            }
                        }.getOrDefault(false)
                    }
                    TcpPortStatus(
                        port = port,
                        serviceName = label,
                        isOpen = open,
                        latencyMs = if (open) rtt else null
                    )
                }
            }.awaitAll()
        }

        HostDiagnosticReport(
            targetHost = cleanHost,
            resolvedIp = resolvedIp,
            icmpReachable = icmpOk,
            icmpLatencyMs = if (icmpOk) icmpRtt else null,
            portStatuses = ports,
            errorMessage = null
        )
    }
}
