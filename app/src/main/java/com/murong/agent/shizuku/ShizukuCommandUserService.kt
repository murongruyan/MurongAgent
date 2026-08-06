package com.murong.agent.shizuku

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.annotation.Keep
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Runs only inside Shizuku/Sui's user-service process, never in Murong's app UID. */
class ShizukuCommandUserService @Keep constructor(
    private val serviceContext: Context,
) : IShizukuCommandService.Stub() {
    /** Kept for Shizuku versions that still instantiate user services through a no-arg ctor. */
    constructor() : this(resolvePrivilegedContext())

    private val displayLock = Any()
    private val privilegedContext: Context by lazy { serviceContext }
    private val displayManager: DisplayManager by lazy {
        privilegedContext.getSystemService(DisplayManager::class.java)
            ?: error("DisplayManager unavailable")
    }
    private var agentDisplay: VirtualDisplay? = null
    private var agentReader: ImageReader? = null
    private var agentDisplayId: Int = -1
    private var agentHasContent = false
    private var agentDisplayPackageName: String? = null
    private var lastAgentScreenshot: ByteArray? = null
    private val framePipeExecutor = Executors.newCachedThreadPool()
    @Volatile private var clientBinder: IBinder? = null
    private val clientDeathRecipient = IBinder.DeathRecipient { destroy() }

    override fun execute(command: String, timeoutSeconds: Int): String {
        val process = ProcessBuilder("/system/bin/sh", "-c", command)
            .redirectErrorStream(true)
            .start()
        val reader = Executors.newSingleThreadExecutor()
        return try {
            val output = reader.submit<String> { readLimited(process.inputStream) }
            val completed = process.waitFor(timeoutSeconds.coerceIn(1, 120).toLong(), TimeUnit.SECONDS)
            if (!completed) {
                process.destroy()
                if (!process.waitFor(1, TimeUnit.SECONDS)) process.destroyForcibly()
            }
            val raw = runCatching { output.get(3, TimeUnit.SECONDS) }
                .getOrElse { "Command execution error: ${it.message}" }
                .trimEnd()
            if ("__RSNX_EXIT_CODE__" in raw) raw else {
                val exitCode = if (completed) process.exitValue() else -1
                "$raw\n__RSNX_EXIT_CODE__$exitCode"
            }
        } catch (error: Throwable) {
            "Command execution error: ${error.message.orEmpty()}\n__RSNX_EXIT_CODE__-1"
        } finally {
            reader.shutdownNow()
            process.destroy()
        }
    }

    override fun remoteUid(): Int = Process.myUid()

    override fun registerAgentDisplayClient(client: IRootAgentDisplayClient?) {
        requireNotNull(client) { "Missing Agent Display client" }
        val binder = client.asBinder()
        clientBinder?.let { previous ->
            runCatching { previous.unlinkToDeath(clientDeathRecipient, 0) }
        }
        binder.linkToDeath(clientDeathRecipient, 0)
        clientBinder = binder
    }

    override fun ensureAgentDisplay(width: Int, height: Int, densityDpi: Int): Int =
        synchronized(displayLock) {
            if (agentDisplay != null && agentDisplayId >= 0) return@synchronized agentDisplayId
            releaseAgentDisplayLocked()
            val safeWidth = width.coerceIn(360, 2_560)
            val safeHeight = height.coerceIn(640, 4_096)
            val reader = ImageReader.newInstance(
                safeWidth,
                safeHeight,
                PixelFormat.RGBA_8888,
                3,
            )
            try {
                val display = displayManager.createVirtualDisplay(
                    AGENT_DISPLAY_NAME,
                    safeWidth,
                    safeHeight,
                    densityDpi.coerceIn(120, 800),
                    reader.surface,
                    agentDisplayFlags(),
                ) ?: error("Android refused to create the isolated display")
                agentReader = reader
                agentDisplay = display
                agentDisplayId = display.display.displayId
                agentHasContent = false
                lastAgentScreenshot = null
                agentDisplayId
            } catch (error: Throwable) {
                reader.close()
                throw error
            }
        }

    override fun currentAgentDisplayId(): Int = synchronized(displayLock) { agentDisplayId }

    override fun currentAgentDisplayPackageName(displayId: Int): String =
        synchronized(displayLock) {
            ensureManagedDisplay(displayId)
            topPackageOnDisplay(displayId) ?: agentDisplayPackageName.orEmpty()
        }

    override fun launchPackageOnAgentDisplay(packageName: String, displayId: Int): Boolean {
        ensureManagedDisplay(displayId)
        val intent = privilegedContext.packageManager.getLaunchIntentForPackage(packageName)
            ?: return false
        return launchIntentOnDisplay(intent, displayId).also { launched ->
            if (launched) agentDisplayPackageName = packageName
        }
    }

    override fun launchViewUriOnAgentDisplay(
        packageName: String,
        uri: String,
        displayId: Int,
    ): Boolean {
        ensureManagedDisplay(displayId)
        if (packageName.isBlank() || uri.isBlank()) return false
        val implicit = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
            setPackage(packageName)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val resolved = privilegedContext.packageManager.resolveActivity(
            implicit,
            android.content.pm.PackageManager.MATCH_DEFAULT_ONLY,
        ) ?: return false
        // Keep VIEW intents package-scoped but implicit. Some apps (including Douyin) route a
        // custom URI through their intent filter before constructing the real search activity.
        // Pinning the resolver's current Activity class bypasses that routing and can reopen a
        // stale SearchResultActivity with its previous query.
        return launchIntentOnDisplay(implicit, displayId).also { launched ->
            if (launched) agentDisplayPackageName = resolved.activityInfo.packageName
        }
    }

    override fun launchShareTextOnAgentDisplay(
        packageName: String,
        text: String,
        displayId: Int,
    ): Boolean {
        ensureManagedDisplay(displayId)
        val implicit = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            setPackage(packageName)
            putExtra(Intent.EXTRA_TEXT, text)
        }
        val resolved = privilegedContext.packageManager.queryIntentActivities(
            implicit,
            android.content.pm.PackageManager.MATCH_DEFAULT_ONLY,
        ).sortedBy { candidate ->
            val name = candidate.activityInfo.name.lowercase()
            when {
                listOf("favorite", "favourite", "collect").any(name::contains) -> 2
                "share" in name || "send" in name -> 0
                else -> 1
            }
        }.firstOrNull() ?: return false
        return launchIntentOnDisplay(
            Intent(implicit).setClassName(
                resolved.activityInfo.packageName,
                resolved.activityInfo.name,
            ),
            displayId,
        ).also { launched ->
            if (launched) agentDisplayPackageName = resolved.activityInfo.packageName
        }
    }

    override fun captureAgentDisplayJpeg(
        displayId: Int,
        maxEdge: Int,
        quality: Int,
    ): ByteArray = synchronized(displayLock) {
        ensureManagedDisplay(displayId)
        val reader = agentReader ?: error("Isolated display capture surface unavailable")
        val image = awaitImage(reader)
        if (image == null) {
            lastAgentScreenshot?.let { return@synchronized it }
            if (agentHasContent) error("Timed out capturing isolated display $displayId")
            return@synchronized blankJpeg(reader.width, reader.height, maxEdge, quality)
        }
        image.use { captured ->
            imageToJpeg(captured, maxEdge, quality).also { encoded ->
                lastAgentScreenshot = encoded
            }
        }
    }

    /**
     * Full-resolution viewer frames can exceed Binder's transaction limit, so stream them through
     * a file-descriptor pipe instead of returning another byte array.
     */
    override fun captureAgentDisplayJpegFile(
        displayId: Int,
        maxEdge: Int,
        quality: Int,
    ): ParcelFileDescriptor {
        val encoded = captureAgentDisplayJpeg(displayId, maxEdge, quality)
        val pipe = ParcelFileDescriptor.createPipe()
        framePipeExecutor.execute {
            runCatching {
                ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { output ->
                    output.write(encoded)
                }
            }.onFailure {
                runCatching { pipe[1].close() }
            }
        }
        return pipe[0]
    }

    override fun tapAgentDisplay(displayId: Int, x: Int, y: Int): Boolean {
        ensureManagedDisplay(displayId)
        val downTime = SystemClock.uptimeMillis()
        injectMotion(displayId, downTime, downTime, MotionEvent.ACTION_DOWN, x, y)
        SystemClock.sleep(55)
        return injectMotion(
            displayId,
            downTime,
            SystemClock.uptimeMillis(),
            MotionEvent.ACTION_UP,
            x,
            y,
        )
    }

    override fun swipeAgentDisplay(
        displayId: Int,
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        durationMs: Int,
    ): Boolean {
        ensureManagedDisplay(displayId)
        val duration = durationMs.coerceIn(50, 10_000)
        val downTime = SystemClock.uptimeMillis()
        if (!injectMotion(displayId, downTime, downTime, MotionEvent.ACTION_DOWN, x1, y1)) {
            return false
        }
        val steps = (duration / 16).coerceIn(3, 80)
        repeat(steps - 1) { index ->
            val progress = (index + 1).toFloat() / steps.toFloat()
            SystemClock.sleep((duration / steps).toLong().coerceAtLeast(1))
            injectMotion(
                displayId,
                downTime,
                SystemClock.uptimeMillis(),
                MotionEvent.ACTION_MOVE,
                (x1 + (x2 - x1) * progress).toInt(),
                (y1 + (y2 - y1) * progress).toInt(),
            )
        }
        SystemClock.sleep((duration / steps).toLong().coerceAtLeast(1))
        return injectMotion(
            displayId,
            downTime,
            SystemClock.uptimeMillis(),
            MotionEvent.ACTION_UP,
            x2,
            y2,
        )
    }

    override fun keyAgentDisplay(displayId: Int, keyCode: String): Boolean {
        ensureManagedDisplay(displayId)
        val code = parseKeyCode(keyCode)
        val downTime = SystemClock.uptimeMillis()
        val down = injectKey(displayId, downTime, downTime, KeyEvent.ACTION_DOWN, code, 0)
        SystemClock.sleep(30)
        val up = injectKey(
            displayId,
            downTime,
            SystemClock.uptimeMillis(),
            KeyEvent.ACTION_UP,
            code,
            0,
        )
        return down && up
    }

    override fun typeAgentDisplay(displayId: Int, text: String): Boolean {
        ensureManagedDisplay(displayId)
        if (text.isEmpty()) return true
        val events = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
            .getEvents(text.toCharArray())
        if (events == null) {
            // KeyCharacterMap cannot synthesize CJK text. Stage it in Android's clipboard and
            // inject the standard hardware-keyboard paste shortcut into the isolated display.
            val clipboard = privilegedContext.getSystemService(ClipboardManager::class.java)
                ?: return false
            clipboard.setPrimaryClip(ClipData.newPlainText("Murong isolated input", text))
            val downTime = SystemClock.uptimeMillis()
            val meta = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
            val down = injectKey(
                displayId,
                downTime,
                downTime,
                KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_V,
                meta,
            )
            SystemClock.sleep(30)
            val up = injectKey(
                displayId,
                downTime,
                SystemClock.uptimeMillis(),
                KeyEvent.ACTION_UP,
                KeyEvent.KEYCODE_V,
                meta,
            )
            return down && up
        }
        var downTime = SystemClock.uptimeMillis()
        return events.all { source ->
            val now = SystemClock.uptimeMillis()
            if (source.action == KeyEvent.ACTION_DOWN) downTime = now
            injectKey(
                displayId,
                downTime,
                now,
                source.action,
                source.keyCode,
                source.metaState,
                source.scanCode,
                source.flags or KeyEvent.FLAG_SOFT_KEYBOARD,
            ).also {
                if (source.action == KeyEvent.ACTION_UP) SystemClock.sleep(4)
            }
        }
    }

    override fun handoffAgentDisplayToMain(displayId: Int): Boolean {
        ensureManagedDisplay(displayId)
        val packageName = agentDisplayPackageName ?: return false
        val moved = moveAgentRootTaskToDisplay(
            sourceDisplayId = displayId,
            targetDisplayId = android.view.Display.DEFAULT_DISPLAY,
            packageName = packageName,
        )
        if (moved) {
            // Keep the virtual display alive until WindowManager has attached the existing task to
            // display 0. Re-launching the package here would lose or refresh its current page.
            SystemClock.sleep(250)
            synchronized(displayLock) {
                if (agentDisplayId == displayId) releaseAgentDisplayLocked()
            }
        }
        return moved
    }

    override fun releaseAgentDisplay() = synchronized(displayLock) {
        releaseAgentDisplayLocked()
    }

    /** Transaction reserved by Shizuku for tearing down a user service. */
    override fun destroy() {
        clientBinder = null
        releaseAgentDisplay()
        System.exit(0)
    }

    private fun launchIntentOnDisplay(
        intent: Intent,
        displayId: Int,
        marksAgentContent: Boolean = true,
    ): Boolean = runCatching {
        val options = ActivityOptions.makeBasic().apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) launchDisplayId = displayId
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (marksAgentContent) intent.addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
        val display = displayManager.getDisplay(displayId) ?: error("Display $displayId unavailable")
        val displayContext = privilegedContext.createDisplayContext(display)
        PendingIntent.getActivity(
            displayContext,
            intent.filterHashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        ).send(
            privilegedContext,
            0,
            null,
            null,
            null,
            null,
            options.toBundle(),
        )
        if (marksAgentContent) agentHasContent = true
        true
    }.getOrDefault(false)

    /** Moves the existing task stack; it deliberately never starts the package launcher again. */
    @SuppressLint("BlockedPrivateApi") // Runs only in the Root/Shizuku privileged process.
    private fun moveAgentRootTaskToDisplay(
        sourceDisplayId: Int,
        targetDisplayId: Int,
        packageName: String,
    ): Boolean = runCatching {
        val managerClass = Class.forName("android.app.ActivityTaskManager")
        val manager = managerClass.getDeclaredMethod("getService")
            .apply { isAccessible = true }
            .invoke(null)
        val interfaceClass = Class.forName("android.app.IActivityTaskManager")
        val rootsOnDisplay = interfaceClass.getMethod(
            "getAllRootTaskInfosOnDisplay",
            Int::class.javaPrimitiveType,
        ).invoke(manager, sourceDisplayId) as? List<*> ?: return@runCatching false
        val matchingRoot = rootsOnDisplay.firstOrNull { root ->
            root != null && root.taskInfoComponent("topActivity")?.packageName == packageName
        } ?: rootsOnDisplay.firstOrNull { root ->
            root != null && root.taskInfoComponent("baseActivity")?.packageName == packageName
        } ?: return@runCatching false
        val rootTaskId = matchingRoot.javaClass.getField("taskId").getInt(matchingRoot)
        if (rootTaskId < 0) return@runCatching false
        interfaceClass.getMethod(
            "moveRootTaskToDisplay",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        ).invoke(manager, rootTaskId, targetDisplayId)
        true
    }.getOrDefault(false)

    @SuppressLint("BlockedPrivateApi") // Reads task state only from the privileged process.
    private fun topPackageOnDisplay(displayId: Int): String? = runCatching {
        val managerClass = Class.forName("android.app.ActivityTaskManager")
        val manager = managerClass.getDeclaredMethod("getService")
            .apply { isAccessible = true }
            .invoke(null)
        val interfaceClass = Class.forName("android.app.IActivityTaskManager")
        val rootsOnDisplay = interfaceClass.getMethod(
            "getAllRootTaskInfosOnDisplay",
            Int::class.javaPrimitiveType,
        ).invoke(manager, displayId) as? List<*> ?: return@runCatching null
        rootsOnDisplay.asSequence()
            .filterNotNull()
            .mapNotNull { root ->
                root.taskInfoComponent("topActivity")?.packageName
                    ?: root.taskInfoComponent("baseActivity")?.packageName
            }
            .firstOrNull()
    }.getOrNull()

    private fun Any.taskInfoComponent(fieldName: String): ComponentName? =
        runCatching { javaClass.getField(fieldName).get(this) as? ComponentName }.getOrNull()

    private fun ensureManagedDisplay(displayId: Int) {
        check(displayId >= 0 && displayId == agentDisplayId && agentDisplay != null) {
            "Display $displayId is not managed by Murong"
        }
    }

    private fun releaseAgentDisplayLocked() {
        agentDisplay?.release()
        agentReader?.close()
        agentDisplay = null
        agentReader = null
        agentDisplayId = -1
        agentHasContent = false
        agentDisplayPackageName = null
        lastAgentScreenshot = null
    }

    private fun awaitImage(reader: ImageReader): Image? {
        // Heavy third-party apps can take several seconds to finish their first transition on a
        // secondary display. Do not declare the display unsupported just before its first frame.
        val deadline = SystemClock.uptimeMillis() + 6_000
        while (SystemClock.uptimeMillis() < deadline) {
            reader.acquireLatestImage()?.let { return it }
            SystemClock.sleep(16)
        }
        return null
    }

    private fun imageToJpeg(image: Image, maxEdge: Int, quality: Int): ByteArray {
        val plane = image.planes.firstOrNull() ?: error("Captured image has no planes")
        val pixelStride = plane.pixelStride.coerceAtLeast(1)
        val rowStride = plane.rowStride.coerceAtLeast(image.width * pixelStride)
        val paddedWidth = (rowStride / pixelStride).coerceAtLeast(image.width)
        val padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
        plane.buffer.rewind()
        padded.copyPixelsFromBuffer(plane.buffer)
        val cropped = if (paddedWidth == image.width) padded else {
            Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
        }
        return try {
            encodeJpeg(cropped, maxEdge, quality)
        } finally {
            if (cropped !== padded) cropped.recycle()
            padded.recycle()
        }
    }

    private fun blankJpeg(
        width: Int,
        height: Int,
        maxEdge: Int,
        quality: Int,
    ): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.BLACK)
        return try {
            encodeJpeg(bitmap, maxEdge, quality)
        } finally {
            bitmap.recycle()
        }
    }

    private fun encodeJpeg(bitmap: Bitmap, maxEdge: Int, quality: Int): ByteArray {
        val largest = maxOf(bitmap.width, bitmap.height)
        val boundedEdge = maxEdge.coerceIn(320, 4_096)
        val scaled = if (largest <= boundedEdge) bitmap else {
            val scale = boundedEdge.toFloat() / largest.toFloat()
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        }
        return try {
            ByteArrayOutputStream().use { output ->
                check(scaled.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(40, 100), output))
                output.toByteArray()
            }
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    private fun injectMotion(
        displayId: Int,
        downTime: Long,
        eventTime: Long,
        action: Int,
        x: Int,
        y: Int,
    ): Boolean {
        val event = MotionEvent.obtain(
            downTime,
            eventTime,
            action,
            x.toFloat(),
            y.toFloat(),
            0,
        ).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        return injectInput(displayId, event)
    }

    private fun injectKey(
        displayId: Int,
        downTime: Long,
        eventTime: Long,
        action: Int,
        keyCode: Int,
        metaState: Int,
        scanCode: Int = 0,
        flags: Int = 0,
    ): Boolean = injectInput(
        displayId,
        KeyEvent(
            downTime,
            eventTime,
            action,
            keyCode,
            0,
            metaState,
            KeyCharacterMap.VIRTUAL_KEYBOARD,
            scanCode,
            flags,
            InputDevice.SOURCE_KEYBOARD,
        ),
    )

    @SuppressLint("BlockedPrivateApi") // Assigning the virtual display is required before privileged injection.
    private fun injectInput(displayId: Int, event: InputEvent): Boolean {
        try {
            InputEvent::class.java.getDeclaredMethod(
                "setDisplayId",
                Int::class.javaPrimitiveType,
            ).apply { isAccessible = true }.invoke(event, displayId)
            val managerClass = Class.forName("android.hardware.input.InputManager")
            val manager = managerClass.getDeclaredMethod("getInstance")
                .apply { isAccessible = true }
                .invoke(null)
            val method = manager.javaClass.getMethod(
                "injectInputEvent",
                InputEvent::class.java,
                Int::class.javaPrimitiveType,
            )
            return method.invoke(manager, event, 2) as? Boolean == true
        } finally {
            if (event is MotionEvent) event.recycle()
        }
    }

    private fun parseKeyCode(value: String): Int {
        value.trim().toIntOrNull()?.let { return it }
        KeyEvent.keyCodeFromString(value.trim()).takeIf { it != KeyEvent.KEYCODE_UNKNOWN }
            ?.let { return it }
        return KeyEvent.keyCodeFromString("KEYCODE_${value.trim().uppercase()}")
            .takeIf { it != KeyEvent.KEYCODE_UNKNOWN }
            ?: error("Unsupported key code: $value")
    }

    private fun readLimited(input: InputStream): String {
        input.bufferedReader(Charsets.UTF_8).use { reader ->
            val result = StringBuilder()
            val buffer = CharArray(4_096)
            while (result.length < MAX_OUTPUT_CHARS) {
                val count = reader.read(buffer, 0, minOf(buffer.size, MAX_OUTPUT_CHARS - result.length))
                if (count <= 0) break
                result.append(buffer, 0, count)
            }
            if (reader.read() >= 0) result.append("\n...(Shizuku 输出已截断)")
            return result.toString()
        }
    }

    private companion object {
        const val MAX_OUTPUT_CHARS = 256 * 1024
        const val AGENT_DISPLAY_NAME = "Murong Agent Display"
        const val AGENT_DISPLAY_FLAGS =
            (1 shl 0) or // PUBLIC
                (1 shl 3) or // OWN_CONTENT_ONLY
                (1 shl 6) or // SUPPORTS_TOUCH
                (1 shl 8) or // DESTROY_CONTENT_ON_REMOVAL
                (1 shl 10) or // TRUSTED
                (1 shl 13) or // TOUCH_FEEDBACK_DISABLED
                (1 shl 14) or // OWN_FOCUS
                (1 shl 16) // STEAL_TOP_FOCUS_DISABLED

        private fun agentDisplayFlags(): Int = AGENT_DISPLAY_FLAGS or
            if (Process.myUid() == 0) {
                // ALWAYS_UNLOCKED is valid only outside the default display group, so Root uses
                // OWN_DISPLAY_GROUP + ALWAYS_UNLOCKED together. These signature-only flags are
                // deliberately omitted from the UID 2000 Shizuku fallback.
                (1 shl 11) or (1 shl 12)
            } else {
                0
            }

        fun resolvePrivilegedContext(): Context {
            val activityThread = Class.forName("android.app.ActivityThread")
            val currentApplication = activityThread.getDeclaredMethod("currentApplication")
                .apply { isAccessible = true }
                .invoke(null) as? Context
            if (currentApplication != null) return currentApplication
            val thread = activityThread.getDeclaredMethod("currentActivityThread")
                .apply { isAccessible = true }
                .invoke(null)
            return activityThread.getDeclaredMethod("getSystemContext")
                .apply { isAccessible = true }
                .invoke(thread) as? Context
                ?: error("Unable to resolve Android context in Shizuku user service")
        }
    }
}
