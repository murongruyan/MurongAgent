package com.murong.agent.core.tool

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 工具注册中心——管理所有可用工具。
 *
 * 工具定义在注册时转换成规范化 JSON，避免每次请求时受 Map 迭代顺序影响而改变
 * prompt 前缀；启用状态仍在构建请求时动态判断。
 */
class ToolRegistry(
    private val promptExposureFilter: (Tool) -> Boolean = { true }
) {

    private data class RegisteredTool(
        val tool: Tool,
        val definitionJson: String,
        val isEnabled: () -> Boolean,
        val isPromptExposed: () -> Boolean
    )

    private val tools = mutableMapOf<String, RegisteredTool>()

    fun register(
        tool: Tool,
        isEnabled: () -> Boolean = { true },
        isPromptExposed: () -> Boolean = isEnabled
    ) {
        tools[tool.name] = RegisteredTool(
            tool = tool,
            definitionJson = buildToolDefinitionJson(tool),
            isEnabled = isEnabled,
            isPromptExposed = isPromptExposed
        )
    }

    fun getTool(name: String): Tool? = tools[name]
        ?.takeIf { it.isEnabled() }
        ?.tool

    fun hasTool(name: String): Boolean = tools.containsKey(name)

    fun isPromptExposed(name: String): Boolean = tools[name]?.let(::isPromptVisible) == true

    fun getAllTools(): List<Tool> = tools.values.mapNotNull { entry ->
        entry.tool.takeIf { entry.isEnabled() }
    }

    fun getPromptVisibleTools(): List<Tool> = tools.values.mapNotNull { entry ->
        entry.tool.takeIf { isPromptVisible(entry) }
    }

    /**
     * 构建发送给模型的 tools 数组 JSON。
     *
     * 已缓存的工具定义按名称排序，保证在注册顺序或参数 Map 顺序不同的情况下，
     * 同一组可见工具产生字节一致的 JSON。
     */
    fun buildToolsJson(): String {
        return tools.entries
            .asSequence()
            .filter { (_, entry) -> isPromptVisible(entry) }
            .sortedBy { (name, _) -> name }
            .joinToString(prefix = "[", postfix = "]", separator = ",") { (_, entry) ->
                entry.definitionJson
            }
    }

    /**
     * 模型只能看到当前可执行且与本轮上下文相关的工具。
     *
     * [isPromptExposed] 是注册点的额外收紧条件，不能绕过 [isEnabled]；
     * [promptExposureFilter] 则用于按当前任务上下文裁剪高噪声工具。
     */
    private fun isPromptVisible(entry: RegisteredTool): Boolean {
        return entry.isEnabled() &&
            entry.isPromptExposed() &&
            promptExposureFilter(entry.tool)
    }

    private fun buildToolDefinitionJson(tool: Tool): String {
        return canonicalJson(
            mapOf(
                "type" to "function",
                "function" to mapOf(
                    "name" to tool.name,
                    "description" to tool.description,
                    "parameters" to normalizeToolSchema(tool.parameters)
                )
            )
        )
    }
}

private fun normalizeToolSchema(raw: Map<String, Any>): Map<String, Any> {
    fun normalize(value: Any?): Any? = when (value) {
        is Map<*, *> -> {
            val normalized = value.entries.associate { (key, child) ->
                key.toString() to normalize(child)
            }.toMutableMap()
            if (normalized["type"] == "object" && normalized["properties"] !is Map<*, *>) {
                normalized["properties"] = emptyMap<String, Any>()
            }
            val required = normalized["required"]
            if (required != null) {
                val names = (required as? Iterable<*>)
                    ?.mapNotNull { it as? String }
                    ?.distinct()
                    ?.sorted()
                if (names == null) normalized.remove("required") else normalized["required"] = names
            }
            normalized
        }
        is Iterable<*> -> value.map(::normalize)
        is Array<*> -> value.map(::normalize)
        else -> value
    }

    @Suppress("UNCHECKED_CAST")
    val normalized = normalize(raw) as? MutableMap<String, Any?> ?: mutableMapOf()
    if (normalized.isEmpty()) {
        normalized["type"] = "object"
        normalized["properties"] = emptyMap<String, Any>()
    } else if (normalized["type"] == "object" && normalized["properties"] !is Map<*, *>) {
        normalized["properties"] = emptyMap<String, Any>()
    }
    @Suppress("UNCHECKED_CAST")
    return normalized as Map<String, Any>
}

private fun canonicalJson(value: Any?): String {
    return when (value) {
        null -> "null"
        is String -> "\"${escapeJsonString(value)}\""
        is Char -> "\"${escapeJsonString(value.toString())}\""
        is Boolean, is Byte, is Short, is Int, is Long -> value.toString()
        is JsonObject -> canonicalJson(value.toMap())
        is JsonArray -> value.joinToString(prefix = "[", postfix = "]", separator = ",") { entry ->
            canonicalJson(entry)
        }
        JsonNull -> "null"
        is JsonPrimitive -> canonicalJsonPrimitive(value)
        is Float -> canonicalJsonNumber(value.toDouble())
        is Double -> canonicalJsonNumber(value)
        is Map<*, *> -> value.entries
            .map { (key, entryValue) -> key?.toString().orEmpty() to entryValue }
            .sortedBy { (key, _) -> key }
            .joinToString(prefix = "{", postfix = "}", separator = ",") { (key, entryValue) ->
                "\"${escapeJsonString(key)}\":${canonicalJson(entryValue)}"
            }
        is Iterable<*> -> value.joinToString(prefix = "[", postfix = "]", separator = ",") { entry ->
            canonicalJson(entry)
        }
        is Array<*> -> value.joinToString(prefix = "[", postfix = "]", separator = ",") { entry ->
            canonicalJson(entry)
        }
        else -> "\"${escapeJsonString(value.toString())}\""
    }
}

private fun canonicalJsonPrimitive(value: JsonPrimitive): String {
    if (value.isString) return "\"${escapeJsonString(value.content)}\""
    return value.content
}

private fun canonicalJsonNumber(value: Double): String {
    require(value.isFinite()) { "Non-finite numbers are not valid JSON values" }
    return value.toString()
}

private fun escapeJsonString(value: String): String {
    return buildString(value.length) {
        value.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> {
                    if (character.code < 0x20) {
                        append("\\u%04x".format(character.code))
                    } else {
                        append(character)
                    }
                }
            }
        }
    }
}
