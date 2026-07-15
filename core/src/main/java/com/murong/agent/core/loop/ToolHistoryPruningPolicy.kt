package com.murong.agent.core.loop

internal const val STALE_TOOL_RESULT_ELISION_MIN_PAYLOAD_CHARS = 1024

internal data class StaleToolResultElisionPlan(
    val elidedIndices: Set<Int>,
    val savedCharsEstimate: Int
)

internal fun planStaleToolResultElision(
    messages: List<ChatMessageUi>,
    keptIndices: Set<Int>,
    recentMessageWindow: Int
): StaleToolResultElisionPlan {
    if (messages.isEmpty() || keptIndices.isEmpty()) {
        return StaleToolResultElisionPlan(
            elidedIndices = emptySet(),
            savedCharsEstimate = 0
        )
    }
    val recentBoundary = (messages.size - recentMessageWindow).coerceAtLeast(0)
    val lastUserIndex = messages.indexOfLast { it.role == "user" }
    val elidedIndices = mutableSetOf<Int>()
    var savedCharsEstimate = 0
    keptIndices.sorted().forEach { index ->
        val message = messages.getOrNull(index) ?: return@forEach
        if (!isToolResultHistoryMessageForPruning(message)) return@forEach
        if (index >= recentBoundary) return@forEach
        if (lastUserIndex >= 0 && index > lastUserIndex) return@forEach
        if (!shouldElideToolResultInHistory(message)) return@forEach
        elidedIndices += index
        val payload = extractToolResultPayloadForPruning(message.content)
        val placeholder = buildElidedToolResultHistorySummary(message.content).orEmpty()
        savedCharsEstimate += (payload.length - placeholder.length).coerceAtLeast(0)
    }
    return StaleToolResultElisionPlan(
        elidedIndices = elidedIndices,
        savedCharsEstimate = savedCharsEstimate
    )
}

internal fun buildElidedToolResultHistorySummary(message: String): String? {
    val toolName = extractToolNameFromToolHistoryMessage(message) ?: return null
    val payload = extractToolResultPayloadForPruning(message)
    if (payload.length < STALE_TOOL_RESULT_ELISION_MIN_PAYLOAD_CHARS) return null
    return buildString {
        append("较早的大型工具结果已省略(")
        append(toolName)
        append("，约 ")
        append(payload.length)
        append(" 字符)。如需细节，请重新运行更窄的读取/搜索，或再次执行该工具。")
    }
}

private fun shouldElideToolResultInHistory(message: ChatMessageUi): Boolean {
    if (message.content.contains("\n\n本次文件变更:\n")) return false
    val toolName = extractToolNameFromToolHistoryMessage(message.content) ?: return false
    if (toolName == "complete_step") return false
    val payload = extractToolResultPayloadForPruning(message.content)
    if (payload.length < STALE_TOOL_RESULT_ELISION_MIN_PAYLOAD_CHARS) return false
    val firstMeaningfulLine = payload.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.isNotEmpty() }
        ?: return false
    return !firstMeaningfulLine.startsWith("Error", ignoreCase = true) &&
        !firstMeaningfulLine.startsWith("Blocked by", ignoreCase = true) &&
        !firstMeaningfulLine.startsWith("Rejected by user", ignoreCase = true) &&
        !firstMeaningfulLine.contains("Unknown tool", ignoreCase = true)
}

private fun extractToolNameFromToolHistoryMessage(message: String): String? {
    if (message.startsWith(WEB_FETCH_RESULT_PREFIX)) return "web_fetch"
    return Regex("""^📦 \*\*(.+?)\*\* 执行结果:""")
        .find(message)
        ?.groupValues
        ?.getOrNull(1)
        ?.trim()
        ?.takeIf { it.isNotBlank() }
}

private fun extractToolResultPayloadForPruning(message: String): String {
    if (message.startsWith(WEB_FETCH_RESULT_PREFIX)) {
        return message.removePrefix(WEB_FETCH_RESULT_PREFIX).trim()
    }
    return Regex("""```(?:\w+)?\n([\s\S]*?)\n```""")
        .find(message)
        ?.groupValues
        ?.getOrNull(1)
        .orEmpty()
}

private fun isToolResultHistoryMessageForPruning(message: ChatMessageUi): Boolean {
    return message.role == "tool_exec" && !message.content.startsWith("🔧 正在执行:")
}
