package com.helpdesk.jiraagent.domain

/** An offline reference, not a port scanner or a recommendation to open these ports. */
data class ServicePort(val id: String, val service: String, val port: String, val use: String, val note: String)

object ServicePortReference {
    val entries = listOf(
        ServicePort("dns", "DNS", "53 TCP/UDP", "Разрешение имён и запросы DNS.", "TCP также используется для крупных ответов и передачи зон; доступ к зонам должен быть ограничен."),
        ServicePort("kerberos", "Kerberos / AD", "88 TCP/UDP", "Аутентификация Kerberos в домене.", "Проверяйте корпоративный DNS и синхронизацию времени."),
        ServicePort("ldap", "LDAP", "389 TCP/UDP", "Каталог Active Directory и LDAP.", "Для защиты учётных данных используйте согласованные защищённые методы подключения."),
        ServicePort("ldaps", "LDAPS", "636 TCP", "LDAP поверх TLS.", "Нужен действительный сертификат контроллера и доверенный центр сертификации."),
        ServicePort("global", "Глобальный каталог AD", "3268 / 3269 TCP", "Поиск в глобальном каталоге; 3269 — TLS.", "Доступен на контроллерах с ролью глобального каталога."),
        ServicePort("smb", "SMB", "445 TCP", "Доступ к сетевым папкам и общим ресурсам Windows.", "Не открывайте SMB в интернет; ограничьте доступ доверенными сетями."),
        ServicePort("rpc", "RPC Endpoint Mapper", "135 TCP", "Начальное согласование ряда служб Windows RPC.", "Часто дополнительно требуются динамические RPC-порты; одного 135 недостаточно."),
        ServicePort("rdp", "RDP", "3389 TCP/UDP", "Удалённый рабочий стол Windows.", "UDP может использоваться дополнительно. Не публикуйте RDP напрямую в интернет."),
        ServicePort("ssh", "SSH", "22 TCP", "Удалённая консоль и передача файлов SSH/SFTP.", "На конкретном сервере порт может быть изменён; используйте доверенные ключи."),
        ServicePort("http", "HTTP", "80 TCP", "Веб-сервис без TLS и перенаправления.", "HTTP не шифрует данные, не передавайте пароли открытым текстом."),
        ServicePort("https", "HTTPS", "443 TCP", "Веб-сервис поверх TLS.", "Проверяйте имя и срок сертификата; HTTP/3 может дополнительно использовать UDP 443."),
        ServicePort("ntp", "NTP", "123 UDP", "Синхронизация времени.", "На доменных системах учитывайте иерархию источников времени."),
        ServicePort("smtp", "SMTP / Submission", "25 / 587 TCP", "25 — обмен почтовых серверов; 587 — отправка клиентом.", "Параметры TLS и авторизации зависят от сервера и его политики."),
        ServicePort("imap", "IMAPS", "993 TCP", "Получение почты IMAP через TLS.", "Проверяйте сертификат и настройки конкретного почтового сервера."),
        ServicePort("winrm", "WinRM", "5985 / 5986 TCP", "Удалённое управление Windows; 5986 — HTTPS.", "Применяйте только в доверенной сети и по корпоративной политике."),
        ServicePort("zabbix", "Zabbix agent", "10050 TCP", "Обычно агент принимает запросы сервера или прокси.", "Активные и пассивные проверки используют разные направления; порт может быть изменён.")
    )

    fun search(query: String): List<ServicePort> {
        val terms = query.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        return entries.filter { entry -> terms.all { term ->
            listOf(entry.service, entry.port, entry.use, entry.note).any { it.contains(term, ignoreCase = true) }
        } }
    }
}
