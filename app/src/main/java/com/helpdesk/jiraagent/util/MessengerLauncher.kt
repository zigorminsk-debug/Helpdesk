package com.helpdesk.jiraagent.util

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
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

    private val TELEGRAM_PACKAGES = listOf(
        "org.telegram.messenger",
        "org.telegram.messenger.web",
        "org.thunderdog.challegram"
    )
    private val VIBER_PACKAGES = listOf("com.viber.voip")
    private val WHATSAPP_PACKAGES = listOf("com.whatsapp", "com.whatsapp.w4b")

    /**
     * Opens the selected messenger directly on the chat screen with [phoneE164] (e.g. "+375291234567")
     * without falling back to opening web URLs in a browser.
     */
    fun openChatByPhone(context: Context, app: MessengerApp, phoneE164: String) {
        val digits = phoneE164.filter { it.isDigit() }
        if (digits.isEmpty()) {
            Toast.makeText(context, "Некорректный номер контакта", Toast.LENGTH_SHORT).show()
            return
        }

        when (app) {
            MessengerApp.TELEGRAM -> {
                val uri = "tg://resolve?phone=$digits"
                val opened = tryLaunchUriWithPackages(context, uri, TELEGRAM_PACKAGES) ||
                    tryLaunchUri(context, uri)
                if (!opened) {
                    copyPhoneFallback(context, "+$digits")
                    Toast.makeText(context, "Приложение Telegram не установлено (номер +$digits скопирован)", Toast.LENGTH_SHORT).show()
                }
            }

            MessengerApp.VIBER -> {
                val opened = tryLaunchUriWithPackages(context, "viber://chat?number=%2B$digits", VIBER_PACKAGES) ||
                    tryLaunchUri(context, "viber://chat?number=%2B$digits") ||
                    tryLaunchUri(context, "viber://contact?number=%2B$digits") ||
                    tryLaunchUri(context, "viber://add?number=$digits")
                if (!opened) {
                    copyPhoneFallback(context, "+$digits")
                    Toast.makeText(context, "Приложение Viber не установлено (номер +$digits скопирован)", Toast.LENGTH_SHORT).show()
                }
            }

            MessengerApp.WHATSAPP -> {
                val uri = "whatsapp://send?phone=$digits"
                val opened = tryLaunchUriWithPackages(context, uri, WHATSAPP_PACKAGES) ||
                    tryLaunchUri(context, uri)
                if (!opened) {
                    copyPhoneFallback(context, "+$digits")
                    Toast.makeText(context, "Приложение WhatsApp не установлено (номер +$digits скопирован)", Toast.LENGTH_SHORT).show()
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

        val uri = "tg://resolve?domain=$clean"
        val opened = tryLaunchUriWithPackages(context, uri, TELEGRAM_PACKAGES) ||
            tryLaunchUri(context, uri)
        if (!opened) {
            copyPhoneFallback(context, "@$clean")
            Toast.makeText(context, "Приложение Telegram не установлено (@$clean скопирован)", Toast.LENGTH_SHORT).show()
        }
    }

    private fun tryLaunchUriWithPackages(context: Context, uriString: String, packages: List<String>): Boolean {
        val parsed = Uri.parse(uriString)
        for (pkg in packages) {
            try {
                val intent = Intent(Intent.ACTION_VIEW, parsed).apply {
                    setPackage(pkg)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                return true
            } catch (_: Exception) {
                // Try next package
            }
        }
        return false
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

    private fun copyPhoneFallback(context: Context, value: String) {
        runCatching {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.setPrimaryClip(ClipData.newPlainText("Help-CSL Contact", value))
        }
    }
}
