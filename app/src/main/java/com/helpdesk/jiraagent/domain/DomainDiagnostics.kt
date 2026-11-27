package com.helpdesk.jiraagent.domain

/** Offline, role-specific reference; role selection is supplied by the engineer, never detected. */
enum class DomainDiagnosticRole(val label: String, val location: String, val warning: String) {
    MEMBER(
        "Участник домена", "Проблемный доменный ПК или рядовой Windows-сервер",
        "Убедитесь, что это не контроллер домена. Для кластеров и критичных серверов дополнительно согласуйте действия с владельцем сервиса."
    ),
    CONTROLLER(
        "Контроллер домена", "Проверяемый контроллер домена (DC)",
        "Не используйте Test-ComputerSecureChannel для DC: возможны ложные ошибки. Восстановление DC требует отдельного плана и специалиста AD."
    ),
    RSAT(
        "Админ-ПК / RSAT", "Административный Windows-компьютер с RSAT / AD DS",
        "Данные запрашиваются у контроллеров домена. Нужны доступ, подходящие права и инструменты RSAT; результаты могут не охватывать все проблемы."
    )
}

data class DomainDiagnosticCheck(val location: String, val guidance: String, val command: SysadminCommand)

object DomainDiagnostics {
    private val dnsBasic = SysadminCommand(
        "ad-dns-basic", CommandPlatform.WINDOWS, "Базовая проверка DNS контроллера",
        "Проверяет доступность служб, сетевую конфигурацию DNS-клиента и наличие зон.",
        "На проверяемом контроллере домена с установленными AD DS откройте cmd от администратора и выполните команду. Без /s проверяется локальный DC.",
        "Диагностика обращается к службам и DNS, может занять время. Выбран /DnsBasic, а не /DnsAll: тест динамического обновления, который может создать тестовую DNS-запись, не запускается.",
        "dcdiag /test:DNS /DnsBasic /v", CommandTopic.DOMAIN
    )

    private fun command(id: String): SysadminCommand =
        requireNotNull(SysadminCommandCatalog.entries.singleOrNull { it.id == id }) { "Unknown domain command: $id" }

    fun checks(role: DomainDiagnosticRole): List<DomainDiagnosticCheck> = when (role) {
        DomainDiagnosticRole.MEMBER -> listOf(
            DomainDiagnosticCheck("На проблемном члене домена · CMD", "Проверьте сеть/VPN и корпоративные DNS-серверы; не меняйте настройки автоматически.", command("win-ip")),
            DomainDiagnosticCheck("На проблемном члене домена · PowerShell", "Определите домен и контроллер; результат может быть закэширован.", command("ad-dc-discovery")),
            DomainDiagnosticCheck("На проблемном члене домена · CMD", "Сравните время и источник с контроллером, учитывая политику организации.", command("ad-time")),
            DomainDiagnosticCheck("На проблемном члене домена · PowerShell от администратора", "Только член домена. Сначала проверьте DNS, связь и время; ошибка теста не всегда означает, что требуется ремонт.", command("ad-trust-check")),
            DomainDiagnosticCheck("На проблемном члене домена · PowerShell", "Изучите системные события и зафиксируйте время и коды ошибок.", command("ad-domain-events"))
        )
        DomainDiagnosticRole.CONTROLLER -> listOf(
            DomainDiagnosticCheck("На проверяемом DC · CMD", "Проверьте адреса и DNS, не меняя настройки.", command("win-ip")),
            DomainDiagnosticCheck("На проверяемом DC · CMD от администратора", "Базовые проверки DNS; при необходимости дальнейший анализ согласуйте с владельцем AD.", dnsBasic),
            DomainDiagnosticCheck("На проверяемом DC · CMD от администратора", "Проверьте состояние служб и выясните значение ошибок до любых действий.", command("ad-dc-services")),
            DomainDiagnosticCheck("На проверяемом DC или машине с AD DS / RSAT · CMD", "Оцените сводку репликации; команды не принуждают репликацию.", command("ad-replication")),
            DomainDiagnosticCheck("На проверяемом DC · CMD", "Зафиксируйте источник времени и состояние синхронизации.", command("ad-time"))
        )
        DomainDiagnosticRole.RSAT -> listOf(
            DomainDiagnosticCheck("На административном компьютере с RSAT / AD DS · CMD", "Проверьте сводку репликации; необходимы подходящие разрешения и сетевая доступность контроллеров.", command("ad-replication"))
        )
    }

    /** Only the member role can obtain the existing guarded script via this card. */
    fun repairFor(role: DomainDiagnosticRole): SysadminCommand? = when (role) {
        DomainDiagnosticRole.MEMBER -> command("ad-trust-repair")
        DomainDiagnosticRole.CONTROLLER, DomainDiagnosticRole.RSAT -> null
    }
}
