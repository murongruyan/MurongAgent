package com.murong.agent.shizuku

import android.os.Process
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Runs only inside Shizuku/Sui's user-service process, never in Murong's app UID. */
class ShizukuCommandUserService : IShizukuCommandService.Stub() {
    override fun execute(command: String, timeoutSeconds: Int): String {
        val process = ProcessBuilder("/system/bin/sh", "-c", command)
            .redirectErrorStream(true)
            .start()
        val reader = Executors.newSingleThreadExecutor()
        return try {
            val output = reader.submit<String> { readLimited(process.inputStream) }
            val completed = process.waitFor(timeoutSeconds.coerceIn(1, 120).toLong(), TimeUnit.SECONDS)
            if (!completed) {
                process.destroy()
                if (!process.waitFor(1, TimeUnit.SECONDS)) process.destroyForcibly()
            }
            val raw = runCatching { output.get(3, TimeUnit.SECONDS) }
                .getOrElse { "Command execution error: ${it.message}" }
                .trimEnd()
            if ("__RSNX_EXIT_CODE__" in raw) raw else {
                val exitCode = if (completed) process.exitValue() else -1
                "$raw\n__RSNX_EXIT_CODE__$exitCode"
            }
        } catch (error: Throwable) {
            "Command execution error: ${error.message.orEmpty()}\n__RSNX_EXIT_CODE__-1"
        } finally {
            reader.shutdownNow()
            process.destroy()
        }
    }

    override fun remoteUid(): Int = Process.myUid()

    /** Transaction reserved by Shizuku for tearing down a user service. */
    override fun destroy() {
        System.exit(0)
    }

    private fun readLimited(input: InputStream): String {
        input.bufferedReader(Charsets.UTF_8).use { reader ->
            val result = StringBuilder()
            val buffer = CharArray(4_096)
            while (result.length < MAX_OUTPUT_CHARS) {
                val count = reader.read(buffer, 0, minOf(buffer.size, MAX_OUTPUT_CHARS - result.length))
                if (count <= 0) break
                result.append(buffer, 0, count)
            }
            if (reader.read() >= 0) result.append("\n...(Shizuku 输出已截断)")
            return result.toString()
        }
    }

    private companion object {
        const val MAX_OUTPUT_CHARS = 256 * 1024
    }
}
