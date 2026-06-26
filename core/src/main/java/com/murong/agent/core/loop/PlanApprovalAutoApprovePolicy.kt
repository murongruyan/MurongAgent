package com.murong.agent.core.loop

import com.murong.agent.core.config.isFreshApprovalToolName
import com.murong.agent.core.tool.ToolApprovalRequest

internal data class PlanApprovalAutoApproveWindow(
    val planId: String,
    val planGoal: String
)

internal data class PlanApprovalAutoApproveDecision(
    val autoApprove: Boolean,
    val label: String? = null,
    val detail: String? = null
)

internal fun resolvePlanApprovalAutoApproveDecision(
    window: PlanApprovalAutoApproveWindow?,
    request: ToolApprovalRequest
): PlanApprovalAutoApproveDecision {
    if (window == null) return PlanApprovalAutoApproveDecision(autoApprove = false)
    if (!request.commandBoundaryValue.isNullOrBlank()) {
        return PlanApprovalAutoApproveDecision(autoApprove = false)
    }
    if (request.approvalScopeTokens.isNotEmpty()) {
        return PlanApprovalAutoApproveDecision(autoApprove = false)
    }
    if (isFreshApprovalToolName(request.toolName)) {
        return PlanApprovalAutoApproveDecision(autoApprove = false)
    }
    return PlanApprovalAutoApproveDecision(
        autoApprove = true,
        label = "计划审批窗口放行",
        detail = "用户刚批准按计划执行 `${window.planGoal}`，当前计划执行窗口内的低层工具操作可直接继续。"
    )
}
