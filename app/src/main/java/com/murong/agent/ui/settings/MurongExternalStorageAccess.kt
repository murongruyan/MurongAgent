package com.murong.agent.ui.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Shared-storage access used by the terminal and agent workspaces.
 *
 * Android 11+ intentionally treats this as a special user-controlled setting:
 * declaring MANAGE_EXTERNAL_STORAGE in the manifest is not enough to read
 * arbitrary files under /storage/emulated/0.
 */
internal object MurongExternalStorageAccess {
    fun hasAccess(context: Context): Boolean {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> Environment.isExternalStorageManager()
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.READ_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_GRANTED
            }
            else -> true
        }
    }

    fun permissionToRequest(): String? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            Build.VERSION.SDK_INT < Build.VERSION_CODES.R
        ) {
            Manifest.permission.READ_EXTERNAL_STORAGE
        } else {
            null
        }
    }

    fun settingsIntent(context: Context): Intent {
        val packageUri = Uri.parse("package:${context.packageName}")
        return Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, packageUri)
    }

    fun missingAccessSummary(): String =
        "未授予时 Android 可能仍显示目录，却会隐藏 /storage/emulated/0 中的 ZIP、IMG、SH 等普通文件。"
}
