package com.helpdesk.jiraagent.service

import android.os.Build
import android.telecom.Call
import android.telecom.CallScreeningService
import com.helpdesk.jiraagent.data.SettingsRepository
import com.helpdesk.jiraagent.model.HelpdeskTicket
import com.helpdesk.jiraagent.model.TicketStage
import com.helpdesk.jiraagent.notification.NotificationHelper

/**
 * Official Android Caller ID service (`CallScreeningService`).
 * Requires zero dangerous Call Log permissions — matches incoming phone numbers against cached
 * Jira tickets and shows an instant Caller ID banner with client name and active ticket summary.
 */
class HelpCslCallScreeningService : CallScreeningService() {

    override fun onScreenCall(callDetails: Call.Details) {
        // Always let the incoming call ring immediately without any delay or blocking
        respondToCall(callDetails, CallResponse.Builder().build())

        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                callDetails.callDirection != Call.Details.DIRECTION_INCOMING
            ) {
                return
            }
            val rawNumber = callDetails.handle?.schemeSpecificPart?.trim().orEmpty()
            val incomingDigits = rawNumber.filter(Char::isDigit)
            if (incomingDigits.length < 7) return

            val repo = SettingsRepository.getInstance(applicationContext)
            if (!repo.appSettings.value.callerIdEnabled) return

            val tail = incomingDigits.takeLast(9)
            val matchedTickets = findMatchingTickets(repo.tickets.value, tail)
            if (matchedTickets.isNotEmpty()) {
                NotificationHelper.showIncomingCallerIdBanner(
                    context = applicationContext,
                    callerNumber = rawNumber,
                    matchedTickets = matchedTickets
                )
            }
        }
    }

    companion object {
        fun findMatchingTickets(allTickets: List<HelpdeskTicket>, incomingTailDigits: String): List<HelpdeskTicket> {
            if (incomingTailDigits.length < 7) return emptyList()
            val tail = incomingTailDigits.takeLast(9)
            return allTickets
                .filter { ticket ->
                    ticket.phoneNumbers.any { pm ->
                        val tDigits = pm.e164.filter(Char::isDigit)
                        tDigits.endsWith(tail) || tail.endsWith(tDigits.takeLast(7))
                    }
                }
                .sortedWith(
                    compareBy<HelpdeskTicket> {
                        when (it.stage) {
                            TicketStage.NEW -> 0
                            TicketStage.IN_PROGRESS -> 1
                            TicketStage.CLOSED -> 2
                            TicketStage.SERVICE -> 3
                        }
                    }.thenByDescending { it.createdEpochMillis }
                )
        }
    }
}
