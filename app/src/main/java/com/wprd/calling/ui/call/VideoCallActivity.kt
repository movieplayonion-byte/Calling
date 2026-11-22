package com.wprd.calling.ui.call

import android.os.Bundle
import com.wprd.calling.databinding.ActivityVideoCallBinding

class VideoCallActivity : BaseCallActivity() {

    private lateinit var binding: ActivityVideoCallBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVideoCallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvVideoPeerName.text = peerName
        binding.tvVideoTimer.text = "Calling…"
        binding.surfaceLocalVideo.setZOrderMediaOverlay(true)

        binding.btnVideoEndCall.setOnClickListener { endCall() }
        binding.btnSwitchCamera.setOnClickListener { webRtcClient?.switchCamera() }
        binding.btnVideoMute.setOnClickListener {
            val client = webRtcClient ?: return@setOnClickListener
            val muted = client.isMicEnabled
            client.setMicEnabled(!muted)
            audioRoute.setMicMuted(muted)
            binding.btnVideoMute.alpha = if (muted) 0.5f else 1f
        }
        binding.btnVideoOff.setOnClickListener {
            val client = webRtcClient ?: return@setOnClickListener
            val off = client.isVideoEnabled
            client.setVideoEnabled(!off)
            binding.btnVideoOff.alpha = if (off) 0.5f else 1f
        }
        binding.btnVideoSpeaker.setOnClickListener {
            val on = audioRoute.toggleSpeaker()
            binding.btnVideoSpeaker.alpha = if (on) 1f else 0.5f
        }

        startSession(binding.surfaceLocalVideo, binding.surfaceRemoteVideo)
    }

    override fun onStatusText(text: String) { binding.tvVideoTimer.text = text }
    override fun onTimerTick(formatted: String) { binding.tvVideoTimer.text = formatted }
}
