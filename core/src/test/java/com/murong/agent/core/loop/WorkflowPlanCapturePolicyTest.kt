package com.murong.agent.core.loop

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkflowPlanCapturePolicyTest {

    @Test
    fun looksLikeWorkflowPlanText_acceptsSummaryStructure() {
        val raw = """
            SUMMARY: 修复登录模块
            STEPS:
            1. 检查现有实现
            2. 修改认证逻辑
            3. 运行验证
        """.trimIndent()

        assertTrue(looksLikeWorkflowPlanText(raw))
    }

    @Test
    fun looksLikeWorkflowPlanText_acceptsNumberedSteps() {
        assertTrue(looksLikeWorkflowPlanText("1. 检查配置\n2. 修复问题\n3. 验证"))
        assertTrue(looksLikeWorkflowPlanText("1) 检查配置\n2) 修复问题"))
    }

    @Test
    fun looksLikeWorkflowPlanText_acceptsBulletedSteps() {
        assertTrue(looksLikeWorkflowPlanText("- 检查配置\n- 修复问题"))
        assertTrue(looksLikeWorkflowPlanText("* 检查配置\n* 修复问题"))
    }

    @Test
    fun looksLikeWorkflowPlanText_rejectsPlainAnswer() {
        assertFalse(looksLikeWorkflowPlanText("好的，这个问题需要先分析一下，我会先检查相关配置再处理。"))
        assertFalse(looksLikeWorkflowPlanText("已确认，正在处理。"))
    }

    @Test
    fun looksLikeWorkflowPlanText_rejectsBlank() {
        assertFalse(looksLikeWorkflowPlanText(""))
        assertFalse(looksLikeWorkflowPlanText("   \n  "))
    }

    @Test
    fun looksLikeWorkflowPlanText_ignoresCaseForSummary() {
        assertTrue(looksLikeWorkflowPlanText("summary: 一句话总结\nsteps:\n1. 第一步"))
    }
}
