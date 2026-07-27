package com.murong.agent.ui.assistant

import android.app.assist.AssistStructure
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.service.voice.VoiceInteractionSession
import android.text.InputType
import com.murong.agent.core.loop.PendingImageAttachmentUi
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class VoiceAssistantScreenContextState(
    val requestedText: Boolean = false,
    val requestedScreenshot: Boolean = false,
    val textDisabledByUser: Boolean = false,
    val screenshotDisabledByUser: Boolean = false,
    val activityName: String? = null,
    val visibleText: String = "",
    val screenshotAvailable: Boolean = false,
)

/** A user-drawn rectangle expressed as fractions of the supplied system screenshot. */
internal data class ScreenSelection(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    fun crop(source: Bitmap): Bitmap {
        val safeLeft = min(left, right).coerceIn(0f, 1f)
        val safeTop = min(top, bottom).coerceIn(0f, 1f)
        val safeRight = max(left, right).coerceIn(0f, 1f)
        val safeBottom = max(top, bottom).coerceIn(0f, 1f)
        val x = (safeLeft * source.width).toInt().coerceIn(0, source.width - 1)
        val y = (safeTop * source.height).toInt().coerceIn(0, source.height - 1)
        val width = ((safeRight * source.width).toInt() - x).coerceAtLeast(1)
            .coerceAtMost(source.width - x)
        val height = ((safeBottom * source.height).toInt() - y).coerceAtLeast(1)
            .coerceAtMost(source.height - y)
        return Bitmap.createBitmap(source, x, y, width, height)
    }
}

/**
 * Holds only the current system-assistant invocation context. Text is bounded and password-like
 * fields are excluded. Screenshots remain in memory until a request imports one into the normal
 * conversation-media pipeline.
 */
internal object VoiceAssistantScreenContext {
    private const val MAX_VISIBLE_TEXT_CHARS = 6_000
    private const val MAX_VISIBLE_TEXT_ITEMS = 180
    private val screenshot = AtomicReference<Bitmap?>(null)
    private val _state = MutableStateFlow(VoiceAssistantScreenContextState())
    val state: StateFlow<VoiceAssistantScreenContextState> = _state.asStateFlow()

    fun begin(showFlags: Int, userDisabledFlags: Int) {
        replaceScreenshot(null)
        _state.value = VoiceAssistantScreenContextState(
            requestedText = showFlags and VoiceInteractionSession.SHOW_WITH_ASSIST != 0,
            requestedScreenshot = showFlags and VoiceInteractionSession.SHOW_WITH_SCREENSHOT != 0,
            textDisabledByUser =
                userDisabledFlags and VoiceInteractionSession.SHOW_WITH_ASSIST != 0,
            screenshotDisabledByUser =
                userDisabledFlags and VoiceInteractionSession.SHOW_WITH_SCREENSHOT != 0,
        )
    }

    fun updateStructure(structure: AssistStructure?) {
        structure ?: return
        val textItems = linkedSetOf<String>()
        repeat(structure.windowNodeCount) { windowIndex ->
            collectNodeText(structure.getWindowNodeAt(windowIndex).rootViewNode, textItems)
        }
        _state.value = _state.value.copy(
            activityName = structure.activityComponent?.flattenToShortString(),
            visibleText = textItems.joinToString("\n").take(MAX_VISIBLE_TEXT_CHARS),
        )
    }

    fun updateScreenshot(bitmap: Bitmap?) {
        val safeCopy = bitmap?.let { source ->
            runCatching { source.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()
        }
        replaceScreenshot(safeCopy)
        _state.value = _state.value.copy(screenshotAvailable = safeCopy != null)
    }

    fun buildModelPrompt(userText: String): String {
        val current = _state.value
        if (current.visibleText.isBlank() && current.activityName.isNullOrBlank()) return userText
        return buildString {
            appendLine("[当前屏幕上下文：由 Android 系统助手权限为本次请求提供]")
            current.activityName?.let { appendLine("前台界面：$it") }
            if (current.visibleText.isNotBlank()) {
                appendLine("屏幕可见文字：")
                appendLine(current.visibleText)
            }
            appendLine("[用户语音指令]")
            append(userText)
        }
    }

    /** The returned bitmap remains owned by this object; callers must not recycle or mutate it. */
    fun currentScreenshot(): Bitmap? = screenshot.get()

    fun createScreenshotAttachment(
        context: Context,
        selection: ScreenSelection? = null,
    ): PendingImageAttachmentUi? {
        val current = screenshot.get() ?: return null
        val directory = File(context.cacheDir, "assistant-captures").also { folder ->
            folder.mkdirs()
            folder.listFiles()
                ?.filter { System.currentTimeMillis() - it.lastModified() > 24 * 60 * 60 * 1_000L }
                ?.forEach(File::delete)
        }
        val target = File(directory, "screen-${System.currentTimeMillis()}.png")
        val saved = runCatching {
            val cropped = selection?.crop(current)
            try {
                target.outputStream().use { output ->
                    check((cropped ?: current).compress(Bitmap.CompressFormat.PNG, 100, output))
                }
            } finally {
                cropped?.recycle()
            }
            true
        }.getOrDefault(false)
        if (!saved) {
            target.delete()
            return null
        }
        return PendingImageAttachmentUi(
            uri = Uri.fromFile(target).toString(),
            fileName = "当前屏幕.png",
            mimeType = "image/png",
        )
    }

    fun clear() {
        replaceScreenshot(null)
        _state.value = VoiceAssistantScreenContextState()
    }

    private fun collectNodeText(
        node: AssistStructure.ViewNode?,
        output: LinkedHashSet<String>,
    ) {
        node ?: return
        if (output.size >= MAX_VISIBLE_TEXT_ITEMS || node.isAssistBlocked) return
        if (!node.isPasswordLike()) {
            listOf(node.text, node.contentDescription, node.hint)
                .mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotBlank) }
                .forEach { value ->
                    if (output.size < MAX_VISIBLE_TEXT_ITEMS) output += value.take(500)
                }
        }
        repeat(node.childCount) { index ->
            if (output.size < MAX_VISIBLE_TEXT_ITEMS) collectNodeText(node.getChildAt(index), output)
        }
    }

    private fun AssistStructure.ViewNode.isPasswordLike(): Boolean {
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
            variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
    }

    private fun replaceScreenshot(next: Bitmap?) {
        screenshot.getAndSet(next)?.takeUnless { it === next }?.recycle()
    }
}
