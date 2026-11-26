package com.helpdesk.jiraagent.service

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.helpdesk.jiraagent.MainActivity
import com.helpdesk.jiraagent.data.SettingsRepository
import com.helpdesk.jiraagent.domain.BelarusWorkCalendar
import com.helpdesk.jiraagent.model.AppSettings
import java.time.Instant

/**
 * Hardware wall-clock (`AlarmManager.RTC_WAKEUP`) scheduler that guarantees Help-CSL:
 * 1. Wakes up out of overnight Android Deep Doze right on the dot at `09:00:02` Minsk time
 *    at the start of every RB working shift (instead of relying on `Coroutine.delay` /
 *    `SystemClock.uptimeMillis()`, which freezes when the AP CPU suspends).
 * 2. Maintains a lightweight Doze-piercing watchdog pulse during active working hours so
 *    OEM battery savers (MIUI/HyperOS, OneUI, EMUI) cannot silently stall ticket notifications.
 */
object ShiftWakeupScheduler {

    private const val REQUEST_CODE_SHIFT_WAKEUP = 4902
    private const val REQUEST_CODE_SHOW_APP = 4903

    /**
     * Pure calculation returning `(triggerAtEpochMillis, isMorningShiftStart)`.
     */
    fun computeNextWakeupEpochMillis(
        settings: AppSettings,
        nowMillis: Long = System.currentTimeMillis()
    ): Pair<Long, Boolean> {
        val nowInstant = Instant.ofEpochMilli(nowMillis)
        val heartbeatMs = (settings.pollIntervalSeconds.coerceIn(10, 3600) * 1_000L * 3L)
            .coerceIn(90_000L, 240_000L)

        if (BelarusWorkCalendar.isCalendarWorkingHoursNow(settings, nowInstant)) {
            return (nowMillis + heartbeatMs) to false
        }

        val nextShiftStartMs = BelarusWorkCalendar.nextWorkingWindowStartEpochMillis(settings, nowInstant)
        return when (val offMinutes = settings.normalizedOffHoursPollMinutes) {
            AppSettings.OFF_HOURS_POLL_SAME_AS_WORK -> {
                val candidate = nowMillis + heartbeatMs
                if (candidate >= nextShiftStartMs - 10_000L) {
                    nextShiftStartMs to true
                } else {
                    candidate to false
                }
            }

            AppSettings.OFF_HOURS_POLL_DISABLED -> {
                nextShiftStartMs to true
            }

            else -> {
                val offIntervalMs = offMinutes * 60_000L
                val candidate = nowMillis + offIntervalMs
                // Snap directly to 09:00:02 if an off-hours alarm would land just before shift start
                if (candidate >= nextShiftStartMs - 15_000L) {
                    nextShiftStartMs to true
                } else {
                    candidate to false
                }
            }
        }
    }

    @SuppressLint("ScheduleExactAlarm", "MissingPermission")
    fun scheduleNextWakeup(context: Context) {
        val appContext = context.applicationContext
        val repo = SettingsRepository.getInstance(appContext)
        val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pendingIntent = buildWakeupPendingIntent(appContext)

        if (!JiraMonitorService.isMonitoringWanted(repo)) {
            runCatching { alarmManager.cancel(pendingIntent) }
            MonitorWatchdogJobService.cancelWatchdog(appContext)
            return
        }

        MonitorWatchdogJobService.ensureScheduled(appContext)

        val nowMs = System.currentTimeMillis()
        val (rawTriggerAt, isMorningShiftStart) = computeNextWakeupEpochMillis(repo.appSettings.value, nowMs)
        val triggerAt = rawTriggerAt.coerceAtLeast(nowMs + 5_000L)

        runCatching {
            val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                alarmManager.canScheduleExactAlarms()
            when {
                canExact -> {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerAt,
                        pendingIntent
                    )
                }

                isMorningShiftStart -> {
                    // Guaranteed Doze-piercing fallback if exact-alarm appop was manually turned off
                    val showIntent = PendingIntent.getActivity(
                        appContext,
                        REQUEST_CODE_SHOW_APP,
                        Intent(appContext, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        },
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    alarmManager.setAlarmClock(
                        AlarmManager.AlarmClockInfo(triggerAt, showIntent),
                        pendingIntent
                    )
                }

                else -> {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerAt,
                        pendingIntent
                    )
                }
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

    private fun buildWakeupPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, ShiftWakeupReceiver::class.java).apply {
            action = ShiftWakeupReceiver.ACTION_SHIFT_WAKEUP_ALARM
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_SHIFT_WAKEUP,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
