package com.helpdesk.jiraagent.domain

/** A personal, one-shot local reminder. It never changes the Jira issue. */
data class TicketReminder(val ticketKey: String, val summary: String, val atMillis: Long)

object TicketReminderRules {
    fun upsert(items: List<TicketReminder>, reminder: TicketReminder, nowMillis: Long): List<TicketReminder> {
        require(reminder.ticketKey.isNotBlank() && reminder.atMillis > nowMillis) { "Укажите заявку и будущее время" }
        return (items.filterNot { it.ticketKey.equals(reminder.ticketKey, true) } + reminder).sortedBy { it.atMillis }
    }
    fun due(items: List<TicketReminder>, nowMillis: Long): List<TicketReminder> = items.filter { it.atMillis <= nowMillis }
    fun remove(items: List<TicketReminder>, ticketKey: String): List<TicketReminder> =
        items.filterNot { it.ticketKey.equals(ticketKey, true) }
}

/** Automatic queue alerts are only for NEW issues; a manually scheduled reminder is independent. */
object AutomaticTicketAlertPolicy {
    fun eligible(ticket: com.helpdesk.jiraagent.model.HelpdeskTicket): Boolean =
        ticket.isClientTicket && ticket.stage == com.helpdesk.jiraagent.model.TicketStage.NEW
    fun eligibleUnassigned(ticket: com.helpdesk.jiraagent.model.HelpdeskTicket): Boolean =
        eligible(ticket) && ticket.assigneeName.isNullOrBlank()
}
