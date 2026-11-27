package com.helpdesk.jiraagent.service

import android.annotation.SuppressLint
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
import java.util.concurrent.ConcurrentHashMap

object PostponeScheduler {

    private const val ALARM_REQUEST_CODE = 4901
    private val processingMutex = Mutex()
    private val retryBackoffById = ConcurrentHashMap<String, Pair<Int, Long>>()

    fun clearRetryBackoff() {
        retryBackoffById.clear()
    }

    fun nextEffectiveTriggerMillis(context: Context, nowMillis: Long = System.currentTimeMillis()): Long? {
        val items = SettingsRepository.getInstance(context.applicationContext).postponedTickets.value
        if (items.isEmpty()) return null
        return items.minOfOrNull { item ->
            val backoffUntil = retryBackoffById[item.id]?.second ?: 0L
            maxOf(item.scheduledAtMillis, backoffUntil)
        }?.coerceAtLeast(nowMillis + 5_000L)
    }

    @SuppressLint("ScheduleExactAlarm", "MissingPermission")
    fun scheduleNextAlarm(context: Context) {
        val appContext = context.applicationContext
        val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pendingIntent = buildAlarmPendingIntent(appContext)

        val now = System.currentTimeMillis()
        val triggerAt = nextEffectiveTriggerMillis(appContext, now)
        if (triggerAt == null) {
            runCatching { alarmManager.cancel(pendingIntent) }
            return
        }

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

        // Allow 1.5s tolerance so exact wake-up alarms firing milliseconds early execute immediately,
        // while respecting exponential retry backoff for items that failed when offline
        val dueItems = repo.postponedTickets.value.filter { item ->
            val backoffUntil = retryBackoffById[item.id]?.second ?: 0L
            item.scheduledAtMillis <= now + 1_500L && now >= backoffUntil
        }
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
                retryBackoffById.remove(item.id)
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
                val attempts = (retryBackoffById[item.id]?.first ?: 0) + 1
                val delayMs = when (attempts) {
                    1 -> 60_000L
                    2 -> 180_000L
                    3 -> 600_000L
                    else -> 1_800_000L
                }
                retryBackoffById[item.id] = attempts to (System.currentTimeMillis() + delayMs)
                repo.updatePostponedTicketError(
                    item.id,
                    err.message ?: "Ошибка пересоздания в Jira (повтор при восстановлении связи)"
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
