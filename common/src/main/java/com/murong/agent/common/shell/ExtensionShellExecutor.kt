package com.murong.agent.common.shell

import com.murong.agent.common.toolchain.ToolchainManager
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * 在应用 UID 下执行终端扩展包命令。
 *
 * 与 Root [KeepShell] 分开运行，避免 pkg/apt 创建仅 Root 可维护的文件；
 * PRoot、固定 Termux 前缀和 termux-exec 环境均由 [ToolchainManager] 统一提供。
 */
object ExtensionShellExecutor {

    private const val MAX_OUTPUT_CHARS = 1_000_000

    data class Result(
        val output: String,
        val exitCode: Int? = null,
        val timedOut: Boolean = false,
        val error: String? = null
    )

    fun isAvailable(): Boolean {
        return ToolchainManager.hasRelocatablePackageManager() &&
            ToolchainManager.getBundledCommandPath("bash") != null
    }

    fun execute(
        command: String,
        timeoutSeconds: Int,
        workingDirectory: File? = null
    ): Result {
        if (!isAvailable()) {
            return Result(
                output = "",
                error = "终端扩展环境不可用；请安装或更新终端扩展包。"
            )
        }
        if (workingDirectory != null && !workingDirectory.isDirectory) {
            return Result(
                output = "",
                error = "工作目录不存在或应用无法访问：${workingDirectory.absolutePath}"
            )
        }

        val bash = ToolchainManager.getBundledCommandPath("bash")
            ?: return Result(output = "", error = "终端扩展环境缺少 bash。")
        val launchCommand = ToolchainManager.buildPackageCompatibleCommand(
            listOf(bash, "-c", command)
        )
        val safeTimeout = timeoutSeconds.coerceAtLeast(1)

        return runCatching {
            val process = ProcessBuilder(launchCommand)
                .apply {
                    workingDirectory?.let(::directory)
                    redirectErrorStream(true)
                    ToolchainManager.applyPackageCompatibleEnvironment(environment())
                }
                .start()
            val output = StringBuilder(4096)
            val outputLock = Any()
            var truncated = false
            val readerThread = Thread {
                runCatching {
                    InputStreamReader(process.inputStream, Charsets.UTF_8).use { reader ->
                        val buffer = CharArray(8 * 1024)
                        while (true) {
                            val count = reader.read(buffer)
                            if (count < 0) break
                            synchronized(outputLock) {
                                val remaining = MAX_OUTPUT_CHARS - output.length
                                if (remaining > 0) {
                                    output.append(buffer, 0, count.coerceAtMost(remaining))
                                }
                                if (count > remaining) truncated = true
                            }
                        }
                    }
                }
            }.apply {
                name = "murong-extension-shell-output"
                isDaemon = true
                start()
            }

            val finished = process.waitFor(safeTimeout.toLong(), TimeUnit.SECONDS)
            if (!finished) {
                process.destroy()
                if (!process.waitFor(800, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly()
                    process.waitFor(800, TimeUnit.MILLISECONDS)
                }
            }
            readerThread.join(1_500)
            val captured = synchronized(outputLock) {
                buildString {
                    append(output)
                    if (truncated) {
                        if (isNotEmpty() && last() != '\n') append('\n')
                        append("...(输出已截断到 1000000 字符)")
                    }
                }
            }
            Result(
                output = captured,
                exitCode = if (finished) process.exitValue() else null,
                timedOut = !finished
            )
        }.getOrElse { error ->
            Result(
                output = "",
                error = error.message ?: "启动终端扩展环境失败。"
            )
        }
    }
}
