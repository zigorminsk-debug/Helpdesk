package com.helpdesk.jiraagent.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.helpdesk.jiraagent.data.SettingsRepository

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val repo = SettingsRepository.getInstance(context)
        val settings = repo.appSettings.value

        // Always re-arm exact alarms for any postponed tickets & 09:00 RB shift wakeup across reboots, clock/timezone changes, or APK updates
        PostponeScheduler.scheduleNextAlarm(context)
        ShiftWakeupScheduler.scheduleNextWakeup(context)

        val hasPostponed = repo.postponedTickets.value.isNotEmpty()
        val hasWatchlist = repo.watchlistHosts.value.isNotEmpty()
        val jiraActive = settings.isConfigured && settings.pollingEnabled
        val zabbixActive = settings.isZabbixConfigured && settings.zabbixEnabled && settings.pollingEnabled
        if (!jiraActive && !zabbixActive && !hasPostponed && !hasWatchlist && !settings.isConfluenceReady) return

        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON" -> {
                if (settings.startOnBoot || hasPostponed || hasWatchlist) {
                    JiraMonitorService.syncServiceState(context)
                }
            }
            // Automatically resume agent right after installing a newer APK version or system clock adjustment
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED -> {
                JiraMonitorService.syncServiceState(context)
            }
        }
    }
}
