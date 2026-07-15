package com.murong.agent.common.shell

import com.murong.agent.common.toolchain.ToolchainManager
import java.io.IOException

/**
 * Shell 执行器工厂
 * 移植自 murongdiaodu-apk/common/.../shell/ShellExecutor.java
 *
 * 提供 su / sh 进程的低级创建。
 */
object ShellExecutor {

    /**
     * 获取 Root 进程
     */
    fun getSuperUserRuntime(): Process? {
        val command = RootShellStrategy.buildRootCommand()
        return try {
            ProcessBuilder(command).start()
        } catch (e: IOException) {
            try {
                ProcessBuilder(ToolchainManager.resolveSystemCommandPath("su")).start()
            } catch (e2: IOException) {
                null
            }
        }
    }

    /**
     * 获取普通 Shell 进程
     */
    fun getRuntime(): Process? {
        return try {
            Runtime.getRuntime().exec("sh")
        } catch (e: IOException) {
            null
        }
    }
}
