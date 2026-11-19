package com.wprd.calling.ui.settings

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.wprd.calling.data.local.ProfileSyncManager
import com.wprd.calling.databinding.ActivitySettingsBinding
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val phone = intent.getStringExtra("USER_PHONE") ?: ""
        lifecycleScope.launch {
            val profile = ProfileSyncManager.fetchProfileByPhone(this@SettingsActivity, phone)
            if (profile != null) {
                binding.tvSettingsStatus.text = buildString {
                    if (profile.name.isNotBlank()) append(profile.name).append(" • ")
                    append(profile.phone)
                }
                ProfileSyncManager.base64ToBitmap(profile.photoBase64)?.let { bmp ->
                    val photoId = resources.getIdentifier("ivProfilePhoto", "id", packageName)
                    if (photoId != 0) {
                        findViewById<android.widget.ImageView>(photoId)?.setImageBitmap(bmp)
                    }
                }
            }
        }
    }
}
