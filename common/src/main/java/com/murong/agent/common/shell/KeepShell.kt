package com.murong.agent.common.shell

import com.murong.agent.common.toolchain.ToolchainManager
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 同步 Root Shell 会话
 * 移植自 murongdiaodu-apk/common/.../shell/KeepShell.kt
 *
 * 维持一个持久的 su 进程，通过标签标记来精确获取命令输出。
 */
class KeepShell(
    private val tag: String = "RSNX"
) {
    private var process: Process? = null
    private var writer: java.io.OutputStreamWriter? = null
    private var reader: BufferedReader? = null
    private var stderrReader: BufferedReader? = null
    private val lock = ReentrantLock()

    private val markerStart = "|${tag}>>|"
    private val markerEnd = "|<<${tag}|"

    /**
     * 检查 Root 是否可用
     */
    fun checkRoot(): Boolean {
        return try {
            val result = doCmdSync(
                """
                id -u || echo 'no_id'
                whoami
                set | grep -E '^USER_ID=0' || true
                echo 'success'
                """.trimIndent()
            )
            result.contains("success")
        } catch (e: Exception) {
            false
        }
    }

    companion object {
        private const val MAX_OUTPUT_CHARS = 1_000_000
        private const val MAX_OUTPUT_LINES = 50_000
    }

    /**
     * 执行一条同步命令，返回 output
     */
    fun doCmdSync(command: String): String {
        initProcess()
        lock.withLock {
            try {
                val pw = writer ?: return "error: no writer"
                val br = reader ?: return "error: no reader"

                // 写入命令，用标签包裹以便截取输出
                pw.write("printf '%s\\n' '$markerStart'\n")
                pw.write("$command\n")
                // Always force the end marker onto its own line. Some files (for example
                // shell scripts without a trailing newline) can otherwise glue the marker to
                // the last output line, causing the reader loop to wait forever.
                pw.write("printf '\\n%s\\n' '$markerEnd'\n")
                pw.flush()

                // 读取直到找到结束标签，限制输出大小防止 OOM
                val output = StringBuilder(4096)
                var inBlock = false
                var line: String?
                var lineCount = 0
                while (br.readLine().also { line = it } != null) {
                    val l = line!!
                    if (l == markerStart) {
                        inBlock = true
                        continue
                    }
                    if (l == markerEnd) {
                        break
                    }
                    if (inBlock) {
                        lineCount++
                        if (output.length >= MAX_OUTPUT_CHARS || lineCount > MAX_OUTPUT_LINES) {
                            if (output.length < MAX_OUTPUT_CHARS) {
                                output.append("\n...(truncated, output too large)")
                            }
                            // Drain remaining lines until end marker to keep shell state clean
                            while (br.readLine().also { line = it } != null) {
                                if (line == markerEnd) break
                            }
                            break
                        }
                        if (output.isNotEmpty()) output.append('\n')
                        output.append(l)
                    }
                }
                return output.toString()
            } catch (e: IOException) {
                tryExit()
                return "error: ${e.message}"
            }
        }
    }

    private fun initProcess() {
        if (process != null && process!!.isAlive) return
        try {
            ToolchainManager.ensureInstalled()
            val pb = ProcessBuilder(RootShellStrategy.buildRootCommand())
            pb.redirectErrorStream(false)
            ToolchainManager.applyProcessEnvironment(pb.environment())
            process = pb.start()
            writer = OutputStreamWriter(process!!.outputStream, Charsets.UTF_8)
            // 主 stdout reader
            reader = BufferedReader(InputStreamReader(process!!.inputStream, Charsets.UTF_8))
            // stderr 单独读取（丢弃）
            stderrReader = BufferedReader(InputStreamReader(process!!.errorStream, Charsets.UTF_8))
            bootstrapEnvironment()
            // 启动一个线程丢弃 stderr，防止缓冲区堵塞
            Thread {
                try {
                    var errLine: String?
                    while (stderrReader?.readLine().also { errLine = it } != null) {
                        // discard
                    }
                } catch (_: IOException) {
                    // process died
                }
            }.apply { isDaemon = true }.start()
        } catch (e: IOException) {
            process = null
            writer = null
            reader = null
        }
    }

    private fun bootstrapEnvironment() {
        val pw = writer ?: return
        try {
            val bootstrap = ToolchainManager.buildShellBootstrapCommands()
            if (bootstrap.isNotBlank()) {
                pw.write("$bootstrap\n")
                pw.flush()
            }
        } catch (_: IOException) {
        }
    }

    fun tryExit() {
        try {
            process?.destroy()
        } catch (_: Exception) {}
        process = null
        writer = null
        reader = null
    }

    fun isAlive(): Boolean = process?.isAlive == true
}
