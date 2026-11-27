package com.helpdesk.jiraagent

import com.helpdesk.jiraagent.domain.CallSignals
import com.helpdesk.jiraagent.domain.CallSpeechGuard
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallSpeechGuardTest {
    @Test fun phoneAndMessengerSignalsSuppressOnlyWhenActive() {
        assertFalse(CallSpeechGuard.shouldSuppress(CallSignals(false, false, false)))
        assertTrue(CallSpeechGuard.shouldSuppress(CallSignals(true, false, false)))
        assertTrue(CallSpeechGuard.shouldSuppress(CallSignals(false, true, false)))
        assertTrue(CallSpeechGuard.shouldSuppress(CallSignals(false, false, true)))
    }

    @Test fun ringingAtArrivalOrDuringDelayDropsAnnouncement() = runBlocking {
        assertFalse(CallSpeechGuard.awaitSafeDelay(0, { true }, 1))
        var checks = 0
        assertFalse(CallSpeechGuard.awaitSafeDelay(8, { ++checks == 3 }, 1))
        assertTrue(CallSpeechGuard.awaitSafeDelay(3, { false }, 1))
        assertTrue(CallSpeechGuard.awaitSafeDelay(0, { false }, 1))
    }
}
