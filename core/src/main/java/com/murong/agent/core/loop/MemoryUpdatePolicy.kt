package com.murong.agent.core.loop

import com.murong.agent.core.memory.MemoryScope
import com.murong.agent.core.tool.RememberMemorySuggestion

private const val MAX_MEMORY_UPDATE_SUGGESTION_HISTORY = 12

private val MEMORY_WRITE_TOOL_NAMES = setOf(
    "remember_memory",
    "forget_memory",
    "migrate_legacy_memories",
    "create_global_memory",
    "create_project_memory"
)

private val MEMORY_SUGGESTION_SIGNAL_TOOL_NAMES = setOf(
    "shell",
    "file",
    "code_edit",
    "complete_step",
    "task_repo_apply_patch",
    "task_repo_update_file",
    "task_repo_search_replace",
    "task_repo_commit_files"
)

internal fun buildMemoryUpdateSuggestionContext(
    suggestions: List<MemoryUpdateSuggestionUi>
): String? {
    val pending = suggestions
        .asSequence()
        .filter { it.status == MemoryUpdateSuggestionStatusUi.PENDING }
        .sortedByDescending { it.createdAt }
        .take(3)
        .toList()
    if (pending.isEmpty()) return null
    return buildString {
        appendLine("Recent memory update suggestions:")
        pending.forEach { suggestion ->
            appendLine(
                "- [${MemoryScope.fromRaw(suggestion.scope).wireName()}] ${suggestion.title} " +
                    "(suggestion_id=${suggestion.id})"
            )
        }
        append("If the user wants to persist one of these suggestions, use `remember_memory` with `suggestion_id`.")
    }.trim()
}

internal fun buildMidSessionMemoryUpdateSuggestion(
    stateBeforeSend: SessionState,
    completedState: SessionState,
    userMessage: ChatMessageUi,
    assistantMessageId: Long,
    executionGoal: String,
    currentProjectScopePath: String?
): MemoryUpdateSuggestionUi? {
    val newToolCalls = completedState.recentToolCalls
        .take((completedState.recentToolCalls.size - stateBeforeSend.recentToolCalls.size).coerceAtLeast(0))
    if (newToolCalls.any { it.isSuccess && it.toolName in MEMORY_WRITE_TOOL_NAMES }) {
        return null
    }
    val hadMeaningfulExecution = completedState.fileChanges.size > stateBeforeSend.fileChanges.size ||
        newToolCalls.any { record ->
            record.isSuccess && record.toolName in MEMORY_SUGGESTION_SIGNAL_TOOL_NAMES
        } ||
        completedState.recentFinalReadinessAudits.size > stateBeforeSend.recentFinalReadinessAudits.size
    if (!hadMeaningfulExecution) return null
    val assistantMessage = completedState.messages.firstOrNull { message ->
        message.id == assistantMessageId && message.role == "assistant"
    } ?: return null
    val assistantSummary = assistantMessage.content.toMemorySuggestionSummary() ?: return null
    val scope = if (currentProjectScopePath != null) {
        MemoryScope.PROJECT
    } else {
        MemoryScope.GLOBAL
    }
    val toolNames = newToolCalls
        .filter { it.isSuccess }
        .map { it.toolName }
        .distinct()
        .take(4)
    val title = buildMemorySuggestionTitle(
        executionGoal = executionGoal,
        fallback = userMessage.content
    ) ?: return null
    val content = buildMemorySuggestionContent(
        executionGoal = executionGoal,
        assistantSummary = assistantSummary,
        toolNames = toolNames
    )
    if (completedState.recentMemoryUpdateSuggestions.any { suggestion ->
            suggestion.status == MemoryUpdateSuggestionStatusUi.PENDING &&
                suggestion.scope == scope.wireName() &&
                suggestion.title.equals(title, ignoreCase = true) &&
                suggestion.content.equals(content, ignoreCase = true)
        }
    ) {
        return null
    }
    return MemoryUpdateSuggestionUi(
        title = title,
        content = content,
        scope = scope.wireName(),
        reason = "本轮出现了新的执行结论，可在用户确认后沉淀为 durable memory。",
        sourceKind = "turn_completion",
        sourceUserMessageId = userMessage.id,
        sourceAssistantMessageId = assistantMessageId
    )
}

internal fun applyMemoryUpdateSuggestionTransition(
    state: SessionState,
    suggestion: MemoryUpdateSuggestionUi
): SessionState {
    return state.copy(
        recentMemoryUpdateSuggestions = (
            listOf(suggestion) + state.recentMemoryUpdateSuggestions.filterNot { existing ->
                existing.id == suggestion.id
            }
            ).take(MAX_MEMORY_UPDATE_SUGGESTION_HISTORY)
    )
}

internal fun findRememberMemorySuggestion(
    state: SessionState,
    suggestionId: String
): RememberMemorySuggestion? {
    val suggestion = state.recentMemoryUpdateSuggestions.firstOrNull { candidate ->
        candidate.id == suggestionId && candidate.status == MemoryUpdateSuggestionStatusUi.PENDING
    } ?: return null
    return RememberMemorySuggestion(
        id = suggestion.id,
        title = suggestion.title,
        content = suggestion.content,
        scope = MemoryScope.fromRaw(suggestion.scope)
    )
}

internal fun applyMemoryUpdateSuggestionAppliedTransition(
    state: SessionState,
    suggestionId: String,
    memoryId: String
): SessionState {
    return state.copy(
        recentMemoryUpdateSuggestions = state.recentMemoryUpdateSuggestions.map { suggestion ->
            if (suggestion.id != suggestionId) {
                suggestion
            } else {
                suggestion.copy(
                    status = MemoryUpdateSuggestionStatusUi.APPLIED,
                    linkedMemoryId = memoryId
                )
            }
        }
    )
}

private fun String.toMemorySuggestionSummary(): String? {
    val lines = lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .map { line -> line.removePrefix("- ").removePrefix("* ").trim() }
        .take(4)
        .toList()
    if (lines.isEmpty()) return null
    val joined = lines.joinToString(" ")
        .replace(Regex("\\s+"), " ")
        .trim()
    if (joined.length < 24) return null
    return if (joined.length <= 220) joined else joined.take(217).trimEnd() + "..."
}

private fun buildMemorySuggestionTitle(
    executionGoal: String,
    fallback: String
): String? {
    val seed = executionGoal.trim().ifBlank { fallback.trim() }
    if (seed.isBlank()) return null
    val normalized = seed
        .replace(Regex("\\s+"), " ")
        .trim()
        .trim('。', '，', '.', ',', ':', '：', ';', '；')
    return normalized.takeIf { it.isNotBlank() }
        ?.let { text ->
            if (text.length <= 40) text else text.take(40).trimEnd() + "..."
        }
}

private fun buildMemorySuggestionContent(
    executionGoal: String,
    assistantSummary: String,
    toolNames: List<String>
): String {
    return buildString {
        appendLine("任务: ${executionGoal.trim().ifBlank { "本轮执行" }}")
        appendLine("结论: $assistantSummary")
        if (toolNames.isNotEmpty()) {
            append("相关工具: ${toolNames.joinToString(", ")}")
        }
    }.trim()
}
