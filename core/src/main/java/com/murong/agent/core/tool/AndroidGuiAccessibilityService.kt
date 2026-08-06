package com.murong.agent.core.tool

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.LinearLayout
import android.widget.TextView
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout

internal data class GuiClipboardSnapshot(
    val previous: ClipData?,
    val wasReadable: Boolean,
)

private val TASK_OVERLAY_STEP_PATTERN = Regex("第\\s*(\\d+)\\s*/\\s*(\\d+)\\s*步")

fun taskOverlayCapsuleLabel(detail: String, completed: Boolean): String {
    if (completed) return "Murong · 待确认"
    val step = TASK_OVERLAY_STEP_PATTERN.find(detail)?.let { match ->
        "${match.groupValues[1]}/${match.groupValues[2]}"
    }
    return if (step == null) "Murong · 执行中" else "Murong · $step"
}

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
    private var taskOverlayCapsuleText: TextView? = null
    private var taskOverlayParams: WindowManager.LayoutParams? = null
    private var taskOverlayId: String? = null
    private var taskOverlayHiddenByUser = false
    private var taskOverlayExpanded = false
    private var taskOverlayCompleted = false
    private val latestWindowClassByPackage = ConcurrentHashMap<String, String>()
    @Volatile private var latestWindowPackage: String? = null

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val eventPackage = event.packageName?.toString()?.trim().orEmpty()
        val eventClass = event.className?.toString()?.trim().orEmpty()
        if (eventPackage.isBlank() || eventClass.isBlank()) return
        // Opening an IME also produces a window-state event. Keep it in the package map for
        // diagnostics, but do not let a keyboard replace the foreground app/activity used by
        // phone-task recovery.
        if (eventClass.contains("SoftInputWindow", ignoreCase = true)) return
        latestWindowClassByPackage[eventPackage] = eventClass
        latestWindowPackage = eventPackage
    }

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
            taskOverlayCapsuleText?.text = taskOverlayCapsuleLabel(detail, completed)
            taskOverlayCompleted = completed
            taskOverlayView?.background = if (taskOverlayExpanded) {
                taskOverlayBackground(completed)
            } else {
                taskOverlayCapsuleBackground(completed)
            }
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
        val fullContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val minimizedCapsule = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            contentDescription = "展开手机操作进度"
            setPadding(dp(12), 0, dp(10), 0)
        }
        val capsuleDot = TextView(this).apply {
            text = "●"
            setTextColor(Color.rgb(255, 107, 190))
            textSize = 18f
            gravity = Gravity.CENTER
        }
        val capsuleText = TextView(this).apply {
            text = "Murong · 执行中"
            setTextColor(Color.WHITE)
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 1
            gravity = Gravity.CENTER_VERTICAL
        }
        val capsuleExpand = TextView(this).apply {
            text = "›"
            setTextColor(Color.rgb(255, 174, 218))
            textSize = 22f
            gravity = Gravity.CENTER
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
        fullContent.addView(
            header,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        fullContent.addView(
            detail,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        minimizedCapsule.addView(
            capsuleDot,
            LinearLayout.LayoutParams(dp(24), ViewGroup.LayoutParams.MATCH_PARENT),
        )
        minimizedCapsule.addView(
            capsuleText,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f),
        )
        minimizedCapsule.addView(
            capsuleExpand,
            LinearLayout.LayoutParams(dp(20), ViewGroup.LayoutParams.MATCH_PARENT),
        )
        fullContent.visibility = View.GONE
        root.setPadding(0, 0, 0, 0)
        root.addView(fullContent)
        root.addView(
            minimizedCapsule,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)),
        )
        close.setOnClickListener {
            taskOverlayHiddenByUser = true
            removeTaskProgressOverlayInternal(keepTaskIdentity = true)
        }
        attachOverlayDrag(title, root, windowManager)
        attachOverlayDrag(minimizedCapsule, root, windowManager)

        val params = WindowManager.LayoutParams(
            dp(184),
            dp(48),
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
        collapse.contentDescription = "收起为悬浮胶囊"
        collapse.setOnClickListener {
            fullContent.visibility = View.GONE
            minimizedCapsule.visibility = View.VISIBLE
            taskOverlayExpanded = false
            root.setPadding(0, 0, 0, 0)
            params.width = dp(184)
            params.height = dp(48)
            root.background = taskOverlayCapsuleBackground(taskOverlayCompleted)
            runCatching { windowManager.updateViewLayout(root, params) }
        }
        minimizedCapsule.setOnClickListener {
            minimizedCapsule.visibility = View.GONE
            fullContent.visibility = View.VISIBLE
            taskOverlayExpanded = true
            root.setPadding(dp(14), dp(10), dp(10), dp(10))
            params.width = dp(330)
            params.height = WindowManager.LayoutParams.WRAP_CONTENT
            root.background = taskOverlayBackground(taskOverlayCompleted)
            runCatching { windowManager.updateViewLayout(root, params) }
        }
        runCatching { windowManager.addView(root, params) }
            .onFailure { return }
        taskOverlayView = root
        taskOverlayTitle = title
        taskOverlayDetail = detail
        taskOverlayCapsuleText = capsuleText
        taskOverlayParams = params
        taskOverlayExpanded = false
    }

    private fun attachOverlayDrag(
        dragHandle: View,
        overlay: View,
        windowManager: WindowManager,
    ) {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop.toFloat()
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        dragHandle.setOnTouchListener { _, event ->
            val params = taskOverlayParams
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params?.x ?: 0
                    startY = params?.y ?: 0
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (
                        kotlin.math.abs(event.rawX - downX) >= touchSlop ||
                        kotlin.math.abs(event.rawY - downY) >= touchSlop
                    ) {
                        dragging = true
                    }
                    if (params != null) {
                        params.x = (startX + event.rawX - downX).toInt().coerceAtLeast(0)
                        params.y = (startY + event.rawY - downY).toInt().coerceAtLeast(0)
                        runCatching { windowManager.updateViewLayout(overlay, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) dragHandle.performClick()
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> true
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
        taskOverlayCapsuleText = null
        taskOverlayParams = null
        taskOverlayExpanded = false
        taskOverlayCompleted = false
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

    private fun taskOverlayCapsuleBackground(completed: Boolean): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(24).toFloat()
            setColor(
                if (completed) Color.argb(246, 31, 94, 72)
                else Color.argb(246, 31, 35, 48),
            )
            setStroke(dp(2), Color.rgb(255, 107, 190))
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

    fun observe(
        maxNodes: Int,
        includeText: Boolean,
        displayId: Int? = null,
    ): GuiObservation {
        val root = rootForDisplay(displayId)
            ?: return GuiObservation(
                success = false,
                target = "android",
                observationId = "",
                application = latestWindowPackage,
                windowTitle = latestObservedWindowClassName(latestWindowPackage),
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
        val rootPackage = root.packageName?.toString()?.trim()?.takeIf(String::isNotBlank)
        return GuiObservation(
            target = "android",
            observationId = prefix,
            application = rootPackage,
            windowTitle = if (includeText) {
                root.paneTitle?.toString()?.takeIf(String::isNotBlank)
                    ?: root.className?.toString()?.takeIf(String::isNotBlank)
                    ?: latestObservedWindowClassName(rootPackage)
            } else {
                root.className?.toString()?.takeIf(String::isNotBlank)
                    ?: latestObservedWindowClassName(rootPackage)
            },
            width = display.widthPixels,
            height = display.heightPixels,
            nodes = result,
            truncated = truncated,
            semanticTextRedacted = !includeText,
            source = "accessibility"
        )
    }

    fun latestObservedWindowPackage(): String? = latestWindowPackage

    fun latestObservedWindowClassName(packageName: String? = latestWindowPackage): String? =
        packageName?.let(latestWindowClassByPackage::get)

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

    suspend fun setFocusedText(text: String, displayId: Int? = null): Boolean {
        val root = rootForDisplay(displayId) ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: findFirstEditable(root)
            ?: return false
        if (!focused.isFocused) {
            focused.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        }
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        if (!focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) return false

        // Some custom text fields (notably search pages rendered outside the normal Android
        // widget tree) accept ACTION_SET_TEXT but silently discard the value. Treat the action
        // result as an acknowledgement only; verify the refreshed focused/editable node before
        // reporting success so the caller can fall back to an InputConnection when necessary.
        val deadline = SystemClock.uptimeMillis() + FOCUSED_TEXT_VERIFY_TIMEOUT_MS
        do {
            delay(FOCUSED_TEXT_VERIFY_INTERVAL_MS)
            val refreshedRoot = rootForDisplay(displayId)
            val refreshed = refreshedRoot?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: refreshedRoot?.let(::findFirstEditable)
            if (refreshed?.text?.toString() == text) return true
        } while (SystemClock.uptimeMillis() < deadline)
        return false
    }

    /**
     * Opens an app's own search entry and replaces any stale query through its semantic tree.
     * This is intentionally app-agnostic: no activity, resource id, or coordinate is hardcoded.
     */
    suspend fun prepareSearchQuery(query: String, displayId: Int? = null): Boolean {
        if (query.isBlank()) return false
        val entryDeadline = SystemClock.uptimeMillis() + SEARCH_CONTROL_TIMEOUT_MS
        var root: AccessibilityNodeInfo? = null
        var searchEntry: AccessibilityNodeInfo? = null
        while (SystemClock.uptimeMillis() < entryDeadline) {
            root = rootForDisplay(displayId)
            val editable = root?.let(::findFirstEditable)
            if (editable != null) {
                searchEntry = editable
                break
            }
            searchEntry = root?.let(::findBestSearchEntry)
            if (searchEntry != null) break
            delay(180)
        }
        val initialRoot = root ?: return false
        if (findFirstEditable(initialRoot) == null) {
            val entry = searchEntry ?: return false
            if (!clickNodeOrAncestor(entry)) return false
        }
        val editableDeadline = SystemClock.uptimeMillis() + SEARCH_CONTROL_TIMEOUT_MS
        var editable: AccessibilityNodeInfo? = null
        while (SystemClock.uptimeMillis() < editableDeadline) {
            val freshRoot = rootForDisplay(displayId)
            editable = freshRoot?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: freshRoot?.let(::findFirstEditable)
            if (editable != null) break
            delay(180)
        }
        val target = editable ?: return false
        target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, query)
        }
        return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    fun submitFocusedSearch(displayId: Int? = null): Boolean {
        val root = rootForDisplay(displayId) ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: findFirstEditable(root)
        if (focused != null && focused.performAction(
                AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id,
            )
        ) {
            return true
        }
        val searchButton = findExactSearchButton(root) ?: return false
        return clickNodeOrAncestor(searchButton)
    }

    private fun rootForDisplay(displayId: Int?): AccessibilityNodeInfo? {
        val targetDisplayId = displayId ?: Display.DEFAULT_DISPLAY
        val applicationRoot = windows
            .asSequence()
            .filter { window -> window.displayId == targetDisplayId }
            // A Murong accessibility overlay and the IME can both sit above the target app.
            // Phone observation/actions must use the real application window underneath.
            .filter { window -> window.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .sortedByDescending { window -> window.layer }
            .mapNotNull { window -> window.root }
            .firstOrNull()
        // rootInActiveWindow belongs to whichever display currently has accessibility focus.
        // Falling back to it for an explicitly requested secondary display can pair a virtual
        // display screenshot with the physical display's semantic tree and, worse, execute an
        // action on the physical app. Only the default display may use that fallback.
        return applicationRoot ?: if (targetDisplayId == Display.DEFAULT_DISPLAY) {
            rootInActiveWindow
        } else {
            null
        }
    }

    private fun findBestSearchEntry(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestScore = 0
        val rootBounds = Rect().also(root::getBoundsInScreen)
        val rootWidth = rootBounds.width().coerceAtLeast(resources.displayMetrics.widthPixels)
        val rootHeight = rootBounds.height().coerceAtLeast(resources.displayMetrics.heightPixels)
        fun visit(node: AccessibilityNodeInfo) {
            val text = node.text?.toString().orEmpty().lowercase()
            val description = node.contentDescription?.toString().orEmpty().lowercase()
            val resourceId = node.viewIdResourceName.orEmpty().lowercase()
            val className = node.className?.toString().orEmpty().lowercase()
            val bounds = Rect().also(node::getBoundsInScreen)
            val resemblesTopSearchBar = bounds.top <= rootBounds.top + rootHeight * 28 / 100 &&
                bounds.width() >= rootWidth * 35 / 100 &&
                bounds.height() in 1..(rootHeight * 16 / 100) &&
                (text.isNotBlank() || description.isNotBlank() || node.isClickable)
            var score = 0
            if (node.isEditable) score += 100
            if ("search" in resourceId) score += 90
            if ("搜索" in text || "搜索" in description) score += 80
            if ("search" in text || "search" in description) score += 75
            if ("edittext" in className) score += 70
            if (resemblesTopSearchBar) score += 55
            if (resemblesTopSearchBar && "text" in className) score += 10
            if (node.isVisibleToUser && node.isEnabled && score > bestScore) {
                best = node
                bestScore = score
            }
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(::visit)
            }
        }
        visit(root)
        return best
    }

    private fun findExactSearchButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val text = root.text?.toString()?.trim().orEmpty()
        val description = root.contentDescription?.toString()?.trim().orEmpty()
        if (root.isVisibleToUser && root.isEnabled &&
            (text.equals("搜索", true) || description.equals("搜索", true) ||
                text.equals("search", true) || description.equals("search", true))
        ) {
            return root
        }
        for (index in 0 until root.childCount) {
            findExactSearchButton(root.getChild(index) ?: continue)?.let { return it }
        }
        return null
    }

    private fun clickNodeOrAncestor(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        repeat(MAX_ACTION_ANCESTORS) {
            val candidate = current ?: return@repeat
            if (candidate.isClickable && candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
            current = candidate.parent
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    /**
     * Opens an app's standard text-sharing surface with the body already populated. This removes
     * fragile navigation and Unicode keyboard input from explicit "send a message" tasks. The
     * target activity is resolved from Android's exported SEND handlers instead of an app-specific
     * activity name; collection/favourite handlers are deprioritized when an app exposes several.
     */
    fun launchShareText(packageName: String, text: String): Boolean {
        if (packageName.isBlank() || text.isBlank()) return false
        val implicit = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            setPackage(packageName)
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val resolved = packageManager.queryIntentActivities(
            implicit,
            android.content.pm.PackageManager.MATCH_DEFAULT_ONLY,
        ).sortedBy { candidate ->
            val name = candidate.activityInfo.name.lowercase()
            when {
                listOf("favorite", "favourite", "collect", "addfavorite").any(name::contains) -> 2
                "share" in name || "send" in name -> 0
                else -> 1
            }
        }.firstOrNull() ?: return false
        val explicit = Intent(implicit).apply {
            component = ComponentName(resolved.activityInfo.packageName, resolved.activityInfo.name)
        }
        return runCatching {
            startActivity(explicit)
            true
        }.getOrDefault(false)
    }

    /** Opens a package-owned deep link after Android confirms that the package resolves it. */
    fun launchViewUri(packageName: String, uri: String): Boolean {
        if (packageName.isBlank() || uri.isBlank()) return false
        val implicit = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
            setPackage(packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val resolved = packageManager.resolveActivity(
            implicit,
            android.content.pm.PackageManager.MATCH_DEFAULT_ONLY,
        ) ?: return false
        val explicit = Intent(implicit).apply {
            component = ComponentName(resolved.activityInfo.packageName, resolved.activityInfo.name)
        }
        return runCatching {
            startActivity(explicit)
            true
        }.getOrDefault(false)
    }

    /**
     * Last-resort Unicode input for apps that hide their editable node from accessibility.
     * The caller must supply a positively verified field location. Android's paste toolbar is
     * then found semantically by label, so neither an unrelated field nor the popup is guessed.
     */
    suspend fun pasteFromContextMenuAt(x: Int, y: Int): Boolean {
        if (!tap(x, y, durationMillis = 700L)) return false
        delay(350)
        val pasteNode = findFirstNodeByLabels(
            rootInActiveWindow,
            setOf("粘贴", "paste"),
        ) ?: return false
        var current: AccessibilityNodeInfo? = pasteNode
        repeat(MAX_ACTION_ANCESTORS) {
            val candidate = current ?: return@repeat
            if (candidate.isClickable && candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
            current = candidate.parent
        }
        return pasteNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    internal fun stageClipboardText(text: String): GuiClipboardSnapshot? {
        val clipboard = getSystemService(ClipboardManager::class.java) ?: return null
        val previous = runCatching { clipboard.primaryClip }
        val staged = runCatching {
            clipboard.setPrimaryClip(ClipData.newPlainText("Murong phone input", text))
        }.isSuccess
        if (!staged) return null
        return GuiClipboardSnapshot(
            previous = previous.getOrNull(),
            wasReadable = previous.isSuccess,
        )
    }

    internal fun restoreClipboard(snapshot: GuiClipboardSnapshot) {
        if (!snapshot.wasReadable) return
        val clipboard = getSystemService(ClipboardManager::class.java) ?: return
        runCatching {
            snapshot.previous?.let(clipboard::setPrimaryClip) ?: clipboard.clearPrimaryClip()
        }
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

    suspend fun captureScreenshot(): GuiScreenshot {
        val hiddenOverlay = hideTaskOverlayForAutomation()
        return try {
            withTimeout(SCREENSHOT_CAPTURE_TIMEOUT_MS) {
                captureScreenshotWithoutTaskOverlay()
            }
        } finally {
            restoreTaskOverlayAfterAutomation(hiddenOverlay)
        }
    }

    internal suspend fun hideTaskOverlayForAutomation(): View? =
        suspendCancellableCoroutine { continuation ->
            mainHandler.post {
                val overlay = taskOverlayView?.takeIf { view ->
                    view.visibility == View.VISIBLE
                }
                if (overlay == null) {
                    if (continuation.isActive) continuation.resume(null)
                    return@post
                }
                overlay.visibility = View.INVISIBLE
                // An INVISIBLE view no longer schedules its own draw callbacks. Give the
                // compositor two display frames from the main handler instead of waiting on
                // postOnAnimation, which would otherwise suspend forever.
                mainHandler.postDelayed({
                    if (continuation.isActive) continuation.resume(overlay)
                }, SCREENSHOT_OVERLAY_HIDE_DELAY_MS)
            }
        }

    internal fun restoreTaskOverlayAfterAutomation(hiddenOverlay: View?) {
        hiddenOverlay ?: return
        mainHandler.post {
            if (
                taskOverlayView === hiddenOverlay &&
                !taskOverlayHiddenByUser &&
                hiddenOverlay.isAttachedToWindow
            ) {
                hiddenOverlay.visibility = View.VISIBLE
            }
        }
    }

    private suspend fun captureScreenshotWithoutTaskOverlay(): GuiScreenshot =
        suspendCancellableCoroutine { continuation ->
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

    private fun findFirstNodeByLabels(
        node: AccessibilityNodeInfo?,
        labels: Set<String>,
    ): AccessibilityNodeInfo? {
        node ?: return null
        val text = node.text?.toString()?.trim()?.lowercase().orEmpty()
        val description = node.contentDescription?.toString()?.trim()?.lowercase().orEmpty()
        if (text in labels || description in labels) return node
        for (index in 0 until node.childCount) {
            findFirstNodeByLabels(node.getChild(index), labels)?.let { return it }
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
        private const val SEARCH_CONTROL_TIMEOUT_MS = 4_000L
        private const val FOCUSED_TEXT_VERIFY_TIMEOUT_MS = 360L
        private const val FOCUSED_TEXT_VERIFY_INTERVAL_MS = 60L
        private const val SCREENSHOT_OVERLAY_HIDE_DELAY_MS = 40L
        private const val SCREENSHOT_CAPTURE_TIMEOUT_MS = 10_000L
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
