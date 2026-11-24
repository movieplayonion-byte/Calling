package com.wprd.calling.ui.auth

import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.wprd.calling.data.local.AuthSession
import com.wprd.calling.data.local.ProfileSyncManager
import com.wprd.calling.data.remote.AuthApi
import com.wprd.calling.databinding.ActivityPhoneAuthBinding
import com.wprd.calling.ui.main.MainActivity
import com.wprd.calling.util.AppPermissionsHelper
import kotlinx.coroutines.launch

/**
 * v1.2.0 LOGIN screen (purana Firebase Phone OTP login hata diya gaya):
 * roz ka login = number + password, bina OTP ke. Password bhool jane par
 * hi OTP aata hai (MSG91 SMS, hamare server ke through, rate-limited) —
 * usi flow ke steps isi screen par hain (OTP section, phir naya password
 * section). Sign Up alag screen (SignUpActivity) par hai.
 */
class PhoneAuthActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SHOW_MESSAGE = "SHOW_MESSAGE"
    }

    private lateinit var binding: ActivityPhoneAuthBinding

    private var resetPhone: String = ""
    private var grantToken: String = ""
    private var resendTimer: CountDownTimer? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPhoneAuthBinding.inflate(layoutInflater)
        setContentView(binding.root)

        AppPermissionsHelper.requestAllPermissions(permissionLauncher)

        intent.getStringExtra(EXTRA_SHOW_MESSAGE)?.takeIf { it.isNotBlank() }?.let { msg ->
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            binding.tvAuthSubtitle.text = msg
        }

        binding.btnLogin.setOnClickListener { startLogin() }
        binding.tvForgotPassword.setOnClickListener { startForgotPassword() }
        binding.tvGoToSignUp.setOnClickListener {
            startActivity(Intent(this, SignUpActivity::class.java))
        }
        binding.btnVerifyOtp.setOnClickListener { verifyResetOtp() }
        binding.tvResendOtp.setOnClickListener {
            if (binding.tvResendOtp.isEnabled) sendResetOtp()
        }
        binding.tvBackToLogin.setOnClickListener { showLoginStep() }
        binding.btnResetPassword.setOnClickListener { submitNewPassword() }
    }

    // ------------------------------------------------------------- login

    private fun startLogin() {
        val phone = normalizedInputPhone() ?: return
        val password = binding.etPassword.text.toString()
        if (password.length < 6) {
            binding.etPassword.error = "Password kam se kam 6 characters ka hai"
            return
        }
        if (!binding.cbTermsConditions.isChecked) {
            Toast.makeText(this, "Please accept Terms & Conditions to continue", Toast.LENGTH_SHORT).show()
            return
        }
        setBusy(true)
        lifecycleScope.launch {
            val result = AuthApi.login(phone, password)
            setBusy(false)
            if (!result.ok) {
                toast(AuthApi.friendlyError(result.error))
                return@launch
            }
            val token = result.string("sessionToken")
            val serverPhone = result.string("phone") ?: phone
            if (token == null) {
                toast("Kuch galat ho gaya — dobara try karein")
                return@launch
            }
            AuthSession.save(this@PhoneAuthActivity, serverPhone, token, result.long("expiresAt"))
            routeAfterLogin(serverPhone)
        }
    }

    /** Profile hai to seedha home; nahi hai to pehle profile pura karo. */
    private fun routeAfterLogin(phone: String) {
        lifecycleScope.launch {
            val existing = ProfileSyncManager.fetchProfileByPhone(this@PhoneAuthActivity, phone)
            if (existing != null && existing.name.isNotBlank()) {
                ProfileSyncManager.saveLocalProfile(
                    this@PhoneAuthActivity, phone, existing.name, existing.photoBase64
                )
                startActivity(Intent(this@PhoneAuthActivity, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                })
            } else {
                startActivity(Intent(this@PhoneAuthActivity, CreateProfileActivity::class.java).apply {
                    putExtra("PHONE_NUMBER", phone)
                })
            }
            finish()
        }
    }

    // --------------------------------------------------- forgot password

    private fun startForgotPassword() {
        val phone = normalizedInputPhone() ?: return
        resetPhone = phone
        sendResetOtp()
    }

    private fun sendResetOtp() {
        setBusy(true)
        lifecycleScope.launch {
            val result = AuthApi.sendOtp(resetPhone, "reset")
            setBusy(false)
            if (!result.ok) {
                toast(AuthApi.friendlyError(result.error))
                return@launch
            }
            binding.layoutLoginSection.visibility = View.GONE
            binding.layoutResetSection.visibility = View.GONE
            binding.layoutOtpSection.visibility = View.VISIBLE
            binding.tvAuthTitle.text = "Enter OTP"
            binding.tvOtpInfo.text = "Enter the OTP sent to $resetPhone"
            startResendTimer()
            toast("OTP bhej diya gaya hai")
        }
    }

    private fun verifyResetOtp() {
        val code = binding.etOtpCode.text.toString().trim()
        if (code.length < 4) {
            binding.etOtpCode.error = "Enter the OTP"
            return
        }
        setBusy(true)
        lifecycleScope.launch {
            val result = AuthApi.verifyOtp(resetPhone, code)
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
            binding.layoutResetSection.visibility = View.VISIBLE
            binding.tvAuthTitle.text = "Set New Password"
        }
    }

    private fun submitNewPassword() {
        val newPassword = binding.etNewPassword.text.toString()
        val confirm = binding.etConfirmNewPassword.text.toString()
        if (newPassword.length < 6) {
            binding.etNewPassword.error = "Password kam se kam 6 characters ka rakhein"
            return
        }
        if (newPassword != confirm) {
            binding.etConfirmNewPassword.error = "Dono password ek jaise nahi hain"
            return
        }
        setBusy(true)
        lifecycleScope.launch {
            val result = AuthApi.resetPassword(resetPhone, grantToken, newPassword)
            setBusy(false)
            if (!result.ok) {
                toast(AuthApi.friendlyError(result.error))
                return@launch
            }
            toast("Password badal gaya hai — ab naye password se login karein")
            showLoginStep()
        }
    }

    private fun showLoginStep() {
        resendTimer?.cancel()
        grantToken = ""
        binding.etOtpCode.text?.clear()
        binding.layoutOtpSection.visibility = View.GONE
        binding.layoutResetSection.visibility = View.GONE
        binding.layoutLoginSection.visibility = View.VISIBLE
        binding.tvAuthTitle.text = "Welcome Back"
    }

    // ------------------------------------------------------------ helpers

    /** Phone input ko +91 wale pure roop me; galat ho to error dikhakar null. */
    private fun normalizedInputPhone(): String? {
        val rawInput = binding.etPhoneNumber.text.toString().trim()
        val digitsOnly = rawInput.filter { it.isDigit() }
        if (digitsOnly.length < 10) {
            binding.etPhoneNumber.error = "Enter a valid phone number"
            toast("Please enter a valid phone number")
            return null
        }
        return when {
            rawInput.startsWith("+") -> "+" + digitsOnly
            digitsOnly.length > 10 -> "+" + digitsOnly
            else -> "+91" + digitsOnly.takeLast(10)
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

    private fun setBusy(busy: Boolean) {
        binding.btnLogin.isEnabled = !busy
        binding.btnVerifyOtp.isEnabled = !busy
        binding.btnResetPassword.isEnabled = !busy
        binding.btnLogin.text = if (busy) "Please wait…" else "Login"
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        resendTimer?.cancel()
        super.onDestroy()
    }
}
