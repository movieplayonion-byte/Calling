package com.wprd.calling.update

import android.content.Context
import android.content.pm.PackageManager
import com.wprd.calling.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Mandatory-update check against GitHub Releases.
 *
 * Version scheme (documented choice): the GitHub release TAG carries the
 * version, e.g. tag "v1.1.0" == versionName "1.1.0". We compare the numeric
 * dot-separated parts of the tag (leading "v" stripped) against the installed
 * app's versionName (BuildConfig.VERSION_NAME). versionCode is NOT used for
 * the comparison; it is only bumped alongside for Android bookkeeping.
 * The downloadable APK is the release asset whose name ends in ".apk"
 * (published as WPRD.apk); its browser_download_url is used for the
 * in-app download.
 *
 * Any failure (no internet, timeout, bad JSON, no APK asset) returns null,
 * which callers treat as "no update known" so offline users are never
 * blocked by a failed check.
 */
object UpdateChecker {

    data class UpdateInfo(
        val latestVersion: String,
        val apkUrl: String,
        val notes: String
    )

    private const val LATEST_RELEASE_URL =
        "https://api.github.com/repos/movieplayonion-byte/Calling/releases/latest"

    suspend fun check(context: Context): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val conn = (URL(LATEST_RELEASE_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "WPRD-Android")
            }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return@withContext null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val tag = json.optString("tag_name", "")
            val latest = tag.removePrefix("v").removePrefix("V").trim()
            if (latest.isEmpty()) return@withContext null

            val installed = installedVersionName(context)
            if (!isNewer(latest, installed)) return@withContext null

            val assets = json.optJSONArray("assets") ?: return@withContext null
            var apkUrl: String? = null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.optString("name", "")
                if (name.endsWith(".apk", ignoreCase = true)) {
                    apkUrl = asset.optString("browser_download_url", "")
                    break
                }
            }
            if (apkUrl.isNullOrBlank()) return@withContext null

            UpdateInfo(
                latestVersion = latest,
                apkUrl = apkUrl,
                notes = json.optString("body", "")
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun installedVersionName(context: Context): String {
        // BuildConfig is the compile-time copy; PackageManager is the ground
        // truth of what is actually installed. Prefer BuildConfig, fall back
        // to PackageManager.
        val fromBuild = BuildConfig.VERSION_NAME
        if (fromBuild.isNotBlank()) return fromBuild
        return try {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"
        } catch (e: PackageManager.NameNotFoundException) {
            "0"
        }
    }

    /** True if [candidate] (e.g. "1.2.0") is strictly newer than [current]. */
    fun isNewer(candidate: String, current: String): Boolean {
        val a = parseParts(candidate)
        val b = parseParts(current)
        val n = maxOf(a.size, b.size)
        for (i in 0 until n) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun parseParts(v: String): List<Int> =
        v.split(".", "-", "_").map { part -> part.filter { it.isDigit() }.toIntOrNull() ?: 0 }
}
