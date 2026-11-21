package com.helpdesk.jiraagent.domain

import com.helpdesk.jiraagent.model.HelpdeskTicket
import java.util.Locale

enum class RemoteDesktopKind(val label: String, val packageNames: List<String>, val uriPrefix: String?) {
    ANYDESK(
        label = "AnyDesk",
        packageNames = listOf("com.anydesk.anydeskandroid"),
        uriPrefix = "anydesk:"
    ),
    RUSTDESK(
        label = "RustDesk",
        packageNames = listOf("com.carriez.flutter_hbb"),
        uriPrefix = "rustdesk://"
    ),
    TEAMVIEWER(
        label = "TeamViewer",
        packageNames = listOf("com.teamviewer.teamviewer.market.mobile"),
        uriPrefix = null
    ),
    REMOTE_ID(
        label = "ID удалёнки",
        packageNames = listOf("com.anydesk.anydeskandroid", "com.carriez.flutter_hbb"),
        uriPrefix = "anydesk:"
    )
}

data class RemoteDesktopIdMatch(
    val kind: RemoteDesktopKind,
    val normalizedId: String,
    val formattedId: String
)

data class NetworkTargetMatch(
    val host: String,
    val isIpAddress: Boolean
)

data class SysadminEntities(
    val remoteIds: List<RemoteDesktopIdMatch>,
    val networkTargets: List<NetworkTargetMatch>
) {
    val isEmpty: Boolean
        get() = remoteIds.isEmpty() && networkTargets.isEmpty()
}

object SysadminEntityExtractor {

    // Explicit keyword + 9..10 digit ID (e.g. "AnyDesk: 123 456 789", "анидеск 987654321", "rustdesk 1234567890")
    private val LABELED_REMOTE_REGEX = Regex(
        """(?i)\b(anydesk|анидеск|энидеск|rustdesk|растдеск|teamviewer|тимвьюер|удаленк[аиеу]|id\s*подключен\w*)\s*[:#\-–=]?\s*(\d[\d\s\-]{7,13}\d)"""
    )

    // Classic grouped 9-digit AnyDesk/RustDesk pattern: "412 839 104"
    private val GROUPED_NINE_DIGIT_REGEX = Regex(
        """(?<!\d)(\d{3}[ \t]+\d{3}[ \t]+\d{3})(?!\d)"""
    )

    // IPv4 addresses
    private val IPV4_REGEX = Regex(
        """(?<![\d.])((?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(?:\.(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3})(?![\d.])"""
    )

    // Domain/hostname (excluding email addresses preceded by '@', and common file extensions)
    private val DOMAIN_REGEX = Regex(
        """(?<![@\w.-])([a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(?:\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)+)(?![\w.-])"""
    )

    private val COMMON_TLDS = setOf(
        "by", "бел", "ru", "com", "net", "org", "local", "lan", "corp", "internal", "io", "dev", "me", "info", "pro", "biz", "cloud"
    )

    private val FILE_EXT_BLACKLIST = setOf(
        "png", "jpg", "jpeg", "gif", "webp", "pdf", "doc", "docx", "xls", "xlsx", "exe", "msi", "zip", "rar", "7z", "txt", "log", "apk", "dll", "sys", "ini", "cfg", "xml", "json"
    )

    fun extractFromTicket(ticket: HelpdeskTicket): SysadminEntities {
        val combined = buildString {
            appendLine(ticket.summary)
            appendLine(ticket.description)
            ticket.comments.forEach { appendLine(it.body) }
        }
        val phoneDigitTails = ticket.phoneNumbers.map { it.e164.filter(Char::isDigit).takeLast(9) }.toSet()
        return extract(combined, phoneDigitTails)
    }

    fun extract(text: String, excludeNineDigitTails: Set<String> = emptySet()): SysadminEntities {
        if (text.isBlank()) return SysadminEntities(emptyList(), emptyList())

        val remoteMap = linkedMapOf<String, RemoteDesktopIdMatch>()

        for (match in LABELED_REMOTE_REGEX.findAll(text)) {
            val labelRaw = match.groupValues[1].lowercase(Locale.ROOT)
            val digits = match.groupValues[2].filter(Char::isDigit)
            if (digits.length !in 9..10) continue
            if (digits.takeLast(9) in excludeNineDigitTails) continue

            val kind = when {
                labelRaw.contains("rust") || labelRaw.contains("раст") -> RemoteDesktopKind.RUSTDESK
                labelRaw.contains("team") || labelRaw.contains("тим") -> RemoteDesktopKind.TEAMVIEWER
                labelRaw.contains("any") || labelRaw.contains("ани") || labelRaw.contains("эни") -> RemoteDesktopKind.ANYDESK
                else -> RemoteDesktopKind.REMOTE_ID
            }
            remoteMap[digits] = RemoteDesktopIdMatch(
                kind = kind,
                normalizedId = digits,
                formattedId = formatRemoteDigits(digits)
            )
        }

        for (match in GROUPED_NINE_DIGIT_REGEX.findAll(text)) {
            val digits = match.groupValues[1].filter(Char::isDigit)
            if (digits.length != 9) continue
            if (digits in excludeNineDigitTails) continue
            if (remoteMap.containsKey(digits)) continue
            remoteMap[digits] = RemoteDesktopIdMatch(
                kind = RemoteDesktopKind.ANYDESK,
                normalizedId = digits,
                formattedId = formatRemoteDigits(digits)
            )
        }

        val hostMap = linkedMapOf<String, NetworkTargetMatch>()
        for (match in IPV4_REGEX.findAll(text)) {
            val ip = match.groupValues[1]
            if (ip == "0.0.0.0" || ip == "127.0.0.1" || ip == "255.255.255.255") continue
            hostMap[ip] = NetworkTargetMatch(host = ip, isIpAddress = true)
        }

        for (match in DOMAIN_REGEX.findAll(text)) {
            val candidate = match.groupValues[1].lowercase(Locale.ROOT)
            // Skip if followed immediately by '@' wasn't possible, also check if preceded or followed by email context
            val startIdx = match.range.first
            val endIdx = match.range.last
            if (startIdx > 0 && text[startIdx - 1] == '@') continue
            if (endIdx + 1 < text.length && text[endIdx + 1] == '@') continue

            val tld = candidate.substringAfterLast('.')
            if (tld in FILE_EXT_BLACKLIST) continue
            if (tld !in COMMON_TLDS) continue
            if (candidate.all { it.isDigit() || it == '.' }) continue
            if (!hostMap.containsKey(candidate)) {
                hostMap[candidate] = NetworkTargetMatch(host = candidate, isIpAddress = false)
            }
        }

        return SysadminEntities(
            remoteIds = remoteMap.values.take(5),
            networkTargets = hostMap.values.take(6)
        )
    }

    private fun formatRemoteDigits(digits: String): String {
        return if (digits.length == 9) {
            "${digits.substring(0, 3)} ${digits.substring(3, 6)} ${digits.substring(6, 9)}"
        } else if (digits.length == 10) {
            "${digits.substring(0, 1)} ${digits.substring(1, 4)} ${digits.substring(4, 7)} ${digits.substring(7, 10)}"
        } else {
            digits
        }
    }
}
