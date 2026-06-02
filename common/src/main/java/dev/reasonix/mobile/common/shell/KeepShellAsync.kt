package dev.reasonix.mobile.common.shell

import android.os.Handler
import android.os.Looper
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter

/**
 * 异步 Root Shell 会话（Fire-and-Forget）
 * 移植自 murongdiaodu-apk/common/.../shell/KeepShellAsync.kt
 *
 * 用于长时间运行的命令或不需要即时返回的场景。
 */
class KeepShellAsync {

    private var process: Process? = null
    private var writer: OutputStreamWriter? = null
    private var reader: BufferedReader? = null
    private var stderrReader: BufferedReader? = null
    private var isRunning = false
    private val cmdsCache = StringBuilder()
    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        const val PROCESS_EVENT_CONTENT = 0
        const val PROCESS_EVENT_ERROR_CONTENT = 1
        const val TIMEOUT_MS = 20000L
    }

    /**
     * 初始化 Root 进程
     */
    private fun init() {
        if (process != null && process!!.isAlive) return
        try {
            val pb = ProcessBuilder("su")
            pb.redirectErrorStream(false)
            process = pb.start()
            writer = OutputStreamWriter(process!!.outputStream)
            reader = BufferedReader(InputStreamReader(process!!.inputStream))
            stderrReader = BufferedReader(InputStreamReader(process!!.errorStream))
            isRunning = true

            // 丢弃 stderr 的线程
            Thread {
                try {
                    var line: String?
                    while (stderrReader?.readLine().also { line = it } != null) {
                        // discard stderr
                    }
                } catch (_: IOException) {}
            }.apply { isDaemon = true }.start()

            // 写入缓存的命令
            if (cmdsCache.isNotEmpty()) {
                writer?.write(cmdsCache.toString())
                writer?.flush()
                cmdsCache.clear()
            }
        } catch (e: IOException) {
            process = null
            writer = null
            reader = null
            isRunning = false
        }
    }

    /**
     * 执行命令（不等待输出）
     */
    fun doCmd(command: String) {
        if (writer != null && process?.isAlive == true) {
            try {
                writer!!.write("$command\n")
                writer!!.flush()
            } catch (e: IOException) {
                // 重试一次
                try {
                    init()
                    writer?.write("$command\n")
                    writer?.flush()
                } catch (_: Exception) {}
            }
        } else {
            cmdsCache.append("$command\n")
            init()
        }
    }

    fun tryExit() {
        try {
            process?.destroy()
        } catch (_: Exception) {}
        process = null
        writer = null
        reader = null
        isRunning = false
    }
}
