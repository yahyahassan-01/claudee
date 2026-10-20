package com.example.phonebridge.server

import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import com.example.phonebridge.audio.Audio
import com.example.phonebridge.audio.PcmPlayer
import com.example.phonebridge.audio.PcmRecorder

class ServerAudioBridge : AudioListener {

    private val lock = Any()
    private var player: PcmPlayer? = null
    private var recorder: PcmRecorder? = null
    @Volatile private var lastClientVoiceAt = 0L

    override fun onCallActive() {
        synchronized(lock) {
            if (player != null) return                   // already running (state can repeat)
            player = PcmPlayer().also { it.start() }     // Client voice -> Server speaker -> call
            // Plain MIC on purpose: an echo canceller would remove the caller's voice
            // (it is just speaker output to the AEC) from what we capture.
            recorder = PcmRecorder(MediaRecorder.AudioSource.MIC, useEffects = false) { frame ->
                val clientTalking = SystemClock.elapsedRealtime() - lastClientVoiceAt < DUCK_MS
                if (!clientTalking) ServerHub.sendAudio(frame)     // duck while the Client speaks
            }.takeIf { it.start() }
            if (recorder == null) Log.e(TAG, "mic capture failed (permission, or blocked during call)")
        }
    }

    override fun onCallEnded() {
        synchronized(lock) {
            recorder?.stop(); recorder = null
            player?.stop(); player = null
        }
    }

    override fun onClientAudio(pcm: ByteArray) {
        if (pcm.size != Audio.FRAME_BYTES) return
        if (Audio.rms(pcm) > CLIENT_VOICE_RMS) lastClientVoiceAt = SystemClock.elapsedRealtime()
        player?.push(pcm)
    }

    private companion object {
        const val TAG = "ServerAudioBridge"
        const val DUCK_MS = 250L          // keep muting the return path this long after the Client speaks
        const val CLIENT_VOICE_RMS = 300  // tune: higher = less sensitive
    }
}
