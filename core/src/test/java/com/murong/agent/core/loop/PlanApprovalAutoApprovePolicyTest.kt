package com.murong.agent.core.loop

import com.murong.agent.core.tool.ApprovalRiskLevel
import com.murong.agent.core.tool.ToolApprovalRequest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlanApprovalAutoApprovePolicyTest {

    @Test
    fun resolvePlanApprovalAutoApproveDecision_allowsOrdinaryToolDuringPlanWindow() {
        val decision = resolvePlanApprovalAutoApproveDecision(
            window = PlanApprovalAutoApproveWindow(
                planId = "plan-1",
                planGoal = "修复工作流"
            ),
            request = ToolApprovalRequest(
                toolName = "code_edit",
                summary = "修改代码文件",
                detail = "/tmp/a.kt",
                riskLevel = ApprovalRiskLevel.HIGH,
                rawArgs = "{}"
            )
        )

        assertTrue(decision.autoApprove)
        assertTrue(decision.detail?.contains("修复工作流") == true)
    }

    @Test
    fun resolvePlanApprovalAutoApproveDecision_blocksFreshApprovalTool() {
        val decision = resolvePlanApprovalAutoApproveDecision(
            window = PlanApprovalAutoApproveWindow(
                planId = "plan-1",
                planGoal = "修复工作流"
            ),
            request = ToolApprovalRequest(
                toolName = "ask_user",
                summary = "询问用户",
                detail = "继续吗",
                riskLevel = ApprovalRiskLevel.LOW,
                rawArgs = "{}"
            )
        )

        assertFalse(decision.autoApprove)
    }

    @Test
    fun resolvePlanApprovalAutoApproveDecision_blocksScopedRequest() {
        val decision = resolvePlanApprovalAutoApproveDecision(
            window = PlanApprovalAutoApproveWindow(
                planId = "plan-1",
                planGoal = "修复工作流"
            ),
            request = ToolApprovalRequest(
                toolName = "task_repo_write_file",
                summary = "修改远端文件",
                detail = "repo/path.kt",
                riskLevel = ApprovalRiskLevel.HIGH,
                rawArgs = "{}",
                approvalScopeTokens = setOf("mcp:github:write")
            )
        )

        assertFalse(decision.autoApprove)
    }

    @Test
    fun resolvePlanApprovalAutoApproveDecision_blocksCommandBoundRequest() {
        val decision = resolvePlanApprovalAutoApproveDecision(
            window = PlanApprovalAutoApproveWindow(
                planId = "plan-1",
                planGoal = "修复工作流"
            ),
            request = ToolApprovalRequest(
                toolName = "shell",
                summary = "执行 root shell 命令",
                detail = "rm -rf /data/local/tmp/demo",
                riskLevel = ApprovalRiskLevel.HIGH,
                rawArgs = """{"command":"rm -rf /data/local/tmp/demo"}""",
                commandBoundaryValue = "rm -rf /data/local/tmp/demo"
            )
        )

        assertFalse(decision.autoApprove)
    }
}
