package com.helpdesk.jiraagent.domain

import com.helpdesk.jiraagent.model.AdminChatCodec
import com.helpdesk.jiraagent.model.FilterSettings
import com.helpdesk.jiraagent.model.HelpdeskTicket
import com.helpdesk.jiraagent.model.RepairEquipmentCodec
import java.util.Locale

data class FilterEvaluation(
    val isClientTicket: Boolean,
    val reason: String? = null
)

object TicketFilterEngine {

    private val CLOSED_QUEUE_HINTS = listOf(
        "готов", "решен", "закрыт", "выполн", "заверш", "отмен", "отклон",
        "done", "closed", "resolved", "completed", "cancelled", "canceled"
    )

    private val NOISE_OR_NON_TICKET_QUEUE_HINTS = listOf(
        "zabbix", "backup", "бэкап", "резервн", "raid", "monitoring", "мониторинг",
        "alert", "prometheus", "grafana", "nagios", "cron", "chat", "чат",
        "закуп", "назначенные мне", "assigned to me"
    )

    private val ACTIVE_SUPPORT_QUEUE_HINTS = listOf(
        "в работе", "ожидан", "новые", "открыт", "очеред", "обращен", "заявк",
        "инцидент", "поддержк", "ремонт", "заправ", "принтер", "техник", "клиент",
        "свободн", "неназначен", "активн", "open", "in progress", "waiting",
        "support", "queue", "triage", "unassigned", "incident", "service request", "helpdesk"
    )

    /**
     * Builds the active (Новые + В работе) JQL query compatible with both Jira Server/DC and Jira Cloud.
     */
    fun buildActiveJql(filter: FilterSettings): String {
        val customTrimmed = filter.customJqlCondition.trim()
        if (customTrimmed.contains("ORDER BY", ignoreCase = true) ||
            customTrimmed.contains("statusCategory", ignoreCase = true)
        ) {
            return buildJql(filter)
        }

        val clauses = buildBaseClauses(filter).toMutableList()
        clauses.add("statusCategory != Done")
        val wherePart = clauses.joinToString(" AND ")
        return "$wherePart ORDER BY created DESC"
    }

    /**
     * Builds a dedicated JQL query for tickets strictly in "В работе" (IN_PROGRESS) statuses
     * using server-verified numeric status IDs, so active work-in-progress tickets (even weeks old)
     * are never drowned out by thousands of automated Zabbix / Backup tickets sitting in initial status.
     */
    fun buildInProgressJql(
        filter: FilterSettings,
        inProgressStatusIds: List<String>
    ): String? {
        val cleanIds = inProgressStatusIds.map { it.trim() }.filter { it.isNotEmpty() && it.all(Char::isDigit) }.distinct()
        if (cleanIds.isEmpty()) return null

        val customTrimmed = filter.customJqlCondition.trim()
        if (customTrimmed.contains("ORDER BY", ignoreCase = true) ||
            customTrimmed.contains("statusCategory", ignoreCase = true)
        ) {
            return null
        }

        val clauses = buildBaseClauses(filter).toMutableList()
        clauses.add("status in (${cleanIds.joinToString(", ")})")
        clauses.add("""summary !~ "${AdminChatCodec.ADMIN_CHAT_MARKER}"""")
        clauses.add("""summary !~ "${RepairEquipmentCodec.REPAIR_REGISTRY_MARKER}"""")
        val wherePart = clauses.joinToString(" AND ")
        return "$wherePart ORDER BY updated DESC"
    }

    /**
     * Builds an active JQL query that pre-filters internal storage rooms and obvious monitoring/backup
     * noise on the Jira server side so older client tickets in "Ожидание поддержки" are not pushed
     * past maxResults when automated alerts flood the project.
     */
    fun buildNoiseFilteredActiveJql(filter: FilterSettings): String? {
        val customTrimmed = filter.customJqlCondition.trim()
        if (customTrimmed.contains("ORDER BY", ignoreCase = true) ||
            customTrimmed.contains("statusCategory", ignoreCase = true)
        ) {
            return null
        }

        val clauses = buildBaseClauses(filter).toMutableList()
        clauses.add("statusCategory != Done")
        clauses.add("""summary !~ "${AdminChatCodec.ADMIN_CHAT_MARKER}"""")
        clauses.add("""summary !~ "${RepairEquipmentCodec.REPAIR_REGISTRY_MARKER}"""")
        if (filter.clientOnlyFilterEnabled) {
            val allExcluded = (splitCsv(filter.excludedReporters) + splitCsv(filter.excludedLabelsAndKeywords))
                .map { it.lowercase(Locale.ROOT) }
            if (allExcluded.any { it.contains("zabbix") }) {
                clauses.add("""summary !~ "zabbix"""")
            }
            clauses.add("""summary !~ "BACKUP ERROR"""")
            clauses.add("""summary !~ "RAID PROBLEM"""")
        }
        val wherePart = clauses.joinToString(" AND ")
        return "$wherePart ORDER BY created DESC"
    }

    /**
     * Identifies active human client queues in Jira Service Management (e.g. "В работе", "Ремонт техники")
     * while skipping unfiltered ("ALL"), closed ("Готовые Заявки", "Выполнено"), and automated ("Zabbix", "BACKUP ERRORS") queues.
     */
    fun isHumanActiveSupportQueueName(queueName: String): Boolean {
        val norm = queueName.trim().lowercase(Locale.ROOT).replace('ё', 'е')
        if (norm.isEmpty()) return false
        if (norm == "all" || norm == "все" || norm == "все заявки" || norm == "all issues" || norm == "all open") {
            return false
        }
        if (CLOSED_QUEUE_HINTS.any { norm.contains(it) }) {
            return false
        }
        if (NOISE_OR_NON_TICKET_QUEUE_HINTS.any { norm.contains(it) }) {
            return false
        }
        return ACTIVE_SUPPORT_QUEUE_HINTS.any { norm.contains(it) }
    }

    /**
     * Identifies closed human client queues in Jira Service Management (e.g. "Готовые Заявки", "Закрытые", "Выполнено")
     * while skipping automated monitoring/backup queues.
     */
    fun isClosedHumanQueueName(queueName: String): Boolean {
        val norm = queueName.trim().lowercase(Locale.ROOT).replace('ё', 'е')
        if (norm.isEmpty()) return false
        if (NOISE_OR_NON_TICKET_QUEUE_HINTS.any { norm.contains(it) }) {
            return false
        }
        return CLOSED_QUEUE_HINTS.any { norm.contains(it) }
    }

    /**
     * Identifies automated monitoring / backup / infrastructure queues in Jira Service Management
     * (e.g. "Zabbix", "BACKUP ERRORS", "RAID PROBLEMS") for populating the "Сервисные" tab.
     */
    fun isAutomatedServiceQueueName(queueName: String): Boolean {
        val norm = queueName.trim().lowercase(Locale.ROOT).replace('ё', 'е')
        if (norm.isEmpty()) return false
        if (norm.contains("chat") || norm.contains("чат") ||
            norm.contains("назначенные мне") || norm.contains("assigned to me") ||
            norm.contains("закуп")
        ) {
            return false
        }
        return listOf(
            "zabbix", "backup", "бэкап", "резервн", "raid",
            "monitoring", "мониторинг", "alert", "prometheus", "grafana", "nagios"
        ).any { norm.contains(it) }
    }

    /**
     * Builds locale-safe candidate active JQL queries (ordered best-first) so Russian-locale
     * Jira Server/DC instances (where `statusCategory != Done` returns HTTP 400 because
     * `Done` is localized as `Готово`) always succeed via numeric status IDs or `resolution = Unresolved`.
     */
    fun buildLocaleSafeActiveJqlCandidates(
        filter: FilterSettings,
        doneStatusIds: List<String> = emptyList(),
        fallbackProjectKeys: List<String> = emptyList(),
        excludeNoise: Boolean = false
    ): List<String> {
        val customTrimmed = filter.customJqlCondition.trim()
        if (customTrimmed.contains("ORDER BY", ignoreCase = true) ||
            customTrimmed.contains("statusCategory", ignoreCase = true)
        ) {
            return listOf(buildJql(filter))
        }

        val base = buildBaseClausesWithFallbackProjects(filter, fallbackProjectKeys).toMutableList()
        base.add("""summary !~ "${AdminChatCodec.ADMIN_CHAT_MARKER}"""")
        base.add("""summary !~ "${RepairEquipmentCodec.REPAIR_REGISTRY_MARKER}"""")
        if (excludeNoise && filter.clientOnlyFilterEnabled) {
            val allExcluded = (splitCsv(filter.excludedReporters) + splitCsv(filter.excludedLabelsAndKeywords))
                .map { it.lowercase(Locale.ROOT) }
            if (allExcluded.any { it.contains("zabbix") }) {
                base.add("""summary !~ "zabbix"""")
            }
            base.add("""summary !~ "BACKUP ERROR"""")
            base.add("""summary !~ "RAID PROBLEM"""")
        }

        val cleanDoneIds = doneStatusIds.map { it.trim() }.filter { it.isNotEmpty() && it.all(Char::isDigit) }.distinct()
        val statusConds = buildList {
            if (cleanDoneIds.isNotEmpty()) {
                add("status not in (${cleanDoneIds.joinToString(", ")})")
            }
            add("statusCategory != 3")
            add("resolution = Unresolved")
            add("statusCategory != Done")
        }
        return statusConds.map { cond ->
            val clauses = base + cond
            "${clauses.joinToString(" AND ")} ORDER BY created DESC"
        }
    }

    /**
     * Builds locale-safe candidate closed JQL queries (ordered best-first) for the "Закрытые" and "Сервисные" tabs.
     * Works on Russian Jira Server/DC (`status in (<numeric done IDs>)` / `statusCategory = 3` / `resolution is not EMPTY`)
     * as well as English Jira Server/DC and Jira Cloud (`statusCategory = Done`).
     */
    fun buildLocaleSafeClosedJqlCandidates(
        filter: FilterSettings,
        doneStatusIds: List<String> = emptyList(),
        fallbackProjectKeys: List<String> = emptyList(),
        excludeZabbixReporter: Boolean = false
    ): List<String> {
        val customTrimmed = filter.customJqlCondition.trim()
        if (customTrimmed.contains("ORDER BY", ignoreCase = true) ||
            customTrimmed.contains("statusCategory", ignoreCase = true)
        ) {
            return emptyList()
        }

        val base = buildBaseClausesWithFallbackProjects(filter, fallbackProjectKeys).toMutableList()
        base.add("""summary !~ "${AdminChatCodec.ADMIN_CHAT_MARKER}"""")
        base.add("""summary !~ "${RepairEquipmentCodec.REPAIR_REGISTRY_MARKER}"""")
        if (excludeZabbixReporter && filter.clientOnlyFilterEnabled) {
            val excludedSenders = splitCsv(filter.excludedReporters).map { it.lowercase(Locale.ROOT) }
            if (excludedSenders.any { it == "zabbix" }) {
                base.add("""(reporter is EMPTY OR reporter != "zabbix")""")
            }
        }

        val cleanDoneIds = doneStatusIds.map { it.trim() }.filter { it.isNotEmpty() && it.all(Char::isDigit) }.distinct()
        val statusConds = buildList {
            if (cleanDoneIds.isNotEmpty()) {
                add("status in (${cleanDoneIds.joinToString(", ")})")
            }
            add("statusCategory = 3")
            add("resolution is not EMPTY")
            add("statusCategory = Done")
        }
        return statusConds.map { cond ->
            val clauses = base + cond
            "${clauses.joinToString(" AND ")} ORDER BY updated DESC"
        }
    }

    private fun buildBaseClausesWithFallbackProjects(
        filter: FilterSettings,
        fallbackProjectKeys: List<String>
    ): List<String> {
        val explicitProjects = splitCsv(filter.projectKeys)
        if (explicitProjects.isNotEmpty()) {
            return buildBaseClauses(filter)
        }
        val clauses = mutableListOf<String>()
        val cleanFallback = fallbackProjectKeys.map { it.trim().replace("\"", "") }.filter { it.isNotEmpty() }.distinct()
        if (cleanFallback.isNotEmpty()) {
            val quoted = cleanFallback.joinToString(", ") { "\"$it\"" }
            clauses.add("project in ($quoted)")
        }
        val customTrimmed = filter.customJqlCondition.trim()
        if (customTrimmed.isNotEmpty()) {
            clauses.add("($customTrimmed)")
        }
        return clauses
    }

    /**
     * Scopes a Jira Service Management queue JQL string to [projectKey] (preserving operator precedence
     * and existing ORDER BY clauses) so it can be executed safely via `/rest/api/2/search`.
     */
    fun scopeJsmQueueJql(rawQueueJql: String, projectKey: String): String {
        val trimmed = rawQueueJql.trim()
        val orderRegex = Regex("\\bORDER\\s+BY\\b", RegexOption.IGNORE_CASE)
        val orderMatch = orderRegex.find(trimmed)
        val wherePart = if (orderMatch != null) trimmed.substring(0, orderMatch.range.first).trim() else trimmed
        val orderPart = if (orderMatch != null) trimmed.substring(orderMatch.range.first).trim() else "ORDER BY created DESC"

        val cleanKey = projectKey.trim().replace("\"", "")
        val scopedWhere = when {
            cleanKey.isEmpty() -> wherePart
            wherePart.isEmpty() -> """project = "$cleanKey""""
            Regex("\\bproject\\b", RegexOption.IGNORE_CASE).containsMatchIn(wherePart) -> wherePart
            else -> """project = "$cleanKey" AND ($wherePart)"""
        }
        return if (scopedWhere.isEmpty()) orderPart else "$scopedWhere $orderPart"
    }

    /**
     * Builds the closed (Закрытые) JQL query ordered by most recently updated/closed first.
     * Returns null when the user supplied a full custom ORDER BY / statusCategory query.
     */
    fun buildClosedJql(filter: FilterSettings): String? {
        val customTrimmed = filter.customJqlCondition.trim()
        if (customTrimmed.contains("ORDER BY", ignoreCase = true) ||
            customTrimmed.contains("statusCategory", ignoreCase = true)
        ) {
            return null
        }

        val clauses = buildBaseClauses(filter).toMutableList()
        clauses.add("statusCategory = Done")
        val wherePart = clauses.joinToString(" AND ")
        return "$wherePart ORDER BY updated DESC"
    }

    /**
     * Builds a general JQL query preview / fallback.
     */
    fun buildJql(filter: FilterSettings): String {
        val customTrimmed = filter.customJqlCondition.trim()
        if (customTrimmed.contains("ORDER BY", ignoreCase = true)) {
            return customTrimmed
        }

        val clauses = buildBaseClauses(filter)
        val wherePart = clauses.joinToString(" AND ")
        return if (wherePart.isEmpty()) {
            "ORDER BY updated DESC"
        } else {
            "$wherePart ORDER BY updated DESC"
        }
    }

    private fun buildBaseClauses(filter: FilterSettings): List<String> {
        val clauses = mutableListOf<String>()
        val projects = splitCsv(filter.projectKeys)
        if (projects.isNotEmpty()) {
            val quoted = projects.joinToString(", ") { "\"${it.replace("\"", "")}\"" }
            clauses.add("project in ($quoted)")
        }
        val customTrimmed = filter.customJqlCondition.trim()
        if (customTrimmed.isNotEmpty()) {
            clauses.add("($customTrimmed)")
        }
        return clauses
    }

    /**
     * Evaluates whether [ticket] is a genuine client request (placed into Новые / В работе / Закрытые
     * according to its Jira status) or a service/automated ticket (such as sender `zabbix`) placed
     * into Сервисные without notification banners.
     */
    fun evaluate(
        ticket: HelpdeskTicket,
        filter: FilterSettings,
        currentAgentLoginOrEmail: String = ""
    ): FilterEvaluation {
        if (AdminChatCodec.isAdminChatStorageIssue(
                issueKey = ticket.key,
                summary = ticket.summary,
                labels = ticket.labels
            )
        ) {
            return FilterEvaluation(
                isClientTicket = false,
                reason = "Служебная комната чата админов"
            )
        }

        if (!filter.clientOnlyFilterEnabled) {
            return FilterEvaluation(isClientTicket = true, reason = null)
        }

        // 1. Excluded Senders (e.g. "zabbix") — checked first so automated senders always go to service tickets
        val excludedReporters = splitCsv(filter.excludedReporters)
        if (excludedReporters.isNotEmpty()) {
            val senderHaystack = "${ticket.reporterName} | ${ticket.reporterLoginOrEmail}".lowercase()
            val matchedSender = excludedReporters.firstOrNull { pattern ->
                val p = pattern.lowercase()
                if (p.length <= 3) {
                    Regex("""(?<![a-zа-яё0-9])${Regex.escape(p)}(?![a-zа-яё0-9])""")
                        .containsMatchIn(senderHaystack)
                } else {
                    senderHaystack.contains(p)
                }
            }
            if (matchedSender != null) {
                val authorLabel = ticket.reporterName.ifBlank { ticket.reporterLoginOrEmail }
                return FilterEvaluation(
                    isClientTicket = false,
                    reason = "Сервисный отправитель: $authorLabel"
                )
            }
        }

        // 2. Ignore tickets created by the current Helpdesk agent themselves (if toggled)
        val agentId = currentAgentLoginOrEmail.trim()
        if (filter.ignoreOwnTickets && agentId.isNotEmpty()) {
            val senderTokens = ticket.reporterLoginOrEmail.split('|').map { it.trim() }
            if (ticket.reporterName.equals(agentId, ignoreCase = true) ||
                senderTokens.any { it.equals(agentId, ignoreCase = true) }
            ) {
                return FilterEvaluation(
                    isClientTicket = false,
                    reason = "Собственная заявка агента (${ticket.reporterName})"
                )
            }
        }

        // 3. Exclude subtasks
        if (filter.excludeSubtasks && ticket.isSubtask) {
            return FilterEvaluation(
                isClientTicket = false,
                reason = "Подзадача (${ticket.issueType})"
            )
        }

        // 4. Require JSM Customer Portal metadata if toggled
        if (filter.onlyCustomerPortalRequests &&
            !ticket.hasCustomerPortalInfo &&
            ticket.customerRequestType.isNullOrBlank()
        ) {
            return FilterEvaluation(
                isClientTicket = false,
                reason = "Создана вне клиентского портала JSM"
            )
        }

        // 5. Project whitelist check (if configured)
        val allowedProjects = splitCsv(filter.projectKeys)
        if (allowedProjects.isNotEmpty() && ticket.projectKey.isNotBlank()) {
            val matchesProject = allowedProjects.any {
                it.equals(ticket.projectKey, ignoreCase = true)
            }
            if (!matchesProject) {
                return FilterEvaluation(
                    isClientTicket = false,
                    reason = "Другой проект (${ticket.projectKey})"
                )
            }
        }

        // 6. Issue Type / Customer Request Type whitelist (if user specified allowed types)
        val allowedTypes = splitCsv(filter.allowedIssueTypes)
        if (allowedTypes.isNotEmpty()) {
            val typeCandidates = listOfNotNull(ticket.issueType, ticket.customerRequestType)
            val matchesAllowed = allowedTypes.any { allowed ->
                typeCandidates.any { candidate ->
                    candidate.contains(allowed, ignoreCase = true)
                }
            }
            if (!matchesAllowed) {
                return FilterEvaluation(
                    isClientTicket = false,
                    reason = "Тип не в списке разрешённых (${ticket.customerRequestType ?: ticket.issueType})"
                )
            }
        }

        // 7. Excluded Issue Types / Request Types (exact name match so JSM "Service Request"
        // or "Проблемы с ПК/Ноутбуком" is never falsely caught)
        val excludedTypes = splitCsv(filter.excludedIssueTypes)
        if (excludedTypes.isNotEmpty()) {
            val reqType = ticket.customerRequestType?.trim().orEmpty()
            val issType = ticket.issueType.trim()

            if (reqType.isNotEmpty() && excludedTypes.any { reqType.equals(it, ignoreCase = true) }) {
                return FilterEvaluation(
                    isClientTicket = false,
                    reason = "Сервисный тип обращения: $reqType"
                )
            }
            if (excludedTypes.any { issType.equals(it, ignoreCase = true) }) {
                return FilterEvaluation(
                    isClientTicket = false,
                    reason = "Сервисный тип задачи: $issType"
                )
            }
        }

        // 8. Excluded Service Labels & Summary Markers
        val excludedKeywords = splitCsv(filter.excludedLabelsAndKeywords)
        if (excludedKeywords.isNotEmpty()) {
            val matchedLabel = ticket.labels.firstOrNull { label ->
                excludedKeywords.any { kw -> label.equals(kw, ignoreCase = true) }
            }
            if (matchedLabel != null) {
                return FilterEvaluation(
                    isClientTicket = false,
                    reason = "Сервисная метка: $matchedLabel"
                )
            }

            val summaryLower = ticket.summary.lowercase()
            val matchedSummaryTag = excludedKeywords.firstOrNull { kw ->
                val kwLower = kw.lowercase()
                if (kwLower.startsWith("[") && kwLower.endsWith("]")) {
                    summaryLower.contains(kwLower)
                } else {
                    Regex("""(?<![a-zа-яё0-9])${Regex.escape(kwLower)}(?![a-zа-яё0-9])""")
                        .containsMatchIn(summaryLower)
                }
            }
            if (matchedSummaryTag != null) {
                return FilterEvaluation(
                    isClientTicket = false,
                    reason = "Сервисный признак в теме: $matchedSummaryTag"
                )
            }
        }

        return FilterEvaluation(isClientTicket = true, reason = null)
    }

    fun splitCsv(raw: String): List<String> {
        return raw.split(',', ';', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }
}
