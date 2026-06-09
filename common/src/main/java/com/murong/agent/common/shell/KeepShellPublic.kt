package com.murong.agent.common.shell

/**
 * KeepShell 公共访问点（单例）
 * 移植自 murongdiaodu-apk/common/.../shell/KeepShellPublic.kt
 *
 * 维护两个 KeepShell 实例做负载均衡/故障转移。
 */
object KeepShellPublic {

    private val keepShells = arrayOf(
        KeepShell(tag = "RSNX-0"),
        KeepShell(tag = "RSNX-1")
    )

    /**
     * 检测 Root 可用性
     */
    fun checkRoot(): Boolean {
        for (shell in keepShells) {
            if (shell.checkRoot()) return true
        }
        return false
    }

    /**
     * 执行同步命令（自动选择空闲的 shell 实例）
     */
    fun doCmdSync(command: String): String {
        val shell = getDefaultInstance()
        return shell.doCmdSync(command)
    }

    /**
     * 获取当前空闲的 shell 实例
     */
    private fun getDefaultInstance(): KeepShell {
        // 优先选存活的；两个都存活时随机选一个
        val alive = keepShells.filter { it.isAlive() }
        return if (alive.size >= 2) {
            alive[alive.indices.random()]
        } else if (alive.size == 1) {
            alive[0]
        } else {
            // 两个都挂了，用第一个触发重新初始化
            keepShells[0]
        }
    }

    /**
     * 退出所有 shell 会话
     */
    fun tryExit() {
        for (shell in keepShells) {
            shell.tryExit()
        }
    }
}
