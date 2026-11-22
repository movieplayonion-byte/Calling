package com.wprd.calling.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.wprd.calling.R
import com.wprd.calling.ui.call.VideoCallActivity
import com.wprd.calling.ui.call.VoiceCallActivity

/** Keeps mic/camera alive while a call is in progress (foreground service). */
class CallForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val peerName = intent?.getStringExtra(EXTRA_PEER_NAME) ?: "WPRD Call"
        val isVideo = intent?.getBooleanExtra(EXTRA_IS_VIDEO, false) ?: false
        val notification = buildNotification(peerName, isVideo)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_STICKY
    }

    private fun buildNotification(peerName: String, isVideo: Boolean): Notification {
        val channelId = "wprd_ongoing_call"
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(channelId, "Ongoing call", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val target = if (isVideo) VideoCallActivity::class.java else VoiceCallActivity::class.java
        val pending = PendingIntent.getActivity(
            this, 0,
            Intent(this, target).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_call)
            .setContentTitle(if (isVideo) "Video call in progress" else "Voice call in progress")
            .setContentText(peerName)
            .setContentIntent(pending)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val EXTRA_PEER_NAME = "peer_name"
        private const val EXTRA_IS_VIDEO = "is_video"

        fun start(context: Context, peerName: String, isVideo: Boolean) {
            val intent = Intent(context, CallForegroundService::class.java).apply {
                putExtra(EXTRA_PEER_NAME, peerName)
                putExtra(EXTRA_IS_VIDEO, isVideo)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CallForegroundService::class.java))
        }
    }
}
