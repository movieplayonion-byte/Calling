package com.wprd.calling.data.local

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.tasks.await
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

data class SyncedProfile(
    val phone: String,
    val name: String,
    val photoBase64: String?
)

object ProfileSyncManager {

    private const val PREFS_NAME = "wprd_profile_sync"

    fun normalizePhoneKey(phone: String): String {
        val digits = phone.filter { it.isDigit() }
        return if (digits.isNotEmpty()) digits else "unknown_user"
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

    fun saveLocalProfile(context: Context, phone: String, name: String, photoBase64: String?) {
        val key = normalizePhoneKey(phone)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString("last_phone", phone)
            .putString("name_$key", name)
            .putString("phone_$key", phone)
            .putString("photo_$key", photoBase64 ?: "")
            .apply()

        val bitmap = base64ToBitmap(photoBase64)
        if (bitmap != null) {
            try {
                val file = File(context.filesDir, "avatar_$key.jpg")
                FileOutputStream(file).use { fos ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 85, fos)
                }
            } catch (_: Exception) {
            }
        }
    }

    /** Logout ke time local profile hata do, taaki session ke bina kuch na bache. */
    fun clearLocalProfile(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastPhone = prefs.getString("last_phone", "") ?: ""
        val key = normalizePhoneKey(lastPhone)
        try { File(context.filesDir, "avatar_$key.jpg").delete() } catch (_: Exception) {}
        prefs.edit().clear().apply()
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
        return SyncedProfile(savedPhone, name, photoBase64)
    }

    suspend fun saveAndSyncProfile(
        context: Context,
        phone: String,
        name: String,
        photoBase64: String?
    ): Boolean {
        saveLocalProfile(context, phone, name, photoBase64)
        val key = normalizePhoneKey(phone)
        return try {
            val ref = FirebaseDatabase.getInstance().reference
                .child("users")
                .child(key)

            val payload = hashMapOf<String, Any>(
                "phone" to phone,
                "name" to name,
                "photoBase64" to (photoBase64 ?: ""),
                "updatedAt" to System.currentTimeMillis()
            )
            ref.setValue(payload).await()
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
            val snap = FirebaseDatabase.getInstance().reference
                .child("users")
                .child(key)
                .get()
                .await()

            if (!snap.exists()) return local

            val remoteName = snap.child("name").getValue(String::class.java) ?: local?.name ?: ""
            val remotePhone = snap.child("phone").getValue(String::class.java) ?: phone
            val remotePhoto = snap.child("photoBase64").getValue(String::class.java)
                ?.takeIf { it.isNotBlank() } ?: local?.photoBase64

            saveLocalProfile(context, remotePhone, remoteName, remotePhoto)
            SyncedProfile(remotePhone, remoteName, remotePhoto)
        } catch (_: Exception) {
            local
        }
    }
}
