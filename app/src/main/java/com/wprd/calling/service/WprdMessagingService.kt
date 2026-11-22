package com.wprd.calling.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.wprd.calling.R
import com.wprd.calling.data.local.ProfileSyncManager
import com.wprd.calling.data.remote.FirebaseRepository
import com.wprd.calling.ui.call.IncomingCallActivity

/**
 * FCM wake-up for incoming calls.
 *
 * The backend (or a Cloud Function watching calls/<id>) must send a
 * high-priority *data* message with:
 *   type=INCOMING_CALL, callId, callerName, callerPhone, callType (VOICE/VIDEO)
 * to the callee's token stored at users/<uid>/fcmToken.
 * When the app is in foreground, MainActivity's RTDB listener handles it instead.
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
        if (data["type"] != "INCOMING_CALL") return

        val callId = data["callId"] ?: return
        val callerName = data["callerName"] ?: "Unknown"
        val callerPhone = data["callerPhone"] ?: ""
        val callType = data["callType"] ?: "VOICE"

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

        val channelId = "wprd_incoming_calls"
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(channelId, "Incoming calls", NotificationManager.IMPORTANCE_HIGH)
                    .apply { setSound(null, null) }
            )
        }
        val notification = NotificationCompat.Builder(this, channelId)
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
        // Also bring the ringing screen up directly when allowed.
        startActivity(intent)
    }
}
