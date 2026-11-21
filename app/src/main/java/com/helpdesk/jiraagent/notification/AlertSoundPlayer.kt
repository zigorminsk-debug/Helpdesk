package com.helpdesk.jiraagent.notification

import android.content.Context
import android.media.AudioAttributes
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
 * Plays synthesized multi-tone melodies, system ringtones, or user-selected MP3/audio files,
 * paired with tactile vibration.
 */
object AlertSoundPlayer {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var activeMediaPlayer: MediaPlayer? = null

    @Volatile
    private var activeAudioTrack: AudioTrack? = null

    @Volatile
    private var stopJob: Job? = null

    private data class NoteStep(
        val freqHz: Double,
        val secondFreqHz: Double = 0.0,
        val durationMs: Int,
        val pauseAfterMs: Int = 45
    )

    fun stopCurrent() {
        stopJob?.cancel()
        stopJob = null
        runCatching {
            activeMediaPlayer?.let { mp ->
                if (mp.isPlaying) mp.stop()
                mp.release()
            }
        }
        activeMediaPlayer = null

        runCatching {
            activeAudioTrack?.let { track ->
                track.pause()
                track.flush()
                track.release()
            }
        }
        activeAudioTrack = null
    }

    fun playTicketAlert(
        context: Context,
        settings: AppSettings,
        forcePreview: Boolean = false
    ) {
        val appContext = context.applicationContext
        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val ringerMode = audioManager?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL

        if (!forcePreview) {
            vibrateDevice(appContext)
            if (ringerMode == AudioManager.RINGER_MODE_SILENT ||
                ringerMode == AudioManager.RINGER_MODE_VIBRATE
            ) {
                return
            }
        } else {
            vibrateDevice(appContext)
        }

        scope.launch {
            stopCurrent()
            val usage = chooseAudibleUsage(audioManager, forcePreview)

            when (settings.alertSoundPreset) {
                AlertSoundPreset.CUSTOM_URI -> {
                    val customUriStr = settings.customAlertSoundUri.trim()
                    val played = if (customUriStr.isNotEmpty()) {
                        playUriWithMediaPlayer(appContext, Uri.parse(customUriStr), usage)
                    } else {
                        false
                    }
                    if (!played) {
                        playSynthesizedPreset(AlertSoundPreset.CSL_CHIME, usage)
                    }
                }

                AlertSoundPreset.SYSTEM_DEFAULT -> {
                    val sysUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                    val played = if (sysUri != null) {
                        playUriWithMediaPlayer(appContext, sysUri, usage)
                    } else {
                        false
                    }
                    if (!played) {
                        playSynthesizedPreset(AlertSoundPreset.CSL_CHIME, usage)
                    }
                }

                else -> {
                    playSynthesizedPreset(settings.alertSoundPreset, usage)
                }
            }
        }
    }

    private fun chooseAudibleUsage(audioManager: AudioManager?, forcePreview: Boolean): Int {
        if (forcePreview) return AudioAttributes.USAGE_MEDIA
        if (audioManager == null) return AudioAttributes.USAGE_NOTIFICATION_RINGTONE

        val notifVol = audioManager.getStreamVolume(AudioManager.STREAM_NOTIFICATION)
        val ringVol = audioManager.getStreamVolume(AudioManager.STREAM_RING)
        return when {
            notifVol > 0 -> AudioAttributes.USAGE_NOTIFICATION_EVENT
            ringVol > 0 -> AudioAttributes.USAGE_NOTIFICATION_RINGTONE
            else -> AudioAttributes.USAGE_ALARM
        }
    }

    private fun playUriWithMediaPlayer(context: Context, uri: Uri, usage: Int): Boolean {
        return try {
            val mp = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(usage)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(context, uri)
                isLooping = false
                prepare()
                setOnCompletionListener { player ->
                    runCatching { player.release() }
                    if (activeMediaPlayer === player) activeMediaPlayer = null
                }
                start()
            }
            activeMediaPlayer = mp
            // Cap long ringtone/MP3 files at 7 seconds so alert finishes cleanly
            stopJob = scope.launch {
                delay(7_000L)
                if (activeMediaPlayer === mp) {
                    stopCurrent()
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun playSynthesizedPreset(preset: AlertSoundPreset, usage: Int) {
        val sequence = when (preset) {
            AlertSoundPreset.CSL_CHIME -> listOf(
                NoteStep(freqHz = 783.99, secondFreqHz = 1567.98, durationMs = 150, pauseAfterMs = 45), // G5
                NoteStep(freqHz = 987.77, secondFreqHz = 1975.53, durationMs = 150, pauseAfterMs = 45), // B5
                NoteStep(freqHz = 1174.66, secondFreqHz = 2349.32, durationMs = 280, pauseAfterMs = 90), // D6
                NoteStep(freqHz = 1567.98, secondFreqHz = 783.99, durationMs = 360, pauseAfterMs = 0)    // G6
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

        runCatching {
            val byteSize = pcm.size * 2
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(usage)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
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

            track.write(pcm, 0, pcm.size)
            activeAudioTrack = track
            track.play()

            stopJob = scope.launch {
                delay(totalMs.toLong() + 250L)
                if (activeAudioTrack === track) {
                    runCatching {
                        track.stop()
                        track.release()
                    }
                    activeAudioTrack = null
                }
            }
        }
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
