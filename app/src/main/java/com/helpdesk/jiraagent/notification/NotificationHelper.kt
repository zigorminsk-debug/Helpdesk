package com.helpdesk.jiraagent.notification

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.helpdesk.jiraagent.MainActivity
import com.helpdesk.jiraagent.R
import com.helpdesk.jiraagent.data.SettingsRepository
import com.helpdesk.jiraagent.domain.BelarusWorkCalendar
import com.helpdesk.jiraagent.model.HelpdeskTicket

object NotificationHelper {

    const val CHANNEL_CLIENT_BANNER = "help_csl_urgent_banner_v4"
    const val CHANNEL_MONITOR_SERVICE = "jira_agent_service_status_v1"

    const val SERVICE_NOTIFICATION_ID = 1001
    const val EXTRA_OPEN_TICKET_KEY = "extra_open_ticket_key"

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // High-priority urgent channel for floating heads-up banners.
        // Channel sound is set to null because AlertSoundPlayer directly plays the user's selected
        // melody reliably across Xiaomi MIUI/HyperOS, Samsung, Huawei, and Pixel devices.
        val bannerChannel = NotificationChannel(
            CHANNEL_CLIENT_BANNER,
            "Новые заявки Help-CSL (срочный баннер)",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Срочные всплывающие баннеры о новых заявках клиентов в Help-CSL"
            enableVibration(true)
            vibrationPattern = longArrayOf(0L, 280L, 120L, 320L)
            enableLights(true)
            lightColor = 0xFFF7631B.toInt()
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setBypassDnd(true)
            setSound(null, null)
        }

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

    /**
     * Opens the system channel settings screen so Xiaomi MIUI / HyperOS users can toggle
     * "Всплывающие уведомления" in 1 tap if restricted by the firmware.
     */
    fun openBannerChannelSettings(context: Context) {
        ensureChannels(context)
        val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL_CLIENT_BANNER)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching {
            context.startActivity(intent)
        }.onFailure {
            val fallback = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(fallback) }
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
     * Displays a Heads-Up Notification Banner + plays the user's selected alert melody
     * + shows an in-app banner if Help-CSL is open on screen.
     */
    @SuppressLint("MissingPermission")
    fun showClientTicketBanner(
        context: Context,
        ticket: HelpdeskTicket,
        bypassWorkSchedule: Boolean = false,
        customTitleSuffix: String? = null
    ): Boolean {
        if (!ticket.isClientTicket) return false

        val repo = SettingsRepository.getInstance(context)
        val appSettings = repo.appSettings.value

        if (!bypassWorkSchedule && !BelarusWorkCalendar.isWorkingTimeNow(appSettings)) {
            return false
        }

        val titleSuffix = customTitleSuffix ?: "Заявка от клиента"

        // 1. Always emit in-app top floating banner (visible immediately when Help-CSL is in foreground)
        repo.emitInAppBanner(ticket, titleSuffix)

        // 2. Always play the user's selected alert melody & vibrate via AlertSoundPlayer
        AlertSoundPlayer.playTicketAlert(context, appSettings, forcePreview = false)

        if (!hasNotificationPermission(context)) return true

        ensureChannels(context)

        val openTicketIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
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

        val clientLabel = ticket.reporterName.ifBlank { "Клиент" }
        val builder = NotificationCompat.Builder(context, CHANNEL_CLIENT_BANNER)
            .setSmallIcon(R.drawable.ic_notification_ticket)
            .setColor(0xFFF7631B.toInt())
            .setContentTitle("$clientLabel · $titleSuffix")
            .setContentText(
                if (primaryPhone != null) {
                    "${ticket.summary} (${primaryPhone.formattedDisplay}) · № ${ticket.key}"
                } else {
                    "${ticket.summary} · № ${ticket.key}"
                }
            )
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setVibrate(longArrayOf(0L, 280L, 120L, 320L))
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .setAutoCancel(true)
            .setContentIntent(openPendingIntent)
            .setFullScreenIntent(openPendingIntent, true)

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
