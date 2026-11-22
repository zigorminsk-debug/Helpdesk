package com.helpdesk.jiraagent.domain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.security.SecureRandom
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

data class WakeOnLanResult(
    val normalizedMac: String,
    val targetAddress: String,
    val port: Int,
    val message: String
)

data class SubnetCalculationResult(
    val ipAddress: String,
    val prefixLength: Int,
    val cidrNotation: String,
    val subnetMask: String,
    val wildcardMask: String,
    val networkAddress: String,
    val broadcastAddress: String,
    val firstUsableHost: String,
    val lastUsableHost: String,
    val usableHostsCount: Long,
    val scopeDescription: String
) {
    val cidrPrefix: Int get() = prefixLength
    val usableHostCount: Long get() = usableHostsCount
}

enum class WatchlistHealthLevel {
    OK,
    WARNING,
    CRITICAL
}

data class WatchlistHostStatus(
    val host: String,
    val resolvedIp: String?,
    val isAlive: Boolean,
    val latencyMs: Long?,
    val openPorts: List<Int> = emptyList(),
    val portStatuses: List<TcpPortStatus> = emptyList(),
    val openPortsSummary: String,
    val sslSubject: String?,
    val sslIssuer: String? = null,
    val sslExpiresFormatted: String?,
    val sslDaysRemaining: Long?,
    val httpServerSummary: String? = null,
    val healthLevel: WatchlistHealthLevel,
    val statusSummary: String,
    val checkedAtDisplay: String
) {
    val isOnline: Boolean get() = isAlive
    val isSslExpiringSoon: Boolean get() = sslDaysRemaining != null && sslDaysRemaining <= 14L
}

object SysadminToolboxEngine {

    val DEFAULT_WATCHLIST_HOSTS = listOf(
        "csl.by",
        "habr.com"
    )

    fun normalizeHostKey(rawHost: String): String {
        return rawHost.trim()
            .lowercase(Locale.ROOT)
            .removePrefix("https://")
            .removePrefix("http://")
            .substringBefore('/')
            .trim()
    }

    private val secureRandom = SecureRandom()

    // ==================== 1. WAKE-ON-LAN (MAGIC PACKET) ====================

    fun parseMacBytes(rawMac: String): ByteArray? {
        val hexOnly = rawMac.trim().filter { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
        if (hexOnly.length != 12) return null
        return ByteArray(6) { idx ->
            hexOnly.substring(idx * 2, idx * 2 + 2).toInt(16).toByte()
        }
    }

    fun formatMacColon(rawMac: String): String? {
        val bytes = parseMacBytes(rawMac) ?: return null
        return bytes.joinToString(":") { b -> String.format(Locale.US, "%02X", b.toInt() and 0xFF) }
    }

    fun buildMagicPacket(rawMac: String): ByteArray? {
        val macBytes = parseMacBytes(rawMac) ?: return null
        val packet = ByteArray(6 + 16 * 6)
        for (i in 0 until 6) {
            packet[i] = 0xFF.toByte()
        }
        for (rep in 0 until 16) {
            System.arraycopy(macBytes, 0, packet, 6 + rep * 6, 6)
        }
        return packet
    }

    suspend fun sendWakeOnLan(
        rawMac: String,
        broadcastAddress: String = "255.255.255.255",
        port: Int = 9
    ): Result<WakeOnLanResult> = withContext(Dispatchers.IO) {
        runCatching {
            val packetBytes = buildMagicPacket(rawMac)
                ?: throw IllegalArgumentException("Некорректный MAC-адрес (ожидается 12 HEX-символов, напр. AA:BB:CC:DD:EE:FF)")
            val normalizedMac = formatMacColon(rawMac) ?: rawMac
            val targetAddr = InetAddress.getByName(broadcastAddress.trim().ifBlank { "255.255.255.255" })
            val primaryPort = port.coerceIn(1, 65535)

            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.soTimeout = 2000
                for (p in setOf(primaryPort, 9, 7)) {
                    val datagram = DatagramPacket(packetBytes, packetBytes.size, targetAddr, p)
                    socket.send(datagram)
                }
            }
            val msg = "Magic Packet (102 байта) отправлен на $normalizedMac через ${targetAddr.hostAddress}:$primaryPort/UDP"
            WakeOnLanResult(
                normalizedMac = normalizedMac,
                targetAddress = targetAddr.hostAddress ?: broadcastAddress,
                port = primaryPort,
                message = msg
            )
        }
    }

    // ==================== 2. IPv4 / CIDR SUBNET CALCULATOR ====================

    fun calculateSubnet(rawInput: String): Result<SubnetCalculationResult> {
        val cleaned = rawInput.trim()
        if (cleaned.isEmpty()) {
            return Result.failure(IllegalArgumentException("Введите IPv4-адрес (напр. 192.168.1.10/24)"))
        }

        val ipPart: String
        val prefix: Int

        if ('/' in cleaned) {
            val parts = cleaned.split('/', limit = 2)
            ipPart = parts[0].trim()
            prefix = parts[1].trim().toIntOrNull()?.takeIf { it in 0..32 }
                ?: return Result.failure(IllegalArgumentException("Некорректный префикс CIDR (допустимо /0..32)"))
        } else if (' ' in cleaned) {
            val parts = cleaned.split(Regex("\\s+"), limit = 2)
            ipPart = parts[0].trim()
            prefix = maskToPrefixLength(parts[1].trim())
                ?: return Result.failure(IllegalArgumentException("Некорректная маска подсети (напр. 255.255.255.0)"))
        } else {
            ipPart = cleaned
            prefix = 24
        }

        val ipLong = parseIpv4ToUInt(ipPart)
            ?: return Result.failure(IllegalArgumentException("Некорректный IPv4-адрес"))
        val maskLong = if (prefix == 0) 0L else ((0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL)
        val wildcardLong = maskLong.inv() and 0xFFFFFFFFL
        val networkLong = ipLong and maskLong
        val broadcastLong = (networkLong or wildcardLong) and 0xFFFFFFFFL

        val firstHostLong: Long
        val lastHostLong: Long
        val usableHosts: Long

        when (prefix) {
            32 -> {
                firstHostLong = ipLong
                lastHostLong = ipLong
                usableHosts = 1L
            }
            31 -> {
                firstHostLong = networkLong
                lastHostLong = broadcastLong
                usableHosts = 2L
            }
            else -> {
                firstHostLong = (networkLong + 1L) and 0xFFFFFFFFL
                lastHostLong = (broadcastLong - 1L) and 0xFFFFFFFFL
                usableHosts = ((1L shl (32 - prefix)) - 2L).coerceAtLeast(0L)
            }
        }

        val firstOctet = ((ipLong ushr 24) and 0xFFL).toInt()
        val secondOctet = ((ipLong ushr 16) and 0xFFL).toInt()
        val scope = when {
            firstOctet == 10 -> "Частная сеть (RFC 1918 · Класс A)"
            firstOctet == 172 && secondOctet in 16..31 -> "Частная сеть (RFC 1918 · Класс B)"
            firstOctet == 192 && secondOctet == 168 -> "Частная локальная сеть (RFC 1918 · Класс C)"
            firstOctet == 100 && secondOctet in 64..127 -> "Сеть оператора CGNAT (RFC 6598)"
            firstOctet == 127 -> "Loopback-интерфейс (127.0.0.0/8)"
            firstOctet == 169 && secondOctet == 254 -> "Link-Local APIPA (169.254.0.0/16)"
            else -> "Публичный маршрутизируемый IPv4"
        }

        val cleanIp = formatUIntToIpv4(ipLong)
        val netStr = formatUIntToIpv4(networkLong)
        return Result.success(
            SubnetCalculationResult(
                ipAddress = cleanIp,
                prefixLength = prefix,
                cidrNotation = "$netStr/$prefix",
                subnetMask = formatUIntToIpv4(maskLong),
                wildcardMask = formatUIntToIpv4(wildcardLong),
                networkAddress = netStr,
                broadcastAddress = formatUIntToIpv4(broadcastLong),
                firstUsableHost = formatUIntToIpv4(firstHostLong),
                lastUsableHost = formatUIntToIpv4(lastHostLong),
                usableHostsCount = usableHosts,
                scopeDescription = scope
            )
        )
    }

    private fun parseIpv4ToUInt(ip: String): Long? {
        val parts = ip.split('.')
        if (parts.size != 4) return null
        var acc = 0L
        for (p in parts) {
            val v = p.toIntOrNull() ?: return null
            if (v !in 0..255) return null
            acc = (acc shl 8) or v.toLong()
        }
        return acc and 0xFFFFFFFFL
    }

    private fun maskToPrefixLength(maskStr: String): Int? {
        val mask = parseIpv4ToUInt(maskStr) ?: return null
        var seenZero = false
        var ones = 0
        for (bit in 31 downTo 0) {
            val isOne = ((mask ushr bit) and 1L) == 1L
            if (isOne) {
                if (seenZero) return null // non-contiguous subnet mask
                ones++
            } else {
                seenZero = true
            }
        }
        return ones
    }

    private fun formatUIntToIpv4(v: Long): String {
        val b1 = (v ushr 24) and 0xFFL
        val b2 = (v ushr 16) and 0xFFL
        val b3 = (v ushr 8) and 0xFFL
        val b4 = v and 0xFFL
        return "$b1.$b2.$b3.$b4"
    }

    // ==================== 3. PASSWORD & ACCESS CARD GENERATOR ====================

    fun generateStrongPassword(
        length: Int = 16,
        includeSymbols: Boolean = true,
        avoidAmbiguous: Boolean = true
    ): String {
        val safeLen = length.coerceIn(8, 64)
        val upper = if (avoidAmbiguous) "ABCDEFGHJKLMNPQRSTUVWXYZ" else "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        val lower = if (avoidAmbiguous) "abcdefghijkmnopqrstuvwxyz" else "abcdefghijklmnopqrstuvwxyz"
        val digits = if (avoidAmbiguous) "23456789" else "0123456789"
        val symbols = "!@#\$%&*+-=?"

        val required = mutableListOf(
            upper[secureRandom.nextInt(upper.length)],
            lower[secureRandom.nextInt(lower.length)],
            digits[secureRandom.nextInt(digits.length)]
        )
        if (includeSymbols) {
            required.add(symbols[secureRandom.nextInt(symbols.length)])
        }

        val pool = buildString {
            append(upper)
            append(lower)
            append(digits)
            if (includeSymbols) append(symbols)
        }

        while (required.size < safeLen) {
            required.add(pool[secureRandom.nextInt(pool.length)])
        }

        // Fisher-Yates shuffle with SecureRandom
        for (i in required.lastIndex downTo 1) {
            val j = secureRandom.nextInt(i + 1)
            val tmp = required[i]
            required[i] = required[j]
            required[j] = tmp
        }

        return required.joinToString("")
    }

    fun formatCredentialCard(
        serviceTitle: String,
        login: String,
        password: String
    ): String = formatCredentialCard(
        serviceTitle = serviceTitle,
        hostOrUrl = "",
        login = login,
        password = password,
        note = ""
    )

    fun formatCredentialCard(
        serviceTitle: String,
        hostOrUrl: String,
        login: String,
        password: String,
        note: String = ""
    ): String {
        return buildString {
            appendLine("🔐 Данные для подключения (${serviceTitle.trim().ifBlank { "Удалённый доступ / Сервис" }})")
            if (hostOrUrl.isNotBlank()) appendLine("• Адрес / Сервер: ${hostOrUrl.trim()}")
            if (login.isNotBlank()) appendLine("• Логин: ${login.trim()}")
            appendLine("• Пароль: $password")
            if (note.isNotBlank()) appendLine("• Примечание: ${note.trim()}")
            append("Служба техподдержки ООО «Компьютерная безопасность» (csl.by)")
        }
    }

    // ==================== 4. SSL & CLIENT HOST WATCHLIST MONITOR ====================

    suspend fun checkWatchlistHosts(hosts: List<String>): List<WatchlistHostStatus> = coroutineScope {
        val stamp = ZonedDateTime.now(ZoneId.of("Europe/Minsk"))
            .format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        val cleanedHosts = hosts.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        cleanedHosts.map { host ->
            async(Dispatchers.IO) {
                val rep = NetworkDiagnosticsTool.runDiagnostics(
                    rawHost = host,
                    timeoutMs = 1900,
                    portsToScan = NetworkDiagnosticsTool.WATCHLIST_PORTS
                )
                val sortedPortStatuses = rep.portStatuses.sortedWith(
                    compareByDescending<TcpPortStatus> { it.isOpen }.thenBy { it.port }
                )
                val openPortNums = sortedPortStatuses.filter { it.isOpen }.map { it.port }
                val openPorts = sortedPortStatuses.filter { it.isOpen }
                    .joinToString(", ") { "${it.port}/${it.serviceName}" }
                    .ifBlank { "нет открытых TCP" }

                val ssl = rep.sslCertificate
                val httpSummary = rep.httpProbe?.let { hp ->
                    buildString {
                        append("${hp.scheme.uppercase(Locale.ROOT)} ${hp.statusCode}")
                        if (!hp.serverHeader.isNullOrBlank()) append(" · ${hp.serverHeader}")
                        append(" (${hp.latencyMs} мс)")
                    }
                }
                val level: WatchlistHealthLevel
                val summary: String

                when {
                    !rep.isHostAlive -> {
                        level = WatchlistHealthLevel.CRITICAL
                        summary = "НЕДОСТУПЕН (${rep.errorMessage ?: "нет ответа ICMP/TCP"})"
                    }
                    ssl != null && (ssl.isExpired || ssl.daysRemaining <= 3) -> {
                        level = WatchlistHealthLevel.CRITICAL
                        summary = if (ssl.isExpired) {
                            "SSL ПРОСРОЧЕН (${ssl.expiresFormatted})"
                        } else {
                            "SSL истекает через ${ssl.daysRemaining} дн. (${ssl.expiresFormatted})"
                        }
                    }
                    ssl != null && ssl.daysRemaining <= 14 -> {
                        level = WatchlistHealthLevel.WARNING
                        summary = "Внимание: SSL истекает через ${ssl.daysRemaining} дн. (${ssl.expiresFormatted})"
                    }
                    ssl != null -> {
                        level = WatchlistHealthLevel.OK
                        summary = "ОК · SSL ещё ${ssl.daysRemaining} дн. (до ${ssl.expiresFormatted})"
                    }
                    else -> {
                        level = WatchlistHealthLevel.OK
                        summary = "ОК · Узел доступен (${rep.icmpLatencyMs?.let { "$it мс" } ?: openPorts})"
                    }
                }

                WatchlistHostStatus(
                    host = host,
                    resolvedIp = rep.resolvedIp,
                    isAlive = rep.isHostAlive,
                    latencyMs = rep.icmpLatencyMs,
                    openPorts = openPortNums,
                    portStatuses = sortedPortStatuses,
                    openPortsSummary = openPorts,
                    sslSubject = ssl?.subjectCn,
                    sslIssuer = ssl?.issuerCn,
                    sslExpiresFormatted = ssl?.expiresFormatted,
                    sslDaysRemaining = ssl?.daysRemaining,
                    httpServerSummary = httpSummary,
                    healthLevel = level,
                    statusSummary = summary,
                    checkedAtDisplay = stamp
                )
            }
        }.awaitAll().sortedWith(
            compareByDescending<WatchlistHostStatus> { it.healthLevel.ordinal }
                .thenBy { it.sslDaysRemaining ?: Long.MAX_VALUE }
        )
    }
}
