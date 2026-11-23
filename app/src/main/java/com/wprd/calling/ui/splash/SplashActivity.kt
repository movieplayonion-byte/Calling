package com.wprd.calling.ui.splash

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.wprd.calling.databinding.ActivitySplashBinding
import com.wprd.calling.data.local.ProfileSyncManager
import com.google.firebase.auth.FirebaseAuth
import com.wprd.calling.ui.auth.CreateProfileActivity
import com.wprd.calling.ui.auth.PhoneAuthActivity
import com.wprd.calling.ui.main.MainActivity
import com.wprd.calling.ui.update.UpdateActivity
import com.wprd.calling.update.UpdateChecker
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

@SuppressLint("CustomSplashScreen")
class SplashActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplashBinding
    private var navigated = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        lifecycleScope.launch {
            // Mandatory-update gate, checked first: query GitHub Releases
            // while the splash shows (min 700ms). If a newer version is
            // KNOWN to exist, the user must install it before anything
            // else in the app is reachable. If the check itself fails
            // (offline, timeout, server error) we proceed normally so
            // offline users are never bricked.
            val check = async { UpdateChecker.check(this@SplashActivity) }
            delay(700)
            val update = withTimeoutOrNull(5000) { check.await() }
            if (isFinishing || isDestroyed || navigated) return@launch
            if (update != null) {
                navigated = true
                startActivity(Intent(this@SplashActivity, UpdateActivity::class.java).apply {
                    putExtra(UpdateActivity.EXTRA_APK_URL, update.apkUrl)
                    putExtra(UpdateActivity.EXTRA_LATEST_VERSION, update.latestVersion)
                })
                finish()
                return@launch
            }
            navigateNext()
        }
    }

    private fun navigateNext() {
        if (navigated || isFinishing || isDestroyed) return
        navigated = true
        val user = FirebaseAuth.getInstance().currentUser
        val hasProfile = ProfileSyncManager.getLocalProfile(this@SplashActivity, "") != null
        when {
            // Signed in + profile ready -> home. Back from home exits the app.
            user != null && hasProfile ->
                startActivity(Intent(this@SplashActivity, MainActivity::class.java))
            // Signed in but profile not created yet -> finish profile first.
            user != null -> startActivity(
                Intent(this@SplashActivity, CreateProfileActivity::class.java).apply {
                    putExtra("PHONE_NUMBER", user.phoneNumber ?: "")
                }
            )
            // Not signed in -> OTP login. A leftover local profile alone is NOT a login.
            else -> startActivity(Intent(this@SplashActivity, PhoneAuthActivity::class.java))
        }
        finish()
    }
}
