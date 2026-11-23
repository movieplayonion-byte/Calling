package com.wprd.calling.ui.call

import android.os.Bundle
import android.view.View
import com.wprd.calling.data.local.ProfileSyncManager
import com.wprd.calling.databinding.ActivityVoiceCallBinding

class VoiceCallActivity : BaseCallActivity() {

    private lateinit var binding: ActivityVoiceCallBinding
    private var videoUpgraded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVoiceCallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvCallName.text = peerName
        binding.tvCallTimer.text = "Calling…"
        ProfileSyncManager.getLocalProfile(this, peerPhone)?.photoBase64
            ?.let { ProfileSyncManager.base64ToBitmap(it) }
            ?.let { binding.ivCallAvatar.setImageBitmap(it) }

        binding.btnEndCall.setOnClickListener { endCall() }
        binding.btnMute.setOnClickListener {
            val client = webRtcClient ?: return@setOnClickListener
            val muted = client.isMicEnabled
            client.setMicEnabled(!muted)
            audioRoute.setMicMuted(muted)
            binding.btnMute.alpha = if (muted) 0.5f else 1f
            toast(if (muted) "Muted" else "Unmuted")
        }
        binding.btnSpeaker.setOnClickListener {
            val on = audioRoute.toggleSpeaker()
            binding.btnSpeaker.alpha = if (on) 1f else 0.5f
        }
        binding.btnVideoToggle.setOnClickListener {
            if (videoUpgraded) return@setOnClickListener
            videoUpgraded = true
            binding.surfaceVoiceRemoteVideo.visibility = View.VISIBLE
            binding.surfaceVoiceLocalVideo.visibility = View.VISIBLE
            binding.ivCallAvatar.visibility = View.GONE
            upgradeToVideo()
            toast("Video on")
        }
        binding.btnAddCall.setOnClickListener {
            toast("Pehle ye call khatam karke doosri call karein")
        }

        startSession(binding.surfaceVoiceLocalVideo, binding.surfaceVoiceRemoteVideo)
    }

    override fun onStatusText(text: String) { binding.tvCallTimer.text = text }
    override fun onTimerTick(formatted: String) { binding.tvCallTimer.text = formatted }

    override fun onRemoteVideoReady() {
        // The other side turned video on mid-call (voice -> video upgrade
        // initiated by the peer): reveal this side's video surfaces too,
        // otherwise the peer's video renders into a GONE renderer = blank.
        if (!videoUpgraded) {
            binding.surfaceVoiceRemoteVideo.visibility = View.VISIBLE
            binding.surfaceVoiceLocalVideo.visibility = View.VISIBLE
            binding.ivCallAvatar.visibility = View.GONE
        }
    }
}
