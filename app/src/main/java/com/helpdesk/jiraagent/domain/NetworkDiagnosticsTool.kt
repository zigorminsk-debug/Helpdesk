package com.helpdesk.jiraagent.domain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.security.cert.X509Certificate
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.system.measureTimeMillis

data class TcpPortStatus(
    val port: Int,
    val serviceName: String,
    val isOpen: Boolean,
    val latencyMs: Long? = null,
    val failureDescription: String? = null
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

enum class ProbeStageState { SUCCESS, FAILED, NOT_CHECKED, CACHED }

data class ProbeStage(val state: ProbeStageState, val detail: String) {
    fun display(): String = when (state) {
        ProbeStageState.SUCCESS -> "Получен результат: $detail"
        ProbeStageState.FAILED -> "Ошибка: $detail"
        ProbeStageState.NOT_CHECKED -> "Не проверено: $detail"
        ProbeStageState.CACHED -> "Данные из кэша: $detail"
    }
}

data class HostProbeStages(
    val dns: ProbeStage,
    val tcp: ProbeStage,
    val tls: ProbeStage,
    val http: ProbeStage,
    val https: ProbeStage
) {
    fun lines(): List<String> = listOf(
        "DNS: ${dns.display()}", "TCP: ${tcp.display()}", "TLS 443: ${tls.display()}",
        "HTTP 80: ${http.display()}", "HTTPS 443: ${https.display()}"
    )
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
    val errorMessage: String? = null,
    val stages: HostProbeStages? = null
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
                errorMessage = "Пустой адрес хоста",
                stages = skippedStages(ProbeStage(ProbeStageState.NOT_CHECKED, "пустой адрес"), "пустой адрес")
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
                errorMessage = "Системный резолвер не разрешил адрес для $cleanHost",
                stages = skippedStages(ProbeStage(ProbeStageState.FAILED, "системный резолвер не разрешил имя"), "адрес не разрешён")
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
                        var failure: Throwable? = null
                        val rtt = measureTimeMillis {
                            try {
                                Socket().use { socket -> socket.connect(InetSocketAddress(resolvedAddress, port), timeoutMs) }
                            } catch (e: Exception) { failure = e }
                        }
                        TcpPortStatus(
                            port = port,
                            serviceName = label,
                            isOpen = failure == null,
                            latencyMs = if (failure == null) rtt else null,
                            failureDescription = failure?.let { describeNetworkFailure(it) }
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

            val skipped443 = if (ports.any { it.port == 443 }) "TCP 443 не установлено" else "TCP 443 не проверялся"
            val skipped80 = if (ports.any { it.port == 80 }) "TCP 80 не установлено" else "TCP 80 не проверялся"
            val sslDeferred = async {
                if (!port443Open) {
                    null to ProbeStage(ProbeStageState.NOT_CHECKED, skipped443)
                } else {
                    val cached = cachedSslProvider?.invoke(cleanHost)
                    if (cached != null) {
                        cached to ProbeStage(ProbeStageState.CACHED, "сертификат до ${cached.expiresFormatted}; текущее рукопожатие не проверено")
                    } else {
                        probeTls(cleanHost, resolvedAddress, 443, timeoutMs + 700).also { (cert, _) ->
                            if (cert != null) onSslFetched?.invoke(cleanHost, cert)
                        }
                    }
                }
            }
            val httpsDeferred = async {
                when {
                    !includeHttpProbe -> null to ProbeStage(ProbeStageState.NOT_CHECKED, "HTTP-проверки отключены")
                    !port443Open -> null to ProbeStage(ProbeStageState.NOT_CHECKED, skipped443)
                    else -> probeHttp("https", cleanHost, 443, timeoutMs + 700)
                }
            }
            val httpDeferred = async {
                when {
                    !includeHttpProbe -> null to ProbeStage(ProbeStageState.NOT_CHECKED, "HTTP-проверки отключены")
                    !port80Open -> null to ProbeStage(ProbeStageState.NOT_CHECKED, skipped80)
                    else -> probeHttp("http", cleanHost, 80, timeoutMs + 500)
                }
            }
            val (ssl, tlsStage) = sslDeferred.await()
            val (http, httpStage) = httpDeferred.await()
            val (https, httpsStage) = httpsDeferred.await()
            val dnsStage = if (isIpLiteral) {
                ProbeStage(ProbeStageState.NOT_CHECKED, "введён IP-адрес, разрешение имени не требовалось")
            } else {
                ProbeStage(ProbeStageState.SUCCESS, "системный резолвер: ${localAs.joinToString(", ")}; MX/TXT/NS не проверялись")
            }
            val tcpStage = if (ports.isEmpty()) {
                ProbeStage(ProbeStageState.NOT_CHECKED, "порты не запрашивались")
            } else {
                val partial = if (ports.size < effectivePorts.size) "; фоновая проверка пропустила ${effectivePorts.size - ports.size} портов" else ""
                ProbeStage(ProbeStageState.SUCCESS, "соединение: ${ports.count { it.isOpen }} из ${ports.size} проверенных$partial")
            }
            HostDiagnosticReport(
                targetHost = cleanHost,
                resolvedIp = resolvedIp,
                icmpReachable = icmpOk,
                icmpLatencyMs = icmpRtt,
                portStatuses = ports,
                sslCertificate = ssl,
                httpProbe = https ?: http,
                dnsRecords = runCatching { dnsDeferred.await() }.getOrDefault(DnsRecordSet()),
                errorMessage = null,
                stages = HostProbeStages(dnsStage, tcpStage, tlsStage, httpStage, httpsStage)
            )
        }
    }

    private fun skippedStages(dns: ProbeStage, reason: String): HostProbeStages {
        val skipped = ProbeStage(ProbeStageState.NOT_CHECKED, reason)
        return HostProbeStages(dns, skipped, skipped, skipped, skipped)
    }

    private fun describeNetworkFailure(error: Throwable): String = when (error) {
        is SocketTimeoutException -> "тайм-аут"
        is NoRouteToHostException -> "нет маршрута или узел недоступен из этой сети"
        is ConnectException -> "соединение не установлено (отказ или ошибка сети)"
        is SSLException -> "ошибка TLS, сертификата или проверки имени"
        else -> "сетевая ошибка (${error.javaClass.simpleName})"
    }

    /** Trust validation and HTTPS identity checking are never bypassed. Host is kept for SNI. */
    internal fun probeTls(host: String, address: InetAddress, port: Int, timeoutMs: Int,
        factory: SSLSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
    ): Pair<SslCertificateReport?, ProbeStage> {
        return try {
            val certificate = Socket().use { tcp ->
                tcp.connect(InetSocketAddress(address, port), timeoutMs)
                (factory.createSocket(tcp, host, port, true) as SSLSocket).use { ssl ->
                    ssl.soTimeout = timeoutMs
                    ssl.sslParameters = ssl.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                    ssl.startHandshake()
                    val cert = ssl.session.peerCertificates.firstOrNull() as? X509Certificate
                        ?: error("No peer certificate")
                    val expiration = cert.notAfter.toInstant()
                    val now = Instant.now()
                    SslCertificateReport(
                        subjectCn = extractCn(cert.subjectX500Principal.name).ifBlank { host },
                        issuerCn = extractCn(cert.issuerX500Principal.name).ifBlank { "CA" },
                        expiresFormatted = DATE_FMT.format(expiration),
                        daysRemaining = ChronoUnit.DAYS.between(now, expiration),
                        isExpired = now.isAfter(expiration)
                    )
                }
            }
            certificate to ProbeStage(ProbeStageState.SUCCESS, "рукопожатие, доверие и имя проверены; сертификат до ${certificate.expiresFormatted}")
        } catch (e: Exception) {
            null to ProbeStage(ProbeStageState.FAILED, "${describeNetworkFailure(e)}; сертификат не оценён")
        }
    }

    internal fun probeHttp(scheme: String, host: String, port: Int, timeoutMs: Int): Pair<HttpProbeReport?, ProbeStage> {
        return try {
            var code = 0
            var serverHdr: String? = null
            val rtt = measureTimeMillis {
                val conn = (URL(scheme, host, port, "/").openConnection() as HttpURLConnection).apply {
                    requestMethod = "HEAD"
                    connectTimeout = timeoutMs
                    readTimeout = timeoutMs
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", "Help-CSL-SysadminProbe/1.0")
                }
                try {
                    code = conn.responseCode
                    serverHdr = conn.getHeaderField("Server")?.take(32)
                } finally { conn.disconnect() }
            }
            check(code > 0) { "No HTTP response" }
            val report = HttpProbeReport(scheme.uppercase(), code, serverHdr, rtt)
            report to ProbeStage(ProbeStageState.SUCCESS, "HEAD: код $code, $rtt мс; ответ не означает исправность приложения")
        } catch (e: Exception) {
            null to ProbeStage(ProbeStageState.FAILED, "HEAD: ${describeNetworkFailure(e)}")
        }
    }

    private fun extractCn(dn: String): String {
        val match = Regex("""(?i)(?:^|,)\s*CN=([^,]+)""").find(dn)
        return match?.groupValues?.getOrNull(1)?.trim().orEmpty()
    }
}
