package com.murong.agent.ui.chat

import com.murong.agent.core.loop.FinalReadinessReceipt
import com.murong.agent.core.loop.FinalReadinessReceiptKind
import com.murong.agent.core.loop.FinalReadinessRequiredAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FinalReadinessPresentationTest {

    @Test
    fun toFinalReadinessPresentation_formatsMissingCompleteStepReceipt() {
        val receipt = FinalReadinessReceipt(
            kind = FinalReadinessReceiptKind.MISSING_COMPLETE_STEP_AFTER_WRITE,
            requiredAction = FinalReadinessRequiredAction.SIGN_OFF_WITH_EVIDENCE,
            message = "最终收口校验未通过：写工具 `code_edit` 已成功执行。",
            latestSuccessfulWriteToolName = "code_edit"
        )

        val presentation = receipt.toFinalReadinessPresentation()

        assertEquals("最终签收", presentation.title)
        assertTrue(presentation.message.contains("complete_step"))
        assertTrue(presentation.message.contains("最近成功写工具：code_edit"))
    }

    @Test
    fun toFinalReadinessPresentation_formatsCanonicalWorkflowReceipt() {
        val receipt = FinalReadinessReceipt(
            kind = FinalReadinessReceiptKind.INCOMPLETE_CANONICAL_WORKFLOW,
            requiredAction = FinalReadinessRequiredAction.COMPLETE_REMAINING_PLAN,
            message = "跨轮次计划仍未完成：还剩 2 个未签收步骤。",
            remainingUnsignedSteps = 2,
            nextRequiredStep = "修复工作流",
            latestSignedOffStep = "定位问题",
            latestSignedOffResultSummary = "已经完成排查",
            latestSignedOffMatchedTools = listOf("shell", "code_edit")
        )

        val presentation = receipt.toFinalReadinessPresentation()

        assertEquals("计划收口", presentation.title)
        assertTrue(presentation.message.contains("剩余未签收步骤：2"))
        assertTrue(presentation.message.contains("下一步计划：修复工作流"))
        assertTrue(presentation.message.contains("最近已签收：定位问题，结果：已经完成排查"))
        assertTrue(presentation.message.contains("签收工具：shell, code_edit"))
    }
}
