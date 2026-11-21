package com.helpdesk.jiraagent

import android.app.Application
import com.helpdesk.jiraagent.notification.NotificationHelper

class HelpdeskApp : Application() {
    override fun onCreate() {
        super.onCreate()
        NotificationHelper.ensureChannels(this)
    }
}
