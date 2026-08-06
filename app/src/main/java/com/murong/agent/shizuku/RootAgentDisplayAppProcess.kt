package com.murong.agent.shizuku

import android.content.Context
import android.util.Log
import com.murong.agent.common.shell.KeepShellPublic
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Starts Murong's privileged display service directly through Root, without Shizuku. */
internal class RootAgentDisplayAppProcess(
    private val binderTimeoutSeconds: Long,
) : AutoCloseable {
    private var token: String? = null
    private var process: Process? = null

    fun start(context: Context): IShizukuCommandService? {
        val launchToken = UUID.randomUUID().toString()
        token = launchToken
        RootAgentDisplayBrokerRegistry.prepare(launchToken)
        val appProcessCommand = listOf(
            "/system/bin/app_process",
            "-Djava.class.path=${context.packageCodePath}",
            "/system/bin",
            RootAgentDisplayMain::class.java.name,
            "--package=${context.packageName}",
            "--token=$launchToken",
        ).joinToString(" ") { it.shellQuoted() }

        return try {
            process = ProcessBuilder("su", "-c", appProcessCommand)
                .redirectErrorStream(true)
                .start()
                .also(::drainProcessOutput)
            RootAgentDisplayBrokerRegistry.await(
                launchToken,
                binderTimeoutSeconds,
                TimeUnit.SECONDS,
            ) ?: run {
                close()
                null
            }
        } catch (error: Throwable) {
            Log.w(TAG, "Unable to launch Root Agent Display", error)
            close()
            null
        }
    }

    override fun close() {
        val launchToken = token
        token = null
        if (launchToken != null) RootAgentDisplayBrokerRegistry.cancel(launchToken)
        runCatching { process?.destroy() }
        process = null
        if (launchToken != null) {
            // The UUID is unique to this launch, so this cannot kill another app's Root process.
            runCatching {
                KeepShellPublic.doCmdSync("pkill -f '${launchToken}' 2>/dev/null || true")
            }
        }
    }

    private fun drainProcessOutput(rootProcess: Process) {
        thread(name = "MurongRootProcessOutput", isDaemon = true) {
            runCatching {
                rootProcess.inputStream.bufferedReader().useLines { lines ->
                    lines.take(MAX_LOG_LINES).forEach { line -> Log.d(TAG, line) }
                }
            }
        }
    }

    private companion object {
        const val TAG = "MurongRootDisplay"
        const val MAX_LOG_LINES = 120
    }
}

internal fun exemptHiddenApis() {
    val bypass = Class.forName("org.lsposed.hiddenapibypass.HiddenApiBypass")
    bypass.getDeclaredMethod("addHiddenApiExemptions", Array<String>::class.java)
        .invoke(null, arrayOf(""))
}

private fun String.shellQuoted(): String = "'" + replace("'", "'\\''") + "'"
