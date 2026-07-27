package com.murong.agent.core.tool

import com.murong.agent.common.shell.KeepShellPublic

/** The user-selected privilege lane for Android system operations. */
enum class AndroidExecutionMode(val label: String) {
    AUTO("自动选择"),
    STANDARD("标准应用"),
    ACCESSIBILITY("无障碍"),
    SHIZUKU("Shizuku"),
    ROOT("Root")
}

/**
 * App-owned integrations (currently Shizuku/Sui) are injected here so core tools do not depend on
 * a particular optional SDK. A missing bridge is a normal capability downgrade, never a crash.
 */
interface ExternalSystemCommandBridge {
    val label: String
    fun isAvailable(): Boolean
    fun execute(command: String, timeoutSeconds: Int): String
}

enum class AndroidSystemExecutionRoute(val label: String) {
    NONE("标准应用 / 无障碍"),
    SHIZUKU("Shizuku"),
    ROOT("Root")
}

object AndroidSystemExecution {
    @Volatile
    private var preferredMode: AndroidExecutionMode = AndroidExecutionMode.AUTO

    @Volatile
    private var externalBridge: ExternalSystemCommandBridge? = null

    fun setPreferredMode(mode: AndroidExecutionMode) {
        preferredMode = mode
    }

    fun preferredMode(): AndroidExecutionMode = preferredMode

    fun installExternalBridge(bridge: ExternalSystemCommandBridge?) {
        externalBridge = bridge
    }

    fun resolvedRoute(): AndroidSystemExecutionRoute = when (preferredMode) {
        AndroidExecutionMode.ROOT -> if (KeepShellPublic.checkRoot()) {
            AndroidSystemExecutionRoute.ROOT
        } else {
            AndroidSystemExecutionRoute.NONE
        }
        AndroidExecutionMode.SHIZUKU -> if (externalBridge?.isAvailable() == true) {
            AndroidSystemExecutionRoute.SHIZUKU
        } else {
            AndroidSystemExecutionRoute.NONE
        }
        AndroidExecutionMode.STANDARD,
        AndroidExecutionMode.ACCESSIBILITY -> AndroidSystemExecutionRoute.NONE
        AndroidExecutionMode.AUTO -> when {
            KeepShellPublic.checkRoot() -> AndroidSystemExecutionRoute.ROOT
            externalBridge?.isAvailable() == true -> AndroidSystemExecutionRoute.SHIZUKU
            else -> AndroidSystemExecutionRoute.NONE
        }
    }

    fun isSystemCommandAvailable(): Boolean = resolvedRoute() != AndroidSystemExecutionRoute.NONE

    fun unavailableReason(): String = when (preferredMode) {
        AndroidExecutionMode.STANDARD -> "当前为标准应用模式，不提供系统 Shell；可使用终端扩展、文件和普通应用能力。"
        AndroidExecutionMode.ACCESSIBILITY -> "当前为无障碍模式，界面自动化可用，但系统 Shell 需要切换到 Shizuku 或 Root。"
        AndroidExecutionMode.SHIZUKU -> "Shizuku 未运行或尚未向 Murong 授权。"
        AndroidExecutionMode.ROOT -> "Root 不可用或未授权。"
        AndroidExecutionMode.AUTO -> "未检测到可用的 Root 或已授权 Shizuku；可继续使用标准应用和无障碍能力。"
    }

    fun executeSystemCommand(command: String, timeoutSeconds: Int): String = when (resolvedRoute()) {
        AndroidSystemExecutionRoute.ROOT -> KeepShellPublic.doCmdSync(command)
        AndroidSystemExecutionRoute.SHIZUKU -> externalBridge?.execute(command, timeoutSeconds)
            ?: "Error: Shizuku bridge is unavailable."
        AndroidSystemExecutionRoute.NONE -> "Error: ${unavailableReason()}"
    }
}
