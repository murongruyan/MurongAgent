package com.murong.agent.core.mcp

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * MCP (Model Context Protocol) 数据类型
 *
 * 协议规范: https://spec.modelcontextprotocol.io/
 */

/** JSON-RPC 消息 */
@Serializable
data class JsonRpcRequest(
    val jsonrpc: String = "2.0",
    val id: String,
    val method: String,
    val params: JsonElement? = null
)

@Serializable
data class JsonRpcNotification(
    val jsonrpc: String = "2.0",
    val method: String,
    val params: JsonElement? = null
)

@Serializable
data class JsonRpcResponse(
    val jsonrpc: String = "2.0",
    val id: String? = null,
    val result: kotlinx.serialization.json.JsonElement? = null,
    val error: JsonRpcError? = null
)

@Serializable
data class JsonRpcError(
    val code: Int,
    val message: String
)

/** MCP 服务器配置 */
@Serializable
data class McpServerConfig(
    val name: String,
    val transport: McpTransportType = McpTransportType.STDIO,
    /** stdio: command to execute */
    val command: String = "",
    /** stdio: arguments */
    val args: List<String> = emptyList(),
    /** stdio: working directory */
    val cwd: String = "",
    /** stdio: environment overrides */
    val env: Map<String, String> = emptyMap(),
    /** remote: URL endpoint */
    val url: String = "",
    /** remote: request headers */
    val headers: Map<String, String> = emptyMap(),
    /** Header name -> encrypted local secret reference. Values never enter exported diagnostics. */
    val authHeaderSecretReferences: Map<String, String> = emptyMap(),
    /** per-request timeout in ms */
    val requestTimeoutMs: Long? = null,
    /** config source type */
    val source: McpConfigSource = McpConfigSource.MANUAL,
    /** optional original source path, e.g. .mcp.json */
    val sourcePath: String = "",
    /** trusted read-only tool names exported by this server */
    val trustedReadOnlyTools: List<String> = emptyList(),
    /** whether the server should auto-start with the app */
    val autoStart: Boolean = true,
    /** 启用状态 */
    val enabled: Boolean = true
)

@Serializable
enum class McpConfigSource {
    MANUAL, IMPORTED_DRAFT, MCP_JSON
}

@Serializable
enum class McpTransportType {
    STDIO, SSE, STREAMABLE_HTTP
}

/** MCP 工具定义 */
data class McpToolDef(
    val name: String,
    val description: String,
    val inputSchema: Map<String, Any>,
    /** 所属服务器 */
    val serverName: String
)

/**
 * The agent-visible tool id includes its owner, while the raw MCP name remains available for the
 * JSON-RPC request. This prevents two servers exporting `search` from overwriting each other.
 */
fun McpToolDef.canonicalToolName(): String = canonicalMcpToolName(serverName, name)

fun canonicalMcpToolName(serverName: String, rawToolName: String): String {
    val supplied = rawToolName.trim().lowercase()
    if (supplied.startsWith("mcp__") && supplied.count { it == '_' } >= 4) return supplied
    val rawName = supplied.removePrefix("mcp_")
    return "mcp__${canonicalMcpIdentifierPart(serverName)}__${canonicalMcpIdentifierPart(rawName)}"
}

fun canonicalMcpIdentifierPart(value: String): String {
    return value.trim().lowercase()
        .replace(Regex("[^a-z0-9]+"), "_")
        .trim('_')
        .ifBlank { "unnamed" }
}

/** MCP 服务器状态 */
data class McpServerStatus(
    val name: String,
    val connected: Boolean,
    val toolCount: Int,
    val error: String? = null,
    val toolNames: List<String> = emptyList(),
    val failureRecord: McpFailureRecord? = null,
    val lastConnectedAt: Long? = null,
    val toolCacheUpdatedAt: Long? = null,
    val configurationGeneration: Long = 0L
)

@Serializable
data class McpFailureRecord(
    val stage: McpFailureStage,
    val message: String,
    val transport: McpTransportType? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val retryable: Boolean = true
)

@Serializable
enum class McpFailureStage {
    CONFIG_LOAD, CONNECT, LIST_TOOLS, CALL_TOOL
}
