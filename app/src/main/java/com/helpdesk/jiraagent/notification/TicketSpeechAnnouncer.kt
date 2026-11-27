package com.helpdesk.jiraagent.notification

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.helpdesk.jiraagent.domain.CallSpeechGuard
import com.helpdesk.jiraagent.model.AppSettings
import com.helpdesk.jiraagent.model.HelpdeskTicket
import com.helpdesk.jiraagent.model.TtsReadoutMode
import com.helpdesk.jiraagent.model.ZabbixProblem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID

object TicketSpeechAnnouncer {
    private data class SpeechRequest(val text: String, val protectCalls: Boolean)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingUtterance: SpeechRequest? = null
    private var announceJob: Job? = null
    private var idleShutdownJob: Job? = null
    private var jiraAlarmJob: Job? = null
    private var ticketGuardJob: Job? = null
    private var activeTicketId: String? = null

    fun stopSpeaking() = scope.launch {
        announceJob?.cancel()
        announceJob = null
        pendingUtterance = null
        activeTicketId = null
        ticketGuardJob?.cancel()
        ticketGuardJob = null
        idleShutdownJob?.cancel()
        runCatching { tts?.stop() }
        scheduleIdleShutdown()
    }

    private fun scheduleIdleShutdown() {
        idleShutdownJob?.cancel()
        idleShutdownJob = scope.launch {
            delay(60_000L)
            val inst = tts
            tts = null
            ttsReady = false
            pendingUtterance = null
            activeTicketId = null
            ticketGuardJob?.cancel()
            ticketGuardJob = null
            runCatching { inst?.stop() }
            runCatching { inst?.shutdown() }
        }
    }

    fun isExternalHeadsetConnected(context: Context): Boolean {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { dev ->
            when (dev.type) {
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_USB_HEADSET -> true
                else -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    (dev.type == AudioDeviceInfo.TYPE_BLE_HEADSET || dev.type == AudioDeviceInfo.TYPE_BLE_SPEAKER)
            }
        }
    }

    private fun enabled(context: Context, settings: AppSettings): Boolean = when (settings.ttsReadoutMode) {
        TtsReadoutMode.OFF -> false
        TtsReadoutMode.HEADSET_ONLY -> isExternalHeadsetConnected(context)
        TtsReadoutMode.ALWAYS -> true
    }

    private fun scheduleAnnouncement(context: Context, request: SpeechRequest, delayMs: Long) {
        val appContext = context.applicationContext
        // Capture call state at arrival, not only after the main thread schedules the task.
        if (request.protectCalls && CallActivityDetector.isCallActive(appContext)) return
        scope.launch {
            announceJob?.cancel()
            announceJob = scope.launch {
                if (request.protectCalls) {
                    if (!CallSpeechGuard.awaitSafeDelay(delayMs, { CallActivityDetector.isCallActive(appContext) })) return@launch
                } else if (delayMs > 0L) delay(delayMs)
                speakNow(appContext, request)
            }
        }
    }

    fun announceZabbixAlertIfEnabled(context: Context, problem: ZabbixProblem, settings: AppSettings, delayAfterChimeMs: Long = 2400L) {
        if (!enabled(context, settings)) return
        val host = problem.effectiveHostLabel.take(40)
        val summary = problem.name.replace(Regex("\\s+"), " ").take(80)
        scheduleAnnouncement(context, SpeechRequest("Внимание, Заббикс. Узел $host. $summary", false), delayAfterChimeMs)
    }

    fun announceNewTicketIfEnabled(context: Context, ticket: HelpdeskTicket, settings: AppSettings, delayAfterChimeMs: Long = 2400L) {
        if (!enabled(context, settings)) return
        val client = ticket.reporterName.ifBlank { "клиента" }.take(45)
        val summary = ticket.summary.replace(Regex("\\s+"), " ").take(85)
        scheduleAnnouncement(context, SpeechRequest("Новая заявка от $client. $summary", true), delayAfterChimeMs)
    }

    fun announceStormIfEnabled(context: Context, count: Int, settings: AppSettings, delayAfterChimeMs: Long = 2400L) {
        if (!enabled(context, settings)) return
        scheduleAnnouncement(context, SpeechRequest("Внимание, поступило $count новых заявок от клиентов.", true), delayAfterChimeMs)
    }

    /** Independent of ticket readout settings, but respects the device's silent/vibrate mode. */
    fun announceJiraConnectionAlarm(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            val audio = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (audio != null && audio.ringerMode != AudioManager.RINGER_MODE_NORMAL) return@launch
            jiraAlarmJob?.cancel()
            jiraAlarmJob = scope.launch {
                delay(2_500L)
                speakNow(appContext, SpeechRequest(com.helpdesk.jiraagent.domain.JiraConnectionAlarm.SPEECH, false))
            }
        }
    }

    fun cancelJiraConnectionAlarm() {
        scope.launch {
            jiraAlarmJob?.cancel()
            jiraAlarmJob = null
            if (pendingUtterance?.text == com.helpdesk.jiraagent.domain.JiraConnectionAlarm.SPEECH) pendingUtterance = null
        }
    }

    fun speakPreview(context: Context) {
        scheduleAnnouncement(context, SpeechRequest("Проверка голосового чтения Help Си Эс Эл. Новая заявка от Сейлзмодерн: проблемы с ноутбуком.", true), 0)
    }

    // All TTS state is accessed on the main dispatcher, including TTS callbacks.
    private fun speakNow(appContext: Context, request: SpeechRequest) {
        if (request.protectCalls && CallActivityDetector.isCallActive(appContext)) return
        ticketGuardJob?.cancel()
        ticketGuardJob = null
        activeTicketId = null
        pendingUtterance = request
        if (request.protectCalls) {
            // Covers delayed engine initialization and the entire spoken utterance. Never replay it.
            ticketGuardJob = scope.launch {
                while (isActive) {
                    if (CallActivityDetector.isCallActive(appContext)) {
                        if (pendingUtterance?.protectCalls == true) pendingUtterance = null
                        if (activeTicketId != null) runCatching { tts?.stop() }
                        activeTicketId = null
                        break
                    }
                    delay(200L)
                }
            }
        }
        scheduleIdleShutdown()
        val engine = tts
        if (engine != null && ttsReady) {
            pendingUtterance = null
            enqueueSpeech(appContext, engine, request)
            return
        }
        if (engine == null) {
            tts = TextToSpeech(appContext) { status ->
                scope.launch {
                    val inst = tts ?: return@launch
                    if (status != TextToSpeech.SUCCESS) {
                        pendingUtterance = null
                        ticketGuardJob?.cancel()
                        ticketGuardJob = null
                        tts = null
                        ttsReady = false
                        runCatching { inst.shutdown() }
                        return@launch
                    }
                    val result = inst.setLanguage(Locale("ru", "RU"))
                    if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) inst.setLanguage(Locale.getDefault())
                    inst.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    inst.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String) = Unit
                        override fun onDone(utteranceId: String) { finishTicket(utteranceId) }
                        @Deprecated("Deprecated in Android") override fun onError(utteranceId: String) { finishTicket(utteranceId) }
                    })
                    ttsReady = true
                    val queued = pendingUtterance
                    pendingUtterance = null
                    if (queued != null) enqueueSpeech(appContext, inst, queued)
                }
            }
        }
    }

    private fun finishTicket(id: String) {
        scope.launch {
            if (activeTicketId == id) {
                activeTicketId = null
                ticketGuardJob?.cancel()
                ticketGuardJob = null
            }
        }
    }

    private fun enqueueSpeech(context: Context, engine: TextToSpeech, request: SpeechRequest) {
        if (request.protectCalls && CallActivityDetector.isCallActive(context)) {
            ticketGuardJob?.cancel()
            ticketGuardJob = null
            activeTicketId = null
            return
        }
        val id = "helpcsl_tts_${UUID.randomUUID()}"
        activeTicketId = if (request.protectCalls) id else null
        val result = runCatching { engine.speak(request.text, TextToSpeech.QUEUE_FLUSH, null, id) }.getOrDefault(TextToSpeech.ERROR)
        if (result == TextToSpeech.ERROR) finishTicket(id)
        scheduleIdleShutdown()
    }
}
