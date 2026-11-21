package com.helpdesk.jiraagent.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.helpdesk.jiraagent.data.JiraApiClient
import com.helpdesk.jiraagent.data.SettingsRepository
import com.helpdesk.jiraagent.notification.NotificationHelper
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object PostponeScheduler {

    private const val ALARM_REQUEST_CODE = 4901
    private val processingMutex = Mutex()

    fun scheduleNextAlarm(context: Context) {
        val appContext = context.applicationContext
        val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pendingIntent = buildAlarmPendingIntent(appContext)

        val nextDue = SettingsRepository.getInstance(appContext)
            .postponedTickets
            .value
            .minByOrNull { it.scheduledAtMillis }

        if (nextDue == null) {
            runCatching { alarmManager.cancel(pendingIntent) }
            return
        }

        val triggerAt = nextDue.scheduledAtMillis.coerceAtLeast(System.currentTimeMillis() + 1_000L)
        runCatching {
            val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                alarmManager.canScheduleExactAlarms()
            if (canExact) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAt,
                    pendingIntent
                )
            } else {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAt,
                    pendingIntent
                )
            }
        }.onFailure {
            runCatching {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAt,
                    pendingIntent
                )
            }
        }
    }

    /**
     * Checks all postponed tickets whose scheduled time has arrived, recreates them in Jira,
     * puts each new ticket into "Новые", and displays a notification banner with sound.
     */
    suspend fun processDuePostponedTickets(
        context: Context,
        apiClient: JiraApiClient
    ): Int = processingMutex.withLock {
        val appContext = context.applicationContext
        val repo = SettingsRepository.getInstance(appContext)
        val now = System.currentTimeMillis()

        // Allow 1.5s tolerance so exact wake-up alarms firing milliseconds early execute immediately
        val dueItems = repo.postponedTickets.value.filter { it.scheduledAtMillis <= now + 1_500L }
        if (dueItems.isEmpty()) {
            scheduleNextAlarm(appContext)
            return@withLock 0
        }

        val settings = repo.appSettings.value
        val filter = repo.filterSettings.value
        var recreatedCount = 0

        for (item in dueItems) {
            val res = apiClient.recreatePostponedTicket(item, settings, filter)
            res.onSuccess { recreatedTicket ->
                repo.registerRecreatedPostponedTicket(recreatedTicket)
                repo.removePostponedTicket(item.id)
                NotificationHelper.showClientTicketBanner(
                    context = appContext,
                    ticket = recreatedTicket,
                    bypassWorkSchedule = true,
                    customTitleSuffix = "Возврат отложенной (${item.originalKey})"
                )
                recreatedCount++
            }.onFailure { err ->
                repo.updatePostponedTicketError(
                    item.id,
                    err.message ?: "Ошибка пересоздания в Jira (повтор при следующем опросе)"
                )
            }
        }

        scheduleNextAlarm(appContext)
        recreatedCount
    }

    private fun buildAlarmPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, PostponeAlarmReceiver::class.java).apply {
            action = PostponeAlarmReceiver.ACTION_ALARM_TRIGGER
        }
        return PendingIntent.getBroadcast(
            context,
            ALARM_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
