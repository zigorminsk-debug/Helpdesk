package com.helpdesk.jiraagent.domain

import com.google.gson.JsonParser
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
import java.net.URLEncoder
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

    private val DATE_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy").withZone(ZoneId.of("Europe/Minsk"))

    suspend fun runDiagnostics(
        rawHost: String,
        timeoutMs: Int = 2000
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

        val allAddresses = runCatching { InetAddress.getAllByName(cleanHost) }.getOrNull()
        val resolvedAddress = allAddresses?.firstOrNull()
        val isIpLiteral = cleanHost.all { it.isDigit() || it == '.' }

        // Even if local DNS didn't resolve A record (e.g. mail-only domain), still try DoH MX/NS!
        val dnsDeferred = coroutineScope {
            async {
                if (isIpLiteral) {
                    DnsRecordSet(aRecords = listOf(cleanHost))
                } else {
                    resolveDnsRecords(cleanHost, allAddresses?.mapNotNull { it.hostAddress }.orEmpty())
                }
            }
        }

        if (resolvedAddress == null) {
            val dnsOnly = runCatching { dnsDeferred.await() }.getOrDefault(DnsRecordSet())
            return@withContext HostDiagnosticReport(
                targetHost = cleanHost,
                resolvedIp = null,
                icmpReachable = false,
                icmpLatencyMs = null,
                portStatuses = emptyList(),
                dnsRecords = dnsOnly,
                errorMessage = "DNS не разрешил A-запись для $cleanHost"
            )
        }

        val resolvedIp = resolvedAddress.hostAddress

        coroutineScope {
            val icmpDeferred = async {
                var ok = false
                val rtt = measureTimeMillis {
                    ok = runCatching { resolvedAddress.isReachable(timeoutMs) }.getOrDefault(false)
                }
                ok to (if (ok) rtt else null)
            }

            val portsDeferred = DEFAULT_PORTS.map { (port, label) ->
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
            }

            val ports = portsDeferred.awaitAll()
            val port443Open = ports.any { it.port == 443 && it.isOpen }
            val port80Open = ports.any { it.port == 80 && it.isOpen }

            val sslDeferred = async {
                if (port443Open) inspectSslCertificate(cleanHost, timeoutMs + 700) else null
            }
            val httpDeferred = async {
                when {
                    port443Open -> probeHttp("https", cleanHost, timeoutMs + 700)
                        ?: if (port80Open) probeHttp("http", cleanHost, timeoutMs + 500) else null
                    port80Open -> probeHttp("http", cleanHost, timeoutMs + 500)
                    else -> null
                }
            }

            val (icmpOk, icmpRtt) = icmpDeferred.await()
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

    private suspend fun resolveDnsRecords(domain: String, localARecords: List<String>): DnsRecordSet = coroutineScope {
        val mxJob = async { queryDohRecords(domain, "MX") }
        val txtJob = async { queryDohRecords(domain, "TXT") }
        val nsJob = async { queryDohRecords(domain, "NS") }
        val txtAll = txtJob.await()
        val spfAndDmarc = txtAll
            .map { it.trim('"') }
            .filter {
                it.contains("v=spf1", ignoreCase = true) ||
                    it.contains("v=DMARC", ignoreCase = true) ||
                    txtAll.size <= 3
            }
            .take(4)

        DnsRecordSet(
            aRecords = localARecords.distinct().take(4),
            mxRecords = mxJob.await().take(5),
            txtSpfRecords = spfAndDmarc,
            nsRecords = nsJob.await().take(4)
        )
    }

    private fun queryDohRecords(domain: String, type: String): List<String> {
        val encoded = URLEncoder.encode(domain, "UTF-8")
        val endpoints = listOf(
            "https://cloudflare-dns.com/dns-query?name=$encoded&type=$type",
            "https://dns.google/resolve?name=$encoded&type=$type"
        )
        for (endpoint in endpoints) {
            val result = runCatching {
                val conn = (URL(endpoint).openConnection() as HttpsURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 1600
                    readTimeout = 1600
                    setRequestProperty("Accept", "application/dns-json")
                }
                try {
                    if (conn.responseCode != 200) return@runCatching emptyList()
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val root = JsonParser.parseString(body).asJsonObject
                    val answers = root.getAsJsonArray("Answer") ?: return@runCatching emptyList()
                    buildList {
                        for (el in answers) {
                            val obj = el.asJsonObject
                            val data = obj.get("data")?.asString?.trim()?.trimEnd('.') ?: continue
                            if (data.isNotBlank()) add(data)
                        }
                    }
                } finally {
                    conn.disconnect()
                }
            }.getOrNull()
            if (!result.isNullOrEmpty()) return result.distinct()
        }
        return emptyList()
    }

    private fun extractCn(dn: String): String {
        val match = Regex("""(?i)(?:^|,)\s*CN=([^,]+)""").find(dn)
        return match?.groupValues?.getOrNull(1)?.trim().orEmpty()
    }
}
