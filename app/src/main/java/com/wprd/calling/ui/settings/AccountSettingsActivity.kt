package com.wprd.calling.ui.settings

import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.wprd.calling.data.local.ProfileSyncManager
import com.wprd.calling.databinding.ActivityAccountSettingsBinding
import kotlinx.coroutines.launch

/** Naam, photo aur about badlo — RTDB users/<phoneKey> + per-user local store me save. */
class AccountSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAccountSettingsBinding
    private var phone: String = ""
    private var photoBase64: String? = null

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            val encoded = ProfileSyncManager.uriToCompressedBase64(this, uri)
            if (encoded != null) {
                photoBase64 = encoded
                ProfileSyncManager.base64ToBitmap(encoded)?.let {
                    binding.ivAccountPhoto.setPadding(0, 0, 0, 0)
                    binding.ivAccountPhoto.setImageBitmap(it)
                }
            } else {
                Toast.makeText(this, "Could not read selected image", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAccountSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        phone = intent.getStringExtra("USER_PHONE")
            ?: ProfileSyncManager.getLocalProfile(this, "")?.phone ?: ""
        binding.tvAccountPhone.text = phone

        lifecycleScope.launch {
            val profile = ProfileSyncManager.fetchProfileByPhone(this@AccountSettingsActivity, phone)
            if (profile != null) {
                binding.etAccountName.setText(profile.name)
                binding.etAccountAbout.setText(profile.about)
                photoBase64 = profile.photoBase64
                ProfileSyncManager.base64ToBitmap(profile.photoBase64)?.let { bmp ->
                    binding.ivAccountPhoto.setPadding(0, 0, 0, 0)
                    binding.ivAccountPhoto.setImageBitmap(bmp)
                }
            }
        }

        val openGallery = { pickImageLauncher.launch("image/*") }
        binding.ivAccountPhoto.setOnClickListener { openGallery() }
        binding.tvChangePhoto.setOnClickListener { openGallery() }

        binding.btnSaveAccount.setOnClickListener {
            val name = binding.etAccountName.text.toString().trim()
            if (name.isEmpty()) {
                binding.etAccountName.error = "Name is required"
                return@setOnClickListener
            }
            if (phone.isBlank()) {
                Toast.makeText(this, "Phone number nahi mila", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val about = binding.etAccountAbout.text.toString().trim()
            binding.btnSaveAccount.isEnabled = false
            lifecycleScope.launch {
                val ok = ProfileSyncManager.saveAndSyncProfile(
                    context = this@AccountSettingsActivity,
                    phone = phone,
                    name = name,
                    photoBase64 = photoBase64,
                    about = about
                )
                binding.btnSaveAccount.isEnabled = true
                if (ok) {
                    Toast.makeText(
                        this@AccountSettingsActivity,
                        "Profile update ho gaya", Toast.LENGTH_SHORT
                    ).show()
                    finish()
                } else {
                    Toast.makeText(
                        this@AccountSettingsActivity,
                        "Save nahi ho paya, dobara try karein", Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }
}
