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
        FinalReadinessReceiptKind.MISSING_COMPLETE_STEP_AFTER_WRITE -> "最终签收"
        FinalReadinessReceiptKind.INCOMPLETE_CANONICAL_WORKFLOW -> "计划收口"
    }
    val lines = buildList {
        add(sanitizeForUiDisplay(message))
        add(
            when (requiredAction) {
                FinalReadinessRequiredAction.SIGN_OFF_WITH_EVIDENCE ->
                    "需要动作：确认修改结果后调用 complete_step 完成本轮签收。"
                FinalReadinessRequiredAction.COMPLETE_REMAINING_PLAN ->
                    "需要动作：先完成剩余步骤，再回来结束本轮任务。"
            }
        )
        latestSuccessfulWriteToolName
            ?.takeIf { it.isNotBlank() }
            ?.let { add("最近成功写工具：${sanitizeForUiDisplay(it)}") }
        remainingUnsignedSteps?.let { add("剩余未签收步骤：$it") }
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
                add("最近已签收：${sanitizeForUiDisplay(step)}$summary")
            }
        latestSignedOffMatchedTools
            .takeIf { it.isNotEmpty() }
            ?.let { tools ->
                add("签收工具：${tools.joinToString(", ") { sanitizeForUiDisplay(it) }}")
            }
    }
    return FinalReadinessPresentation(
        title = title,
        message = lines.joinToString("\n")
    )
}
