package com.murong.agent.core.provider

import kotlinx.serialization.json.Json

internal const val INTERRUPTED_TOOL_RESULT =
    "[no result: the previous turn was interrupted before this tool call completed]"

private val normalizerJson = Json

/**
 * Produces a provider-wire-safe copy of a conversation without mutating the
 * persisted session history. OpenAI-compatible providers require every
 * assistant tool call to have one immediately following tool result, while
 * orphan tool messages are rejected by several endpoints.
 */
internal fun normalizeMessagesForProvider(messages: List<ChatMessage>): List<ChatMessage> {
    val normalized = mutableListOf<ChatMessage>()
    var index = 0

    while (index < messages.size) {
        val message = messages[index]
        val toolCalls = message.toolCalls.orEmpty()
        if (message.role != "assistant" || toolCalls.isEmpty()) {
            if (message.role != "tool") {
                normalized += message
            }
            index++
            continue
        }

        var resultEnd = index + 1
        while (resultEnd < messages.size && messages[resultEnd].role == "tool") {
            resultEnd++
        }
        val availableResults = messages.subList(index + 1, resultEnd)
        val repairedCalls = repairToolCalls(toolCalls, availableResults)
        normalized += message.copy(toolCalls = repairedCalls)
        normalized += pairToolResults(repairedCalls, availableResults)
        index = resultEnd
    }

    return normalized
}

internal fun normalizeChatRequestForProvider(request: ChatRequest): ChatRequest {
    return request.copy(messages = normalizeMessagesForProvider(request.messages))
}

private fun repairToolCalls(
    toolCalls: List<ToolCall>,
    availableResults: List<ChatMessage>
): List<ToolCall> {
    val namesById = availableResults
        .filter { it.toolCallId?.isNotBlank() == true && !it.name.isNullOrBlank() }
        .associate { it.toolCallId!! to it.name!! }

    return toolCalls.mapIndexed { index, call ->
        val fallbackName = namesById[call.id]
            ?: availableResults.getOrNull(index)?.name
        call.copy(
            function = call.function.copy(
                name = call.function.name.ifBlank { fallbackName.orEmpty() },
                arguments = closeTruncatedJsonArguments(call.function.arguments)
            )
        )
    }
}

private fun pairToolResults(
    toolCalls: List<ToolCall>,
    availableResults: List<ChatMessage>
): List<ChatMessage> {
    val resultsById = availableResults
        .filter { !it.toolCallId.isNullOrBlank() }
        .associateBy { it.toolCallId!! }

    return toolCalls.mapIndexed { index, call ->
        val matched = resultsById[call.id]
            ?: if (hasDistinctIds(toolCalls)) null else availableResults.getOrNull(index)
        if (matched == null) {
            ChatMessage(
                role = "tool",
                toolCallId = call.id,
                name = call.function.name,
                content = INTERRUPTED_TOOL_RESULT
            )
        } else {
            matched.copy(
                toolCallId = call.id,
                name = call.function.name
            )
        }
    }
}

private fun hasDistinctIds(toolCalls: List<ToolCall>): Boolean {
    val ids = toolCalls.map { it.id }
    return ids.none { it.isBlank() } && ids.distinct().size == ids.size
}

/** Best-effort repair for tool arguments cut off during a streamed response. */
internal fun closeTruncatedJsonArguments(arguments: String): String {
    if (arguments.isBlank() || isValidJson(arguments)) return arguments

    val closers = mutableListOf<Char>()
    var inString = false
    var escaped = false
    for (character in arguments) {
        if (inString) {
            when {
                escaped -> escaped = false
                character == '\\' -> escaped = true
                character == '"' -> inString = false
            }
            continue
        }
        when (character) {
            '"' -> inString = true
            '{' -> closers += '}'
            '[' -> closers += ']'
            '}', ']' -> if (closers.isNotEmpty()) closers.removeAt(closers.lastIndex)
        }
    }

    var repaired = arguments
    if (escaped && repaired.isNotEmpty()) repaired = repaired.dropLast(1)
    if (inString) repaired += '"'
    val trimmed = repaired.trimEnd()
    repaired = when {
        trimmed.endsWith(',') -> trimmed.dropLast(1)
        trimmed.endsWith(':') -> "${trimmed}null"
        else -> trimmed
    }
    closers.asReversed().forEach { repaired += it }

    return repaired.takeIf(::isValidJson) ?: "{}"
}

private fun isValidJson(value: String): Boolean = runCatching {
    normalizerJson.parseToJsonElement(value)
}.isSuccess
