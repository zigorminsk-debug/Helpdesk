package com.helpdesk.jiraagent.notification

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.helpdesk.jiraagent.domain.CallSignals
import com.helpdesk.jiraagent.domain.CallSpeechGuard

/** Best-effort system call signals only. Does not read phone numbers, contacts, or audio. */
object CallActivityDetector {
    fun isCallActive(context: Context): Boolean {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val mode = runCatching { audio?.mode }.getOrNull()
        val inCommunication = mode == AudioManager.MODE_RINGTONE || mode == AudioManager.MODE_IN_CALL ||
            mode == AudioManager.MODE_IN_COMMUNICATION || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && mode == AudioManager.MODE_CALL_SCREENING)
        val phoneBusy = if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
            runCatching {
                @Suppress("DEPRECATION")
                val state = (context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager)?.callState
                state == TelephonyManager.CALL_STATE_RINGING || state == TelephonyManager.CALL_STATE_OFFHOOK
            }.getOrDefault(false)
        } else false
        // Some messengers ring without changing telephony state or audio mode.
        val callPlayback = runCatching {
            audio?.activePlaybackConfigurations?.any { config ->
                when (config.audioAttributes.usage) {
                    AudioAttributes.USAGE_NOTIFICATION_RINGTONE,
                    AudioAttributes.USAGE_VOICE_COMMUNICATION,
                    AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING -> true
                    else -> false
                }
            } == true
        }.getOrDefault(false)
        val callRecording = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) runCatching {
            audio?.activeRecordingConfigurations?.any { config ->
                config.clientAudioSource in setOf(MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    MediaRecorder.AudioSource.VOICE_CALL, MediaRecorder.AudioSource.VOICE_UPLINK, MediaRecorder.AudioSource.VOICE_DOWNLINK)
            } == true
        }.getOrDefault(false) else false
        return CallSpeechGuard.shouldSuppress(CallSignals(phoneBusy, inCommunication, callPlayback || callRecording))
    }
}
