package com.helpdesk.jiraagent.model

data class FilterSettings(
    val clientOnlyFilterEnabled: Boolean = true,
    val excludeSubtasks: Boolean = true,
    val onlyCustomerPortalRequests: Boolean = false,
    val excludeResolvedClosed: Boolean = true,
    val ignoreOwnTickets: Boolean = false,
    val projectKeys: String = "",
    val allowedIssueTypes: String = "",
    val excludedIssueTypes: String = DEFAULT_EXCLUDED_ISSUE_TYPES,
    val excludedReporters: String = DEFAULT_EXCLUDED_REPORTERS,
    val excludedLabelsAndKeywords: String = DEFAULT_EXCLUDED_LABELS_AND_KEYWORDS,
    val customJqlCondition: String = ""
) {
    companion object {
        const val DEFAULT_EXCLUDED_ISSUE_TYPES =
            "Service, Сервисная, Сервисный, Internal, Внутренняя, Внутренний, " +
                "Sub-task, Подзадача, Epic, Эпик, Maintenance, Регламент, " +
                "Мониторинг, Monitoring, Alert, Оповещение, Автоматическая, " +
                "Change, Изменение, Problem, Проблема, DevOps, Infrastructure"

        const val DEFAULT_EXCLUDED_REPORTERS =
            "jira, automation, bot, zabbix, prometheus, grafana, nagios, " +
                "alertmanager, system, service, monitoring, noreply, no-reply, " +
                "daemon, webhook, integration, cron, jenkins, gitlab"

        const val DEFAULT_EXCLUDED_LABELS_AND_KEYWORDS =
            "internal, service, сервисная, внутренняя, monitoring, zabbix, " +
                "auto, cron, healthcheck, [service], [internal], [auto], [monitoring]"
    }
}
