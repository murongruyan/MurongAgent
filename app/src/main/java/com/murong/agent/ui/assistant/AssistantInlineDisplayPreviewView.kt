package com.murong.agent.ui.assistant

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.View
import com.murong.agent.shizuku.ShizukuSystemAccess
import kotlin.math.abs
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Lightweight live preview used inside the chat task card.
 *
 * It reads the already-running Root/Shizuku display and never creates or launches another display,
 * so opening the chat cannot restart the target app or change its task stack.
 */
internal class AssistantInlineDisplayPreviewView(context: Context) : View(context) {
    var onOpenPreview: (() -> Unit)? = null
    var onFrameAspectRatioChanged: ((Float) -> Unit)? = null

    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        color = Color.argb(210, 255, 151, 188)
    }
    private val statusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = sp(13f)
    }
    private val contentRect = RectF()
    private val clipPath = Path()
    private var renderScope: CoroutineScope? = null
    private var frame: Bitmap? = null
    private var frameAspectRatio = DEFAULT_FRAME_ASPECT_RATIO
    private var status = "正在连接离屏实时画面…"

    init {
        isClickable = true
        contentDescription = "离屏实时预览，点击进入全屏预览"
        setOnClickListener { onOpenPreview?.invoke() }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (renderScope != null) return
        ShizukuSystemAccess.initialize(context.applicationContext)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        // Publish the scope before launching. Dispatchers.IO may start immediately; launching
        // inside an assignment's `also` block lets renderFrames observe renderScope == null and
        // exit forever, leaving the card stuck on "正在连接离屏实时画面…".
        renderScope = scope
        scope.launch { renderFrames(scope) }
    }

    override fun onDetachedFromWindow() {
        renderScope?.cancel()
        renderScope = null
        frame?.recycle()
        frame = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val radius = dp(18f)
        canvas.save()
        clipPath.reset()
        clipPath.addRoundRect(
            0f,
            0f,
            width.toFloat(),
            height.toFloat(),
            radius,
            radius,
            Path.Direction.CW,
        )
        canvas.clipPath(clipPath)
        canvas.drawColor(Color.rgb(20, 22, 30))
        val current = frame
        if (current == null || current.isRecycled) {
            canvas.drawText(
                status,
                width / 2f,
                height / 2f - (statusPaint.ascent() + statusPaint.descent()) / 2f,
                statusPaint,
            )
        } else {
            drawWholeFrame(canvas, current)
        }
        canvas.restore()
        canvas.drawRoundRect(
            borderPaint.strokeWidth / 2f,
            borderPaint.strokeWidth / 2f,
            width - borderPaint.strokeWidth / 2f,
            height - borderPaint.strokeWidth / 2f,
            radius,
            radius,
            borderPaint,
        )
    }

    private fun drawWholeFrame(canvas: Canvas, bitmap: Bitmap) {
        val availableWidth = width.toFloat().coerceAtLeast(1f)
        val availableHeight = height.toFloat().coerceAtLeast(1f)
        val scale = min(
            availableWidth / bitmap.width.coerceAtLeast(1),
            availableHeight / bitmap.height.coerceAtLeast(1),
        )
        val scaledWidth = bitmap.width * scale
        val scaledHeight = bitmap.height * scale
        contentRect.set(
            (width - scaledWidth) / 2f,
            (height - scaledHeight) / 2f,
            (width + scaledWidth) / 2f,
            (height + scaledHeight) / 2f,
        )
        canvas.save()
        clipPath.reset()
        clipPath.addRoundRect(contentRect, dp(10f), dp(10f), Path.Direction.CW)
        canvas.clipPath(clipPath)
        canvas.drawBitmap(bitmap, null, contentRect, framePaint)
        canvas.restore()
    }

    private suspend fun renderFrames(scope: CoroutineScope) {
        while (scope.isActive) {
            val startedAt = SystemClock.elapsedRealtime()
            try {
                val service = ShizukuSystemAccess.agentDisplayService()
                val displayId = service?.currentAgentDisplayId() ?: -1
                if (service == null || displayId < 0) {
                    updateStatus("离屏画面尚未就绪…", clearFrame = true)
                    delay(NO_DISPLAY_RETRY_MILLIS)
                    continue
                }
                val descriptor = service.captureAgentDisplayJpegFile(
                    displayId,
                    INLINE_CAPTURE_LONG_EDGE,
                    INLINE_JPEG_QUALITY,
                )
                val bytes = ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
                val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (decoded == null) {
                    updateStatus("离屏画面暂不可用，正在重试…", clearFrame = false)
                } else {
                    post {
                        if (!isAttachedToWindow) {
                            decoded.recycle()
                        } else {
                            val old = frame
                            frame = decoded
                            val decodedAspectRatio =
                                decoded.width.toFloat() / decoded.height.coerceAtLeast(1).toFloat()
                            if (abs(decodedAspectRatio - frameAspectRatio) > ASPECT_RATIO_EPSILON) {
                                frameAspectRatio = decodedAspectRatio
                                onFrameAspectRatioChanged?.invoke(decodedAspectRatio)
                            }
                            status = ""
                            invalidate()
                            old?.takeUnless { it === decoded }?.recycle()
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                updateStatus("离屏连接中断，正在重连…", clearFrame = false)
            }
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            if (elapsed < INLINE_FRAME_PERIOD_MILLIS) {
                delay(INLINE_FRAME_PERIOD_MILLIS - elapsed)
            }
        }
    }

    private fun updateStatus(message: String, clearFrame: Boolean) {
        post {
            status = message
            if (clearFrame) {
                val old = frame
                frame = null
                old?.recycle()
            }
            invalidate()
        }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private fun sp(value: Float): Float =
        value * resources.displayMetrics.density * resources.configuration.fontScale

    private companion object {
        const val INLINE_CAPTURE_LONG_EDGE = 960
        const val INLINE_JPEG_QUALITY = 82
        const val INLINE_FRAME_PERIOD_MILLIS = 100L
        const val NO_DISPLAY_RETRY_MILLIS = 450L
        const val DEFAULT_FRAME_ASPECT_RATIO = 1440f / 3136f
        const val ASPECT_RATIO_EPSILON = 0.001f
    }
}
