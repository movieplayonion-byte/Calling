package com.wprd.calling.util

import android.Manifest
import android.os.Build
import androidx.activity.result.ActivityResultLauncher

object AppPermissionsHelper {

    fun requiredPermissions(): Array<String> {
        val list = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.READ_MEDIA_IMAGES)
            list.add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            list.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        return list.toTypedArray()
    }

    fun requestAllPermissions(launcher: ActivityResultLauncher<Array<String>>) {
        launcher.launch(requiredPermissions())
    }
}
