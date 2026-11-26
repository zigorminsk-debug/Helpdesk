package com.helpdesk.jiraagent

import android.app.Application
import com.helpdesk.jiraagent.notification.NotificationHelper
import com.helpdesk.jiraagent.service.ShiftWakeupScheduler

class HelpdeskApp : Application() {
    override fun onCreate() {
        super.onCreate()
        NotificationHelper.ensureChannels(this)
        runCatching { ShiftWakeupScheduler.scheduleNextWakeup(this) }
    }
}
