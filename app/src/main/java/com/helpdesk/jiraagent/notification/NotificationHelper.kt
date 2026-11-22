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
import com.helpdesk.jiraagent.model.AdminChatMessage
import com.helpdesk.jiraagent.model.HelpdeskTicket
import com.helpdesk.jiraagent.model.ZabbixProblem
import com.helpdesk.jiraagent.update.GitHubReleaseUpdate

object NotificationHelper {

    const val CHANNEL_CLIENT_BANNER = "help_csl_urgent_banner_v4"
    const val CHANNEL_ADMIN_CHAT = "help_csl_admin_chat_v1"
    const val CHANNEL_HOST_MONITOR = "help_csl_host_monitor_v1"
    const val CHANNEL_MONITOR_SERVICE = "jira_agent_service_status_v1"

    const val SERVICE_NOTIFICATION_ID = 1001
    const val UPDATE_NOTIFICATION_ID = 1002
    const val SECURITY_WIPE_NOTIFICATION_ID = 1009
    const val EXTRA_OPEN_TICKET_KEY = "extra_open_ticket_key"
    const val EXTRA_TRIGGER_APP_UPDATE = "extra_trigger_app_update"
    const val EXTRA_OPEN_ADMIN_CHAT = "extra_open_admin_chat"
    const val EXTRA_OPEN_WATCHLIST_MONITOR = "extra_open_watchlist_monitor"
    const val EXTRA_OPEN_ZABBIX_TAB = "extra_open_zabbix_tab"
    const val EXTRA_OPEN_ZABBIX_EVENT_ID = "extra_open_zabbix_event_id"

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

        val chatChannel = NotificationChannel(
            CHANNEL_ADMIN_CHAT,
            "Внутренний чат админов Help-CSL",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Сообщения коллег во внутреннем чате техподдержки"
            enableVibration(true)
            lightColor = 0xFFF7631B.toInt()
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }

        val hostMonitorChannel = NotificationChannel(
            CHANNEL_HOST_MONITOR,
            "Мониторинг узлов SSL / Офлайн-оповещения",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Уведомления о недоступности внесённых узлов и серверов (проверка раз в 5 минут)"
            enableVibration(true)
            vibrationPattern = longArrayOf(0L, 250L, 120L, 250L)
            enableLights(true)
            lightColor = 0xFFD93025.toInt()
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }

        manager.createNotificationChannels(
            listOf(bannerChannel, chatChannel, hostMonitorChannel, statusChannel)
        )
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
        TicketSpeechAnnouncer.announceNewTicketIfEnabled(context, ticket, appSettings)

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

        val notificationId = 2000 + (ticket.key.hashCode() and 0x7FFFFFFF) % 100_000

        val takeInWorkIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_TAKE_TICKET_IN_WORK
            putExtra(NotificationActionReceiver.EXTRA_TICKET_KEY, ticket.key)
            putExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_ID, notificationId)
        }
        val takePendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode + 2,
            takeInWorkIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        builder.addAction(
            0,
            "В работу",
            takePendingIntent
        )

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
                "Позвонить",
                dialPendingIntent
            )
        }

        builder.addAction(
            0,
            "Открыть",
            openPendingIntent
        )

        NotificationManagerCompat.from(context).notify(
            notificationId,
            builder.build()
        )
        return true
    }

    @SuppressLint("MissingPermission")
    fun showTicketStormBanner(
        context: Context,
        tickets: List<HelpdeskTicket>,
        playSound: Boolean = true
    ) {
        if (tickets.isEmpty()) return
        val repo = SettingsRepository.getInstance(context)
        val settings = repo.appSettings.value
        if (playSound) {
            AlertSoundPlayer.playTicketAlert(context, settings)
            TicketSpeechAnnouncer.announceStormIfEnabled(context, tickets.size, settings)
        }
        tickets.lastOrNull()?.let { latest ->
            repo.emitInAppBanner(latest, "⚡ Шторм заявок: +${tickets.size} новых обращений!")
        }

        if (!hasNotificationPermission(context)) return
        ensureChannels(context)

        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            7700,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val linesText = tickets.take(6).joinToString("\n") {
            "• ${it.reporterName.ifBlank { "Клиент" }} (${it.key}): ${it.summary.take(55)}"
        } + if (tickets.size > 6) "\n…и ещё ${tickets.size - 6} заявок" else ""

        val notification = NotificationCompat.Builder(context, CHANNEL_CLIENT_BANNER)
            .setSmallIcon(R.drawable.ic_notification_ticket)
            .setColor(0xFFF7631B.toInt())
            .setContentTitle("⚡ Шторм заявок: ${tickets.size} новых обращений")
            .setContentText("Сгруппировано, чтобы не перегружать звуковые сигналы")
            .setStyle(NotificationCompat.BigTextStyle().bigText(linesText))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .addAction(0, "Открыть очередь", pendingIntent)
            .build()

        NotificationManagerCompat.from(context).notify(7700, notification)
    }

    @SuppressLint("MissingPermission")
    fun showIncomingCallerIdBanner(
        context: Context,
        callerNumber: String,
        matchedTickets: List<HelpdeskTicket>
    ) {
        if (matchedTickets.isEmpty() || !hasNotificationPermission(context)) return
        ensureChannels(context)

        val primary = matchedTickets.first()
        val clientName = primary.reporterName.ifBlank { "Клиент Jira" }
        val openTicketIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_TICKET_KEY, primary.key)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            8800 + (primary.key.hashCode() and 0xFFF),
            openTicketIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val dossier = SettingsRepository.getInstance(context).getClientDossierNote(clientName)
        val details = buildString {
            append("Заявка ${primary.key} (${primary.status}): ${primary.summary}")
            if (matchedTickets.size > 1) {
                append("\nВсего заявок с этого номера: ${matchedTickets.size}")
            }
            if (dossier.isNotBlank()) {
                append("\nДосье: ").append(dossier.take(120))
            }
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_CLIENT_BANNER)
            .setSmallIcon(R.drawable.ic_notification_ticket)
            .setColor(0xFFF7631B.toInt())
            .setContentTitle("📞 Звонит: $clientName ($callerNumber)")
            .setContentText("${primary.key}: ${primary.summary}")
            .setStyle(NotificationCompat.BigTextStyle().bigText(details))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .addAction(0, "Открыть ${primary.key}", pendingIntent)
            .build()

        NotificationManagerCompat.from(context).notify(8801, notification)
    }

    @SuppressLint("MissingPermission")
    fun showAppUpdateNotification(
        context: Context,
        update: GitHubReleaseUpdate
    ) {
        if (!hasNotificationPermission(context)) return
        ensureChannels(context)

        val updateIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_TRIGGER_APP_UPDATE, true)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            UPDATE_NOTIFICATION_ID,
            updateIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_CLIENT_BANNER)
            .setSmallIcon(R.drawable.ic_notification_ticket)
            .setColor(0xFFF7631B.toInt())
            .setContentTitle("Доступно обновление Help-CSL v${update.versionName}")
            .setContentText("Нажмите, чтобы автоматически скачать и установить новую версию с GitHub")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .addAction(0, "Обновить", pendingIntent)
            .build()

        NotificationManagerCompat.from(context).notify(UPDATE_NOTIFICATION_ID, notification)
    }

    @SuppressLint("MissingPermission")
    fun showAdminChatMessageNotification(
        context: Context,
        message: AdminChatMessage
    ) {
        if (!hasNotificationPermission(context)) return
        ensureChannels(context)

        val openChatIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_ADMIN_CHAT, true)
        }
        val reqCode = 9000 + (message.id.hashCode() and 0x7FFF)
        val pendingIntent = PendingIntent.getActivity(
            context,
            reqCode,
            openChatIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ADMIN_CHAT)
            .setSmallIcon(R.drawable.ic_notification_ticket)
            .setColor(0xFFF7631B.toInt())
            .setContentTitle("Чат техподдержки · ${message.authorDisplayName}")
            .setContentText(message.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message.text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .addAction(0, "Открыть чат", pendingIntent)
            .build()

        NotificationManagerCompat.from(context).notify(
            5000 + (message.id.hashCode() and 0x7FFFFFFF) % 10_000,
            notification
        )
    }

    private fun hostNotificationId(hostKey: String): Int =
        40_000 + (hostKey.lowercase().hashCode() and 0x7FFF)

    @SuppressLint("MissingPermission")
    fun showHostOfflineNotification(
        context: Context,
        hostKey: String,
        shortTitle: String,
        fullDisplayName: String
    ) {
        if (!hasNotificationPermission(context)) return
        ensureChannels(context)

        val openToolboxIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_WATCHLIST_MONITOR, true)
        }
        val notifId = hostNotificationId(hostKey)
        val pendingIntent = PendingIntent.getActivity(
            context,
            notifId,
            openToolboxIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = "🔴 Узел недоступен: $shortTitle"
        val body = "Узел «$fullDisplayName» не доступен (офлайн — нет ответа на фоновую проверку)."

        val notification = NotificationCompat.Builder(context, CHANNEL_HOST_MONITOR)
            .setSmallIcon(R.drawable.ic_notification_ticket)
            .setColor(0xFFD93025.toInt())
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setContentIntent(pendingIntent)
            .addAction(0, "Открыть SSL / Узлы", pendingIntent)
            .build()

        NotificationManagerCompat.from(context).notify(notifId, notification)
    }

    fun cancelHostOfflineNotification(context: Context, hostKey: String) {
        runCatching {
            NotificationManagerCompat.from(context).cancel(hostNotificationId(hostKey))
        }
    }

    @SuppressLint("MissingPermission")
    fun showAccountWipedNotification(context: Context, reason: String) {
        if (!hasNotificationPermission(context)) return
        ensureChannels(context)

        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            SECURITY_WIPE_NOTIFICATION_ID,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val body = "Авторизация на w.csl.by не прошла ($reason). Локальная база Читалки и все записи программы удалены."
        val notification = NotificationCompat.Builder(context, CHANNEL_HOST_MONITOR)
            .setSmallIcon(R.drawable.ic_notification_ticket)
            .setColor(0xFFD93025.toInt())
            .setContentTitle("🔒 Учётная запись отключена — база очищена")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        NotificationManagerCompat.from(context).notify(SECURITY_WIPE_NOTIFICATION_ID, notification)
    }

    /**
     * Displays a client-ticket-style Urgent Heads-Up Banner + plays the configured alert melody
     * + shows the top floating In-App Banner whenever Zabbix reports an Error or Critical Warning.
     */
    @SuppressLint("MissingPermission")
    fun showZabbixAlertBanner(
        context: Context,
        problem: ZabbixProblem,
        bypassWorkSchedule: Boolean = false
    ): Boolean {
        val repo = SettingsRepository.getInstance(context)
        val appSettings = repo.appSettings.value

        if (!bypassWorkSchedule && !BelarusWorkCalendar.isWorkingTimeNow(appSettings)) {
            return false
        }
        if (!bypassWorkSchedule &&
            problem.isHiddenFromActiveFeed(repo.dismissedZabbixKeys.value, repo.currentMutedZabbixKeySet())
        ) {
            return false
        }

        val titleSuffix = problem.severity.bannerSuffix

        // 1. Always emit in-app top floating banner (identical UX to a new client ticket)
        repo.emitZabbixInAppBanner(problem, titleSuffix)

        // 2. Always play the user's selected alert melody & vibrate via AlertSoundPlayer
        AlertSoundPlayer.playTicketAlert(context, appSettings, forcePreview = false)
        TicketSpeechAnnouncer.announceZabbixAlertIfEnabled(context, problem, appSettings)

        if (!hasNotificationPermission(context)) return true
        ensureChannels(context)

        val openZabbixIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_ZABBIX_TAB, true)
            if (problem.eventId.isNotBlank()) {
                putExtra(EXTRA_OPEN_ZABBIX_EVENT_ID, problem.eventId)
            }
        }
        val requestCode = ("zbx_" + problem.alertKey).hashCode()
        val openPendingIntent = PendingIntent.getActivity(
            context,
            requestCode,
            openZabbixIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val bigText = buildString {
            append(problem.name)
            if (problem.opData.isNotBlank()) {
                append(" [").append(problem.opData).append("]")
            }
            append("\nУзел: ").append(problem.hostWithIpLabel)
            append("\nВажность: ").append(problem.severity.ruTitle)
            val startStr = problem.formattedStartTime()
            if (startStr.isNotBlank()) {
                append(" · ").append(startStr)
            }
            if (problem.comments.isNotBlank()) {
                val snippet = problem.comments.replace('\n', ' ').take(150)
                append("\n").append(snippet)
            }
        }

        val accentColor = if (problem.severityLevel >= 4) 0xFFD93025.toInt() else 0xFFF7631B.toInt()
        val notificationId = 140_000 + (problem.alertKey.hashCode() and 0x7FFFFFFF) % 40_000

        val builder = NotificationCompat.Builder(context, CHANNEL_CLIENT_BANNER)
            .setSmallIcon(R.drawable.ic_notification_ticket)
            .setColor(accentColor)
            .setContentTitle("🚨 Zabbix · ${problem.effectiveHostLabel} · $titleSuffix")
            .setContentText(
                if (problem.opData.isNotBlank()) {
                    "${problem.name} (${problem.opData})"
                } else {
                    "${problem.name} · ${problem.hostWithIpLabel}"
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

        if (problem.eventId.isNotBlank() && problem.eventId != "0" && !problem.eventId.startsWith("DEMO")) {
            val ackIntent = Intent(context, NotificationActionReceiver::class.java).apply {
                action = NotificationActionReceiver.ACTION_ACK_ZABBIX_PROBLEM
                putExtra(NotificationActionReceiver.EXTRA_ZABBIX_EVENT_ID, problem.eventId)
                putExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_ID, notificationId)
            }
            val ackPendingIntent = PendingIntent.getBroadcast(
                context,
                requestCode + 3,
                ackIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(0, "Подтвердить", ackPendingIntent)
        }

        if (!problem.eventId.startsWith("DEMO")) {
            val muteIntent = Intent(context, NotificationActionReceiver::class.java).apply {
                action = NotificationActionReceiver.ACTION_MUTE_ZABBIX_PROBLEM
                putExtra(NotificationActionReceiver.EXTRA_ZABBIX_MUTE_RULE_KEY, problem.muteRuleKey)
                putExtra(NotificationActionReceiver.EXTRA_ZABBIX_SIGNATURE_KEY, problem.signatureMuteKey)
                putExtra(NotificationActionReceiver.EXTRA_ZABBIX_TRIGGER_ID, problem.triggerId)
                putExtra(NotificationActionReceiver.EXTRA_ZABBIX_HOST_LABEL, problem.hostWithIpLabel)
                putExtra(NotificationActionReceiver.EXTRA_ZABBIX_PROBLEM_NAME, problem.name)
                putExtra(NotificationActionReceiver.EXTRA_ZABBIX_SEVERITY_LEVEL, problem.severityLevel)
                putExtra(NotificationActionReceiver.EXTRA_ZABBIX_ALERT_KEY, problem.alertKey)
                putExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_ID, notificationId)
            }
            val mutePendingIntent = PendingIntent.getBroadcast(
                context,
                requestCode + 4,
                muteIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(0, "Не решаема", mutePendingIntent)
        }

        builder.addAction(0, "Открыть", openPendingIntent)

        NotificationManagerCompat.from(context).notify(notificationId, builder.build())
        return true
    }

    fun cancelZabbixAlertNotification(context: Context, problem: ZabbixProblem) {
        val notificationId = 140_000 + (problem.alertKey.hashCode() and 0x7FFFFFFF) % 40_000
        runCatching {
            NotificationManagerCompat.from(context).cancel(notificationId)
        }
    }

    @SuppressLint("MissingPermission")
    fun showZabbixStormBanner(
        context: Context,
        problems: List<ZabbixProblem>,
        playSound: Boolean = true
    ) {
        if (problems.isEmpty()) return
        val repo = SettingsRepository.getInstance(context)
        val settings = repo.appSettings.value
        if (playSound) {
            AlertSoundPlayer.playTicketAlert(context, settings)
        }
        problems.firstOrNull()?.let { top ->
            repo.emitZabbixInAppBanner(top, "⚡ Шторм Zabbix: +${problems.size} аварий/ошибок!")
        }

        if (!hasNotificationPermission(context)) return
        ensureChannels(context)

        val openZabbixIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_ZABBIX_TAB, true)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            7705,
            openZabbixIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val linesText = problems.take(6).joinToString("\n") {
            "• [${it.severity.shortBadge}] ${it.effectiveHostLabel}: ${it.name.take(55)}"
        } + if (problems.size > 6) "\n…и ещё ${problems.size - 6} проблем в Zabbix" else ""

        val notification = NotificationCompat.Builder(context, CHANNEL_CLIENT_BANNER)
            .setSmallIcon(R.drawable.ic_notification_ticket)
            .setColor(0xFFD93025.toInt())
            .setContentTitle("🚨 Шторм Zabbix: ${problems.size} новых ошибок / аварий")
            .setContentText("Нажмите, чтобы открыть мониторинг z.csl.by")
            .setStyle(NotificationCompat.BigTextStyle().bigText(linesText))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .addAction(0, "Открыть Zabbix", pendingIntent)
            .build()

        NotificationManagerCompat.from(context).notify(7705, notification)
    }
}
