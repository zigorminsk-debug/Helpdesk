package com.helpdesk.jiraagent.notification

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.helpdesk.jiraagent.model.AlertSoundPreset
import com.helpdesk.jiraagent.model.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Cross-device alert sound player for Help-CSL, specifically hardened for Xiaomi MIUI / HyperOS.
 *
 * When a Bluetooth, BLE, wired, or USB headset is connected, Xiaomi MIUI/HyperOS suppresses
 * normal alarm/notification/ringtone streams on the built-in speaker. To guarantee audible sound
 * BOTH in the headset AND on the phone's loudspeaker:
 *  1) Plays in the connected headset via A2DP/media routing;
 *  2) Immediately routes to TYPE_BUILTIN_SPEAKER via AudioManager communication device override
 *     (the same hardware path used by the "Speakerphone" button in calls) and restores audio state.
 */
object AlertSoundPlayer {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val playerLock = Any()

    private val activeMediaPlayers = mutableListOf<MediaPlayer>()
    private val activeAudioTracks = mutableListOf<AudioTrack>()

    @Volatile
    private var playJob: Job? = null

    private data class NoteStep(
        val freqHz: Double,
        val secondFreqHz: Double = 0.0,
        val durationMs: Int,
        val pauseAfterMs: Int = 45
    )

    private data class OutputRoutingTargets(
        val builtInSpeaker: AudioDeviceInfo?,
        val externalHeadset: AudioDeviceInfo?
    )

    fun stopCurrent() {
        playJob?.cancel()
        playJob = null
        releaseActivePlayers()
    }

    private fun releaseActivePlayers() {
        synchronized(playerLock) {
            activeMediaPlayers.forEach { mp ->
                runCatching {
                    if (mp.isPlaying) mp.stop()
                }
                runCatching { mp.release() }
            }
            activeMediaPlayers.clear()

            activeAudioTracks.forEach { track ->
                runCatching { track.pause() }
                runCatching { track.flush() }
                runCatching { track.release() }
            }
            activeAudioTracks.clear()
        }
    }

    fun playTicketAlert(
        context: Context,
        settings: AppSettings,
        forcePreview: Boolean = false
    ) {
        val appContext = context.applicationContext
        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val ringerMode = audioManager?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL

        vibrateDevice(appContext)

        if (!forcePreview &&
            (ringerMode == AudioManager.RINGER_MODE_SILENT ||
                ringerMode == AudioManager.RINGER_MODE_VIBRATE)
        ) {
            return
        }

        playJob?.cancel()
        releaseActivePlayers()

        playJob = scope.launch {
            val targets = resolveOutputTargets(audioManager)
            val speakerUsage = chooseNormalSpeakerUsage(audioManager)

            val resolvedUri: Uri? = when (settings.alertSoundPreset) {
                AlertSoundPreset.CUSTOM_URI -> {
                    settings.customAlertSoundUri.trim()
                        .takeIf { it.isNotEmpty() }
                        ?.let { runCatching { Uri.parse(it) }.getOrNull() }
                }
                AlertSoundPreset.SYSTEM_DEFAULT -> {
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                }
                else -> null
            }

            if (targets.externalHeadset != null && audioManager != null) {
                // Headset is connected -> Play in Headset + Force Phone Built-in Loudspeaker!
                if (resolvedUri != null) {
                    val ok = playUriInHeadsetThenForcedSpeaker(
                        context = appContext,
                        audioManager = audioManager,
                        uri = resolvedUri,
                        targets = targets
                    )
                    if (!ok) {
                        playSynthInHeadsetThenForcedSpeaker(
                            audioManager = audioManager,
                            preset = AlertSoundPreset.CSL_CHIME,
                            targets = targets
                        )
                    }
                } else {
                    playSynthInHeadsetThenForcedSpeaker(
                        audioManager = audioManager,
                        preset = settings.alertSoundPreset,
                        targets = targets
                    )
                }
            } else {
                // No headset connected -> Play directly on phone speaker
                if (resolvedUri != null) {
                    val ok = playSingleUri(
                        context = appContext,
                        uri = resolvedUri,
                        usage = speakerUsage,
                        contentType = AudioAttributes.CONTENT_TYPE_SONIFICATION,
                        preferredDevice = targets.builtInSpeaker,
                        maxDurationMs = 7_000L
                    )
                    if (!ok) {
                        val (pcm, totalMs) = synthesizePresetPcm(AlertSoundPreset.CSL_CHIME)
                        playSinglePcmTrack(
                            pcm = pcm,
                            totalMs = totalMs,
                            usage = speakerUsage,
                            contentType = AudioAttributes.CONTENT_TYPE_SONIFICATION,
                            preferredDevice = targets.builtInSpeaker
                        )
                    }
                } else {
                    val (pcm, totalMs) = synthesizePresetPcm(settings.alertSoundPreset)
                    playSinglePcmTrack(
                        pcm = pcm,
                        totalMs = totalMs,
                        usage = speakerUsage,
                        contentType = AudioAttributes.CONTENT_TYPE_SONIFICATION,
                        preferredDevice = targets.builtInSpeaker
                    )
                }
            }
        }
    }

    /**
     * Plays a synthesized melody first in the connected headset (A2DP/wired) and immediately
     * afterwards on the phone's built-in loudspeaker using hardware speakerphone routing.
     */
    private suspend fun playSynthInHeadsetThenForcedSpeaker(
        audioManager: AudioManager,
        preset: AlertSoundPreset,
        targets: OutputRoutingTargets
    ) {
        val (pcm, totalMs) = synthesizePresetPcm(preset)

        // 1. Play clearly in the connected headset (normal A2DP / wired mode)
        playSinglePcmTrack(
            pcm = pcm,
            totalMs = totalMs,
            usage = AudioAttributes.USAGE_MEDIA,
            contentType = AudioAttributes.CONTENT_TYPE_MUSIC,
            preferredDevice = targets.externalHeadset
        )

        // 2. Force hardware speakerphone route on the phone and play out of the built-in loudspeaker
        withForcedPhoneLoudspeaker(audioManager, targets.builtInSpeaker) { commSpeaker ->
            playSinglePcmTrack(
                pcm = pcm,
                totalMs = totalMs,
                usage = AudioAttributes.USAGE_VOICE_COMMUNICATION,
                contentType = AudioAttributes.CONTENT_TYPE_SPEECH,
                preferredDevice = commSpeaker ?: targets.builtInSpeaker
            )
        }
    }

    /**
     * Plays a ringtone/MP3 URI first in the connected headset and immediately afterwards
     * on the phone's built-in loudspeaker using hardware speakerphone routing.
     */
    private suspend fun playUriInHeadsetThenForcedSpeaker(
        context: Context,
        audioManager: AudioManager,
        uri: Uri,
        targets: OutputRoutingTargets
    ): Boolean {
        // 1. Play in the connected headset (up to 3.5s)
        val okHeadset = playSingleUri(
            context = context,
            uri = uri,
            usage = AudioAttributes.USAGE_MEDIA,
            contentType = AudioAttributes.CONTENT_TYPE_MUSIC,
            preferredDevice = targets.externalHeadset,
            maxDurationMs = 3_500L
        )
        if (!okHeadset) return false

        // 2. Play on the phone's built-in loudspeaker (up to 3.5s)
        withForcedPhoneLoudspeaker(audioManager, targets.builtInSpeaker) { commSpeaker ->
            playSingleUri(
                context = context,
                uri = uri,
                usage = AudioAttributes.USAGE_VOICE_COMMUNICATION,
                contentType = AudioAttributes.CONTENT_TYPE_SPEECH,
                preferredDevice = commSpeaker ?: targets.builtInSpeaker,
                maxDurationMs = 3_500L
            )
        }
        return true
    }

    /**
     * Temporarily switches Android / Xiaomi HyperOS audio routing to the built-in speakerphone
     * (bypassing Bluetooth A2DP speaker suppression) and cleanly restores all state in finally.
     */
    private suspend fun withForcedPhoneLoudspeaker(
        audioManager: AudioManager,
        fallbackSpeaker: AudioDeviceInfo?,
        block: suspend (AudioDeviceInfo?) -> Unit
    ) {
        val prevMode = audioManager.mode
        // Never interrupt an active GSM/VoIP call
        if (prevMode == AudioManager.MODE_IN_CALL || prevMode == AudioManager.MODE_RINGTONE) {
            return
        }

        @Suppress("DEPRECATION")
        val prevSpeakerOn = audioManager.isSpeakerphoneOn
        val prevCallVol = runCatching {
            audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
        }.getOrDefault(0)
        val maxCallVol = runCatching {
            audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
        }.getOrDefault(7).coerceAtLeast(1)

        val focusAttrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(focusAttrs)
            .setOnAudioFocusChangeListener { }
            .build()

        var volumeAdjusted = false
        try {
            runCatching { audioManager.requestAudioFocus(focusRequest) }
            runCatching {
                @Suppress("DEPRECATION")
                audioManager.stopBluetoothSco()
                @Suppress("DEPRECATION")
                audioManager.isBluetoothScoOn = false
            }

            runCatching { audioManager.mode = AudioManager.MODE_IN_COMMUNICATION }

            var commSpeaker: AudioDeviceInfo? = null
            var routedViaCommDevice = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                commSpeaker = runCatching {
                    audioManager.availableCommunicationDevices.firstOrNull {
                        it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                    }
                }.getOrNull()
                if (commSpeaker != null) {
                    routedViaCommDevice = runCatching {
                        audioManager.setCommunicationDevice(commSpeaker)
                    }.getOrDefault(false)
                }
            }
            if (!routedViaCommDevice) {
                @Suppress("DEPRECATION")
                runCatching { audioManager.isSpeakerphoneOn = true }
            }

            // Ensure loudspeaker voice-call stream volume is clearly audible
            val targetCallVol = computeDesiredSpeakerCallVolume(audioManager, maxCallVol)
            if (targetCallVol > prevCallVol) {
                runCatching {
                    audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, targetCallVol, 0)
                    volumeAdjusted = true
                }
            }

            // Give the hardware speaker amplifier 150ms to open before the first note
            delay(150L)

            block(commSpeaker ?: fallbackSpeaker)
        } finally {
            releaseActivePlayers()
            if (volumeAdjusted) {
                runCatching {
                    audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, prevCallVol, 0)
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                runCatching { audioManager.clearCommunicationDevice() }
            }
            @Suppress("DEPRECATION")
            runCatching { audioManager.isSpeakerphoneOn = prevSpeakerOn }
            runCatching { audioManager.mode = prevMode }
            runCatching { audioManager.abandonAudioFocusRequest(focusRequest) }
        }
    }

    private fun computeDesiredSpeakerCallVolume(audioManager: AudioManager, maxCallVol: Int): Int {
        val ringMax = audioManager.getStreamMaxVolume(AudioManager.STREAM_RING).coerceAtLeast(1)
        val ringVol = audioManager.getStreamVolume(AudioManager.STREAM_RING)
        val alarmMax = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM).coerceAtLeast(1)
        val alarmVol = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)

        val ratio = maxOf(
            ringVol.toDouble() / ringMax.toDouble(),
            alarmVol.toDouble() / alarmMax.toDouble(),
            0.85
        ).coerceIn(0.75, 1.0)

        return (maxCallVol * ratio).roundToInt().coerceIn(1, maxCallVol)
    }

    private suspend fun playSinglePcmTrack(
        pcm: ShortArray,
        totalMs: Int,
        usage: Int,
        contentType: Int,
        preferredDevice: AudioDeviceInfo?
    ) {
        val sampleRate = 44_100
        val track = runCatching {
            val byteSize = pcm.size * 2
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(usage)
                        .setContentType(contentType)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(byteSize)
                .build()
                .also { built ->
                    if (preferredDevice != null) {
                        runCatching { built.setPreferredDevice(preferredDevice) }
                    }
                    built.write(pcm, 0, pcm.size)
                }
        }.getOrNull() ?: return

        synchronized(playerLock) {
            activeAudioTracks.add(track)
        }
        try {
            runCatching { track.play() }
            delay(totalMs.toLong() + 180L)
        } finally {
            synchronized(playerLock) {
                activeAudioTracks.remove(track)
            }
            runCatching { track.stop() }
            runCatching { track.release() }
        }
    }

    private suspend fun playSingleUri(
        context: Context,
        uri: Uri,
        usage: Int,
        contentType: Int,
        preferredDevice: AudioDeviceInfo?,
        maxDurationMs: Long
    ): Boolean {
        val mp = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(usage)
                        .setContentType(contentType)
                        .build()
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && preferredDevice != null) {
                    runCatching { setPreferredDevice(preferredDevice) }
                }
                setDataSource(context, uri)
                isLooping = false
                prepare()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && preferredDevice != null) {
                    runCatching { setPreferredDevice(preferredDevice) }
                }
            }
        }.getOrNull() ?: return false

        synchronized(playerLock) {
            activeMediaPlayers.add(mp)
        }
        try {
            val clipDuration = runCatching { mp.duration.toLong() }.getOrDefault(maxDurationMs)
            val waitMs = if (clipDuration > 150L) {
                minOf(clipDuration + 150L, maxDurationMs)
            } else {
                maxDurationMs
            }
            mp.start()
            delay(waitMs)
            return true
        } catch (_: Exception) {
            return false
        } finally {
            synchronized(playerLock) {
                activeMediaPlayers.remove(mp)
            }
            runCatching {
                if (mp.isPlaying) mp.stop()
            }
            runCatching { mp.release() }
        }
    }

    private fun resolveOutputTargets(audioManager: AudioManager?): OutputRoutingTargets {
        if (audioManager == null) return OutputRoutingTargets(null, null)
        val outputs = runCatching {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        }.getOrDefault(emptyList())

        val speaker = outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }

        val preferredHeadsetTypes = buildList {
            add(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(AudioDeviceInfo.TYPE_BLE_HEADSET)
                add(AudioDeviceInfo.TYPE_BLE_SPEAKER)
            }
            add(AudioDeviceInfo.TYPE_WIRED_HEADSET)
            add(AudioDeviceInfo.TYPE_WIRED_HEADPHONES)
            add(AudioDeviceInfo.TYPE_USB_HEADSET)
            add(AudioDeviceInfo.TYPE_USB_DEVICE)
            add(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
        }

        var headset: AudioDeviceInfo? = null
        for (targetType in preferredHeadsetTypes) {
            val match = outputs.firstOrNull { it.type == targetType }
            if (match != null) {
                headset = match
                break
            }
        }

        return OutputRoutingTargets(
            builtInSpeaker = speaker,
            externalHeadset = headset
        )
    }

    private fun chooseNormalSpeakerUsage(audioManager: AudioManager?): Int {
        if (audioManager == null) return AudioAttributes.USAGE_ALARM
        val alarmVol = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
        val ringVol = audioManager.getStreamVolume(AudioManager.STREAM_RING)
        val notifVol = audioManager.getStreamVolume(AudioManager.STREAM_NOTIFICATION)
        return when {
            alarmVol > 0 -> AudioAttributes.USAGE_ALARM
            ringVol > 0 -> AudioAttributes.USAGE_NOTIFICATION_RINGTONE
            notifVol > 0 -> AudioAttributes.USAGE_NOTIFICATION_EVENT
            else -> AudioAttributes.USAGE_ALARM
        }
    }

    private fun synthesizePresetPcm(preset: AlertSoundPreset): Pair<ShortArray, Int> {
        val sequence = when (preset) {
            AlertSoundPreset.CSL_CHIME -> listOf(
                NoteStep(freqHz = 783.99, secondFreqHz = 1567.98, durationMs = 150, pauseAfterMs = 45),
                NoteStep(freqHz = 987.77, secondFreqHz = 1975.53, durationMs = 150, pauseAfterMs = 45),
                NoteStep(freqHz = 1174.66, secondFreqHz = 2349.32, durationMs = 280, pauseAfterMs = 90),
                NoteStep(freqHz = 1567.98, secondFreqHz = 783.99, durationMs = 360, pauseAfterMs = 0)
            )

            AlertSoundPreset.DISPATCH_PULSE -> listOf(
                NoteStep(freqHz = 1046.50, secondFreqHz = 1318.51, durationMs = 190, pauseAfterMs = 70),
                NoteStep(freqHz = 1046.50, secondFreqHz = 1318.51, durationMs = 190, pauseAfterMs = 140),
                NoteStep(freqHz = 1318.51, secondFreqHz = 1567.98, durationMs = 320, pauseAfterMs = 0)
            )

            AlertSoundPreset.HARMONIC_CHORD -> listOf(
                NoteStep(freqHz = 523.25, secondFreqHz = 659.25, durationMs = 180, pauseAfterMs = 35),
                NoteStep(freqHz = 659.25, secondFreqHz = 783.99, durationMs = 180, pauseAfterMs = 35),
                NoteStep(freqHz = 783.99, secondFreqHz = 1046.50, durationMs = 420, pauseAfterMs = 0)
            )

            AlertSoundPreset.URGENT_ALARM -> listOf(
                NoteStep(freqHz = 880.0, secondFreqHz = 1760.0, durationMs = 140, pauseAfterMs = 40),
                NoteStep(freqHz = 1174.66, secondFreqHz = 2349.32, durationMs = 140, pauseAfterMs = 40),
                NoteStep(freqHz = 880.0, secondFreqHz = 1760.0, durationMs = 140, pauseAfterMs = 40),
                NoteStep(freqHz = 1174.66, secondFreqHz = 2349.32, durationMs = 140, pauseAfterMs = 40),
                NoteStep(freqHz = 1396.91, secondFreqHz = 2093.0, durationMs = 340, pauseAfterMs = 0)
            )

            else -> listOf(
                NoteStep(freqHz = 880.0, durationMs = 180, pauseAfterMs = 50),
                NoteStep(freqHz = 1174.66, durationMs = 280, pauseAfterMs = 0)
            )
        }

        val sampleRate = 44_100
        val totalMs = sequence.sumOf { it.durationMs + it.pauseAfterMs }.coerceAtLeast(100)
        val totalSamples = (sampleRate * totalMs) / 1000
        val pcm = ShortArray(totalSamples)

        var cursor = 0
        for (step in sequence) {
            val noteSamples = (sampleRate * step.durationMs) / 1000
            val attackSamples = (sampleRate * 8) / 1000
            for (i in 0 until noteSamples) {
                if (cursor + i >= pcm.size) break
                val t = i.toDouble() / sampleRate
                val attackEnv = if (i < attackSamples) i.toDouble() / attackSamples else 1.0
                val decayEnv = exp(-2.4 * (i.toDouble() / noteSamples))
                val env = (attackEnv * decayEnv).coerceIn(0.0, 1.0)

                val primaryWave = sin(2.0 * PI * step.freqHz * t)
                val overtoneWave = if (step.secondFreqHz > 0.0) {
                    0.35 * sin(2.0 * PI * step.secondFreqHz * t)
                } else {
                    0.0
                }
                val combined = ((primaryWave + overtoneWave) / 1.35) * env * 0.95
                pcm[cursor + i] = (combined * Short.MAX_VALUE).toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    .toShort()
            }
            cursor += noteSamples + (sampleRate * step.pauseAfterMs) / 1000
        }

        return pcm to totalMs
    }

    private fun vibrateDevice(context: Context) {
        runCatching {
            val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (vibrator?.hasVibrator() == true) {
                vibrator.vibrate(
                    VibrationEffect.createWaveform(longArrayOf(0L, 260L, 120L, 320L), -1)
                )
            }
        }
    }
}
