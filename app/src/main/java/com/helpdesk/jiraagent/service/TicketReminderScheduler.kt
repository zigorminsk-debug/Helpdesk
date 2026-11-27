package com.helpdesk.jiraagent.service

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.helpdesk.jiraagent.MainActivity
import com.helpdesk.jiraagent.R
import com.helpdesk.jiraagent.data.SettingsRepository
import com.helpdesk.jiraagent.domain.TicketReminder
import com.helpdesk.jiraagent.domain.TicketReminderRules
import com.helpdesk.jiraagent.notification.AlertSoundPlayer
import com.helpdesk.jiraagent.notification.NotificationHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** One local queue/alarm, restored on boot; no Jira writes or network dependency. */
object TicketReminderScheduler {
    private const val PREFS = "personal_ticket_reminders"
    private const val KEY = "items"
    private val gson = Gson()
    private val state = MutableStateFlow<List<TicketReminder>>(emptyList())
    private var loaded = false

    @Synchronized fun observe(context: Context): StateFlow<List<TicketReminder>> { load(context); return state }
    private fun load(context: Context) {
        if (loaded) return
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]")
        val type = object : TypeToken<List<TicketReminder>>() {}.type
        state.value = runCatching { gson.fromJson<List<TicketReminder>>(json, type) ?: emptyList() }.getOrDefault(emptyList())
        loaded = true
    }
    private fun save(context: Context, items: List<TicketReminder>) {
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, gson.toJson(items)).commit()) { "Не удалось сохранить напоминание" }
        state.value = items
    }
    @Synchronized fun set(context: Context, item: TicketReminder) {
        load(context); save(context, TicketReminderRules.upsert(state.value, item, System.currentTimeMillis())); schedule(context)
    }
    @Synchronized fun cancel(context: Context, key: String) {
        load(context); save(context, TicketReminderRules.remove(state.value, key)); schedule(context)
    }
    @Synchronized fun clear(context: Context) { load(context); save(context, emptyList()); schedule(context) }
    @SuppressLint("ScheduleExactAlarm")
    @Synchronized fun schedule(context: Context, retryAfterMs: Long = 0L) {
        load(context)
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pending = PendingIntent.getBroadcast(context, 4912, Intent(context, TicketReminderReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val next = state.value.minOfOrNull { it.atMillis }
        if (next == null) { alarm.cancel(pending); return }
        val whenMs = maxOf(next, System.currentTimeMillis() + maxOf(1_000L, retryAfterMs))
        runCatching {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarm.canScheduleExactAlarms()) alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenMs, pending)
            else alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenMs, pending)
        }.onFailure { alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenMs, pending) }
    }
    @SuppressLint("MissingPermission")
    @Synchronized fun deliver(context: Context) {
        load(context)
        val due = TicketReminderRules.due(state.value, System.currentTimeMillis())
        if (due.isEmpty()) { schedule(context); return }
        if (!NotificationHelper.hasNotificationPermission(context) || !NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            schedule(context, 15 * 60_000L); return
        }
        NotificationHelper.ensureChannels(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager)
                .getNotificationChannel(NotificationHelper.CHANNEL_CLIENT_BANNER)?.importance == android.app.NotificationManager.IMPORTANCE_NONE) {
            schedule(context, 15 * 60_000L); return
        }
        var remaining = state.value
        for (item in due) {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(NotificationHelper.EXTRA_OPEN_TICKET_KEY, item.ticketKey)
                data = android.net.Uri.parse("helpcsl://reminder/${android.net.Uri.encode(item.ticketKey)}")
            }
            val pending = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification = NotificationCompat.Builder(context, NotificationHelper.CHANNEL_CLIENT_BANNER)
                .setSmallIcon(R.drawable.ic_notification_ticket).setContentTitle("⏰ Напоминание · ${item.ticketKey}")
                .setContentText(item.summary).setStyle(NotificationCompat.BigTextStyle().bigText(item.summary))
                .setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true).setContentIntent(pending).build()
            val sent = runCatching { NotificationManagerCompat.from(context).notify(300_000 + (item.ticketKey.hashCode() and 0x7fffffff) % 100_000, notification) }.isSuccess
            if (sent) remaining = TicketReminderRules.remove(remaining, item.ticketKey)
        }
        if (remaining.size != state.value.size) {
            save(context, remaining)
            AlertSoundPlayer.playTicketAlert(context, SettingsRepository.getInstance(context).appSettings.value)
        }
        schedule(context, if (remaining.any { it.atMillis <= System.currentTimeMillis() }) 15 * 60_000L else 0)
    }
}

class TicketReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) { TicketReminderScheduler.deliver(context.applicationContext) }
}
