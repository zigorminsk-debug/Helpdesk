package com.helpdesk.jiraagent.domain

import kotlinx.coroutines.delay

/** Inputs supplied by Android; no numbers or conversation contents are collected. */
data class CallSignals(val phoneBusy: Boolean, val communicationMode: Boolean, val callPlayback: Boolean)

object CallSpeechGuard {
    fun shouldSuppress(signals: CallSignals): Boolean = signals.phoneBusy || signals.communicationMode || signals.callPlayback

    /** A call observed at any point drops this announcement, rather than replaying it later. */
    suspend fun awaitSafeDelay(delayMs: Long, isBusy: () -> Boolean, intervalMs: Long = 200L): Boolean {
        if (isBusy()) return false
        var remaining = delayMs.coerceAtLeast(0)
        while (remaining > 0) {
            val step = minOf(remaining, intervalMs.coerceAtLeast(1))
            delay(step)
            if (isBusy()) return false
            remaining -= step
        }
        return !isBusy()
    }
}
