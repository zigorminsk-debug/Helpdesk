package com.helpdesk.jiraagent

import com.helpdesk.jiraagent.domain.AutomaticTicketAlertPolicy
import com.helpdesk.jiraagent.domain.TicketReminder
import com.helpdesk.jiraagent.domain.TicketReminderRules
import com.helpdesk.jiraagent.model.HelpdeskTicket
import org.junit.Assert.*
import org.junit.Test

class TicketReminderTest {
    @Test fun reminderReplacementCancellationAndLateDelivery() {
        val first = TicketReminder("A-1", "Утром", 3000)
        val second = TicketReminder("A-2", "Вечером", 4000)
        val items = TicketReminderRules.upsert(TicketReminderRules.upsert(emptyList(), first, 1000), second, 1000)
        val changed = TicketReminderRules.upsert(items, first.copy(ticketKey = "a-1", atMillis = 5000), 1000)
        assertEquals(2, changed.size)
        assertEquals(listOf(second), TicketReminderRules.due(changed, 4000))
        assertEquals(2, TicketReminderRules.due(changed, 9000).size)
        assertEquals(listOf(second), TicketReminderRules.remove(changed, "A-1"))
        assertTrue(runCatching { TicketReminderRules.upsert(items, first.copy(atMillis = 1000), 1000) }.isFailure)
    }

    @Test fun automaticRepeatNeverTargetsInProgressButManualReminderRemainsPossible() {
        val ticket = HelpdeskTicket(id="1", key="A-1", summary="test", description="", issueType="", isSubtask=false,
            customerRequestType=null, hasCustomerPortalInfo=false, status="В работе", statusCategoryKey="indeterminate",
            priority="", projectKey="A", projectName="", reporterName="", reporterLoginOrEmail="", assigneeName=null,
            createdIso="", createdEpochMillis=1, updatedIso="", labels=emptyList(), phoneNumbers=emptyList(), comments=emptyList(), webUrl="")
        assertFalse(AutomaticTicketAlertPolicy.eligible(ticket))
        assertFalse(AutomaticTicketAlertPolicy.eligibleUnassigned(ticket))
        assertTrue(AutomaticTicketAlertPolicy.eligibleUnassigned(ticket.copy(status="Новая", statusCategoryKey="new")))
        assertEquals(1, TicketReminderRules.due(listOf(TicketReminder(ticket.key, ticket.summary, 2000)), 2000).size)
    }
}
