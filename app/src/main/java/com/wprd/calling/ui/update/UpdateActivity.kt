package com.wprd.calling.ui.update

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.wprd.calling.databinding.ActivityUpdateBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Mandatory update gate. Shown from SplashActivity when UpdateChecker has
 * confirmed a newer release exists on GitHub. There is deliberately NO way
 * past this screen except installing the update: back press is swallowed,
 * there is no skip/close button, and the activity is not finishable from
 * the UI. The user flow is:
 *   Download Update -> (progress) -> Install Update -> system installer.
 * If the install is cancelled the user lands back here with the Install
 * button still available. If the download itself fails, the button becomes
 * "Retry Download" (the gate stays, because the update is KNOWN to exist).
 */
class UpdateActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_APK_URL = "extra_apk_url"
        const val EXTRA_LATEST_VERSION = "extra_latest_version"
        private const val APK_FILE_NAME = "WPRD-update.apk"
        private const val REQ_UNKNOWN_SOURCES = 4711
    }

    private lateinit var binding: ActivityUpdateBinding
    private var apkUrl: String = ""
    private var downloadedFile: File? = null
    private var downloading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUpdateBinding.inflate(layoutInflater)
        setContentView(binding.root)

        apkUrl = intent.getStringExtra(EXTRA_APK_URL) ?: ""
        val latest = intent.getStringExtra(EXTRA_LATEST_VERSION) ?: ""
        if (latest.isNotBlank()) {
            binding.tvUpdateMessage.text =
                "WPRD v$latest is available. Please update to continue using the app."
        }

        // Mandatory gate: back does nothing here.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // No way back. The update must be installed to proceed.
            }
        })

        binding.btnInstallUpdate.setOnClickListener {
            val file = downloadedFile
            if (file != null && file.exists()) {
                installApk(file)
            } else if (!downloading) {
                downloadApk()
            }
        }
    }

    private fun downloadApk() {
        if (apkUrl.isBlank()) {
            Toast.makeText(this, "Update link missing. Please try again later.", Toast.LENGTH_LONG).show()
            return
        }
        downloading = true
        binding.btnInstallUpdate.isEnabled = false
        binding.btnInstallUpdate.text = "Downloading…"
        binding.progressUpdate.visibility = View.VISIBLE
        binding.progressUpdate.progress = 0
        binding.tvUpdateStatus.text = "Downloading update…"

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { downloadToFile(apkUrl) { pct ->
                runOnUiThread {
                    if (pct >= 0) {
                        binding.progressUpdate.progress = pct
                        binding.tvUpdateStatus.text = "Downloading update… $pct%"
                    }
                }
            } }
            downloading = false
            binding.btnInstallUpdate.isEnabled = true
            if (result != null) {
                downloadedFile = result
                binding.progressUpdate.progress = 100
                binding.tvUpdateStatus.text = "Download complete. Tap Install to finish the update."
                binding.btnInstallUpdate.text = "Install Update"
            } else {
                binding.progressUpdate.visibility = View.GONE
                binding.tvUpdateStatus.text =
                    "Download failed. Check your internet connection and try again."
                binding.btnInstallUpdate.text = "Retry Download"
            }
        }
    }

    private fun downloadToFile(url: String, onProgress: (Int) -> Unit): File? {
        return try {
            val dir = getExternalFilesDir(null) ?: cacheDir
            val out = File(dir, APK_FILE_NAME)
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 15000
                readTimeout = 30000
                setRequestProperty("User-Agent", "WPRD-Android")
            }
            if (conn.responseCode !in 200..299) return null
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                out.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var copied = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        if (total > 0) onProgress(((copied * 100) / total).toInt())
                    }
                }
            }
            if (out.length() > 0) out else null
        } catch (e: Exception) {
            null
        }
    }

    private fun installApk(file: File) {
        // Android 8+: the app needs the "install unknown apps" grant first.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !packageManager.canRequestPackageInstalls()
        ) {
            Toast.makeText(
                this,
                "Allow WPRD to install updates, then tap Install again.",
                Toast.LENGTH_LONG
            ).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:$packageName")
            )
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQ_UNKNOWN_SOURCES)
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_UNKNOWN_SOURCES) {
            // Returned from the unknown-sources settings page: if the grant
            // is now given, go straight to the installer.
            val file = downloadedFile
            if (file != null && file.exists() &&
                (Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
                    packageManager.canRequestPackageInstalls())
            ) {
                installApk(file)
            }
        }
    }
}
