package com.wprd.calling.data.local

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

data class SyncedProfile(
    val phone: String,
    val name: String,
    val photoBase64: String?,
    val about: String = ""
)

object ProfileSyncManager {

    private const val PREFS_NAME = "wprd_profile_sync"

    fun normalizePhoneKey(phone: String): String {
        val digits = phone.filter { it.isDigit() }
        if (digits.isEmpty()) return "unknown_user"
        return when {
            // Plain 10-digit Indian mobile (e.g. a contact saved without a
            // country code) -> prefix 91 so it matches the registered key
            // (registered keys come from +91 numbers and are 12 digits).
            digits.length == 10 -> "91$digits"
            // Trunk-prefixed form 0XXXXXXXXXX (11 digits) -> drop the 0, prefix 91.
            digits.length == 11 && digits.startsWith("0") -> "91" + digits.substring(1)
            // Already international (e.g. 91XXXXXXXXXX) or any other
            // international number: leave as-is.
            else -> digits
        }
    }

    fun uriToCompressedBase64(context: Context, uri: Uri): String? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            val original = BitmapFactory.decodeStream(inputStream)
            inputStream.close()
            if (original == null) return null

            val scaled = Bitmap.createScaledBitmap(original, 320, 320, true)
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 75, out)
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } catch (_: Exception) {
            null
        }
    }

    fun base64ToBitmap(base64Str: String?): Bitmap? {
        if (base64Str.isNullOrBlank()) return null
        return try {
            val bytes = Base64.decode(base64Str, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (_: Exception) {
            null
        }
    }

    fun saveLocalProfile(
        context: Context, phone: String, name: String, photoBase64: String?,
        about: String? = null
    ) {
        val key = normalizePhoneKey(phone)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
            .putString("last_phone", phone)
            .putString("name_$key", name)
            .putString("phone_$key", phone)
            .putString("photo_$key", photoBase64 ?: "")
        if (about != null) editor.putString("about_$key", about)
        editor.apply()

        // Per-user avatar file (avatar_<phoneKey>.jpg): har user ki photo
        // sirf usi ke key par rehti hai — dusre account se kabhi mix nahi hoti.
        val avatarFile = File(context.filesDir, "avatar_$key.jpg")
        val bitmap = base64ToBitmap(photoBase64)
        if (bitmap != null) {
            try {
                FileOutputStream(avatarFile).use { fos ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 85, fos)
                }
            } catch (_: Exception) {
            }
        } else if (photoBase64.isNullOrBlank()) {
            // Photo hatayi gayi hai to purani file bhi hata do, warna stale
            // photo isi user ko dikhti rahegi.
            try { avatarFile.delete() } catch (_: Exception) {}
        }
    }

    /**
     * Logout ke time sirf session pointer (last_phone) hatao. Per-user keyed
     * stores (name_/photo_/about_<phoneKey> aur avatar_<phoneKey>.jpg) jaan
     * bujh kar rakhe jate hain: shared phone par dusre account ka data usi ke
     * key par surakshit rehta hai, aur kisi dusre user ko kabhi dikhta nahi
     * kyunki bina login ke last_phone se koi profile resolve nahi hota.
     */
    fun clearLocalProfile(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove("last_phone").apply()
    }

    fun getLocalProfile(context: Context, phone: String): SyncedProfile? {
        val targetPhone = if (phone.isNotBlank()) {
            phone
        } else {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString("last_phone", "") ?: ""
        }
        if (targetPhone.isBlank()) return null

        val key = normalizePhoneKey(targetPhone)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val name = prefs.getString("name_$key", "") ?: ""
        val savedPhone = prefs.getString("phone_$key", targetPhone) ?: targetPhone
        val about = prefs.getString("about_$key", "") ?: ""
        var photoBase64 = prefs.getString("photo_$key", "")

        if (photoBase64.isNullOrBlank()) {
            val file = File(context.filesDir, "avatar_$key.jpg")
            if (file.exists()) {
                val bmp = BitmapFactory.decodeFile(file.absolutePath)
                if (bmp != null) {
                    val out = ByteArrayOutputStream()
                    bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
                    photoBase64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                }
            }
        }

        if (name.isBlank() && photoBase64.isNullOrBlank()) return null
        return SyncedProfile(savedPhone, name, photoBase64, about)
    }

    suspend fun saveAndSyncProfile(
        context: Context,
        phone: String,
        name: String,
        photoBase64: String?,
        about: String = ""
    ): Boolean {
        saveLocalProfile(context, phone, name, photoBase64, about)
        val key = normalizePhoneKey(phone)
        return try {
            val ref = FirebaseDatabase.getInstance().reference
                .child("users")
                .child(key)

            val payload = hashMapOf<String, Any>(
                "phone" to phone,
                "name" to name,
                "photoBase64" to (photoBase64 ?: ""),
                "about" to about,
                "updatedAt" to System.currentTimeMillis()
            )
            // Timeout zaroori hai: database unreachable ho to await hamesha ke liye
            // atak sakta hai; local save ho chuka hai, user ko block mat karo.
            withTimeoutOrNull(10_000) { ref.setValue(payload).await() }
            true
        } catch (_: Exception) {
            // Local save already succeeded so user is not blocked if offline/rules restricted
            true
        }
    }

    suspend fun fetchProfileByPhone(context: Context, phone: String): SyncedProfile? {
        val local = getLocalProfile(context, phone)
        val key = normalizePhoneKey(phone)
        return try {
            // Timeout ke bina ye call database na hone par hamesha ke liye atak sakti hai.
            val snap = withTimeoutOrNull(8_000) {
                FirebaseDatabase.getInstance().reference
                    .child("users")
                    .child(key)
                    .get()
                    .await()
            } ?: return local

            if (!snap.exists()) return local

            val remoteName = snap.child("name").getValue(String::class.java) ?: local?.name ?: ""
            val remotePhone = snap.child("phone").getValue(String::class.java) ?: phone
            val remotePhoto = snap.child("photoBase64").getValue(String::class.java)
                ?.takeIf { it.isNotBlank() } ?: local?.photoBase64
            val remoteAbout = snap.child("about").getValue(String::class.java)
                ?: local?.about ?: ""

            saveLocalProfile(context, remotePhone, remoteName, remotePhoto, remoteAbout)
            SyncedProfile(remotePhone, remoteName, remotePhoto, remoteAbout)
        } catch (_: Exception) {
            local
        }
    }
}
