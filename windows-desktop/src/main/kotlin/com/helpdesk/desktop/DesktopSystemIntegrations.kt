package com.helpdesk.desktop

import com.helpdesk.jiraagent.domain.CslCompanyContacts
import com.helpdesk.jiraagent.domain.EngineerNameResolver
import com.helpdesk.jiraagent.domain.RemoteDesktopKind
import com.helpdesk.jiraagent.model.AlertSoundPreset
import com.helpdesk.jiraagent.model.HelpdeskTicket
import com.helpdesk.jiraagent.model.RepairEquipmentItem
import com.helpdesk.jiraagent.model.RepairStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.PDFont
import java.awt.Color
import java.awt.Desktop
import java.awt.Image
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.net.URLEncoder
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

object DesktopBuildInfo {
    val versionName: String
        get() = System.getProperty("helpcsl.version")?.takeIf { it.isNotBlank() } ?: "1.0.28"

    val versionCode: Int
        get() = System.getProperty("helpcsl.versionCode")?.toIntOrNull() ?: 1028
}

object DesktopSoundPlayer {

    fun playAlert(preset: AlertSoundPreset, customWavPath: String = "") {
        thread(isDaemon = true, name = "helpcsl-audio") {
            runCatching {
                if (preset == AlertSoundPreset.CUSTOM_URI && customWavPath.isNotBlank()) {
                    val f = File(customWavPath)
                    if (f.exists()) {
                        AudioSystem.getAudioInputStream(f).use { stream ->
                            val clip = AudioSystem.getClip()
                            clip.open(stream)
                            clip.start()
                            Thread.sleep((clip.microsecondLength / 1000L).coerceIn(400L, 8_000L))
                            clip.close()
                            return@thread
                        }
                    }
                }
                val notes: List<Pair<Double, Int>> = when (preset) {
                    AlertSoundPreset.CSL_CHIME -> listOf(587.33 to 150, 783.99 to 150, 1046.50 to 260, 0.0 to 70, 1046.50 to 240)
                    AlertSoundPreset.DISPATCH_PULSE -> listOf(880.0 to 160, 0.0 to 80, 880.0 to 160, 0.0 to 80, 1174.66 to 260)
                    AlertSoundPreset.HARMONIC_CHORD -> listOf(523.25 to 180, 659.25 to 180, 783.99 to 320)
                    AlertSoundPreset.URGENT_ALARM -> listOf(987.77 to 140, 783.99 to 140, 987.77 to 140, 783.99 to 140, 1318.51 to 300)
                    AlertSoundPreset.SYSTEM_DEFAULT, AlertSoundPreset.CUSTOM_URI -> listOf(659.25 to 160, 880.0 to 240)
                }
                playSynthesizedSequence(notes)
            }.onFailure {
                runCatching { Toolkit.getDefaultToolkit().beep() }
            }
        }
    }

    private fun playSynthesizedSequence(notes: List<Pair<Double, Int>>) {
        val sampleRate = 44100f
        val format = AudioFormat(sampleRate, 16, 1, true, false)
        val bos = ByteArrayOutputStream()
        for ((freq, durationMs) in notes) {
            val totalSamples = ((durationMs / 1000.0) * sampleRate).toInt().coerceAtLeast(1)
            for (i in 0 until totalSamples) {
                val sampleVal = if (freq <= 1.0) {
                    0.0
                } else {
                    val t = i / sampleRate.toDouble()
                    val attack = (i / (sampleRate * 0.012)).coerceAtMost(1.0)
                    val decay = exp(-2.8 * t)
                    val fundamental = sin(2.0 * PI * freq * t)
                    val overtone = 0.28 * sin(2.0 * PI * (freq * 2.0) * t)
                    (fundamental + overtone) * attack * decay * 0.65
                }
                val pcm = (sampleVal * Short.MAX_VALUE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                bos.write(pcm and 0xFF)
                bos.write((pcm shr 8) and 0xFF)
            }
        }
        val data = bos.toByteArray()
        AudioSystem.getSourceDataLine(format).use { line ->
            line.open(format)
            line.start()
            line.write(data, 0, data.size)
            line.drain()
        }
    }

    fun speakTicketSummaryWindows(clientName: String, summary: String) {
        val isWin = System.getProperty("os.name").orEmpty().contains("win", ignoreCase = true)
        if (!isWin) return
        thread(isDaemon = true, name = "helpcsl-tts") {
            runCatching {
                val safeText = "Новая заявка. Клиент: $clientName. ${summary.take(120)}"
                    .replace("'", " ")
                    .replace("\"", " ")
                    .replace("\n", " ")
                ProcessBuilder(
                    "powershell.exe",
                    "-NoProfile",
                    "-WindowStyle",
                    "Hidden",
                    "-Command",
                    "Add-Type -AssemblyName System.Speech; \$s = New-Object System.Speech.Synthesis.SpeechSynthesizer; \$s.Speak('$safeText')"
                ).start().waitFor(15, TimeUnit.SECONDS)
            }
        }
    }
}

object WindowsExternalLaunchers {

    fun copyToClipboard(text: String) {
        runCatching {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
        }
    }

    /**
     * Reads an image directly from the Windows clipboard (e.g. after pressing `Win+Shift+S` or `PrintScreen`)
     * and encodes it as PNG bytes ready for 1-click upload into Jira!
     */
    fun readClipboardImagePng(): Pair<String, ByteArray>? {
        return runCatching {
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            if (!clipboard.isDataFlavorAvailable(DataFlavor.imageFlavor)) return null
            val awtImage = clipboard.getData(DataFlavor.imageFlavor) as? Image ?: return null
            val width = awtImage.getWidth(null)
            val height = awtImage.getHeight(null)
            if (width <= 0 || height <= 0) return null
            val buffered = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
            val g = buffered.createGraphics()
            g.drawImage(awtImage, 0, 0, null)
            g.dispose()
            val bos = ByteArrayOutputStream()
            ImageIO.write(buffered, "png", bos)
            val fileName = "screenshot_${System.currentTimeMillis() / 1000}.png"
            fileName to bos.toByteArray()
        }.getOrNull()
    }

    fun openUrl(url: String) {
        if (url.isBlank()) return
        runCatching {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(URI(url))
                return
            }
        }
        runCatching {
            ProcessBuilder("cmd.exe", "/c", "start", "", url).start()
        }
    }

    fun openFile(file: File) {
        runCatching {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(file)
                return
            }
        }
        runCatching {
            ProcessBuilder("cmd.exe", "/c", "start", "", file.absolutePath).start()
        }
    }

    fun printOrOpenPdf(file: File) {
        runCatching {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.PRINT)) {
                Desktop.getDesktop().print(file)
                return
            }
        }
        openFile(file)
    }

    fun launchRemoteDesktop(kind: RemoteDesktopKind, rawId: String): String {
        val cleanId = rawId.filter { it.isDigit() || it.isLetter() }
        copyToClipboard(cleanId)
        val localAppData = System.getenv("LOCALAPPDATA").orEmpty()
        val progFiles = System.getenv("ProgramFiles").orEmpty()
        val progFilesX86 = System.getenv("ProgramFiles(x86)").orEmpty()

        if (kind == RemoteDesktopKind.ANYDESK) {
            val candidates = listOf(
                File(progFilesX86, "AnyDesk\\AnyDesk.exe"),
                File(progFiles, "AnyDesk\\AnyDesk.exe"),
                File(localAppData, "Programs\\AnyDesk\\AnyDesk.exe")
            )
            val exe = candidates.firstOrNull { it.exists() }
            if (exe != null) {
                runCatching {
                    ProcessBuilder(exe.absolutePath, cleanId).start()
                    return "Запущен AnyDesk → $cleanId (ID также в буфере)"
                }
            }
            openUrl("anydesk:$cleanId")
            return "ID $cleanId скопирован и отправлен в AnyDesk"
        } else {
            val candidates = listOf(
                File(progFiles, "RustDesk\\rustdesk.exe"),
                File(progFilesX86, "RustDesk\\rustdesk.exe"),
                File(localAppData, "Programs\\RustDesk\\rustdesk.exe")
            )
            val exe = candidates.firstOrNull { it.exists() }
            if (exe != null) {
                runCatching {
                    ProcessBuilder(exe.absolutePath, "--connect", cleanId).start()
                    return "Запущен RustDesk → $cleanId (ID также в буфере)"
                }
            }
            openUrl("rustdesk://$cleanId")
            return "ID $cleanId скопирован и отправлен в RustDesk"
        }
    }

    fun launchRdp(host: String) {
        val clean = host.trim()
        if (clean.isEmpty()) return
        runCatching {
            ProcessBuilder("mstsc.exe", "/v:$clean").start()
        }
    }

    fun launchSshTerminal(host: String) {
        val clean = host.trim()
        if (clean.isEmpty()) return
        runCatching {
            ProcessBuilder("cmd.exe", "/c", "start", "SSH $clean", "ssh", clean).start()
        }
    }

    fun callSipPhone(phoneE164: String) {
        copyToClipboard(phoneE164)
        openUrl("tel:${phoneE164.trim()}")
    }

    fun dialSipOrTel(phoneE164: String) = callSipPhone(phoneE164)

    fun openBrowser(url: String) = openUrl(url)

    fun launchAnyDesk(rawId: String): String = launchRemoteDesktop(RemoteDesktopKind.ANYDESK, rawId)

    fun launchRustDesk(rawId: String): String = launchRemoteDesktop(RemoteDesktopKind.RUSTDESK, rawId)

    fun openTelegram(phoneE164: String, usernameHandle: String? = null) {
        if (!usernameHandle.isNullOrBlank()) {
            val clean = usernameHandle.trim().removePrefix("@")
            openUrl("tg://resolve?domain=$clean")
            return
        }
        val digits = phoneE164.filter { it.isDigit() }
        openUrl("tg://resolve?phone=$digits")
    }

    fun openViber(phoneE164: String) {
        val digits = phoneE164.filter { it.isDigit() }
        openUrl("viber://chat?number=%2B$digits")
    }

    fun openWhatsApp(phoneE164: String) {
        val digits = phoneE164.filter { it.isDigit() }
        openUrl("https://wa.me/$digits")
    }

    fun openMapForAddress(address: String) {
        val enc = URLEncoder.encode(address, "UTF-8")
        openUrl("https://yandex.by/maps/?text=$enc")
    }
}

object DesktopPdfGenerator {

    private val DATE_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")

    private fun loadUnicodeFont(doc: PDDocument, bold: Boolean): PDFont {
        val winDir = System.getenv("WINDIR") ?: "C:\\Windows"
        val candidates = if (bold) {
            listOf(
                File(winDir, "Fonts\\segoeuib.ttf"),
                File(winDir, "Fonts\\arialbd.ttf"),
                File("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf")
            )
        } else {
            listOf(
                File(winDir, "Fonts\\segoeui.ttf"),
                File(winDir, "Fonts\\arial.ttf"),
                File("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf")
            )
        }
        for (f in candidates) {
            if (f.exists()) {
                val loaded = runCatching { PDType0Font.load(doc, f) }.getOrNull()
                if (loaded != null) return loaded
            }
        }
        return if (bold) PDType1Font.HELVETICA_BOLD else PDType1Font.HELVETICA
    }

    private fun drawCorporateCslHeader(
        cs: PDPageContentStream,
        pw: Float,
        ph: Float,
        fontBold: PDFont,
        fontRegular: PDFont,
        docTitle: String
    ) {
        // Corporate csl.by Header (#04091E + #F7631B) with official contacts from csl.by/contacts
        cs.setNonStrokingColor(Color(4, 9, 30))
        cs.addRect(0f, ph - 112f, pw, 112f)
        cs.fill()

        cs.setNonStrokingColor(Color(247, 99, 27))
        cs.addRect(0f, ph - 117f, pw, 5f)
        cs.fill()

        drawTextSafe(cs, fontBold, 15.5f, Color.WHITE, 36f, ph - 26f, CslCompanyContacts.BRAND_TITLE)
        drawTextSafe(cs, fontRegular, 9.2f, Color(203, 213, 225), 36f, ph - 43f, CslCompanyContacts.ADDRESS_AND_HOURS)
        drawTextSafe(cs, fontRegular, 9.2f, Color(203, 213, 225), 36f, ph - 57f, CslCompanyContacts.PHONES_AND_WEB)

        cs.setStrokingColor(Color(51, 65, 85))
        cs.setLineWidth(0.75f)
        cs.moveTo(36f, ph - 68f)
        cs.lineTo(pw - 36f, ph - 68f)
        cs.stroke()
        cs.setLineWidth(1f)

        drawTextSafe(cs, fontBold, 11.2f, Color(255, 210, 184), 36f, ph - 91f, docTitle)
    }

    fun generateRepairReceiptPdf(
        item: RepairEquipmentItem,
        engineerName: String
    ): File {
        val isIssueAct = item.status == RepairStatus.READY || item.status == RepairStatus.ISSUED
        val outDir = File(System.getProperty("java.io.tmpdir"), "HelpCSL-Documents").apply { mkdirs() }
        val safeIntake = item.intakeNumber.ifBlank { "REM" }.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val prefix = if (isIssueAct) "Act_Issue" else "Receipt_Intake"
        val outFile = File(outDir, "${prefix}_${safeIntake}_${System.currentTimeMillis() / 1000}.pdf")

        val cleanEngineer = EngineerNameResolver.resolve(
            rawEngineer = item.assignedEngineer,
            connectedUserDisplayName = engineerName,
            fallbackLabel = "Сервисный инженер"
        )

        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            val fontRegular = loadUnicodeFont(doc, bold = false)
            val fontBold = loadUnicodeFont(doc, bold = true)
            val pw = page.mediaBox.width
            val ph = page.mediaBox.height

            PDPageContentStream(doc, page).use { cs ->
                val nowStr = ZonedDateTime.now(ZoneId.of("Europe/Minsk")).format(DATE_FMT)
                val docTitle = if (isIssueAct) {
                    "АКТ ВЫДАЧИ ТЕХНИКИ ИЗ РЕМОНТА № ${item.intakeNumber.ifBlank { "Б/Н" }}  от  $nowStr"
                } else {
                    "КВИТАНЦИЯ О ПРИЁМЕ ТЕХНИКИ В РЕМОНТ № ${item.intakeNumber.ifBlank { "Б/Н" }}  от  $nowStr"
                }
                drawCorporateCslHeader(cs, pw, ph, fontBold, fontRegular, docTitle)

                var y = ph - 144f
                val rows = listOf(
                    "Клиент / Организация:" to item.clientName.ifBlank { "Не указан" },
                    "Контакт / Заявка Jira:" to listOf(item.clientPhone, item.linkedTicketKey).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "—" },
                    "Оборудование:" to "${item.deviceCategory} — ${item.deviceModel.ifBlank { "Без модели" }}",
                    "Серийный / Инв. №:" to item.serialNumber.ifBlank { "Б/Н" },
                    "Комплектность:" to item.accessories.ifBlank { "Без комплекта" },
                    "Внешний вид / Полка:" to listOf(item.externalCondition.ifBlank { "Б/У, следы эксплуатации" }, item.shelfLocation).filter { it.isNotBlank() }.joinToString(" · "),
                    "Статус / Инженер:" to "${item.status.title} ($cleanEngineer)"
                )

                for ((label, value) in rows) {
                    drawTextSafe(cs, fontBold, 10.5f, Color(71, 85, 105), 36f, y, label)
                    drawTextSafe(cs, fontRegular, 10.5f, Color(15, 23, 42), 195f, y, value.take(66))
                    y -= 20f
                }

                y -= 12f
                drawTextSafe(cs, fontBold, 12f, Color(247, 99, 27), 36f, y, "Заявленная неисправность:")
                y -= 18f
                for (line in wrapWords(item.reportedDefect.ifBlank { "Диагностика и проверка работоспособности" }, 82).take(5)) {
                    drawTextSafe(cs, fontRegular, 10.5f, Color(15, 23, 42), 42f, y, line)
                    y -= 15f
                }

                if (item.repairNotes.isNotBlank() || isIssueAct) {
                    y -= 12f
                    drawTextSafe(cs, fontBold, 12f, Color(247, 99, 27), 36f, y, "Диагностика / Выполненные работы / Запчасти:")
                    y -= 18f
                    val notes = item.repairNotes.ifBlank { "Диагностика и обслуживание выполнены, оборудование исправно." }
                    for (line in wrapWords(notes, 82).take(8)) {
                        drawTextSafe(cs, fontRegular, 10.5f, Color(15, 23, 42), 42f, y, line)
                        y -= 15f
                    }
                }

                val sigY = 120f
                cs.setStrokingColor(Color(203, 213, 225))
                cs.moveTo(36f, sigY + 45f)
                cs.lineTo(pw - 36f, sigY + 45f)
                cs.stroke()

                drawTextSafe(cs, fontBold, 10.5f, Color(15, 23, 42), 36f, sigY + 22f, "Сервисный инженер:")
                drawTextSafe(cs, fontRegular, 10f, Color(15, 23, 42), 36f, sigY + 6f, cleanEngineer.take(38))
                cs.moveTo(36f, sigY - 22f)
                cs.lineTo(240f, sigY - 22f)
                cs.stroke()

                drawTextSafe(cs, fontBold, 10.5f, Color(15, 23, 42), 310f, sigY + 22f, "Представитель Заказчика:")
                drawTextSafe(cs, fontRegular, 10f, Color(15, 23, 42), 310f, sigY + 6f, item.clientName.take(38))
                cs.moveTo(310f, sigY - 22f)
                cs.lineTo(545f, sigY - 22f)
                cs.stroke()
            }
            doc.save(outFile)
        }
        return outFile
    }

    fun generateTicketServiceActPdf(
        ticket: HelpdeskTicket,
        engineerName: String,
        performedWorks: List<String>,
        extraNotes: String,
        serialNumber: String
    ): File {
        val outDir = File(System.getProperty("java.io.tmpdir"), "HelpCSL-Documents").apply { mkdirs() }
        val safeKey = ticket.key.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val outFile = File(outDir, "ServiceAct_${safeKey}_${System.currentTimeMillis() / 1000}.pdf")

        val cleanEngineer = EngineerNameResolver.resolve(
            rawEngineer = engineerName,
            connectedUserDisplayName = ticket.assigneeName,
            fallbackLabel = "Сервисный инженер"
        )

        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            val fontRegular = loadUnicodeFont(doc, bold = false)
            val fontBold = loadUnicodeFont(doc, bold = true)
            val pw = page.mediaBox.width
            val ph = page.mediaBox.height

            PDPageContentStream(doc, page).use { cs ->
                val nowStr = ZonedDateTime.now(ZoneId.of("Europe/Minsk")).format(DATE_FMT)
                drawCorporateCslHeader(
                    cs = cs,
                    pw = pw,
                    ph = ph,
                    fontBold = fontBold,
                    fontRegular = fontRegular,
                    docTitle = "АКТ ВЫПОЛНЕННЫХ РАБОТ ПО ЗАЯВКЕ № ${ticket.key}  от  $nowStr"
                )

                var y = ph - 144f
                drawTextSafe(cs, fontBold, 10.5f, Color(71, 85, 105), 36f, y, "Заказчик (Клиент):")
                drawTextSafe(cs, fontRegular, 10.5f, Color(15, 23, 42), 185f, y, ticket.reporterName.ifBlank { "Не указан" }.take(66))
                y -= 20f
                drawTextSafe(cs, fontBold, 10.5f, Color(71, 85, 105), 36f, y, "Заявка / Тема:")
                val summaryLines = wrapWords("${ticket.key} — ${ticket.summary}", 62).take(2)
                if (summaryLines.isEmpty()) {
                    drawTextSafe(cs, fontRegular, 10.5f, Color(15, 23, 42), 185f, y, ticket.key)
                    y -= 20f
                } else {
                    summaryLines.forEachIndexed { idx, line ->
                        drawTextSafe(cs, fontRegular, 10.5f, Color(15, 23, 42), 185f, y, line)
                        y -= if (idx == summaryLines.lastIndex) 20f else 15f
                    }
                }
                if (serialNumber.isNotBlank()) {
                    drawTextSafe(cs, fontBold, 10.5f, Color(71, 85, 105), 36f, y, "Оборудование (S/N):")
                    drawTextSafe(cs, fontRegular, 10.5f, Color(15, 23, 42), 185f, y, serialNumber.take(66))
                    y -= 20f
                }

                y -= 12f
                drawTextSafe(cs, fontBold, 12f, Color(247, 99, 27), 36f, y, "Перечень выполненных работ:")
                y -= 18f
                for (work in performedWorks.map { it.trim() }.filter { it.isNotEmpty() }) {
                    val wrappedWork = wrapWords(work, 78).take(6)
                    wrappedWork.forEachIndexed { idx, line ->
                        if (idx == 0) {
                            drawVectorCheckmark(cs, 42f, y)
                        }
                        drawTextSafe(cs, fontRegular, 10.5f, Color(15, 23, 42), 56f, y, line)
                        y -= 16f
                    }
                    y -= 3f
                    if (y < 210f) break
                }

                if (extraNotes.isNotBlank() && y >= 210f) {
                    y -= 8f
                    drawTextSafe(cs, fontBold, 12f, Color(247, 99, 27), 36f, y, "Комментарий инженера:")
                    y -= 18f
                    for (line in wrapWords(extraNotes, 82).take(6)) {
                        if (y < 180f) break
                        drawTextSafe(cs, fontRegular, 10.5f, Color(15, 23, 42), 42f, y, line)
                        y -= 15f
                    }
                }

                val sigY = 120f
                cs.setLineWidth(1f)
                cs.setStrokingColor(Color(203, 213, 225))
                cs.moveTo(36f, sigY + 45f)
                cs.lineTo(pw - 36f, sigY + 45f)
                cs.stroke()

                drawTextSafe(cs, fontBold, 10.5f, Color(15, 23, 42), 36f, sigY + 22f, "Сервисный инженер:")
                drawTextSafe(cs, fontRegular, 10f, Color(15, 23, 42), 36f, sigY + 6f, cleanEngineer.take(38))
                cs.moveTo(36f, sigY - 22f)
                cs.lineTo(240f, sigY - 22f)
                cs.stroke()

                drawTextSafe(cs, fontBold, 10.5f, Color(15, 23, 42), 310f, sigY + 22f, "Представитель Заказчика:")
                drawTextSafe(cs, fontRegular, 10f, Color(15, 23, 42), 310f, sigY + 6f, ticket.reporterName.ifBlank { "Заказчик" }.take(38))
                cs.moveTo(310f, sigY - 22f)
                cs.lineTo(545f, sigY - 22f)
                cs.stroke()
            }
            doc.save(outFile)
        }
        return outFile
    }

    private fun drawVectorCheckmark(cs: PDPageContentStream, x: Float, baselineY: Float) {
        cs.setStrokingColor(Color(247, 99, 27))
        cs.setLineWidth(1.5f)
        cs.moveTo(x, baselineY + 3.5f)
        cs.lineTo(x + 3.2f, baselineY + 0.5f)
        cs.lineTo(x + 8.5f, baselineY + 7.2f)
        cs.stroke()
        cs.setLineWidth(1f)
    }

    private fun canEncodeGlyph(font: PDFont, s: String): Boolean {
        return try {
            font.encode(s)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun sanitizeForFont(font: PDFont, raw: String): String {
        val cleaned = raw
            .replace("\r", " ")
            .replace("\n", " ")
            .replace("\t", " ")
            .replace('\u00A0', ' ')

        if (canEncodeGlyph(font, cleaned)) return cleaned

        val sb = StringBuilder(cleaned.length)
        var i = 0
        while (i < cleaned.length) {
            val cp = cleaned.codePointAt(i)
            val charCount = Character.charCount(cp)
            val token = String(Character.toChars(cp))
            if (cp >= 0x20 && canEncodeGlyph(font, token)) {
                sb.append(token)
            } else {
                val fallback = when (cp) {
                    0x2713, 0x2714, 0x2611 -> "+"
                    0x2715, 0x2716, 0x2717, 0x2718 -> "x"
                    0x2014, 0x2013, 0x2212 -> "-"
                    0x00AB, 0x00BB, 0x201C, 0x201D, 0x201E -> "\""
                    0x2018, 0x2019 -> "'"
                    0x00B7, 0x2022 -> "-"
                    0x2116 -> "No."
                    0x2026 -> "..."
                    else -> ""
                }
                if (fallback.isNotEmpty() && canEncodeGlyph(font, fallback)) {
                    sb.append(fallback)
                }
            }
            i += charCount
        }
        return sb.toString()
    }

    private fun drawTextSafe(
        cs: PDPageContentStream,
        font: PDFont,
        size: Float,
        color: Color,
        x: Float,
        y: Float,
        text: String
    ) {
        val safeText = sanitizeForFont(font, text)
        if (safeText.isEmpty()) return
        runCatching {
            cs.setNonStrokingColor(color)
            cs.beginText()
            try {
                cs.setFont(font, size)
                cs.newLineAtOffset(x, y)
                cs.showText(safeText)
            } finally {
                runCatching { cs.endText() }
            }
        }
    }

    private fun wrapWords(text: String, maxCols: Int): List<String> {
        val words = text.replace("\r", "").replace("\n", " ").split(" ").filter { it.isNotEmpty() }
        val lines = mutableListOf<String>()
        var cur = StringBuilder()
        for (rawWord in words) {
            var w = rawWord
            while (w.length > maxCols) {
                if (cur.isNotEmpty()) {
                    lines.add(cur.toString())
                    cur = StringBuilder()
                }
                lines.add(w.substring(0, maxCols))
                w = w.substring(maxCols)
            }
            if (cur.isEmpty()) cur.append(w)
            else if (cur.length + 1 + w.length <= maxCols) cur.append(' ').append(w)
            else {
                lines.add(cur.toString())
                cur = StringBuilder(w)
            }
        }
        if (cur.isNotEmpty()) lines.add(cur.toString())
        return lines
    }
}

data class DesktopReleaseUpdate(
    val tagName: String,
    val versionName: String,
    val versionCode: Int?,
    val htmlUrl: String,
    val setupExeUrl: String
)

object DesktopUpdateManager {

    private const val OWNER = "zigorminsk-debug"
    private const val REPO = "Helpdesk"
    private const val ATOM_URL = "https://github.com/$OWNER/$REPO/releases.atom"

    private val ENTRY_REGEX = Regex("""<entry\b[^>]*>(.*?)</entry>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val TAG_LINK_REGEX = Regex("""href\s*=\s*["']([^"']*/releases/tag/([^"'/?#]+))["']""", RegexOption.IGNORE_CASE)
    private val VERSION_CODE_REGEX = Regex("""versionCode\s*=\s*(\d+)""", RegexOption.IGNORE_CASE)

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    suspend fun checkForWindowsUpdate(
        currentVersionName: String = DesktopBuildInfo.versionName,
        currentVersionCode: Int = DesktopBuildInfo.versionCode
    ): Result<DesktopReleaseUpdate?> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url(ATOM_URL)
                .header("User-Agent", "Help-CSL-Windows/$currentVersionName")
                .header("Accept", "application/atom+xml, application/xml, text/xml;q=0.9, */*;q=0.8")
                .build()
            val xml = client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                resp.body?.string().orEmpty()
            }

            var best: DesktopReleaseUpdate? = null
            for (m in ENTRY_REGEX.findAll(xml)) {
                val block = m.groupValues[1]
                val linkMatch = TAG_LINK_REGEX.find(block) ?: continue
                val htmlUrl = linkMatch.groupValues[1]
                val tag = linkMatch.groupValues[2].trim()
                val cleanVer = tag.removePrefix("v").removePrefix("V").trim()
                val vCode = VERSION_CODE_REGEX.find(block)?.groupValues?.getOrNull(1)?.toIntOrNull()

                val isNewer = if (vCode != null) {
                    vCode > currentVersionCode
                } else {
                    compareSemVer(cleanVer, currentVersionName) > 0
                }
                if (isNewer) {
                    val candidate = DesktopReleaseUpdate(
                        tagName = tag,
                        versionName = cleanVer,
                        versionCode = vCode,
                        htmlUrl = htmlUrl,
                        setupExeUrl = "https://github.com/$OWNER/$REPO/releases/download/$tag/setup.exe"
                    )
                    if (best == null || (candidate.versionCode ?: 0) > (best.versionCode ?: 0)) {
                        best = candidate
                    }
                }
            }
            best
        }
    }

    suspend fun downloadAndLaunchSetupExe(
        update: DesktopReleaseUpdate,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val outDir = File(System.getProperty("java.io.tmpdir"), "HelpCSL-Update").apply { mkdirs() }
            val target = File(outDir, "Help-CSL-Setup-v${update.versionName}.exe")
            val req = Request.Builder()
                .url(update.setupExeUrl)
                .header("User-Agent", "Help-CSL-Windows/${DesktopBuildInfo.versionName}")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw IllegalStateException("Файл setup.exe для версии ${update.tagName} ещё собирается или недоступен (HTTP ${resp.code})")
                }
                val body = resp.body ?: throw IllegalStateException("Пустой ответ при загрузке setup.exe")
                val total = body.contentLength()
                var downloaded = 0L
                body.byteStream().use { input ->
                    FileOutputStream(target).use { output ->
                        val buf = ByteArray(32 * 1024)
                        while (true) {
                            val read = input.read(buf)
                            if (read < 0) break
                            output.write(buf, 0, read)
                            downloaded += read
                            onProgress(downloaded, total)
                        }
                    }
                }
            }
            ProcessBuilder(target.absolutePath).start()
            target
        }
    }

    private fun compareSemVer(a: String, b: String): Int {
        val pa = a.split('.').map { it.toIntOrNull() ?: 0 }
        val pb = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val da = pa.getOrElse(i) { 0 }
            val db = pb.getOrElse(i) { 0 }
            if (da != db) return da.compareTo(db)
        }
        return 0
    }
}
