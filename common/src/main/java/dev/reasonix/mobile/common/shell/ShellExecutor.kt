package dev.reasonix.mobile.common.shell

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
        return try {
            Runtime.getRuntime().exec("su")
        } catch (e: IOException) {
            try {
                Runtime.getRuntime().exec("su", null, null)
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
