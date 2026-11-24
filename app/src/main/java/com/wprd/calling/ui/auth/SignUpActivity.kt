package com.wprd.calling.ui.auth

import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.wprd.calling.data.local.AuthSession
import com.wprd.calling.data.remote.AuthApi
import com.wprd.calling.databinding.ActivitySignUpBinding
import kotlinx.coroutines.launch

/**
 * v1.2.0 SIGN UP: naam + number -> ek MSG91 SMS OTP (hamare server ke
 * through) -> password set -> account ban gaya aur session mil gaya.
 * Uske baad profile (photo) ke liye CreateProfileActivity, phir home.
 * Roz ke login me OTP NAHI lagta — wo PhoneAuthActivity (login screen)
 * par number + password se hota hai.
 */
class SignUpActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySignUpBinding

    private var verifiedPhone: String = ""
    private var userName: String = ""
    private var grantToken: String = ""
    private var resendTimer: CountDownTimer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySignUpBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnSendOtp.setOnClickListener { startSignUp() }
        binding.btnVerifyOtp.setOnClickListener { verifyOtp() }
        binding.tvResendOtp.setOnClickListener {
            if (binding.tvResendOtp.isEnabled) sendOtp()
        }
        binding.tvChangeNumber.setOnClickListener { showFormStep() }
        binding.btnCreateAccount.setOnClickListener { createAccount() }
        binding.tvGoToLogin.setOnClickListener { finish() }
    }

    private fun startSignUp() {
        val name = binding.etName.text.toString().trim()
        if (name.isEmpty()) {
            binding.etName.error = "Name is required"
            return
        }
        val rawInput = binding.etPhoneNumber.text.toString().trim()
        val digitsOnly = rawInput.filter { it.isDigit() }
        if (digitsOnly.length < 10) {
            binding.etPhoneNumber.error = "Enter a valid phone number"
            toast("Please enter a valid phone number")
            return
        }
        if (!binding.cbTermsConditions.isChecked) {
            toast("Please accept Terms & Conditions to continue")
            return
        }
        userName = name
        verifiedPhone = when {
            rawInput.startsWith("+") -> "+" + digitsOnly
            digitsOnly.length > 10 -> "+" + digitsOnly
            else -> "+91" + digitsOnly.takeLast(10)
        }
        sendOtp()
    }

    private fun sendOtp() {
        setBusy(true)
        lifecycleScope.launch {
            val result = AuthApi.sendOtp(verifiedPhone, "signup")
            setBusy(false)
            if (!result.ok) {
                toast(AuthApi.friendlyError(result.error))
                return@launch
            }
            binding.layoutFormSection.visibility = View.GONE
            binding.layoutPasswordSection.visibility = View.GONE
            binding.layoutOtpSection.visibility = View.VISIBLE
            binding.tvSignUpTitle.text = "Enter OTP"
            binding.tvOtpInfo.text = "Enter the OTP sent to $verifiedPhone"
            startResendTimer()
            toast("OTP bhej diya gaya hai")
        }
    }

    private fun verifyOtp() {
        val code = binding.etOtpCode.text.toString().trim()
        if (code.length < 4) {
            binding.etOtpCode.error = "Enter the OTP"
            return
        }
        setBusy(true)
        lifecycleScope.launch {
            val result = AuthApi.verifyOtp(verifiedPhone, code)
            setBusy(false)
            if (!result.ok) {
                binding.etOtpCode.error = AuthApi.friendlyError(result.error)
                toast(AuthApi.friendlyError(result.error))
                return@launch
            }
            grantToken = result.string("grantToken") ?: ""
            if (grantToken.isBlank()) {
                toast("Kuch galat ho gaya — dobara try karein")
                return@launch
            }
            resendTimer?.cancel()
            binding.layoutOtpSection.visibility = View.GONE
            binding.layoutPasswordSection.visibility = View.VISIBLE
            binding.tvSignUpTitle.text = "Set Password"
        }
    }

    private fun createAccount() {
        val password = binding.etPassword.text.toString()
        val confirm = binding.etConfirmPassword.text.toString()
        if (password.length < 6) {
            binding.etPassword.error = "Password kam se kam 6 characters ka rakhein"
            return
        }
        if (password != confirm) {
            binding.etConfirmPassword.error = "Dono password ek jaise nahi hain"
            return
        }
        setBusy(true)
        lifecycleScope.launch {
            val result = AuthApi.signup(verifiedPhone, grantToken, userName, password)
            setBusy(false)
            if (!result.ok) {
                toast(AuthApi.friendlyError(result.error))
                return@launch
            }
            val token = result.string("sessionToken")
            val serverPhone = result.string("phone") ?: verifiedPhone
            if (token == null) {
                toast("Kuch galat ho gaya — dobara try karein")
                return@launch
            }
            AuthSession.save(this@SignUpActivity, serverPhone, token, result.long("expiresAt"))
            toast("Account ban gaya hai!")
            // Profile (photo) pura karke home.
            startActivity(Intent(this@SignUpActivity, CreateProfileActivity::class.java).apply {
                putExtra("PHONE_NUMBER", serverPhone)
                putExtra("PREFILL_NAME", userName)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            })
            finish()
        }
    }

    private fun showFormStep() {
        resendTimer?.cancel()
        grantToken = ""
        binding.etOtpCode.text?.clear()
        binding.layoutOtpSection.visibility = View.GONE
        binding.layoutPasswordSection.visibility = View.GONE
        binding.layoutFormSection.visibility = View.VISIBLE
        binding.tvSignUpTitle.text = "Create Account"
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

    private fun setBusy(busy: Boolean) {
        binding.btnSendOtp.isEnabled = !busy
        binding.btnVerifyOtp.isEnabled = !busy
        binding.btnCreateAccount.isEnabled = !busy
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        resendTimer?.cancel()
        super.onDestroy()
    }
}
