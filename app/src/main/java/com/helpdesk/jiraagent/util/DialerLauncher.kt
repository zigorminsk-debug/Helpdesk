package com.helpdesk.jiraagent.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.helpdesk.jiraagent.domain.PhoneNumberNormalizer
import com.helpdesk.jiraagent.model.PhoneRegion

object DialerLauncher {

    /**
     * Opens the Android phone's default system dialer ("штатную звонилку по умолчанию")
     * with the phone number converted to international E.164 format (e.g. tel:+375291234567).
     */
    fun openSystemDialer(
        context: Context,
        phoneInput: String,
        region: PhoneRegion = PhoneRegion.AUTO_BY_RU,
        customCountryCode: String = "+375"
    ) {
        val e164 = if (phoneInput.startsWith("+") && phoneInput.drop(1).all { it.isDigit() }) {
            phoneInput
        } else {
            PhoneNumberNormalizer.normalizeSingle(
                rawInput = phoneInput,
                region = region,
                customCountryCode = customCountryCode,
                strictForFreeText = false
            )?.first ?: run {
                val digits = phoneInput.filter { it.isDigit() }
                if (digits.isNotEmpty()) "+$digits" else return
            }
        }

        val dialIntent = Intent(Intent.ACTION_DIAL).apply {
            data = Uri.parse("tel:${Uri.encode(e164)}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        runCatching {
            context.startActivity(dialIntent)
        }.onFailure {
            Toast.makeText(
                context,
                "Не удалось открыть приложение телефона для номера $e164",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    fun openExternalUrl(context: Context, url: String) {
        if (url.isBlank()) return
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching {
            context.startActivity(intent)
        }.onFailure {
            Toast.makeText(context, "Не удалось открыть ссылку", Toast.LENGTH_SHORT).show()
        }
    }
}
