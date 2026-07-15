package com.murong.agent.core.provider

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ClaudeProviderProtocolTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun buildAnthropicToolsFromOpenAiSchema_convertsFunctionShape() {
        val tools = buildAnthropicToolsFromOpenAiSchema(
            json,
            """
            [
              {
                "type": "function",
                "function": {
                  "name": "read_file",
                  "description": "Read a file from disk",
                  "parameters": {
                    "type": "object",
                    "properties": {
                      "path": { "type": "string" }
                    },
                    "required": ["path"]
                  }
                }
              }
            ]
            """.trimIndent()
        )

        val tool = tools[0].jsonObject
        assertEquals("read_file", tool["name"]?.jsonPrimitive?.content)
        assertEquals("Read a file from disk", tool["description"]?.jsonPrimitive?.content)
        assertEquals("object", tool["input_schema"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertNull(tool["function"])
    }

    @Test
    fun convertMessagesToAnthropic_wrapsToolResultWithoutDuplicatingTextBlock() {
        val messages = listOf(
            ChatMessage(role = "assistant", content = "先读取文件"),
            ChatMessage(role = "tool", toolCallId = "call_1", content = "hello from tool")
        )

        val converted = convertMessagesToAnthropic(json, messages)
        val toolMessage = converted[1]
        val contentBlocks = toolMessage["content"]?.jsonArray ?: error("missing content")

        assertEquals("user", toolMessage["role"]?.jsonPrimitive?.content)
        assertEquals(1, contentBlocks.size)
        assertEquals("tool_result", contentBlocks[0].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("call_1", contentBlocks[0].jsonObject["tool_use_id"]?.jsonPrimitive?.content)
        assertEquals("hello from tool", contentBlocks[0].jsonObject["content"]?.jsonPrimitive?.content)
    }

    @Test
    fun convertMessagesToAnthropic_keepsAssistantToolUseBlocks() {
        val messages = listOf(
            ChatMessage(
                role = "assistant",
                toolCalls = listOf(
                    ToolCall(
                        id = "call_2",
                        function = ToolCallFunction(
                            name = "read_file",
                            arguments = """{"path":"/tmp/a.txt"}"""
                        )
                    )
                )
            )
        )

        val converted = convertMessagesToAnthropic(json, messages)
        val assistantMessage = converted.single()
        val contentBlocks = assistantMessage["content"]?.jsonArray ?: error("missing content")

        assertEquals("assistant", assistantMessage["role"]?.jsonPrimitive?.content)
        assertEquals("tool_use", contentBlocks[0].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("call_2", contentBlocks[0].jsonObject["id"]?.jsonPrimitive?.content)
        assertEquals("read_file", contentBlocks[0].jsonObject["name"]?.jsonPrimitive?.content)
        assertEquals(
            "/tmp/a.txt",
            contentBlocks[0].jsonObject["input"]?.jsonObject?.get("path")?.jsonPrimitive?.content
        )
    }
}
