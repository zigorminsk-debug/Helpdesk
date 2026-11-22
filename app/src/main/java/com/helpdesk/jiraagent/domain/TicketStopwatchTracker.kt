package com.helpdesk.jiraagent.domain

import java.util.Locale

data class TicketStopwatchEntry(
    val ticketKey: String = "",
    val accumulatedMillis: Long = 0L,
    val runningSinceEpochMs: Long? = null
) {
    val isRunning: Boolean
        get() = runningSinceEpochMs != null

    fun totalElapsedMillis(nowEpochMs: Long = System.currentTimeMillis()): Long {
        val liveDelta = runningSinceEpochMs?.let { (nowEpochMs - it).coerceAtLeast(0L) } ?: 0L
        return (accumulatedMillis + liveDelta).coerceAtLeast(0L)
    }

    fun roundedMinutesForWorklog(nowEpochMs: Long = System.currentTimeMillis()): Int {
        val totalMs = totalElapsedMillis(nowEpochMs)
        if (totalMs <= 0L) return 0
        // Round up to at least 1 minute once started, nearest ceil minute
        return ((totalMs + 59_999L) / 60_000L).toInt().coerceAtLeast(1)
    }

    fun formatTimerDisplay(nowEpochMs: Long = System.currentTimeMillis()): String {
        val totalSec = (totalElapsedMillis(nowEpochMs) / 1000L).coerceAtLeast(0L)
        val hours = totalSec / 3600L
        val mins = (totalSec % 3600L) / 60L
        val secs = totalSec % 60L
        return if (hours > 0L) {
            String.format(Locale.US, "%d:%02d:%02d", hours, mins, secs)
        } else {
            String.format(Locale.US, "%02d:%02d", mins, secs)
        }
    }
}

object TicketStopwatchTracker {

    fun formatTimerDisplay(
        entry: TicketStopwatchEntry?,
        nowEpochMs: Long = System.currentTimeMillis()
    ): String = entry?.formatTimerDisplay(nowEpochMs) ?: "00:00"

    fun roundedMinutesForWorklog(
        entry: TicketStopwatchEntry?,
        nowEpochMs: Long = System.currentTimeMillis()
    ): Int = entry?.roundedMinutesForWorklog(nowEpochMs) ?: 0

    fun startOrResume(
        map: Map<String, TicketStopwatchEntry>,
        ticketKey: String,
        nowEpochMs: Long = System.currentTimeMillis()
    ): Map<String, TicketStopwatchEntry> {
        val key = ticketKey.trim().uppercase(Locale.ROOT)
        if (key.isEmpty()) return map
        val existing = map[key]
        if (existing != null && existing.runningSinceEpochMs != null) {
            return map
        }
        val next = (existing ?: TicketStopwatchEntry(ticketKey = key)).copy(
            runningSinceEpochMs = nowEpochMs
        )
        return map + (key to next)
    }

    fun pause(
        map: Map<String, TicketStopwatchEntry>,
        ticketKey: String,
        nowEpochMs: Long = System.currentTimeMillis()
    ): Map<String, TicketStopwatchEntry> {
        val key = ticketKey.trim().uppercase(Locale.ROOT)
        val existing = map[key] ?: return map
        val started = existing.runningSinceEpochMs ?: return map
        val delta = (nowEpochMs - started).coerceAtLeast(0L)
        val next = existing.copy(
            accumulatedMillis = existing.accumulatedMillis + delta,
            runningSinceEpochMs = null
        )
        return map + (key to next)
    }

    fun stopAndClear(
        map: Map<String, TicketStopwatchEntry>,
        ticketKey: String
    ): Map<String, TicketStopwatchEntry> {
        val key = ticketKey.trim().uppercase(Locale.ROOT)
        if (key !in map) return map
        return map - key
    }
}
