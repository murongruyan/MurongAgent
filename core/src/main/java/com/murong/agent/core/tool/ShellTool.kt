package com.murong.agent.core.tool

import com.murong.agent.common.shell.ExtensionShellExecutor
import com.murong.agent.common.shell.KeepShellPublic
import com.murong.agent.common.toolchain.ToolchainManager
import com.murong.agent.core.loop.BackgroundJobCompletion
import com.murong.agent.core.loop.BackgroundJobRequest
import kotlinx.serialization.json.*
import java.io.File

/**
 * Shell 执行工具——按调用参数选择 Root 系统环境或应用 UID 扩展环境。
 */
class ShellTool(
    private val scheduleBackgroundExecution: (suspend (BackgroundJobRequest, suspend () -> BackgroundJobCompletion) -> String)? = null,
    private val workingDirectoryProvider: () -> String? = { null },
    private val rootAvailableProvider: () -> Boolean = KeepShellPublic::checkRoot,
    private val systemCommandExecutor: (String, Int) -> String = ::executeSystemCommand,
    private val extensionAvailableProvider: () -> Boolean = ExtensionShellExecutor::isAvailable,
    private val extensionCommandExecutor: (String, Int, File?) -> ExtensionShellExecutor.Result =
        ExtensionShellExecutor::execute
) : Tool {

    override val name = "shell"
    override val description = "在 Android 设备上执行 shell 命令。environment=system（默认）使用 Root 系统 shell，适合 pm、dumpsys、settings、进程和系统文件操作；environment=extension 使用应用 UID 下的终端扩展包 PRoot/Termux 环境，适合 pkg、apt、dpkg、python、pip、clang 等开发命令。返回标准输出和错误输出。做代码库定位时，如果你已知类名或文件名但还不知道路径，可先用 find/ls 在 src/main、src/test、app/src、core/src、common/src 等源码目录里找精确文件，再用 file.read 或 code_search 看局部内容；不要把 build、intermediates、mapping 产物当成首选证据。"
    override val parameters: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "command" to mapOf(
                "type" to "string",
                "description" to "要执行的 shell 命令"
            ),
            "environment" to mapOf(
                "type" to "string",
                "enum" to listOf("system", "extension"),
                "description" to "执行环境：system 为 Root Android 系统环境；extension 为应用 UID 下的终端扩展包环境。默认 system",
                "default" to "system"
            ),
            "working_directory" to mapOf(
                "type" to "string",
                "description" to "可选工作目录；默认使用当前项目作用域目录"
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
            val environmentLabel = when (
                jsonObj["environment"]?.jsonPrimitive?.content?.trim()?.lowercase()
            ) {
                "extension" -> "终端扩展环境"
                else -> "Root 系统环境"
            }
            ToolApprovalRequest(
                toolName = name,
                summary = "在${environmentLabel}执行命令",
                detail = command,
                riskLevel = ApprovalRiskLevel.HIGH,
                rawArgs = args,
                commandBoundaryValue = command
            )
        } catch (_: Exception) {
            ToolApprovalRequest(
                toolName = name,
                summary = "执行 shell 命令",
                detail = args,
                riskLevel = ApprovalRiskLevel.HIGH,
                rawArgs = args,
                commandBoundaryValue = args
            )
        }
    }

    override suspend fun execute(args: String): String = executeWithResult(args).output

    override suspend fun executeWithResult(args: String): ToolExecutionResult {
        return try {
            val jsonObj = json.parseToJsonElement(args).jsonObject
            val command = jsonObj["command"]?.jsonPrimitive?.content
                ?: return failureResult("Error: 'command' parameter is required")

            val timeout = jsonObj["timeout"]?.jsonPrimitive?.intOrNull ?: 10
            val environment = when (
                jsonObj["environment"]?.jsonPrimitive?.content?.trim()?.lowercase().orEmpty()
            ) {
                "", "system" -> ShellEnvironment.SYSTEM
                "extension" -> ShellEnvironment.EXTENSION
                else -> return failureResult("Error: 'environment' must be 'system' or 'extension'.")
            }
            val workingDirectory = jsonObj["working_directory"]
                ?.jsonPrimitive
                ?.content
                ?.trim()
                ?.takeIf(String::isNotBlank)
                ?: workingDirectoryProvider()?.trim()?.takeIf(String::isNotBlank)
            val runInBackground =
                jsonObj["background"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false

            if (environment == ShellEnvironment.SYSTEM && !rootAvailableProvider()) {
                return failureResult("Error: Root shell is not available. Please check root permissions.")
            }
            if (environment == ShellEnvironment.EXTENSION && !extensionAvailableProvider()) {
                return failureResult(
                    "Error: Terminal extension environment is unavailable. Please install or update the terminal extension package."
                )
            }

            if (runInBackground) {
                val scheduler = scheduleBackgroundExecution
                    ?: return failureResult("Error: Background shell jobs are not available in this session.")
                val scheduleOutput = scheduler(
                    BackgroundJobRequest(
                        toolName = name,
                        title = "Shell 后台任务 · ${environment.label}",
                        summary = command,
                        detail = command,
                        timeoutSeconds = timeout.coerceAtLeast(1)
                    )
                ) {
                    val commandResult = executeCommand(command, timeout, environment, workingDirectory)
                    val failed = commandResult.resolvedSuccess == false ||
                        (commandResult.resolvedSuccess == null && isFailureOutput(commandResult.output))
                    BackgroundJobCompletion(
                        status = if (failed) "failed" else "completed",
                        statusMessage = if (failed) {
                            "后台 shell 命令执行失败。"
                        } else {
                            "后台 shell 命令执行完成。"
                        },
                        resultPreview = commandResult.output
                    )
                }
                return if (isFailureOutput(scheduleOutput)) {
                    failureResult(scheduleOutput)
                } else {
                    successResult(scheduleOutput)
                }
            }
            executeCommand(command, timeout, environment, workingDirectory)
        } catch (e: Exception) {
            failureResult("Error executing shell command: ${e.message}")
        }
    }

    private fun executeCommand(
        command: String,
        timeout: Int,
        environment: ShellEnvironment,
        workingDirectory: String?
    ): ToolExecutionResult {
        if (environment == ShellEnvironment.EXTENSION) {
            return formatExtensionResult(
                extensionCommandExecutor(
                    command,
                    timeout.coerceAtLeast(1),
                    workingDirectory?.let(::File)
                ),
                timeout
            )
        }
        val scopedCommand = buildWorkingDirectoryCommand(command, workingDirectory)
        return formatSystemResult(systemCommandExecutor(scopedCommand, timeout), timeout)
    }

    private fun formatExtensionResult(
        result: ExtensionShellExecutor.Result,
        timeout: Int
    ): ToolExecutionResult {
        result.error?.let {
            return failureResult(
                output = "Command execution error: $it",
                exitCode = result.exitCode,
                timedOut = result.timedOut
            )
        }
        val normalizedResult = result.output.trim()
        if (result.timedOut) {
            val output = if (normalizedResult.isBlank()) {
                "Command timed out after ${timeout.coerceAtLeast(1)}s with no output."
            } else {
                "$normalizedResult\n\n...(命令执行超时，已中止，timeout=${timeout.coerceAtLeast(1)}s)"
            }
            return failureResult(output, exitCode = result.exitCode, timedOut = true)
        }
        if (result.exitCode != null && result.exitCode != 0) {
            val output = if (normalizedResult.isBlank()) {
                "Command execution error: extension command exited with code ${result.exitCode}."
            } else {
                "Command execution error (exit ${result.exitCode}):\n$normalizedResult"
            }
            return failureResult(output, exitCode = result.exitCode)
        }
        return successResult(
            output = normalizedResult.ifBlank { "(command completed, no output)" },
            exitCode = result.exitCode
        )
    }

    private fun formatSystemResult(rawResult: String, timeout: Int): ToolExecutionResult {
        val timedOut = rawResult.contains(TIMEOUT_MARKER)
        val exitCode = EXIT_CODE_PATTERN.findAll(rawResult)
            .lastOrNull()
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
        val normalizedResult = rawResult
            .replace(TIMEOUT_MARKER, "")
            .replace(EXIT_CODE_PATTERN, "")
            .trim()

        if (timedOut) {
            val output = if (normalizedResult.isBlank()) {
                "Command timed out after ${timeout.coerceAtLeast(1)}s with no output."
            } else {
                "$normalizedResult\n\n...(命令执行超时，已中止，timeout=${timeout.coerceAtLeast(1)}s)"
            }
            return failureResult(output, exitCode = exitCode, timedOut = true)
        }
        if (exitCode != null && exitCode != 0) {
            val output = if (normalizedResult.isBlank()) {
                "Command execution error: system command exited with code $exitCode."
            } else {
                "Command execution error (exit $exitCode):\n$normalizedResult"
            }
            return failureResult(output, exitCode = exitCode)
        }
        if (exitCode == null && normalizedResult.startsWith("error:", ignoreCase = true)) {
            return failureResult("Command execution error: $normalizedResult")
        }
        return successResult(
            output = normalizedResult.ifBlank { "(command completed, no output)" },
            exitCode = exitCode
        )
    }

    private fun buildWorkingDirectoryCommand(command: String, workingDirectory: String?): String {
        val systemPreamble = buildString {
            append("export PATH=")
            append(shellQuote(ToolchainManager.buildSystemPath()))
            append("; unset PREFIX TERMUX__PREFIX TERMUX__ROOTFS TERMUX__HOME ")
            append("TERMUX__CACHE_DIR TERMUX_APP__PACKAGE_NAME TERMUX_APP__DATA_DIR ")
            append("TERMUX_APP_PACKAGE_MANAGER PROOT_LOADER PROOT_TMP_DIR LD_PRELOAD LD_LIBRARY_PATH; ")
            append("hash -r >/dev/null 2>&1 || true; ")
        }
        val directory = workingDirectory?.trim()?.takeIf(String::isNotBlank)
        val directoryCommand = if (directory == null) {
            ""
        } else {
            "cd ${shellQuote(directory)} || { echo '工作目录不可访问'; exit 1; }; "
        }
        return systemPreamble + directoryCommand + command
    }

    private enum class ShellEnvironment(val label: String) {
        SYSTEM("系统 Root 环境"),
        EXTENSION("终端扩展环境")
    }

    private companion object {
        const val TIMEOUT_MARKER = "__RSNX_TIMEOUT__"
        const val EXIT_CODE_MARKER = "__RSNX_EXIT_CODE__"
        val EXIT_CODE_PATTERN = Regex("(?m)^${EXIT_CODE_MARKER}(-?\\d+)\\s*$")

        fun executeSystemCommand(command: String, timeout: Int): String {
            return KeepShellPublic.doCmdSync(wrapCommandWithTimeout(command, timeout))
        }

        fun wrapCommandWithTimeout(command: String, timeoutSeconds: Int): String {
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
                    echo "${EXIT_CODE_MARKER}${'$'}status"
                    exit ${'$'}status
                )
            """.trimIndent()
        }

        fun shellQuote(value: String): String {
            return "'${value.replace("'", "'\"'\"'")}'"
        }
    }

    private fun isFailureOutput(output: String): Boolean {
        return output.startsWith("Error:", ignoreCase = true) ||
            output.startsWith("Command execution error", ignoreCase = true) ||
            output.startsWith("Command timed out", ignoreCase = true)
    }

    private fun successResult(output: String, exitCode: Int? = null): ToolExecutionResult {
        return ToolExecutionResult(
            output = output,
            status = ToolExecutionStatus.SUCCESS,
            success = true,
            exitCode = exitCode,
            timedOut = false
        )
    }

    private fun failureResult(
        output: String,
        exitCode: Int? = null,
        timedOut: Boolean = false
    ): ToolExecutionResult {
        return ToolExecutionResult(
            output = output,
            status = if (timedOut) ToolExecutionStatus.TIMED_OUT else ToolExecutionStatus.FAILURE,
            success = false,
            exitCode = exitCode,
            timedOut = timedOut
        )
    }

}
