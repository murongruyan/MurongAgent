package com.murong.agent.ui.settings

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import com.murong.agent.core.tool.AndroidSystemExecution
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI

internal suspend fun openAuthUriInFloatingWindow(context: Context, rawUri: String) {
    val normalizedUri = normalizeAuthBrowserUri(rawUri) ?: return
    val command = buildAuthBrowserFloatingWindowCommand(
        rawUri = normalizedUri,
        manufacturer = Build.MANUFACTURER,
    )
    val shellOpened = if (AndroidSystemExecution.isSystemCommandAvailable()) {
        withContext(Dispatchers.IO) {
            runCatching {
                AndroidSystemExecution.executeSystemCommand(command, timeoutSeconds = 12)
            }.getOrNull()?.let(::authBrowserCommandStarted) == true
        }
    } else {
        false
    }
    if (shellOpened) return

    val uri = Uri.parse(normalizedUri)
    val metrics = context.resources.displayMetrics
    val width = (metrics.widthPixels * 0.88f).toInt().coerceAtLeast(1)
    val height = (metrics.heightPixels * 0.78f).toInt().coerceAtLeast(1)
    val left = ((metrics.widthPixels - width) / 2).coerceAtLeast(0)
    val top = ((metrics.heightPixels - height) / 2).coerceAtLeast(0)
    val options = ActivityOptions.makeBasic().apply {
        setLaunchBounds(Rect(left, top, left + width, top + height))
    }
    val floatingIntent = Intent(Intent.ACTION_VIEW, uri).addFlags(
        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK,
    )
    if (runCatching { context.startActivity(floatingIntent, options.toBundle()) }.isSuccess) return
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

internal fun buildAuthBrowserFloatingWindowCommand(
    rawUri: String,
    manufacturer: String,
): String {
    val normalizedUri = checkNotNull(normalizeAuthBrowserUri(rawUri)) { "授权网址无效" }
    val windowingMode = when (manufacturer.trim().lowercase()) {
        "oppo", "realme", "oneplus" -> 100
        else -> 5
    }
    // `am` launched through realme's Root shell rejects --activity-new-task even though
    // the adb shell parser accepts it. MULTIPLE_TASK is supported there and the explicit
    // VIEW launch already creates a task for the resolved browser activity.
    // The app-owned root shell prepends its Termux-compatible toolchain to PATH. Its
    // `am` wrapper intentionally supports only the portable subset and rejects
    // --windowingMode, so use Android's platform launcher explicitly here.
    return "/system/bin/am start -W --windowingMode $windowingMode --activity-multiple-task " +
        "-a android.intent.action.VIEW -d ${shellSingleQuote(normalizedUri)}"
}

internal fun shellSingleQuote(value: String): String = "'${value.replace("'", "'\"'\"'")}'"

internal fun authBrowserCommandStarted(output: String): Boolean {
    val normalized = output.trim()
    if (normalized.isEmpty()) return false
    if (normalized.lineSequence().any {
            it.trimStart().startsWith("Error:", ignoreCase = true) ||
                it.contains("Exception", ignoreCase = true)
        }
    ) {
        return false
    }
    return normalized.contains("Status: ok", ignoreCase = true) ||
        normalized.contains("Starting: Intent", ignoreCase = true) ||
        normalized.contains("Activity:", ignoreCase = true)
}

private fun normalizeAuthBrowserUri(rawUri: String): String? {
    val normalized = rawUri.trim()
    if (normalized.isEmpty()) return null
    val uri = runCatching { URI(normalized) }.getOrNull() ?: return null
    if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrBlank()) return null
    return normalized
}
