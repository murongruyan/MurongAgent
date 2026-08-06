package com.murong.agent.ui.assistant

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class AssistantOffscreenTaskSnapshot(
    val request: String,
    val title: String,
    val detail: String,
    val active: Boolean,
    val displayAvailable: Boolean,
    val progressHistory: List<String>,
    val modelOutput: String,
    val modelOutputHistory: List<String>,
    val executionHistory: List<String>,
)

/** Shared labels keep the viewer, chat card and promoted live update in sync. */
internal object AssistantOffscreenActionLabels {
    const val RETURN_CHAT = "返回聊天"
    const val RETURN_VOICE_ASSISTANT = "回到语音助手"
    const val OFFSCREEN_CONTROLS = "离屏控制"
    const val FULLSCREEN_PREVIEW = "全屏预览"
    const val FULLSCREEN_TAKEOVER = "全屏接管"
    const val CLOSE_OFFSCREEN = "关闭离屏"

    /** ColorOS currently renders only three actions on the promoted live card. */
    val fluidCloud: List<String> = listOf(
        RETURN_CHAT,
        RETURN_VOICE_ASSISTANT,
        OFFSCREEN_CONTROLS,
    )

    val all: List<String> = listOf(
        RETURN_CHAT,
        RETURN_VOICE_ASSISTANT,
        FULLSCREEN_PREVIEW,
        FULLSCREEN_TAKEOVER,
        CLOSE_OFFSCREEN,
    )
}

internal enum class AssistantPhoneLiveThirdAction(val label: String) {
    OPEN_OFFSCREEN(AssistantOffscreenActionLabels.OFFSCREEN_CONTROLS),
    CONFIRM_AND_CLOSE("确认并关闭"),
    STOP("停止"),
}

/**
 * ColorOS exposes only three promoted-live actions. If a retained display is available, the
 * third slot must keep the review entry instead of turning into a destructive close shortcut.
 * Closing remains available inside the viewer and chat card.
 */
internal fun assistantPhoneLiveThirdAction(
    offscreenAvailable: Boolean,
    retainForReview: Boolean,
): AssistantPhoneLiveThirdAction = when {
    offscreenAvailable -> AssistantPhoneLiveThirdAction.OPEN_OFFSCREEN
    retainForReview -> AssistantPhoneLiveThirdAction.CONFIRM_AND_CLOSE
    else -> AssistantPhoneLiveThirdAction.STOP
}

internal fun isAssistantModelProgress(value: String): Boolean =
    value.startsWith(MODEL_STREAM_PREFIX) || value.startsWith(MODEL_REPLY_PREFIX)

internal fun isAssistantInferenceHeartbeat(value: String): Boolean =
    value.contains("模型仍在分析当前截图，已等待")

internal fun shouldReplaceAssistantProgress(previous: String, current: String): Boolean =
    (isAssistantModelProgress(previous) && isAssistantModelProgress(current)) ||
        (isAssistantInferenceHeartbeat(previous) && isAssistantInferenceHeartbeat(current))

private fun isAssistantExecutionProgress(value: String): Boolean {
    if (isAssistantModelProgress(value) || isAssistantInferenceHeartbeat(value)) return false
    if (value.contains("正在读取当前屏幕")) return false
    if (value.contains("正在识别界面并规划下一步")) return false
    return value.isNotBlank()
}

/** Process-local source of truth shared by the foreground service and the chat status bubble. */
internal object AssistantOffscreenTaskState {
    private val mutableState = MutableStateFlow<AssistantOffscreenTaskSnapshot?>(null)
    val state: StateFlow<AssistantOffscreenTaskSnapshot?> = mutableState.asStateFlow()

    fun begin(request: String, title: String, detail: String) {
        mutableState.value = AssistantOffscreenTaskSnapshot(
            request = request,
            title = title,
            detail = detail,
            active = true,
            displayAvailable = false,
            progressHistory = listOf(detail).filter(String::isNotBlank),
            modelOutput = "",
            modelOutputHistory = emptyList(),
            executionHistory = listOf(detail).filter(String::isNotBlank),
        )
    }

    fun update(
        title: String,
        detail: String,
        active: Boolean,
        displayAvailable: Boolean? = null,
        progressEntry: String? = null,
    ) {
        val previous = mutableState.value ?: return
        val normalizedProgress = progressEntry?.trim().orEmpty()
        val modelText = normalizedProgress
            .removePrefix(MODEL_STREAM_PREFIX)
            .removePrefix(MODEL_REPLY_PREFIX)
            .trim()
        val isModelEntry = isAssistantModelProgress(normalizedProgress)
        val lastProgress = previous.progressHistory.lastOrNull()
        val replacesPriorModelPartial = isModelEntry &&
            lastProgress?.let(::isAssistantModelProgress) == true
        val nextHistory = when {
            normalizedProgress.isBlank() || previous.progressHistory.lastOrNull() == normalizedProgress ->
                previous.progressHistory
            lastProgress != null && shouldReplaceAssistantProgress(lastProgress, normalizedProgress) ->
                (previous.progressHistory.dropLast(1) + normalizedProgress)
                    .takeLast(MAX_PROGRESS_HISTORY)
            else -> (previous.progressHistory + normalizedProgress).takeLast(MAX_PROGRESS_HISTORY)
        }
        val nextExecutionHistory = if (
            isAssistantExecutionProgress(normalizedProgress) &&
            previous.executionHistory.lastOrNull() != normalizedProgress
        ) {
            (previous.executionHistory + normalizedProgress).takeLast(MAX_EXECUTION_HISTORY)
        } else {
            previous.executionHistory
        }
        val nextModelOutputHistory = when {
            !isModelEntry || modelText.isBlank() -> previous.modelOutputHistory
            replacesPriorModelPartial && previous.modelOutputHistory.isNotEmpty() -> {
                previous.modelOutputHistory.dropLast(1) + mergeAssistantModelText(
                    previous.modelOutputHistory.last(),
                    modelText,
                )
            }
            previous.modelOutputHistory.lastOrNull() == modelText -> previous.modelOutputHistory
            else -> (previous.modelOutputHistory + modelText).takeLast(MAX_MODEL_OUTPUT_HISTORY)
        }
        mutableState.value = previous.copy(
            title = title,
            detail = detail,
            active = active,
            displayAvailable = displayAvailable ?: previous.displayAvailable,
            progressHistory = nextHistory,
            modelOutput = nextModelOutputHistory.joinToString("\n\n"),
            modelOutputHistory = nextModelOutputHistory,
            executionHistory = nextExecutionHistory,
        )
    }

    private const val MAX_PROGRESS_HISTORY = 40
    private const val MAX_EXECUTION_HISTORY = 40
    private const val MAX_MODEL_OUTPUT_HISTORY = 40
}

private fun mergeAssistantModelText(previous: String, current: String): String = when {
    current == previous -> previous
    current.contains(previous) -> current
    previous.contains(current) -> previous
    else -> "$previous\n$current"
}

private const val MODEL_STREAM_PREFIX = "模型正在说："
private const val MODEL_REPLY_PREFIX = "模型回复："
