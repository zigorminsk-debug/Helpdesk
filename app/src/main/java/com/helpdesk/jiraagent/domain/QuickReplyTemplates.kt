package com.helpdesk.jiraagent.domain

import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

data class QuickReplyPreset(
    val id: String,
    val title: String,
    val body: String,
    val isBuiltIn: Boolean = false
)

object QuickReplyTemplates {

    val DEFAULT_PRESETS: List<QuickReplyPreset> = listOf(
        QuickReplyPreset(
            id = "accepted",
            title = "👋 Принято в работу",
            body = "Здравствуйте, {клиент}! Заявка {заявка} принята в работу инженером {инженер}. Подключаюсь к диагностике.",
            isBuiltIn = true
        ),
        QuickReplyPreset(
            id = "remote_id",
            title = "🖥 Запрос AnyDesk / RustDesk",
            body = "Здравствуйте, {клиент}! Пришлите, пожалуйста, в ответном сообщении ID и временный пароль AnyDesk или RustDesk для удалённого подключения.",
            isBuiltIn = true
        ),
        QuickReplyPreset(
            id = "done_check",
            title = "✅ Выполнено — проверьте",
            body = "Здравствуйте, {клиент}! Работы по заявке {заявка} выполнены. Проверьте, пожалуйста, со своей стороны и подтвердите, всё ли работает корректно.",
            isBuiltIn = true
        ),
        QuickReplyPreset(
            id = "reboot_spooler",
            title = "🖨 Службы / Печать",
            body = "Перезапущены системные службы и очищена очередь диспетчера печати. Проверьте, пожалуйста, печать документа.",
            isBuiltIn = true
        ),
        QuickReplyPreset(
            id = "workshop_intake",
            title = "🔧 Передача в СЦ csl.by",
            body = "Требуется аппаратная диагностика в сервисном центре ООО «Компьютерная безопасность» (г. Минск, ул. Фабрициуса, 9, Пн–Пт 09:00–17:00). Согласуем забор оборудования.",
            isBuiltIn = true
        )
    )

    fun expand(
        templateBody: String,
        clientName: String?,
        ticketKey: String?,
        engineerName: String?
    ): String {
        val cleanClient = clientName
            ?.trim()
            ?.takeIf { it.isNotEmpty() && !it.equals("Не указан", ignoreCase = true) }
            ?.substringBefore(" (")
            ?: "коллеги"
        val cleanKey = ticketKey?.trim()?.takeIf { it.isNotEmpty() } ?: "CSL"
        val cleanEngineer = engineerName
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: "службы поддержки csl.by"

        return templateBody
            .replace("{клиент}", cleanClient, ignoreCase = true)
            .replace("{заявка}", cleanKey, ignoreCase = true)
            .replace("{инженер}", cleanEngineer, ignoreCase = true)
    }

    fun formatDiagnosticReportForComment(report: HostDiagnosticReport): String {
        val stamp = ZonedDateTime.now(ZoneId.of("Europe/Minsk"))
            .format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))
        val openPorts = report.portStatuses.filter { it.isOpen }
        val closedPorts = report.portStatuses.filter { !it.isOpen }

        return buildString {
            appendLine("🌐 Сетевая экспресс-диагностика Help-CSL ($stamp):")
            appendLine("• Узел: ${report.targetHost} (IP: ${report.resolvedIp ?: "не разрешён"})")
            appendLine(
                "• ICMP / Доступность: " +
                    if (report.isHostAlive) {
                        "ДОСТУПЕН" + (report.icmpLatencyMs?.let { " (${it} мс)" } ?: "")
                    } else {
                        "НЕТ ОТВЕТА"
                    }
            )
            if (openPorts.isNotEmpty()) {
                appendLine(
                    "• Открытые TCP-порты: " + openPorts.joinToString(", ") { p ->
                        "${p.port}/${p.serviceName}" + (p.latencyMs?.let { " (${it} мс)" } ?: "")
                    }
                )
            }
            if (closedPorts.isNotEmpty()) {
                appendLine(
                    "• TCP-порты без соединения: " + closedPorts.joinToString(", ") { p -> "${p.port}/${p.serviceName}" }
                )
            }
            report.httpProbe?.let { http ->
                appendLine(
                    "• HTTP-проверка: ${http.scheme.uppercase()} ${http.statusCode} (${http.latencyMs} мс)" +
                        (http.serverHeader?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
                )
            }
            report.sslCertificate?.let { ssl ->
                val sslState = if (ssl.isExpired) "ПРОСРОЧЕН" else "осталось ${ssl.daysRemaining} дн."
                appendLine("• SSL-сертификат: CN=${ssl.subjectCn}, до ${ssl.expiresFormatted} ($sslState)")
            }
            if (report.dnsRecords.mxRecords.isNotEmpty()) {
                appendLine("• MX-записи: ${report.dnsRecords.mxRecords.joinToString(", ")}")
            }
            report.errorMessage?.takeIf { it.isNotBlank() }?.let { err ->
                appendLine("• Примечание: $err")
            }
        }.trimEnd()
    }
}
