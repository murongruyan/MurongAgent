package com.murong.agent.core.tool

import com.murong.agent.core.workspace.ComputerWorkspaceGateway
import com.murong.agent.core.workspace.ComputerWorkspaceOperation
import com.murong.agent.core.workspace.ComputerWorkspaceRequest
import com.murong.agent.core.workspace.ComputerTerminalDescriptor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ComputerTerminalTool(
    private val gateway: ComputerWorkspaceGateway,
    private val allowExecute: Boolean
) : Tool {
    override val name: String = "computer_terminal"

    override val description: String
        get() {
            val workspace = gateway.activeWorkspace()
            val label = workspace?.label?.take(80) ?: "未连接"
            val terminals = workspace?.effectiveTerminalBackends.orEmpty().joinToString("；") {
                "${it.id}=${it.label}${it.version.takeIf { version -> version.isNotBlank() }?.let { version -> " $version" }.orEmpty()}"
            }.ifBlank { "未启用" }
            return "在 Windows Node 已授权的电脑终端中执行命令，默认工作目录为电脑工作区“$label”。" +
                "可用终端：$terminals。PowerShell、CMD 与 WSL 使用各自的命令语法；Windows Terminal 只是宿主，不是 Shell。" +
                "命令以 Windows Node 当前用户或所选 WSL 用户权限运行，可能访问工作区之外；每次调用都由手机端审批策略处理。"
        }

    override val parameters: Map<String, Any>
        get() {
            val terminals = gateway.activeWorkspace()?.effectiveTerminalBackends.orEmpty()
            return mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "command" to mapOf(
                        "type" to "string",
                        "description" to "要交给所选电脑终端执行的命令，最多 16384 字符"
                    ),
                    "terminal" to mapOf(
                        "type" to "string",
                        "enum" to terminals.map { it.id },
                        "description" to "终端 ID；省略时使用 Windows Node 配置中的第一个终端"
                    ),
                    "path" to mapOf(
                        "type" to "string",
                        "description" to "工作区内的相对工作目录；根目录使用 ."
                    ),
                    "timeout_seconds" to mapOf(
                        "type" to "integer",
                        "minimum" to 1,
                        "maximum" to 600,
                        "description" to "超时秒数，默认 120，最多 600"
                    )
                ),
                "required" to listOf("command")
            )
        }

    private val json = Json { ignoreUnknownKeys = false }

    override fun buildApprovalRequest(args: String): ToolApprovalRequest? {
        val parsed = parseArgs(args).getOrNull() ?: return null
        val workspace = gateway.activeWorkspace() ?: return null
        val terminal = resolveTerminal(workspace.effectiveTerminalBackends, parsed.terminalId) ?: return null
        return ToolApprovalRequest(
            toolName = name,
            summary = "在电脑 ${terminal.label} 执行命令",
            detail = "终端：${terminal.label}${terminal.version.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()}\n" +
                "${workspace.label}/${parsed.path}\n${parsed.command.take(500)}",
            riskLevel = ApprovalRiskLevel.HIGH,
            rawArgs = args,
            commandBoundaryValue = parsed.command,
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
        if (!allowExecute) return failure("当前只读/计划模式不允许执行电脑终端命令")
        if (!workspace.terminal) return failure("Windows Node 没有启用电脑终端能力")
        val terminal = resolveTerminal(workspace.effectiveTerminalBackends, parsed.terminalId)
            ?: return failure("请求的电脑终端未启用；可用终端：${workspace.effectiveTerminalBackends.joinToString { it.id }}")

        val response = gateway.execute(
            ComputerWorkspaceRequest(
                operation = ComputerWorkspaceOperation.RUN,
                relativePath = parsed.path,
                command = parsed.command,
                terminalId = terminal.id,
                timeoutMillis = parsed.timeoutSeconds * 1_000L
            )
        )
        if (!response.success) {
            val code = response.errorCode?.let { " [$it]" }.orEmpty()
            return failure("电脑终端执行失败$code：${response.errorMessage ?: "未知错误"}")
        }

        val exitCode = response.exitCode
        val succeeded = !response.timedOut && exitCode == 0
        return ToolExecutionResult(
            output = buildString {
                appendLine("电脑终端（${terminal.label}）：${workspace.label}/${parsed.path}")
                appendLine("退出码: ${exitCode ?: "unknown"}${if (response.timedOut) "（已超时）" else ""}")
                appendLine("--- stdout ---")
                appendLine(response.stdout.orEmpty())
                appendLine("--- stderr ---")
                append(response.stderr.orEmpty())
            },
            status = when {
                response.timedOut -> ToolExecutionStatus.TIMED_OUT
                succeeded -> ToolExecutionStatus.SUCCESS
                else -> ToolExecutionStatus.FAILURE
            },
            success = succeeded,
            exitCode = exitCode,
            timedOut = response.timedOut
        )
    }

    private fun parseArgs(raw: String): Result<ParsedArgs> = runCatching {
        val obj = json.parseToJsonElement(raw).jsonObject
        val command = obj["command"]?.jsonPrimitive?.contentOrNull
            ?: throw IllegalArgumentException("缺少 command")
        require(command.isNotBlank()) { "command 不能为空" }
        require(command.length <= MAX_COMMAND_CHARS) { "command 超过 $MAX_COMMAND_CHARS 字符上限" }
        require(command.none { it.code == 0 }) { "command 不能包含 NUL 字符" }
        val path = normalizeComputerWorkspaceRelativePath(
            rawPath = obj["path"]?.jsonPrimitive?.contentOrNull ?: ".",
            allowRoot = true
        ).getOrElse { throw it }
        val timeoutSeconds = obj["timeout_seconds"]?.jsonPrimitive?.intOrNull ?: DEFAULT_TIMEOUT_SECONDS
        require(timeoutSeconds in 1..MAX_TIMEOUT_SECONDS) {
            "timeout_seconds 必须在 1 到 $MAX_TIMEOUT_SECONDS 之间"
        }
        val terminalId = obj["terminal"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        ParsedArgs(command, path, timeoutSeconds, terminalId)
    }

    private fun resolveTerminal(
        available: List<ComputerTerminalDescriptor>,
        requestedId: String?
    ) = if (requestedId == null) available.firstOrNull() else available.firstOrNull { it.id == requestedId }

    private fun failure(message: String) = ToolExecutionResult(
        output = "Error: $message",
        status = ToolExecutionStatus.FAILURE,
        success = false
    )

    private data class ParsedArgs(
        val command: String,
        val path: String,
        val timeoutSeconds: Int,
        val terminalId: String?
    )

    private companion object {
        const val MAX_COMMAND_CHARS = 16_384
        const val DEFAULT_TIMEOUT_SECONDS = 120
        const val MAX_TIMEOUT_SECONDS = 600
    }
}
