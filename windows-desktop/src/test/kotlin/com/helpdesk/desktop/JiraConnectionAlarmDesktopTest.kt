package com.helpdesk.desktop

import com.helpdesk.jiraagent.data.JiraApiException
import com.helpdesk.jiraagent.domain.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class JiraConnectionAlarmDesktopTest {
    @Test fun incidentOnlyAlertsOnceAndAuthIsNotAnOutage() {
        val tracker = JiraConnectionAlarmTracker()
        assertEquals(JiraConnectionAlarmChange.STARTED, tracker.observe(JiraConnectionAlarm.classify(JiraApiException("timeout", isNetworkError = true))))
        assertEquals(JiraConnectionAlarmChange.NONE, tracker.observe(JiraConnectionObservation.UNREACHABLE))
        assertTrue(tracker.isUnavailable())
        assertEquals(JiraConnectionAlarmChange.RECOVERED, tracker.observe(JiraConnectionAlarm.classify(JiraApiException("auth", 401))))
        assertFalse(tracker.isUnavailable())
        assertEquals(JiraConnectionAlarmChange.STARTED, tracker.observe(JiraConnectionAlarm.classify(JiraApiException("503", 503))))
    }
}
