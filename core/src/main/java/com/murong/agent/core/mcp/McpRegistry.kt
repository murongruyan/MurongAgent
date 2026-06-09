package com.murong.agent.core.mcp

import android.content.Context
import com.murong.agent.core.tool.ApprovalRiskLevel
import com.murong.agent.core.tool.Tool
import com.murong.agent.core.tool.ToolApprovalRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.util.Locale

/**
 * MCP 注册中心——管理所有 MCP 服务器连接和工具
 */
class McpRegistry(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val transports = mutableMapOf<String, McpTransport>()
    private val toolCache = mutableMapOf<String, McpToolDef>()
    private val serverErrors = mutableMapOf<String, String>()

    private val configFile: File
        get() = File(context.filesDir, "mcp_servers.json")

    /**
     * 获取所有 MCP 工具（适配为 Tool 接口）
     */
    fun getMcpTools(): List<Tool> {
        return toolCache.values.map { McpToolAdapter(this, it) }
    }

    /**
     * 获取所有服务器状态
     */
    fun getServerStatuses(): List<McpServerStatus> {
        val serverNames = (transports.keys + serverErrors.keys).distinct().sorted()
        return serverNames.map { name ->
            val transport = transports[name]
            val serverTools = toolCache.values
                .filter { it.serverName == name }
                .sortedBy { it.name }
            McpServerStatus(
                name = name,
                connected = transport?.isActive() == true,
                toolCount = serverTools.size,
                error = serverErrors[name],
                toolNames = serverTools.map { it.name }
            )
        }
    }

    /**
     * 连接所有已启用的 MCP 服务器
     */
    suspend fun connectAll(configs: List<McpServerConfig>) {
        withContext(Dispatchers.IO) {
            for (cfg in configs) {
                if (!cfg.enabled || transports.containsKey(cfg.name)) continue
                try {
                    val transport = McpTransport(cfg)
                    val result = transport.connect()
                    if (result.isSuccess) {
                        transports[cfg.name] = transport
                        serverErrors.remove(cfg.name)
                        val toolsResult = transport.listTools()
                        if (toolsResult.isSuccess) {
                            toolCache.entries.removeAll { it.value.serverName == cfg.name }
                            toolsResult.getOrThrow().forEach { toolDef ->
                                toolCache[toolDef.name] = toolDef
                            }
                        } else {
                            serverErrors[cfg.name] = toolsResult.exceptionOrNull()?.message
                                ?: "列出工具失败"
                        }
                    } else {
                        serverErrors[cfg.name] = result.exceptionOrNull()?.message
                            ?: "连接失败"
                    }
                } catch (e: Exception) {
                    serverErrors[cfg.name] = e.message ?: "连接失败"
                }
            }
        }
    }

    /**
     * 断开指定服务器
     */
    fun disconnect(serverName: String) {
        transports.remove(serverName)?.disconnect()
        toolCache.entries.removeAll { it.value.serverName == serverName }
        serverErrors.remove(serverName)
    }

    /**
     * 断开所有
     */
    fun disconnectAll() {
        transports.values.forEach { it.disconnect() }
        transports.clear()
        toolCache.clear()
        serverErrors.clear()
    }

    /**
     * 保存 MCP 服务器配置到文件
     */
    fun saveConfigs(configs: List<McpServerConfig>) {
        configFile.parentFile?.mkdirs()
        configFile.writeText(json.encodeToString(ListSerializer(McpServerConfig.serializer()), configs))
    }

    /**
     * 加载 MCP 服务器配置
     */
    fun loadConfigs(): List<McpServerConfig> {
        if (!configFile.exists()) return emptyList()
        return try {
            json.decodeFromString(ListSerializer(McpServerConfig.serializer()), configFile.readText())
        } catch (_: Exception) { emptyList() }
    }

    /**
     * 调用 MCP 工具
     */
    suspend fun callTool(toolName: String, args: String): String {
        val toolDef = toolCache[toolName] ?: return "Error: MCP tool '$toolName' not found"
        val transport = transports[toolDef.serverName]
            ?: return "Error: MCP server '${toolDef.serverName}' not connected"

        val argsMap = try {
            json.parseToJsonElement(args).jsonObject
        } catch (e: Exception) {
            return "Error: Invalid JSON args: ${e.message}"
        }

        val argsForCall = buildJsonObject {
            argsMap.forEach { (k, v) -> put(k, v) }
        }

        val result = transport.callTool(toolName, argsForCall)
        return result.fold(
            onSuccess = { element ->
                try {
                    json.encodeToString(JsonElement.serializer(), element)
                } catch (_: Exception) {
                    element.toString()
                }
            },
            onFailure = { "Error calling MCP tool '$toolName': ${it.message}" }
        )
    }
}

/**
 * MCP 工具适配器——将 MCP 工具转为 Murong Tool 接口
 */
class McpToolAdapter(
    private val registry: McpRegistry,
    private val toolDef: McpToolDef
) : Tool {

    override val name: String get() = "mcp_${toolDef.name}"
    override val description: String
        get() = "[MCP/${toolDef.serverName}] ${toolDef.description}"
    override val parameters: Map<String, Any> get() = toolDef.inputSchema

    override fun buildApprovalRequest(args: String): ToolApprovalRequest? {
        val approvalScopeTokens = buildMcpApprovalScopeTokens(toolDef)
        if (approvalScopeTokens.isEmpty()) return null
        return ToolApprovalRequest(
            toolName = name,
            summary = "执行 GitHub 远端写操作",
            detail = buildGitHubApprovalDetail(toolDef, args),
            riskLevel = ApprovalRiskLevel.HIGH,
            rawArgs = args,
            approvalScopeTokens = approvalScopeTokens
        )
    }

    override suspend fun execute(args: String): String {
        return registry.callTool(toolDef.name, args)
    }
}

private fun buildMcpApprovalScopeTokens(toolDef: McpToolDef): Set<String> {
    if (!toolDef.isGitHubTool()) return emptySet()
    return if (toolDef.isGitHubWriteTool()) setOf("mcp:github:write") else emptySet()
}

private fun buildGitHubApprovalDetail(toolDef: McpToolDef, args: String): String {
    val target = summarizeGitHubApprovalTarget(args)
    return buildString {
        append("MCP/GitHub 工具: ")
        append(toolDef.name)
        target?.let {
            append("\n目标: ")
            append(it)
        }
        append("\n参数: ")
        append(args)
    }
}

private fun summarizeGitHubApprovalTarget(args: String): String? {
    val obj = runCatching { Json.parseToJsonElement(args).jsonObject }.getOrNull() ?: return null
    val parts = mutableListOf<String>()
    obj["owner"]?.jsonPrimitive?.contentOrNull
        ?.takeIf { it.isNotBlank() }
        ?.let { owner ->
            val repo = obj["repo"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotBlank() }
            parts += if (repo != null) "$owner/$repo" else owner
        }
    obj["path"]?.jsonPrimitive?.contentOrNull
        ?.takeIf { it.isNotBlank() }
        ?.let { parts += "path=$it" }
    obj["branch"]?.jsonPrimitive?.contentOrNull
        ?.takeIf { it.isNotBlank() }
        ?.let { parts += "branch=$it" }
    obj["pull_number"]?.jsonPrimitive?.contentOrNull
        ?.takeIf { it.isNotBlank() }
        ?.let { parts += "PR #$it" }
    obj["issue_number"]?.jsonPrimitive?.contentOrNull
        ?.takeIf { it.isNotBlank() }
        ?.let { parts += "Issue #$it" }
    obj["name"]?.jsonPrimitive?.contentOrNull
        ?.takeIf { it.isNotBlank() }
        ?.let { parts += "name=$it" }
    return parts.distinct().takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

private fun McpToolDef.isGitHubTool(): Boolean {
    return serverName.contains("github", ignoreCase = true) ||
        name.contains("github", ignoreCase = true)
}

private fun McpToolDef.isGitHubWriteTool(): Boolean {
    val normalized = name.lowercase(Locale.ROOT)
    val writePrefixes = listOf(
        "create_",
        "update_",
        "delete_",
        "merge_",
        "push_",
        "fork_",
        "add_"
    )
    if (writePrefixes.any { normalized.startsWith(it) }) return true
    return normalized in setOf(
        "create_branch",
        "create_repository",
        "create_issue",
        "create_pull_request",
        "create_pull_request_review",
        "create_or_update_file",
        "push_files",
        "merge_pull_request",
        "update_pull_request_branch",
        "update_issue",
        "add_issue_comment",
        "fork_repository"
    )
}
