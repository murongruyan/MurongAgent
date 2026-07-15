package com.murong.agent.common.shell

import com.murong.agent.common.toolchain.ToolchainManager
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 统一选择最合适的系统 su 启动参数，优先争取全局 mount namespace。
 */
object RootShellStrategy {

    @Volatile
    private var cachedCommand: List<String>? = null

    fun buildRootCommand(): List<String> {
        cachedCommand?.let { return it }
        val resolved = resolveRootCommand()
        cachedCommand = resolved
        return resolved
    }

    private fun resolveRootCommand(): List<String> {
        val suPath = ToolchainManager.resolveSystemCommandPath("su")
        val helpText = readSuHelp(suPath)
        val candidates = buildList {
            if (helpText.contains("--mount-master")) {
                add(listOf(suPath, "--mount-master"))
            }
            if (helpText.contains("-mm")) {
                add(listOf(suPath, "-mm"))
            }
            if (helpText.contains("-M")) {
                add(listOf(suPath, "-M"))
            }
            if (helpText.contains("--target")) {
                add(listOf(suPath, "--target", "1"))
            }
            if (helpText.contains(" -t") || helpText.contains("\n-t")) {
                add(listOf(suPath, "-t", "1"))
            }
            add(listOf(suPath))
        }
        return candidates.firstOrNull(::isUsableCommand) ?: listOf(suPath)
    }

    private fun readSuHelp(suPath: String): String {
        return runCatching {
            ProcessBuilder(suPath, "--help")
                .redirectErrorStream(true)
                .start()
                .let { process ->
                    process.inputStream.bufferedReader().use { reader ->
                        val text = reader.readText()
                        process.waitFor(2, TimeUnit.SECONDS)
                        text
                    }
                }
        }.getOrDefault("")
    }

    private fun isUsableCommand(command: List<String>): Boolean {
        return runCatching {
            val process = ProcessBuilder(command + listOf("-c", "id -u"))
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor(5, TimeUnit.SECONDS)
            process.exitValue() == 0 && output.lineSequence().any { it.trim() == "0" }
        }.getOrDefault(false)
    }
}
