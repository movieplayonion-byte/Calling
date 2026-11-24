package com.wprd.calling.ui.main

import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayoutMediator
import com.google.firebase.database.ChildEventListener
import com.google.firebase.messaging.FirebaseMessaging
import com.wprd.calling.data.local.BlockedUsersManager
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
    private var searchQuery: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Home screen sirf verified login ke baad. Session nahi hai to login screen par bhejo.
        if (!com.wprd.calling.data.local.AuthSession.isLoggedIn(this)) {
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
        // Hamesha Calls tab par khule — chahe call log khaali hi kyun na ho.
        binding.viewPagerMain.currentItem = 0
        binding.viewPagerMain.registerOnPageChangeCallback(
            object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    // Tab badla to wahi search query naye tab par lago.
                    dispatchSearch()
                }
            }
        )

        binding.btnSettingsMenu.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java).apply {
                putExtra("USER_PHONE", myPhone)
            })
        }
        setupSearch()

        if (myUid.isNotBlank() && myUid != "unknown_user") {
            repository.setPresence(myUid, true)
            FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
                repository.saveFcmToken(myUid, token)
            }
            // Blocked list ka local cache hamesha taza rakho (ring rokne ke liye).
            BlockedUsersManager.observeBlocked(this, myUid) { }
            listenForIncomingCalls()
            maybeRequestFullScreenIntentAccess()
        }
    }

    private fun setupSearch() {
        binding.btnSearch.setOnClickListener {
            val opening = binding.layoutSearchBar.visibility != View.VISIBLE
            binding.layoutSearchBar.visibility = if (opening) View.VISIBLE else View.GONE
            if (opening) {
                binding.etSearchQuery.requestFocus()
            } else {
                binding.etSearchQuery.text?.clear()
            }
        }
        binding.btnCloseSearch.setOnClickListener {
            binding.etSearchQuery.text?.clear()
            binding.layoutSearchBar.visibility = View.GONE
        }
        binding.etSearchQuery.addTextChangedListener { text ->
            searchQuery = text?.toString()?.trim() ?: ""
            dispatchSearch()
        }
    }

    /** Current tab ke fragment ko search query bhejo (Calls ya Contacts). */
    private fun dispatchSearch() {
        val position = binding.viewPagerMain.currentItem
        val fragment = supportFragmentManager.findFragmentByTag("f$position")
        (fragment as? SearchableTab)?.applySearch(searchQuery)
    }

    /**
     * Android 14 par full-screen incoming-call notification ke liye alag
     * permission hoti hai. Ek baar (sirf pehli baar) system settings khol kar
     * maang lo; user na de to heads-up notification se kaam chalega.
     */
    private fun maybeRequestFullScreenIntentAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.canUseFullScreenIntent()) return
        val prefs = getSharedPreferences("wprd_prefs", MODE_PRIVATE)
        if (prefs.getBoolean("fsi_requested", false)) return
        prefs.edit().putBoolean("fsi_requested", true).apply()
        try {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                    android.net.Uri.parse("package:$packageName")
                )
            )
        } catch (_: Exception) {
        }
    }

    private fun listenForIncomingCalls() {
        incomingListener = SignalingClient.observeIncomingCalls(myUid) { snap ->
            val callId = snap.child("callId").getValue(String::class.java)
                ?: snap.key ?: return@observeIncomingCalls
            if (!shownCallIds.add(callId)) return@observeIncomingCalls
            // Blocked caller ki call par ghanti hi nahi bajegi.
            val callerUid = snap.child("callerUid").getValue(String::class.java) ?: ""
            if (callerUid.isNotBlank() &&
                BlockedUsersManager.isBlockedLocal(this, myUid, callerUid)
            ) {
                return@observeIncomingCalls
            }
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
