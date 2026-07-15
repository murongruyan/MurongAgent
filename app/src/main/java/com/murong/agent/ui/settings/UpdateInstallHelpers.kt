package com.murong.agent.ui.settings

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment

data class PendingApkInstallDownload(
    val downloadId: Long,
    val title: String,
    val fileName: String
)

internal fun enqueueApkInstallDownload(
    context: Context,
    title: String,
    downloadUrl: String,
    fileName: String
): PendingApkInstallDownload {
    val safeFileName = sanitizeApkDownloadFileName(fileName)
    val request = DownloadManager.Request(Uri.parse(downloadUrl))
        .setTitle(title)
        .setDescription("下载完成后自动拉起安装")
        .setMimeType("application/vnd.android.package-archive")
        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        .setAllowedOverMetered(true)
        .setAllowedOverRoaming(true)
    runCatching {
        request.setDestinationInExternalPublicDir(
            Environment.DIRECTORY_DOWNLOADS,
            "MurongAgent/$safeFileName"
        )
    }.getOrElse {
        request.setDestinationInExternalFilesDir(
            context,
            Environment.DIRECTORY_DOWNLOADS,
            safeFileName
        )
    }
    val manager = context.getSystemService(DownloadManager::class.java)
        ?: error("系统下载服务不可用")
    val downloadId = manager.enqueue(request)
    return PendingApkInstallDownload(
        downloadId = downloadId,
        title = title,
        fileName = safeFileName
    )
}

internal fun openDownloadedApkInstaller(
    context: Context,
    downloadId: Long
): Result<Unit> {
    return runCatching {
        val manager = context.getSystemService(DownloadManager::class.java)
            ?: error("系统下载服务不可用")
        val uri = manager.getUriForDownloadedFile(downloadId)
            ?: resolveDownloadedFileUri(manager, downloadId)
            ?: error("找不到已下载的安装包")
        val installIntent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(installIntent)
    }
}

internal fun queryDownloadFailureReason(
    context: Context,
    downloadId: Long
): String? {
    val manager = context.getSystemService(DownloadManager::class.java) ?: return null
    val query = DownloadManager.Query().setFilterById(downloadId)
    manager.query(query)?.use { cursor ->
        if (!cursor.moveToFirst()) return null
        val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
        if (statusIndex < 0) return null
        val status = cursor.getInt(statusIndex)
        if (status != DownloadManager.STATUS_FAILED) return null
        val reasonIndex = cursor.getColumnIndex(DownloadManager.COLUMN_REASON)
        val reason = if (reasonIndex >= 0) cursor.getInt(reasonIndex) else 0
        return "下载失败，系统原因码 $reason"
    }
    return null
}

private fun resolveDownloadedFileUri(
    manager: DownloadManager,
    downloadId: Long
): Uri? {
    val query = DownloadManager.Query().setFilterById(downloadId)
    manager.query(query)?.use { cursor ->
        if (!cursor.moveToFirst()) return null
        val localUriIndex = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
        if (localUriIndex < 0) return null
        val localUri = cursor.getString(localUriIndex).orEmpty()
        if (localUri.isBlank()) return null
        return Uri.parse(localUri)
    }
    return null
}

private fun sanitizeApkDownloadFileName(rawName: String): String {
    val trimmed = rawName.trim().ifBlank { "murong-update.apk" }
    val sanitized = trimmed.replace(Regex("[^A-Za-z0-9._-]"), "-")
    return if (sanitized.lowercase().endsWith(".apk")) {
        sanitized
    } else {
        "$sanitized.apk"
    }
}
