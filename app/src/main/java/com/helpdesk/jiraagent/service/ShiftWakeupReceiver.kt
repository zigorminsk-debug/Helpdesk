package com.helpdesk.jiraagent.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import com.helpdesk.jiraagent.data.SettingsRepository

/**
 * Receives `AlarmManager.RTC_WAKEUP` exact alarms scheduled by [ShiftWakeupScheduler]
 * (including the `09:00:02` RB morning shift start and working-hours Doze heartbeat pulses).
 *
 * Acquires a brief `PARTIAL_WAKE_LOCK` so Android Doze cannot re-suspend the CPU before
 * [JiraMonitorService] wakes its polling loop and completes the Jira/Zabbix HTTP fetch.
 */
class ShiftWakeupReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val appContext = context.applicationContext
        val pm = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wakeLock = runCatching {
            pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HelpCSL:ShiftWakeupReceiver")?.apply {
                setReferenceCounted(false)
                acquire(20_000L)
            }
        }.getOrNull()

        try {
            val repo = SettingsRepository.getInstance(appContext)
            PostponeScheduler.scheduleNextAlarm(appContext)
            ShiftWakeupScheduler.scheduleNextWakeup(appContext)
            if (JiraMonitorService.isMonitoringWanted(repo)) {
                JiraMonitorService.triggerWakeupPoll(appContext)
            }
        } finally {
            // Leave the WakeLock alive for up to its 20s timeout unless service isn't wanted;
            // JiraMonitorService acquires its own poll-scoped WakeLock once started.
            if (!JiraMonitorService.isMonitoringWanted(SettingsRepository.getInstance(appContext))) {
                runCatching { if (wakeLock?.isHeld == true) wakeLock.release() }
            }
        }
    }

    companion object {
        const val ACTION_SHIFT_WAKEUP_ALARM = "com.helpdesk.jiraagent.action.SHIFT_WAKEUP_ALARM"
    }
}
