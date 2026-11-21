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

data class MacAddressMatch(
    val normalizedMac: String,
    val ouiPrefix: String,
    val vendorName: String?
)

data class StreetAddressMatch(
    val displayAddress: String,
    val searchQuery: String
)

data class SysadminEntities(
    val remoteIds: List<RemoteDesktopIdMatch>,
    val networkTargets: List<NetworkTargetMatch>,
    val macAddresses: List<MacAddressMatch> = emptyList(),
    val streetAddresses: List<StreetAddressMatch> = emptyList()
) {
    val isEmpty: Boolean
        get() = remoteIds.isEmpty() && networkTargets.isEmpty() && macAddresses.isEmpty() && streetAddresses.isEmpty()
}

object SysadminEntityExtractor {

    private val LABELED_REMOTE_REGEX = Regex(
        """(?iu)\b(anydesk|анидеск|энидеск|rustdesk|растдеск|teamviewer|тимвьюер|удаленк[аиеу]|id\s*подключен\w*)\s*[:#\-–=]?\s*(\d[\d\s\-]{7,13}\d)"""
    )

    private val GROUPED_NINE_DIGIT_REGEX = Regex(
        """(?<!\d)(\d{3}[ \t]+\d{3}[ \t]+\d{3})(?!\d)"""
    )

    private val IPV4_REGEX = Regex(
        """(?<![\d.])((?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(?:\.(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3})(?![\d.])"""
    )

    private val DOMAIN_REGEX = Regex(
        """(?<![@\w.-])([a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(?:\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)+)(?![\w.-])"""
    )

    // Standard MAC (AA:BB:CC:DD:EE:FF or AA-BB-CC-DD-EE-FF) and Cisco MAC (AABB.CCDD.EEFF)
    private val MAC_COLON_DASH_REGEX = Regex(
        """(?i)(?<![0-9A-F:])((?:[0-9A-F]{2}[:-]){5}[0-9A-F]{2})(?![0-9A-F]|:[0-9A-F])"""
    )
    private val MAC_CISCO_REGEX = Regex(
        """(?i)(?<![0-9A-F])([0-9A-F]{4}\.[0-9A-F]{4}\.[0-9A-F]{4})(?![0-9A-F]|\.[0-9A-F])"""
    )

    // Street / office address patterns (RU/BY)
    private val LABELED_ADDRESS_REGEX = Regex(
        """(?imu)^\s*(?:адрес(?:\s+выезда|\s+объекта)?|местонахождение|выезд\s+по\s+адресу)\s*[:\-–]\s*([^\r\n]{6,95})"""
    )
    private val STREET_ADDRESS_REGEX = Regex(
        """(?iu)((?:г\.?\s*[А-ЯЁІЎа-яёіў\-]{3,20}\s*,\s*)?(?:ул\.|улица|пр-т|пр\.|проспект|пер\.|переулок|тракт|б-р|бульвар|шоссе|пл\.)\s*[А-ЯЁІЎа-яёіў0-9][А-ЯЁІЎа-яёіў0-9\s.\-]{2,34}?,?\s*(?:д\.?\s*|дом\s*)?\d{1,4}[А-Яа-яA-Za-z0-9/\-]*(?:\s*,\s*(?:к\.|корп\.|оф\.|офис|каб\.)\s*\d+[А-Яа-яA-Za-z0-9\-]*)?)"""
    )

    private val COMMON_TLDS = setOf(
        "by", "бел", "ru", "com", "net", "org", "local", "lan", "corp", "internal", "io", "dev", "me", "info", "pro", "biz", "cloud"
    )

    private val FILE_EXT_BLACKLIST = setOf(
        "png", "jpg", "jpeg", "gif", "webp", "pdf", "doc", "docx", "xls", "xlsx", "exe", "msi", "zip", "rar", "7z", "txt", "log", "apk", "dll", "sys", "ini", "cfg", "xml", "json"
    )

    // Offline OUI prefix -> Enterprise / Office / Network vendor lookup
    private val OUI_VENDORS = mapOf(
        "D4:CA:6D" to "MikroTik", "E4:8D:8C" to "MikroTik", "4C:5E:0C" to "MikroTik",
        "CC:2D:E0" to "MikroTik", "64:D1:54" to "MikroTik", "B8:69:F4" to "MikroTik",
        "C4:AD:34" to "MikroTik", "08:55:31" to "MikroTik", "18:FD:74" to "MikroTik",
        "2C:C8:1B" to "MikroTik", "48:8F:5A" to "MikroTik", "DC:2C:6E" to "MikroTik",
        "78:9A:18" to "MikroTik", "F4:1E:57" to "MikroTik",
        "00:1A:A1" to "Cisco", "00:1B:54" to "Cisco", "00:24:14" to "Cisco",
        "F8:72:EA" to "Cisco", "BC:16:65" to "Cisco", "68:BC:0C" to "Cisco",
        "24:A4:3C" to "Ubiquiti", "78:8A:20" to "Ubiquiti", "F0:9F:C2" to "Ubiquiti",
        "80:2A:A8" to "Ubiquiti", "FC:EC:DA" to "Ubiquiti", "74:83:C2" to "Ubiquiti",
        "50:C7:BF" to "TP-Link", "C0:25:E9" to "TP-Link", "E8:DE:27" to "TP-Link",
        "14:CC:20" to "TP-Link", "B0:BE:76" to "TP-Link", "98:DA:C4" to "TP-Link",
        "50:FF:20" to "Keenetic / ZyXEL", "EC:43:F6" to "Keenetic", "28:28:5D" to "ZyXEL",
        "1C:74:0D" to "ZyXEL", "BC:CF:4F" to "ZTE / Провайдер ONT",
        "48:46:FB" to "Huawei", "00:E0:FC" to "Huawei", "88:28:B3" to "Huawei",
        "D4:6E:5C" to "Huawei", "70:72:3C" to "Huawei",
        "C0:56:27" to "Belkin / Linksys", "1C:BD:B9" to "D-Link", "28:10:7B" to "D-Link",
        "3C:D9:2B" to "HP / Hewlett Packard", "A0:D3:C1" to "HP", "FC:15:B4" to "HP",
        "94:57:A5" to "HP Printer / Enterprise", "10:60:4B" to "HP",
        "F8:B1:56" to "Dell", "18:03:73" to "Dell", "D4:BE:D9" to "Dell",
        "54:EE:75" to "Lenovo", "F0:DE:F1" to "Lenovo", "00:21:CC" to "Lenovo",
        "04:D4:C4" to "ASUS", "2C:56:DC" to "ASUS", "AC:9E:17" to "ASUS",
        "00:1B:21" to "Intel", "A4:BF:01" to "Intel", "3C:FD:FE" to "Intel",
        "00:E0:4C" to "Realtek", "52:54:00" to "QEMU / Proxmox KVM",
        "00:50:56" to "VMware ESXi", "00:0C:29" to "VMware", "00:15:5D" to "Microsoft Hyper-V",
        "C0:56:E3" to "Hikvision", "44:19:B6" to "Hikvision", "BC:AD:28" to "Hikvision",
        "28:57:BE" to "Hikvision", "3C:EF:8C" to "Dahua CCTV", "E0:50:8B" to "Dahua CCTV",
        "00:11:32" to "Synology NAS", "24:5E:BE" to "QNAP NAS",
        "00:17:C8" to "Kyocera Printer", "00:C0:EE" to "Kyocera Printer",
        "00:00:85" to "Canon Printer", "18:0C:AC" to "Canon Printer",
        "00:00:AA" to "Xerox Printer", "30:05:5C" to "Brother Printer",
        "00:26:73" to "Ricoh Printer", "00:00:48" to "Epson Printer",
        "00:C0:B7" to "APC UPS", "28:29:86" to "APC UPS",
        "F0:18:98" to "Apple", "3C:22:FB" to "Apple"
    )

    fun lookupMacVendor(rawMac: String): MacAddressMatch? {
        val hex = rawMac.uppercase(Locale.ROOT).filter { it in '0'..'9' || it in 'A'..'F' }
        if (hex.length != 12) return null
        val normalized = hex.chunked(2).joinToString(":")
        val prefix = normalized.substring(0, 8)
        return MacAddressMatch(
            normalizedMac = normalized,
            ouiPrefix = prefix,
            vendorName = OUI_VENDORS[prefix]
        )
    }

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

        val macMap = linkedMapOf<String, MacAddressMatch>()
        for (match in MAC_COLON_DASH_REGEX.findAll(text)) {
            val resolved = lookupMacVendor(match.groupValues[1]) ?: continue
            macMap[resolved.normalizedMac] = resolved
        }
        for (match in MAC_CISCO_REGEX.findAll(text)) {
            val resolved = lookupMacVendor(match.groupValues[1]) ?: continue
            macMap[resolved.normalizedMac] = resolved
        }

        val addressMap = linkedMapOf<String, StreetAddressMatch>()
        for (match in LABELED_ADDRESS_REGEX.findAll(text)) {
            val cleaned = match.groupValues[1].trim().trimEnd('.', ';')
            if (cleaned.length >= 6 && cleaned.any(Char::isDigit)) {
                addressMap[cleaned.lowercase(Locale.ROOT)] = StreetAddressMatch(
                    displayAddress = cleaned,
                    searchQuery = enrichBelarusQuery(cleaned)
                )
            }
        }
        for (match in STREET_ADDRESS_REGEX.findAll(text)) {
            val cleaned = match.groupValues[1].trim().replace(Regex("\\s+"), " ").trimEnd(',', '.', ';')
            if (cleaned.length >= 8) {
                val key = cleaned.lowercase(Locale.ROOT)
                if (addressMap.keys.none { it.contains(key) || key.contains(it) }) {
                    addressMap[key] = StreetAddressMatch(
                        displayAddress = cleaned,
                        searchQuery = enrichBelarusQuery(cleaned)
                    )
                }
            }
        }

        return SysadminEntities(
            remoteIds = remoteMap.values.take(5),
            networkTargets = hostMap.values.take(6),
            macAddresses = macMap.values.take(4),
            streetAddresses = addressMap.values.take(3)
        )
    }

    private fun enrichBelarusQuery(raw: String): String {
        val lower = raw.lowercase(Locale.ROOT)
        return if (
            lower.contains("минск") || lower.contains("брест") || lower.contains("гродно") ||
            lower.contains("гомель") || lower.contains("витебск") || lower.contains("могилев") ||
            lower.contains("могилёв") || lower.contains("г.")
        ) {
            raw
        } else {
            "Минск, $raw"
        }
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
