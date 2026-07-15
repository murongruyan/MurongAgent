package com.murong.agent.core.tool

import com.murong.agent.common.shell.KeepShellPublic
import com.murong.agent.core.loop.BackgroundJobCompletion
import com.murong.agent.core.loop.BackgroundJobRequest
import kotlinx.serialization.json.*

/**
 * Shell 执行工具——通过 Root Shell 执行任意命令
 */
class ShellTool(
    private val scheduleBackgroundExecution: (suspend (BackgroundJobRequest, suspend () -> BackgroundJobCompletion) -> String)? = null
) : Tool {

    override val name = "shell"
    override val description = "在 Android 设备上以 root 权限执行 shell 命令。返回命令的标准输出。适用于文件操作、进程管理、系统设置、应用管理等。做代码库定位时，如果你已知类名或文件名但还不知道路径，可先用 find/ls 在 src/main、src/test、app/src、core/src、common/src 等源码目录里找精确文件，再用 file.read 或 code_search 看局部内容；不要把 build、intermediates、mapping 产物当成首选证据。"
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
            ),
            "background" to mapOf(
                "type" to "boolean",
                "description" to "是否改为后台执行。默认 false；开启后会立刻返回任务已排队，由会话级后台任务管理器接管。",
                "default" to false
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
            val runInBackground =
                jsonObj["background"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false

            // 检查 root
            if (!KeepShellPublic.checkRoot()) {
                return "Error: Root shell is not available. Please check root permissions."
            }

            if (runInBackground) {
                val scheduler = scheduleBackgroundExecution
                    ?: return "Error: Background shell jobs are not available in this session."
                return scheduler(
                    BackgroundJobRequest(
                        toolName = name,
                        title = "Shell 后台任务",
                        summary = command,
                        detail = command,
                        timeoutSeconds = timeout.coerceAtLeast(1)
                    )
                ) {
                    val output = executeCommand(command, timeout)
                    val failed = isFailureOutput(output)
                    BackgroundJobCompletion(
                        status = if (failed) "failed" else "completed",
                        statusMessage = if (failed) {
                            "后台 shell 命令执行失败。"
                        } else {
                            "后台 shell 命令执行完成。"
                        },
                        resultPreview = output
                    )
                }
            }
            executeCommand(command, timeout)
        } catch (e: Exception) {
            "Error executing shell command: ${e.message}"
        }
    }

    private fun executeCommand(command: String, timeout: Int): String {
        val result = KeepShellPublic.doCmdSync(wrapCommandWithTimeout(command, timeout))
        val timedOut = result.contains(TIMEOUT_MARKER)
        val normalizedResult = result
            .replace(TIMEOUT_MARKER, "")
            .trim()

        return if (normalizedResult.startsWith("error:")) {
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
    }

    private fun isFailureOutput(output: String): Boolean {
        return output.startsWith("Error:", ignoreCase = true) ||
            output.startsWith("Command execution error:", ignoreCase = true)
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
