package com.murong.agent.ui.chat

import com.murong.agent.core.loop.ChatMessageUi

/**
 * 一次助手处理过程中可折叠的过程消息摘要。
 *
 * 摘要仅供展示使用，原始消息始终保留在展开内容中。
 */
internal data class ChatProcessSummaryUi(
    val reasoningCount: Int,
    val readFileCount: Int,
    val commandCount: Int,
    val changedFileCount: Int,
    val toolCount: Int,
    val subagentCount: Int,
    val systemCount: Int,
    val hasFailure: Boolean
) {
    fun labels(): List<String> = buildList {
        if (reasoningCount > 0) add("思考过程")
        if (readFileCount > 0) add("已读取 $readFileCount 个文件")
        if (commandCount > 0) add("已运行 $commandCount 个命令")
        if (changedFileCount > 0) add("已修改 $changedFileCount 个文件")
        if (subagentCount > 0) add("子代理 $subagentCount")
        if (hasFailure) add("存在失败")
        if (isEmpty() && toolCount > 0) add("已执行 $toolCount 个工具")
        if (isEmpty() && systemCount > 0) add("系统状态 $systemCount")
    }
}

internal fun isChatProcessMessage(message: ChatMessageUi): Boolean {
    return message.role == "tool_exec" ||
        message.role == "subagent" ||
        message.role == "system" ||
        (message.role == "assistant" && message.content.isBlank() && !message.reasoning.isNullOrBlank())
}

internal fun buildChatProcessSummary(messages: List<ChatMessageUi>): ChatProcessSummaryUi {
    val toolCalls = messages.filter { it.role == "tool_exec" }
    val toolNames = toolCalls.mapNotNull(::toolNameFromProcessMessage)
    val readPaths = linkedSetOf<String>()
    var readCallsWithoutPath = 0
    var commandCount = 0
    var changedCallsWithoutFiles = 0
    val changedFiles = linkedSetOf<String>()

    toolCalls.forEach { message ->
        val toolName = toolNameFromProcessMessage(message).orEmpty().lowercase()
        if (toolName.isReadTool()) {
            val paths = extractProcessPaths(message.content)
            if (paths.isEmpty()) {
                readCallsWithoutPath += 1
            } else {
                readPaths += paths
            }
        }
        if (toolName.isCommandTool()) {
            commandCount += 1
        }
        val fileChanges = extractProcessFileChanges(message.content)
        changedFiles += fileChanges
        if (fileChanges.isEmpty() && toolName.isWriteTool()) {
            changedCallsWithoutFiles += 1
        }
    }

    return ChatProcessSummaryUi(
        reasoningCount = messages.count { !it.reasoning.isNullOrBlank() },
        readFileCount = readPaths.size + readCallsWithoutPath,
        commandCount = commandCount,
        changedFileCount = changedFiles.size + changedCallsWithoutFiles,
        toolCount = toolNames.distinct().size,
        subagentCount = messages.count { it.role == "subagent" },
        systemCount = messages.count { it.role == "system" },
        hasFailure = messages.any { message ->
            message.content.contains("执行失败") ||
                message.content.contains("失败") ||
                message.content.contains("error", ignoreCase = true)
        }
    )
}

private fun toolNameFromProcessMessage(message: ChatMessageUi): String? {
    val execution = Regex("""^🔧 正在执行: \*\*(.+?)\*\*""").find(message.content)
    if (execution != null) return execution.groupValues.getOrNull(1)?.trim()
    return Regex("""^📦 \*\*(.+?)\*\* 执行结果:""")
        .find(message.content)
        ?.groupValues
        ?.getOrNull(1)
        ?.trim()
}

private fun extractProcessPaths(content: String): Set<String> {
    return Regex(""""(?:file_path|path|uri)"\s*:\s*"([^"]+)"""")
        .findAll(content)
        .map { it.groupValues[1].trim() }
        .filter { it.isNotBlank() }
        .toSet()
}

private fun extractProcessFileChanges(content: String): Set<String> {
    val changes = content.substringAfter("本次文件变更:\n", missingDelimiterValue = "")
    if (changes.isBlank()) return emptySet()
    return changes.lineSequence()
        .map { it.trim() }
        .takeWhile { it.startsWith("- ") }
        .map { it.removePrefix("- ").trim() }
        .filter { it.isNotBlank() }
        .toSet()
}

private fun String.isReadTool(): Boolean =
    contains("read") || contains("open") || contains("cat")

private fun String.isCommandTool(): Boolean =
    contains("bash") || contains("command") || contains("shell") || contains("terminal") || contains("exec")

private fun String.isWriteTool(): Boolean =
    contains("write") || contains("edit") || contains("patch") || contains("delete") || contains("move")
