package com.wprd.calling.webrtc

import android.content.Context
import android.media.AudioManager

/** Earpiece / speaker / bluetooth routing + mic mute via AudioManager. */
class AudioRouteManager(context: Context) {

    private val audioManager =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var previousMode = AudioManager.MODE_NORMAL
    private var started = false

    var isSpeakerOn = false
        private set

    fun start(forVideo: Boolean) {
        if (started) return
        started = true
        previousMode = audioManager.mode
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        // Video calls default to speaker, voice calls to earpiece (WhatsApp-like).
        setSpeaker(forVideo)
    }

    fun setSpeaker(on: Boolean) {
        isSpeakerOn = on
        @Suppress("DEPRECATION")
        audioManager.isSpeakerphoneOn = on
    }

    fun toggleSpeaker(): Boolean {
        setSpeaker(!isSpeakerOn)
        return isSpeakerOn
    }

    fun setMicMuted(muted: Boolean) {
        audioManager.isMicrophoneMute = muted
    }

    fun isBluetoothConnected(): Boolean {
        @Suppress("DEPRECATION")
        return audioManager.isBluetoothScoOn || audioManager.isBluetoothA2dpOn
    }

    fun stop() {
        if (!started) return
        started = false
        audioManager.isMicrophoneMute = false
        @Suppress("DEPRECATION")
        audioManager.isSpeakerphoneOn = false
        audioManager.mode = previousMode
    }
}
