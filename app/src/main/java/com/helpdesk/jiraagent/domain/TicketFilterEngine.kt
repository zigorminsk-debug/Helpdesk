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
     * Fine-grained issueType/reporter/keyword filtering runs locally via [evaluate] so non-existent
     * type names never trigger HTTP 400 JQL validation errors on a specific Jira server.
     */
    fun buildJql(filter: FilterSettings): String {
        val customTrimmed = filter.customJqlCondition.trim()
        // If user wrote a complete JQL with ORDER BY, respect it directly
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
     * banner) or an internal/service/monitoring ticket that must be ignored.
     */
    fun evaluate(
        ticket: HelpdeskTicket,
        filter: FilterSettings,
        currentAgentLoginOrEmail: String = ""
    ): FilterEvaluation {
        if (!filter.clientOnlyFilterEnabled) {
            return FilterEvaluation(isClientTicket = true, reason = null)
        }

        // 1. Exclude subtasks
        if (filter.excludeSubtasks && ticket.isSubtask) {
            return FilterEvaluation(
                isClientTicket = false,
                reason = "Подзадача (${ticket.issueType})"
            )
        }

        // 2. Require JSM Customer Portal metadata if toggled
        if (filter.onlyCustomerPortalRequests &&
            !ticket.hasCustomerPortalInfo &&
            ticket.customerRequestType.isNullOrBlank()
        ) {
            return FilterEvaluation(
                isClientTicket = false,
                reason = "Создана вне клиентского портала JSM"
            )
        }

        // 3. Exclude resolved / closed if enabled
        if (filter.excludeResolvedClosed &&
            ticket.statusCategoryKey.equals("done", ignoreCase = true)
        ) {
            return FilterEvaluation(
                isClientTicket = false,
                reason = "Заявка уже закрыта (${ticket.status})"
            )
        }

        // 4. Project whitelist check (if configured)
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

        // 5. Issue Type / Customer Request Type whitelist (if user specified allowed types)
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
                    reason = "Тип не в списке клиентских (${ticket.customerRequestType ?: ticket.issueType})"
                )
            }
        }

        // 6. Excluded Service Issue Types / Request Types
        val excludedTypes = splitCsv(filter.excludedIssueTypes)
        if (excludedTypes.isNotEmpty()) {
            val matchedExcludedType = excludedTypes.firstOrNull { excluded ->
                ticket.issueType.equals(excluded, ignoreCase = true) ||
                    ticket.issueType.contains(excluded, ignoreCase = true) ||
                    (ticket.customerRequestType?.contains(excluded, ignoreCase = true) == true)
            }
            if (matchedExcludedType != null) {
                return FilterEvaluation(
                    isClientTicket = false,
                    reason = "Сервисный тип задачи: ${ticket.customerRequestType ?: ticket.issueType}"
                )
            }
        }

        // 7. Ignore tickets created by the current Helpdesk agent themselves (if toggled)
        val agentId = currentAgentLoginOrEmail.trim()
        if (filter.ignoreOwnTickets && agentId.isNotEmpty()) {
            if (ticket.reporterLoginOrEmail.equals(agentId, ignoreCase = true) ||
                ticket.reporterName.equals(agentId, ignoreCase = true)
            ) {
                return FilterEvaluation(
                    isClientTicket = false,
                    reason = "Собственная заявка агента (${ticket.reporterName})"
                )
            }
        }

        // 8. Excluded Service / Bot / Monitoring Reporters
        val excludedReporters = splitCsv(filter.excludedReporters)
        if (excludedReporters.isNotEmpty()) {
            val reporterCombined = "${ticket.reporterName} ${ticket.reporterLoginOrEmail}".lowercase()
            val matchedBot = excludedReporters.firstOrNull { pattern ->
                reporterCombined.contains(pattern.lowercase())
            }
            if (matchedBot != null) {
                return FilterEvaluation(
                    isClientTicket = false,
                    reason = "Сервисный автор/бот: ${ticket.reporterName.ifBlank { ticket.reporterLoginOrEmail }}"
                )
            }
        }

        // 9. Excluded Service Labels & Summary Keywords
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
                    // Match whole word in summary so short words like "auto" don't hit "automobile"
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
