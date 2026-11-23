package com.wprd.calling.ui.auth

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.wprd.calling.data.local.ProfileSyncManager
import com.google.firebase.auth.FirebaseAuth
import com.wprd.calling.databinding.ActivityCreateProfileBinding
import com.wprd.calling.ui.main.MainActivity
import kotlinx.coroutines.launch

class CreateProfileActivity : AppCompatActivity() {

    companion object {
        /** Login screen ke "Sign Up" link se — abhi OTP login nahi hua hai. */
        const val FLOW_SIGN_UP_PRE = "SIGN_UP_PRE"
    }

    private lateinit var binding: ActivityCreateProfileBinding
    private var selectedPhotoBase64: String? = null
    private var isPreSignup: Boolean = false

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

        isPreSignup = intent.getStringExtra("AUTH_FLOW") == FLOW_SIGN_UP_PRE

        if (isPreSignup) {
            // Sign-up (login se pehle): koi session nahi chahiye. User apna
            // number khud dalega; profile usi number ke key par save hoga aur
            // kaam pura hote hi wapas login screen par bhejenge.
            binding.tvCreateProfileSubtitle.text =
                "Apna naam, photo aur number dalen — account ban jayega, phir OTP se login karein"
            binding.etProfilePhone.isEnabled = true
        } else {
            // Post-OTP flow: profile sirf OTP-verified session ke saath banega.
            if (FirebaseAuth.getInstance().currentUser == null) {
                startActivity(Intent(this, PhoneAuthActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                })
                finish()
                return
            }
            val incomingPhone = intent.getStringExtra("PHONE_NUMBER")
                ?: FirebaseAuth.getInstance().currentUser?.phoneNumber ?: ""
            if (incomingPhone.isNotBlank()) {
                binding.etProfilePhone.setText(incomingPhone)
                binding.etProfilePhone.isEnabled = false // verified number change nahi hoga
            }
            prefillExistingProfile(incomingPhone)
        }

        val openGallery = {
            pickImageLauncher.launch("image/*")
        }
        binding.ivProfilePhoto.setOnClickListener { openGallery() }
        binding.tvSelectPhoto.setOnClickListener { openGallery() }

        binding.btnContinue.setOnClickListener {
            val name = binding.etProfileName.text.toString().trim()
            val rawPhone = binding.etProfilePhone.text.toString().trim()

            if (name.isEmpty()) {
                binding.etProfileName.error = "Name is required"
                Toast.makeText(this, "Please enter your name", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val digits = rawPhone.filter { it.isDigit() }
            if (digits.length < 10) {
                binding.etProfilePhone.error = "Valid phone number required"
                Toast.makeText(this, "Please enter a valid phone number", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Signup screen ka apna T&C — yahi wala tick zaroori hai.
            if (!binding.cbSignupTerms.isChecked) {
                Toast.makeText(
                    this, "Please accept Terms & Conditions to continue", Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }

            // Number ko +91 wale pure roop me rakho taaki profile hamesha
            // sahi phone key (919xxxxxxxxx) par store ho.
            val phone = when {
                rawPhone.startsWith("+") -> "+" + digits
                digits.length > 10 -> "+" + digits
                else -> "+91" + digits.takeLast(10)
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

                if (!ok) {
                    Toast.makeText(
                        this@CreateProfileActivity, "Failed to save profile", Toast.LENGTH_SHORT
                    ).show()
                    return@launch
                }
                if (isPreSignup) {
                    // Account ban gaya — ab wapas login screen, OTP se login hoga.
                    Toast.makeText(
                        this@CreateProfileActivity,
                        "Account ban gaya hai, ab login karo", Toast.LENGTH_LONG
                    ).show()
                    val intent = Intent(this@CreateProfileActivity, PhoneAuthActivity::class.java)
                        .apply {
                            putExtra(
                                PhoneAuthActivity.EXTRA_SHOW_MESSAGE,
                                "Account ban gaya hai, ab login karo"
                            )
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_CLEAR_TASK
                        }
                    startActivity(intent)
                    finish()
                } else {
                    Toast.makeText(
                        this@CreateProfileActivity, "Profile saved & synced", Toast.LENGTH_SHORT
                    ).show()
                    val intent = Intent(this@CreateProfileActivity, MainActivity::class.java)
                        .apply {
                            putExtra("USER_PHONE", phone)
                            putExtra("USER_NAME", name)
                        }
                    startActivity(intent)
                    finishAffinity()
                }
            }
        }
    }

    /** Post-OTP flow me agar profile pehle se hai (jaise pre-signup wala), use bhar do. */
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
                if (binding.etProfilePhone.text.isNullOrBlank() && existing.phone.isNotBlank()) {
                    binding.etProfilePhone.setText(existing.phone)
                }
                if (!existing.photoBase64.isNullOrBlank()) {
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
