package com.murong.agent.core.mcp

import android.content.Context
import com.murong.agent.core.config.SecureConfigSecretStore
import com.murong.agent.core.doctor.SensitiveDataSanitizer
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
    private val serverFailures = mutableMapOf<String, McpFailureRecord>()
    private val serverConnectedAt = mutableMapOf<String, Long>()
    private val toolCacheUpdatedAt = mutableMapOf<String, Long>()
    private val secretStore = SecureConfigSecretStore(context)
    private var configurationGeneration: Long = 0L

    private val configFile: File
        get() = File(context.filesDir, "mcp_servers.json")

    /**
     * 获取所有 MCP 工具（适配为 Tool 接口）
     */
    fun getMcpTools(): List<Tool> {
        return toolCache.values
            .sortedBy(McpToolDef::canonicalToolName)
            .map { McpToolAdapter(this, it) }
    }

    /**
     * 获取所有服务器状态
     */
    fun getServerStatuses(): List<McpServerStatus> {
        val serverNames = (loadConfigs().map { it.name } + transports.keys + serverFailures.keys)
            .distinct()
            .sorted()
        return serverNames.map { name ->
            val transport = transports[name]
            val serverTools = toolCache.values
                .filter { it.serverName == name }
                .sortedBy { it.name }
            val failure = serverFailures[name]
            McpServerStatus(
                name = name,
                connected = transport?.isActive() == true,
                toolCount = serverTools.size,
                error = failure?.message,
                toolNames = serverTools.map { it.name },
                failureRecord = failure,
                lastConnectedAt = serverConnectedAt[name],
                toolCacheUpdatedAt = toolCacheUpdatedAt[name],
                configurationGeneration = configurationGeneration
            )
        }
    }

    /**
     * 连接所有已启用的 MCP 服务器
     */
    suspend fun connectAll(configs: List<McpServerConfig>) {
        withContext(Dispatchers.IO) {
            for (cfg in configs) {
                if (!cfg.enabled) {
                    disconnect(cfg.name)
                    continue
                }
                try {
                    val connectedTransport = transports[cfg.name]
                    if (connectedTransport?.isActive() == true) {
                        refreshToolCache(cfg, connectedTransport)
                        continue
                    }
                    if (connectedTransport != null) disconnect(cfg.name)
                    val transport = McpTransport(cfg)
                    val result = transport.connect()
                    if (result.isSuccess) {
                        transports[cfg.name] = transport
                        serverConnectedAt[cfg.name] = System.currentTimeMillis()
                        serverFailures.remove(cfg.name)
                        refreshToolCache(cfg, transport)
                    } else {
                        serverFailures[cfg.name] = buildFailureRecord(
                            cfg = cfg,
                            stage = McpFailureStage.CONNECT,
                            message = result.exceptionOrNull()?.message ?: "连接失败"
                        )
                    }
                } catch (e: Exception) {
                    serverFailures[cfg.name] = buildFailureRecord(
                        cfg = cfg,
                        stage = McpFailureStage.CONNECT,
                        message = e.message ?: "连接失败"
                    )
                }
            }
        }
    }

    private suspend fun refreshToolCache(cfg: McpServerConfig, transport: McpTransport) {
        val toolsResult = transport.listTools()
        if (toolsResult.isSuccess) {
            toolCache.entries.removeAll { it.value.serverName == cfg.name }
            toolsResult.getOrThrow().forEach { toolDef ->
                toolCache[toolDef.canonicalToolName()] = toolDef
            }
            toolCacheUpdatedAt[cfg.name] = System.currentTimeMillis()
            configurationGeneration += 1
            serverFailures.remove(cfg.name)
        } else {
            // A failed refresh must not leave a stale list available to the next Agent turn.
            toolCache.entries.removeAll { it.value.serverName == cfg.name }
            configurationGeneration += 1
            serverFailures[cfg.name] = buildFailureRecord(
                cfg = cfg,
                stage = McpFailureStage.LIST_TOOLS,
                message = toolsResult.exceptionOrNull()?.message ?: "列出工具失败"
            )
        }
    }

    /**
     * 断开指定服务器
     */
    fun disconnect(serverName: String) {
        transports.remove(serverName)?.disconnect()
        val removedTools = toolCache.entries.removeAll { it.value.serverName == serverName }
        if (removedTools) configurationGeneration += 1
        serverFailures.remove(serverName)
    }

    /**
     * 断开所有
     */
    fun disconnectAll() {
        transports.values.forEach { it.disconnect() }
        transports.clear()
        toolCache.clear()
        serverFailures.clear()
        configurationGeneration += 1
    }

    /**
     * 保存 MCP 服务器配置到文件
     */
    fun saveConfigs(configs: List<McpServerConfig>) {
        val previousConfigs = loadConfigs()
        val previousByName = previousConfigs.associateBy { it.name }
        val nextByName = configs.associateBy { it.name }
        transports.keys
            .filter { name ->
                val next = nextByName[name]
                next == null || !next.enabled || previousByName[name] != next
            }
            .toList()
            .forEach(::disconnect)
        configFile.parentFile?.mkdirs()
        val persisted = configs.map(::moveSecretHeadersToSecureStore)
        configFile.writeText(json.encodeToString(ListSerializer(McpServerConfig.serializer()), persisted))
        val retainedSecretReferences = persisted
            .flatMap { it.authHeaderSecretReferences.values }
            .toSet()
        previousConfigs
            .flatMap { it.authHeaderSecretReferences.values }
            .filterNot(retainedSecretReferences::contains)
            .forEach { obsoleteReference ->
                secretStore.write(obsoleteReference, "")
            }
        configurationGeneration += 1
    }

    /**
     * 加载 MCP 服务器配置
     */
    fun loadConfigs(): List<McpServerConfig> {
        if (!configFile.exists()) return emptyList()
        return try {
            json.decodeFromString(ListSerializer(McpServerConfig.serializer()), configFile.readText())
                .map(::restoreSecretHeaders)
        } catch (error: Exception) {
            serverFailures["config"] = McpFailureRecord(
                stage = McpFailureStage.CONFIG_LOAD,
                message = sanitizeMcpDiagnostic(error.message ?: "加载 MCP 配置失败"),
                retryable = false
            )
            emptyList()
        }
    }

    /** Returns portable MCP definitions without headers, environment values, or arguments that look secret. */
    fun exportBackupConfigs(): List<McpServerConfig> {
        return loadConfigs().map(McpServerConfig::sanitizedForBackup)
    }

    fun validateBackupConfigs(configs: List<McpServerConfig>) {
        require(configs.size <= 100) { "MCP 服务器数量超过上限" }
        require(configs.map { it.name }.distinct().size == configs.size) { "MCP 服务器名称重复" }
        require(configs.all { it.name.isNotBlank() && it.name.length <= 200 }) { "MCP 服务器名称无效" }
        require(configs.all { it == it.sanitizedForBackup() }) {
            "MCP 备份包含不允许导入的敏感值或安全存储引用"
        }
    }

    /** Restores definitions while retaining matching credentials already stored on this device. */
    fun restoreBackupConfigs(configs: List<McpServerConfig>) {
        validateBackupConfigs(configs)
        val currentByName = loadConfigs().associateBy { it.name }
        val restored = configs.map { portable ->
            val current = currentByName[portable.name]
            val currentSensitiveHeaders = current?.headers
                .orEmpty()
                .filterKeys(String::isSensitiveMcpHeader)
            val currentSensitiveEnvironment = current?.env
                .orEmpty()
                .filterKeys(String::isSensitiveMcpIdentifier)
            portable.copy(
                headers = portable.headers + currentSensitiveHeaders,
                env = portable.env + currentSensitiveEnvironment,
                args = current?.args
                    ?.takeIf { it.sanitizedMcpArguments() == portable.args }
                    ?: portable.args,
                url = current?.url
                    ?.takeIf { it.sanitizeMcpInlineSecrets() == portable.url }
                    ?: portable.url,
                authHeaderSecretReferences = emptyMap()
            )
        }
        saveConfigs(restored)
    }

    private fun moveSecretHeadersToSecureStore(config: McpServerConfig): McpServerConfig {
        val retainedHeaders = config.headers.toMutableMap()
        val references = config.authHeaderSecretReferences.toMutableMap()
        config.headers.forEach { (headerName, value) ->
            if (headerName.isSensitiveMcpHeader() && value.isNotBlank()) {
                val reference = references[headerName].orEmpty().ifBlank {
                    "mcp_header_${canonicalMcpIdentifierPart(config.name)}_${canonicalMcpIdentifierPart(headerName)}"
                }
                secretStore.write(reference, value)
                references[headerName] = reference
                retainedHeaders.remove(headerName)
            }
        }
        return config.copy(
            headers = retainedHeaders,
            authHeaderSecretReferences = references
        )
    }

    private fun restoreSecretHeaders(config: McpServerConfig): McpServerConfig {
        if (config.authHeaderSecretReferences.isEmpty()) return config
        val restored = config.headers.toMutableMap()
        config.authHeaderSecretReferences.forEach { (headerName, reference) ->
            secretStore.read(reference).takeIf { it.isNotBlank() }?.let { value ->
                restored[headerName] = value
            }
        }
        return config.copy(headers = restored)
    }

    fun getTrustedReadOnlyToolNames(): Set<String> {
        return loadConfigs()
            .asSequence()
            .filter { it.enabled }
            .flatMap { cfg ->
                cfg.trustedReadOnlyTools.asSequence().map { tool ->
                    canonicalMcpToolName(cfg.name, tool)
                }
            }
            .filter { it.isNotBlank() }
            .toSet()
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

        val result = transport.callTool(toolDef.name, argsForCall)
        return result.fold(
            onSuccess = { element ->
                try {
                    json.encodeToString(JsonElement.serializer(), element)
                } catch (_: Exception) {
                    element.toString()
                }
            },
            onFailure = { error ->
                val diagnostic = sanitizeMcpDiagnostic(error.message ?: "unknown error")
                serverFailures[toolDef.serverName] = McpFailureRecord(
                    stage = McpFailureStage.CALL_TOOL,
                    message = diagnostic,
                    transport = loadConfigs().firstOrNull { cfg -> cfg.name == toolDef.serverName }?.transport
                )
                "Error calling MCP tool '$toolName': $diagnostic"
            }
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

    override val name: String get() = toolDef.canonicalToolName()
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
        return registry.callTool(name, args)
    }
}

private fun buildMcpApprovalScopeTokens(toolDef: McpToolDef): Set<String> {
    if (!toolDef.isGitHubTool()) return emptySet()
    return if (toolDef.isGitHubWriteTool()) setOf("mcp:github:write") else emptySet()
}

private fun buildFailureRecord(
    cfg: McpServerConfig,
    stage: McpFailureStage,
    message: String
): McpFailureRecord {
    return McpFailureRecord(
        stage = stage,
        message = sanitizeMcpDiagnostic(message),
        transport = cfg.transport
    )
}

private fun String.isSensitiveMcpHeader(): Boolean {
    val normalized = trim().lowercase(Locale.ROOT)
    return normalized.contains("auth") ||
        normalized.contains("cookie") ||
        normalized.contains("credential") ||
        normalized.contains("key") ||
        normalized.contains("api-key") ||
        normalized.contains("token") ||
        normalized.contains("secret") ||
        normalized.contains("password")
}

private fun String.isSensitiveMcpIdentifier(): Boolean {
    val normalized = trim()
        .removePrefix("--")
        .removePrefix("-")
        .lowercase(Locale.ROOT)
        .replace('_', '-')
    return normalized.contains("auth") ||
        normalized.contains("cookie") ||
        normalized.contains("credential") ||
        normalized.contains("key") ||
        normalized.contains("api-key") ||
        normalized.contains("apikey") ||
        normalized.contains("token") ||
        normalized.contains("secret") ||
        normalized.contains("password") ||
        normalized.endsWith("credential")
}

internal fun McpServerConfig.sanitizedForBackup(): McpServerConfig {
    return copy(
        env = env.filterKeys { !it.isSensitiveMcpIdentifier() },
        headers = headers.filterKeys { !it.isSensitiveMcpHeader() },
        authHeaderSecretReferences = emptyMap(),
        args = args.sanitizedMcpArguments(),
        url = url.sanitizeMcpInlineSecrets()
    )
}

private fun List<String>.sanitizedMcpArguments(): List<String> {
    val sanitizedArguments = toMutableList()
    var index = 0
    while (index < sanitizedArguments.size) {
        val argument = sanitizedArguments[index]
        val key = argument.substringBefore('=', argument)
        when {
            key.isSensitiveMcpIdentifier() && '=' in argument -> {
                sanitizedArguments[index] = "${argument.substringBefore('=')}="
            }
            key.isSensitiveMcpIdentifier() && index + 1 < sanitizedArguments.size -> {
                sanitizedArguments[index + 1] = ""
                index += 1
            }
            (argument.equals("-H", ignoreCase = true) || argument.equals("--header", ignoreCase = true)) &&
                index + 1 < sanitizedArguments.size -> {
                val header = sanitizedArguments[index + 1]
                val headerName = header.substringBefore(':', header)
                if (headerName.isSensitiveMcpHeader()) {
                    sanitizedArguments[index + 1] = "$headerName:"
                }
                index += 1
            }
            else -> sanitizedArguments[index] = argument.sanitizeMcpInlineSecrets()
        }
        index += 1
    }
    return sanitizedArguments
}

private fun String.sanitizeMcpInlineSecrets(): String {
    return replace(
        Regex("(?i)(https?://)[^/@\\s]+@"),
        "$1"
    ).replace(
        Regex("(?i)([?&](?:api[_-]?key|access[_-]?token|token|secret|password)=)[^&#\\s]*"),
        "$1"
    )
}

private fun sanitizeMcpDiagnostic(value: String): String {
    return SensitiveDataSanitizer.sanitizeText(value, redactPaths = false)
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
