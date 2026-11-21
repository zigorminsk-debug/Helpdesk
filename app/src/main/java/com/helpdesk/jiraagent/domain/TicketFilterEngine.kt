package com.helpdesk.jiraagent.domain

import com.helpdesk.jiraagent.model.FilterSettings
import com.helpdesk.jiraagent.model.HelpdeskTicket

data class FilterEvaluation(
    val isClientTicket: Boolean,
    val reason: String? = null
)

object TicketFilterEngine {

    /**
     * Builds a safe server-side JQL query compatible with both Jira Server/DC and Jira Cloud.
     */
    fun buildJql(filter: FilterSettings): String {
        val customTrimmed = filter.customJqlCondition.trim()
        if (customTrimmed.contains("ORDER BY", ignoreCase = true)) {
            return customTrimmed
        }

        val clauses = mutableListOf<String>()

        val projects = splitCsv(filter.projectKeys)
        if (projects.isNotEmpty()) {
            val quoted = projects.joinToString(", ") { "\"${it.replace("\"", "")}\"" }
            clauses.add("project in ($quoted)")
        }

        if (filter.excludeResolvedClosed) {
            clauses.add("statusCategory != Done")
        }

        if (customTrimmed.isNotEmpty()) {
            clauses.add("($customTrimmed)")
        }

        val wherePart = clauses.joinToString(" AND ")
        return if (wherePart.isEmpty()) {
            "ORDER BY created DESC"
        } else {
            "$wherePart ORDER BY created DESC"
        }
    }

    /**
     * Evaluates whether [ticket] is a genuine client request (eligible for Heads-Up notification
     * banner) or a service/automated ticket (such as sender `zabbix`) that must be ignored.
     */
    fun evaluate(
        ticket: HelpdeskTicket,
        filter: FilterSettings,
        currentAgentLoginOrEmail: String = ""
    ): FilterEvaluation {
        if (!filter.clientOnlyFilterEnabled) {
            return FilterEvaluation(isClientTicket = true, reason = null)
        }

        // 1. Excluded Senders (e.g. "zabbix") — checked first so automated senders always go to service tickets
        val excludedReporters = splitCsv(filter.excludedReporters)
        if (excludedReporters.isNotEmpty()) {
            val senderHaystack = "${ticket.reporterName} | ${ticket.reporterLoginOrEmail}".lowercase()
            val matchedSender = excludedReporters.firstOrNull { pattern ->
                val p = pattern.lowercase()
                // Short generic words like "bot" require word boundaries so "rabota" isn't hit;
                // specific sender names like "zabbix" match anywhere in login/email/displayName.
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

        // 5. Exclude resolved / closed if enabled
        if (filter.excludeResolvedClosed &&
            ticket.statusCategoryKey.equals("done", ignoreCase = true)
        ) {
            return FilterEvaluation(
                isClientTicket = false,
                reason = "Заявка уже закрыта (${ticket.status})"
            )
        }

        // 6. Project whitelist check (if configured)
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

        // 7. Issue Type / Customer Request Type whitelist (if user specified allowed types)
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

        // 8. Excluded Issue Types / Request Types (exact name match so JSM "Service Request"
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

        // 9. Excluded Service Labels & Summary Markers
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
