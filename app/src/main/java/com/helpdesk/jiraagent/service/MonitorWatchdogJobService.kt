package com.helpdesk.jiraagent.service

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import com.helpdesk.jiraagent.data.SettingsRepository

/**
 * Periodic `JobScheduler` watchdog (`setPersisted(true)`, 15 min) that acts as a secondary
 * safety net if aggressive OEM task killers (Xiaomi HyperOS/MIUI, Huawei EMUI, Samsung OneUI)
 * terminate [JiraMonitorService] overnight while the device is idle.
 */
class MonitorWatchdogJobService : JobService() {

    override fun onStartJob(params: JobParameters?): Boolean {
        val appContext = applicationContext
        val repo = SettingsRepository.getInstance(appContext)
        PostponeScheduler.scheduleNextAlarm(appContext)
        ShiftWakeupScheduler.scheduleNextWakeup(appContext)
        if (JiraMonitorService.isMonitoringWanted(repo)) {
            JiraMonitorService.triggerWakeupPoll(appContext)
        }
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean = true

    companion object {
        private const val JOB_ID_MONITOR_WATCHDOG = 4905
        private const val WATCHDOG_INTERVAL_MS = 15L * 60_000L

        fun ensureScheduled(context: Context) {
            val appContext = context.applicationContext
            val scheduler = appContext.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return
            val existing = runCatching { scheduler.getPendingJob(JOB_ID_MONITOR_WATCHDOG) }.getOrNull()
            if (existing != null) return

            val component = ComponentName(appContext, MonitorWatchdogJobService::class.java)
            val jobInfo = JobInfo.Builder(JOB_ID_MONITOR_WATCHDOG, component)
                .setPeriodic(WATCHDOG_INTERVAL_MS)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .build()
            runCatching { scheduler.schedule(jobInfo) }
        }

        fun cancelWatchdog(context: Context) {
            val appContext = context.applicationContext
            val scheduler = appContext.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return
            runCatching { scheduler.cancel(JOB_ID_MONITOR_WATCHDOG) }
        }
    }
}
