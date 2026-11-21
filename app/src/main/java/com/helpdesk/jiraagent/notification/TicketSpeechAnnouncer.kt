package com.helpdesk.jiraagent.notification

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.speech.tts.TextToSpeech
import com.helpdesk.jiraagent.model.AppSettings
import com.helpdesk.jiraagent.model.HelpdeskTicket
import com.helpdesk.jiraagent.model.TtsReadoutMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

object TicketSpeechAnnouncer {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var tts: TextToSpeech? = null
    @Volatile
    private var ttsReady = false
    private var pendingUtterance: String? = null
    private var announceJob: Job? = null

    fun stopSpeaking() {
        announceJob?.cancel()
        announceJob = null
        runCatching { tts?.stop() }
    }

    fun isExternalHeadsetConnected(context: Context): Boolean {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        val outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return outputs.any { dev ->
            when (dev.type) {
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_USB_HEADSET -> true
                else -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    (dev.type == AudioDeviceInfo.TYPE_BLE_HEADSET || dev.type == AudioDeviceInfo.TYPE_BLE_SPEAKER)
            }
        }
    }

    fun announceNewTicketIfEnabled(
        context: Context,
        ticket: HelpdeskTicket,
        settings: AppSettings,
        delayAfterChimeMs: Long = 2400L
    ) {
        val shouldSpeak = when (settings.ttsReadoutMode) {
            TtsReadoutMode.OFF -> false
            TtsReadoutMode.HEADSET_ONLY -> isExternalHeadsetConnected(context)
            TtsReadoutMode.ALWAYS -> true
        }
        if (!shouldSpeak) return

        val client = ticket.reporterName.ifBlank { "клиента" }.take(45)
        val summary = ticket.summary.replace(Regex("\\s+"), " ").take(85)
        val phrase = "Новая заявка от $client. $summary"

        announceJob?.cancel()
        announceJob = scope.launch {
            if (delayAfterChimeMs > 0L) delay(delayAfterChimeMs)
            speakNow(context.applicationContext, phrase)
        }
    }

    fun announceStormIfEnabled(
        context: Context,
        count: Int,
        settings: AppSettings,
        delayAfterChimeMs: Long = 2400L
    ) {
        val shouldSpeak = when (settings.ttsReadoutMode) {
            TtsReadoutMode.OFF -> false
            TtsReadoutMode.HEADSET_ONLY -> isExternalHeadsetConnected(context)
            TtsReadoutMode.ALWAYS -> true
        }
        if (!shouldSpeak) return

        announceJob?.cancel()
        announceJob = scope.launch {
            if (delayAfterChimeMs > 0L) delay(delayAfterChimeMs)
            speakNow(context.applicationContext, "Внимание, поступило $count новых заявок от клиентов.")
        }
    }

    fun speakPreview(context: Context) {
        announceJob?.cancel()
        announceJob = scope.launch {
            speakNow(
                context.applicationContext,
                "Проверка голосового чтения Help Си Эс Эл. Новая заявка от Сейлзмодерн: проблемы с ноутбуком."
            )
        }
    }

    private fun speakNow(appContext: Context, text: String) {
        val engine = tts
        if (engine != null && ttsReady) {
            enqueueSpeech(engine, text)
            return
        }
        pendingUtterance = text
        if (engine == null) {
            tts = TextToSpeech(appContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val inst = tts ?: return@TextToSpeech
                    val ruLocale = Locale("ru", "RU")
                    val langRes = inst.setLanguage(ruLocale)
                    if (langRes == TextToSpeech.LANG_MISSING_DATA || langRes == TextToSpeech.LANG_NOT_SUPPORTED) {
                        inst.setLanguage(Locale.getDefault())
                    }
                    inst.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    ttsReady = true
                    pendingUtterance?.let { queued ->
                        pendingUtterance = null
                        enqueueSpeech(inst, queued)
                    }
                }
            }
        }
    }

    private fun enqueueSpeech(engine: TextToSpeech, text: String) {
        runCatching {
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "helpcsl_tts_${System.currentTimeMillis()}")
        }
    }
}
