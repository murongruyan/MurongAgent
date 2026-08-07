package com.murong.agent.core.tool

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class CompleteGoalTool(
    private val currentGoalProvider: () -> String?,
    private val completionBlockReasonProvider: () -> String?,
    private val onGoalCompleted: (result: String) -> Unit
) : Tool {
    override val name: String = NAME

    override val description: String =
        "在当前长期目标已经真实达成后立即结束目标模式。若存在执行计划，必须先完成并签收全部步骤。成功调用后本轮立即收口，不要继续操作。"

    override val parameters: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "result" to mapOf(
                "type" to "string",
                "description" to "目标达成结果的简短总结，说明完成了什么以及验证结果"
            )
        ),
        "required" to listOf("result"),
        "additionalProperties" to false
    )

    override val terminatesTurnOnSuccess: Boolean = true

    override suspend fun execute(args: String): String = executeWithResult(args).output

    override suspend fun executeWithResult(args: String): ToolExecutionResult {
        val goal = currentGoalProvider()?.trim().orEmpty()
        if (goal.isBlank()) {
            return failure("当前没有正在进行的目标。")
        }
        completionBlockReasonProvider()?.trim()?.takeIf { it.isNotBlank() }?.let { reason ->
            return failure(reason)
        }
        val result = runCatching {
            json.parseToJsonElement(args).jsonObject["result"]
                ?.jsonPrimitive
                ?.contentOrNull
                ?.trim()
                .orEmpty()
        }.getOrElse { error ->
            return failure("complete_goal 参数无效：${error.message}")
        }
        if (result.isBlank()) {
            return failure("result 不能为空。")
        }
        if (result.length > 2_000) {
            return failure("result 不能超过 2000 字符。")
        }
        onGoalCompleted(result)
        return ToolExecutionResult(
            output = "Goal completed.\nresult=$result",
            status = ToolExecutionStatus.SUCCESS,
            success = true
        )
    }

    private fun failure(message: String): ToolExecutionResult = ToolExecutionResult(
        output = "Error: $message",
        status = ToolExecutionStatus.FAILURE,
        success = false
    )

    companion object {
        const val NAME: String = "complete_goal"
        private val json = Json { ignoreUnknownKeys = false }
    }
}
