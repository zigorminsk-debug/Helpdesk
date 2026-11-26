package com.helpdesk.jiraagent.model

enum class JiraServerType(val title: String, val subtitle: String) {
    AUTO("Автоопределение", "Cloud для *.atlassian.net, иначе Server / Data Center"),
    SERVER_DC("Jira Server / Data Center", "REST API v2 — корпоративный сервер"),
    CLOUD("Jira Cloud", "REST API v3 — облако Atlassian")
}

enum class AuthType(val title: String, val loginLabel: String, val secretLabel: String) {
    BASIC_LOGIN_PASSWORD(
        title = "Логин + Пароль (Basic)",
        loginLabel = "Логин Jira",
        secretLabel = "Пароль"
    ),
    CLOUD_EMAIL_TOKEN(
        title = "Email + API Token (Cloud)",
        loginLabel = "Email аккаунта Atlassian",
        secretLabel = "API Token"
    ),
    BEARER_PAT(
        title = "Personal Access Token (PAT / Bearer)",
        loginLabel = "Логин (необязательно, для фильтра своих заявок)",
        secretLabel = "Personal Access Token"
    )
}

enum class PhoneRegion(val title: String, val defaultDialCode: String, val example: String) {
    AUTO_BY_RU(
        title = "Авто: Беларусь (+375) и Россия (+7)",
        defaultDialCode = "+375",
        example = "8029… → +37529…, 8916… → +7916…"
    ),
    BELARUS_375(
        title = "Беларусь (+375)",
        defaultDialCode = "+375",
        example = "8 (029) 123-45-67 → +375291234567"
    ),
    RUSSIA_7(
        title = "Россия / Казахстан (+7)",
        defaultDialCode = "+7",
        example = "8 (916) 123-45-67 → +79161234567"
    ),
    CUSTOM(
        title = "Свой код страны",
        defaultDialCode = "+375",
        example = "Используется указанный ниже международный код"
    )
}

enum class AlertSoundPreset(val title: String, val subtitle: String) {
    CSL_CHIME(
        title = "Фирменный сигнал Help-CSL",
        subtitle = "Яркий тройной перезвон (хорошо слышен на любом телефоне)"
    ),
    DISPATCH_PULSE(
        title = "Сигнал диспетчера",
        subtitle = "Чёткий двойной импульс службы поддержки"
    ),
    HARMONIC_CHORD(
        title = "Мелодичный аккорд",
        subtitle = "Чистый гармонический сигнал средней громкости"
    ),
    URGENT_ALARM(
        title = "Громкий тревожный сигнал",
        subtitle = "Интенсивный сигнал повышенного внимания"
    ),
    SYSTEM_DEFAULT(
        title = "Стандартный звук уведомлений телефона",
        subtitle = "Мелодия уведомлений Android по умолчанию"
    ),
    CUSTOM_URI(
        title = "Выбранная мелодия с телефона",
        subtitle = "Системный рингтон или свой MP3/аудиофайл"
    )
}

enum class TtsReadoutMode(val title: String, val subtitle: String) {
    OFF("Выключено", "Голосовое чтение новых заявок отключено"),
    HEADSET_ONLY("Только в наушниках / Bluetooth", "Озвучивать клиента и тему, когда подключена гарнитура или авто"),
    ALWAYS("Всегда (в рабочие часы)", "Озвучивать новые заявки и на динамике, и в гарнитуре")
}

enum class AppThemeMode(val title: String) {
    SYSTEM("Системная"),
    LIGHT("☀️ Светлая"),
    DARK("🌙 Тёмная")
}

enum class DarkThemePreset(
    val title: String,
    val shortTitle: String,
    val subtitle: String,
    val previewBgHex: Long,
    val previewCardHex: Long,
    val previewAccentHex: Long
) {
    CSL_NAVY(
        title = "🌑 Ночной CSL",
        shortTitle = "Ночной CSL",
        subtitle = "Фирменный глубокий тёмно-синий с оранжевым акцентом",
        previewBgHex = 0xFF090F1EL,
        previewCardHex = 0xFF131D35L,
        previewAccentHex = 0xFFFF7A33L
    ),
    GRAPHITE_OLED(
        title = "🖤 Графит OLED",
        shortTitle = "Графит OLED",
        subtitle = "Чистый угольно-графитовый без синего оттенка",
        previewBgHex = 0xFF0A0B0EL,
        previewCardHex = 0xFF151820L,
        previewAccentHex = 0xFFFF782EL
    ),
    NORD_SLATE(
        title = "❄️ Сланец Nord",
        shortTitle = "Сланец Nord",
        subtitle = "Мягкий скандинавский серо-стальной для долгой смены",
        previewBgHex = 0xFF181C24L,
        previewCardHex = 0xFF222834L,
        previewAccentHex = 0xFF38BDF8L
    ),
    EMERALD_NOC(
        title = "🌲 Изумруд NOC",
        shortTitle = "Изумруд NOC",
        subtitle = "Спокойный тёмно-хвойный оттенок NOC-мониторинга",
        previewBgHex = 0xFF081310L,
        previewCardHex = 0xFF11221DL,
        previewAccentHex = 0xFF10B981L
    ),
    CYBER_PLUM(
        title = "🟣 Космос Индиго",
        shortTitle = "Космос",
        subtitle = "Глубокий космический индиго-дракула",
        previewBgHex = 0xFF110E1CL,
        previewCardHex = 0xFF1B162CL,
        previewAccentHex = 0xFFA78BFAL
    ),
    WARM_ESPRESSO(
        title = "☕ Тёплый Мокко",
        shortTitle = "Тёплый Мокко",
        subtitle = "Тёплый антрацит без холодного синего свечения",
        previewBgHex = 0xFF14110FL,
        previewCardHex = 0xFF201B18L,
        previewAccentHex = 0xFFF59E0BL
    )
}

data class AppSettings(
    val serverUrl: String = "",
    val confluenceUrl: String = DEFAULT_CONFLUENCE_URL,
    val confluenceToken: String = "",
    val zabbixUrl: String = DEFAULT_ZABBIX_URL,
    val zabbixLogin: String = "",
    val zabbixPassword: String = "",
    val zabbixEnabled: Boolean = true,
    val zabbixMinAlertSeverity: Int = DEFAULT_ZABBIX_MIN_SEVERITY,
    val serverType: JiraServerType = JiraServerType.AUTO,
    val authType: AuthType = AuthType.BASIC_LOGIN_PASSWORD,
    val usernameOrEmail: String = "",
    val passwordOrToken: String = "",
    val trustSelfSignedSsl: Boolean = false,
    val pollingEnabled: Boolean = true,
    val pollIntervalSeconds: Int = 30,
    val offHoursPollIntervalMinutes: Int = DEFAULT_OFF_HOURS_POLL_MINUTES,
    val startOnBoot: Boolean = true,
    val autoCheckUpdates: Boolean = true,
    val workScheduleEnabled: Boolean = true,
    val workStartTime: String = "09:00",
    val workEndTime: String = "17:30",
    val extraWorkingDates: String = "",
    val extraHolidayDates: String = "",
    val alertSoundPreset: AlertSoundPreset = AlertSoundPreset.CSL_CHIME,
    val customAlertSoundUri: String = "",
    val customAlertSoundTitle: String = "",
    val adminChatIssueKey: String = "",
    val adminChatNotificationsEnabled: Boolean = true,
    val staleTicketReminderEnabled: Boolean = true,
    val staleTicketReminderMinutes: Int = 15,
    val quickReplyTemplates: List<String> = DEFAULT_QUICK_REPLY_TEMPLATES,
    val ttsReadoutMode: TtsReadoutMode = TtsReadoutMode.HEADSET_ONLY,
    val stormProtectionEnabled: Boolean = true,
    val callerIdEnabled: Boolean = true,
    val postCallWorklogPromptEnabled: Boolean = true,
    val biometricLockEnabled: Boolean = false,
    val phoneRegion: PhoneRegion = PhoneRegion.AUTO_BY_RU,
    val customCountryCode: String = "+375",
    val customPhoneFieldNames: String = "Телефон, Phone, Номер телефона, Контактный телефон, Mobile",
    val themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    val darkThemePreset: DarkThemePreset = DarkThemePreset.CSL_NAVY
) {
    val isConfigured: Boolean
        get() = serverUrl.isNotBlank() && passwordOrToken.isNotBlank() &&
            (authType == AuthType.BEARER_PAT || usernameOrEmail.isNotBlank())

    val isConfluenceReady: Boolean
        get() = confluenceToken.isNotBlank() ||
            (passwordOrToken.isNotBlank() &&
                (authType == AuthType.BEARER_PAT || usernameOrEmail.isNotBlank()))

    val isZabbixConfigured: Boolean
        get() = zabbixUrl.isNotBlank() && zabbixLogin.isNotBlank() && zabbixPassword.isNotBlank()

    val normalizedZabbixMinSeverity: Int
        get() = if (zabbixMinAlertSeverity in 1..5) zabbixMinAlertSeverity else DEFAULT_ZABBIX_MIN_SEVERITY

    val normalizedOffHoursPollMinutes: Int
        get() = when {
            offHoursPollIntervalMinutes == OFF_HOURS_POLL_SAME_AS_WORK -> OFF_HOURS_POLL_SAME_AS_WORK
            offHoursPollIntervalMinutes < 0 -> OFF_HOURS_POLL_DISABLED
            offHoursPollIntervalMinutes == 0 -> DEFAULT_OFF_HOURS_POLL_MINUTES
            else -> offHoursPollIntervalMinutes.coerceIn(5, 720)
        }

    companion object {
        const val DEFAULT_CONFLUENCE_URL = "https://w.csl.by"
        const val DEFAULT_ZABBIX_URL = "http://z.csl.by"
        const val DEFAULT_ZABBIX_MIN_SEVERITY = 5
        const val DEFAULT_OFF_HOURS_POLL_MINUTES = 120
        const val OFF_HOURS_POLL_DISABLED = -1
        const val OFF_HOURS_POLL_SAME_AS_WORK = -2

        fun formatOffHoursIntervalLabel(rawMinutes: Int): String {
            return when {
                rawMinutes == OFF_HOURS_POLL_SAME_AS_WORK -> "Как в рабочее время"
                rawMinutes < 0 -> "Пауза до начала смены"
                rawMinutes == 0 || rawMinutes == 120 -> "Раз в 2 часа"
                rawMinutes % 60 == 0 -> {
                    val h = rawMinutes / 60
                    val suffix = when {
                        h % 10 == 1 && h % 100 != 11 -> "час"
                        h % 10 in 2..4 && h % 100 !in 12..14 -> "часа"
                        else -> "часов"
                    }
                    "Раз в $h $suffix"
                }
                else -> "Раз в $rawMinutes мин"
            }
        }

        val DEFAULT_QUICK_REPLY_TEMPLATES = listOf(
            "Здравствуйте, {клиент}! Заявка {заявка} принята в работу инженером {инженер}.",
            "Пришлите, пожалуйста, ID и временный пароль AnyDesk / RustDesk для подключения.",
            "Здравствуйте, {клиент}! Работы по заявке {заявка} выполнены. Проверьте, пожалуйста.",
            "Не удалось дозвониться. Укажите удобное время или актуальный номер телефона.",
            "Перезапущены системные службы / спулер печати, доступ восстановлен."
        )
    }
}
