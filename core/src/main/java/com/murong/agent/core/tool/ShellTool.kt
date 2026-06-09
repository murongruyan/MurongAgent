package com.murong.agent.core.tool

import com.murong.agent.common.shell.KeepShellPublic
import kotlinx.serialization.json.*

/**
 * Shell 执行工具——通过 Root Shell 执行任意命令
 */
class ShellTool : Tool {

    override val name = "shell"
    override val description = "在 Android 设备上以 root 权限执行 shell 命令。返回命令的标准输出。适用于文件操作、进程管理、系统设置、应用管理等。"
    override val parameters: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "command" to mapOf(
                "type" to "string",
                "description" to "要执行的 shell 命令"
            ),
            "timeout" to mapOf(
                "type" to "integer",
                "description" to "超时时间（秒），默认 10",
                "default" to 10
            )
        ),
        "required" to listOf("command")
    )

    private val json = Json { ignoreUnknownKeys = true }

    override fun buildApprovalRequest(args: String): ToolApprovalRequest? {
        return try {
            val jsonObj = json.parseToJsonElement(args).jsonObject
            val command = jsonObj["command"]?.jsonPrimitive?.content ?: return null
            ToolApprovalRequest(
                toolName = name,
                summary = "执行 root shell 命令",
                detail = command,
                riskLevel = ApprovalRiskLevel.HIGH,
                rawArgs = args,
                commandBoundaryValue = command
            )
        } catch (_: Exception) {
            ToolApprovalRequest(
                toolName = name,
                summary = "执行 root shell 命令",
                detail = args,
                riskLevel = ApprovalRiskLevel.HIGH,
                rawArgs = args,
                commandBoundaryValue = args
            )
        }
    }

    override suspend fun execute(args: String): String {
        return try {
            val jsonObj = json.parseToJsonElement(args).jsonObject
            val command = jsonObj["command"]?.jsonPrimitive?.content
                ?: return "Error: 'command' parameter is required"

            val timeout = jsonObj["timeout"]?.jsonPrimitive?.intOrNull ?: 10

            // 检查 root
            if (!KeepShellPublic.checkRoot()) {
                return "Error: Root shell is not available. Please check root permissions."
            }

            val result = KeepShellPublic.doCmdSync(wrapCommandWithTimeout(command, timeout))
            val timedOut = result.contains(TIMEOUT_MARKER)
            val normalizedResult = result
                .replace(TIMEOUT_MARKER, "")
                .trim()

            if (normalizedResult.startsWith("error:")) {
                "Command execution error: $normalizedResult"
            } else if (timedOut) {
                if (normalizedResult.isBlank()) {
                    "Command timed out after ${timeout.coerceAtLeast(1)}s with no output."
                } else {
                    "$normalizedResult\n\n...(命令执行超时，已中止，timeout=${timeout.coerceAtLeast(1)}s)"
                }
            } else {
                normalizedResult.ifBlank { "(command completed, no output)" }
            }
        } catch (e: Exception) {
            "Error executing shell command: ${e.message}"
        }
    }

    private fun wrapCommandWithTimeout(command: String, timeoutSeconds: Int): String {
        val safeTimeout = timeoutSeconds.coerceAtLeast(1)
        return """
            (
                (
                    $command
                ) 2>&1 &
                cmd_pid=${'$'}!
                (
                    sleep $safeTimeout
                    if kill -0 ${'$'}cmd_pid 2>/dev/null; then
                        echo "$TIMEOUT_MARKER"
                        kill -TERM ${'$'}cmd_pid 2>/dev/null
                        sleep 1
                        kill -KILL ${'$'}cmd_pid 2>/dev/null
                    fi
                ) &
                watchdog_pid=${'$'}!
                wait ${'$'}cmd_pid
                status=${'$'}?
                kill ${'$'}watchdog_pid 2>/dev/null
                wait ${'$'}watchdog_pid 2>/dev/null
                exit ${'$'}status
            )
        """.trimIndent()
    }

    private companion object {
        const val TIMEOUT_MARKER = "__RSNX_TIMEOUT__"
    }
}
