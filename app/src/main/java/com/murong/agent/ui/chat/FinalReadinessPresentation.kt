package com.murong.agent.ui.chat

import com.murong.agent.core.loop.FinalReadinessReceipt
import com.murong.agent.core.loop.FinalReadinessReceiptKind
import com.murong.agent.core.loop.FinalReadinessRequiredAction
import com.murong.agent.ui.sanitizeForUiDisplay

internal data class FinalReadinessPresentation(
    val title: String,
    val message: String
)

internal fun FinalReadinessReceipt.toFinalReadinessPresentation(): FinalReadinessPresentation {
    val title = when (kind) {
        FinalReadinessReceiptKind.MISSING_COMPLETE_STEP_AFTER_WRITE -> "需要继续确认"
        FinalReadinessReceiptKind.INCOMPLETE_CANONICAL_WORKFLOW -> "还有步骤未完成"
    }
    val lines = buildList {
        add(sanitizeForUiDisplay(message))
        add(
            when (requiredAction) {
                FinalReadinessRequiredAction.SIGN_OFF_WITH_EVIDENCE ->
                    "需要动作：先确认刚才的修改结果，再继续。"
                FinalReadinessRequiredAction.COMPLETE_REMAINING_PLAN ->
                    "需要动作：先完成剩余步骤，再回来结束本轮任务。"
            }
        )
        latestSuccessfulWriteToolName
            ?.takeIf { it.isNotBlank() }
            ?.let { add("最近成功修改：${sanitizeForUiDisplay(it)}") }
        remainingUnsignedSteps?.let { add("剩余步骤：$it") }
        nextRequiredStep
            ?.takeIf { it.isNotBlank() }
            ?.let { add("下一步计划：${sanitizeForUiDisplay(it)}") }
        latestSignedOffStep
            ?.takeIf { it.isNotBlank() }
            ?.let { step ->
                val summary = latestSignedOffResultSummary
                    ?.takeIf { it.isNotBlank() }
                    ?.let { "，结果：${sanitizeForUiDisplay(it)}" }
                    .orEmpty()
                add("最近已确认：${sanitizeForUiDisplay(step)}$summary")
            }
        latestSignedOffMatchedTools
            .takeIf { it.isNotEmpty() }
            ?.let { tools ->
                add("相关工具：${tools.joinToString(", ") { sanitizeForUiDisplay(it) }}")
            }
    }
    return FinalReadinessPresentation(
        title = title,
        message = lines.joinToString("\n")
    )
}
