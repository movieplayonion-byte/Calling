package com.wprd.calling.ui.auth

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.wprd.calling.data.local.AuthSession
import com.wprd.calling.data.local.ProfileSyncManager
import com.wprd.calling.databinding.ActivityCreateProfileBinding
import com.wprd.calling.ui.main.MainActivity
import kotlinx.coroutines.launch

/**
 * Profile pura karne ki screen (naam/photo) — v1.2.0 me ye SIRF login
 * ke baad aati hai: ya to SignUpActivity ke turant baad (naya account),
 * ya login ke baad jab profile abhi bana hi nahi ho. Session hamare
 * apne server wala hai (AuthSession); bina session ke yahan koi nahi
 * aa sakta. Number hamesha verified wala locked rehta hai.
 */
class CreateProfileActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCreateProfileBinding
    private var selectedPhotoBase64: String? = null
    private var verifiedPhone: String = ""

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            val encoded = ProfileSyncManager.uriToCompressedBase64(this, uri)
            if (encoded != null) {
                selectedPhotoBase64 = encoded
                val bitmap = ProfileSyncManager.base64ToBitmap(encoded)
                if (bitmap != null) {
                    binding.ivProfilePhoto.setPadding(0, 0, 0, 0)
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

        // Bina login session ke profile nahi banega — login screen par bhejo.
        if (!AuthSession.isLoggedIn(this)) {
            startActivity(Intent(this, PhoneAuthActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            })
            finish()
            return
        }

        verifiedPhone = intent.getStringExtra("PHONE_NUMBER")
            ?: AuthSession.phone(this) ?: ""
        if (verifiedPhone.isNotBlank()) {
            binding.etProfilePhone.setText(verifiedPhone)
            binding.etProfilePhone.isEnabled = false // verified number change nahi hoga
        }

        // T&C signup/login par pehle hi accept ho chuka hai — yahan dobara nahi.
        binding.cbSignupTerms.visibility = View.GONE

        // Signup par diya gaya naam pehle se bhar do (user badal sakta hai).
        intent.getStringExtra("PREFILL_NAME")?.takeIf { it.isNotBlank() }?.let {
            binding.etProfileName.setText(it)
        }
        prefillExistingProfile(verifiedPhone)

        val openGallery = {
            pickImageLauncher.launch("image/*")
        }
        binding.ivProfilePhoto.setOnClickListener { openGallery() }
        binding.tvSelectPhoto.setOnClickListener { openGallery() }

        binding.btnContinue.setOnClickListener {
            val name = binding.etProfileName.text.toString().trim()
            if (name.isEmpty()) {
                binding.etProfileName.error = "Name is required"
                Toast.makeText(this, "Please enter your name", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (verifiedPhone.isBlank()) {
                Toast.makeText(this, "Session khatm ho gaya hai — dobara login karein", Toast.LENGTH_LONG).show()
                startActivity(Intent(this, PhoneAuthActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                })
                finish()
                return@setOnClickListener
            }

            binding.btnContinue.isEnabled = false
            lifecycleScope.launch {
                val ok = ProfileSyncManager.saveAndSyncProfile(
                    context = this@CreateProfileActivity,
                    phone = verifiedPhone,
                    name = name,
                    photoBase64 = selectedPhotoBase64
                )
                binding.btnContinue.isEnabled = true

                if (!ok) {
                    Toast.makeText(
                        this@CreateProfileActivity, "Failed to save profile", Toast.LENGTH_SHORT
                    ).show()
                    return@launch
                }
                Toast.makeText(
                    this@CreateProfileActivity, "Profile saved & synced", Toast.LENGTH_SHORT
                ).show()
                val intent = Intent(this@CreateProfileActivity, MainActivity::class.java)
                    .apply {
                        putExtra("USER_PHONE", verifiedPhone)
                        putExtra("USER_NAME", name)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    }
                startActivity(intent)
                finish()
            }
        }
    }

    /** Agar profile pehle se hai (jaise dobara login par), use bhar do. */
    private fun prefillExistingProfile(incomingPhone: String) {
        val initialPhone = incomingPhone.ifBlank {
            getSharedPreferences("wprd_profile_sync", MODE_PRIVATE)
                .getString("last_phone", "") ?: ""
        }
        if (initialPhone.isBlank()) return
        lifecycleScope.launch {
            val existing = ProfileSyncManager.fetchProfileByPhone(
                this@CreateProfileActivity, initialPhone
            )
            if (existing != null) {
                if (existing.name.isNotBlank() && binding.etProfileName.text.isNullOrBlank()) {
                    binding.etProfileName.setText(existing.name)
                }
                if (!existing.photoBase64.isNullOrBlank() && selectedPhotoBase64 == null) {
                    selectedPhotoBase64 = existing.photoBase64
                    ProfileSyncManager.base64ToBitmap(existing.photoBase64)?.let { bmp ->
                        binding.ivProfilePhoto.setPadding(0, 0, 0, 0)
                        binding.ivProfilePhoto.setImageBitmap(bmp)
                    }
                }
            }
        }
    }
}
