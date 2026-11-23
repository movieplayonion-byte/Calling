package com.wprd.calling.ui.auth

import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.firebase.FirebaseException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.PhoneAuthOptions
import com.google.firebase.auth.PhoneAuthProvider
import com.wprd.calling.data.local.ProfileSyncManager
import com.wprd.calling.databinding.ActivityPhoneAuthBinding
import com.wprd.calling.ui.main.MainActivity
import com.wprd.calling.util.AppPermissionsHelper
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * WhatsApp-style single flow for login & sign-up:
 * phone number -> real Firebase SMS OTP -> (new user) profile -> home.
 * There is no way past this screen without a verified OTP sign-in.
 */
class PhoneAuthActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SHOW_MESSAGE = "SHOW_MESSAGE"
    }

    private lateinit var binding: ActivityPhoneAuthBinding
    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }

    private var verificationId: String? = null
    private var resendToken: PhoneAuthProvider.ForceResendingToken? = null
    private var verifiedPhone: String = ""
    private var resendTimer: CountDownTimer? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPhoneAuthBinding.inflate(layoutInflater)
        setContentView(binding.root)

        AppPermissionsHelper.requestAllPermissions(permissionLauncher)

        // Signup ke baad wapas aaye hain to sandesh dikhao.
        intent.getStringExtra(EXTRA_SHOW_MESSAGE)?.takeIf { it.isNotBlank() }?.let { msg ->
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            binding.tvAuthSubtitle.text = msg
        }

        binding.btnNextAuth.setOnClickListener { startAuth() }
        // Sign Up link: login wale T&C tick se iska koi lena-dena nahi —
        // seedha signup screen kholo, bina kisi shart ke.
        binding.tvGoToSignUp.setOnClickListener {
            val intent = Intent(this, CreateProfileActivity::class.java).apply {
                putExtra("AUTH_FLOW", CreateProfileActivity.FLOW_SIGN_UP_PRE)
            }
            startActivity(intent)
        }
        binding.tvChangeNumber.setOnClickListener { showPhoneStep() }
        binding.btnVerifyOtp.setOnClickListener {
            val code = binding.etOtpCode.text.toString().trim()
            val vid = verificationId
            if (code.length != 6 || vid == null) {
                binding.etOtpCode.error = "Enter the 6-digit OTP"
                return@setOnClickListener
            }
            setBusy(true)
            signIn(PhoneAuthProvider.getCredential(vid, code))
        }
        binding.tvResendOtp.setOnClickListener {
            if (resendToken != null && binding.tvResendOtp.isEnabled) sendOtp(resend = true)
        }
    }

    private fun startAuth() {
        val rawInput = binding.etPhoneNumber.text.toString().trim()
        val digitsOnly = rawInput.filter { it.isDigit() }
        if (digitsOnly.length < 10) {
            binding.etPhoneNumber.error = "Enter a valid phone number"
            Toast.makeText(this, "Please enter a valid phone number", Toast.LENGTH_SHORT).show()
            return
        }
        if (!binding.cbTermsConditions.isChecked) {
            Toast.makeText(this, "Please accept Terms & Conditions to continue", Toast.LENGTH_SHORT).show()
            return
        }
        verifiedPhone = when {
            rawInput.startsWith("+") -> "+" + digitsOnly
            digitsOnly.length > 10 -> "+" + digitsOnly
            else -> "+91" + digitsOnly.takeLast(10)
        }
        sendOtp(resend = false)
    }

    private fun sendOtp(resend: Boolean) {
        setBusy(true)
        val builder = PhoneAuthOptions.newBuilder(auth)
            .setPhoneNumber(verifiedPhone)
            .setTimeout(60L, TimeUnit.SECONDS)
            .setActivity(this)
            .setCallbacks(callbacks)
        if (resend && resendToken != null) builder.setForceResendingToken(resendToken!!)
        PhoneAuthProvider.verifyPhoneNumber(builder.build())
    }

    private val callbacks = object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
        override fun onVerificationCompleted(credential: PhoneAuthCredential) {
            // Auto-retrieval / instant verification on many devices.
            signIn(credential)
        }

        override fun onVerificationFailed(e: FirebaseException) {
            setBusy(false)
            val detail = if (e is com.google.firebase.auth.FirebaseAuthException) {
                "${e.errorCode} — ${e.message}"
            } else {
                "${e.javaClass.simpleName} — ${e.message}"
            }
            android.util.Log.e("PhoneAuth", "OTP verification failed: $detail", e)
            val msg = when (e) {
                is FirebaseAuthInvalidCredentialsException ->
                    "Number galat hai ya app verification fail hua."
                is FirebaseTooManyRequestsException ->
                    "Aaj ke SMS ki limit (10/day) khatm ho gayi lagti hai. Kal try karein ya test number use karein."
                else -> "OTP bheja nahi ja saka."
            }
            // Asal Firebase error screen par bhi dikhao taaki screenshot se diagnosis ho sake.
            binding.tvAuthSubtitle.text = "$msg\nFirebase error: $detail"
            binding.tvAuthSubtitle.setTextColor(0xFFFF8080.toInt())
            Toast.makeText(this@PhoneAuthActivity, "$msg ($detail)", Toast.LENGTH_LONG).show()
        }

        override fun onCodeSent(vid: String, token: PhoneAuthProvider.ForceResendingToken) {
            setBusy(false)
            verificationId = vid
            resendToken = token
            // WhatsApp ki tarah: OTP step aate hi number wala hissa hide,
            // sirf OTP wala hissa dikhe.
            binding.layoutPhoneSection.visibility = View.GONE
            binding.layoutOtpSection.visibility = View.VISIBLE
            binding.tvAuthTitle.text = "Enter OTP"
            binding.tvOtpInfo.text = "Enter the 6-digit OTP sent to $verifiedPhone"
            startResendTimer()
            Toast.makeText(this@PhoneAuthActivity, "OTP bhej diya gaya hai", Toast.LENGTH_SHORT).show()
        }
    }

    /** "Number badlein": OTP step se wapas number wale step par. */
    private fun showPhoneStep() {
        resendTimer?.cancel()
        verificationId = null
        resendToken = null
        binding.etOtpCode.text?.clear()
        binding.layoutOtpSection.visibility = View.GONE
        binding.layoutPhoneSection.visibility = View.VISIBLE
        binding.tvAuthTitle.text = "Enter Your Number"
        binding.btnNextAuth.isEnabled = true
        binding.etPhoneNumber.isEnabled = true
    }

    private fun startResendTimer() {
        resendTimer?.cancel()
        binding.tvResendOtp.isEnabled = false
        resendTimer = object : CountDownTimer(60_000, 1_000) {
            override fun onTick(ms: Long) {
                binding.tvResendOtp.text = "Resend OTP in ${ms / 1000}s"
            }
            override fun onFinish() {
                binding.tvResendOtp.text = "Resend OTP"
                binding.tvResendOtp.isEnabled = true
            }
        }.start()
    }

    private var signInSettled = false
    private val signInWatchdog = Runnable {
        if (!signInSettled && !isFinishing && !isDestroyed) {
            // 30 second me koi jawab nahi — UI ko hamesha ke liye "Please wait" par mat roko.
            setBusy(false)
            binding.tvAuthSubtitle.text =
                "Server se jawab nahi aaya (network slow ya database setup baki hai). Dobara try karein."
            binding.tvAuthSubtitle.setTextColor(0xFFFF8080.toInt())
            Toast.makeText(this, "Network slow hai, dobara try karein", Toast.LENGTH_LONG).show()
        }
    }

    private fun signIn(credential: PhoneAuthCredential) {
        signInSettled = false
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        handler.postDelayed(signInWatchdog, 30_000)
        auth.signInWithCredential(credential)
            .addOnSuccessListener {
                signInSettled = true
                handler.removeCallbacks(signInWatchdog)
                resendTimer?.cancel()
                routeAfterSignIn()
            }
            .addOnFailureListener {
                signInSettled = true
                handler.removeCallbacks(signInWatchdog)
                setBusy(false)
                binding.etOtpCode.error = "Galat OTP"
                Toast.makeText(this, "OTP galat hai, dobara try karein", Toast.LENGTH_LONG).show()
            }
    }

    /** Existing profile -> straight home (task cleared). New user -> create profile. */
    private fun routeAfterSignIn() {
        val phone = auth.currentUser?.phoneNumber ?: verifiedPhone
        lifecycleScope.launch {
            val existing = ProfileSyncManager.fetchProfileByPhone(this@PhoneAuthActivity, phone)
            if (existing != null && existing.name.isNotBlank()) {
                ProfileSyncManager.saveLocalProfile(
                    this@PhoneAuthActivity, phone, existing.name, existing.photoBase64
                )
                val intent = Intent(this@PhoneAuthActivity, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                startActivity(intent)
                finish()
            } else {
                val intent = Intent(this@PhoneAuthActivity, CreateProfileActivity::class.java).apply {
                    putExtra("PHONE_NUMBER", phone)
                    putExtra("AUTH_FLOW", "SIGN_UP")
                }
                startActivity(intent)
                finish()
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        binding.btnNextAuth.isEnabled = !busy
        binding.btnVerifyOtp.isEnabled = !busy
        if (busy) binding.btnVerifyOtp.text = "Please wait…"
        else binding.btnVerifyOtp.text = "Verify & Continue"
    }

    override fun onDestroy() {
        resendTimer?.cancel()
        super.onDestroy()
    }
}
