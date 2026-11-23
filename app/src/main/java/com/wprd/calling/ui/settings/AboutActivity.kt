package com.wprd.calling.ui.settings

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.wprd.calling.BuildConfig
import com.wprd.calling.databinding.ActivityAboutBinding

class AboutActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAboutBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.tvAboutVersion.text = "Version ${BuildConfig.VERSION_NAME}"
    }
}
