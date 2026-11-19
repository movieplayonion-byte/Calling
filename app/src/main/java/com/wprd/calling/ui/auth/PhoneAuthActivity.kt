package com.wprd.calling.ui.auth

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.wprd.calling.databinding.ActivityPhoneAuthBinding
import com.wprd.calling.util.AppPermissionsHelper

class PhoneAuthActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPhoneAuthBinding

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // Permissions requested on opening; user can continue smoothly
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPhoneAuthBinding.inflate(layoutInflater)
        setContentView(binding.root)

        AppPermissionsHelper.requestAllPermissions(permissionLauncher)

        binding.btnNextAuth.setOnClickListener {
            val rawInput = binding.etPhoneNumber.text.toString().trim()
            val digitsOnly = rawInput.filter { it.isDigit() }

            if (digitsOnly.length < 10) {
                binding.etPhoneNumber.error = "Enter a valid phone number"
                Toast.makeText(this, "Please enter a valid phone number", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (!binding.cbTermsConditions.isChecked) {
                Toast.makeText(
                    this,
                    "Please accept Terms & Conditions to continue",
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }

            val formattedPhone = if (rawInput.startsWith("+")) rawInput else "+91$digitsOnly"

            val intent = Intent(this, CreateProfileActivity::class.java).apply {
                putExtra("PHONE_NUMBER", formattedPhone)
                putExtra("AUTH_FLOW", "LOGIN_OR_SIGNUP")
            }
            startActivity(intent)
        }

        binding.tvGoToSignUp.setOnClickListener {
            if (!binding.cbTermsConditions.isChecked) {
                Toast.makeText(
                    this,
                    "Please accept Terms & Conditions first",
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }

            val rawInput = binding.etPhoneNumber.text.toString().trim()
            val digitsOnly = rawInput.filter { it.isDigit() }
            val formattedPhone = if (digitsOnly.length >= 10) {
                if (rawInput.startsWith("+")) rawInput else "+91$digitsOnly"
            } else {
                ""
            }

            val intent = Intent(this, CreateProfileActivity::class.java).apply {
                putExtra("PHONE_NUMBER", formattedPhone)
                putExtra("AUTH_FLOW", "SIGN_UP")
            }
            startActivity(intent)
        }
    }
}
