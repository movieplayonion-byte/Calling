package com.wprd.calling.ui.call

import android.content.Context
import android.content.Intent
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.wprd.calling.data.local.AppDatabase
import com.wprd.calling.data.local.ProfileSyncManager
import com.wprd.calling.data.model.CallRecord
import com.wprd.calling.databinding.ActivityIncomingCallBinding
import com.wprd.calling.webrtc.SignalingClient
import kotlinx.coroutines.launch

class IncomingCallActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CALL_ID = "extra_call_id"
        const val EXTRA_CALLER_NAME = "extra_caller_name"
        const val EXTRA_CALLER_PHONE = "extra_caller_phone"
        const val EXTRA_CALL_TYPE = "extra_call_type"
    }

    private lateinit var binding: ActivityIncomingCallBinding
    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null
    private var answered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityIncomingCallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val callId = intent.getStringExtra(EXTRA_CALL_ID) ?: run { finish(); return }
        val callerName = intent.getStringExtra(EXTRA_CALLER_NAME) ?: "Unknown"
        val callerPhone = intent.getStringExtra(EXTRA_CALLER_PHONE) ?: ""
        val callType = intent.getStringExtra(EXTRA_CALL_TYPE) ?: "VOICE"
        val callerUid = ProfileSyncManager.normalizePhoneKey(callerPhone)

        binding.tvIncomingName.text = callerName
        binding.tvIncomingPhone.text = callerPhone
        binding.tvIncomingStatus.text =
            if (callType == "VIDEO") "Incoming Video Call" else "Incoming Voice Call"
        if (callType == "VIDEO") {
            // Mockup: green button carries a video glyph, labelled "Answer Video".
            binding.btnAnswerCall.setImageResource(com.wprd.calling.R.drawable.ic_video_white)
            binding.tvAnswerLabel.text = "Answer Video"
        }
        // Saamne wale ki asal profile photo: pehle local copy (agar ho), phir
        // RTDB se uski asal photo — sirf apne account ki photo par nirbhar nahi.
        ProfileSyncManager.getLocalProfile(this, callerPhone)?.photoBase64
            ?.let { ProfileSyncManager.base64ToBitmap(it) }
            ?.let { binding.ivIncomingAvatar.setImageBitmap(it) }
        com.wprd.calling.data.remote.FirebaseRepository().fetchUserPhotoBase64(callerUid) { base64 ->
            val bmp = base64?.let { ProfileSyncManager.base64ToBitmap(it) }
            if (bmp != null && !isFinishing) {
                binding.ivIncomingAvatar.setImageBitmap(bmp)
                binding.ivIncomingAvatar.setPadding(0, 0, 0, 0)
            }
        }

        startRinging()
        val myUid = ProfileSyncManager.normalizePhoneKey(
            ProfileSyncManager.getLocalProfile(this, "")?.phone ?: ""
        )
        val signaling = SignalingClient(callId, myUid)

        binding.btnAnswerCall.setOnClickListener {
            answered = true
            stopRinging()
            val target = if (callType == "VIDEO") VideoCallActivity::class.java
                else VoiceCallActivity::class.java
            val intent = Intent(this, target).apply {
                putExtra(BaseCallActivity.EXTRA_CALL_ID, callId)
                putExtra(BaseCallActivity.EXTRA_PEER_UID, callerUid)
                putExtra(BaseCallActivity.EXTRA_PEER_NAME, callerName)
                putExtra(BaseCallActivity.EXTRA_PEER_PHONE, callerPhone)
                putExtra(BaseCallActivity.EXTRA_CALL_TYPE, callType)
                putExtra(BaseCallActivity.EXTRA_IS_CALLER, false)
            }
            startActivity(intent)
            finish()
        }

        binding.btnDeclineCall.setOnClickListener {
            stopRinging()
            signaling.setStatus("DECLINED")
            saveMissedOrDeclined(callerUid, callerName, callerPhone, callType)
            finish()
        }
    }

    private fun saveMissedOrDeclined(uid: String, name: String, phone: String, type: String) {
        val myKey = ProfileSyncManager.normalizePhoneKey(
            ProfileSyncManager.getLocalProfile(this, "")?.phone ?: ""
        )
        lifecycleScope.launch {
            try {
                AppDatabase.getInstance(this@IncomingCallActivity).callDao().insertCallRecord(
                    CallRecord(
                        ownerKey = myKey,
                        peerUid = uid, peerName = name, peerPhone = phone,
                        callType = type, direction = "MISSED",
                        timestamp = System.currentTimeMillis(), durationSeconds = 0
                    )
                )
            } catch (_: Exception) {}
        }
    }

    private fun startRinging() {
        try {
            ringtone = RingtoneManager.getRingtone(
                this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            )
            ringtone?.play()
        } catch (_: Exception) {}
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        val pattern = longArrayOf(0, 800, 600, 800, 600)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(pattern, 0)
        }
    }

    private fun stopRinging() {
        try { ringtone?.stop() } catch (_: Exception) {}
        vibrator?.cancel()
    }

    override fun onDestroy() {
        stopRinging()
        super.onDestroy()
    }
}
