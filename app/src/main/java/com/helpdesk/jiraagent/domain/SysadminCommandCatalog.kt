package com.helpdesk.jiraagent.domain

/** Reference material only. The application never executes these commands. */
enum class CommandPlatform(val label: String) {
    WINDOWS("Windows CMD"), LINUX("Linux / Bash"), POWERSHELL("PowerShell")
}

data class SysadminCommand(
    val id: String,
    val platform: CommandPlatform,
    val title: String,
    val description: String,
    val instructions: String,
    val impact: String,
    val command: String
)

object SysadminCommandCatalog {
    val entries: List<SysadminCommand> = listOf(
        SysadminCommand("win-ip", CommandPlatform.WINDOWS, "IP-адреса и DNS", "Показывает адреса, шлюзы и DNS всех сетевых адаптеров.", "Откройте командную строку (cmd), вставьте команду и нажмите Enter.", "Только чтение; права администратора не требуются.", "ipconfig /all"),
        SysadminCommand("win-dns", CommandPlatform.WINDOWS, "Проверка DNS", "Проверяет, какой IP-адрес возвращает DNS-сервер для имени.", "Откройте cmd. После запуска введите имя узла и нажмите Enter; для выхода наберите exit.", "Только чтение; введите имя проверяемого узла.", "nslookup"),
        SysadminCommand("win-route", CommandPlatform.WINDOWS, "Таблица маршрутов", "Показывает маршруты и интерфейсы для диагностики VPN и шлюза.", "Откройте cmd и вставьте команду.", "Только чтение.", "route print"),
        SysadminCommand("win-ping", CommandPlatform.WINDOWS, "Проверка доступности узла", "Отправляет четыре ICMP-запроса указанному хосту.", "Откройте cmd и замените example.com на адрес проверяемого узла.", "Только диагностика; отсутствие ответа может означать блокировку ICMP.", "ping -n 4 example.com"),
        SysadminCommand("win-trace", CommandPlatform.WINDOWS, "Трассировка маршрута", "Показывает промежуточные узлы по пути к серверу.", "Откройте cmd и замените example.com на нужное имя или IP.", "Только диагностика; некоторые узлы не отвечают на запросы.", "tracert -d example.com"),
        SysadminCommand("win-ports", CommandPlatform.WINDOWS, "Порты и процессы", "Выводит открытые сетевые соединения, слушающие порты и PID процессов.", "Откройте cmd и вставьте команду; PID можно найти в диспетчере задач.", "Только чтение; вывод может содержать внутренние адреса.", "netstat -ano"),
        SysadminCommand("win-disk", CommandPlatform.WINDOWS, "Проверка диска без исправлений", "Запускает онлайн-проверку файловой системы тома C:.", "Откройте cmd от имени администратора. Для другого тома замените C:.", "Без параметров исправления; проверка может занять время и не заменяет резервную копию.", "chkdsk C:"),
        SysadminCommand("win-sfc", CommandPlatform.WINDOWS, "Проверка и восстановление системных файлов", "SFC проверяет целостность защищённых файлов Windows и пытается их восстановить.", "Сохраните работу, откройте cmd от имени администратора и дождитесь завершения.", "Изменяет системные файлы. На сервере согласуйте окно обслуживания и убедитесь в наличии резервной копии.", "sfc /scannow"),

        SysadminCommand("linux-ip", CommandPlatform.LINUX, "Адреса сетевых интерфейсов", "Показывает состояние интерфейсов и назначенные IP-адреса.", "Откройте Bash или совместимую оболочку и вставьте команду.", "Только чтение; нужен пакет iproute2.", "ip -br address"),
        SysadminCommand("linux-route", CommandPlatform.LINUX, "Маршруты и шлюз", "Показывает таблицу маршрутизации IPv4, включая маршрут по умолчанию.", "Откройте терминал на нужном узле и вставьте команду.", "Только чтение; нужен iproute2.", "ip route show"),
        SysadminCommand("linux-dns", CommandPlatform.LINUX, "DNS через системный резолвер", "Ищет адреса имени, используя стандартные настройки разрешения имён системы.", "Замените example.com на имя сервера и выполните в терминале.", "Только чтение; результат может включать записи из /etc/hosts.", "getent ahosts example.com"),
        SysadminCommand("linux-ping", CommandPlatform.LINUX, "Проверка доступности", "Отправляет четыре ICMP-запроса узлу и показывает потери и задержку.", "Замените example.com на адрес проверяемого устройства.", "Только диагностика; блокировка ICMP не означает отказ службы.", "ping -c 4 example.com"),
        SysadminCommand("linux-disk", CommandPlatform.LINUX, "Свободное место на дисках", "Показывает занятость подключённых файловых систем.", "Вставьте команду в терминал; обратите внимание на столбец Use%.", "Только чтение.", "df -hT"),
        SysadminCommand("linux-inodes", CommandPlatform.LINUX, "Свободные inode", "Помогает найти исчерпание inode, когда место на диске ещё есть.", "Выполните команду на проблемном сервере.", "Только чтение.", "df -i"),
        SysadminCommand("linux-memory", CommandPlatform.LINUX, "Память и swap", "Показывает общую, доступную и используемую оперативную память и swap.", "Откройте терминал и вставьте команду.", "Только чтение; утилита free обычно входит в procps.", "free -h"),
        SysadminCommand("linux-services", CommandPlatform.LINUX, "Неисправные службы systemd", "Выводит службы в состоянии failed.", "Выполните команду на системе с systemd. Для деталей используйте systemctl status ИМЯ.", "Только чтение; список зависит от прав пользователя.", "systemctl --failed --type=service --no-pager"),
        SysadminCommand("linux-journal", CommandPlatform.LINUX, "Ошибки текущей загрузки", "Показывает ошибки и более критичные события systemd journal текущей загрузки.", "Выполните в терминале. Если доступ запрещён, повторите с sudo при наличии прав.", "Только чтение; журналы могут содержать конфиденциальные сведения.", "journalctl -b -p err --no-pager -n 100"),
        SysadminCommand("linux-ports", CommandPlatform.LINUX, "Слушающие TCP/UDP-порты", "Показывает локальные адреса и порты ожидающих соединений служб.", "Выполните команду в терминале; для просмотра процессов могут потребоваться повышенные права.", "Только чтение; нужен iproute2.", "ss -tuln"),

        SysadminCommand("ps-network", CommandPlatform.POWERSHELL, "Диагностика сети и DNS", "Собирает IP-конфигурацию, маршруты по умолчанию и DNS-серверы Windows.", "Откройте PowerShell на диагностируемом ПК, вставьте весь блок и нажмите Enter.", "Только чтение; доступность команд зависит от версии Windows.", """
            Get-NetIPConfiguration | Format-List
            Get-NetRoute -DestinationPrefix '0.0.0.0/0' -ErrorAction SilentlyContinue | Format-Table -AutoSize
            Get-DnsClientServerAddress | Format-Table -AutoSize
        """.trimIndent()),
        SysadminCommand("ps-port", CommandPlatform.POWERSHELL, "Проверка TCP-порта сервера", "Проверяет DNS и возможность открыть TCP-порт указанного сервера.", "Откройте PowerShell, вставьте блок, затем введите имя/IP и номер порта, например 443.", "Только диагностика; убедитесь, что проверка разрешена сетевыми правилами.", """
            & {
                ${'$'}targetHost = Read-Host 'Имя или IP сервера'
                ${'$'}portText = Read-Host 'TCP-порт (1-65535)'
                ${'$'}port = 0
                if ([string]::IsNullOrWhiteSpace(${'$'}targetHost) -or -not [int]::TryParse(${'$'}portText, [ref]${'$'}port) -or ${'$'}port -lt 1 -or ${'$'}port -gt 65535) {
                    Write-Host 'Неверный адрес или порт.'
                } else {
                    Test-NetConnection -ComputerName ${'$'}targetHost -Port ${'$'}port
                }
            }
        """.trimIndent()),
        SysadminCommand("ps-service", CommandPlatform.POWERSHELL, "Проверка и перезапуск службы", "Показывает службу и предлагает перезапустить её для устранения сбоя.", "Запустите PowerShell от администратора, вставьте блок, введите системное имя службы и ознакомьтесь с её состоянием. Перезапуск выполняется только после ввода YES.", "Изменяет состояние службы и может прервать работу пользователей или зависимых служб. Сначала согласуйте обслуживание и проверьте зависимости.", """
            & {
                ${'$'}name = Read-Host 'Системное имя службы (например, Spooler)'
                ${'$'}service = Get-Service -Name ${'$'}name -ErrorAction SilentlyContinue
                if (-not ${'$'}service) {
                    Write-Host 'Служба не найдена.'
                } else {
                    ${'$'}service | Format-List Name, DisplayName, Status, DependentServices
                    ${'$'}confirm = Read-Host 'Перезапустить службу? Введите YES для подтверждения'
                    if (${'$'}confirm -ceq 'YES') {
                        try {
                            Restart-Service -Name ${'$'}service.Name -ErrorAction Stop
                            Get-Service -Name ${'$'}service.Name | Format-Table Name, Status
                        } catch { Write-Warning "Не удалось перезапустить службу: ${'$'}_" }
                    } else { Write-Host 'Отменено; изменения не внесены.' }
                }
            }
        """.trimIndent()),
        SysadminCommand("ps-dnsflush", CommandPlatform.POWERSHELL, "Очистка локального DNS-кэша", "Очищает локальный кэш DNS-клиента, если адрес изменился, а Windows продолжает использовать старую запись.", "Откройте PowerShell от администратора, вставьте блок и введите YES только после проверки ситуации.", "Изменяет локальный кэш; не меняет DNS-сервер или его записи. Новые запросы будут отправлены заново.", """
            & {
                ${'$'}confirm = Read-Host 'Очистить локальный DNS-кэш? Введите YES для подтверждения'
                if (${'$'}confirm -ceq 'YES') {
                    try { Clear-DnsClientCache -ErrorAction Stop; Write-Host 'Локальный DNS-кэш очищен.' }
                    catch { Write-Warning "Не удалось очистить кэш: ${'$'}_" }
                } else { Write-Host 'Отменено; изменения не внесены.' }
            }
        """.trimIndent()),
        SysadminCommand("ps-events", CommandPlatform.POWERSHELL, "Последние системные ошибки", "Выводит до 50 последних ошибок журнала System для диагностики компьютера или сервера.", "Вставьте блок в PowerShell. Если доступ запрещён, откройте PowerShell от администратора.", "Только чтение; перед передачей журнала проверьте его на конфиденциальные данные.", """
            Get-WinEvent -FilterHashtable @{ LogName = 'System'; Level = 2 } -MaxEvents 50 -ErrorAction Stop |
                Select-Object TimeCreated, ProviderName, Id, Message | Format-List
        """.trimIndent()),
        SysadminCommand("ps-space", CommandPlatform.POWERSHELL, "Место на локальных дисках", "Показывает объём и свободное пространство локальных дисков в гигабайтах.", "Вставьте весь блок в PowerShell на нужном компьютере или сервере.", "Только чтение; сетевые диски в отчёт не входят.", """
            Get-CimInstance Win32_LogicalDisk -Filter "DriveType=3" |
                Select-Object DeviceID, VolumeName,
                    @{Name='Размер ГБ'; Expression={[math]::Round(${'$'}_.Size / 1GB, 1)}},
                    @{Name='Свободно ГБ'; Expression={[math]::Round(${'$'}_.FreeSpace / 1GB, 1)}} |
                Format-Table -AutoSize
        """.trimIndent())
    )

    fun search(query: String, platform: CommandPlatform? = null): List<SysadminCommand> {
        val words = query.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        return entries.filter { entry ->
            (platform == null || entry.platform == platform) && words.all { word ->
                listOf(entry.platform.label, entry.title, entry.description, entry.instructions, entry.impact, entry.command)
                    .any { it.contains(word, ignoreCase = true) }
            }
        }
    }
}
