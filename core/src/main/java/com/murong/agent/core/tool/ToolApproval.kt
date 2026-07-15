package com.murong.agent.core.tool

enum class ApprovalRiskLevel {
    LOW,
    MEDIUM,
    HIGH
}

data class ToolApprovalRequest(
    val toolName: String,
    val summary: String,
    val detail: String,
    val riskLevel: ApprovalRiskLevel,
    val rawArgs: String,
    val commandBoundaryValue: String? = null,
    val pathBoundaryValue: String? = null,
    val approvalScopeTokens: Set<String> = emptySet()
)
