package com.helpdesk.jiraagent.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

enum class MessengerApp(val label: String) {
    TELEGRAM("Telegram"),
    VIBER("Viber"),
    WHATSAPP("WhatsApp")
}

object MessengerLauncher {

    /**
     * Opens the selected messenger directly on the chat screen with [phoneE164] (e.g. "+375291234567").
     */
    fun openChatByPhone(context: Context, app: MessengerApp, phoneE164: String) {
        val digits = phoneE164.filter { it.isDigit() }
        if (digits.isEmpty()) {
            Toast.makeText(context, "Некорректный номер контакта", Toast.LENGTH_SHORT).show()
            return
        }

        when (app) {
            MessengerApp.TELEGRAM -> {
                val opened = tryLaunchUri(context, "tg://resolve?phone=$digits") ||
                    tryLaunchUri(context, "https://t.me/+$digits")
                if (!opened) {
                    Toast.makeText(context, "Не удалось открыть Telegram", Toast.LENGTH_SHORT).show()
                }
            }

            MessengerApp.VIBER -> {
                val opened = tryLaunchUri(context, "viber://chat?number=%2B$digits") ||
                    tryLaunchUri(context, "viber://contact?number=%2B$digits") ||
                    tryLaunchUri(context, "viber://add?number=$digits")
                if (!opened) {
                    Toast.makeText(context, "Приложение Viber не установлено", Toast.LENGTH_SHORT).show()
                }
            }

            MessengerApp.WHATSAPP -> {
                val opened = tryLaunchUri(context, "whatsapp://send?phone=$digits") ||
                    tryLaunchUri(context, "https://wa.me/$digits")
                if (!opened) {
                    Toast.makeText(context, "Не удалось открыть WhatsApp", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun openTelegramByPhone(context: Context, phoneE164: String) {
        openChatByPhone(context, MessengerApp.TELEGRAM, phoneE164)
    }

    fun openViberByPhone(context: Context, phoneE164: String) {
        openChatByPhone(context, MessengerApp.VIBER, phoneE164)
    }

    fun openWhatsAppByPhone(context: Context, phoneE164: String) {
        openChatByPhone(context, MessengerApp.WHATSAPP, phoneE164)
    }

    fun openTelegramByUsername(context: Context, handle: String) {
        openTelegramUsername(context, handle)
    }

    /**
     * Opens Telegram directly on a `@username` or `t.me/username` handle found in a ticket.
     */
    fun openTelegramUsername(context: Context, handle: String) {
        val clean = handle.trim()
            .removePrefix("https://t.me/")
            .removePrefix("http://t.me/")
            .removePrefix("t.me/")
            .removePrefix("@")
            .trim('/')
        if (clean.isEmpty()) return

        val opened = tryLaunchUri(context, "tg://resolve?domain=$clean") ||
            tryLaunchUri(context, "https://t.me/$clean")
        if (!opened) {
            Toast.makeText(context, "Не удалось открыть Telegram (@$clean)", Toast.LENGTH_SHORT).show()
        }
    }

    private fun tryLaunchUri(context: Context, uriString: String): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uriString)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: Exception) {
            false
        }
    }
}
