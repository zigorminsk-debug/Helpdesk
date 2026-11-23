package com.helpdesk.desktop

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.helpdesk.jiraagent.domain.CslCompanyContacts
import com.helpdesk.jiraagent.domain.EngineerNameResolver
import com.helpdesk.jiraagent.domain.PeriodAnalyticsSummary
import com.helpdesk.jiraagent.domain.RemoteDesktopKind
import com.helpdesk.jiraagent.model.AlertSoundPreset
import com.helpdesk.jiraagent.model.AppSettings
import com.helpdesk.jiraagent.model.AuthType
import com.helpdesk.jiraagent.model.HelpdeskTicket
import com.helpdesk.jiraagent.model.RepairEquipmentCodec
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
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Desktop
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.awt.Image
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.SwingUtilities
import javax.swing.Timer
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
                        val playedViaJava = runCatching {
                            AudioSystem.getAudioInputStream(f).use { stream ->
                                val clip = AudioSystem.getClip()
                                clip.open(stream)
                                clip.start()
                                Thread.sleep((clip.microsecondLength / 1000L).coerceIn(400L, 8_000L))
                                clip.close()
                            }
                            true
                        }.getOrDefault(false)
                        if (playedViaJava || playWavViaWindowsSoundPlayer(f)) {
                            return@thread
                        }
                    }
                }
                val notes: List<Pair<Double, Int>> = when (preset) {
                    AlertSoundPreset.CSL_CHIME -> listOf(
                        587.33 to 160, 783.99 to 160, 1046.50 to 270, 0.0 to 70,
                        783.99 to 140, 1046.50 to 300
                    )
                    AlertSoundPreset.DISPATCH_PULSE -> listOf(
                        880.0 to 170, 0.0 to 80, 880.0 to 170, 0.0 to 80, 1174.66 to 300
                    )
                    AlertSoundPreset.HARMONIC_CHORD -> listOf(
                        523.25 to 190, 659.25 to 190, 783.99 to 340
                    )
                    AlertSoundPreset.URGENT_ALARM -> listOf(
                        987.77 to 150, 783.99 to 150, 987.77 to 150, 783.99 to 150, 1318.51 to 340
                    )
                    AlertSoundPreset.SYSTEM_DEFAULT, AlertSoundPreset.CUSTOM_URI -> listOf(
                        659.25 to 180, 880.0 to 280
                    )
                }
                playSynthesizedSequence(notes)
            }.onFailure {
                runCatching { Toolkit.getDefaultToolkit().beep() }
            }
        }
    }

    internal fun buildStereoPcmAndWav(notes: List<Pair<Double, Int>>, sampleRate: Int = 44100): Pair<ByteArray, ByteArray> {
        val pcmBos = ByteArrayOutputStream()
        for ((freq, durationMs) in notes) {
            val totalSamples = ((durationMs / 1000.0) * sampleRate).toInt().coerceAtLeast(1)
            for (i in 0 until totalSamples) {
                val sampleVal = if (freq <= 1.0) {
                    0.0
                } else {
                    val t = i / sampleRate.toDouble()
                    val attack = (i / (sampleRate * 0.010)).coerceAtMost(1.0)
                    val decay = exp(-2.3 * t)
                    val fundamental = sin(2.0 * PI * freq * t)
                    val overtone = 0.32 * sin(2.0 * PI * (freq * 2.0) * t)
                    val fifth = 0.14 * sin(2.0 * PI * (freq * 1.5) * t)
                    ((fundamental + overtone + fifth) / 1.46) * attack * decay * 0.90
                }
                val pcm = (sampleVal * Short.MAX_VALUE).toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                val lo = pcm and 0xFF
                val hi = (pcm shr 8) and 0xFF
                // 2-channel Stereo (Left + Right) for universal Windows WASAPI/USB/Bluetooth compatibility
                pcmBos.write(lo)
                pcmBos.write(hi)
                pcmBos.write(lo)
                pcmBos.write(hi)
            }
        }
        val pcmData = pcmBos.toByteArray()

        val channels = 2
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * (bitsPerSample / 8)
        val blockAlign = channels * (bitsPerSample / 8)
        val wavBuffer = ByteBuffer.allocate(44 + pcmData.size).order(ByteOrder.LITTLE_ENDIAN)
        wavBuffer.put("RIFF".toByteArray(Charsets.US_ASCII))
        wavBuffer.putInt(36 + pcmData.size)
        wavBuffer.put("WAVE".toByteArray(Charsets.US_ASCII))
        wavBuffer.put("fmt ".toByteArray(Charsets.US_ASCII))
        wavBuffer.putInt(16)
        wavBuffer.putShort(1) // PCM
        wavBuffer.putShort(channels.toShort())
        wavBuffer.putInt(sampleRate)
        wavBuffer.putInt(byteRate)
        wavBuffer.putShort(blockAlign.toShort())
        wavBuffer.putShort(bitsPerSample.toShort())
        wavBuffer.put("data".toByteArray(Charsets.US_ASCII))
        wavBuffer.putInt(pcmData.size)
        wavBuffer.put(pcmData)

        return pcmData to wavBuffer.array()
    }

    private fun playSynthesizedSequence(notes: List<Pair<Double, Int>>) {
        val sampleRate = 44100
        val (stereoPcm, wavBytes) = buildStereoPcmAndWav(notes, sampleRate)
        val stereoFormat = AudioFormat(sampleRate.toFloat(), 16, 2, true, false)

        // 1. Primary: Stereo SourceDataLine
        val playedLine = runCatching {
            AudioSystem.getSourceDataLine(stereoFormat).use { line ->
                line.open(stereoFormat)
                line.start()
                line.write(stereoPcm, 0, stereoPcm.size)
                line.drain()
            }
            true
        }.getOrDefault(false)
        if (playedLine) return

        // 2. Fallback: Java Sound Clip from in-memory RIFF/WAV
        val playedClip = runCatching {
            AudioSystem.getAudioInputStream(ByteArrayInputStream(wavBytes)).use { ais ->
                val clip = AudioSystem.getClip()
                clip.open(ais)
                clip.start()
                Thread.sleep((clip.microsecondLength / 1000L).coerceIn(350L, 6_000L))
                clip.close()
            }
            true
        }.getOrDefault(false)
        if (playedClip) return

        // 3. Native Windows winmm.dll fallback via System.Media.SoundPlayer
        val outDir = File(System.getProperty("java.io.tmpdir"), "HelpCSL-Audio").apply { mkdirs() }
        val tempWav = File(outDir, "helpcsl_alert.wav")
        runCatching { tempWav.writeBytes(wavBytes) }
        if (!playWavViaWindowsSoundPlayer(tempWav)) {
            runCatching { Toolkit.getDefaultToolkit().beep() }
        }
    }

    private fun playWavViaWindowsSoundPlayer(wavFile: File): Boolean {
        val isWin = System.getProperty("os.name").orEmpty().contains("win", ignoreCase = true)
        if (!isWin || !wavFile.exists()) return false
        return runCatching {
            val escapedPath = wavFile.absolutePath.replace("'", "''")
            val proc = ProcessBuilder(
                "powershell.exe",
                "-NoProfile",
                "-NonInteractive",
                "-WindowStyle",
                "Hidden",
                "-Command",
                "(New-Object System.Media.SoundPlayer '$escapedPath').PlaySync()"
            ).start()
            proc.waitFor(10, TimeUnit.SECONDS) && proc.exitValue() == 0
        }.getOrDefault(false)
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

/**
 * Hardware-independent Swing Always-On-Top Popup Toast Window at the bottom-right corner above
 * the Windows taskbar. Works 100% reliably even when the main Compose window is hidden in the
 * Windows system tray (`visible = false`), minimized, or when Windows 11 Focus Assist is active.
 */
object DesktopNativePopupManager {

    @Volatile
    private var activeDialog: JDialog? = null

    @Volatile
    private var dismissTimer: Timer? = null

    fun showAlert(
        alert: DesktopToastAlert,
        onTakeInWork: ((String) -> Unit)?,
        onOpen: () -> Unit,
        onDismissed: () -> Unit
    ) {
        if (GraphicsEnvironment.isHeadless()) return

        SwingUtilities.invokeLater {
            runCatching {
                dismissCurrentInternal(notifyCallback = false)

                val dialog = JDialog(null as java.awt.Frame?, "Help-CSL Уведомление", false).apply {
                    isUndecorated = true
                    runCatching { type = java.awt.Window.Type.POPUP }
                    isAlwaysOnTop = true
                    focusableWindowState = false
                }

                val bgNavy = Color(4, 9, 30)
                val borderOrange = Color(247, 99, 27)
                val btnDark = Color(19, 30, 62)

                val root = JPanel(BorderLayout(0, 8)).apply {
                    background = bgNavy
                    border = BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(borderOrange, 2),
                        BorderFactory.createEmptyBorder(12, 14, 12, 14)
                    )
                }

                // Top Row: Title + Close button
                val headerPanel = JPanel(BorderLayout(8, 0)).apply {
                    isOpaque = false
                }
                val titleLabel = JLabel(alert.title).apply {
                    foreground = borderOrange
                    font = Font("Segoe UI", Font.BOLD, 14)
                }
                val closeLabel = JLabel("✕").apply {
                    foreground = Color(203, 213, 225)
                    font = Font("Segoe UI", Font.BOLD, 14)
                    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    addMouseListener(object : MouseAdapter() {
                        override fun mouseClicked(e: MouseEvent?) {
                            dismissCurrentInternal(notifyCallback = false)
                            onDismissed()
                        }
                    })
                }
                headerPanel.add(titleLabel, BorderLayout.CENTER)
                headerPanel.add(closeLabel, BorderLayout.EAST)

                // Middle: Wrapped ticket summary (clickable to open)
                val bodyArea = JTextArea(alert.subtitle.take(180)).apply {
                    isEditable = false
                    isFocusable = false
                    isOpaque = false
                    lineWrap = true
                    wrapStyleWord = true
                    foreground = Color.WHITE
                    font = Font("Segoe UI", Font.PLAIN, 13)
                    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    addMouseListener(object : MouseAdapter() {
                        override fun mouseClicked(e: MouseEvent?) {
                            dismissCurrentInternal(notifyCallback = false)
                            onOpen()
                        }
                    })
                }

                // Bottom Row: Action Buttons («▶ В работу» + «Открыть»)
                val actionsPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply {
                    isOpaque = false
                }

                val ticketKey = alert.ticketKey
                if (ticketKey != null && !alert.isStormSummary && onTakeInWork != null) {
                    val takeBtn = JButton("▶ В работу").apply {
                        isFocusable = false
                        background = btnDark
                        foreground = Color.WHITE
                        font = Font("Segoe UI", Font.BOLD, 12)
                        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                        border = BorderFactory.createCompoundBorder(
                            BorderFactory.createLineBorder(borderOrange, 1),
                            BorderFactory.createEmptyBorder(5, 12, 5, 12)
                        )
                        addActionListener {
                            dismissCurrentInternal(notifyCallback = false)
                            onTakeInWork(ticketKey)
                        }
                    }
                    actionsPanel.add(takeBtn)
                }

                val openBtn = JButton("Открыть").apply {
                    isFocusable = false
                    background = borderOrange
                    foreground = Color.WHITE
                    font = Font("Segoe UI", Font.BOLD, 12)
                    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    border = BorderFactory.createEmptyBorder(6, 14, 6, 14)
                    addActionListener {
                        dismissCurrentInternal(notifyCallback = false)
                        onOpen()
                    }
                }
                actionsPanel.add(openBtn)

                root.add(headerPanel, BorderLayout.NORTH)
                root.add(bodyArea, BorderLayout.CENTER)
                root.add(actionsPanel, BorderLayout.SOUTH)

                val toastWidth = 430
                val toastHeight = 152
                root.preferredSize = Dimension(toastWidth, toastHeight)
                dialog.contentPane = root
                dialog.pack()
                dialog.setSize(toastWidth, toastHeight)

                val maxBounds = runCatching {
                    GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
                }.getOrNull()
                val screenSize = runCatching { Toolkit.getDefaultToolkit().screenSize }.getOrNull()
                val rightEdge = maxBounds?.maxX?.toInt() ?: screenSize?.width ?: 1920
                val bottomEdge = maxBounds?.maxY?.toInt() ?: ((screenSize?.height ?: 1080) - 48)
                val posX = (rightEdge - toastWidth - 18).coerceAtLeast(16)
                val posY = (bottomEdge - toastHeight - 18).coerceAtLeast(16)
                dialog.setLocation(posX, posY)

                dialog.isVisible = true
                dialog.toFront()
                activeDialog = dialog

                val timer = Timer(35_000) {
                    dismissCurrentInternal(notifyCallback = false)
                    onDismissed()
                }.apply {
                    isRepeats = false
                    start()
                }
                dismissTimer = timer
            }
        }
    }

    fun dismiss() {
        if (GraphicsEnvironment.isHeadless()) return
        SwingUtilities.invokeLater {
            dismissCurrentInternal(notifyCallback = false)
        }
    }

    private fun dismissCurrentInternal(notifyCallback: Boolean) {
        runCatching { dismissTimer?.stop() }
        dismissTimer = null
        val prev = activeDialog
        activeDialog = null
        if (prev != null) {
            runCatching {
                prev.isVisible = false
                prev.dispose()
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

    private val BROWSER_EXECUTABLE_NAMES = setOf(
        "chrome.exe", "msedge.exe", "firefox.exe", "opera.exe",
        "browser.exe", "yandex.exe", "brave.exe", "vivaldi.exe",
        "iexplore.exe", "waterfox.exe", "maxthon.exe", "arc.exe", "thorium.exe"
    )

    private fun isBrowserPath(raw: String): Boolean {
        val low = raw.lowercase()
        return BROWSER_EXECUTABLE_NAMES.any { low.contains(it) }
    }

    /**
     * Finds a running non-WindowsApps Win32 executable matching [exeNamesLower] via JDK 17 ProcessHandle.
     * Catches portable apps (e.g. D:\Telegram\Telegram.exe or Portable AnyDesk/MicroSIP) instantly.
     */
    private fun findRunningWin32Executable(exeNamesLower: Set<String>): File? {
        return runCatching {
            var found: File? = null
            ProcessHandle.allProcesses().forEach { ph ->
                if (found != null) return@forEach
                val cmd = ph.info().command().orElse(null) ?: return@forEach
                val f = File(cmd)
                val nameLow = f.name.lowercase()
                if (nameLow in exeNamesLower && !cmd.lowercase().contains("\\windowsapps\\") && f.exists()) {
                    found = f
                }
            }
            found
        }.getOrNull()
    }

    private fun isRunningInWindowsApps(exeNamesLower: Set<String>): Boolean {
        return runCatching {
            var running = false
            ProcessHandle.allProcesses().forEach { ph ->
                if (running) return@forEach
                val cmd = ph.info().command().orElse(null) ?: return@forEach
                val nameLow = File(cmd).name.lowercase()
                if (nameLow in exeNamesLower && cmd.lowercase().contains("\\windowsapps\\")) {
                    running = true
                }
            }
            running
        }.getOrDefault(false)
    }

    /**
     * Queries Windows Registry (`HKCU\Software\Classes\<scheme>` & `HKCR\<scheme>`) to:
     * 1) Extract a direct Win32 `.exe` path if registered (and NOT a web browser!),
     * 2) Detect whether a native/Store UWP handler (`URL Protocol` / `DelegateExecute` / `AppX`) is installed.
     */
    private fun inspectProtocolRegistration(scheme: String): Pair<File?, Boolean> {
        val cleanScheme = scheme.trim().lowercase()
        val regRoots = listOf(
            "HKCU\\Software\\Classes\\$cleanScheme",
            "HKCR\\$cleanScheme"
        )
        for (root in regRoots) {
            val cmdOut = runCatching {
                val proc = ProcessBuilder("reg.exe", "query", "$root\\shell\\open\\command", "/ve")
                    .redirectErrorStream(true)
                    .start()
                val finished = proc.waitFor(1500, java.util.concurrent.TimeUnit.MILLISECONDS)
                if (!finished) {
                    proc.destroyForcibly()
                    ""
                } else if (proc.exitValue() == 0) {
                    proc.inputStream.bufferedReader().readText()
                } else ""
            }.getOrDefault("")

            if (cmdOut.isNotBlank() && !isBrowserPath(cmdOut)) {
                val quotedExe = Regex("\"([A-Za-z]:\\\\[^\"]+\\.exe)\"", RegexOption.IGNORE_CASE)
                    .find(cmdOut)?.groupValues?.getOrNull(1)
                val unquotedExe = if (quotedExe == null) {
                    Regex("([A-Za-z]:\\\\[^\\r\\n\"]+?\\.exe)", RegexOption.IGNORE_CASE)
                        .find(cmdOut)?.groupValues?.getOrNull(1)
                } else null
                val exePath = quotedExe ?: unquotedExe
                if (exePath != null && !exePath.lowercase().contains("\\windowsapps\\")) {
                    val f = File(exePath)
                    if (f.exists()) return f to true
                }
                return null to true
            }

            // Microsoft Store / MSIX UWP apps register DelegateExecute or AppX under shell\open or root key
            val rootOut = runCatching {
                val proc = ProcessBuilder("reg.exe", "query", root)
                    .redirectErrorStream(true)
                    .start()
                val finished = proc.waitFor(1200, java.util.concurrent.TimeUnit.MILLISECONDS)
                if (!finished) {
                    proc.destroyForcibly()
                    ""
                } else if (proc.exitValue() == 0) {
                    proc.inputStream.bufferedReader().readText()
                } else ""
            }.getOrDefault("")

            if (rootOut.isNotBlank() && !isBrowserPath(rootOut)) {
                if (rootOut.contains("URL Protocol", ignoreCase = true) ||
                    rootOut.contains("AppX", ignoreCase = true) ||
                    rootOut.contains("shell", ignoreCase = true)
                ) {
                    return null to true
                }
            }
        }
        return null to false
    }

    private fun launchViaWindowsShellProtocol(uri: String): Boolean {
        return runCatching {
            ProcessBuilder("explorer.exe", uri).start()
            true
        }.getOrElse {
            runCatching {
                ProcessBuilder("rundll32.exe", "url.dll,FileProtocolHandler", uri).start()
                true
            }.getOrDefault(false)
        }
    }

    /**
     * Opens an `http://` or `https://` web URL in the default browser.
     * IMPORTANT: Never pass custom app protocols (`tg://`, `viber://`, `whatsapp://`, `tel:`) to
     * `Desktop.getDesktop().browse(...)` because OpenJDK 17 `WDesktopPeer.browse` on Windows
     * unconditionally spawns `defaultBrowser.exe <uri>` instead of `ShellExecute`!
     */
    fun openUrl(url: String) {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return
        val scheme = trimmed.substringBefore(':', "").lowercase()
        if (scheme == "http" || scheme == "https") {
            runCatching {
                if (Desktop.isDesktopSupported()) {
                    Desktop.getDesktop().browse(URI(trimmed))
                    return
                }
            }
            runCatching {
                ProcessBuilder("explorer.exe", trimmed).start()
            }
            return
        }

        val (regExe, hasHandler) = inspectProtocolRegistration(scheme)
        if (regExe != null) {
            runCatching {
                ProcessBuilder(regExe.absolutePath, trimmed).directory(regExe.parentFile).start()
                return
            }
        }
        if (hasHandler) {
            launchViaWindowsShellProtocol(trimmed)
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
            val candidates = listOfNotNull(
                findRunningWin32Executable(setOf("anydesk.exe")),
                File(progFilesX86, "AnyDesk\\AnyDesk.exe"),
                File(progFiles, "AnyDesk\\AnyDesk.exe"),
                File(localAppData, "Programs\\AnyDesk\\AnyDesk.exe"),
                inspectProtocolRegistration("anydesk").first
            )
            val exe = candidates.firstOrNull { it.exists() }
            if (exe != null) {
                runCatching {
                    ProcessBuilder(exe.absolutePath, cleanId).directory(exe.parentFile).start()
                    return "Запущен AnyDesk → $cleanId (ID также в буфере)"
                }
            }
            if (inspectProtocolRegistration("anydesk").second) {
                launchViaWindowsShellProtocol("anydesk:$cleanId")
                return "ID $cleanId скопирован и отправлен в AnyDesk"
            }
            return "⚠️ Установленный AnyDesk не найден на ПК (ID $cleanId скопирован в буфер)"
        } else {
            val candidates = listOfNotNull(
                findRunningWin32Executable(setOf("rustdesk.exe")),
                File(progFiles, "RustDesk\\rustdesk.exe"),
                File(progFilesX86, "RustDesk\\rustdesk.exe"),
                File(localAppData, "Programs\\RustDesk\\rustdesk.exe"),
                inspectProtocolRegistration("rustdesk").first
            )
            val exe = candidates.firstOrNull { it.exists() }
            if (exe != null) {
                runCatching {
                    ProcessBuilder(exe.absolutePath, "--connect", cleanId).directory(exe.parentFile).start()
                    return "Запущен RustDesk → $cleanId (ID также в буфере)"
                }
            }
            if (inspectProtocolRegistration("rustdesk").second) {
                launchViaWindowsShellProtocol("rustdesk://$cleanId")
                return "ID $cleanId скопирован и отправлен в RustDesk"
            }
            return "⚠️ Установленный RustDesk не найден на ПК (ID $cleanId скопирован в буфер)"
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

    fun callSipPhone(phoneE164: String): String {
        val cleanPhone = phoneE164.trim()
        copyToClipboard(cleanPhone)
        val appData = System.getenv("APPDATA").orEmpty()
        val localAppData = System.getenv("LOCALAPPDATA").orEmpty()
        val progFiles = System.getenv("ProgramFiles").orEmpty()
        val progFilesX86 = System.getenv("ProgramFiles(x86)").orEmpty()

        // 1. Check MicroSIP first (most popular lightweight SIP softphone — accepts raw phone argument directly)
        val microSipCandidates = listOfNotNull(
            findRunningWin32Executable(setOf("microsip.exe")),
            File(localAppData, "MicroSIP\\microsip.exe"),
            File(appData, "MicroSIP\\microsip.exe"),
            File(progFilesX86, "MicroSIP\\microsip.exe"),
            File(progFiles, "MicroSIP\\microsip.exe")
        )
        microSipCandidates.firstOrNull { it.exists() }?.let { exe ->
            val ok = runCatching {
                ProcessBuilder(exe.absolutePath, cleanPhone).directory(exe.parentFile).start()
                true
            }.getOrDefault(false)
            if (ok) return "📞 Вызов $cleanPhone передан в MicroSIP"
        }

        // 2. Check PhonerLite / 3CX / Zoiper / Linphone / Bria
        val otherSipRunning = findRunningWin32Executable(
            setOf(
                "phonerlite.exe", "3cxdesktopapp.exe", "3cxwin8phone.exe", "3cxphone.exe",
                "zoiper5.exe", "zoiper.exe", "linphone.exe", "bria.exe", "x-lite.exe", "eyebeam.exe"
            )
        )
        val otherSipCandidates = listOfNotNull(
            otherSipRunning,
            File(progFilesX86, "PhonerLite\\PhonerLite.exe"),
            File(progFiles, "PhonerLite\\PhonerLite.exe"),
            File(localAppData, "Programs\\3CXDesktopApp\\3CXDesktopApp.exe"),
            File(progFiles, "3CXPhone\\3CXWin8Phone.exe"),
            File(progFilesX86, "3CXPhone\\3CXPhone.exe"),
            File(progFilesX86, "Zoiper5\\Zoiper5.exe"),
            File(progFiles, "Linphone\\bin\\linphone.exe")
        )
        otherSipCandidates.firstOrNull { it.exists() }?.let { exe ->
            val arg = if (exe.name.equals("PhonerLite.exe", ignoreCase = true)) "callto:$cleanPhone" else "tel:$cleanPhone"
            val ok = runCatching {
                ProcessBuilder(exe.absolutePath, arg).directory(exe.parentFile).start()
                true
            }.getOrDefault(false)
            if (ok) return "📞 Вызов $cleanPhone передан в ${exe.nameWithoutExtension}"
        }

        // 3. Check registered non-browser protocol handler (`tel`, `sip`, `callto`)
        for (scheme in listOf("tel", "sip", "callto")) {
            val (regExe, hasHandler) = inspectProtocolRegistration(scheme)
            if (regExe != null) {
                val ok = runCatching {
                    ProcessBuilder(regExe.absolutePath, "$scheme:$cleanPhone").directory(regExe.parentFile).start()
                    true
                }.getOrDefault(false)
                if (ok) return "📞 Вызов $cleanPhone передан в ${regExe.nameWithoutExtension}"
            }
            if (hasHandler && launchViaWindowsShellProtocol("$scheme:$cleanPhone")) {
                return "📞 Вызов $cleanPhone передан в SIP-софтфон"
            }
        }

        return "📋 Номер $cleanPhone скопирован (установленный SIP-софтфон не найден на ПК)"
    }

    fun dialSipOrTel(phoneE164: String): String = callSipPhone(phoneE164)

    fun openBrowser(url: String) = openUrl(url)

    fun launchAnyDesk(rawId: String): String = launchRemoteDesktop(RemoteDesktopKind.ANYDESK, rawId)

    fun launchRustDesk(rawId: String): String = launchRemoteDesktop(RemoteDesktopKind.RUSTDESK, rawId)

    /**
     * Launches the installed Telegram Desktop app directly (`Telegram.exe -- tg://resolve?...` or UWP Store app).
     * Never opens a web browser (`t.me`).
     */
    fun openTelegram(phoneE164: String, usernameHandle: String? = null): String {
        val tgUri: String
        val label: String
        if (!usernameHandle.isNullOrBlank()) {
            val clean = usernameHandle.trim().removePrefix("@")
            copyToClipboard("@$clean")
            tgUri = "tg://resolve?domain=$clean"
            label = "@$clean"
        } else {
            val digits = phoneE164.filter { it.isDigit() }
            copyToClipboard("+$digits")
            tgUri = "tg://resolve?phone=$digits"
            label = "+$digits"
        }

        val appData = System.getenv("APPDATA").orEmpty()
        val localAppData = System.getenv("LOCALAPPDATA").orEmpty()
        val progFiles = System.getenv("ProgramFiles").orEmpty()
        val progFilesX86 = System.getenv("ProgramFiles(x86)").orEmpty()
        val userHome = System.getProperty("user.home").orEmpty()

        val tgExeNames = setOf("telegram.exe", "ayugram.exe", "64gram.exe", "kotatogram.exe", "unigram.exe")
        val (regExe, hasProtocolHandler) = inspectProtocolRegistration("tg")

        val win32Candidates = listOfNotNull(
            findRunningWin32Executable(tgExeNames),
            regExe,
            File(appData, "Telegram Desktop\\Telegram.exe"),
            File(localAppData, "Programs\\Telegram Desktop\\Telegram.exe"),
            File(progFiles, "Telegram Desktop\\Telegram.exe"),
            File(progFilesX86, "Telegram Desktop\\Telegram.exe"),
            File(userHome, "Downloads\\Telegram Desktop\\Telegram.exe"),
            File(appData, "AyuGramDesktop\\AyuGram.exe"),
            File(appData, "64Gram\\Telegram.exe")
        )
        win32Candidates.firstOrNull { it.exists() }?.let { exe ->
            val ok = runCatching {
                ProcessBuilder(exe.absolutePath, "--", tgUri).directory(exe.parentFile).start()
                true
            }.getOrDefault(false)
            if (ok) return "✈ Открыт чат $label в ${exe.nameWithoutExtension} Desktop"
        }

        val storeAlias = File(localAppData, "Microsoft\\WindowsApps\\Telegram.exe")
        if (storeAlias.exists() || isRunningInWindowsApps(tgExeNames) || hasProtocolHandler) {
            if (launchViaWindowsShellProtocol(tgUri)) {
                return "✈ Открыт чат $label в Telegram Desktop"
            }
        }

        return "⚠️ Установленный Telegram Desktop не найден на ПК (контакт $label скопирован в буфер)"
    }

    /**
     * Launches the installed Rakuten Viber Desktop app directly (`Viber.exe viber://chat?number=...`).
     * Never opens a web browser.
     */
    fun openViber(phoneE164: String): String {
        val digits = phoneE164.filter { it.isDigit() }
        copyToClipboard("+$digits")
        val viberUri = "viber://chat?number=%2B$digits"

        val appData = System.getenv("APPDATA").orEmpty()
        val localAppData = System.getenv("LOCALAPPDATA").orEmpty()
        val progFiles = System.getenv("ProgramFiles").orEmpty()
        val progFilesX86 = System.getenv("ProgramFiles(x86)").orEmpty()

        val viberExeNames = setOf("viber.exe")
        val (regExe, hasProtocolHandler) = inspectProtocolRegistration("viber")

        val win32Candidates = listOfNotNull(
            findRunningWin32Executable(viberExeNames),
            regExe,
            File(localAppData, "Viber\\Viber.exe"),
            File(appData, "ViberPC\\Viber.exe"),
            File(localAppData, "Programs\\Viber\\Viber.exe"),
            File(progFiles, "Viber\\Viber.exe"),
            File(progFilesX86, "Viber\\Viber.exe")
        )
        win32Candidates.firstOrNull { it.exists() }?.let { exe ->
            val ok = runCatching {
                ProcessBuilder(exe.absolutePath, viberUri).directory(exe.parentFile).start()
                true
            }.getOrDefault(false)
            if (ok) return "💬 Открыт чат +$digits в Viber Desktop"
        }

        val storeAlias = File(localAppData, "Microsoft\\WindowsApps\\Viber.exe")
        if (storeAlias.exists() || isRunningInWindowsApps(viberExeNames) || hasProtocolHandler) {
            if (launchViaWindowsShellProtocol(viberUri)) {
                return "💬 Открыт чат +$digits в Viber Desktop"
            }
        }

        return "⚠️ Установленный Viber не найден на ПК (номер +$digits скопирован в буфер)"
    }

    /**
     * Launches the installed WhatsApp Desktop app directly via `whatsapp://send?phone=...` or `WhatsApp.exe`.
     * Never opens `https://wa.me/...` in a web browser.
     */
    fun openWhatsApp(phoneE164: String): String {
        val digits = phoneE164.filter { it.isDigit() }
        copyToClipboard("+$digits")
        val waUri = "whatsapp://send?phone=$digits"

        val localAppData = System.getenv("LOCALAPPDATA").orEmpty()
        val progFiles = System.getenv("ProgramFiles").orEmpty()
        val progFilesX86 = System.getenv("ProgramFiles(x86)").orEmpty()

        val waExeNames = setOf("whatsapp.exe", "whatsapp.root.exe")
        val (regExe, hasProtocolHandler) = inspectProtocolRegistration("whatsapp")

        // Official Windows 10/11 WhatsApp Desktop is an MSIX/UWP package activated via whatsapp:// protocol
        val storeAlias = File(localAppData, "Microsoft\\WindowsApps\\WhatsApp.exe")
        if (hasProtocolHandler || isRunningInWindowsApps(waExeNames) || storeAlias.exists()) {
            if (launchViaWindowsShellProtocol(waUri)) {
                return "💬 Открыт чат +$digits в WhatsApp Desktop"
            }
        }

        val win32Candidates = listOfNotNull(
            findRunningWin32Executable(waExeNames),
            regExe,
            File(localAppData, "WhatsApp\\WhatsApp.exe"),
            File(localAppData, "Programs\\WhatsApp\\WhatsApp.exe"),
            File(progFiles, "WhatsApp\\WhatsApp.exe"),
            File(progFilesX86, "WhatsApp\\WhatsApp.exe")
        )
        win32Candidates.firstOrNull { it.exists() }?.let { exe ->
            val ok = runCatching {
                ProcessBuilder(exe.absolutePath, waUri).directory(exe.parentFile).start()
                true
            }.getOrDefault(false)
            if (ok) return "💬 Открыт чат +$digits в WhatsApp Desktop"
        }

        return "⚠️ Установленный WhatsApp Desktop не найден на ПК (номер +$digits скопирован в буфер)"
    }

    fun openMapForAddress(address: String) {
        val enc = URLEncoder.encode(address, "UTF-8")
        openUrl("https://yandex.by/maps/?text=$enc")
    }

    fun copyImageToClipboard(image: BufferedImage) {
        runCatching {
            val transferable = object : Transferable {
                override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.imageFlavor)
                override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = DataFlavor.imageFlavor.equals(flavor)
                override fun getTransferData(flavor: DataFlavor): Any {
                    if (!isDataFlavorSupported(flavor)) throw UnsupportedFlavorException(flavor)
                    return image
                }
            }
            Toolkit.getDefaultToolkit().systemClipboard.setContents(transferable, null)
        }
    }

    /**
     * Opens Confluence (`w.csl.by`) in the default Windows browser and automatically submits
     * the same Jira login/password to `dologin.action` when Basic/Form credentials are configured.
     */
    fun openConfluenceWithAutoLogin(targetUrl: String, settings: AppSettings) {
        val rawBase = settings.confluenceUrl.trim().ifBlank { AppSettings.DEFAULT_CONFLUENCE_URL }.trimEnd('/')
        val baseUrl = if (rawBase.startsWith("http://", ignoreCase = true) ||
            rawBase.startsWith("https://", ignoreCase = true)
        ) {
            rawBase
        } else {
            "https://$rawBase"
        }

        val resolvedTarget = when {
            targetUrl.isBlank() -> baseUrl
            targetUrl.startsWith("http://", ignoreCase = true) ||
                targetUrl.startsWith("https://", ignoreCase = true) -> targetUrl
            else -> "$baseUrl/${targetUrl.trimStart('/')}"
        }

        // Open directly in the user's normal browser so saved session cookies and Confluence 2FA work cleanly
        // without triggering Confluence's cross-origin dologin.action / Basic-Auth 2FA block.
        openUrl(resolvedTarget)
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

    /**
     * Generates a vector 58x40 mm QR sticker PDF (`REM-...`) suitable for thermal label printers
     * (Xprinter / Zebra / TSC) and regular office printers.
     */
    fun generateRepairQrStickerPdf(item: RepairEquipmentItem): File {
        val outDir = File(System.getProperty("java.io.tmpdir"), "HelpCSL-Documents").apply { mkdirs() }
        val code = item.intakeNumber.ifBlank { item.id.take(8).uppercase() }
        val safeCode = code.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val outFile = File(outDir, "Sticker_${safeCode}_${System.currentTimeMillis() / 1000}.pdf")

        // 58 mm x 40 mm in PDF points (1 mm = 2.83465 pt)
        val labelWidth = 164.4f
        val labelHeight = 113.4f

        PDDocument().use { doc ->
            val page = PDPage(PDRectangle(labelWidth, labelHeight))
            doc.addPage(page)
            val fontRegular = loadUnicodeFont(doc, bold = false)
            val fontBold = loadUnicodeFont(doc, bold = true)

            val payload = RepairEquipmentCodec.encodeQrStickerPayload(item)
            val hints = mapOf(
                EncodeHintType.CHARACTER_SET to "UTF-8",
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to 0
            )
            val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 33, 33, hints)

            PDPageContentStream(doc, page).use { cs ->
                // Top brand strip
                cs.setNonStrokingColor(Color(4, 9, 30))
                cs.addRect(0f, labelHeight - 16f, labelWidth, 16f)
                cs.fill()

                drawTextSafe(cs, fontBold, 7.5f, Color(247, 99, 27), 6f, labelHeight - 11f, "csl.by")
                drawTextSafe(cs, fontBold, 8f, Color.WHITE, 36f, labelHeight - 11f, code)
                val dateShort = runCatching {
                    Instant.ofEpochMilli(item.createdAtEpochMs)
                        .atZone(ZoneId.of("Europe/Minsk"))
                        .format(DateTimeFormatter.ofPattern("dd.MM.yy"))
                }.getOrDefault("")
                drawTextSafe(cs, fontRegular, 6.8f, Color(203, 213, 225), labelWidth - 36f, labelHeight - 11f, dateShort)

                // Vector QR on left (56 x 56 pt)
                val qrBoxSize = 58f
                val qrX = 5f
                val qrY = labelHeight - 16f - qrBoxSize - 6f
                val cols = matrix.width.coerceAtLeast(1)
                val cell = qrBoxSize / cols.toFloat()
                cs.setNonStrokingColor(Color.BLACK)
                for (my in 0 until matrix.height) {
                    for (mx in 0 until cols) {
                        if (matrix.get(mx, my)) {
                            val rx = qrX + mx * cell
                            val ry = qrY + (matrix.height - 1 - my) * cell
                            cs.addRect(rx, ry, cell + 0.12f, cell + 0.12f)
                        }
                    }
                }
                cs.fill()

                // Right metadata lines
                val textX = qrX + qrBoxSize + 5f
                var ty = labelHeight - 27f
                val clientShort = item.clientName.ifBlank { "Клиент" }.take(22)
                drawTextSafe(cs, fontBold, 7.2f, Color(15, 23, 42), textX, ty, clientShort)
                ty -= 9.5f

                if (item.clientPhone.isNotBlank()) {
                    drawTextSafe(cs, fontRegular, 6.6f, Color(51, 65, 85), textX, ty, item.clientPhone.take(20))
                    ty -= 9f
                }

                val devLine = "${item.deviceCategory} ${item.deviceModel}".trim().take(23)
                if (devLine.isNotBlank()) {
                    drawTextSafe(cs, fontBold, 6.6f, Color(15, 23, 42), textX, ty, devLine)
                    ty -= 9f
                }

                if (item.serialNumber.isNotBlank()) {
                    drawTextSafe(cs, fontRegular, 6.2f, Color(71, 85, 105), textX, ty, "S/N: ${item.serialNumber.take(18)}")
                    ty -= 8.5f
                }

                if (item.shelfLocation.isNotBlank()) {
                    drawTextSafe(cs, fontBold, 6.4f, Color(247, 99, 27), textX, ty, "Полка: ${item.shelfLocation.take(14)}")
                }

                // Bottom defect / footer line
                cs.setStrokingColor(Color(203, 213, 225))
                cs.setLineWidth(0.5f)
                cs.moveTo(5f, 16f)
                cs.lineTo(labelWidth - 5f, 16f)
                cs.stroke()

                val bottomLine = item.reportedDefect.ifBlank { "СЦ ООО «Компьютерная безопасность»" }.take(38)
                drawTextSafe(cs, fontRegular, 6.0f, Color(51, 65, 85), 5f, 7f, bottomLine)
            }
            doc.save(outFile)
        }
        return outFile
    }

    /**
     * Generates a corporate A4 PDF Shift Analytics Report (`Сегодня` / `7 дней` / `30 дней`).
     */
    fun generateShiftAnalyticsPdf(
        summary: PeriodAnalyticsSummary,
        engineerName: String
    ): File {
        val outDir = File(System.getProperty("java.io.tmpdir"), "HelpCSL-Documents").apply { mkdirs() }
        val outFile = File(outDir, "ShiftReport_${summary.period.name}_${System.currentTimeMillis() / 1000}.pdf")
        val cleanEngineer = EngineerNameResolver.resolve(
            rawEngineer = engineerName,
            connectedUserDisplayName = engineerName,
            fallbackLabel = "Дежурный инженер"
        )

        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            val fontRegular = loadUnicodeFont(doc, bold = false)
            val fontBold = loadUnicodeFont(doc, bold = true)
            val pw = page.mediaBox.width
            val ph = page.mediaBox.height

            PDPageContentStream(doc, page).use { cs ->
                drawCorporateCslHeader(
                    cs = cs,
                    pw = pw,
                    ph = ph,
                    fontBold = fontBold,
                    fontRegular = fontRegular,
                    docTitle = "ОТЧЁТ ДЕЖУРНОЙ СМЕНЫ (${summary.period.title.uppercase()} · ${summary.periodRangeDisplay})"
                )

                var y = ph - 145f
                drawTextSafe(cs, fontBold, 11f, Color(247, 99, 27), 36f, y, "1. Ключевые показатели очереди и SLA:")
                y -= 18f

                val kpis = listOf(
                    "Поступило клиентских обращений за период" to summary.createdInPeriod.toString(),
                    "Выполнено и закрыто за период (всего / мной)" to "${summary.closedInPeriod} / ${summary.closedByMeInPeriod}",
                    "Текущая очередь: Новые / В работе" to "${summary.activeNewCount} / ${summary.activeInProgressCount}",
                    "Среднее время ожидания в «Новых»" to "${summary.avgQueueWaitMinutes} мин.",
                    "Доля заявок в зелёной зоне SLA (< 15 мин)" to "${summary.slaGreenPercent}%"
                )
                for ((label, value) in kpis) {
                    drawTextSafe(cs, fontRegular, 10f, Color(51, 65, 85), 44f, y, "• $label:")
                    drawTextSafe(cs, fontBold, 10.5f, Color(15, 23, 42), 340f, y, value)
                    y -= 16f
                }

                y -= 10f
                drawTextSafe(cs, fontBold, 11f, Color(247, 99, 27), 36f, y, "2. Выработка инженеров техподдержки:")
                y -= 18f
                if (summary.engineerStats.isEmpty()) {
                    drawTextSafe(cs, fontRegular, 10f, Color(100, 116, 139), 44f, y, "Нет данных по назначенным заявкам за выбранный период.")
                    y -= 16f
                } else {
                    for (eng in summary.engineerStats.take(10)) {
                        drawTextSafe(cs, fontBold, 10f, Color(15, 23, 42), 44f, y, eng.engineerName.take(44))
                        drawTextSafe(
                            cs,
                            fontRegular,
                            10f,
                            Color(51, 65, 85),
                            320f,
                            y,
                            "Закрыто: ${eng.closedCount}   |   В работе: ${eng.inProgressCount}"
                        )
                        y -= 15f
                    }
                }

                y -= 10f
                drawTextSafe(cs, fontBold, 11f, Color(247, 99, 27), 36f, y, "3. Топ организаций / клиентов по числу обращений:")
                y -= 18f
                for (cl in summary.topClients.take(8)) {
                    if (y < 110f) break
                    drawTextSafe(cs, fontRegular, 10f, Color(15, 23, 42), 44f, y, cl.clientName.take(46))
                    drawTextSafe(
                        cs,
                        fontRegular,
                        10f,
                        Color(51, 65, 85),
                        320f,
                        y,
                        "Всего обращений: ${cl.totalCount} (открыто: ${cl.openCount})"
                    )
                    y -= 15f
                }

                val footerY = 72f
                cs.setStrokingColor(Color(203, 213, 225))
                cs.setLineWidth(0.8f)
                cs.moveTo(36f, footerY + 14f)
                cs.lineTo(pw - 36f, footerY + 14f)
                cs.stroke()

                val stamp = ZonedDateTime.now(ZoneId.of("Europe/Minsk")).format(DATE_FMT)
                drawTextSafe(
                    cs,
                    fontRegular,
                    9.5f,
                    Color(71, 85, 105),
                    36f,
                    footerY,
                    "Отчёт сформировал: $cleanEngineer   |   Дата формирования: $stamp"
                )
            }
            doc.save(outFile)
        }
        return outFile
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

object DesktopTrayIconRenderer {

    fun renderTrayIcon(baseIcon: BufferedImage, unreadCount: Int): BufferedImage {
        val size = 64
        val canvas = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = canvas.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)

            g.drawImage(baseIcon, 0, 0, size, size, null)

            if (unreadCount > 0) {
                val badgeLabel = if (unreadCount > 9) "9+" else unreadCount.toString()
                val badgeH = 30
                val badgeW = if (badgeLabel.length > 1) 38 else 30
                val bx = size - badgeW - 1
                val by = size - badgeH - 1

                // Dark halo + bright orange-red badge for instant taskbar visibility
                g.color = Color(0x04, 0x09, 0x1E, 235)
                g.fillRoundRect(bx - 2, by - 2, badgeW + 4, badgeH + 4, badgeH + 4, badgeH + 4)

                g.color = Color(0xEF, 0x44, 0x44)
                g.fillRoundRect(bx, by, badgeW, badgeH, badgeH, badgeH)

                g.stroke = BasicStroke(2.2f)
                g.color = Color.WHITE
                g.drawRoundRect(bx, by, badgeW, badgeH, badgeH, badgeH)

                val fontSize = if (badgeLabel.length > 1) 17 else 19
                g.font = Font(Font.SANS_SERIF, Font.BOLD, fontSize)
                val fm = g.fontMetrics
                val tx = bx + (badgeW - fm.stringWidth(badgeLabel)) / 2
                val ty = by + ((badgeH - fm.height) / 2) + fm.ascent
                g.drawString(badgeLabel, tx, ty)
            }
        } finally {
            g.dispose()
        }
        return canvas
    }
}

object DesktopRepairCsvExporter {

    private val MINSK_ZONE = ZoneId.of("Europe/Minsk")
    private val DATE_TIME_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
    private val FILE_DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm")

    fun exportToCsv(
        items: List<RepairEquipmentItem>,
        outFile: File? = null,
        connectedUserName: String? = null,
        currentUsernameOrEmail: String = "",
        knownTickets: List<HelpdeskTicket> = emptyList()
    ): File {
        val activeItems = items
            .filter { !it.isDeleted }
            .sortedByDescending { it.updatedAtEpochMs }

        val target = outFile ?: run {
            val docs = File(System.getProperty("user.home", "."), "Documents/Help-CSL_Acts").apply { mkdirs() }
            val stamp = ZonedDateTime.now(MINSK_ZONE).format(FILE_DATE_FMT)
            File(docs, "Help-CSL_Журнал_Ремонта_$stamp.csv")
        }
        target.parentFile?.mkdirs()

        val sb = StringBuilder()
        // UTF-8 BOM so Microsoft Excel (Russian locale) opens Cyrillic cleanly
        sb.append('\uFEFF')
        val headers = listOf(
            "Квитанция №",
            "Статус",
            "Клиент / Организация",
            "Телефон",
            "Заявка Jira",
            "Тип устройства",
            "Модель оборудования",
            "Серийный номер (S/N)",
            "Комплектность",
            "Заявленная неисправность",
            "Заметки СЦ / Ремонт",
            "Полка / Место",
            "Инженер СЦ",
            "Принято",
            "Обновлено",
            "Выдано"
        )
        sb.append(headers.joinToString(";") { csvCell(it) }).append("\r\n")

        for (item in activeItems) {
            val humanEngineer = EngineerNameResolver.resolve(
                rawEngineer = item.assignedEngineer,
                connectedUserDisplayName = connectedUserName,
                currentUsernameOrEmail = currentUsernameOrEmail,
                knownTickets = knownTickets
            )
            val row = listOf(
                item.intakeNumber.ifBlank { item.id.take(8).uppercase() },
                item.status.title,
                item.clientName,
                item.clientPhone,
                item.linkedTicketKey,
                item.deviceCategory,
                item.deviceModel,
                item.serialNumber,
                item.accessories,
                item.reportedDefect,
                item.repairNotes,
                item.shelfLocation,
                humanEngineer,
                formatEpoch(item.createdAtEpochMs),
                formatEpoch(item.updatedAtEpochMs),
                item.issuedAtEpochMs?.let { formatEpoch(it) }.orEmpty()
            )
            sb.append(row.joinToString(";") { csvCell(it) }).append("\r\n")
        }

        target.writeBytes(sb.toString().toByteArray(StandardCharsets.UTF_8))
        return target
    }

    private fun formatEpoch(epochMs: Long): String {
        if (epochMs <= 0L) return ""
        return runCatching {
            Instant.ofEpochMilli(epochMs).atZone(MINSK_ZONE).format(DATE_TIME_FMT)
        }.getOrDefault("")
    }

    private fun csvCell(raw: String?): String {
        val cleaned = raw.orEmpty()
            .replace("\r\n", " ")
            .replace('\n', ' ')
            .replace('\r', ' ')
            .trim()
        // Prevent Excel formula injection if text starts with =, +, - (non-phone), @
        val safe = if (cleaned.startsWith("=") || cleaned.startsWith("@")) "'$cleaned" else cleaned
        return if (safe.contains(';') || safe.contains('"')) {
            "\"${safe.replace("\"", "\"\"")}\""
        } else {
            safe
        }
    }
}
