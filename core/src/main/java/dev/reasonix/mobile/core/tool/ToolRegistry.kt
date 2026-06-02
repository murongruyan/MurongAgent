package dev.reasonix.mobile.core.tool

/**
 * 工具注册中心——管理所有可用工具
 */
class ToolRegistry {

    private val tools = mutableMapOf<String, Tool>()

    fun register(tool: Tool) {
        tools[tool.name] = tool
    }

    fun getTool(name: String): Tool? = tools[name]

    fun getAllTools(): List<Tool> = tools.values.toList()

    /**
     * 构建 tools 数组 JSON（发送给模型用）
     */
    fun buildToolsJson(): String {
        val sb = StringBuilder()
        sb.append("[")
        tools.values.forEachIndexed { index, tool ->
            if (index > 0) sb.append(",")
            sb.append("""
                {
                    "type": "function",
                    "function": {
                        "name": "${tool.name}",
                        "description": "${escapeJson(tool.description)}",
                        "parameters": ${jsonSchemaToJson(tool.parameters)}
                    }
                }
            """.trimIndent())
        }
        sb.append("]")
        return sb.toString()
    }

    private fun escapeJson(s: String): String {
        return s
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    @Suppress("UNCHECKED_CAST")
    private fun jsonSchemaToJson(params: Map<String, Any>): String {
        val sb = StringBuilder()
        sb.append("{")
        params.entries.forEachIndexed { index, (key, value) ->
            if (index > 0) sb.append(",")
            sb.append("\"$key\":")
            when (value) {
                is String -> sb.append("\"${escapeJson(value)}\"")
                is Number -> sb.append(value)
                is Boolean -> sb.append(value)
                is List<*> -> {
                    sb.append("[")
                    value.forEachIndexed { i, v ->
                        if (i > 0) sb.append(",")
                        if (v is String) sb.append("\"${escapeJson(v)}\"")
                        else sb.append(v)
                    }
                    sb.append("]")
                }
                is Map<*, *> -> sb.append(jsonSchemaToJson(value as Map<String, Any>))
                else -> sb.append("\"$value\"")
            }
        }
        sb.append("}")
        return sb.toString()
    }
}
