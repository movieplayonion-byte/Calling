package com.wprd.calling.ui.main

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.tabs.TabLayoutMediator
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.ChildEventListener
import com.google.firebase.messaging.FirebaseMessaging
import com.wprd.calling.data.local.ProfileSyncManager
import com.wprd.calling.data.remote.FirebaseRepository
import com.wprd.calling.databinding.ActivityMainBinding
import com.wprd.calling.ui.auth.PhoneAuthActivity
import com.wprd.calling.ui.call.IncomingCallActivity
import com.wprd.calling.ui.settings.SettingsActivity
import com.wprd.calling.webrtc.SignalingClient

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val repository = FirebaseRepository()
    private var myUid: String = ""
    private var incomingListener: ChildEventListener? = null
    private val shownCallIds = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Home screen sirf verified login ke baad. Session nahi hai to OTP screen par bhejo.
        if (FirebaseAuth.getInstance().currentUser == null) {
            startActivity(Intent(this, PhoneAuthActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            })
            finish()
            return
        }
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val profile = ProfileSyncManager.getLocalProfile(this, "")
        myUid = ProfileSyncManager.normalizePhoneKey(profile?.phone ?: "")
        val myPhone = profile?.phone ?: ""
        val myName = profile?.name ?: ""

        binding.viewPagerMain.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount() = 2
            override fun createFragment(position: Int): Fragment =
                if (position == 0) CallsFragment() else ContactsFragment()
        }
        TabLayoutMediator(binding.tabLayoutMain, binding.viewPagerMain) { tab, position ->
            tab.text = if (position == 0) "Calls" else "Contacts"
        }.attach()
        binding.viewPagerMain.currentItem = 1 // Contacts first: yahi se call shuru hoti hai

        binding.btnSettingsMenu.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java).apply {
                putExtra("USER_PHONE", myPhone)
            })
        }
        binding.btnSearch.setOnClickListener {
            binding.viewPagerMain.currentItem = 1
        }

        if (myUid.isNotBlank() && myUid != "unknown_user") {
            repository.setPresence(myUid, true)
            FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
                repository.saveFcmToken(myUid, token)
            }
            listenForIncomingCalls()
        }
    }

    private fun listenForIncomingCalls() {
        incomingListener = SignalingClient.observeIncomingCalls(myUid) { snap ->
            val callId = snap.child("callId").getValue(String::class.java)
                ?: snap.key ?: return@observeIncomingCalls
            if (!shownCallIds.add(callId)) return@observeIncomingCalls
            val intent = Intent(this, IncomingCallActivity::class.java).apply {
                putExtra(IncomingCallActivity.EXTRA_CALL_ID, callId)
                putExtra(
                    IncomingCallActivity.EXTRA_CALLER_NAME,
                    snap.child("callerName").getValue(String::class.java) ?: "Unknown"
                )
                putExtra(
                    IncomingCallActivity.EXTRA_CALLER_PHONE,
                    snap.child("callerPhone").getValue(String::class.java) ?: ""
                )
                putExtra(
                    IncomingCallActivity.EXTRA_CALL_TYPE,
                    snap.child("callType").getValue(String::class.java) ?: "VOICE"
                )
            }
            startActivity(intent)
        }
    }

    override fun onDestroy() {
        if (myUid.isNotBlank()) repository.setPresence(myUid, false)
        super.onDestroy()
    }
}
