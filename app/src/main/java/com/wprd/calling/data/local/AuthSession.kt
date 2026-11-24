package com.wprd.calling.data.local

import android.content.Context

/**
 * v1.2.0 ka login session: hamare apne server (MSG91 OTP + password)
 * se mila session token. FirebaseAuth ab session ka source NAHI hai —
 * Splash/MainActivity ke gates aur logout yahin se chalte hain.
 *
 * Token ke saath phone aur expiry rakhi jati hai; per-user profile data
 * pehle ki tarah ProfileSyncManager ke per-phone keys par rehta hai.
 */
object AuthSession {

    private const val PREFS_NAME = "wprd_auth"
    private const val KEY_TOKEN = "session_token"
    private const val KEY_PHONE = "session_phone"
    private const val KEY_EXPIRES_AT = "session_expires_at"

    fun save(context: Context, phone: String, token: String, expiresAt: Long) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TOKEN, token)
            .putString(KEY_PHONE, phone)
            .putLong(KEY_EXPIRES_AT, expiresAt)
            .apply()
    }

    fun token(context: Context): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() }

    fun phone(context: Context): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PHONE, null)?.takeIf { it.isNotBlank() }

    fun expiresAt(context: Context): Long =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_EXPIRES_AT, 0L)

    /** Local check: token hai aur expire nahi hua. Server session ko
     *  logout/reset par revoke kar sakta hai; aise me agli server call
     *  fail hogi aur user ko dobara login karna hoga. */
    fun isLoggedIn(context: Context): Boolean =
        token(context) != null && expiresAt(context) > System.currentTimeMillis()

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().apply()
    }
}
