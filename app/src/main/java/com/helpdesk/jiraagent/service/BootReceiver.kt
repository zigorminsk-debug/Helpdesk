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

        // Always re-arm exact alarms for any postponed tickets across reboots or APK updates
        PostponeScheduler.scheduleNextAlarm(context)

        val hasPostponed = repo.postponedTickets.value.isNotEmpty()
        if ((!settings.isConfigured || !settings.pollingEnabled) && !hasPostponed) return

        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON" -> {
                if (settings.startOnBoot || hasPostponed) {
                    JiraMonitorService.syncServiceState(context)
                }
            }
            // Automatically resume agent right after installing a newer APK version over the top
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                JiraMonitorService.syncServiceState(context)
            }
        }
    }
}
