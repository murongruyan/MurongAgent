package com.murong.agent.core.provider

import java.io.ByteArrayInputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OpenAIProtocolTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun endpointResolver_prefersResponsesOnlyForOfficialOrExplicitEndpoints() {
        val official = resolveOpenAIEndpoint("https://api.openai.com")
        assertEquals(OpenAIWireProtocol.RESPONSES, official.protocol)
        assertEquals("https://api.openai.com/v1/responses", official.url)
        assertEquals("https://api.openai.com/v1/chat/completions", official.chatCompletionsFallbackUrl)

        val compatible = resolveOpenAIEndpoint("https://relay.example/v1")
        assertEquals(OpenAIWireProtocol.CHAT_COMPLETIONS, compatible.protocol)
        assertEquals("https://relay.example/v1/chat/completions", compatible.url)

        val explicitResponses = resolveOpenAIEndpoint("https://relay.example/v1/responses")
        assertEquals(OpenAIWireProtocol.RESPONSES, explicitResponses.protocol)
        assertEquals("https://relay.example/v1/chat/completions", explicitResponses.chatCompletionsFallbackUrl)

        val explicitChat = resolveOpenAIEndpoint("https://api.openai.com/v1/chat/completions")
        assertEquals(OpenAIWireProtocol.CHAT_COMPLETIONS, explicitChat.protocol)
    }

    @Test
    fun responsesPayload_mapsMessagesToolsImagesAndReasoningToNativeSchema() {
        val request = ChatRequest(
            model = "gpt-5.6-sol",
            reasoningEffort = "high",
            maxTokens = 4096,
            messages = listOf(
                ChatMessage("system", "Be concise."),
                ChatMessage(
                    role = "user",
                    content = "Inspect this",
                    images = listOf(ChatImageAttachment("image/png", "YWJj"))
                ),
                ChatMessage(
                    role = "assistant",
                    toolCalls = listOf(ToolCall("call_1", function = ToolCallFunction("read_file", "{\"path\":\"a.kt\"}")))
                ),
                ChatMessage(role = "tool", toolCallId = "call_1", content = "file body")
            ),
            tools = """[{"type":"function","function":{"name":"read_file","description":"Read a file","parameters":{"type":"object"}}}]"""
        )

        val payload = buildResponsesPayload(request, stream = true)

        assertEquals("Be concise.", payload["instructions"]?.jsonPrimitive?.content)
        assertEquals(4096, payload["max_output_tokens"]?.jsonPrimitive?.content?.toInt())
        assertEquals("high", payload["reasoning"]?.jsonObject?.get("effort")?.jsonPrimitive?.content)
        assertFalse(payload.containsKey("temperature"))
        val tool = payload["tools"]!!.jsonArray.single().jsonObject
        assertEquals("read_file", tool["name"]?.jsonPrimitive?.content)
        assertFalse(tool.containsKey("function"))

        val input = payload["input"]!!.jsonArray
        val imageContent = input.first().jsonObject["content"]!!.jsonArray
        assertEquals("input_image", imageContent.last().jsonObject["type"]?.jsonPrimitive?.content)
        assertTrue(input.any { it.jsonObject["type"]?.jsonPrimitive?.content == "function_call" })
        assertTrue(input.any { it.jsonObject["type"]?.jsonPrimitive?.content == "function_call_output" })
    }

    @Test
    fun responsesPayload_withPreviousResponseId_sendsOnlyNewTail() {
        val request = ChatRequest(
            model = "gpt-5.6-sol",
            messages = listOf(
                ChatMessage("system", "Tools are available."),
                ChatMessage("user", "old request"),
                ChatMessage(
                    role = "assistant",
                    toolCalls = listOf(ToolCall("call_7", function = ToolCallFunction("shell", "{}")))
                ),
                ChatMessage(role = "tool", toolCallId = "call_7", content = "ok")
            ),
            responsesContinuation = ResponsesContinuation(previousResponseId = "resp_123")
        )

        val payload = buildResponsesPayload(request, stream = false)
        val input = payload["input"]!!.jsonArray

        assertEquals("resp_123", payload["previous_response_id"]?.jsonPrimitive?.content)
        assertEquals(1, input.size)
        assertEquals("function_call_output", input.single().jsonObject["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun chatCompletionsPayload_usesNormalizedCopyForStreamAndNonStream() {
        val original = ChatRequest(
            model = "compatible-model",
            messages = listOf(
                ChatMessage("user", "run"),
                ChatMessage(
                    role = "assistant",
                    toolCalls = listOf(
                        ToolCall("call_1", function = ToolCallFunction("shell", "{\"command\":"))
                    )
                )
            )
        )
        val normalized = normalizeChatRequestForProvider(original)

        listOf(true, false).forEach { stream ->
            val messages = buildChatCompletionsPayload(normalized, stream)["messages"]!!.jsonArray
            val arguments = messages[1].jsonObject["tool_calls"]!!.jsonArray.single()
                .jsonObject["function"]!!.jsonObject["arguments"]!!.jsonPrimitive.content
            assertEquals("{\"command\":null}", arguments)
            assertEquals(INTERRUPTED_TOOL_RESULT, messages[2].jsonObject["content"]?.jsonPrimitive?.content)
        }

        assertEquals("{\"command\":", original.messages[1].toolCalls!!.single().function.arguments)
    }

    @Test
    fun responsesResponse_parsesTextFunctionsUsageAndContinuation() {
        val root = json.parseToJsonElement(
            """{
              "id":"resp_42",
              "output":[
                {"type":"reasoning","id":"rs_1","encrypted_content":"opaque","summary":[]},
                {"type":"message","content":[{"type":"output_text","text":"done"}]},
                {"type":"function_call","call_id":"call_2","name":"shell","arguments":"{\"command\":\"pwd\"}"}
              ],
              "usage":{
                "input_tokens":100,"output_tokens":25,"total_tokens":125,
                "input_tokens_details":{"cached_tokens":60},
                "output_tokens_details":{"reasoning_tokens":10}
              }
            }"""
        ).jsonObject

        val response = parseResponsesResponse(root)

        assertEquals("done", response.content)
        assertEquals("call_2", response.toolCalls!!.single().id)
        assertEquals(60, response.usage?.promptCacheHitTokens)
        assertEquals(10, response.usage?.reasoningTokens)
        assertEquals("resp_42", response.responsesContinuation?.previousResponseId)
        assertTrue(response.responsesContinuation?.reasoningItems?.single()?.contains("encrypted_content") == true)
    }

    @Test
    fun responsesSse_emitsNativeTextReasoningAndFunctionEvents() {
        val sse = listOf(
            "data: {\"type\":\"response.reasoning_summary_text.delta\",\"delta\":\"checking\"}",
            "data: {\"type\":\"response.output_text.delta\",\"delta\":\"done\"}",
            "data: {\"type\":\"response.output_item.added\",\"output_index\":1,\"item\":{\"type\":\"function_call\",\"id\":\"fc_1\",\"call_id\":\"call_1\",\"name\":\"shell\",\"arguments\":\"\"}}",
            "data: {\"type\":\"response.function_call_arguments.delta\",\"output_index\":1,\"item_id\":\"fc_1\",\"delta\":\"{\\\"command\\\":\\\"pwd\\\"}\"}",
            "data: {\"type\":\"response.output_item.done\",\"output_index\":1,\"item\":{\"type\":\"function_call\",\"id\":\"fc_1\",\"call_id\":\"call_1\",\"name\":\"shell\",\"arguments\":\"{\\\"command\\\":\\\"pwd\\\"}\"}}",
            "data: {\"type\":\"response.completed\",\"response\":{\"id\":\"resp_1\",\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"done\"}]},{\"type\":\"function_call\",\"call_id\":\"call_1\",\"name\":\"shell\",\"arguments\":\"{\\\"command\\\":\\\"pwd\\\"}\"}],\"usage\":{\"input_tokens\":5,\"output_tokens\":3,\"total_tokens\":8}}}",
            ""
        ).joinToString("\n")
        val deltas = mutableListOf<StreamDelta>()

        val response = parseResponsesSse(ByteArrayInputStream(sse.toByteArray()), deltas::add)

        assertEquals("done", response.content)
        assertEquals("shell", response.toolCalls!!.single().function.name)
        assertEquals("resp_1", response.responsesContinuation?.previousResponseId)
        assertTrue(deltas.any { it == StreamDelta.Content("done") })
        assertTrue(deltas.any { it == StreamDelta.Reasoning("checking") })
        assertIs<StreamDelta.ToolCallStart>(deltas.first { it is StreamDelta.ToolCallStart })
        assertNotNull(deltas.firstOrNull { it is StreamDelta.ToolCallDelta })
        assertIs<StreamDelta.Done>(deltas.last())
    }

    @Test
    fun responsesSse_preservesPartialTextFromIncompleteResponse() {
        val sse = listOf(
            "data: {\"type\":\"response.output_text.delta\",\"delta\":\"partial\"}",
            "data: {\"type\":\"response.incomplete\",\"response\":{\"id\":\"resp_partial\",\"incomplete_details\":{\"reason\":\"max_output_tokens\"},\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"partial\"}]}]}}",
            ""
        ).joinToString("\n")
        val deltas = mutableListOf<StreamDelta>()

        val response = parseResponsesSse(ByteArrayInputStream(sse.toByteArray()), deltas::add)

        assertEquals("partial", response.content)
        assertEquals("resp_partial", response.responsesContinuation?.previousResponseId)
        assertTrue(deltas.any { it is StreamDelta.Error && it.message.contains("max_output_tokens") })
        assertIs<StreamDelta.Done>(deltas.last())
    }
}
