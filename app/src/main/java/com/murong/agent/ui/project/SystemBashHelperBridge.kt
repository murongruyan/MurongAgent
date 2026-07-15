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
private const val SYSTEM_BASH_HELPER_ACCEPT_TIMEOUT_SECONDS = 2L
private const val SYSTEM_BASH_HELPER_LOG_TAG = "MurongSystemBashHelper"

internal data class SystemBashHelperSession(
    val terminalFd: Int,
    val shellPid: Int,
    val processMonitor: TerminalSessionProcessMonitor
)

internal object SystemBashHelperBridge {

    fun open(
        context: Context,
        cwd: String,
        path: String,
        libraryPath: String,
        home: String,
        rcFilePath: String,
        tmpDir: String,
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
                cwd = cwd,
                path = path,
                libraryPath = libraryPath,
                home = home,
                rcFilePath = rcFilePath,
                tmpDir = tmpDir,
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
        cwd: String,
        path: String,
        libraryPath: String,
        home: String,
        rcFilePath: String,
        tmpDir: String,
        rows: Int,
        columns: Int,
        cellWidth: Int,
        cellHeight: Int
    ) {
        val termuxLibraryPath = "${context.applicationInfo.nativeLibraryDir}/libtermux.so"
        val helperArgs = listOf(
            socketName,
            encodeArg(cwd),
            encodeArg(path),
            encodeArg(libraryPath),
            encodeArg(home),
            encodeArg(rcFilePath),
            encodeArg(tmpDir),
            rows.toString(),
            columns.toString(),
            cellWidth.toString(),
            cellHeight.toString()
        )
        val helperCommand = buildString {
            append("CLASSPATH=${context.applicationInfo.sourceDir} ")
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
        val supplementaryGroups = currentProcessSupplementaryGroups()
        val groupArgs = supplementaryGroups.joinToString(separator = "") { " -G $it" }
        val command = buildString {
            append("nsenter -t ")
            append(appPid)
            append(" -m -- ")
            append(suPath)
            append(" -g ")
            append(context.applicationInfo.uid)
            append(groupArgs)
            append(' ')
            append(context.applicationInfo.uid)
            append(" -c ")
            append(shellQuote(helperCommand))
            append(" >/dev/null 2>&1 &")
        }
        ProcessBuilder(RootShellStrategy.buildRootCommand() + listOf("-c", command))
            .redirectErrorStream(true)
            .start()
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
