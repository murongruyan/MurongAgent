package com.murong.agent.core.loop

import com.murong.agent.core.memory.MemoryScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MemoryUpdatePolicyTest {

    @Test
    fun buildMemoryUpdateSuggestionContext_onlyShowsNewestPendingSuggestions() {
        val context = buildMemoryUpdateSuggestionContext(
            listOf(
                MemoryUpdateSuggestionUi(
                    id = "applied",
                    title = "旧建议",
                    content = "旧内容",
                    scope = "global",
                    reason = "old",
                    sourceKind = "turn_completion",
                    status = MemoryUpdateSuggestionStatusUi.APPLIED,
                    createdAt = 1L
                ),
                MemoryUpdateSuggestionUi(
                    id = "latest",
                    title = "最新建议",
                    content = "最新内容",
                    scope = "project",
                    reason = "new",
                    sourceKind = "turn_completion",
                    createdAt = 30L
                ),
                MemoryUpdateSuggestionUi(
                    id = "older",
                    title = "较早建议",
                    content = "较早内容",
                    scope = "global",
                    reason = "old",
                    sourceKind = "turn_completion",
                    createdAt = 20L
                )
            )
        )

        assertNotNull(context)
        assertTrue(context.contains("Recent memory update suggestions:"))
        assertTrue(context.contains("[project] 最新建议"))
        assertTrue(context.contains("[global] 较早建议"))
        assertTrue(!context.contains("旧建议"))
    }

    @Test
    fun buildMidSessionMemoryUpdateSuggestion_buildsProjectSuggestionAfterMeaningfulExecution() {
        val assistantMessage = ChatMessageUi(
            id = 2L,
            role = "assistant",
            content = "- 已修复 durable memory 的 turn-end policy 抽离，并补齐回归测试。"
        )

        val suggestion = buildMidSessionMemoryUpdateSuggestion(
            stateBeforeSend = SessionState(),
            completedState = SessionState(
                messages = listOf(assistantMessage),
                recentToolCalls = listOf(
                    ToolCallRecordUi(
                        toolName = "code_edit",
                        args = "{}",
                        isSuccess = true
                    )
                )
            ),
            userMessage = ChatMessageUi(
                id = 1L,
                role = "user",
                content = "继续把 memory update policy 抽出来"
            ),
            assistantMessageId = 2L,
            executionGoal = "抽离 memory update policy",
            currentProjectScopePath = "C:/workspace/app"
        )

        assertNotNull(suggestion)
        assertEquals("project", suggestion.scope)
        assertEquals("抽离 memory update policy", suggestion.title)
        assertEquals(1L, suggestion.sourceUserMessageId)
        assertEquals(2L, suggestion.sourceAssistantMessageId)
        assertTrue(suggestion.content.contains("code_edit"))
    }

    @Test
    fun buildMidSessionMemoryUpdateSuggestion_skipsWhenMemoryWasAlreadyWrittenThisTurn() {
        val suggestion = buildMidSessionMemoryUpdateSuggestion(
            stateBeforeSend = SessionState(),
            completedState = SessionState(
                messages = listOf(
                    ChatMessageUi(
                        id = 2L,
                        role = "assistant",
                        content = "- 已经生成一条新的 durable memory。"
                    )
                ),
                recentToolCalls = listOf(
                    ToolCallRecordUi(
                        toolName = "remember_memory",
                        args = "{}",
                        isSuccess = true
                    )
                )
            ),
            userMessage = ChatMessageUi(id = 1L, role = "user", content = "继续"),
            assistantMessageId = 2L,
            executionGoal = "写入 durable memory",
            currentProjectScopePath = null
        )

        assertNull(suggestion)
    }

    @Test
    fun buildMidSessionMemoryUpdateSuggestion_skipsDuplicatePendingSuggestion() {
        val existing = MemoryUpdateSuggestionUi(
            id = "existing",
            title = "抽离 memory update policy",
            content = "任务: 抽离 memory update policy\n结论: 已修复 durable memory 的 turn-end policy 抽离，并补齐回归测试。\n相关工具: code_edit",
            scope = "project",
            reason = "existing",
            sourceKind = "turn_completion"
        )

        val suggestion = buildMidSessionMemoryUpdateSuggestion(
            stateBeforeSend = SessionState(),
            completedState = SessionState(
                messages = listOf(
                    ChatMessageUi(
                        id = 2L,
                        role = "assistant",
                        content = "- 已修复 durable memory 的 turn-end policy 抽离，并补齐回归测试。"
                    )
                ),
                recentToolCalls = listOf(
                    ToolCallRecordUi(
                        toolName = "code_edit",
                        args = "{}",
                        isSuccess = true
                    )
                ),
                recentMemoryUpdateSuggestions = listOf(existing)
            ),
            userMessage = ChatMessageUi(id = 1L, role = "user", content = "继续"),
            assistantMessageId = 2L,
            executionGoal = "抽离 memory update policy",
            currentProjectScopePath = "C:/workspace/app"
        )

        assertNull(suggestion)
    }

    @Test
    fun applyMemoryUpdateSuggestionTransition_prependsAndCapsHistory() {
        val state = SessionState(
            recentMemoryUpdateSuggestions = (1..12).map { index ->
                MemoryUpdateSuggestionUi(
                    id = "existing-$index",
                    title = "建议$index",
                    content = "内容$index",
                    scope = "global",
                    reason = "r$index",
                    sourceKind = "turn_completion",
                    createdAt = index.toLong()
                )
            }
        )

        val updated = applyMemoryUpdateSuggestionTransition(
            state = state,
            suggestion = MemoryUpdateSuggestionUi(
                id = "new",
                title = "新建议",
                content = "新内容",
                scope = "project",
                reason = "new",
                sourceKind = "turn_completion",
                createdAt = 99L
            )
        )

        assertEquals(12, updated.recentMemoryUpdateSuggestions.size)
        assertEquals("new", updated.recentMemoryUpdateSuggestions.first().id)
        assertTrue(updated.recentMemoryUpdateSuggestions.none { it.id == "existing-12" })
    }

    @Test
    fun findRememberMemorySuggestion_onlyReturnsPendingSuggestion() {
        val result = findRememberMemorySuggestion(
            state = SessionState(
                recentMemoryUpdateSuggestions = listOf(
                    MemoryUpdateSuggestionUi(
                        id = "pending",
                        title = "发布约定",
                        content = "发布前先核对 changelog。",
                        scope = "global",
                        reason = "new",
                        sourceKind = "turn_completion"
                    ),
                    MemoryUpdateSuggestionUi(
                        id = "applied",
                        title = "旧建议",
                        content = "旧内容",
                        scope = "project",
                        reason = "old",
                        sourceKind = "turn_completion",
                        status = MemoryUpdateSuggestionStatusUi.APPLIED
                    )
                )
            ),
            suggestionId = "pending"
        )

        assertNotNull(result)
        assertEquals("发布约定", result.title)
        assertEquals(MemoryScope.GLOBAL, result.scope)
        assertNull(findRememberMemorySuggestion(SessionState(), "missing"))
    }

    @Test
    fun applyMemoryUpdateSuggestionAppliedTransition_marksSuggestionAsApplied() {
        val updated = applyMemoryUpdateSuggestionAppliedTransition(
            state = SessionState(
                recentMemoryUpdateSuggestions = listOf(
                    MemoryUpdateSuggestionUi(
                        id = "pending",
                        title = "发布约定",
                        content = "发布前先核对 changelog。",
                        scope = "global",
                        reason = "new",
                        sourceKind = "turn_completion"
                    )
                )
            ),
            suggestionId = "pending",
            memoryId = "mem-123"
        )

        val suggestion = updated.recentMemoryUpdateSuggestions.single()
        assertEquals(MemoryUpdateSuggestionStatusUi.APPLIED, suggestion.status)
        assertEquals("mem-123", suggestion.linkedMemoryId)
    }
}
