package com.murong.agent.core.config

import kotlinx.serialization.Serializable

/** Permission capability required by a saved workflow node. */
@Serializable
enum class ToolPermissionCategory(val label: String) {
    PROJECT_READ("项目读取"),
    EXTERNAL_STORAGE_READ("外部存储读取"),
    NETWORK_READ("网络读取"),
    SHELL("Shell 命令"),
    FILE_WRITE("文件写入"),
    GITHUB_WRITE("GitHub 写入"),
    MCP("MCP 工具"),
    ROOT_SYSTEM("Root / 系统"),
    PACKAGE_MANAGER("软件包管理"),
    MICROPHONE("录音")
}
