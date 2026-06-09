package com.murong.agent.core.tool

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

data class ToolFileChange(
    val path: String,
    val operation: String,
    val beforeContent: String? = null,
    val afterContent: String? = null,
    val diffPreview: String = "",
    val changedAt: Long = System.currentTimeMillis()
)

data class ToolExecutionResult(
    val output: String,
    val fileChanges: List<ToolFileChange> = emptyList()
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
