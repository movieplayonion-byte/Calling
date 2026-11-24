package com.wprd.calling.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Hamare server ke auth endpoints ka client (koi third-party HTTP
 * library nahi — seedha HttpURLConnection). OTP MSG91 se server ke
 * through jata hai; app kabhi MSG91 se seedha baat nahi karti.
 *
 * Har call [Result] deta hai: success par pura JSON, failure par
 * server ka error code ([Result.error]) jise [friendlyError] user ke
 * samajhne layak sandesh me badalta hai.
 */
object AuthApi {

    class Result(
        val ok: Boolean,
        val json: JSONObject?,
        val error: String?,
        val httpCode: Int
    ) {
        fun string(key: String): String? = json?.optString(key)?.takeIf { it.isNotBlank() }
        fun long(key: String): Long = json?.optLong(key, 0L) ?: 0L
    }

    private suspend fun post(path: String, body: JSONObject): Result =
        withContext(Dispatchers.IO) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(AuthConfig.SERVER_BASE_URL + path).openConnection()
                    as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 15_000
                    readTimeout = 20_000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
                val json = try {
                    JSONObject(text)
                } catch (_: Exception) {
                    null
                }
                Result(
                    ok = code in 200..299 && json?.optBoolean("ok") == true,
                    json = json,
                    error = json?.optString("error")?.takeIf { it.isNotBlank() },
                    httpCode = code
                )
            } catch (e: Exception) {
                Result(false, null, "network", -1)
            } finally {
                conn?.disconnect()
            }
        }

    suspend fun sendOtp(phone: String, purpose: String): Result =
        post("/auth/otp/send", JSONObject().put("phone", phone).put("purpose", purpose))

    suspend fun verifyOtp(phone: String, otp: String): Result =
        post("/auth/otp/verify", JSONObject().put("phone", phone).put("otp", otp))

    suspend fun signup(phone: String, grantToken: String, name: String, password: String): Result =
        post(
            "/auth/signup", JSONObject()
                .put("phone", phone)
                .put("grantToken", grantToken)
                .put("name", name)
                .put("password", password)
        )

    suspend fun login(phone: String, password: String): Result =
        post("/auth/login", JSONObject().put("phone", phone).put("password", password))

    suspend fun resetPassword(phone: String, grantToken: String, newPassword: String): Result =
        post(
            "/auth/reset", JSONObject()
                .put("phone", phone)
                .put("grantToken", grantToken)
                .put("newPassword", newPassword)
        )

    /** Best-effort: fail ho jaye to bhi local logout rukta nahi. */
    suspend fun logout(sessionToken: String) {
        try {
            post("/auth/logout", JSONObject().put("sessionToken", sessionToken))
        } catch (_: Exception) {
        }
    }

    /** Server error code -> user ko dikhane wala sandesh. */
    fun friendlyError(error: String?): String = when (error) {
        "account_exists" -> "Is number se account pehle se hai — login karein"
        "invalid_credentials" -> "Number ya password galat hai"
        "otp_not_configured" -> "OTP service abhi taiyaar nahi hai (server setup baki hai)"
        "cooldown" -> "Thoda intezaar karke dobara OTP bhejein"
        "daily_limit" -> "Aaj ke OTP ki limit khatm ho gayi hai — kal try karein"
        "invalid_otp" -> "OTP galat hai, dobara check karein"
        "otp_expired" -> "OTP expire ho gaya hai — naya OTP bhejein"
        "too_many_attempts" -> "Bahut zyada galat koshish — naya OTP bhejein"
        "no_otp_requested" -> "Pehle OTP bhejein"
        "otp_required" -> "OTP verification zaroori hai — dobara OTP bhejein"
        "locked" -> "Bahut zyada galat login koshish — kuch der baad try karein"
        "weak_password" -> "Password kam se kam 6 characters ka rakhein"
        "no_account" -> "Is number se koi account nahi hai — pehle Sign Up karein"
        "invalid_phone", "invalid_request" -> "Number sahi se dalen"
        "network" -> "Server se sampark nahi ho paya — network check karke dobara try karein"
        else -> "Kuch galat ho gaya — dobara try karein"
    }
}
