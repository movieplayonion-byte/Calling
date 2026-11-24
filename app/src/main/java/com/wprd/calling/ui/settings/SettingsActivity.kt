package com.wprd.calling.ui.settings

import android.content.Intent
import android.os.Bundle
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.wprd.calling.BuildConfig
import com.wprd.calling.data.local.ProfileSyncManager
import com.wprd.calling.data.remote.FirebaseRepository
import com.wprd.calling.databinding.ActivitySettingsBinding
import com.wprd.calling.ui.auth.PhoneAuthActivity
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val phone = intent.getStringExtra("USER_PHONE")
            ?: ProfileSyncManager.getLocalProfile(this, "")?.phone ?: ""

        binding.tvAboutVersion.text = "Version ${BuildConfig.VERSION_NAME}"

        binding.rowAccount.setOnClickListener {
            startActivity(Intent(this, AccountSettingsActivity::class.java).apply {
                putExtra("USER_PHONE", phone)
            })
        }
        binding.rowPrivacy.setOnClickListener {
            startActivity(Intent(this, PrivacySecurityActivity::class.java).apply {
                putExtra("USER_PHONE", phone)
            })
        }
        binding.rowHelp.setOnClickListener {
            startActivity(Intent(this, HelpFeedbackActivity::class.java).apply {
                putExtra("USER_PHONE", phone)
            })
        }
        binding.rowAbout.setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }
        // Header tap = account settings (photo/name yahin se badalte hain).
        binding.ivSettingsAvatar.setOnClickListener {
            startActivity(Intent(this, AccountSettingsActivity::class.java).apply {
                putExtra("USER_PHONE", phone)
            })
        }

        binding.btnLogout.setOnClickListener {
            val uid = ProfileSyncManager.normalizePhoneKey(phone)
            if (uid.isNotBlank()) FirebaseRepository().setPresence(uid, false)
            // v1.2.0: server session revoke (best-effort) + local session saaf.
            val sessionToken = com.wprd.calling.data.local.AuthSession.token(this)
            com.wprd.calling.data.local.AuthSession.clear(this)
            if (sessionToken != null) {
                lifecycleScope.launch {
                    com.wprd.calling.data.remote.AuthApi.logout(sessionToken)
                }
            }
            ProfileSyncManager.clearLocalProfile(this)
            startActivity(Intent(this, PhoneAuthActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            })
            finish()
        }
        loadHeader(phone)
    }

    override fun onResume() {
        super.onResume()
        val phone = intent.getStringExtra("USER_PHONE")
            ?: ProfileSyncManager.getLocalProfile(this, "")?.phone ?: ""
        loadHeader(phone)
    }

    private fun loadHeader(phone: String) {
        if (phone.isBlank()) return
        lifecycleScope.launch {
            val profile = ProfileSyncManager.fetchProfileByPhone(this@SettingsActivity, phone)
            if (profile != null) {
                if (profile.name.isNotBlank()) binding.tvSettingsName.text = profile.name
                binding.tvSettingsStatus.text =
                    if (profile.phone.isNotBlank()) "Always Connected • ${profile.phone}"
                    else "Always Connected"
                ProfileSyncManager.base64ToBitmap(profile.photoBase64)?.let { bmp ->
                    binding.ivSettingsAvatar.setPadding(0, 0, 0, 0)
                    binding.ivSettingsAvatar.scaleType = ImageView.ScaleType.CENTER_CROP
                    binding.ivSettingsAvatar.setImageBitmap(bmp)
                }
            }
        }
    }
}
