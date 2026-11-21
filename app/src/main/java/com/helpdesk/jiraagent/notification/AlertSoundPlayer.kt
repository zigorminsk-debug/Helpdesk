package com.helpdesk.jiraagent.notification

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
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
import kotlin.math.sin

/**
 * Reliable cross-device alert player for Help-CSL (including Xiaomi MIUI / HyperOS, Samsung, Huawei).
 * When a Bluetooth, wired, or USB headset is connected, plays the alert simultaneously on BOTH
 * the phone's built-in loudspeaker AND the connected headset so an incoming ticket is never missed.
 */
object AlertSoundPlayer {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val playerLock = Any()

    private val activeMediaPlayers = mutableListOf<MediaPlayer>()
    private val activeAudioTracks = mutableListOf<AudioTrack>()

    @Volatile
    private var stopJob: Job? = null

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
        stopJob?.cancel()
        stopJob = null

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

        scope.launch {
            stopCurrent()
            val targets = resolveOutputTargets(audioManager)
            val speakerUsage = chooseSpeakerUsage(audioManager)

            when (settings.alertSoundPreset) {
                AlertSoundPreset.CUSTOM_URI -> {
                    val customUriStr = settings.customAlertSoundUri.trim()
                    val played = if (customUriStr.isNotEmpty()) {
                        playUriDualOutput(
                            context = appContext,
                            uri = Uri.parse(customUriStr),
                            speakerUsage = speakerUsage,
                            targets = targets
                        )
                    } else {
                        false
                    }
                    if (!played) {
                        playSynthesizedPresetDualOutput(
                            preset = AlertSoundPreset.CSL_CHIME,
                            speakerUsage = speakerUsage,
                            targets = targets
                        )
                    }
                }

                AlertSoundPreset.SYSTEM_DEFAULT -> {
                    val sysUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                    val played = if (sysUri != null) {
                        playUriDualOutput(
                            context = appContext,
                            uri = sysUri,
                            speakerUsage = speakerUsage,
                            targets = targets
                        )
                    } else {
                        false
                    }
                    if (!played) {
                        playSynthesizedPresetDualOutput(
                            preset = AlertSoundPreset.CSL_CHIME,
                            speakerUsage = speakerUsage,
                            targets = targets
                        )
                    }
                }

                else -> {
                    playSynthesizedPresetDualOutput(
                        preset = settings.alertSoundPreset,
                        speakerUsage = speakerUsage,
                        targets = targets
                    )
                }
            }
        }
    }

    /**
     * Detects both the phone's built-in speaker and any connected Bluetooth / wired / USB headset.
     */
    private fun resolveOutputTargets(audioManager: AudioManager?): OutputRoutingTargets {
        if (audioManager == null) return OutputRoutingTargets(null, null)
        val outputs = runCatching {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        }.getOrDefault(emptyList())

        val speaker = outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }

        // Priority order: stereo A2DP / BLE / Wired / USB first, SCO last
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

    private fun chooseSpeakerUsage(audioManager: AudioManager?): Int {
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

    /**
     * Plays a ringtone/MP3 URI on the phone speaker AND (if connected) simultaneously in the headset.
     */
    private fun playUriDualOutput(
        context: Context,
        uri: Uri,
        speakerUsage: Int,
        targets: OutputRoutingTargets
    ): Boolean {
        val createdPlayers = mutableListOf<MediaPlayer>()
        return try {
            val dualCapable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                targets.externalHeadset != null &&
                targets.builtInSpeaker != null

            if (dualCapable) {
                // 1. Phone built-in speaker player
                createPreparedMediaPlayer(
                    context = context,
                    uri = uri,
                    usage = speakerUsage,
                    preferredDevice = targets.builtInSpeaker
                )?.let { createdPlayers.add(it) }

                // 2. Connected headset player
                createPreparedMediaPlayer(
                    context = context,
                    uri = uri,
                    usage = AudioAttributes.USAGE_MEDIA,
                    preferredDevice = targets.externalHeadset
                )?.let { createdPlayers.add(it) }
            } else {
                // No external headset connected (or API 26-27): play on speaker/sonification strategy
                createPreparedMediaPlayer(
                    context = context,
                    uri = uri,
                    usage = speakerUsage,
                    preferredDevice = targets.builtInSpeaker
                )?.let { createdPlayers.add(it) }
            }

            if (createdPlayers.isEmpty()) return false

            synchronized(playerLock) {
                activeMediaPlayers.addAll(createdPlayers)
            }
            createdPlayers.forEach { mp ->
                runCatching { mp.start() }
            }

            // Cap long ringtone/MP3 files at 7 seconds so alert finishes cleanly
            stopJob = scope.launch {
                delay(7_000L)
                stopCurrent()
            }
            true
        } catch (_: Exception) {
            createdPlayers.forEach { runCatching { it.release() } }
            false
        }
    }

    private fun createPreparedMediaPlayer(
        context: Context,
        uri: Uri,
        usage: Int,
        preferredDevice: AudioDeviceInfo?
    ): MediaPlayer? {
        return runCatching {
            val contentType = if (usage == AudioAttributes.USAGE_MEDIA) {
                AudioAttributes.CONTENT_TYPE_MUSIC
            } else {
                AudioAttributes.CONTENT_TYPE_SONIFICATION
            }
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
                setOnCompletionListener { player ->
                    runCatching { player.release() }
                    synchronized(playerLock) {
                        activeMediaPlayers.remove(player)
                    }
                }
            }
        }.getOrNull()
    }

    /**
     * Synthesizes a multi-tone PCM chime and plays it simultaneously on BOTH the phone speaker
     * and any connected Bluetooth / wired / USB headset.
     */
    private fun playSynthesizedPresetDualOutput(
        preset: AlertSoundPreset,
        speakerUsage: Int,
        targets: OutputRoutingTargets
    ) {
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
                val combined = ((primaryWave + overtoneWave) / 1.35) * env * 0.92
                pcm[cursor + i] = (combined * Short.MAX_VALUE).toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    .toShort()
            }
            cursor += noteSamples + (sampleRate * step.pauseAfterMs) / 1000
        }

        val createdTracks = mutableListOf<AudioTrack>()
        if (targets.externalHeadset != null && targets.builtInSpeaker != null) {
            // 1. Track pinned to the phone's built-in loudspeaker
            buildStaticTrack(
                pcm = pcm,
                sampleRate = sampleRate,
                usage = speakerUsage,
                preferredDevice = targets.builtInSpeaker
            )?.let { createdTracks.add(it) }

            // 2. Track pinned to the connected Bluetooth / wired / USB headset
            buildStaticTrack(
                pcm = pcm,
                sampleRate = sampleRate,
                usage = AudioAttributes.USAGE_MEDIA,
                preferredDevice = targets.externalHeadset
            )?.let { createdTracks.add(it) }
        } else {
            buildStaticTrack(
                pcm = pcm,
                sampleRate = sampleRate,
                usage = speakerUsage,
                preferredDevice = targets.builtInSpeaker
            )?.let { createdTracks.add(it) }
        }

        if (createdTracks.isEmpty()) return

        synchronized(playerLock) {
            activeAudioTracks.addAll(createdTracks)
        }
        createdTracks.forEach { track ->
            runCatching { track.play() }
        }

        stopJob = scope.launch {
            delay(totalMs.toLong() + 300L)
            synchronized(playerLock) {
                createdTracks.forEach { track ->
                    if (activeAudioTracks.remove(track)) {
                        runCatching { track.stop() }
                        runCatching { track.release() }
                    }
                }
            }
        }
    }

    private fun buildStaticTrack(
        pcm: ShortArray,
        sampleRate: Int,
        usage: Int,
        preferredDevice: AudioDeviceInfo?
    ): AudioTrack? {
        return runCatching {
            val byteSize = pcm.size * 2
            val contentType = if (usage == AudioAttributes.USAGE_MEDIA) {
                AudioAttributes.CONTENT_TYPE_MUSIC
            } else {
                AudioAttributes.CONTENT_TYPE_SONIFICATION
            }
            val track = AudioTrack.Builder()
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

            if (preferredDevice != null) {
                runCatching { track.setPreferredDevice(preferredDevice) }
            }
            track.write(pcm, 0, pcm.size)
            track
        }.getOrNull()
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
