package com.wprd.calling.ui.settings

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.database.FirebaseDatabase
import com.wprd.calling.data.local.ProfileSyncManager
import com.wprd.calling.databinding.ActivityHelpFeedbackBinding

/** FAQ + feedback form. Feedback RTDB feedback/<phoneKey>/<pushId> par jata hai. */
class HelpFeedbackActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHelpFeedbackBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHelpFeedbackBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val phone = intent.getStringExtra("USER_PHONE")
            ?: ProfileSyncManager.getLocalProfile(this, "")?.phone ?: ""
        val profile = ProfileSyncManager.getLocalProfile(this, phone)
        val myKey = ProfileSyncManager.normalizePhoneKey(phone)

        binding.btnSendFeedback.setOnClickListener {
            val message = binding.etFeedback.text.toString().trim()
            if (message.isEmpty()) {
                binding.etFeedback.error = "Pehle kuch likhein"
                return@setOnClickListener
            }
            if (myKey.isBlank() || myKey == "unknown_user") {
                Toast.makeText(this, "Profile nahi mila, pehle login karein", Toast.LENGTH_SHORT)
                    .show()
                return@setOnClickListener
            }
            binding.btnSendFeedback.isEnabled = false
            val ref = FirebaseDatabase.getInstance().reference
                .child("feedback").child(myKey).push()
            val payload = hashMapOf<String, Any>(
                "name" to (profile?.name ?: ""),
                "phone" to phone,
                "message" to message,
                "createdAt" to System.currentTimeMillis()
            )
            ref.setValue(payload)
                .addOnSuccessListener {
                    binding.btnSendFeedback.isEnabled = true
                    binding.etFeedback.text?.clear()
                    Toast.makeText(
                        this,
                        "Feedback bhej diya gaya, dhanyavaad!", Toast.LENGTH_LONG
                    ).show()
                }
                .addOnFailureListener {
                    binding.btnSendFeedback.isEnabled = true
                    Toast.makeText(
                        this,
                        "Feedback nahi bhej paya — internet check karke dobara try karein",
                        Toast.LENGTH_LONG
                    ).show()
                }
        }
    }
}
