package com.helpdesk.jiraagent.model

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class ZabbixSeverity(
    val level: Int,
    val ruTitle: String,
    val shortBadge: String,
    val bannerSuffix: String
) {
    NOT_CLASSIFIED(
        level = 0,
        ruTitle = "Не классифицировано",
        shortBadge = "ИНФО 0",
        bannerSuffix = "Событие Zabbix"
    ),
    INFORMATION(
        level = 1,
        ruTitle = "Информация",
        shortBadge = "ИНФО",
        bannerSuffix = "Информация Zabbix"
    ),
    WARNING(
        level = 2,
        ruTitle = "Предупреждение",
        shortBadge = "ПРЕДУПР.",
        bannerSuffix = "Предупреждение Zabbix"
    ),
    AVERAGE(
        level = 3,
        ruTitle = "Ошибка (Средняя)",
        shortBadge = "ОШИБКА",
        bannerSuffix = "Ошибка Zabbix"
    ),
    HIGH(
        level = 4,
        ruTitle = "Критическое предупреждение (Высокая)",
        shortBadge = "КРИТИЧ.",
        bannerSuffix = "Критическое предупреждение Zabbix"
    ),
    DISASTER(
        level = 5,
        ruTitle = "Чрезвычайная авария",
        shortBadge = "АВАРИЯ",
        bannerSuffix = "Чрезвычайная авария Zabbix"
    );

    companion object {
        fun fromLevel(level: Int): ZabbixSeverity {
            return entries.firstOrNull { it.level == level.coerceIn(0, 5) } ?: NOT_CLASSIFIED
        }

        fun formatThresholdOptionLabel(minLevel: Int): String {
            return when (minLevel) {
                5 -> "Только Чрезвычайные (5)"
                4 -> "Высокие и Чрезвычайные (4–5)"
                3 -> "Ошибки и критические (3–5)"
                2 -> "Предупреждения и выше (2–5)"
                else -> "Все проблемы (1–5)"
            }
        }
    }
}

data class ZabbixProblem(
    val eventId: String,
    val triggerId: String,
    val name: String,
    val opData: String = "",
    val severityLevel: Int,
    val hostId: String = "",
    val hostTechnicalName: String = "",
    val hostDisplayName: String = "",
    val hostIp: String = "",
    val clockEpochSeconds: Long = 0L,
    val acknowledged: Boolean = false,
    val ackMessage: String = "",
    val comments: String = "",
    val triggerUrl: String = ""
) {
    val severity: ZabbixSeverity
        get() = ZabbixSeverity.fromLevel(severityLevel)

    val effectiveHostLabel: String
        get() = hostDisplayName.ifBlank { hostTechnicalName }.ifBlank { "Узел Zabbix" }

    val hostWithIpLabel: String
        get() = if (hostIp.isNotBlank() && !effectiveHostLabel.contains(hostIp)) {
            "$effectiveHostLabel ($hostIp)"
        } else {
            effectiveHostLabel
        }

    val alertKey: String
        get() = if (eventId.isNotBlank() && eventId != "0") {
            "zbx_ev_$eventId"
        } else {
            "zbx_tr_${triggerId}_$clockEpochSeconds"
        }

    val fallbackOccurrenceKey: String
        get() = "zbx_tr_${triggerId}_$clockEpochSeconds"

    val signatureMuteKey: String
        get() = "sig:${effectiveHostLabel.trim().lowercase()}::${name.trim().lowercase()}"

    val muteRuleKey: String
        get() = if (triggerId.isNotBlank() && triggerId != "0") {
            "tr:${triggerId.trim()}"
        } else {
            signatureMuteKey
        }

    fun isDismissedBy(dismissedKeys: Set<String>): Boolean {
        if (dismissedKeys.isEmpty()) return false
        return alertKey in dismissedKeys || fallbackOccurrenceKey in dismissedKeys
    }

    fun isMutedBy(mutedRuleKeys: Set<String>): Boolean {
        if (mutedRuleKeys.isEmpty()) return false
        return muteRuleKey in mutedRuleKeys || signatureMuteKey in mutedRuleKeys
    }

    fun isHiddenFromActiveFeed(
        dismissedKeys: Set<String>,
        mutedRuleKeys: Set<String>
    ): Boolean = isDismissedBy(dismissedKeys) || isMutedBy(mutedRuleKeys)

    fun toMutedRule(nowMillis: Long = System.currentTimeMillis()): ZabbixMutedRule {
        return ZabbixMutedRule(
            ruleKey = muteRuleKey,
            signatureKey = signatureMuteKey,
            triggerId = triggerId.trim(),
            hostLabel = hostWithIpLabel,
            problemName = name,
            severityLevel = severityLevel,
            mutedAtMillis = nowMillis
        )
    }

    fun shouldTriggerBanner(minSeverityLevel: Int): Boolean {
        val threshold = minSeverityLevel.coerceIn(1, 5)
        if (severityLevel >= threshold) return true
        // Also catch triggers explicitly marked "критич" / "critical" / "ошибк" / "error" at Warning(2)+
        if (severityLevel >= 2) {
            val lower = name.lowercase()
            if (lower.contains("критич") || lower.contains("critical") ||
                lower.contains("disaster") || lower.contains("авари")
            ) {
                return true
            }
        }
        return false
    }

    fun formattedStartTime(zoneId: ZoneId = ZoneId.of("Europe/Minsk")): String {
        if (clockEpochSeconds <= 0L) return "сейчас"
        return runCatching {
            DateTimeFormatter.ofPattern("dd.MM HH:mm:ss")
                .withZone(zoneId)
                .format(Instant.ofEpochSecond(clockEpochSeconds))
        }.getOrDefault("")
    }

    fun formattedDuration(nowEpochMillis: Long = System.currentTimeMillis()): String {
        if (clockEpochSeconds <= 0L) return "активна"
        val diffSec = ((nowEpochMillis / 1000L) - clockEpochSeconds).coerceAtLeast(0L)
        val days = diffSec / 86_400L
        val hours = (diffSec % 86_400L) / 3_600L
        val mins = (diffSec % 3_600L) / 60L
        return when {
            days > 0 -> "${days}д ${hours}ч"
            hours > 0 -> "${hours}ч ${mins}м"
            mins > 0 -> "${mins} мин"
            else -> "${diffSec} сек"
        }
    }

    /**
     * Converts this Zabbix alert into a synthetic HelpdeskTicket representation so
     * client-ticket floating banners and overlays can render it with full visual parity.
     */
    fun toBannerDisplayTicket(zabbixBaseUrl: String): HelpdeskTicket {
        val displayCode = if (eventId.isNotBlank() && eventId != "0") "ZBX-$eventId" else "ZBX-T$triggerId"
        val cleanBase = zabbixBaseUrl.trim().trimEnd('/').ifBlank { AppSettings.DEFAULT_ZABBIX_URL }
        val webLink = if (eventId.isNotBlank() && eventId != "0" && triggerId.isNotBlank()) {
            "$cleanBase/tr_events.php?triggerid=$triggerId&eventid=$eventId"
        } else {
            "$cleanBase/zabbix.php?action=problem.view"
        }
        val summaryWithOpData = buildString {
            append(name)
            if (opData.isNotBlank()) {
                append(" [").append(opData).append("]")
            }
        }
        val desc = buildString {
            append("Узел: ").append(hostWithIpLabel)
            append("\nВажность: ").append(severity.ruTitle)
            if (opData.isNotBlank()) append("\nТекущее значение: ").append(opData)
            if (comments.isNotBlank()) append("\nОписание триггера: ").append(comments)
        }
        val startedMs = if (clockEpochSeconds > 0L) clockEpochSeconds * 1000L else System.currentTimeMillis()
        val iso = Instant.ofEpochMilli(startedMs).toString()
        return HelpdeskTicket(
            id = eventId.ifBlank { triggerId },
            key = displayCode,
            summary = summaryWithOpData,
            description = desc,
            issueType = "Zabbix Alert",
            isSubtask = false,
            customerRequestType = "Zabbix Monitoring",
            hasCustomerPortalInfo = true,
            status = if (acknowledged) "Подтверждено" else "Активная проблема",
            statusCategoryKey = if (acknowledged) "indeterminate" else "new",
            priority = severity.ruTitle,
            projectKey = "ZBX",
            projectName = "Zabbix (z.csl.by)",
            reporterName = "Zabbix · $hostWithIpLabel",
            reporterLoginOrEmail = "zabbix@csl.by",
            assigneeName = null,
            assigneeLogin = null,
            createdIso = iso,
            createdEpochMillis = startedMs,
            updatedIso = iso,
            updatedEpochMillis = startedMs,
            labels = listOf("zabbix", severity.name.lowercase()),
            phoneNumbers = emptyList(),
            telegramHandles = emptyList(),
            comments = emptyList(),
            attachments = emptyList(),
            webUrl = webLink,
            isClientTicket = true,
            filterReason = "Zabbix: ${severity.ruTitle}"
        )
    }
}

data class ZabbixDashboardSnapshot(
    val serverVersion: String = "",
    val resolvedEndpointUrl: String = "",
    val monitoredHostsCount: Int = 0,
    val problems: List<ZabbixProblem> = emptyList(),
    val fetchedAtMillis: Long = System.currentTimeMillis()
) {
    val disasterCount: Int get() = problems.count { it.severityLevel >= 5 }
    val highCount: Int get() = problems.count { it.severityLevel == 4 }
    val averageCount: Int get() = problems.count { it.severityLevel == 3 }
    val warningCount: Int get() = problems.count { it.severityLevel == 2 }
    val criticalAndErrorCount: Int get() = problems.count { it.severityLevel >= 3 }
}

data class ZabbixMutedRule(
    val ruleKey: String,
    val signatureKey: String = "",
    val triggerId: String = "",
    val hostLabel: String = "",
    val problemName: String = "",
    val severityLevel: Int = 3,
    val mutedAtMillis: Long = System.currentTimeMillis()
) {
    fun allMatchKeys(): Set<String> {
        return buildSet {
            if (ruleKey.isNotBlank()) add(ruleKey)
            if (signatureKey.isNotBlank()) add(signatureKey)
        }
    }
}
