package com.murong.agent.core.tool

import com.murong.agent.core.workspace.ComputerWorkspaceGateway
import com.murong.agent.core.workspace.ComputerWorkspaceOperation
import com.murong.agent.core.workspace.ComputerWorkspaceRequest
import com.murong.agent.core.workspace.DEFAULT_COMPUTER_WORKSPACE_MAX_TEXT_BYTES
import java.text.Normalizer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ComputerWorkspaceTool(
    private val gateway: ComputerWorkspaceGateway,
    private val allowWrite: Boolean
) : Tool {
    override val name: String = "computer_workspace"

    override val description: String
        get() {
            val workspace = gateway.activeWorkspace()
            val label = workspace?.label?.take(80) ?: "未连接"
            val writeHint = if (allowWrite && workspace?.writable == true) {
                "写入和建目录由手机端 Murong 审批。"
            } else {
                "当前轮次只允许读取。"
            }
            return "访问 Windows Node 明确授权的工作区“$label”。只使用相对路径；支持 list、read、stat、write、mkdir。$writeHint"
        }

    override val parameters: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "operation" to mapOf(
                "type" to "string",
                "enum" to listOf("list", "read", "stat", "write", "mkdir"),
                "description" to "工作区操作"
            ),
            "path" to mapOf(
                "type" to "string",
                "description" to "工作区内的正斜杠相对路径；列根目录时使用 ."
            ),
            "content" to mapOf(
                "type" to "string",
                "description" to "write 的完整 UTF-8 文本内容，最多 1 MiB"
            ),
            "expected_sha256" to mapOf(
                "type" to "string",
                "description" to "覆盖已有文件时必须使用最近一次 read 返回的 SHA-256；创建新文件时省略"
            )
        ),
        "required" to listOf("operation", "path")
    )

    private val json = Json { ignoreUnknownKeys = false }

    override fun buildApprovalRequest(args: String): ToolApprovalRequest? {
        val parsed = parseArgs(args).getOrNull() ?: return null
        if (parsed.operation !in WRITE_OPERATIONS) return null
        val workspace = gateway.activeWorkspace() ?: return null
        return ToolApprovalRequest(
            toolName = name,
            summary = if (parsed.operation == ComputerWorkspaceOperation.MKDIR) {
                "创建电脑工作区目录"
            } else {
                "写入电脑工作区文件"
            },
            detail = "${workspace.label}/${parsed.path}",
            riskLevel = ApprovalRiskLevel.HIGH,
            rawArgs = args,
            pathBoundaryValue = "computer-workspace:${workspace.sessionId}:${parsed.path}"
        )
    }

    override suspend fun execute(args: String): String = executeWithResult(args).output

    override suspend fun executeWithResult(args: String): ToolExecutionResult {
        val parsed = parseArgs(args).getOrElse { error ->
            return failure(error.message ?: "参数无效")
        }
        val workspace = gateway.activeWorkspace()
            ?: return failure("电脑工作区未连接或 Windows Node 授权已过期")
        if (!workspace.readable) return failure("Windows Node 没有授予读取权限")
        if (parsed.operation in WRITE_OPERATIONS) {
            if (!allowWrite) return failure("当前只读/计划模式不允许写入电脑工作区")
            if (!workspace.writable) return failure("Windows Node 没有授予写入权限")
        }
        if (parsed.operation == ComputerWorkspaceOperation.WRITE) {
            val content = parsed.content ?: return failure("write 必须提供 content")
            if (content.toByteArray(Charsets.UTF_8).size > DEFAULT_COMPUTER_WORKSPACE_MAX_TEXT_BYTES) {
                return failure("写入内容超过 1 MiB 上限")
            }
            if (parsed.expectedSha256 != null && !SHA256_PATTERN.matches(parsed.expectedSha256)) {
                return failure("expected_sha256 必须是 64 位十六进制 SHA-256")
            }
        }

        val response = gateway.execute(
            ComputerWorkspaceRequest(
                operation = parsed.operation,
                relativePath = parsed.path,
                content = parsed.content,
                expectedSha256 = parsed.expectedSha256?.lowercase()
            )
        )
        if (!response.success) {
            val code = response.errorCode?.let { " [$it]" }.orEmpty()
            return failure("电脑工作区操作失败$code：${response.errorMessage ?: "未知错误"}")
        }
        return when (parsed.operation) {
            ComputerWorkspaceOperation.LIST -> {
                val rows = response.entries.joinToString("\n") { entry ->
                    val kind = if (entry.directory) "dir " else "file"
                    val metadata = buildList {
                        entry.size?.let { add("size=$it") }
                        entry.lastModified?.let { add("mtime=$it") }
                    }.joinToString(" ")
                    "$kind\t${entry.relativePath}${metadata.takeIf(String::isNotBlank)?.let { "\t$it" }.orEmpty()}"
                }
                success(
                    buildString {
                        append("电脑工作区 ${workspace.label} 的 ${parsed.path}：")
                        if (rows.isBlank()) append("\n(空目录)") else append("\n$rows")
                    }
                )
            }

            ComputerWorkspaceOperation.READ -> success(
                buildString {
                    appendLine("电脑工作区文件：${workspace.label}/${parsed.path}")
                    appendLine("SHA-256: ${response.sha256 ?: "unknown"}")
                    appendLine("大小: ${response.size ?: response.content?.toByteArray(Charsets.UTF_8)?.size ?: 0} bytes")
                    appendLine("--- content ---")
                    append(response.content.orEmpty())
                }
            )

            ComputerWorkspaceOperation.STAT -> success(
                "电脑工作区条目：${workspace.label}/${parsed.path}\n" +
                    "类型: ${if (response.directory == true) "directory" else "file"}\n" +
                    "大小: ${response.size ?: "unknown"}\n" +
                    "修改时间: ${response.lastModified ?: "unknown"}\n" +
                    "SHA-256: ${response.sha256 ?: "not-computed"}"
            )

            ComputerWorkspaceOperation.WRITE -> ToolExecutionResult(
                output = buildString {
                    append(if (response.created) "已创建" else "已写入")
                    append("电脑工作区文件 ${workspace.label}/${parsed.path}")
                    response.sha256?.let { append("\nSHA-256: $it") }
                    append("\nWindows Node 已完成冲突校验和原子写回。")
                },
                fileChanges = listOf(
                    ToolFileChange(
                        path = "computer://${sanitizeLabel(workspace.label)}/${parsed.path}",
                        operation = if (response.created) "create" else "write",
                        afterContent = parsed.content,
                        diffPreview = response.diffPreview.orEmpty()
                    )
                ),
                status = ToolExecutionStatus.SUCCESS,
                success = true
            )

            ComputerWorkspaceOperation.MKDIR -> ToolExecutionResult(
                output = buildString {
                    append(if (response.created) "已创建" else "目录已存在")
                    append("电脑工作区目录 ${workspace.label}/${parsed.path}")
                },
                fileChanges = if (response.created) {
                    listOf(
                        ToolFileChange(
                            path = "computer://${sanitizeLabel(workspace.label)}/${parsed.path}",
                            operation = "mkdir"
                        )
                    )
                } else {
                    emptyList()
                },
                status = ToolExecutionStatus.SUCCESS,
                success = true
            )

            ComputerWorkspaceOperation.RUN -> failure("computer_workspace 不执行终端命令")
        }
    }

    private fun parseArgs(raw: String): Result<ParsedArgs> = runCatching {
        val obj = json.parseToJsonElement(raw).jsonObject
        val operation = when (obj["operation"]?.jsonPrimitive?.contentOrNull?.lowercase()) {
            "list" -> ComputerWorkspaceOperation.LIST
            "read" -> ComputerWorkspaceOperation.READ
            "stat" -> ComputerWorkspaceOperation.STAT
            "write" -> ComputerWorkspaceOperation.WRITE
            "mkdir" -> ComputerWorkspaceOperation.MKDIR
            else -> throw IllegalArgumentException("operation 必须是 list、read、stat、write 或 mkdir")
        }
        val rawPath = obj["path"]?.jsonPrimitive?.contentOrNull
            ?: throw IllegalArgumentException("缺少 path")
        val path = normalizeComputerWorkspaceRelativePath(
            rawPath = rawPath,
            allowRoot = operation == ComputerWorkspaceOperation.LIST
        ).getOrElse { throw it }
        ParsedArgs(
            operation = operation,
            path = path,
            content = obj["content"]?.jsonPrimitive?.contentOrNull,
            expectedSha256 = obj["expected_sha256"]?.jsonPrimitive?.contentOrNull
        )
    }

    private fun success(output: String) = ToolExecutionResult(
        output = output,
        status = ToolExecutionStatus.SUCCESS,
        success = true
    )

    private fun failure(message: String) = ToolExecutionResult(
        output = "Error: $message",
        status = ToolExecutionStatus.FAILURE,
        success = false
    )

    private data class ParsedArgs(
        val operation: ComputerWorkspaceOperation,
        val path: String,
        val content: String?,
        val expectedSha256: String?
    )

    private companion object {
        val SHA256_PATTERN = Regex("^[A-Fa-f0-9]{64}$")
        val WRITE_OPERATIONS = setOf(
            ComputerWorkspaceOperation.WRITE,
            ComputerWorkspaceOperation.MKDIR
        )
    }
}

internal fun normalizeComputerWorkspaceRelativePath(
    rawPath: String,
    allowRoot: Boolean
): Result<String> = runCatching {
    require(rawPath.length <= 1024) { "路径超过 1024 字符上限" }
    require(rawPath.isNotEmpty()) { "路径不能为空" }
    require(rawPath == rawPath.trim()) { "路径首尾不能包含空白" }
    require('\\' !in rawPath) { "路径只能使用正斜杠" }
    require(':' !in rawPath) { "路径不能包含盘符或冒号" }
    require(!rawPath.startsWith('/')) { "不允许绝对路径" }
    require(rawPath.none { it.code == 0 || it.code < 0x20 || it.code == 0x7f }) {
        "路径不能包含控制字符"
    }
    if (rawPath == ".") {
        require(allowRoot) { "该操作不能以工作区根目录作为文件" }
        return@runCatching "."
    }
    val segments = rawPath.split('/')
    require(segments.size <= 64) { "路径层级超过 64 段上限" }
    require(segments.none { it.isEmpty() || it == "." || it == ".." }) {
        "路径不能包含空段、. 或 .."
    }
    require(segments.all { it.length <= 255 }) { "路径段超过 255 字符上限" }
    Normalizer.normalize(segments.joinToString("/"), Normalizer.Form.NFC)
}

private fun sanitizeLabel(value: String): String = value
    .replace('/', '_')
    .replace('\\', '_')
    .take(80)
