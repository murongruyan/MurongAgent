package com.murong.agent.ui.assistant

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.murong.agent.shizuku.ShizukuSystemAccess
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Interactive viewer for the Root- or Shizuku-owned Phone Agent display.
 *
 * The virtual display stays isolated, so opening this screen does not move or restart the target
 * app. Frames are mirrored locally and touches are injected back into that same display.
 */
class AssistantIsolatedDisplayActivity : ComponentActivity() {
    private lateinit var displayView: IsolatedDisplayView
    private lateinit var rootContainer: FrameLayout
    private lateinit var toolbar: LinearLayout
    private lateinit var fullscreenHandle: TextView
    private lateinit var fullscreenMenu: LinearLayout
    private var isFullscreen = false
    private val collapseMenuRunnable = Runnable { collapseFullscreenMenu() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ShizukuSystemAccess.initialize(applicationContext)
        window.statusBarColor = Color.rgb(22, 24, 32)
        window.navigationBarColor = Color.BLACK

        rootContainer = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(7), dp(10), dp(7))
            setBackgroundColor(Color.rgb(31, 35, 48))
        }
        val chat = actionPill(AssistantOffscreenActionLabels.RETURN_CHAT, filled = false, compact = true) {
            returnToChat()
        }
        val assistant = actionPill(
            AssistantOffscreenActionLabels.RETURN_VOICE_ASSISTANT,
            filled = false,
            compact = true,
        ) {
            returnToVoiceAssistant()
        }
        val fullscreen = actionPill(
            AssistantOffscreenActionLabels.FULLSCREEN_PREVIEW,
            filled = false,
            compact = true,
        ) {
            setFullscreen(true)
        }
        val takeover = actionPill(
            AssistantOffscreenActionLabels.FULLSCREEN_TAKEOVER,
            filled = false,
            compact = true,
        ) {
            handoffToMainScreen()
        }
        val stop = actionPill(
            AssistantOffscreenActionLabels.CLOSE_OFFSCREEN,
            filled = true,
            compact = true,
        ) {
            closeOffscreen()
        }
        toolbar.setPadding(dp(8), dp(7), dp(8), dp(7))
        toolbar.addView(chat, LinearLayout.LayoutParams(-2, dp(36)).apply { marginEnd = dp(4) })
        toolbar.addView(assistant, LinearLayout.LayoutParams(-2, dp(36)).apply { marginEnd = dp(4) })
        toolbar.addView(fullscreen, LinearLayout.LayoutParams(-2, dp(36)).apply { marginEnd = dp(4) })
        toolbar.addView(takeover, LinearLayout.LayoutParams(-2, dp(36)).apply { marginEnd = dp(4) })
        toolbar.addView(stop, LinearLayout.LayoutParams(-2, dp(36)))

        displayView = IsolatedDisplayView()
        content.addView(toolbar, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        content.addView(displayView, LinearLayout.LayoutParams(-1, 0, 1f))
        rootContainer.addView(content, FrameLayout.LayoutParams(-1, -1))

        fullscreenHandle = TextView(this).apply {
            text = "·"
            textSize = 25f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            contentDescription = "打开离屏全屏控制"
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(205, 218, 74, 128))
            }
            visibility = View.GONE
            setOnClickListener { expandFullscreenMenu() }
        }
        rootContainer.addView(
            fullscreenHandle,
            FrameLayout.LayoutParams(dp(34), dp(34), Gravity.TOP or Gravity.END).apply {
                topMargin = dp(10)
                marginEnd = dp(10)
            },
        )
        fullscreenMenu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(7), dp(6), dp(7), dp(6))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(24).toFloat()
                setColor(Color.argb(225, 31, 35, 48))
            }
            visibility = View.GONE
            addView(
                actionPill("退出全屏预览", filled = false) { setFullscreen(false) },
                LinearLayout.LayoutParams(-1, dp(36)).apply { bottomMargin = dp(7) },
            )
            addView(
                actionPill(AssistantOffscreenActionLabels.FULLSCREEN_TAKEOVER, filled = false) {
                    handoffToMainScreen()
                },
                LinearLayout.LayoutParams(-1, dp(36)).apply { bottomMargin = dp(7) },
            )
            addView(
                actionPill(AssistantOffscreenActionLabels.RETURN_CHAT, filled = false) { returnToChat() },
                LinearLayout.LayoutParams(-1, dp(36)).apply { bottomMargin = dp(7) },
            )
            addView(
                actionPill(AssistantOffscreenActionLabels.RETURN_VOICE_ASSISTANT, filled = false) {
                    returnToVoiceAssistant()
                },
                LinearLayout.LayoutParams(-1, dp(36)).apply { bottomMargin = dp(7) },
            )
            addView(
                actionPill(AssistantOffscreenActionLabels.CLOSE_OFFSCREEN, filled = true) {
                    closeOffscreen()
                },
                LinearLayout.LayoutParams(-1, dp(36)),
            )
        }
        rootContainer.addView(
            fullscreenMenu,
            FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
                topMargin = dp(10)
                marginEnd = dp(10)
            },
        )
        ViewCompat.setOnApplyWindowInsetsListener(rootContainer) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.statusBars() or
                    WindowInsetsCompat.Type.navigationBars() or
                    WindowInsetsCompat.Type.displayCutout(),
            )
            if (isFullscreen) {
                rootContainer.setPadding(0, 0, 0, 0)
                toolbar.setPadding(dp(8), dp(7), dp(8), dp(7))
            } else {
                rootContainer.setPadding(bars.left, 0, bars.right, bars.bottom)
                // Android 15+ forces edge-to-edge. Put the controls below the status bar instead
                // of letting the clock/network icons cover the title and action pills.
                toolbar.setPadding(dp(8), bars.top + dp(7), dp(8), dp(7))
            }
            insets
        }
        setContentView(rootContainer)
        ViewCompat.requestApplyInsets(rootContainer)
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (!displayView.sendBack()) {
                        Toast.makeText(
                            this@AssistantIsolatedDisplayActivity,
                            "离屏任务尚未连接",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            },
        )
    }

    private fun setFullscreen(enabled: Boolean) {
        if (isFullscreen == enabled) return
        isFullscreen = enabled
        toolbar.visibility = if (enabled) View.GONE else View.VISIBLE
        fullscreenMenu.visibility = View.GONE
        fullscreenHandle.visibility = if (enabled) View.VISIBLE else View.GONE
        if (enabled) fadeFullscreenHandle()
        rootContainer.removeCallbacks(collapseMenuRunnable)
        WindowInsetsControllerCompat(window, rootContainer).apply {
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (enabled) {
                hide(WindowInsetsCompat.Type.systemBars())
            } else {
                show(WindowInsetsCompat.Type.systemBars())
            }
        }
        ViewCompat.requestApplyInsets(rootContainer)
        rootContainer.requestLayout()
        displayView.invalidate()
        if (enabled) {
            Toast.makeText(this, "已进入点对点全屏；返回手势控制离屏页面", Toast.LENGTH_SHORT).show()
        }
    }

    private fun expandFullscreenMenu() {
        if (!isFullscreen) return
        fullscreenHandle.animate().cancel()
        fullscreenHandle.visibility = View.GONE
        fullscreenMenu.visibility = View.VISIBLE
        rootContainer.removeCallbacks(collapseMenuRunnable)
        rootContainer.postDelayed(collapseMenuRunnable, 4_000L)
    }

    private fun collapseFullscreenMenu() {
        if (!isFullscreen) return
        fullscreenMenu.visibility = View.GONE
        fullscreenHandle.visibility = View.VISIBLE
        fadeFullscreenHandle()
    }

    private fun fadeFullscreenHandle() {
        fullscreenHandle.animate().cancel()
        fullscreenHandle.alpha = 0.72f
        fullscreenHandle.animate()
            .alpha(0.24f)
            .setStartDelay(1_200L)
            .setDuration(500L)
            .start()
    }

    private fun returnToChat() {
        startActivity(AssistantTaskEntry.launchIntent(this))
        finish()
    }

    private fun returnToVoiceAssistant() {
        startActivity(AssistantVoicePopupEntry.launchIntent(this))
        finish()
    }

    private fun handoffToMainScreen() {
        startService(
            android.content.Intent(this, AssistantTaskForegroundService::class.java)
                .setAction(AssistantTaskForegroundService.ACTION_HANDOFF_DISPLAY),
        )
        finish()
    }

    private fun closeOffscreen() {
        startService(
            android.content.Intent(this, AssistantTaskForegroundService::class.java)
                .setAction(ACTION_CANCEL_TASK),
        )
        finish()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt().coerceAtLeast(1)

    private fun actionPill(
        label: String,
        filled: Boolean,
        compact: Boolean = false,
        action: () -> Unit,
    ): TextView =
        TextView(this).apply {
            text = label
            textSize = if (compact) 11f else 12f
            gravity = Gravity.CENTER
            setPadding(dp(if (compact) 7 else 9), 0, dp(if (compact) 7 else 9), 0)
            setTextColor(if (filled) Color.WHITE else Color.rgb(255, 151, 188))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(18).toFloat()
                if (filled) {
                    setColor(Color.rgb(218, 74, 128))
                } else {
                    setColor(Color.TRANSPARENT)
                    setStroke(dp(1), Color.rgb(180, 91, 126))
                }
            }
            setOnClickListener { action() }
        }

    private inner class IsolatedDisplayView : View(this@AssistantIsolatedDisplayActivity) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.LTGRAY
            textSize = dp(15).toFloat()
            textAlign = Paint.Align.CENTER
        }
        private val infoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = dp(12).toFloat()
        }
        private val infoBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(168, 18, 20, 28)
        }
        private val imageRect = RectF()
        private var renderScope: CoroutineScope? = null
        private var renderJob: Job? = null
        private var frame: Bitmap? = null
        private var status = "正在连接离屏任务…"
        private var streamInfo = ""
        @Volatile private var displayId = -1
        private val displayWidth = resources.displayMetrics.widthPixels
        private val displayHeight = resources.displayMetrics.heightPixels
        private var downX = 0f
        private var downY = 0f
        private var downAt = 0L

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            if (renderJob != null) return
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            renderScope = scope
            renderJob = scope.launch { renderFrames() }
        }

        override fun onDetachedFromWindow() {
            renderScope?.cancel()
            renderScope = null
            renderJob = null
            frame?.recycle()
            frame = null
            super.onDetachedFromWindow()
        }

        private suspend fun renderFrames() {
            val configured = AssistantOffscreenDisplayPreferences.read(this@AssistantIsolatedDisplayActivity)
            val captureLongEdge = if (configured.maxLongEdge == 0) {
                maxOf(displayWidth, displayHeight)
            } else {
                configured.maxLongEdge
            }
            val requestedFps = if (configured.targetFps == 0) {
                (display?.refreshRate ?: 60f).roundToInt().coerceIn(10, 240)
            } else {
                configured.targetFps
            }
            val framePeriodMs = (1_000L / requestedFps).coerceAtLeast(1L)
            var adaptiveQuality = configured.jpegQuality
            var measuredFrames = 0
            var measurementStartedAt = SystemClock.elapsedRealtime()
            while (renderScope?.isActive == true) {
                val frameStartedAt = SystemClock.elapsedRealtime()
                try {
                    val service = ShizukuSystemAccess.agentDisplayService()
                    val currentId = service?.currentAgentDisplayId() ?: -1
                    displayId = currentId
                    if (currentId < 0 || service == null) {
                        clearFrameAndUpdateStatus("当前没有运行中的离屏任务")
                        delay(800)
                        continue
                    }
                    val descriptor = service.captureAgentDisplayJpegFile(
                        currentId,
                        captureLongEdge,
                        adaptiveQuality,
                    )
                    val bytes = ParcelFileDescriptor.AutoCloseInputStream(descriptor).use {
                        it.readBytes()
                    }
                    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (decoded == null) {
                        updateStatus("离屏画面暂不可用，正在重试…")
                    } else {
                        measuredFrames += 1
                        val measuredAt = SystemClock.elapsedRealtime()
                        val elapsedMeasurement = measuredAt - measurementStartedAt
                        val actualFps = if (elapsedMeasurement >= 1_000L) {
                            (measuredFrames * 1_000f / elapsedMeasurement).also {
                                measuredFrames = 0
                                measurementStartedAt = measuredAt
                            }
                        } else {
                            null
                        }
                        val targetBytesPerFrame = configured.targetBitrateMbps
                            .takeIf { it > 0 }
                            ?.let { it * 1_000_000L / 8L / requestedFps }
                        if (targetBytesPerFrame != null) {
                            adaptiveQuality = when {
                                bytes.size > targetBytesPerFrame * 1.15f ->
                                    (adaptiveQuality - 2).coerceAtLeast(55)
                                bytes.size < targetBytesPerFrame * 0.72f ->
                                    (adaptiveQuality + 1).coerceAtMost(configured.jpegQuality)
                                else -> adaptiveQuality
                            }
                        }
                        post {
                            val old = frame
                            frame = decoded
                            status = ""
                            actualFps?.let { fps ->
                                streamInfo = "${decoded.width} × ${decoded.height}  ·  " +
                                    "%.1f FPS / %d".format(fps, requestedFps)
                            }
                            invalidate()
                            old?.takeUnless { it === decoded }?.recycle()
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    updateStatus("离屏连接中断，正在重连…")
                }
                val elapsed = SystemClock.elapsedRealtime() - frameStartedAt
                if (elapsed < framePeriodMs) delay(framePeriodMs - elapsed)
            }
        }

        private fun updateStatus(message: String) {
            post {
                status = message
                invalidate()
            }
        }

        private fun clearFrameAndUpdateStatus(message: String) {
            post {
                val staleFrame = frame
                frame = null
                imageRect.setEmpty()
                streamInfo = ""
                status = message
                invalidate()
                staleFrame?.recycle()
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawColor(Color.BLACK)
            val bitmap = frame
            if (bitmap == null) {
                canvas.drawText(status, width / 2f, height / 2f, textPaint)
                return
            }
            val scale = minOf(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
            val targetWidth = bitmap.width * scale
            val targetHeight = bitmap.height * scale
            val left = (width - targetWidth) / 2f
            val top = (height - targetHeight) / 2f
            imageRect.set(left, top, left + targetWidth, top + targetHeight)
            canvas.drawBitmap(bitmap, null, imageRect, paint)
            if (streamInfo.isNotBlank() && !isFullscreen) {
                val padding = dp(8).toFloat()
                val infoWidth = infoPaint.measureText(streamInfo) + padding * 2
                val infoHeight = dp(28).toFloat()
                val infoTop = height - infoHeight - dp(10)
                canvas.drawRoundRect(
                    dp(10).toFloat(),
                    infoTop,
                    dp(10) + infoWidth,
                    infoTop + infoHeight,
                    dp(10).toFloat(),
                    dp(10).toFloat(),
                    infoBackgroundPaint,
                )
                canvas.drawText(
                    streamInfo,
                    dp(10) + padding,
                    infoTop + dp(19),
                    infoPaint,
                )
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (displayId < 0 || !imageRect.contains(event.x, event.y)) return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    downAt = SystemClock.uptimeMillis()
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    val currentId = displayId
                    val start = toDisplayPoint(downX, downY)
                    val end = toDisplayPoint(event.x, event.y)
                    val duration = (SystemClock.uptimeMillis() - downAt).toInt().coerceIn(50, 5_000)
                    val moved = abs(event.x - downX) > dp(10) || abs(event.y - downY) > dp(10)
                    renderScope?.launch {
                        val service = ShizukuSystemAccess.agentDisplayService() ?: return@launch
                        if (moved) {
                            service.swipeAgentDisplay(
                                currentId,
                                start.first,
                                start.second,
                                end.first,
                                end.second,
                                duration,
                            )
                        } else {
                            service.tapAgentDisplay(currentId, end.first, end.second)
                        }
                    }
                    performClick()
                    return true
                }
                MotionEvent.ACTION_CANCEL -> return true
            }
            return true
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }

        fun sendBack(): Boolean {
            val currentId = displayId
            if (currentId < 0) return false
            renderScope?.launch {
                ShizukuSystemAccess.agentDisplayService()
                    ?.keyAgentDisplay(currentId, "BACK")
            }
            return true
        }

        private fun toDisplayPoint(x: Float, y: Float): Pair<Int, Int> {
            val normalizedX = ((x - imageRect.left) / imageRect.width()).coerceIn(0f, 1f)
            val normalizedY = ((y - imageRect.top) / imageRect.height()).coerceIn(0f, 1f)
            return Pair(
                (normalizedX * displayWidth).toInt().coerceIn(0, displayWidth - 1),
                (normalizedY * displayHeight).toInt().coerceIn(0, displayHeight - 1),
            )
        }
    }

    private companion object {
        const val ACTION_CANCEL_TASK = "com.murong.agent.action.CANCEL_ASSISTANT_TASK"
    }
}
