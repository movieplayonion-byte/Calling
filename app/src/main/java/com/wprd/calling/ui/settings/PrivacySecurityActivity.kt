package com.wprd.calling.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.database.FirebaseDatabase
import com.wprd.calling.R
import com.wprd.calling.data.local.BlockedUser
import com.wprd.calling.data.local.BlockedUsersManager
import com.wprd.calling.data.local.ProfileSyncManager
import com.wprd.calling.data.model.User
import com.wprd.calling.data.remote.FirebaseRepository
import com.wprd.calling.databinding.ActivityPrivacySecurityBinding

/**
 * Privacy toggles (users/<key>/privacy) + blocked users
 * (users/<key>/blocked). Blocked callers never ring — the check lives in
 * MainActivity's incoming listener and the FCM service via the local cache.
 */
class PrivacySecurityActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPrivacySecurityBinding
    private var phone: String = ""
    private var myKey: String = ""
    private var blockedUsers: List<BlockedUser> = emptyList()
    private var registeredUsers: Map<String, User> = emptyMap()

    private val privacyRef by lazy {
        FirebaseDatabase.getInstance().reference
            .child("users").child(myKey).child("privacy")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPrivacySecurityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        phone = intent.getStringExtra("USER_PHONE")
            ?: ProfileSyncManager.getLocalProfile(this, "")?.phone ?: ""
        myKey = ProfileSyncManager.normalizePhoneKey(phone)
        if (myKey.isBlank() || myKey == "unknown_user") {
            Toast.makeText(this, "Profile nahi mila", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        loadPrivacyToggles()
        binding.switchPhotoVisibility.setOnCheckedChangeListener { _, checked ->
            privacyRef.child("profilePhotoEveryone").setValue(checked)
        }
        binding.switchLastSeen.setOnCheckedChangeListener { _, checked ->
            privacyRef.child("lastSeenVisible").setValue(checked)
        }

        BlockedUsersManager.observeBlocked(this, myKey) { users ->
            blockedUsers = users
            renderBlocked()
        }
        FirebaseRepository().observeRegisteredUsers { users -> registeredUsers = users }

        binding.btnBlockContact.setOnClickListener { showBlockPicker() }
    }

    private fun loadPrivacyToggles() {
        privacyRef.get().addOnSuccessListener { snap ->
            val photoEveryone =
                snap.child("profilePhotoEveryone").getValue(Boolean::class.java) ?: true
            val lastSeen = snap.child("lastSeenVisible").getValue(Boolean::class.java) ?: true
            binding.switchPhotoVisibility.isChecked = photoEveryone
            binding.switchLastSeen.isChecked = lastSeen
        }
    }

    private fun renderBlocked() {
        binding.llBlockedList.removeAllViews()
        binding.tvNoBlocked.visibility = if (blockedUsers.isEmpty()) View.VISIBLE else View.GONE
        val inflater = LayoutInflater.from(this)
        for (user in blockedUsers) {
            val row = inflater.inflate(R.layout.item_blocked_user, binding.llBlockedList, false)
            row.findViewById<TextView>(R.id.tvBlockedName).text =
                user.name.ifBlank { user.phone }
            row.findViewById<TextView>(R.id.tvBlockedPhone).text = user.phone
            row.findViewById<View>(R.id.btnUnblock).setOnClickListener {
                BlockedUsersManager.unblock(this, myKey, user.uid)
                Toast.makeText(this, "${user.name.ifBlank { user.phone }} unblock ho gaya",
                    Toast.LENGTH_SHORT).show()
            }
            binding.llBlockedList.addView(row)
        }
    }

    private fun showBlockPicker() {
        val blockedKeys = blockedUsers.map { it.uid }.toSet()
        val candidates = registeredUsers.values
            .filter { it.uid != myKey && it.uid !in blockedKeys }
            .sortedBy { it.name.lowercase() }
        if (candidates.isEmpty()) {
            Toast.makeText(this, "Block karne ke liye koi user nahi mila", Toast.LENGTH_SHORT)
                .show()
            return
        }
        val labels = candidates.map { user ->
            val display = user.name.ifBlank { user.phone }
            "$display\n${user.phone}"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Kise block karein?")
            .setItems(labels) { _, which ->
                val user = candidates[which]
                BlockedUsersManager.block(
                    this, myKey,
                    BlockedUser(uid = user.uid, name = user.name, phone = user.phone)
                )
                Toast.makeText(
                    this,
                    "${user.name.ifBlank { user.phone }} block ho gaya — ab uski call nahi aayegi",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
