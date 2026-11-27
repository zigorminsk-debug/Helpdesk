package com.helpdesk.jiraagent.domain

import android.graphics.Bitmap
import android.graphics.Color
import com.google.gson.Gson
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.helpdesk.jiraagent.model.AppSettings
import com.helpdesk.jiraagent.model.FilterSettings
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class SettingsTransferBundle(
    val version: Int = 1,
    val includesCredentials: Boolean = false,
    val appSettings: AppSettings,
    val filterSettings: FilterSettings
)

object SettingsTransferCodec {

    private const val PREFIX = "HELPCSL1:"
    private const val DEFAULT_SALT = "HelpCSL-Corporate-Onboarding-v1"
    private val gson = Gson()

    fun encode(
        appSettings: AppSettings,
        filterSettings: FilterSettings,
        includeCredentials: Boolean,
        pinCode: String = ""
    ): String {
        val sanitizedApp = if (includeCredentials) {
            appSettings.copy(customAlertSoundUri = "", customAlertSoundTitle = "")
        } else {
            appSettings.copy(
                usernameOrEmail = "",
                passwordOrToken = "",
                confluenceToken = "",
                zabbixLogin = "",
                zabbixPassword = "",
                customAlertSoundUri = "",
                customAlertSoundTitle = ""
            )
        }
        val bundle = SettingsTransferBundle(
            version = 1,
            includesCredentials = includeCredentials,
            appSettings = sanitizedApp,
            filterSettings = filterSettings
        )
        val jsonBytes = gson.toJson(bundle).toByteArray(StandardCharsets.UTF_8)
        val compressed = gzip(jsonBytes)

        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(pinCode), GCMParameterSpec(128, iv))
        val cipherBytes = cipher.doFinal(compressed)

        val combined = ByteArray(iv.size + cipherBytes.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(cipherBytes, 0, combined, iv.size, cipherBytes.size)

        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(combined)
    }

    fun decode(encodedPayload: String, pinCode: String = ""): Result<SettingsTransferBundle> {
        return runCatching {
            val trimmed = encodedPayload.trim()
            require(trimmed.startsWith(PREFIX)) {
                "Неверный формат кода настроек Help-CSL"
            }
            val raw = Base64.getUrlDecoder().decode(trimmed.removePrefix(PREFIX))
            require(raw.size > 16) { "Код настроек повреждён" }

            val iv = raw.copyOfRange(0, 12)
            val cipherText = raw.copyOfRange(12, raw.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(pinCode), GCMParameterSpec(128, iv))
            val compressed = runCatching { cipher.doFinal(cipherText) }.getOrElse {
                throw IllegalArgumentException("Неверный PIN-код защиты или повреждённый QR-код")
            }
            val json = gunzip(compressed).toString(StandardCharsets.UTF_8)
            val parsed = gson.fromJson(json, SettingsTransferBundle::class.java)
                ?: throw IllegalArgumentException("Не удалось разобрать пакет настроек")
            // Ensure non-null defaults after Gson deserialization
            parsed.copy(
                appSettings = parsed.appSettings.copy(
                    confluenceUrl = (parsed.appSettings.confluenceUrl as String?)?.takeIf { it.isNotBlank() }
                        ?: AppSettings.DEFAULT_CONFLUENCE_URL,
                    confluenceToken = (parsed.appSettings.confluenceToken as String?).orEmpty(),
                    zabbixUrl = (parsed.appSettings.zabbixUrl as String?)?.takeIf { it.isNotBlank() }
                        ?: AppSettings.DEFAULT_ZABBIX_URL,
                    zabbixLogin = (parsed.appSettings.zabbixLogin as String?).orEmpty(),
                    zabbixPassword = (parsed.appSettings.zabbixPassword as String?).orEmpty(),
                    zabbixMinAlertSeverity = parsed.appSettings.normalizedZabbixMinSeverity,
                    offHoursPollIntervalMinutes = parsed.appSettings.normalizedOffHoursPollMinutes,
                darkThemePreset = (parsed.appSettings.darkThemePreset as com.helpdesk.jiraagent.model.DarkThemePreset?) ?: com.helpdesk.jiraagent.model.DarkThemePreset.CSL_NAVY,
                lightThemePreset = (parsed.appSettings.lightThemePreset as com.helpdesk.jiraagent.model.LightThemePreset?) ?: com.helpdesk.jiraagent.model.LightThemePreset.CLASSIC,
                    quickReplyTemplates = parsed.appSettings.quickReplyTemplates
                        ?: AppSettings.DEFAULT_QUICK_REPLY_TEMPLATES
                )
            )
        }
    }

    fun generateQrBitmap(payload: String, sizePx: Int = 720): Bitmap? {
        return runCatching {
            val hints = mapOf(
                EncodeHintType.CHARACTER_SET to "UTF-8",
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
                EncodeHintType.MARGIN to 2
            )
            val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
            val pixels = IntArray(sizePx * sizePx)
            for (y in 0 until sizePx) {
                val offset = y * sizePx
                for (x in 0 until sizePx) {
                    pixels[offset + x] = if (matrix.get(x, y)) 0xFF04091E.toInt() else Color.WHITE
                }
            }
            Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888).apply {
                setPixels(pixels, 0, sizePx, 0, 0, sizePx, sizePx)
            }
        }.getOrNull()
    }

    private fun deriveKey(pinCode: String): SecretKeySpec {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest("$DEFAULT_SALT:${pinCode.trim()}".toByteArray(StandardCharsets.UTF_8))
        return SecretKeySpec(bytes, "AES")
    }

    private fun gzip(input: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(input) }
        return bos.toByteArray()
    }

    private fun gunzip(input: ByteArray): ByteArray {
        return GZIPInputStream(ByteArrayInputStream(input)).use { it.readBytes() }
    }
}
