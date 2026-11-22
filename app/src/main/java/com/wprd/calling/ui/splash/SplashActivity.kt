package com.wprd.calling.ui.splash

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import com.wprd.calling.databinding.ActivitySplashBinding
import com.wprd.calling.data.local.ProfileSyncManager
import com.google.firebase.auth.FirebaseAuth
import com.wprd.calling.ui.auth.CreateProfileActivity
import com.wprd.calling.ui.auth.PhoneAuthActivity
import com.wprd.calling.ui.main.MainActivity

@SuppressLint("CustomSplashScreen")
class SplashActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplashBinding
    private val handler = Handler(Looper.getMainLooper())
    private var navigated = false

    private val goToAuthRunnable = Runnable {
        if (!navigated && !isFinishing && !isDestroyed) {
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        handler.postDelayed(goToAuthRunnable, 700)
    }

    override fun onDestroy() {
        handler.removeCallbacks(goToAuthRunnable)
        super.onDestroy()
    }
}
