package com.helpdesk.jiraagent.domain

import com.helpdesk.jiraagent.model.HelpdeskTicket
import java.util.Locale

object CslCompanyContacts {
    const val LEGAL_NAME = "ООО «Компьютерная безопасность»"
    const val BRAND_TITLE = "ООО «Компьютерная безопасность» · Help-CSL"
    const val ADDRESS_AND_HOURS = "220007, Республика Беларусь, г. Минск, ул. Фабрициуса, 9  |  Пн–Пт 09:00–17:00"
    const val PHONES_AND_WEB = "Тел.: +375 (29) 337-14-12, +375 (29) 671-38-22  |  E-mail: info@csl.by  |  csl.by"
}

object EngineerNameResolver {

    private val LOGIN_TOKEN_REGEX = Regex("^[A-Za-z0-9._-]+$")

    /**
     * Returns true when [value] is empty, an email address (e.g. `ziv@csl.by`),
     * or a technical single-word ASCII account login (e.g. `ziv`, `i.zakharevich`),
     * rather than a human full name (`Захаревич Игорь`).
     */
    fun looksLikeLoginOrEmail(value: String?): Boolean {
        val trimmed = value?.trim().orEmpty()
        if (trimmed.isEmpty()) return true
        if (trimmed.contains('@')) return true
        if (!trimmed.contains(' ') && LOGIN_TOKEN_REGEX.matches(trimmed)) return true
        return false
    }

    /**
     * Resolves a human First/Last Name (`Захаревич Игорь`) instead of a login/email (`ziv@csl.by`).
     */
    fun resolve(
        rawEngineer: String?,
        connectedUserDisplayName: String?,
        currentUsernameOrEmail: String = "",
        knownTickets: List<HelpdeskTicket> = emptyList(),
        fallbackLabel: String = "Сервисный инженер"
    ): String {
        val candidate = rawEngineer?.trim().orEmpty()
        if (candidate.isNotEmpty() && !looksLikeLoginOrEmail(candidate)) {
            return candidate
        }

        val cleanDisplay = connectedUserDisplayName?.trim().orEmpty()
            .takeIf { !looksLikeLoginOrEmail(it) }

        val myTokens = loginTokens(currentUsernameOrEmail)
        val candidateTokens = loginTokens(candidate)

        // 1. Empty candidate or candidate matches current logged-in engineer's login/email
        if (candidate.isEmpty() || candidateTokens.any { it in myTokens }) {
            if (cleanDisplay != null) return cleanDisplay
            val fromMyTickets = findDisplayNameInTickets(myTokens + candidateTokens, knownTickets)
            if (fromMyTickets != null) return fromMyTickets
        }

        // 2. Candidate is a colleague's login/email — look up in loaded Jira tickets
        if (candidateTokens.isNotEmpty()) {
            val fromTickets = findDisplayNameInTickets(candidateTokens, knownTickets)
            if (fromTickets != null) return fromTickets
        }

        // 3. Fallback to current user's full name when raw was empty or no account filter was supplied
        if ((candidate.isEmpty() || myTokens.isEmpty()) && cleanDisplay != null) {
            return cleanDisplay
        }
        return fallbackLabel
    }

    private fun loginTokens(loginOrEmail: String): Set<String> {
        val clean = loginOrEmail.trim().lowercase(Locale.ROOT)
        if (clean.isEmpty()) return emptySet()
        return buildSet {
            add(clean)
            val beforeAt = clean.substringBefore('@').trim()
            if (beforeAt.length >= 2) add(beforeAt)
        }
    }

    private fun findDisplayNameInTickets(
        tokens: Set<String>,
        tickets: List<HelpdeskTicket>
    ): String? {
        if (tokens.isEmpty() || tickets.isEmpty()) return null
        for (t in tickets) {
            val assigneeDisplay = t.assigneeName?.trim().orEmpty()
            if (!looksLikeLoginOrEmail(assigneeDisplay)) {
                val loginParts = t.assigneeLogin.orEmpty()
                    .split('|')
                    .flatMap { loginTokens(it) }
                    .toSet()
                if (tokens.any { it in loginParts }) {
                    return assigneeDisplay
                }
            }
        }
        return null
    }
}
