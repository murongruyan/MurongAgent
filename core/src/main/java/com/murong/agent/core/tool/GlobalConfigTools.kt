package com.murong.agent.core.tool

import com.murong.agent.core.config.GlobalMemory
import com.murong.agent.core.config.GlobalRule
import com.murong.agent.core.config.GlobalSkill
import com.murong.agent.core.config.ProviderConfig
import com.murong.agent.core.config.SkillRunAs
import com.murong.agent.core.mcp.McpServerConfig
import com.murong.agent.core.mcp.McpTransportType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

internal class CreateGlobalRuleTool(
    private val configProvider: suspend () -> ProviderConfig,
    private val saveConfig: suspend (ProviderConfig) -> Unit
) : Tool {
    override val name: String = "create_global_rule"
    override val description: String =
        "手动创建一条全局规则。仅当用户明确要求“保存为全局规则/添加到全局规则”时才调用，不要自动识别普通对话。"
    override val parameters: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "title" to mapOf("type" to "string", "description" to "规则标题"),
            "content" to mapOf("type" to "string", "description" to "规则正文，支持普通文本或 Markdown"),
            "enabled" to mapOf("type" to "boolean", "description" to "是否立即启用，默认 true")
        ),
        "required" to listOf("title", "content")
    )

    override fun buildApprovalRequest(args: String): ToolApprovalRequest? {
        val title = parseObject(args)["title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (title.isBlank()) return null
        return ToolApprovalRequest(
            toolName = name,
            summary = "创建全局规则",
            detail = title,
            riskLevel = ApprovalRiskLevel.MEDIUM,
            rawArgs = args
        )
    }

    override suspend fun execute(args: String): String {
        return runCatching {
            val obj = parseObject(args)
            val title = obj["title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val content = obj["content"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val enabled = obj["enabled"]?.jsonPrimitive?.booleanOrNull ?: true
            require(title.isNotBlank()) { "'title' parameter required" }
            require(content.isNotBlank()) { "'content' parameter required" }
            val config = configProvider()
            val updated = config.globalRules + GlobalRule(
                id = UUID.randomUUID().toString().take(8),
                title = title,
                content = content,
                enabled = enabled
            )
            saveConfig(config.copy(globalRules = updated))
            "已创建全局规则：$title（${if (enabled) "已启用" else "已停用"}），当前共 ${updated.size} 条。"
        }.getOrElse { error ->
            "Error: 创建全局规则失败: ${error.message}"
        }
    }
}

internal class CreateGlobalMemoryTool(
    private val configProvider: suspend () -> ProviderConfig,
    private val saveConfig: suspend (ProviderConfig) -> Unit
) : Tool {
    override val name: String = "create_global_memory"
    override val description: String =
        "手动创建一条全局记忆。仅当用户明确要求“保存为全局记忆/添加到全局记忆”时才调用，不要自动识别普通对话。"
    override val parameters: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "title" to mapOf("type" to "string", "description" to "记忆标题"),
            "content" to mapOf("type" to "string", "description" to "记忆正文，支持普通文本或 Markdown"),
            "enabled" to mapOf("type" to "boolean", "description" to "是否立即启用，默认 true")
        ),
        "required" to listOf("title", "content")
    )

    override fun buildApprovalRequest(args: String): ToolApprovalRequest? {
        val title = parseObject(args)["title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (title.isBlank()) return null
        return ToolApprovalRequest(
            toolName = name,
            summary = "创建全局记忆",
            detail = title,
            riskLevel = ApprovalRiskLevel.MEDIUM,
            rawArgs = args
        )
    }

    override suspend fun execute(args: String): String {
        return runCatching {
            val obj = parseObject(args)
            val title = obj["title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val content = obj["content"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val enabled = obj["enabled"]?.jsonPrimitive?.booleanOrNull ?: true
            require(title.isNotBlank()) { "'title' parameter required" }
            require(content.isNotBlank()) { "'content' parameter required" }
            val config = configProvider()
            val updated = config.globalMemories + GlobalMemory(
                id = UUID.randomUUID().toString().take(8),
                title = title,
                content = content,
                enabled = enabled
            )
            saveConfig(config.copy(globalMemories = updated))
            "已创建全局记忆：$title（${if (enabled) "已启用" else "已停用"}），当前共 ${updated.size} 条。"
        }.getOrElse { error ->
            "Error: 创建全局记忆失败: ${error.message}"
        }
    }
}

internal class CreateGlobalSkillTool(
    private val configProvider: suspend () -> ProviderConfig,
    private val saveConfig: suspend (ProviderConfig) -> Unit
) : Tool {
    override val name: String = "create_global_skill"
    override val description: String =
        "手动导入一条全局 Skill。仅当用户明确要求“导入 Skill/保存为全局 Skill”时才调用，不要自动识别普通对话。"
    override val parameters: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "title" to mapOf("type" to "string", "description" to "Skill 标题"),
            "description" to mapOf("type" to "string", "description" to "Skill 描述"),
            "content" to mapOf("type" to "string", "description" to "Skill 内容，支持 Markdown"),
            "runAs" to mapOf("type" to "string", "enum" to listOf("inline", "subagent"), "description" to "执行方式"),
            "allowedTools" to mapOf("type" to "array", "items" to mapOf("type" to "string"), "description" to "允许工具列表"),
            "preferredModel" to mapOf("type" to "string", "description" to "首选模型，可选"),
            "enabled" to mapOf("type" to "boolean", "description" to "是否立即启用，默认 true")
        ),
        "required" to listOf("title", "content")
    )

    override fun buildApprovalRequest(args: String): ToolApprovalRequest? {
        val title = parseObject(args)["title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (title.isBlank()) return null
        return ToolApprovalRequest(
            toolName = name,
            summary = "导入全局 Skill",
            detail = title,
            riskLevel = ApprovalRiskLevel.MEDIUM,
            rawArgs = args
        )
    }

    override suspend fun execute(args: String): String {
        return runCatching {
            val obj = parseObject(args)
            val title = obj["title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val content = obj["content"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            require(title.isNotBlank()) { "'title' parameter required" }
            require(content.isNotBlank()) { "'content' parameter required" }
            val description = obj["description"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val runAs = when (obj["runAs"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()) {
                "subagent" -> SkillRunAs.SUBAGENT
                else -> SkillRunAs.INLINE
            }
            val allowedTools = normalizeAllowedTools(obj.stringList("allowedTools"))
            val preferredModel = obj["preferredModel"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val enabled = obj["enabled"]?.jsonPrimitive?.booleanOrNull ?: true
            val config = configProvider()
            val updated = config.globalSkills + GlobalSkill(
                id = UUID.randomUUID().toString().take(8),
                title = title,
                description = description,
                content = content,
                runAs = runAs,
                allowedTools = allowedTools,
                preferredModel = preferredModel,
                enabled = enabled
            )
            saveConfig(config.copy(globalSkills = updated))
            "已导入全局 Skill：$title（${if (enabled) "已启用" else "已停用"}），当前共 ${updated.size} 条。"
        }.getOrElse { error ->
            "Error: 导入全局 Skill 失败: ${error.message}"
        }
    }
}

internal class CreateMcpServerTool(
    private val configsProvider: () -> List<McpServerConfig>,
    private val saveConfigs: (List<McpServerConfig>) -> Unit,
    private val connectAll: (suspend (List<McpServerConfig>) -> Unit)? = null
) : Tool {
    override val name: String = "create_mcp_server"
    override val description: String =
        "手动导入一条 MCP 服务器配置。仅当用户明确要求“导入 MCP/添加 MCP 服务器”时才调用，不要自动识别普通对话。"
    override val parameters: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "name" to mapOf("type" to "string", "description" to "服务器名称"),
            "transport" to mapOf("type" to "string", "enum" to listOf("stdio", "sse", "streamable-http"), "description" to "传输类型"),
            "command" to mapOf("type" to "string", "description" to "stdio 模式下的命令"),
            "args" to mapOf("type" to "array", "items" to mapOf("type" to "string"), "description" to "stdio 模式下的参数"),
            "cwd" to mapOf("type" to "string", "description" to "工作目录，可选"),
            "env" to mapOf("type" to "object", "additionalProperties" to mapOf("type" to "string"), "description" to "环境变量映射"),
            "url" to mapOf("type" to "string", "description" to "远端模式的 URL"),
            "headers" to mapOf("type" to "object", "additionalProperties" to mapOf("type" to "string"), "description" to "请求头映射"),
            "requestTimeoutMs" to mapOf("type" to "number", "description" to "超时毫秒数，可选"),
            "enabled" to mapOf("type" to "boolean", "description" to "是否启用，默认 true"),
            "connectAfterImport" to mapOf("type" to "boolean", "description" to "导入后是否立即尝试连接，默认 false")
        ),
        "required" to listOf("name", "transport")
    )

    override fun buildApprovalRequest(args: String): ToolApprovalRequest? {
        val obj = parseObject(args)
        val name = obj["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (name.isBlank()) return null
        return ToolApprovalRequest(
            toolName = this.name,
            summary = "导入 MCP 服务器",
            detail = name,
            riskLevel = ApprovalRiskLevel.HIGH,
            rawArgs = args
        )
    }

    override suspend fun execute(args: String): String {
        return runCatching {
            val obj = parseObject(args)
            val name = obj["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            require(name.isNotBlank()) { "'name' parameter required" }
            val transport = when (obj["transport"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()) {
                "stdio", null, "" -> McpTransportType.STDIO
                "sse" -> McpTransportType.SSE
                "streamable-http", "streamable_http", "http" -> McpTransportType.STREAMABLE_HTTP
                else -> error("unknown transport")
            }
            val command = obj["command"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val argsList = obj.stringList("args")
            val cwd = obj["cwd"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val env = obj.stringMap("env")
            val url = obj["url"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val headers = obj.stringMap("headers")
            val requestTimeoutMs = obj["requestTimeoutMs"]?.jsonPrimitive?.contentOrNull?.trim()?.toLongOrNull()
            val enabled = obj["enabled"]?.jsonPrimitive?.booleanOrNull ?: true
            val connectAfterImport = obj["connectAfterImport"]?.jsonPrimitive?.booleanOrNull ?: false
            when (transport) {
                McpTransportType.STDIO -> require(command.isNotBlank()) { "'command' parameter required for stdio transport" }
                McpTransportType.SSE,
                McpTransportType.STREAMABLE_HTTP -> require(url.isNotBlank()) { "'url' parameter required for remote transport" }
            }
            val imported = McpServerConfig(
                name = name,
                transport = transport,
                command = command,
                args = argsList,
                cwd = cwd,
                env = env,
                url = url,
                headers = headers,
                requestTimeoutMs = requestTimeoutMs,
                enabled = enabled
            )
            val updated = configsProvider()
                .filterNot { it.name.equals(name, ignoreCase = true) } + imported
            saveConfigs(updated)
            if (connectAfterImport && enabled) {
                connectAll?.invoke(updated)
            }
            buildString {
                append("已导入 MCP 服务器：")
                append(name)
                append("（")
                append(transport.name.lowercase())
                append("，")
                append(if (enabled) "已启用" else "已停用")
                append("）")
                if (connectAfterImport && enabled && connectAll != null) {
                    append("，已尝试连接。")
                } else {
                    append("。")
                }
                append(" 当前共 ")
                append(updated.size)
                append(" 条配置。")
            }
        }.getOrElse { error ->
            "Error: 导入 MCP 服务器失败: ${error.message}"
        }
    }
}

private val toolJson = Json { ignoreUnknownKeys = true }

private fun parseObject(args: String): JsonObject {
    return toolJson.parseToJsonElement(args).jsonObject
}

private fun JsonObject.stringList(key: String): List<String> {
    val element = get(key) ?: return emptyList()
    return runCatching {
        element.jsonArray.mapNotNull { item ->
            item.jsonPrimitive.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
        }
    }.getOrDefault(emptyList())
}

private fun JsonObject.stringMap(key: String): Map<String, String> {
    return runCatching {
        get(key)?.jsonObject?.mapNotNull { (mapKey, value) ->
            value.jsonPrimitive.contentOrNull?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { mapKey to it }
        }?.toMap()
    }.getOrNull().orEmpty()
}

private fun normalizeAllowedTools(rawTokens: List<String>): List<String> {
    if (rawTokens.isEmpty()) return emptyList()
    return rawTokens
        .flatMap { token ->
            token.split(',', ';', '\n')
        }
        .mapNotNull { raw ->
            val token = raw.trim().removePrefix("-").removePrefix("*").trim().lowercase()
            when {
                token.isBlank() -> null
                token in setOf("edit", "patch", "code-edit", "codeedit") -> "code_edit"
                token in setOf("bash", "sh", "zsh", "pwsh", "powershell", "terminal", "command", "cmd") -> "shell"
                token in setOf("web", "browser", "browse", "search", "fetch", "web-search", "websearch") -> "web_search"
                token in setOf("web-fetch", "webfetch") -> "web_fetch"
                else -> token
            }
        }
        .distinct()
}
