package com.wprd.calling.ui.main

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.wprd.calling.data.local.ProfileSyncManager
import com.wprd.calling.ui.call.BaseCallActivity
import com.wprd.calling.ui.call.VideoCallActivity
import com.wprd.calling.ui.call.VoiceCallActivity
import java.util.UUID

object CallStarter {
    fun start(context: Context, row: CallListRow, callType: String) {
        if (!row.registered) {
            Toast.makeText(context, "${row.name} abhi WPRD par nahi hai", Toast.LENGTH_SHORT).show()
            return
        }
        val micOk = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val camOk = callType != "VIDEO" || ContextCompat.checkSelfPermission(
            context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (!micOk || !camOk) {
            Toast.makeText(context, "Call ke liye mic/camera permission chahiye", Toast.LENGTH_LONG).show()
            return
        }
        val myPhone = ProfileSyncManager.getLocalProfile(context, "")?.phone ?: ""
        if (ProfileSyncManager.normalizePhoneKey(myPhone) == row.uid) {
            Toast.makeText(context, "Khud ko call nahi kar sakte", Toast.LENGTH_SHORT).show()
            return
        }
        val target = if (callType == "VIDEO") VideoCallActivity::class.java else VoiceCallActivity::class.java
        val intent = Intent(context, target).apply {
            putExtra(BaseCallActivity.EXTRA_CALL_ID, UUID.randomUUID().toString())
            putExtra(BaseCallActivity.EXTRA_PEER_UID, row.uid)
            putExtra(BaseCallActivity.EXTRA_PEER_NAME, row.name)
            putExtra(BaseCallActivity.EXTRA_PEER_PHONE, row.phone)
            putExtra(BaseCallActivity.EXTRA_CALL_TYPE, callType)
            putExtra(BaseCallActivity.EXTRA_IS_CALLER, true)
        }
        context.startActivity(intent)
    }
}
