package com.wprd.calling.ui.auth

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.wprd.calling.data.local.ProfileSyncManager
import com.wprd.calling.databinding.ActivityCreateProfileBinding
import com.wprd.calling.ui.main.MainActivity
import kotlinx.coroutines.launch

class CreateProfileActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCreateProfileBinding
    private var selectedPhotoBase64: String? = null

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            val encoded = ProfileSyncManager.uriToCompressedBase64(this, uri)
            if (encoded != null) {
                selectedPhotoBase64 = encoded
                val bitmap = ProfileSyncManager.base64ToBitmap(encoded)
                if (bitmap != null) {
                    binding.ivProfilePhoto.setImageBitmap(bitmap)
                }
            } else {
                Toast.makeText(this, "Could not read selected image", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCreateProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val incomingPhone = intent.getStringExtra("PHONE_NUMBER") ?: ""
        if (incomingPhone.isNotBlank()) {
            binding.etProfilePhone.setText(incomingPhone)
        }

        val initialPhone = incomingPhone.ifBlank {
            getSharedPreferences("wprd_profile_sync", MODE_PRIVATE)
                .getString("last_phone", "") ?: ""
        }

        if (initialPhone.isNotBlank()) {
            lifecycleScope.launch {
                val existing = ProfileSyncManager.fetchProfileByPhone(this@CreateProfileActivity, initialPhone)
                if (existing != null) {
                    if (existing.name.isNotBlank() && binding.etProfileName.text.isNullOrBlank()) {
                        binding.etProfileName.setText(existing.name)
                    }
                    if (!existing.photoBase64.isNullOrBlank()) {
                        selectedPhotoBase64 = existing.photoBase64
                        ProfileSyncManager.base64ToBitmap(existing.photoBase64)?.let { bmp ->
                            binding.ivProfilePhoto.setImageBitmap(bmp)
                        }
                    }
                }
            }
        }

        val openGallery = {
            pickImageLauncher.launch("image/*")
        }
        binding.ivProfilePhoto.setOnClickListener { openGallery() }
        binding.tvSelectPhoto.setOnClickListener { openGallery() }

        binding.btnContinue.setOnClickListener {
            val name = binding.etProfileName.text.toString().trim()
            val phone = binding.etProfilePhone.text.toString().trim()

            if (name.isEmpty()) {
                binding.etProfileName.error = "Name is required"
                Toast.makeText(this, "Please enter your name", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (phone.filter { it.isDigit() }.length < 10) {
                binding.etProfilePhone.error = "Valid phone number required"
                Toast.makeText(this, "Please enter a valid phone number", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            binding.btnContinue.isEnabled = false
            lifecycleScope.launch {
                val ok = ProfileSyncManager.saveAndSyncProfile(
                    context = this@CreateProfileActivity,
                    phone = phone,
                    name = name,
                    photoBase64 = selectedPhotoBase64
                )
                binding.btnContinue.isEnabled = true

                if (ok) {
                    Toast.makeText(this@CreateProfileActivity, "Profile saved & synced", Toast.LENGTH_SHORT).show()
                    val intent = Intent(this@CreateProfileActivity, MainActivity::class.java).apply {
                        putExtra("USER_PHONE", phone)
                        putExtra("USER_NAME", name)
                    }
                    startActivity(intent)
                    finishAffinity()
                } else {
                    Toast.makeText(this@CreateProfileActivity, "Failed to save profile", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
