package com.murong.agent.core.provider

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull

class ChatMessageNormalizerTest {

    @Test
    fun normalizeMessagesForProvider_repairsInterruptedCallsDropsOrphansAndOrdersResults() {
        val original = listOf(
            ChatMessage(
                role = "tool",
                toolCallId = "orphan",
                name = "old_tool",
                content = "must not reach provider"
            ),
            ChatMessage(
                role = "assistant",
                toolCalls = listOf(
                    ToolCall("call-1", function = ToolCallFunction("first", "{\"value\":")),
                    ToolCall("call-2", function = ToolCallFunction("", "{\"ok\":true}"))
                )
            ),
            ChatMessage(
                role = "tool",
                toolCallId = "call-2",
                name = "second",
                content = "second result"
            )
        )

        val normalized = normalizeMessagesForProvider(original)

        assertEquals(3, normalized.size)
        val assistant = normalized[0]
        assertEquals("{\"value\":null}", assistant.toolCalls!![0].function.arguments)
        assertEquals("second", assistant.toolCalls!![1].function.name)
        assertEquals("call-1", normalized[1].toolCallId)
        assertEquals("first", normalized[1].name)
        assertEquals(INTERRUPTED_TOOL_RESULT, normalized[1].content)
        assertEquals("call-2", normalized[2].toolCallId)
        assertEquals("second", normalized[2].name)
        assertEquals("second result", normalized[2].content)
        assertFalse(normalized.any { it.toolCallId == "orphan" })

        assertEquals("{\"value\":", original[1].toolCalls!![0].function.arguments)
        assertEquals("", original[1].toolCalls!![1].function.name)
    }

    @Test
    fun normalizeMessagesForProvider_usesPositionalPairingForDuplicateOrBlankCallIds() {
        val normalized = normalizeMessagesForProvider(
            listOf(
                ChatMessage(
                    role = "assistant",
                    toolCalls = listOf(
                        ToolCall("", function = ToolCallFunction("first", "{}")),
                        ToolCall("", function = ToolCallFunction("second", "{}"))
                    )
                ),
                ChatMessage(role = "tool", toolCallId = "wrong-1", name = "wrong", content = "one"),
                ChatMessage(role = "tool", toolCallId = "wrong-2", name = "wrong", content = "two")
            )
        )

        assertEquals(listOf("", ""), normalized.drop(1).map { it.toolCallId })
        assertEquals(listOf("first", "second"), normalized.drop(1).map { it.name })
        assertEquals(listOf("one", "two"), normalized.drop(1).map { it.content })
    }

    @Test
    fun normalizeChatRequestForProvider_returnsCopyWithoutMutatingRequestHistory() {
        val request = ChatRequest(
            messages = listOf(
                ChatMessage(role = "tool", toolCallId = "orphan", content = "stale"),
                ChatMessage(role = "user", content = "continue")
            ),
            model = "test"
        )

        val normalized = normalizeChatRequestForProvider(request)

        assertNotSame(request, normalized)
        assertEquals(listOf("user"), normalized.messages.map { it.role })
        assertEquals(listOf("tool", "user"), request.messages.map { it.role })
        assertNull(normalized.messages.single().toolCallId)
    }

    @Test
    fun closeTruncatedJsonArguments_repairsOrFallsBackToObject() {
        assertEquals("{\"items\":[1,2]}", closeTruncatedJsonArguments("{\"items\":[1,2"))
        assertEquals("{\"label\":\"unfinished\"}", closeTruncatedJsonArguments("{\"label\":\"unfinished"))
        assertEquals("{}", closeTruncatedJsonArguments("not json"))
        assertEquals("{\"ok\":true}", closeTruncatedJsonArguments("{\"ok\":true}"))
    }
}
