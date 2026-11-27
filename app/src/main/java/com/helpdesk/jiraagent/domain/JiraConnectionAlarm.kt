package com.helpdesk.jiraagent.domain

import com.helpdesk.jiraagent.data.JiraApiException
import java.io.IOException
import javax.net.ssl.SSLException

/** The alert describes this client’s access to Jira, not proof of a server-wide outage. */
enum class JiraConnectionObservation { UNREACHABLE, REACHABLE, UNKNOWN }
enum class JiraConnectionAlarmChange { STARTED, RECOVERED, NONE }

object JiraConnectionAlarm {
    const val BANNER = "ВНИМАНИЕ! HELP DESK НЕДОСТУПЕН! Срочно примите меры по восстановлению связи."
    const val SPEECH = "Этеншен! Хелп деск недоступен! Срочно примите меры по восстановлению."

    fun classify(error: Throwable): JiraConnectionObservation = when {
        error is JiraApiException && error.isNetworkError -> JiraConnectionObservation.UNREACHABLE
        error is JiraApiException && (error.httpCode == 408 || (error.httpCode ?: 0) in 500..599) -> JiraConnectionObservation.UNREACHABLE
        error is JiraApiException && error.httpCode != null -> JiraConnectionObservation.REACHABLE
        error is SSLException -> JiraConnectionObservation.UNKNOWN
        error is IOException -> JiraConnectionObservation.UNREACHABLE
        else -> JiraConnectionObservation.UNKNOWN
    }
}

/** Transitions only once per incident; an inconclusive result must not clear an active alarm. */
class JiraConnectionAlarmTracker {
    private var unavailable = false
    @Synchronized fun observe(observation: JiraConnectionObservation): JiraConnectionAlarmChange = when (observation) {
        JiraConnectionObservation.UNREACHABLE -> if (!unavailable) {
            unavailable = true
            JiraConnectionAlarmChange.STARTED
        } else JiraConnectionAlarmChange.NONE
        JiraConnectionObservation.REACHABLE -> if (unavailable) {
            unavailable = false
            JiraConnectionAlarmChange.RECOVERED
        } else JiraConnectionAlarmChange.NONE
        JiraConnectionObservation.UNKNOWN -> JiraConnectionAlarmChange.NONE
    }
    @Synchronized fun isUnavailable(): Boolean = unavailable
    @Synchronized fun reset() { unavailable = false }
}
