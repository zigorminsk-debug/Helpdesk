package com.helpdesk.jiraagent.notification

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.helpdesk.jiraagent.MainActivity
import com.helpdesk.jiraagent.R
import com.helpdesk.jiraagent.data.SettingsRepository
import com.helpdesk.jiraagent.domain.BelarusWorkCalendar
import com.helpdesk.jiraagent.model.HelpdeskTicket

object NotificationHelper {

    const val CHANNEL_CLIENT_BANNER = "jira_client_tickets_banner_v2"
    const val CHANNEL_MONITOR_SERVICE = "jira_agent_service_status_v1"

    const val SERVICE_NOTIFICATION_ID = 1001
    const val EXTRA_OPEN_TICKET_KEY = "extra_open_ticket_key"

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // High-importance channel for Heads-Up Notification Banners of client tickets
        val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val audioAttrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        val bannerChannel = NotificationChannel(
            CHANNEL_CLIENT_BANNER,
            "Заявки клиентов (всплывающий баннер)",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Всплывающие баннеры при поступлении новых заявок от клиентов"
            enableVibration(true)
            vibrationPattern = longArrayOf(0L, 300L, 150L, 300L)
            enableLights(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setSound(soundUri, audioAttrs)
        }

        // Silent low-importance channel for persistent background polling status
        val statusChannel = NotificationChannel(
            CHANNEL_MONITOR_SERVICE,
            "Фоновый мониторинг Help-CSL",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Тихий индикатор работы фонового агента Help-CSL"
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }

        manager.createNotificationChannels(listOf(bannerChannel, statusChannel))
    }

    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
    }

    fun buildForegroundStatusNotification(
        context: Context,
        statusText: String
    ): Notification {
        ensureChannels(context)
        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_MONITOR_SERVICE)
            .setSmallIcon(R.drawable.ic_notification_ticket)
            .setColor(0xFFF7631B.toInt())
            .setContentTitle("Help-CSL активен")
            .setContentText(statusText)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    /**
     * Displays a Heads-Up Notification Banner + sound signal for a client ticket.
     * Strictly respects the Republic of Belarus working days calendar and 09:00–17:30 working hours
     * unless [bypassWorkSchedule] is explicitly requested (e.g. manual test button or user-scheduled postponed ticket).
     */
    fun showClientTicketBanner(
        context: Context,
        ticket: HelpdeskTicket,
        bypassWorkSchedule: Boolean = false,
        customTitleSuffix: String? = null
    ): Boolean {
        if (!ticket.isClientTicket) return false
        if (!hasNotificationPermission(context)) return false

        if (!bypassWorkSchedule) {
            val appSettings = SettingsRepository.getInstance(context).appSettings.value
            if (!BelarusWorkCalendar.isWorkingTimeNow(appSettings)) {
                return false
            }
        }

        ensureChannels(context)

        val openTicketIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_TICKET_KEY, ticket.key)
        }
        val requestCode = ticket.key.hashCode()
        val openPendingIntent = PendingIntent.getActivity(
            context,
            requestCode,
            openTicketIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val primaryPhone = ticket.phoneNumbers.firstOrNull()
        val bigText = buildString {
            append(ticket.summary)
            append("\nКлиент: ").append(ticket.reporterName)
            if (primaryPhone != null) {
                append("\nТел.: ").append(primaryPhone.formattedDisplay)
            }
            if (ticket.description.isNotBlank()) {
                val snippet = ticket.description.replace('\n', ' ').take(180)
                append("\n").append(snippet)
                if (ticket.description.length > 180) append("…")
            }
        }

        val titleSuffix = customTitleSuffix ?: "Заявка от клиента"
        val builder = NotificationCompat.Builder(context, CHANNEL_CLIENT_BANNER)
            .setSmallIcon(R.drawable.ic_notification_ticket)
            .setColor(0xFFF7631B.toInt())
            .setContentTitle("${ticket.key} · $titleSuffix")
            .setContentText(
                if (primaryPhone != null) {
                    "${ticket.summary} (${primaryPhone.formattedDisplay})"
                } else {
                    ticket.summary
                }
            )
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(openPendingIntent)

        // Direct "Позвонить" button right on the Heads-Up banner when a phone number is detected
        if (primaryPhone != null) {
            val dialIntent = Intent(
                Intent.ACTION_DIAL,
                Uri.parse("tel:${Uri.encode(primaryPhone.e164)}")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val dialPendingIntent = PendingIntent.getActivity(
                context,
                requestCode + 1,
                dialIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                android.R.drawable.ic_menu_call,
                "Позвонить ${primaryPhone.formattedDisplay}",
                dialPendingIntent
            )
        }

        builder.addAction(
            0,
            "Открыть заявку",
            openPendingIntent
        )

        NotificationManagerCompat.from(context).notify(
            2000 + (ticket.key.hashCode() and 0x7FFFFFFF) % 100_000,
            builder.build()
        )
        return true
    }
}
