package com.helpdesk.jiraagent.domain

import com.helpdesk.jiraagent.model.HelpdeskTicket
import com.helpdesk.jiraagent.model.JiraOrganizationRef
import java.util.Locale

data class OrganizationHistory(val loading: Boolean = false, val failed: Boolean = false, val tickets: List<HelpdeskTicket> = emptyList())

/** Reference-only classification of issue summaries; no inference from comments or descriptions. */
object ClientRequestAnalysis {
    private val rules = listOf(
        "Доступ и учётные записи" to Regex("доступ|уч[её]тн|парол|авторизац|вход|блокиров|account|password|login", RegexOption.IGNORE_CASE),
        "Сеть, интернет и VPN" to Regex("сеть|сетев|интернет|vpn|wi[ -]?fi|dns|network", RegexOption.IGNORE_CASE),
        "Почта" to Regex("почт|outlook|exchange|mail", RegexOption.IGNORE_CASE),
        "Печать и сканирование" to Regex("принтер|печат|печать|сканер|сканирован|printer", RegexOption.IGNORE_CASE),
        "Компьютеры и оборудование" to Regex("компьютер|ноутбук|монитор|оборудован|computer|laptop", RegexOption.IGNORE_CASE),
        "Программы и приложения" to Regex("программ|приложени|1с|1c|software", RegexOption.IGNORE_CASE),
        "Серверы и домен" to Regex("сервер|домен|server|active directory", RegexOption.IGNORE_CASE)
    )
    const val OTHER = "Другие темы"

    fun sameReporter(reference: HelpdeskTicket, candidate: HelpdeskTicket): Boolean {
        val a = reference.reporterIdentity?.trim().orEmpty()
        val b = candidate.reporterIdentity?.trim().orEmpty()
        if (a.isNotEmpty() && b.isNotEmpty()) return a == b
        // If an ID is missing, display-name equality remains explicitly approximate.
        val name = reference.reporterName.trim()
        return name.length >= 2 && !name.equals("Клиент", true) && name.equals(candidate.reporterName.trim(), true)
    }

    fun sameOrganization(org: JiraOrganizationRef, ticket: HelpdeskTicket): Boolean =
        ticket.organizations.orEmpty().any { it.fieldId == org.fieldId && it.id == org.id }

    fun groups(tickets: List<HelpdeskTicket>): Map<String, List<HelpdeskTicket>> {
        val unique = tickets.distinctBy { it.key.uppercase(Locale.ROOT) }.sortedByDescending { it.createdEpochMillis }
        val result = linkedMapOf<String, MutableList<HelpdeskTicket>>()
        unique.forEach { ticket ->
            val matching = rules.filter { it.second.containsMatchIn(ticket.summary) }.map { it.first }
                .ifEmpty { listOf(OTHER) }
            matching.forEach { result.getOrPut(it) { mutableListOf() }.add(ticket) }
        }
        return result.entries.sortedWith(compareByDescending<Map.Entry<String, MutableList<HelpdeskTicket>>> { it.value.size }.thenBy { it.key })
            .associate { it.key to it.value.toList() }
    }
}
