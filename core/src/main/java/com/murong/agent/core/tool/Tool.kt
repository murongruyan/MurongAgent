package com.murong.agent.core.tool

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.Serializable

data class ToolFileChange(
    val path: String,
    val operation: String,
    val beforeContent: String? = null,
    val afterContent: String? = null,
    val diffPreview: String = "",
    val changedAt: Long = System.currentTimeMillis()
)

data class StepSignOffReceipt(
    val reportedStep: String,
    val resultSummary: String,
    val matchedEvidenceCount: Int,
    val totalEvidenceCount: Int,
    val matchedToolNames: List<String>,
    val matchedSessionHistorySessionIds: List<String> = emptyList(),
    val matchedSessionHistoryMessageReferences: List<String> = emptyList(),
    val signOffTimestamp: Long,
    val workflowStepIndex: Int? = null,
    val workflowStep: String? = null,
    val workflowTotalSteps: Int? = null
)

@Serializable
data class SessionHistoryToolPayload(
    val kind: String,
    val query: String? = null,
    val projectOnly: Boolean = false,
    val sessionIds: List<String> = emptyList(),
    val messageReferences: List<String> = emptyList(),
    val anchorMessageIds: List<Long> = emptyList(),
    val matchedFields: List<String> = emptyList(),
    val snippets: List<String> = emptyList(),
    val excerptWindows: List<String> = emptyList()
)

@Serializable
data class SkillToolPayload(
    val kind: String,
    val query: String? = null,
    val source: String? = null,
    val skillId: String? = null,
    val skillTitle: String? = null,
    val matchedSkillIds: List<String> = emptyList(),
    val matchedSkillTitles: List<String> = emptyList(),
    val runAs: String? = null,
    val task: String? = null,
    val allowedTools: List<String> = emptyList(),
    val background: Boolean = false,
    val delegatedToSubagent: Boolean = false
)

@Serializable
data class ToolStructuredPayload(
    val sessionHistory: SessionHistoryToolPayload? = null,
    val skill: SkillToolPayload? = null
)

enum class ToolExecutionStatus {
    UNKNOWN,
    SUCCESS,
    FAILURE,
    TIMED_OUT
}

data class ToolExecutionResult(
    val output: String,
    val fileChanges: List<ToolFileChange> = emptyList(),
    val stepSignOffReceipt: StepSignOffReceipt? = null,
    val structuredPayload: ToolStructuredPayload? = null,
    /**
     * Structured execution state. UNKNOWN keeps existing tools source-compatible while
     * callers migrate away from interpreting human-readable output.
     */
    val status: ToolExecutionStatus = ToolExecutionStatus.UNKNOWN,
    /** Explicit success supplied by the tool. Null means the legacy text fallback is required. */
    val success: Boolean? = null,
    /** Process exit code when the tool launches a command. */
    val exitCode: Int? = null,
    /** True when execution was stopped by its timeout. */
    val timedOut: Boolean = false
) {
    /**
     * Resolve only structured state. Null deliberately means "unknown" so legacy tools can
     * continue using the AgentLoop text fallback until they expose structured status.
     * Contradictory failure signals always win over a claimed success.
     */
    val resolvedSuccess: Boolean?
        get() {
            if (success == false || timedOut || exitCode?.let { it != 0 } == true) return false
            if (status == ToolExecutionStatus.FAILURE || status == ToolExecutionStatus.TIMED_OUT) {
                return false
            }
            if (success == true || status == ToolExecutionStatus.SUCCESS || exitCode == 0) return true
            return null
        }
}

data class ToolExecutionReceipt(
    val toolName: String,
    val args: String,
    val result: String? = null,
    val isSuccess: Boolean = true,
    val structuredPayload: ToolStructuredPayload? = null,
    val timestamp: Long = System.currentTimeMillis()
)

data class ToolRuntimeContext(
    val currentTurnToolReceipts: List<ToolExecutionReceipt> = emptyList()
)

/**
 * 工具接口——Agent 可调用的功能
 */
interface Tool {
    /** 工具名称（给模型看的函数名） */
    val name: String

    /** 工具描述（给模型看的） */
    val description: String

    /** JSON Schema 参数定义 */
    val parameters: Map<String, Any>

    /**
     * 执行工具
     * @param args JSON 格式的参数
     * @return 执行结果文本
     */
    suspend fun execute(args: String): String

    /**
     * 执行工具并返回结构化结果。
     * 默认只回传文本结果；文件类工具可额外附带变更明细。
     */
    suspend fun executeWithResult(args: String): ToolExecutionResult {
        return ToolExecutionResult(output = execute(args))
    }

    /**
     * 带运行时上下文执行工具。
     * 默认忽略上下文，兼容现有工具；少数需要查看本轮已执行工具收据的工具可重写。
     */
    suspend fun executeWithContext(
        args: String,
        runtimeContext: ToolRuntimeContext
    ): ToolExecutionResult {
        return executeWithResult(args)
    }

    /**
     * 如果返回非空，则在执行前要求用户审批。
     */
    fun buildApprovalRequest(args: String): ToolApprovalRequest? = null
}

internal fun buildDiffPreview(
    beforeContent: String?,
    afterContent: String?,
    maxLines: Int = 80
): String {
    val beforeLines = beforeContent?.lines().orEmpty()
    val afterLines = afterContent?.lines().orEmpty()
    val preview = mutableListOf<String>()
    var beforeIndex = 0
    var afterIndex = 0

    while (beforeIndex < beforeLines.size || afterIndex < afterLines.size) {
        if (preview.size >= maxLines) {
            preview += "...(diff truncated)"
            break
        }
        val beforeLine = beforeLines.getOrNull(beforeIndex)
        val afterLine = afterLines.getOrNull(afterIndex)
        when {
            beforeLine == afterLine -> {
                preview += "  ${beforeLine.orEmpty()}"
                beforeIndex++
                afterIndex++
            }
            afterLine != null && !beforeLines.drop(beforeIndex).contains(afterLine) -> {
                preview += "+ $afterLine"
                afterIndex++
            }
            beforeLine != null && !afterLines.drop(afterIndex).contains(beforeLine) -> {
                preview += "- $beforeLine"
                beforeIndex++
            }
            else -> {
                if (beforeLine != null) {
                    preview += "- $beforeLine"
                    beforeIndex++
                }
                if (afterLine != null) {
                    preview += "+ $afterLine"
                    afterIndex++
                }
            }
        }
    }

    return preview.joinToString("\n").ifBlank { "(no textual diff)" }
}
