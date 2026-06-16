package com.murong.agent.core.tool

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private data class SubagentPresetSpec(
    val name: String,
    val description: String,
    val defaultReasoningEffort: String? = null,
    val enableWebSearch: Boolean = false,
    val allowedTools: List<String> = emptyList()
)

private class SubagentPresetTool(
    private val delegate: SubagentTool,
    private val spec: SubagentPresetSpec
) : Tool {

    override val name: String = spec.name
    override val description: String = spec.description
    override val parameters: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "goal" to mapOf(
                "type" to "string",
                "description" to "这次子代理要完成的具体目标"
            ),
            "background" to mapOf(
                "type" to "boolean",
                "description" to "是否改为后台排队执行，默认 false",
                "default" to false
            ),
            "model" to mapOf(
                "type" to "string",
                "description" to "可选。指定子代理模型"
            ),
            "reasoningEffort" to mapOf(
                "type" to "string",
                "enum" to listOf("low", "medium", "high", "xhigh", "max"),
                "description" to "可选。覆盖这个预设默认的推理强度"
            )
        ),
        "required" to listOf("goal")
    )

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(args: String): String {
        val obj = json.parseToJsonElement(args).jsonObject
        val goal = obj["goal"]?.jsonPrimitive?.content?.trim().orEmpty()
        if (goal.isBlank()) return "Error: 'goal' parameter required"
        val model = obj["model"]?.jsonPrimitive?.content?.trim().orEmpty()
        val reasoningEffort = obj["reasoningEffort"]?.jsonPrimitive?.content?.trim().orEmpty()
        val background = obj["background"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false

        val delegatedArgs = buildJsonObject {
            put("goal", goal)
            put("background", background)
            if (model.isNotBlank()) {
                put("model", model)
            }
            val resolvedReasoning = reasoningEffort.ifBlank { spec.defaultReasoningEffort.orEmpty() }
            if (resolvedReasoning.isNotBlank()) {
                put("reasoningEffort", resolvedReasoning)
            }
            put("enableWebSearch", spec.enableWebSearch)
            if (spec.allowedTools.isNotEmpty()) {
                put("allowedTools", buildJsonArray {
                    spec.allowedTools.forEach { tool -> add(JsonPrimitive(tool)) }
                })
            }
        }.toString()

        return delegate.execute(delegatedArgs)
    }
}

fun createSubagentPresetTools(delegate: SubagentTool): List<Tool> {
    return listOf(
        SubagentPresetTool(
            delegate = delegate,
            spec = SubagentPresetSpec(
                name = "explore",
                description = "派发一个偏探索型的只读子代理，先快速摸清代码结构、关键文件、调用链和上下文，再给出发现与下一步建议。",
                defaultReasoningEffort = "medium",
                enableWebSearch = false,
                allowedTools = listOf("file(read,list,exists)")
            )
        ),
        SubagentPresetTool(
            delegate = delegate,
            spec = SubagentPresetSpec(
                name = "research",
                description = "派发一个偏研究型的子代理，优先检索文档、网页和项目上下文，整理可选方案、差异、利弊与建议。",
                defaultReasoningEffort = "high",
                enableWebSearch = true,
                allowedTools = listOf("file(read,list,exists)", "web_search", "web_fetch")
            )
        ),
        SubagentPresetTool(
            delegate = delegate,
            spec = SubagentPresetSpec(
                name = "review",
                description = "派发一个偏审查型的子代理，重点找 bug、风险、行为回归、缺失测试和实现缺口。",
                defaultReasoningEffort = "high",
                enableWebSearch = false,
                allowedTools = listOf("file(read,list,exists)")
            )
        ),
        SubagentPresetTool(
            delegate = delegate,
            spec = SubagentPresetSpec(
                name = "security_review",
                description = "派发一个偏安全审查的子代理，重点检查权限边界、输入校验、敏感信息、命令执行与潜在漏洞面。",
                defaultReasoningEffort = "high",
                enableWebSearch = true,
                allowedTools = listOf("file(read,list,exists)", "web_search", "web_fetch")
            )
        )
    )
}
