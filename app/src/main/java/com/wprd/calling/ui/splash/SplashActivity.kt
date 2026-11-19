package com.wprd.calling.ui.splash

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import com.wprd.calling.databinding.ActivitySplashBinding
import com.wprd.calling.ui.auth.PhoneAuthActivity

@SuppressLint("CustomSplashScreen")
class SplashActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplashBinding
    private val handler = Handler(Looper.getMainLooper())
    private var navigated = false

    private val goToAuthRunnable = Runnable {
        if (!navigated && !isFinishing && !isDestroyed) {
            navigated = true
            startActivity(Intent(this@SplashActivity, PhoneAuthActivity::class.java))
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
