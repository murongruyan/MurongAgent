package com.murong.agent.core.tool

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.TextView
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Semantic-first Android GUI adapter.
 *
 * The service keeps no screenshots and only retains observation-scoped node paths. Password
 * fields are always redacted before they can reach a model.
 */
class AndroidGuiAccessibilityService : AccessibilityService() {
    private data class CachedNodePath(
        val path: List<Int>,
        val resourceId: String?,
        val className: String?
    )

    private val observationSequence = AtomicLong()
    private val nodePaths = linkedMapOf<String, CachedNodePath>()
    private val cacheLock = Any()
    private val volumeChordWakeDetector = VolumeChordWakeDetector()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var taskOverlayView: View? = null
    private var taskOverlayTitle: TextView? = null
    private var taskOverlayDetail: TextView? = null
    private var taskOverlayParams: WindowManager.LayoutParams? = null
    private var taskOverlayId: String? = null
    private var taskOverlayHiddenByUser = false

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    private fun renderTaskProgressOverlay(
        taskId: String,
        title: String,
        detail: String,
        completed: Boolean,
    ) {
        mainHandler.post {
            if (taskOverlayId != taskId) {
                removeTaskProgressOverlayInternal()
                taskOverlayId = taskId
                taskOverlayHiddenByUser = false
            }
            if (taskOverlayHiddenByUser) return@post
            ensureTaskProgressOverlay()
            taskOverlayTitle?.text = if (completed) "$title · 已结束" else title
            taskOverlayDetail?.text = detail
            taskOverlayView?.background = taskOverlayBackground(completed)
        }
    }

    private fun ensureTaskProgressOverlay() {
        if (taskOverlayView != null) return
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(10), dp(10))
            background = taskOverlayBackground(completed = false)
            elevation = dp(10).toFloat()
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "Murong 执行中"
            setTextColor(Color.WHITE)
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(2), dp(8), dp(6))
        }
        val collapse = TextView(this).apply {
            text = "—"
            contentDescription = "收起或展开任务进度"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 20f
            setPadding(dp(10), 0, dp(10), 0)
        }
        val close = TextView(this).apply {
            text = "×"
            contentDescription = "关闭任务悬浮窗"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 24f
            setPadding(dp(10), 0, dp(4), 0)
        }
        val detail = TextView(this).apply {
            setTextColor(Color.rgb(235, 238, 245))
            textSize = 13f
            setLineSpacing(0f, 1.12f)
            maxHeight = dp(180)
            setPadding(0, dp(6), dp(4), dp(2))
            isVerticalScrollBarEnabled = true
            movementMethod = android.text.method.ScrollingMovementMethod.getInstance()
        }
        header.addView(
            title,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        header.addView(
            collapse,
            LinearLayout.LayoutParams(dp(44), ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        header.addView(
            close,
            LinearLayout.LayoutParams(dp(44), ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        root.addView(
            header,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        root.addView(
            detail,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        collapse.setOnClickListener {
            val collapsed = detail.visibility == View.VISIBLE
            detail.visibility = if (collapsed) View.GONE else View.VISIBLE
            collapse.text = if (collapsed) "＋" else "—"
        }
        close.setOnClickListener {
            taskOverlayHiddenByUser = true
            removeTaskProgressOverlayInternal(keepTaskIdentity = true)
        }
        attachOverlayDrag(title, root, windowManager)

        val params = WindowManager.LayoutParams(
            dp(330),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(14)
            y = dp(70)
        }
        runCatching { windowManager.addView(root, params) }
            .onFailure { return }
        taskOverlayView = root
        taskOverlayTitle = title
        taskOverlayDetail = detail
        taskOverlayParams = params
    }

    private fun attachOverlayDrag(
        dragHandle: View,
        overlay: View,
        windowManager: WindowManager,
    ) {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        dragHandle.setOnTouchListener { _, event ->
            val params = taskOverlayParams
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params?.x ?: 0
                    startY = params?.y ?: 0
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (params != null) {
                        params.x = (startX + event.rawX - downX).toInt().coerceAtLeast(0)
                        params.y = (startY + event.rawY - downY).toInt().coerceAtLeast(0)
                        runCatching { windowManager.updateViewLayout(overlay, params) }
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun removeTaskProgressOverlayInternal(keepTaskIdentity: Boolean = false) {
        val view = taskOverlayView
        if (view != null) {
            runCatching {
                (getSystemService(WINDOW_SERVICE) as WindowManager).removeViewImmediate(view)
            }
        }
        taskOverlayView = null
        taskOverlayTitle = null
        taskOverlayDetail = null
        taskOverlayParams = null
        if (!keepTaskIdentity) taskOverlayId = null
    }

    private fun taskOverlayBackground(completed: Boolean): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(20).toFloat()
            setColor(
                if (completed) {
                    Color.argb(242, 31, 94, 72)
                } else {
                    Color.argb(242, 31, 35, 48)
                },
            )
            setStroke(dp(1), Color.argb(110, 255, 255, 255))
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val preferences = getSharedPreferences(ASSISTANT_PREFERENCES_NAME, MODE_PRIVATE)
        val enabled = preferences.getBoolean(
            KEY_VOLUME_CHORD_TRIPLE,
            preferences.getBoolean(LEGACY_KEY_DOUBLE_VOLUME_UP, false),
        )
        if (!enabled) {
            volumeChordWakeDetector.reset()
            return false
        }
        val result = volumeChordWakeDetector.onKeyEvent(
            keyCode = event.keyCode,
            action = event.action,
            eventTime = event.eventTime,
            repeatCount = event.repeatCount,
        )
        // Let Android process both volume directions. A complete chord therefore balances the
        // volume instead of swallowing only the second key and changing the user's volume.
        if (!result.triggered) return false

        runCatching {
            startActivity(
                Intent()
                    .setClassName(
                        packageName,
                        "com.murong.agent.ui.assistant.MurongAssistActivity",
                    )
                    .putExtra(
                        "com.murong.agent.extra.ASSISTANT_SOURCE",
                        "volume_shortcut",
                    )
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP,
                    ),
            )
        }
        return false
    }

    override fun onUnbind(intent: Intent?): Boolean {
        clearConnection()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        clearConnection()
        super.onDestroy()
    }

    fun observe(maxNodes: Int, includeText: Boolean): GuiObservation {
        val root = rootInActiveWindow
            ?: return GuiObservation(
                success = false,
                target = "android",
                observationId = "",
                source = "accessibility",
                error = "当前窗口没有可访问的语义树"
            )
        val safeMaxNodes = maxNodes.coerceIn(1, MAX_OBSERVATION_NODES)
        val generation = observationSequence.incrementAndGet()
        val prefix = "android:$generation"
        val result = ArrayList<GuiNodeSnapshot>(safeMaxNodes)
        val freshPaths = linkedMapOf<String, CachedNodePath>()
        var visited = 0
        var truncated = false

        fun visit(node: AccessibilityNodeInfo, path: List<Int>, parentId: String?) {
            if (visited >= MAX_TRAVERSED_NODES || result.size >= safeMaxNodes) {
                truncated = true
                return
            }
            visited++
            val id = if (path.isEmpty()) "$prefix:root" else "$prefix:${path.joinToString(".")}"
            val rawText = node.text?.toString()?.trim().orEmpty()
            val rawDescription = node.contentDescription?.toString()?.trim().orEmpty()
            val password = node.isPassword
            val actionable = node.isClickable || node.isLongClickable || node.isEditable ||
                node.isScrollable || node.actionList.isNotEmpty()
            val meaningful = path.isEmpty() || actionable || rawText.isNotBlank() ||
                rawDescription.isNotBlank() || !node.viewIdResourceName.isNullOrBlank()
            var nextParentId = parentId
            if (meaningful) {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                val checked = if (node.isCheckable) node.isChecked else null
                result += GuiNodeSnapshot(
                    id = id,
                    parentId = parentId,
                    role = roleFor(node),
                    text = when {
                        password -> if (includeText && rawText.isNotBlank()) "[REDACTED]" else null
                        includeText -> rawText.takeIf { it.isNotBlank() }?.take(MAX_NODE_TEXT_CHARS)
                        else -> null
                    },
                    contentDescription = when {
                        password -> if (includeText && rawDescription.isNotBlank()) "[REDACTED]" else null
                        includeText -> rawDescription.takeIf { it.isNotBlank() }?.take(MAX_NODE_TEXT_CHARS)
                        else -> null
                    },
                    resourceId = node.viewIdResourceName?.take(MAX_NODE_TEXT_CHARS),
                    className = node.className?.toString()?.take(MAX_NODE_TEXT_CHARS),
                    bounds = GuiRect(bounds.left, bounds.top, bounds.right, bounds.bottom),
                    clickable = node.isClickable,
                    longClickable = node.isLongClickable,
                    editable = node.isEditable,
                    scrollable = node.isScrollable,
                    enabled = node.isEnabled,
                    selected = node.isSelected,
                    checked = checked,
                    password = password,
                    visible = node.isVisibleToUser
                )
                freshPaths[id] = CachedNodePath(
                    path = path,
                    resourceId = node.viewIdResourceName,
                    className = node.className?.toString()
                )
                nextParentId = id
            }
            for (index in 0 until node.childCount) {
                if (truncated) break
                val child = node.getChild(index) ?: continue
                visit(child, path + index, nextParentId)
            }
        }

        visit(root, emptyList(), null)
        synchronized(cacheLock) {
            nodePaths.clear()
            nodePaths.putAll(freshPaths)
        }
        val display = resources.displayMetrics
        return GuiObservation(
            target = "android",
            observationId = prefix,
            application = root.packageName?.toString(),
            windowTitle = if (includeText) {
                root.paneTitle?.toString() ?: root.className?.toString()
            } else {
                root.className?.toString()
            },
            width = display.widthPixels,
            height = display.heightPixels,
            nodes = result,
            truncated = truncated,
            semanticTextRedacted = !includeText,
            source = "accessibility"
        )
    }

    fun click(nodeId: String, longClick: Boolean): Boolean {
        val node = resolveNode(nodeId) ?: return false
        val action = if (longClick) {
            AccessibilityNodeInfo.ACTION_LONG_CLICK
        } else {
            AccessibilityNodeInfo.ACTION_CLICK
        }
        var current: AccessibilityNodeInfo? = node
        repeat(MAX_ACTION_ANCESTORS) {
            val candidate = current ?: return@repeat
            if ((longClick && candidate.isLongClickable) || (!longClick && candidate.isClickable)) {
                return candidate.performAction(action)
            }
            current = candidate.parent
        }
        return node.performAction(action)
    }

    fun setText(nodeId: String, text: String): Boolean {
        val node = resolveNode(nodeId) ?: return false
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    fun setFocusedText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: findFirstEditable(root)
            ?: return false
        if (!focused.isFocused) {
            focused.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        }
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    fun scroll(nodeId: String?, forward: Boolean): Boolean {
        val requested = nodeId?.let(::resolveNode)
        val target = requested?.takeIf { it.isScrollable } ?: findFirstScrollable(rootInActiveWindow)
            ?: return false
        return target.performAction(
            if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        )
    }

    suspend fun tap(x: Int, y: Int, durationMillis: Long = 80L): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        return dispatchGestureAwait(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMillis.coerceIn(1, 2_000)))
                .build()
        )
    }

    suspend fun swipe(
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        durationMillis: Long
    ): Boolean {
        val path = Path().apply {
            moveTo(startX.toFloat(), startY.toFloat())
            lineTo(endX.toFloat(), endY.toFloat())
        }
        return dispatchGestureAwait(
            GestureDescription.Builder()
                .addStroke(
                    GestureDescription.StrokeDescription(
                        path,
                        0,
                        durationMillis.coerceIn(50, 5_000)
                    )
                )
                .build()
        )
    }

    fun globalAction(action: String): Boolean = performGlobalAction(
        when (action.lowercase()) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
            else -> return false
        }
    )

    suspend fun captureScreenshot(): GuiScreenshot = suspendCancellableCoroutine { continuation ->
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            Executor { command -> mainExecutor.execute(command) },
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val buffer = screenshot.hardwareBuffer
                    try {
                        val hardware = Bitmap.wrapHardwareBuffer(
                            buffer,
                            screenshot.colorSpace ?: ColorSpace.get(ColorSpace.Named.SRGB)
                        ) ?: error("无法读取系统截图缓冲区")
                        val bitmap = hardware.copy(Bitmap.Config.ARGB_8888, false)
                        val output = ByteArrayOutputStream()
                        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                            "无法编码系统截图"
                        }
                        if (continuation.isActive) {
                            continuation.resume(
                                GuiScreenshot(
                                    mimeType = "image/png",
                                    base64Data = android.util.Base64.encodeToString(
                                        output.toByteArray(),
                                        android.util.Base64.NO_WRAP
                                    ),
                                    width = bitmap.width,
                                    height = bitmap.height
                                )
                            )
                        }
                        bitmap.recycle()
                    } catch (error: Throwable) {
                        if (continuation.isActive) {
                            continuation.resumeWith(Result.failure(error))
                        }
                    } finally {
                        buffer.close()
                    }
                }

                override fun onFailure(errorCode: Int) {
                    if (continuation.isActive) {
                        continuation.resumeWith(
                            Result.failure(
                                IllegalStateException("Accessibility 截图失败，错误码 $errorCode")
                            )
                        )
                    }
                }
            }
        )
    }

    private suspend fun dispatchGestureAwait(gesture: GestureDescription): Boolean =
        suspendCancellableCoroutine { continuation ->
            val accepted = dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (continuation.isActive) continuation.resume(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (continuation.isActive) continuation.resume(false)
                    }
                },
                null
            )
            if (!accepted && continuation.isActive) continuation.resume(false)
        }

    private fun resolveNode(nodeId: String): AccessibilityNodeInfo? {
        val cached = synchronized(cacheLock) { nodePaths[nodeId] } ?: return null
        var node = rootInActiveWindow ?: return null
        for (index in cached.path) {
            node = node.getChild(index) ?: return null
        }
        if (cached.resourceId != null && node.viewIdResourceName != cached.resourceId) return null
        if (cached.className != null && node.className?.toString() != cached.className) return null
        return node
    }

    private fun findFirstScrollable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        node ?: return null
        if (node.isScrollable && node.isVisibleToUser) return node
        for (index in 0 until node.childCount) {
            findFirstScrollable(node.getChild(index))?.let { return it }
        }
        return null
    }

    private fun findFirstEditable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        node ?: return null
        if (node.isEditable && node.isVisibleToUser && node.isEnabled) return node
        for (index in 0 until node.childCount) {
            findFirstEditable(node.getChild(index))?.let { return it }
        }
        return null
    }

    private fun clearConnection() {
        removeTaskProgressOverlayInternal()
        if (instance === this) instance = null
        volumeChordWakeDetector.reset()
        synchronized(cacheLock) { nodePaths.clear() }
    }

    private fun roleFor(node: AccessibilityNodeInfo): String {
        val className = node.className?.toString().orEmpty().substringAfterLast('.').lowercase()
        return when {
            node.isEditable -> "textbox"
            node.isCheckable && "switch" in className -> "switch"
            node.isCheckable -> "checkbox"
            node.isClickable && "button" in className -> "button"
            node.isScrollable -> "scroll"
            "image" in className -> "image"
            "text" in className -> "text"
            else -> className.ifBlank { "element" }
        }
    }

    companion object {
        private const val MAX_OBSERVATION_NODES = 500
        private const val MAX_TRAVERSED_NODES = 5_000
        private const val MAX_NODE_TEXT_CHARS = 500
        private const val MAX_ACTION_ANCESTORS = 8
        private const val ASSISTANT_PREFERENCES_NAME = "assistant_invocation"
        private const val KEY_VOLUME_CHORD_TRIPLE = "volume_chord_triple"
        private const val LEGACY_KEY_DOUBLE_VOLUME_UP = "double_volume_up"

        @Volatile
        private var instance: AndroidGuiAccessibilityService? = null

        fun connectedInstance(): AndroidGuiAccessibilityService? = instance

        fun isConnected(): Boolean = instance != null

        fun showTaskProgressOverlay(
            taskId: String,
            title: String,
            detail: String,
            completed: Boolean = false,
        ): Boolean {
            val service = instance ?: return false
            service.renderTaskProgressOverlay(taskId, title, detail, completed)
            return true
        }

        fun dismissTaskProgressOverlay(taskId: String? = null) {
            val service = instance ?: return
            service.mainHandler.post {
                if (taskId == null || service.taskOverlayId == taskId) {
                    service.removeTaskProgressOverlayInternal()
                }
            }
        }
    }
}
