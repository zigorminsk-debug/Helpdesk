package com.helpdesk.jiraagent.domain

/** Offline reference entries: nothing here is executed by Help-CSL. */
object SysadminServerCommands {
    val entries: List<SysadminCommand> = listOf(
        SysadminCommand("ad-identity", CommandPlatform.WINDOWS, "Текущий пользователь и группы", "Показывает SID, группы и привилегии текущего сеанса; полезно при проблемах доступа к серверу или ресурсу.", "Откройте cmd от имени проверяемого пользователя и выполните команду.", "Только чтение; не отправляйте полный вывод сторонним лицам.", "whoami /all", CommandTopic.DOMAIN),
        SysadminCommand("ad-gpo", CommandPlatform.WINDOWS, "Применённые групповые политики", "Показывает применённые политики и источник для текущего компьютера и пользователя.", "На проблемном ПК откройте cmd от администратора. При необходимости проверьте политики в сеансе нужного пользователя.", "Только чтение; набор доступных данных зависит от прав и сеанса.", "gpresult /r", CommandTopic.DOMAIN),
        SysadminCommand("ad-time", CommandPlatform.WINDOWS, "Источник времени и синхронизация", "Показывает источник и состояние службы времени; расхождение часов может мешать Kerberos и входу в домен.", "На проблемном ПК выполните команду в cmd; сравните время с контроллером домена.", "Только чтение; не меняет источник времени и не запускает синхронизацию.", "w32tm /query /status", CommandTopic.DOMAIN),
        SysadminCommand("ad-dc-services", CommandPlatform.WINDOWS, "Службы контроллера домена", "Проверка служб контроллера домена с помощью dcdiag.", "На контроллере домена откройте cmd от администратора; нужны инструменты AD DS / RSAT. Изучите ошибки теста и журнал событий.", "Диагностика; команда применима к контроллеру домена и может занять время.", "dcdiag /test:services", CommandTopic.DOMAIN),
        SysadminCommand("ad-replication", CommandPlatform.WINDOWS, "Сводка репликации AD", "Показывает сводные ошибки репликации между контроллерами домена.", "На машине с RSAT / AD DS откройте cmd с подходящими правами домена и выполните команду.", "Диагностика; запрашивает сведения у контроллеров, не запускает принудительную репликацию.", "repadmin /replsummary", CommandTopic.DOMAIN),
        SysadminCommand("ad-dc-discovery", CommandPlatform.POWERSHELL, "Поиск контроллера своего домена", "Определяет домен компьютера и просит Windows найти его контроллер; помогает проверить DNS и доступность AD.", "На проблемном ПК откройте Windows PowerShell и вставьте блок целиком. Устройство должно быть включено в домен и иметь доступ к корпоративной сети или VPN.", "Диагностика; поиск может использовать кэш Windows. Не меняет членство в домене.", """
            & {
                ${'$'}computer = Get-CimInstance Win32_ComputerSystem -ErrorAction Stop
                if (-not ${'$'}computer.PartOfDomain) { Write-Warning 'Компьютер не состоит в домене AD.'; return }
                Write-Host "Домен компьютера: ${'$'}(${'$'}computer.Domain)"
                & nltest.exe "/dsgetdc:${'$'}(${'$'}computer.Domain)"
                if (${'$'}LASTEXITCODE -ne 0) { Write-Warning 'Контроллер не найден; проверьте корпоративный DNS и VPN.' }
            }
        """.trimIndent(), CommandTopic.DOMAIN),
        SysadminCommand("ad-trust-check", CommandPlatform.POWERSHELL, "Проверить доверие компьютера к домену", "Проверяет защищённый канал между доменным компьютером и AD перед восстановлением доверительных отношений.", "Войдите под локальной административной учётной записью на проблемном члене домена; подключите корпоративную сеть/VPN, проверьте DNS и время. Запустите Windows PowerShell от администратора и вставьте блок.", "Только диагностика для рабочих станций и рядовых серверов Windows. Не используйте этот тест для контроллера домена: возможен ложный результат.", """
            & {
                try {
                    ${'$'}computer = Get-CimInstance Win32_ComputerSystem -ErrorAction Stop
                    if (-not ${'$'}computer.PartOfDomain) { Write-Warning 'Компьютер не состоит в домене AD.'; return }
                    if ([int]${'$'}computer.DomainRole -ge 4) { Write-Warning 'Это контроллер домена: применяйте диагностику AD/DC, а не этот тест.'; return }
                    Write-Host "Компьютер: ${'$'}(${'$'}computer.Name); домен: ${'$'}(${'$'}computer.Domain)"
                    ${'$'}ok = Test-ComputerSecureChannel -ErrorAction Stop
                    if (${'$'}ok) { Write-Host 'Защищённый канал исправен.' }
                    else { Write-Warning 'Защищённый канал не прошёл проверку. Проверьте DNS, время, сеть и доступность DC.' }
                } catch { Write-Warning "Проверка не завершена: ${'$'}_" }
            }
        """.trimIndent(), CommandTopic.DOMAIN),
        SysadminCommand("ad-trust-repair", CommandPlatform.POWERSHELL, "Восстановить доверие компьютера к домену", "Восстанавливает защищённый канал рядового компьютера с доменом без вывода из домена, используя разрешённую доменную учётную запись.", "На проблемном ПК или рядовом сервере: обеспечьте доступ к локальной учётной записи администратора и корпоративной сети/VPN; проверьте DNS, время и контроллер. Запустите Windows PowerShell от локального администратора, вставьте блок, проверьте сведения и введите YES. В окне учётных данных укажите доменную учётную запись с делегированным правом сброса пароля учётной записи этого компьютера. Затем повторите проверку доверия и вход в домен.", "Изменяет пароль учётной записи компьютера и защищённый канал в AD. Только для доменных рабочих станций и рядовых серверов, НЕ для контроллеров домена. Возможны обрыв доменного сеанса и последствия для сервисов: согласуйте окно обслуживания, проверьте резервную копию и доступ через консоль. Пароль вводится только в системное окно, не вставляйте его в команду.", """
            & {
                try {
                    ${'$'}computer = Get-CimInstance Win32_ComputerSystem -ErrorAction Stop
                    if (-not ${'$'}computer.PartOfDomain) { Write-Warning 'Компьютер не состоит в домене AD.'; return }
                    if ([int]${'$'}computer.DomainRole -ge 4) { Write-Warning 'Контроллер домена: этот способ запрещён. Используйте диагностику AD/DC.'; return }
                    Write-Host "Компьютер: ${'$'}(${'$'}computer.Name); домен: ${'$'}(${'$'}computer.Domain)"
                    Write-Warning 'Будет изменён пароль учётной записи компьютера в AD. Проверьте сеть, DNS, время и локальный доступ.'
                    ${'$'}confirm = Read-Host 'Восстановить защищённый канал? Введите YES для подтверждения'
                    if (${'$'}confirm -cne 'YES') { Write-Host 'Отменено; изменения не внесены.'; return }
                    ${'$'}credential = Get-Credential -Message 'Доменная учётная запись с правом сброса пароля этого компьютера'
                    if (-not ${'$'}credential) { Write-Host 'Учётные данные не введены; отменено.'; return }
                    ${'$'}ok = Test-ComputerSecureChannel -Repair -Credential ${'$'}credential -ErrorAction Stop
                    if (${'$'}ok) { Write-Host 'Канал восстановлен. Проверьте вход в домен и связанные службы.' }
                    else { Write-Warning 'Восстановление не подтвердилось. Проверьте подключение к DC, права и события системы.' }
                } catch { Write-Warning "Восстановление не завершено: ${'$'}_" }
                finally { ${'$'}credential = ${'$'}null }
            }
        """.trimIndent(), CommandTopic.DOMAIN, changesSystem = true),
        SysadminCommand("ad-domain-events", CommandPlatform.POWERSHELL, "Ошибки Netlogon и групповых политик", "Показывает последние ошибки системного журнала от Netlogon и GroupPolicy.", "Запустите Windows PowerShell на проблемной машине; при отказе в доступе откройте от администратора.", "Только чтение; при отсутствии событий команда может сообщить, что совпадений нет. В журналах могут быть внутренние имена.", """
            Get-WinEvent -FilterHashtable @{ LogName='System'; ProviderName='NETLOGON','Microsoft-Windows-GroupPolicy'; Level=2 } -MaxEvents 30 -ErrorAction Stop |
                Select-Object TimeCreated, ProviderName, Id, Message | Format-List
        """.trimIndent(), CommandTopic.DOMAIN),

        SysadminCommand("server-uptime", CommandPlatform.POWERSHELL, "Время работы Windows-сервера", "Показывает последнюю загрузку и время работы локального компьютера или сервера.", "Вставьте блок в Windows PowerShell на нужном узле.", "Только чтение; требуется CIM / WMI.", """
            & {
                ${'$'}os = Get-CimInstance Win32_OperatingSystem -ErrorAction Stop
                ${'$'}os | Select-Object CSName, Caption, LastBootUpTime
                Write-Host "Время работы: ${'$'}((Get-Date) - ${'$'}os.LastBootUpTime)"
            }
        """.trimIndent(), CommandTopic.SERVER),
        SysadminCommand("server-auto-services", CommandPlatform.POWERSHELL, "Остановленные автоматические службы", "Показывает службы автоматического запуска, которые сейчас не работают.", "Выполните в PowerShell на сервере. Проверьте назначение службы и её зависимости перед действиями.", "Только чтение; часть автоматических служб может быть остановлена штатно, это не автоматический диагноз.", """
            Get-CimInstance Win32_Service -Filter "StartMode='Auto' AND State='Stopped'" |
                Select-Object Name, DisplayName, StartMode, State | Format-Table -AutoSize
        """.trimIndent(), CommandTopic.SERVER),
        SysadminCommand("server-features", CommandPlatform.POWERSHELL, "Роли и компоненты Windows Server", "Показывает установленные роли и компоненты Windows Server.", "Откройте Windows PowerShell на Windows Server с модулем ServerManager и выполните блок.", "Только чтение; Get-WindowsFeature недоступна на обычных Windows 10/11 без соответствующих средств.", "Get-WindowsFeature | Where-Object Installed | Format-Table Name, DisplayName -AutoSize", CommandTopic.SERVER),
        SysadminCommand("server-top-memory", CommandPlatform.POWERSHELL, "Процессы с наибольшим потреблением памяти", "Выводит 15 процессов с максимальным текущим рабочим набором памяти.", "Вставьте блок в PowerShell; повторите измерение, чтобы сравнить динамику.", "Только чтение; рабочий набор не равен полной выделенной памяти процесса.", """
            Get-Process | Sort-Object WorkingSet64 -Descending | Select-Object -First 15 Name, Id,
                @{Name='Рабочий набор МБ'; Expression={[math]::Round(${'$'}_.WorkingSet64 / 1MB, 1)}} | Format-Table -AutoSize
        """.trimIndent(), CommandTopic.SERVER),
        SysadminCommand("server-volumes", CommandPlatform.POWERSHELL, "Тома и свободное место", "Показывает тома Windows, файловую систему, состояние и свободное место.", "Откройте Windows PowerShell на сервере и выполните команду; требуется модуль Storage.", "Только чтение; состояние HealthStatus не заменяет проверку резервного копирования и оборудования.", "Get-Volume | Select-Object DriveLetter, FileSystemLabel, FileSystem, HealthStatus, SizeRemaining, Size | Format-Table -AutoSize", CommandTopic.STORAGE),
        SysadminCommand("server-smb", CommandPlatform.POWERSHELL, "Общие папки SMB", "Показывает локальные общие папки сервера и их пути.", "Выполните в Windows PowerShell на файловом сервере с модулем SmbShare; для полного списка могут потребоваться права администратора.", "Только чтение; список может включать административные ресурсы и конфиденциальные пути.", "Get-SmbShare | Select-Object Name, Path, Description | Format-Table -AutoSize", CommandTopic.SERVER),
        SysadminCommand("server-restart-pending", CommandPlatform.POWERSHELL, "Признаки ожидающей перезагрузки", "Проверяет распространённые ключи CBS и Windows Update для определения возможного ожидания перезагрузки.", "Откройте Windows PowerShell на нужном ПК или сервере и выполните блок.", "Только чтение; отсутствие этих ключей не гарантирует, что перезагрузка не требуется по другой причине.", """
            & {
                ${'$'}cbs = Test-Path 'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Component Based Servicing\RebootPending'
                ${'$'}update = Test-Path 'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\WindowsUpdate\Auto Update\RebootRequired'
                [pscustomobject]@{ CBS = ${'$'}cbs; WindowsUpdate = ${'$'}update }
            }
        """.trimIndent(), CommandTopic.SERVER),
        SysadminCommand("server-dism-health", CommandPlatform.WINDOWS, "Проверка хранилища компонентов Windows", "DISM проверяет, отмечено ли хранилище компонентов как повреждённое; ничего не восстанавливает.", "Откройте cmd от имени администратора на Windows или Windows Server и дождитесь завершения.", "Диагностика; /CheckHealth проверяет имеющуюся отметку и не выполняет полное сканирование или восстановление.", "DISM /Online /Cleanup-Image /CheckHealth", CommandTopic.REPAIR),

        SysadminCommand("linux-realm", CommandPlatform.LINUX, "Доменная интеграция Linux", "Показывает настроенные домены и режим интеграции realmd, если этот инструмент используется.", "На доменном Linux-сервере выполните команду в терминале. Требуется пакет realmd; наличие записи не подтверждает исправность связи с контроллером.", "Только чтение; не подключает и не отключает компьютер от домена.", "realm list", CommandTopic.DOMAIN),
        SysadminCommand("linux-kerberos", CommandPlatform.LINUX, "Билеты Kerberos текущего пользователя", "Показывает кэш и сроки действия Kerberos-билетов текущего пользователя.", "Выполните в сеансе проверяемого пользователя; требуется утилита klist из пакета Kerberos.", "Только чтение; не создаёт, не обновляет и не удаляет билеты. Не передавайте вывод посторонним.", "klist", CommandTopic.DOMAIN),
        SysadminCommand("linux-chrony", CommandPlatform.LINUX, "Синхронизация времени chrony", "Показывает источник, смещение и состояние синхронизации сервера chrony.", "Запустите в терминале Linux-сервера, использующего chrony; при необходимости проверьте доступ к chronyd.", "Только чтение; команды chronyc и работающий chronyd требуются.", "chronyc tracking", CommandTopic.SERVER),
        SysadminCommand("linux-uptime", CommandPlatform.LINUX, "Время работы и нагрузка Linux", "Показывает длительность работы и среднюю нагрузку за 1, 5 и 15 минут.", "Запустите на Linux-сервере в терминале; интерпретируйте нагрузку с учётом числа CPU и ожидания ввода-вывода.", "Только чтение.", "uptime", CommandTopic.SERVER),
        SysadminCommand("linux-block", CommandPlatform.LINUX, "Диски и разделы", "Показывает блочные устройства, файловые системы, UUID и точки монтирования.", "Выполните команду на Linux-сервере; используйте вывод для проверки устройства до любых работ с диском.", "Только чтение; UUID и структура дисков могут быть конфиденциальными.", "lsblk -f", CommandTopic.STORAGE),
        SysadminCommand("linux-mounts", CommandPlatform.LINUX, "Смонтированные файловые системы", "Показывает используемые источники, файловые системы и точки монтирования.", "Выполните команду в терминале на сервере; требуется утилита findmnt.", "Только чтение; никакое монтирование не меняется.", "findmnt -D", CommandTopic.STORAGE),
        SysadminCommand("linux-topmem", CommandPlatform.LINUX, "Процессы с наибольшим потреблением памяти", "Показывает 15 процессов с максимальной долей используемой памяти.", "Вставьте команду в Bash; для полной картины могут понадобиться дополнительные права.", "Только чтение; имена и аргументы процессов могут содержать конфиденциальные данные.", "ps aux --sort=-%mem | head -n 16", CommandTopic.SERVER),
        SysadminCommand("linux-timedate", CommandPlatform.LINUX, "Часы и синхронизация Linux", "Показывает системное время, часовой пояс и сведения о синхронизации на системах с systemd.", "Откройте терминал и выполните команду; при проблемах с доменом сравните время с контроллером.", "Только чтение; не включает и не настраивает NTP.", "timedatectl status", CommandTopic.SERVER),
        SysadminCommand("linux-service-status", CommandPlatform.LINUX, "Состояние службы на Linux", "Выводит состояние и последние сообщения выбранной службы systemd.", "Замените ssh.service на действительное имя службы вашей системы (например, sshd.service) и выполните команду.", "Только чтение; на некоторых дистрибутивах имя службы отличается.", "systemctl status ssh.service --no-pager", CommandTopic.SERVER),
        SysadminCommand("linux-service-journal", CommandPlatform.LINUX, "Журнал службы Linux", "Показывает последние 100 событий выбранной службы systemd.", "Замените ssh.service на нужное имя и выполните команду; при отказе в доступе используйте sudo, если он разрешён.", "Только чтение; журнал может содержать конфиденциальные сведения.", "journalctl -u ssh.service -n 100 --no-pager", CommandTopic.SERVER)
    )
}
