package com.helpdesk.desktop

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.helpdesk.jiraagent.domain.TicketFilterEngine
import com.helpdesk.jiraagent.model.AdminChatCodec
import com.helpdesk.jiraagent.model.AdminChatMessage
import com.helpdesk.jiraagent.model.AppSettings
import com.helpdesk.jiraagent.model.ClientDirectoryCodec
import com.helpdesk.jiraagent.model.ClientDirectoryEntry
import com.helpdesk.jiraagent.model.FilterSettings
import com.helpdesk.jiraagent.model.HelpdeskTicket
import com.helpdesk.jiraagent.model.PostponedTicket
import com.helpdesk.jiraagent.model.RepairEquipmentCodec
import com.helpdesk.jiraagent.model.RepairEquipmentItem
import com.helpdesk.jiraagent.model.RepairStatus
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class DesktopPersistedState(
    val appSettings: AppSettings = AppSettings(),
    val filterSettings: FilterSettings = FilterSettings(),
    val minimizeToTray: Boolean = true,
    val resolvedChatRoomKey: String = "",
    val resolvedRepairRoomKey: String = "",
    val pinnedShiftNote: String = "",
    val baselineInitialized: Boolean = false,
    val seenTicketKeys: Set<String> = emptySet(),
    val deferredAlertKeys: Set<String> = emptySet(),
    val clientDossierNotes: Map<String, String> = emptyMap(),
    val postponedTickets: List<PostponedTicket> = emptyList(),
    val repairItems: List<RepairEquipmentItem> = emptyList(),
    val clientDirectory: List<ClientDirectoryEntry> = emptyList(),
    val cachedTickets: List<HelpdeskTicket> = emptyList(),
    val cachedChatMessages: List<AdminChatMessage> = emptyList()
)

class DesktopStorage {

    private val gson: Gson = GsonBuilder().disableHtmlEscaping().create()
    private val dataDir: File = resolveAppDataDir().apply { mkdirs() }
    private val keyFile = File(dataDir, ".vault.key")
    private val stateFile = File(dataDir, "state.enc")

    private fun resolveAppDataDir(): File {
        val appData = System.getenv("APPDATA")?.takeIf { it.isNotBlank() }
        return if (appData != null) {
            File(appData, "Help-CSL")
        } else {
            File(System.getProperty("user.home", "."), ".help-csl")
        }
    }

    private fun getOrCreateAesKey(): SecretKeySpec {
        if (keyFile.exists()) {
            val raw = runCatching { Base64.getDecoder().decode(keyFile.readText().trim()) }.getOrNull()
            if (raw != null && raw.size == 32) {
                return SecretKeySpec(raw, "AES")
            }
        }
        val fresh = ByteArray(32).also { SecureRandom().nextBytes(it) }
        runCatching { keyFile.writeText(Base64.getEncoder().encodeToString(fresh)) }
        return SecretKeySpec(fresh, "AES")
    }

    @Synchronized
    fun loadState(): DesktopPersistedState {
        if (!stateFile.exists()) return DesktopPersistedState()
        return runCatching {
            val combined = Base64.getDecoder().decode(stateFile.readText().trim())
            val iv = combined.copyOfRange(0, 12)
            val cipherBytes = combined.copyOfRange(12, combined.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateAesKey(), GCMParameterSpec(128, iv))
            val json = String(cipher.doFinal(cipherBytes), StandardCharsets.UTF_8)
            val raw = gson.fromJson(json, DesktopPersistedState::class.java) ?: DesktopPersistedState()

            val safeApp = (raw.appSettings ?: AppSettings()).let { s ->
                s.copy(
                    quickReplyTemplates = s.quickReplyTemplates
                        ?: AppSettings.DEFAULT_QUICK_REPLY_TEMPLATES
                )
            }
            val safeFilter = raw.filterSettings ?: FilterSettings()
            val safeRepairs = (raw.repairItems ?: emptyList())
                .filter { !it.id.isNullOrBlank() }
                .map { it.copy(status = it.status ?: RepairStatus.RECEIVED) }
            val safeTickets = (raw.cachedTickets ?: emptyList()).map { t ->
                val eval = TicketFilterEngine.evaluate(t, safeFilter, safeApp.usernameOrEmail)
                t.copy(isClientTicket = eval.isClientTicket, filterReason = eval.reason)
            }

            val ticketClients = safeTickets.mapNotNull { t ->
                if (!t.isClientTicket) return@mapNotNull null
                if (AdminChatCodec.isAdminChatStorageIssue(t.key, t.summary, t.labels, raw.resolvedChatRoomKey.orEmpty())) {
                    return@mapNotNull null
                }
                ClientDirectoryEntry(
                    name = t.reporterName,
                    phone = t.phoneNumbers.firstOrNull()?.e164,
                    source = "Helpdesk",
                    lastSeenEpochMs = t.updatedEpochMillis.coerceAtLeast(t.createdEpochMillis)
                )
            }
            val repairClients = safeRepairs.mapNotNull { r ->
                if (r.isDeleted || r.clientName.isBlank()) null
                else ClientDirectoryEntry(
                    name = r.clientName,
                    phone = r.clientPhone.ifBlank { null },
                    source = "В ремонт",
                    lastSeenEpochMs = r.updatedAtEpochMs
                )
            }
            val safeDir = ClientDirectoryCodec.mergeEntries(
                existing = (raw.clientDirectory ?: emptyList()).filter { !(it.name as String?).isNullOrBlank() },
                incoming = ticketClients + repairClients,
                filterSettings = safeFilter
            )

            DesktopPersistedState(
                appSettings = safeApp,
                filterSettings = safeFilter,
                minimizeToTray = raw.minimizeToTray,
                resolvedChatRoomKey = raw.resolvedChatRoomKey.orEmpty(),
                resolvedRepairRoomKey = raw.resolvedRepairRoomKey.orEmpty(),
                pinnedShiftNote = raw.pinnedShiftNote.orEmpty(),
                baselineInitialized = raw.baselineInitialized,
                seenTicketKeys = raw.seenTicketKeys ?: emptySet(),
                deferredAlertKeys = raw.deferredAlertKeys ?: emptySet(),
                clientDossierNotes = raw.clientDossierNotes ?: emptyMap(),
                postponedTickets = raw.postponedTickets ?: emptyList(),
                repairItems = safeRepairs,
                clientDirectory = safeDir,
                cachedTickets = safeTickets,
                cachedChatMessages = raw.cachedChatMessages ?: emptyList()
            )
        }.getOrDefault(DesktopPersistedState())
    }

    @Synchronized
    fun saveState(state: DesktopPersistedState) {
        runCatching {
            val trimmed = state.copy(
                seenTicketKeys = state.seenTicketKeys.toList().takeLast(1000).toSet(),
                repairItems = state.repairItems.take(400),
                clientDirectory = state.clientDirectory.take(1000),
                cachedTickets = state.cachedTickets.take(80),
                cachedChatMessages = state.cachedChatMessages.takeLast(80)
            )
            val jsonBytes = gson.toJson(trimmed).toByteArray(StandardCharsets.UTF_8)
            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateAesKey(), GCMParameterSpec(128, iv))
            val enc = cipher.doFinal(jsonBytes)
            val out = ByteArray(iv.size + enc.size)
            System.arraycopy(iv, 0, out, 0, iv.size)
            System.arraycopy(enc, 0, out, iv.size, enc.size)
            stateFile.writeText(Base64.getEncoder().encodeToString(out))
        }
    }
}

/**
 * 100% compatible with Android `SettingsTransferCodec` (`HELPCSL1:` AES-GCM + GZIP)
 * so engineers can scan the QR code from the Windows monitor with the Android Help-CSL app (or vice versa).
 */
data class DesktopTransferBundle(
    val version: Int = 1,
    val includesCredentials: Boolean = false,
    val appSettings: AppSettings,
    val filterSettings: FilterSettings
)

object DesktopQrTransferCodec {

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
                customAlertSoundUri = "",
                customAlertSoundTitle = ""
            )
        }
        val bundle = DesktopTransferBundle(
            version = 1,
            includesCredentials = includeCredentials,
            appSettings = sanitizedApp,
            filterSettings = filterSettings
        )
        val compressed = gzip(gson.toJson(bundle).toByteArray(StandardCharsets.UTF_8))
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(pinCode), GCMParameterSpec(128, iv))
        val cipherBytes = cipher.doFinal(compressed)
        val combined = ByteArray(iv.size + cipherBytes.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(cipherBytes, 0, combined, iv.size, cipherBytes.size)
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(combined)
    }

    fun decode(encodedPayload: String, pinCode: String = ""): Result<DesktopTransferBundle> = runCatching {
        val trimmed = encodedPayload.trim()
        require(trimmed.startsWith(PREFIX)) { "Неверный формат кода настроек Help-CSL" }
        val raw = Base64.getUrlDecoder().decode(trimmed.removePrefix(PREFIX))
        require(raw.size > 16) { "Код настроек повреждён" }
        val iv = raw.copyOfRange(0, 12)
        val cipherText = raw.copyOfRange(12, raw.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, deriveKey(pinCode), GCMParameterSpec(128, iv))
        val compressed = runCatching { cipher.doFinal(cipherText) }.getOrElse {
            throw IllegalArgumentException("Неверный PIN-код защиты или повреждённый код")
        }
        val json = gunzip(compressed).toString(StandardCharsets.UTF_8)
        val parsed = gson.fromJson(json, DesktopTransferBundle::class.java)
            ?: throw IllegalArgumentException("Не удалось разобрать пакет настроек")
        parsed.copy(
            appSettings = parsed.appSettings.copy(
                quickReplyTemplates = parsed.appSettings.quickReplyTemplates
                    ?: AppSettings.DEFAULT_QUICK_REPLY_TEMPLATES
            )
        )
    }

    fun renderQrImageBitmap(payload: String, sizePx: Int = 360): ImageBitmap? = runCatching {
        val hints = mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
            EncodeHintType.MARGIN to 2
        )
        val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val img = BufferedImage(sizePx, sizePx, BufferedImage.TYPE_INT_ARGB)
        val dark = 0xFF04091E.toInt()
        val white = 0xFFFFFFFF.toInt()
        for (y in 0 until sizePx) {
            for (x in 0 until sizePx) {
                img.setRGB(x, y, if (matrix.get(x, y)) dark else white)
            }
        }
        img.toComposeImageBitmap()
    }.getOrNull()

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
        GZIPInputStream(ByteArrayInputStream(input)).use { return it.readBytes() }
    }
}
