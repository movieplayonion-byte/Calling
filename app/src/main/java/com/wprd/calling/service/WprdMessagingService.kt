package com.wprd.calling.service

import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.wprd.calling.R
import com.wprd.calling.data.local.BlockedUsersManager
import com.wprd.calling.data.local.ProfileSyncManager
import com.wprd.calling.data.remote.FirebaseRepository
import com.wprd.calling.ui.call.IncomingCallActivity

/**
 * FCM wake-up for incoming calls (WhatsApp-style).
 *
 * The sender (see server/ in the repo) sends a high-priority *data* message:
 *   type=incoming_call, callId, callerName, callerPhone, callType (VOICE/VIDEO)
 * to the callee's token stored at users/<uid>/fcmToken.
 *
 * We answer with a high-importance notification on the "Incoming calls"
 * channel (default ringtone) carrying a full-screen intent that opens
 * IncomingCallActivity — so the phone rings even when the app is closed.
 * On Android 14 the full-screen intent needs the USE_FULL_SCREEN_INTENT
 * grant (MainActivity asks once); without it the notification still shows
 * as a heads-up. When the app is already in the foreground, MainActivity's
 * RTDB listener handles the ring instead and this service stays quiet.
 */
class WprdMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        val profile = ProfileSyncManager.getLocalProfile(this, "") ?: return
        val uid = ProfileSyncManager.normalizePhoneKey(profile.phone)
        FirebaseRepository().saveFcmToken(uid, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val data = message.data
        val type = data["type"] ?: return
        if (type != "incoming_call" && type != "INCOMING_CALL") return

        val callId = data["callId"] ?: return
        val callerName = data["callerName"] ?: "Unknown"
        val callerPhone = data["callerPhone"] ?: ""
        val callType = data["callType"] ?: "VOICE"

        // Blocked caller? Ghanti hi nahi bajegi.
        val myUid = ProfileSyncManager.normalizePhoneKey(
            ProfileSyncManager.getLocalProfile(this, "")?.phone ?: ""
        )
        val callerUid = data["callerUid"]?.takeIf { it.isNotBlank() }
            ?: ProfileSyncManager.normalizePhoneKey(callerPhone)
        if (myUid.isNotBlank() &&
            BlockedUsersManager.isBlockedLocal(this, myUid, callerUid)
        ) return

        // App pehle se khula hai to RTDB listener ring karega — doहरी ghanti nahi.
        if (isAppInForeground()) return

        val intent = Intent(this, IncomingCallActivity::class.java).apply {
            putExtra(IncomingCallActivity.EXTRA_CALL_ID, callId)
            putExtra(IncomingCallActivity.EXTRA_CALLER_NAME, callerName)
            putExtra(IncomingCallActivity.EXTRA_CALLER_PHONE, callerPhone)
            putExtra(IncomingCallActivity.EXTRA_CALL_TYPE, callType)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            this, callId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val manager = getSystemService(NotificationManager::class.java)
        ensureCallChannel(manager)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_call)
            .setContentTitle(callerName)
            .setContentText(if (callType == "VIDEO") "Incoming video call" else "Incoming voice call")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(pending, true)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        manager.notify(callId.hashCode(), notification)
    }

    private fun ensureCallChannel(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        // Purana (bina awaaz wala) channel hata do — channel ki settings
        // banne ke baad badalti nahi, isliye naya channel id use hota hai.
        manager.deleteNotificationChannel(LEGACY_CHANNEL_ID)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        val audioAttrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val channel = NotificationChannel(
            CHANNEL_ID, "Incoming calls", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Voice aur video calls ki ghanti"
            setSound(ringtoneUri, audioAttrs)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 800, 600, 800, 600)
        }
        manager.createNotificationChannel(channel)
    }

    private fun isAppInForeground(): Boolean {
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val processes = activityManager.runningAppProcesses ?: return false
        return processes.any {
            it.processName == packageName &&
                it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        }
    }

    companion object {
        private const val CHANNEL_ID = "wprd_incoming_calls_v2"
        private const val LEGACY_CHANNEL_ID = "wprd_incoming_calls"
    }
}
