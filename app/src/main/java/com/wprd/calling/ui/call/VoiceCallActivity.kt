package com.wprd.calling.ui.call

import android.os.Bundle
import com.wprd.calling.data.local.ProfileSyncManager
import com.wprd.calling.data.remote.FirebaseRepository
import com.wprd.calling.databinding.ActivityVideoCallBinding
import com.wprd.calling.databinding.ActivityVoiceCallBinding

class VoiceCallActivity : BaseCallActivity() {

    private lateinit var binding: ActivityVoiceCallBinding
    private var videoBinding: ActivityVideoCallBinding? = null
    private var onVideoScreen = false
    private var lastStatusText = "Calling…"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVoiceCallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvCallName.text = peerName
        binding.tvCallTimer.text = "Calling…"
        // Saamne wale ki photo: local copy pehle, phir RTDB se asal photo.
        ProfileSyncManager.getLocalProfile(this, peerPhone)?.photoBase64
            ?.let { ProfileSyncManager.base64ToBitmap(it) }
            ?.let { binding.ivCallAvatar.setImageBitmap(it) }
        FirebaseRepository().fetchUserPhotoBase64(peerUid) { base64 ->
            val bmp = base64?.let { ProfileSyncManager.base64ToBitmap(it) }
            if (bmp != null && !isFinishing && !onVideoScreen) {
                binding.ivCallAvatar.setImageBitmap(bmp)
            }
        }

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
            // Voice screen ke andar chhoti video surfaces par upgrade karne ke
            // bajaye, seedha asli Video Call screen par le jao — wahi layout,
            // wahi bade controls. Session (peer connection) wahi rehta hai,
            // sirf screen badalti hai.
            switchToVideoScreen(initiatedLocally = true)
            toast("Video on")
        }
        binding.btnAddCall.setOnClickListener {
            toast("Pehle ye call khatam karke doosri call karein")
        }

        startSession(null, null)
    }

    /**
     * Isi activity ke andar content ko Video Call wale layout se badal deta
     * hai. Session BaseCallActivity me hi rehta hai (listener isi instance se
     * jude hain), isliye koi handover jhanjhat nahi — sirf surfaces naye
     * layout ke ho jate hain aur renegotiation ho jati hai.
     *
     * @param initiatedLocally true = humne video tap kiya (naya offer bhejo);
     *        false = saamne wale ne video chalu kiya (offer unki taraf se
     *        aayega, hum sirf screen badalte hain).
     */
    private fun switchToVideoScreen(initiatedLocally: Boolean) {
        if (onVideoScreen) return
        val client = webRtcClient ?: return
        onVideoScreen = true

        val vb = ActivityVideoCallBinding.inflate(layoutInflater)
        videoBinding = vb
        setContentView(vb.root)

        vb.tvVideoPeerName.text = peerName
        vb.tvVideoTimer.text = lastStatusText
        vb.surfaceLocalVideo.setZOrderMediaOverlay(true)

        listOf(vb.surfaceLocalVideo, vb.surfaceRemoteVideo).forEach { renderer ->
            renderer.setEnableHardwareScaler(true)
            renderer.init(client.eglContext(), null)
        }
        vb.surfaceLocalVideo.setMirror(true)
        vb.surfaceRemoteVideo.setMirror(false)

        // Remote track ka sink purane (voice) renderer se hata kar naye par lagao.
        client.swapRemoteRenderer(vb.surfaceRemoteVideo)
        localRenderer = vb.surfaceLocalVideo
        remoteRenderer = vb.surfaceRemoteVideo

        // Video call par awaaz speaker se aati hai (WhatsApp jaisa).
        audioRoute.setSpeaker(true)

        vb.btnVideoEndCall.setOnClickListener { endCall() }
        vb.btnSwitchCamera.setOnClickListener { webRtcClient?.switchCamera() }
        vb.btnVideoMute.setOnClickListener {
            val c = webRtcClient ?: return@setOnClickListener
            val muted = c.isMicEnabled
            c.setMicEnabled(!muted)
            audioRoute.setMicMuted(muted)
            vb.btnVideoMute.alpha = if (muted) 0.5f else 1f
        }
        vb.btnVideoOff.setOnClickListener {
            val c = webRtcClient ?: return@setOnClickListener
            val off = c.isVideoEnabled
            c.setVideoEnabled(!off)
            vb.btnVideoOff.alpha = if (off) 0.5f else 1f
        }
        vb.btnVideoSpeaker.setOnClickListener {
            val on = audioRoute.toggleSpeaker()
            vb.btnVideoSpeaker.alpha = if (on) 1f else 0.5f
        }

        if (initiatedLocally) {
            // Base ki upgradeToVideo() ab naye (video layout ke) renderers
            // use karegi — local camera attach + naya offer.
            upgradeToVideo()
        }
    }

    override fun onStatusText(text: String) {
        lastStatusText = text
        val vb = videoBinding
        if (onVideoScreen && vb != null) vb.tvVideoTimer.text = text
        else binding.tvCallTimer.text = text
    }

    override fun onTimerTick(formatted: String) {
        lastStatusText = formatted
        val vb = videoBinding
        if (onVideoScreen && vb != null) vb.tvVideoTimer.text = formatted
        else binding.tvCallTimer.text = formatted
    }

    override fun onRemoteVideoReady() {
        // Saamne wale ne beech call me video chalu kiya: hum bhi seedha
        // video screen par chale jao, warna unki video kahin dikhegi hi nahi.
        if (!onVideoScreen) switchToVideoScreen(initiatedLocally = false)
    }
}
