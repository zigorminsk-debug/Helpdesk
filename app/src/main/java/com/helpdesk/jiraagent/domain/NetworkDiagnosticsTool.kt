package com.helpdesk.jiraagent.domain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.security.cert.X509Certificate
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.system.measureTimeMillis

data class TcpPortStatus(
    val port: Int,
    val serviceName: String,
    val isOpen: Boolean,
    val latencyMs: Long? = null
)

data class SslCertificateReport(
    val subjectCn: String,
    val issuerCn: String,
    val expiresFormatted: String,
    val daysRemaining: Long,
    val isExpired: Boolean
)

data class HttpProbeReport(
    val scheme: String,
    val statusCode: Int,
    val serverHeader: String?,
    val latencyMs: Long
)

data class DnsRecordSet(
    val aRecords: List<String> = emptyList(),
    val mxRecords: List<String> = emptyList(),
    val txtSpfRecords: List<String> = emptyList(),
    val nsRecords: List<String> = emptyList()
) {
    val hasExtendedRecords: Boolean
        get() = mxRecords.isNotEmpty() || txtSpfRecords.isNotEmpty() || nsRecords.isNotEmpty()
}

data class HostDiagnosticReport(
    val targetHost: String,
    val resolvedIp: String?,
    val icmpReachable: Boolean,
    val icmpLatencyMs: Long?,
    val portStatuses: List<TcpPortStatus>,
    val sslCertificate: SslCertificateReport? = null,
    val httpProbe: HttpProbeReport? = null,
    val dnsRecords: DnsRecordSet = DnsRecordSet(),
    val errorMessage: String? = null
) {
    val isHostAlive: Boolean
        get() = icmpReachable || portStatuses.any { it.isOpen } || httpProbe != null
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

    val WATCHLIST_PORTS = listOf(
        80 to "HTTP",
        443 to "HTTPS",
        22 to "SSH",
        3389 to "RDP",
        8291 to "WinBox (MikroTik)",
        8080 to "HTTP-Alt",
        8443 to "HTTPS-Alt",
        445 to "SMB",
        21 to "FTP",
        25 to "SMTP",
        587 to "SMTP-TLS",
        993 to "IMAPS",
        53 to "DNS",
        1194 to "OpenVPN",
        1723 to "PPTP VPN",
        1433 to "MSSQL (1С)",
        5432 to "PostgreSQL (1С)",
        5060 to "SIP (АТС)",
        8006 to "Proxmox"
    )

    private val FAST_PRIMARY_PORT_NUMBERS = setOf(443, 80, 22, 8291, 3389, 1194)

    private val DATE_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy").withZone(ZoneId.of("Europe/Minsk"))

    suspend fun runDiagnostics(
        rawHost: String,
        timeoutMs: Int = 2000,
        portsToScan: List<Pair<Int, String>> = DEFAULT_PORTS,
        includeDnsRecords: Boolean = true,
        includeHttpProbe: Boolean = true,
        fastBackgroundMode: Boolean = false,
        cachedSslProvider: ((String) -> SslCertificateReport?)? = null,
        onSslFetched: ((String, SslCertificateReport) -> Unit)? = null
    ): HostDiagnosticReport = withContext(Dispatchers.IO) {
        val withoutScheme = rawHost.trim()
            .replace(Regex("^https?://", RegexOption.IGNORE_CASE), "")
            .substringBefore('/')
            .trim()
        val customPort = withoutScheme
            .substringAfter(':', "")
            .toIntOrNull()
            ?.takeIf { it in 1..65535 }
        val cleanHost = withoutScheme
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

        val allAddresses = runCatching { InetAddress.getAllByName(cleanHost) }.getOrNull()
        val resolvedAddress = allAddresses?.firstOrNull()
        val isIpLiteral = cleanHost.all { it.isDigit() || it == '.' }
        val localAs = allAddresses?.mapNotNull { it.hostAddress }?.distinct()?.take(4).orEmpty()

        if (resolvedAddress == null) {
            return@withContext HostDiagnosticReport(
                targetHost = cleanHost,
                resolvedIp = null,
                icmpReachable = false,
                icmpLatencyMs = null,
                portStatuses = emptyList(),
                errorMessage = "Системный резолвер не разрешил адрес для $cleanHost"
            )
        }

        val resolvedIp = resolvedAddress.hostAddress

        coroutineScope {
            val dnsDeferred = async {
                when {
                    !includeDnsRecords -> DnsRecordSet(aRecords = if (isIpLiteral) listOf(cleanHost) else localAs)
                    isIpLiteral -> DnsRecordSet(aRecords = listOf(cleanHost))
                    else -> DnsRecordSet(aRecords = localAs)
                }
            }

            val icmpDeferred = async {
                var ok = false
                val rtt = measureTimeMillis {
                    ok = runCatching { resolvedAddress.isReachable(timeoutMs) }.getOrDefault(false)
                }
                ok to (if (ok) rtt else null)
            }

            val effectivePorts = if (customPort != null && portsToScan.none { it.first == customPort }) {
                listOf(customPort to "Порт $customPort") + portsToScan
            } else {
                portsToScan
            }

            suspend fun probePortList(portEntries: List<Pair<Int, String>>): List<TcpPortStatus> = coroutineScope {
                portEntries.map { (port, label) ->
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

            val ports: List<TcpPortStatus>
            val (icmpOk, icmpRtt) = if (fastBackgroundMode) {
                val primaryList = effectivePorts.filter { (p, _) ->
                    p == customPort || p in FAST_PRIMARY_PORT_NUMBERS
                }
                val secondaryList = effectivePorts.filterNot { (p, _) ->
                    p == customPort || p in FAST_PRIMARY_PORT_NUMBERS
                }
                val primaryResults = probePortList(primaryList)
                val icmpPair = icmpDeferred.await()
                if (icmpPair.first || primaryResults.any { it.isOpen } || secondaryList.isEmpty()) {
                    ports = primaryResults
                } else {
                    ports = primaryResults + probePortList(secondaryList)
                }
                icmpPair
            } else {
                ports = probePortList(effectivePorts)
                icmpDeferred.await()
            }

            val port443Open = ports.any { it.port == 443 && it.isOpen }
            val port80Open = ports.any { it.port == 80 && it.isOpen }

            val sslDeferred = async {
                if (!port443Open) {
                    null
                } else {
                    val cached = cachedSslProvider?.invoke(cleanHost)
                    if (cached != null) {
                        cached
                    } else {
                        inspectSslCertificate(cleanHost, timeoutMs + 700)?.also { fresh ->
                            onSslFetched?.invoke(cleanHost, fresh)
                        }
                    }
                }
            }
            val httpDeferred = async {
                if (!includeHttpProbe) {
                    null
                } else {
                    when {
                        port443Open -> probeHttp("https", cleanHost, timeoutMs + 700)
                            ?: if (port80Open) probeHttp("http", cleanHost, timeoutMs + 500) else null
                        port80Open -> probeHttp("http", cleanHost, timeoutMs + 500)
                        else -> null
                    }
                }
            }

            HostDiagnosticReport(
                targetHost = cleanHost,
                resolvedIp = resolvedIp,
                icmpReachable = icmpOk,
                icmpLatencyMs = icmpRtt,
                portStatuses = ports,
                sslCertificate = sslDeferred.await(),
                httpProbe = httpDeferred.await(),
                dnsRecords = runCatching { dnsDeferred.await() }.getOrDefault(DnsRecordSet()),
                errorMessage = null
            )
        }
    }

    private fun inspectSslCertificate(host: String, timeoutMs: Int): SslCertificateReport? {
        return runCatching {
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            (factory.createSocket() as SSLSocket).use { sslSocket ->
                sslSocket.soTimeout = timeoutMs
                sslSocket.connect(InetSocketAddress(host, 443), timeoutMs)
                sslSocket.startHandshake()
                val cert = sslSocket.session.peerCertificates.firstOrNull() as? X509Certificate
                    ?: return null
                val notAfterInstant = cert.notAfter.toInstant()
                val now = Instant.now()
                val daysLeft = ChronoUnit.DAYS.between(now, notAfterInstant)
                SslCertificateReport(
                    subjectCn = extractCn(cert.subjectX500Principal.name).ifBlank { host },
                    issuerCn = extractCn(cert.issuerX500Principal.name).ifBlank { "CA" },
                    expiresFormatted = DATE_FMT.format(notAfterInstant),
                    daysRemaining = daysLeft,
                    isExpired = now.isAfter(notAfterInstant)
                )
            }
        }.getOrNull()
    }

    private fun probeHttp(scheme: String, host: String, timeoutMs: Int): HttpProbeReport? {
        return runCatching {
            var code = 0
            var serverHdr: String? = null
            val rtt = measureTimeMillis {
                val conn = (URL("$scheme://$host/").openConnection() as HttpURLConnection).apply {
                    requestMethod = "HEAD"
                    connectTimeout = timeoutMs
                    readTimeout = timeoutMs
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", "Help-CSL-SysadminProbe/1.0")
                }
                if (conn is HttpsURLConnection) {
                    // Standard verification; if self-signed fails, SSL cert check above still inspected or HTTP fallback reports
                }
                try {
                    code = conn.responseCode
                    serverHdr = conn.getHeaderField("Server")?.take(32)
                } finally {
                    conn.disconnect()
                }
            }
            if (code > 0) {
                HttpProbeReport(
                    scheme = scheme.uppercase(),
                    statusCode = code,
                    serverHeader = serverHdr,
                    latencyMs = rtt
                )
            } else null
        }.getOrNull()
    }

    private fun extractCn(dn: String): String {
        val match = Regex("""(?i)(?:^|,)\s*CN=([^,]+)""").find(dn)
        return match?.groupValues?.getOrNull(1)?.trim().orEmpty()
    }
}
