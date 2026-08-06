package com.murong.agent.shizuku

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import com.murong.agent.core.tool.GuiScreenshot
import com.murong.agent.core.tool.PhoneAgentIsolatedDisplayInfo
import com.murong.agent.core.tool.PhoneAgentIsolatedDisplaySession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext

/** Shizuku-backed isolated display. The privileged service owns every Android display object. */
class ShizukuPhoneAgentIsolatedDisplaySession(
    context: Context,
) : PhoneAgentIsolatedDisplaySession {
    private val appContext = context.applicationContext
    @Volatile private var info: PhoneAgentIsolatedDisplayInfo? = null
    @Volatile private var boundService: IShizukuCommandService? = null

    override fun isAvailable(): Boolean = ShizukuSystemAccess.isAgentDisplayAvailable()

    override suspend fun start(): PhoneAgentIsolatedDisplayInfo = withContext(Dispatchers.IO) {
        info?.let { return@withContext it }
        val service = requireService()
        val metrics = appContext.resources.displayMetrics
        val displayId = service.ensureAgentDisplay(
            metrics.widthPixels,
            metrics.heightPixels,
            metrics.densityDpi,
        )
        check(displayId >= 0) { "Privileged service returned an invalid virtual display id" }
        boundService = service
        ShizukuSystemAccess.rememberAgentDisplayOwner(service)
        PhoneAgentIsolatedDisplayInfo(
            displayId = displayId,
            width = metrics.widthPixels,
            height = metrics.heightPixels,
        ).also { info = it }
    }

    override suspend fun currentPackageName(): String? = withContext(Dispatchers.IO) {
        requireService()
            .currentAgentDisplayPackageName(requireInfo().displayId)
            .trim()
            .takeIf(String::isNotBlank)
    }

    override suspend fun launchPackage(packageName: String): Boolean = withContext(Dispatchers.IO) {
        requireService().launchPackageOnAgentDisplay(packageName, requireInfo().displayId)
    }

    override suspend fun launchViewUri(
        packageName: String,
        uri: String,
    ): Boolean = withContext(Dispatchers.IO) {
        require(packageName.matches(Regex("[A-Za-z0-9._]+"))) { "Invalid package name" }
        require(uri.matches(Regex("[A-Za-z0-9:/?&=._%+~-]+"))) { "Invalid view URI" }
        val service = requireService()
        val displayId = requireInfo().displayId
        // PendingIntent establishes ownership/task metadata for later handoff. On a cold Douyin
        // process it may start the resolver without delivering the custom URI, while Android's
        // ActivityManager path reliably delivers the same package-scoped VIEW intent. Use both;
        // the latter is also exactly what an ADB user would invoke for this virtual display.
        val binderLaunched = service.launchViewUriOnAgentDisplay(
            packageName,
            uri,
            displayId,
        )
        val output = service.execute(
            "am start -W --display $displayId -a android.intent.action.VIEW " +
                "-d ${uri.shellSingleQuoted()} -p ${packageName.shellSingleQuoted()}",
            12,
        )
        val activityManagerLaunched = output.contains("__RSNX_EXIT_CODE__0") &&
            !output.contains("Error:", ignoreCase = true) &&
            !output.contains("Exception", ignoreCase = true)
        Log.i(
            VIEW_LOG_TAG,
            "launchView display=$displayId binder=$binderLaunched am=$activityManagerLaunched " +
                "uid=${runCatching { service.remoteUid() }.getOrDefault(-1)} " +
                "output=${output.replace(Regex("\\s+"), " ").take(300)}",
        )
        binderLaunched || activityManagerLaunched
    }

    override suspend fun launchShareText(
        packageName: String,
        text: String,
    ): Boolean = withContext(Dispatchers.IO) {
        requireService().launchShareTextOnAgentDisplay(
            packageName,
            text,
            requireInfo().displayId,
        )
    }

    override suspend fun captureScreenshot(): GuiScreenshot = withContext(Dispatchers.IO) {
        val display = requireInfo()
        val bytes = requireService().captureAgentDisplayJpeg(
            display.displayId,
            maxOf(display.width, display.height).coerceAtMost(SCREENSHOT_MAX_EDGE),
            SCREENSHOT_JPEG_QUALITY,
        )
        check(bytes.isNotEmpty()) { "Isolated display returned an empty screenshot" }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        check(options.outWidth > 0 && options.outHeight > 0) {
            "Isolated display returned an invalid JPEG"
        }
        GuiScreenshot(
            mimeType = "image/jpeg",
            base64Data = Base64.encodeToString(bytes, Base64.NO_WRAP),
            width = options.outWidth,
            height = options.outHeight,
        )
    }

    override suspend fun tap(x: Int, y: Int): Boolean = withContext(Dispatchers.IO) {
        requireService().tapAgentDisplay(requireInfo().displayId, x, y)
    }

    override suspend fun swipe(
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        durationMs: Int,
    ): Boolean = withContext(Dispatchers.IO) {
        requireService().swipeAgentDisplay(
            requireInfo().displayId,
            x1,
            y1,
            x2,
            y2,
            durationMs,
        )
    }

    override suspend fun key(keyCode: String): Boolean = withContext(Dispatchers.IO) {
        requireService().keyAgentDisplay(requireInfo().displayId, keyCode)
    }

    override suspend fun typeText(text: String): Boolean = withContext(Dispatchers.IO) {
        requireService().typeAgentDisplay(requireInfo().displayId, text)
    }

    override suspend fun close() = withContext(Dispatchers.IO) {
        val service = boundService
        if (service != null) {
            // A vendor display teardown can block in Binder while SurfaceFlinger drains the last
            // buffer. It must not stall the Phone Agent fallback path indefinitely.
            val releaseScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val release = releaseScope.async {
                runCatching { service.releaseAgentDisplay() }
            }
            val completed = withTimeoutOrNull(DISPLAY_RELEASE_TIMEOUT_MILLIS) {
                release.await()
                true
            } == true
            if (completed) {
                releaseScope.cancel()
            } else {
                release.invokeOnCompletion { releaseScope.cancel() }
            }
        }
        if (service != null) ShizukuSystemAccess.clearAgentDisplayOwner(service)
        boundService = null
        info = null
    }

    private fun requireInfo(): PhoneAgentIsolatedDisplayInfo =
        info ?: error("Isolated display session has not started")

    private fun requireService(): IShizukuCommandService =
        boundService?.takeIf { it.asBinder().isBinderAlive }
            ?: ShizukuSystemAccess.agentDisplayService()
            ?: error("Root/Shizuku Agent Display service is unavailable")

    private companion object {
        // 1000 px lost small storefront labels, while a native 3136 px JPEG serialized capture,
        // OCR, and debug preview for several seconds. Match the model-side 1920 px ceiling: small
        // Chinese text remains legible without processing the full panel on every action step.
        const val SCREENSHOT_MAX_EDGE = 1_920
        const val SCREENSHOT_JPEG_QUALITY = 82
        const val DISPLAY_RELEASE_TIMEOUT_MILLIS = 2_500L
        const val VIEW_LOG_TAG = "MurongAgentView"
    }
}

private fun String.shellSingleQuoted(): String = "'" + replace("'", "'\\''") + "'"
