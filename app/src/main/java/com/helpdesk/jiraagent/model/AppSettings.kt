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

data class AppSettings(
    val serverUrl: String = "",
    val serverType: JiraServerType = JiraServerType.AUTO,
    val authType: AuthType = AuthType.BASIC_LOGIN_PASSWORD,
    val usernameOrEmail: String = "",
    val passwordOrToken: String = "",
    val trustSelfSignedSsl: Boolean = false,
    val pollingEnabled: Boolean = true,
    val pollIntervalSeconds: Int = 30,
    val startOnBoot: Boolean = true,
    val workScheduleEnabled: Boolean = true,
    val workStartTime: String = "09:00",
    val workEndTime: String = "17:30",
    val extraWorkingDates: String = "",
    val extraHolidayDates: String = "",
    val alertSoundPreset: AlertSoundPreset = AlertSoundPreset.CSL_CHIME,
    val customAlertSoundUri: String = "",
    val customAlertSoundTitle: String = "",
    val phoneRegion: PhoneRegion = PhoneRegion.AUTO_BY_RU,
    val customCountryCode: String = "+375",
    val customPhoneFieldNames: String = "Телефон, Phone, Номер телефона, Контактный телефон, Mobile"
) {
    val isConfigured: Boolean
        get() = serverUrl.isNotBlank() && passwordOrToken.isNotBlank() &&
            (authType == AuthType.BEARER_PAT || usernameOrEmail.isNotBlank())
}
