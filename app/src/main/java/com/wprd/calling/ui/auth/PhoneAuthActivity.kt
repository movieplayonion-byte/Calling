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

        binding.btnNextAuth.setOnClickListener { startAuth() }
        binding.tvGoToSignUp.setOnClickListener { startAuth() }
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
            binding.layoutOtpSection.visibility = View.VISIBLE
            binding.tvOtpInfo.text = "Enter the 6-digit OTP sent to $verifiedPhone"
            binding.etPhoneNumber.isEnabled = false
            binding.btnNextAuth.isEnabled = false
            binding.btnNextAuth.text = "OTP Sent"
            startResendTimer()
            Toast.makeText(this@PhoneAuthActivity, "OTP bhej diya gaya hai", Toast.LENGTH_SHORT).show()
        }
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

    private fun signIn(credential: PhoneAuthCredential) {
        auth.signInWithCredential(credential)
            .addOnSuccessListener {
                resendTimer?.cancel()
                routeAfterSignIn()
            }
            .addOnFailureListener {
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
