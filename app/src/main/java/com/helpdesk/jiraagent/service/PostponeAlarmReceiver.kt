package com.helpdesk.jiraagent.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class PostponeAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        JiraMonitorService.triggerPostponedCheck(context.applicationContext)
    }

    companion object {
        const val ACTION_ALARM_TRIGGER = "com.helpdesk.jiraagent.action.POSTPONE_ALARM_TRIGGER"
    }
}
