package com.murong.agent.ui.project

import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.util.Base64
import android.util.Log
import com.murong.agent.common.shell.RootShellStrategy
import com.murong.agent.common.toolchain.ToolchainManager
import com.termux.terminal.TerminalSessionProcessMonitor
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.FileDescriptor
import java.io.IOException
import java.io.InputStream
import java.lang.reflect.Field
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

private const val SYSTEM_BASH_HELPER_CLASS = "com.termux.terminal.SystemBashPtyHelper"
private const val SYSTEM_BASH_HELPER_ACCEPT_TIMEOUT_SECONDS = 5L
private const val SYSTEM_BASH_HELPER_LOG_TAG = "MurongSystemBashHelper"

internal data class SystemBashHelperSession(
    val terminalFd: Int,
    val shellPid: Int,
    val processMonitor: TerminalSessionProcessMonitor
)

internal object SystemBashHelperBridge {

    fun open(
        context: Context,
        executable: String,
        cwd: String,
        arguments: List<String>,
        environment: List<String>,
        runAsRoot: Boolean = false,
        rows: Int = 24,
        columns: Int = 80,
        cellWidth: Int = 1,
        cellHeight: Int = 1
    ): SystemBashHelperSession? {
        val socketName = "murong-system-bash-${UUID.randomUUID()}"
        val server = LocalServerSocket(socketName)
        val acceptExecutor = Executors.newSingleThreadExecutor()
        return try {
            val acceptFuture = acceptExecutor.submit<LocalSocket> { server.accept() }
            launchHelper(
                context = context,
                socketName = socketName,
                executable = executable,
                cwd = cwd,
                arguments = arguments,
                environment = environment,
                runAsRoot = runAsRoot,
                rows = rows,
                columns = columns,
                cellWidth = cellWidth,
                cellHeight = cellHeight
            )
            val socket = acceptFuture.get(SYSTEM_BASH_HELPER_ACCEPT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            val input = socket.inputStream
            val pidLine = readProtocolLine(input) ?: return null
            val shellPid = pidLine.removePrefix("PID:").toIntOrNull() ?: return null
            val ancillary = socket.ancillaryFileDescriptors
            val terminalFd = unwrapFileDescriptor(ancillary?.firstOrNull() ?: return null)
            SystemBashHelperSession(
                terminalFd = terminalFd,
                shellPid = shellPid,
                processMonitor = LocalSocketProcessMonitor(socket, input)
            )
        } catch (timeout: TimeoutException) {
            Log.w(SYSTEM_BASH_HELPER_LOG_TAG, "Timed out waiting for helper connection", timeout)
            null
        } catch (error: Throwable) {
            Log.w(SYSTEM_BASH_HELPER_LOG_TAG, "Failed to open helper session", error)
            null
        } finally {
            runCatching { server.close() }
            acceptExecutor.shutdownNow()
        }
    }

    private fun launchHelper(
        context: Context,
        socketName: String,
        executable: String,
        cwd: String,
        arguments: List<String>,
        environment: List<String>,
        runAsRoot: Boolean,
        rows: Int,
        columns: Int,
        cellWidth: Int,
        cellHeight: Int
    ) {
        val termuxLibraryPath = "${context.applicationInfo.nativeLibraryDir}/libtermux.so"
        val helperArgs = listOf(
            socketName,
            encodeArg(cwd),
            encodeArg(executable),
            encodeList(arguments),
            encodeList(environment),
            rows.toString(),
            columns.toString(),
            cellWidth.toString(),
            cellHeight.toString()
        )
        val helperCommand = buildString {
            // nsenter(1) executes the first argument after `--` directly. An inline
            // `CLASSPATH=...` prefix would therefore be treated as an executable name
            // instead of a shell assignment, so use env(1) explicitly.
            append("/system/bin/env CLASSPATH=")
            append(shellQuote(context.applicationInfo.sourceDir))
            append(' ')
            append("/system/bin/app_process ")
            append("-Dmurong.termux.libpath=${shellQuote(termuxLibraryPath)} ")
            append("/ ")
            append(SYSTEM_BASH_HELPER_CLASS)
            helperArgs.forEach { arg ->
                append(' ')
                append(arg)
            }
        }
        val suPath = ToolchainManager.resolveSystemCommandPath("su")
        val appPid = android.os.Process.myPid()
        val command = buildString {
            append("nsenter -t ")
            append(appPid)
            append(" -m -- ")
            if (runAsRoot) {
                // Do not pass the helper through termux-exec or a nested `su`: this process is
                // already root and is deliberately launched in the app mount namespace so it can
                // hand its PTY back to the app's local socket.
                append(helperCommand)
            } else {
                val supplementaryGroups = currentProcessSupplementaryGroups()
                val groupArgs = supplementaryGroups.joinToString(separator = "") { " -G $it" }
                append(suPath)
                append(" -g ")
                append(context.applicationInfo.uid)
                append(groupArgs)
                append(' ')
                append(context.applicationInfo.uid)
                append(" -c ")
                append(shellQuote(helperCommand))
            }
        }
        val launcher = ProcessBuilder(RootShellStrategy.buildRootCommand() + listOf("-c", command))
            .redirectErrorStream(true)
            .start()
        observeLauncher(launcher)
    }

    private fun observeLauncher(process: Process) {
        Thread(
            {
                val output = runCatching {
                    process.inputStream.bufferedReader().use { it.readText() }.trim()
                }.getOrDefault("")
                val exitCode = runCatching { process.waitFor() }.getOrDefault(-1)
                if (exitCode != 0 || output.isNotBlank()) {
                    Log.w(
                        SYSTEM_BASH_HELPER_LOG_TAG,
                        "Helper launcher exited with code $exitCode" +
                            output.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
                    )
                }
            },
            "murong-system-bash-launcher"
        ).apply {
            isDaemon = true
            start()
        }
    }

    private fun currentProcessSupplementaryGroups(): List<Int> {
        return runCatching {
            File("/proc/self/status").useLines { lines ->
                lines.firstOrNull { it.startsWith("Groups:") }
                    ?.removePrefix("Groups:")
                    ?.trim()
                    ?.split(Regex("\\s+"))
                    ?.mapNotNull { it.toIntOrNull() }
                    .orEmpty()
            }
        }.getOrDefault(emptyList())
    }

    private fun encodeArg(value: String): String {
        return if (value.isBlank()) "-" else Base64.encodeToString(
            value.toByteArray(StandardCharsets.UTF_8),
            Base64.NO_WRAP
        )
    }

    private fun encodeList(values: List<String>): String {
        if (values.isEmpty()) return "-"
        val payload = ByteArrayOutputStream()
        DataOutputStream(payload).use { output ->
            output.writeInt(values.size)
            values.forEach { value ->
                val bytes = value.toByteArray(StandardCharsets.UTF_8)
                output.writeInt(bytes.size)
                output.write(bytes)
            }
        }
        return Base64.encodeToString(payload.toByteArray(), Base64.NO_WRAP)
    }

    private fun shellQuote(value: String): String {
        return "'" + value.replace("'", "'\"'\"'") + "'"
    }

    private fun unwrapFileDescriptor(descriptor: FileDescriptor): Int {
        val field = descriptorField()
        return field.get(descriptor) as Int
    }

    private fun descriptorField(): Field {
        return try {
            FileDescriptor::class.java.getDeclaredField("descriptor")
        } catch (_: NoSuchFieldException) {
            FileDescriptor::class.java.getDeclaredField("fd")
        }.apply {
            isAccessible = true
        }
    }

    private fun readProtocolLine(input: InputStream): String? {
        val buffer = StringBuilder()
        while (true) {
            val next = input.read()
            if (next == -1) {
                return buffer.takeIf { it.isNotEmpty() }?.toString()
            }
            if (next == '\n'.code) {
                return buffer.toString()
            }
            if (next != '\r'.code) {
                buffer.append(next.toChar())
            }
        }
    }

    private class LocalSocketProcessMonitor(
        private val socket: LocalSocket,
        private val input: InputStream
    ) : TerminalSessionProcessMonitor {

        override fun waitForProcessExit(): Int {
            val exitCode = try {
                while (true) {
                    val line = readProtocolLine(input) ?: return -1
                    if (line.startsWith("EXIT:")) {
                        return line.removePrefix("EXIT:").toIntOrNull() ?: -1
                    }
                }
                -1
            } catch (error: IOException) {
                Log.w(SYSTEM_BASH_HELPER_LOG_TAG, "Failed waiting for helper exit", error)
                -1
            } finally {
                close()
            }
            return exitCode
        }

        override fun close() {
            runCatching { socket.close() }
        }
    }
}
