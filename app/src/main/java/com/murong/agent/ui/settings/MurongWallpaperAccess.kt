package com.murong.agent.ui.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build

internal object MurongWallpaperAccess {
    fun hasAccess(context: Context): Boolean = MurongExternalStorageAccess.hasAccess(context)

    fun permissionToRequest(): String? = MurongExternalStorageAccess.permissionToRequest()

    fun allFilesAccessIntent(context: Context): Intent = MurongExternalStorageAccess.settingsIntent(context)

    fun explanation(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            "Android 11 及以上在壁纸/自定义背景图模式下会先检查所有文件访问权限；未授权时无法完整读取和切换背景资源。"
        } else {
            "当前系统需要先授予图片读取权限，才能切换壁纸或自定义背景图。"
        }
    }
}

internal tailrec fun Context.findActivity(): Activity? {
    return when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}
