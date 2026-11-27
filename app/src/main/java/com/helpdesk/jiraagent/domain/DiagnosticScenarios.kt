package com.helpdesk.jiraagent.domain

/** Reference-only checklists. None of the commands are executed by Help-CSL. */
enum class ScenarioStepStatus(val label: String) {
    NOT_CHECKED("Не проверено"), OK("Норма"), ISSUE("Есть проблема"), NOT_APPLICABLE("Не применимо")
}

data class ScenarioStep(
    val id: String,
    val title: String,
    val instruction: String,
    val commandIds: List<String> = emptyList(),
    val extraCommands: List<SysadminCommand> = emptyList()
) {
    fun commands(): List<SysadminCommand> = commandIds.mapNotNull { id ->
        SysadminCommandCatalog.entries.firstOrNull { it.id == id && !it.changesSystem }
    } + extraCommands.filterNot { it.changesSystem }
}

data class DiagnosticScenario(val id: String, val title: String, val introduction: String, val steps: List<ScenarioStep>)
data class ScenarioStepResult(val status: ScenarioStepStatus = ScenarioStepStatus.NOT_CHECKED, val note: String = "")

object DiagnosticScenarios {
    private val printerList = SysadminCommand(
        "scenario-printer-list", CommandPlatform.POWERSHELL, "Принтеры и порты Windows",
        "Показывает имена, драйверы, порты и общий статус установленных принтеров.",
        "На проблемном Windows-компьютере откройте PowerShell и вставьте команду. Нужен модуль PrintManagement; при отказе в доступе используйте разрешённые права.",
        "Только чтение. Вывод может содержать имена сетевых принтеров; перед пересылкой удалите лишние сведения.",
        "Get-Printer | Select-Object Name, DriverName, PortName, PrinterStatus | Format-Table -AutoSize"
    )
    private val spooler = SysadminCommand(
        "scenario-spooler", CommandPlatform.POWERSHELL, "Состояние диспетчера печати",
        "Показывает состояние локальной службы Spooler.",
        "На проблемном Windows-компьютере или соответствующем сервере печати выполните команду в PowerShell.",
        "Только чтение. Проверка не перезапускает службу и не удаляет задания.",
        "Get-Service -Name Spooler | Format-List Name, DisplayName, Status, StartType"
    )
    private val windowsServices = SysadminCommand(
        "scenario-services", CommandPlatform.POWERSHELL, "Службы Windows",
        "Показывает состояние и системное имя локальных служб Windows.",
        "На нужном Windows-компьютере откройте PowerShell, выполните команду и найдите нужную службу в списке.",
        "Только чтение. Остановленная служба может быть отключена намеренно; учитывайте режим запуска и зависимости.",
        "Get-Service | Sort-Object Name | Format-Table Name, DisplayName, Status -AutoSize"
    )

    val entries = listOf(
        DiagnosticScenario("network", "Сеть / DNS", "Начните с границ проблемы и проверяйте сеть с затронутого устройства. Отсутствие Ping не доказывает отказ сервиса.", listOf(
            ScenarioStep("scope", "Масштаб и симптомы", "Зафиксируйте время, узел, сообщение об ошибке, кого затронуло и что изменилось. Сравните с работающим устройством при наличии разрешения."),
            ScenarioStep("local", "Подключение и адреса", "На проблемном устройстве проверьте подключение к нужной сети/VPN, адрес, шлюз и DNS. Не меняйте конфигурацию без согласования.", listOf("win-ip", "linux-ip", "linux-route")),
            ScenarioStep("dns", "Разрешение имени", "Сравните имя и полученный адрес с ожидаемыми. Для AD используйте корпоративный DNS. В разделе «DNS» можно явно выбрать сервер; не отправляйте внутренние имена публичным DNS без разрешения.", listOf("win-dns", "linux-dns")),
            ScenarioStep("transport", "Доступность службы", "Если проверка разрешена, проверьте конкретный TCP-порт и ответ службы; Ping — лишь отдельный сигнал. Зафиксируйте источник, цель, порт и результат.", listOf("ps-port", "win-ping"))
        )),
        DiagnosticScenario("domain", "Домен / AD", "Для рабочих станций и рядовых серверов Windows. Если это контроллер домена, не используйте тест защищённого канала члена домена; эскалируйте специалисту AD.", listOf(
            ScenarioStep("scope", "Роль компьютера и симптомы", "Уточните, является ли узел членом домена или контроллером, кто затронут, когда началось и какой текст ошибки. Обеспечьте согласованный локальный административный доступ."),
            ScenarioStep("discovery", "Сеть, DNS и контроллер", "На проблемном члене домена проверьте сеть/VPN, корпоративные DNS и обнаружение контроллера. При необходимости проверьте AD SRV в инструменте «DNS».", listOf("win-ip", "ad-dc-discovery")),
            ScenarioStep("time", "Время", "На проблемном компьютере проверьте источник времени и состояние синхронизации; сравните часы с контроллером. Самостоятельно синхронизацию не запускайте.", listOf("ad-time")),
            ScenarioStep("trust", "Защищённый канал", "Только для члена домена, не для контроллера: после проверки DNS, времени и сети запустите проверку от администратора. Ошибка не является разрешением на автоматический ремонт.", listOf("ad-trust-check")),
            ScenarioStep("escalate", "События и эскалация", "Зафиксируйте результат и ошибки Netlogon/политик. Если проблема не ясна или затронут контроллер — передайте специалисту AD; ремонт доверия и изменения согласовываются отдельно.", listOf("ad-domain-events"))
        )),
        DiagnosticScenario("printer", "Принтер", "Учитывайте путь печати: USB, прямой IP или общий принтер через сервер. Не очищайте очередь и не перезапускайте службу без согласования.", listOf(
            ScenarioStep("scope", "Где проявляется сбой", "Запишите модель, способ подключения, точную ошибку, время и затронутых пользователей. Уточните, печатает ли другой клиент и тестовая страница устройства."),
            ScenarioStep("queue", "Очередь и состояние", "Проверьте очередь задания, состояние бумаги и панели устройства; сравните имя и порт на клиенте и при необходимости на сервере печати. Не удаляйте задания автоматически.", extraCommands = listOf(printerList)),
            ScenarioStep("spooler", "Диспетчер печати", "Проверьте состояние Spooler на клиенте и, если используется, на сервере печати. Перезапуск может прервать работу других пользователей.", extraCommands = listOf(spooler)),
            ScenarioStep("network", "Сеть и дальнейшие действия", "Для сетевого принтера проверьте адрес и доступность разрешённого порта. Нет ответа на Ping не доказывает отказ; при неясной причине передайте результаты ответственному.", listOf("ps-port"))
        )),
        DiagnosticScenario("storage", "Диск / место", "Сначала определите затронутый том и критичность. При признаках отказа оборудования прекратите рискованные операции и эскалируйте; наличие бэкапа нужно проверить отдельно.", listOf(
            ScenarioStep("scope", "Том и влияние", "Укажите сервер/ПК, том или точку монтирования, ошибку, время и затронутые сервисы. Уточните статус резервной копии и последней проверки восстановления."),
            ScenarioStep("space", "Свободное место", "Проверьте свободное место на затронутом томе и, для Linux, inode. Не удаляйте файлы без определения владельца и согласования.", listOf("ps-space", "linux-disk", "linux-inodes")),
            ScenarioStep("layout", "Тома и устройства", "Сопоставьте том, файловую систему и устройство; состояние тома само по себе не подтверждает исправность оборудования или бэкапа.", listOf("server-volumes", "linux-block")),
            ScenarioStep("events", "События и эскалация", "Проверьте ошибки диска и файловой системы, зафиксируйте время и коды. Не запускайте восстановление, форматирование или нагрузочные тесты без оценки риска.", listOf("ps-events", "linux-journal"))
        )),
        DiagnosticScenario("service", "Служба / сервис", "Проверяйте состояние и зависимости до любых изменений. Перезапуск может прервать пользователей и скрыть причину сбоя.", listOf(
            ScenarioStep("scope", "Симптомы и влияние", "Запишите имя службы, узел, время, ошибку, число затронутых пользователей и последние изменения. Согласуйте диагностику на продуктивном сервере."),
            ScenarioStep("status", "Состояние службы", "Определите системное имя, состояние и зависимости. Остановленная служба не обязательно неисправна; на Linux замените пример имени на нужный.", listOf("linux-service-status"), listOf(windowsServices)),
            ScenarioStep("logs", "Журналы и ресурсы", "Изучите недавние ошибки и доступность ресурсов. Замените имя Linux-службы в примере; журналы могут содержать конфиденциальные сведения.", listOf("ps-events", "linux-service-journal", "ps-space", "linux-disk")),
            ScenarioStep("next", "Результат и эскалация", "Зафиксируйте состояние, коды ошибок и кому передано. Перезапуск и любые изменения — только после оценки последствий и отдельного согласования.")
        ))
    )

    fun formatReport(scenario: DiagnosticScenario, context: String, results: Map<String, ScenarioStepResult>, next: String): String = buildString {
        appendLine("Первичная диагностика: ${scenario.title}")
        appendLine("Объект и симптомы: ${context.trim().ifBlank { "не указано" }}")
        scenario.steps.forEachIndexed { index, step ->
            val result = results[step.id] ?: ScenarioStepResult()
            appendLine("${index + 1}. ${step.title}: ${result.status.label}")
            if (result.note.isNotBlank()) appendLine("   ${result.note.trim()}")
        }
        append("Дальнейшие действия / эскалация: ${next.trim().ifBlank { "не указано" }}")
    }
}
