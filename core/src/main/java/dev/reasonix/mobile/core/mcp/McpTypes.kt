package dev.reasonix.mobile.core.mcp

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
    /** per-request timeout in ms */
    val requestTimeoutMs: Long? = null,
    /** 启用状态 */
    val enabled: Boolean = true
)

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

/** MCP 服务器状态 */
data class McpServerStatus(
    val name: String,
    val connected: Boolean,
    val toolCount: Int,
    val error: String? = null,
    val toolNames: List<String> = emptyList()
)
