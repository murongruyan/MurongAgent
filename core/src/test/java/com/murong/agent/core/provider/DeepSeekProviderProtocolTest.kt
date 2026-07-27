package com.murong.agent.core.provider

import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeepSeekProviderProtocolTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun chat_replaysReasoningAndSendsThinkingAtWireTopLevel() = runBlocking {
        val serverResponse = """
            {
              "choices": [{
                "message": {
                  "role": "assistant",
                  "content": "完成",
                  "reasoning_content": "The write result is verified."
                }
              }]
            }
        """.trimIndent()

        val (response, requestBody) = withSingleResponseServer(
            responseBody = serverResponse,
            contentType = "application/json"
        ) { baseUrl ->
            DeepSeekProvider().chat(
                request = ChatRequest(
                    messages = listOf(
                        ChatMessage(role = "user", content = "修改文件"),
                        ChatMessage(
                            role = "assistant",
                            content = null,
                            toolCalls = listOf(
                                ToolCall(
                                    id = "call-1",
                                    function = ToolCallFunction("shell", """{"command":"sed -i ..."}""")
                                )
                            ),
                            reasoningContent = "I need to edit the file now."
                        ),
                        ChatMessage(
                            role = "tool",
                            content = "updated",
                            toolCallId = "call-1",
                            name = "shell"
                        )
                    ),
                    model = "deepseek-v4-pro",
                    // Regression: older app builds accidentally serialized the
                    // depth as thinking.type = "reasoning/low".
                    thinkingMode = "reasoning/low"
                ),
                apiKey = "test-key",
                baseUrl = baseUrl
            )
        }

        val payload = json.parseToJsonElement(requestBody).jsonObject
        assertEquals("enabled", payload["thinking"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertFalse("extra_body" in payload)
        val assistant = payload["messages"]!!.jsonArray[1].jsonObject
        assertEquals("", assistant["content"]?.jsonPrimitive?.content)
        assertEquals(
            "I need to edit the file now.",
            assistant["reasoning_content"]?.jsonPrimitive?.content
        )
        assertEquals("The write result is verified.", response.reasoningContent)
        assertEquals("完成", response.content)
    }

    @Test
    fun chatStream_preservesReasoningAndUsesNormalizedMessages() = runBlocking {
        val serverResponse = """
            data: {"choices":[{"delta":{"reasoning_content":"Need to write now.","tool_calls":[{"index":0,"id":"call-2","function":{"name":"shell","arguments":"{\"command\":\"sed -i ...\"}"}}]},"finish_reason":"tool_calls"}]}

            data: [DONE]

        """.trimIndent() + "\n"

        val deltas = mutableListOf<StreamDelta>()
        val (response, requestBody) = withSingleResponseServer(
            responseBody = serverResponse,
            contentType = "text/event-stream"
        ) { baseUrl ->
            DeepSeekProvider().chatStream(
                request = ChatRequest(
                    messages = listOf(
                        ChatMessage(
                            role = "assistant",
                            content = null,
                            toolCalls = listOf(
                                ToolCall(
                                    id = "call-1",
                                    function = ToolCallFunction("shell", """{"command":"cat service.sh"}""")
                                )
                            ),
                            reasoningContent = "First inspect service.sh."
                        ),
                        ChatMessage(role = "user", content = "继续")
                    ),
                    model = "deepseek-v4-pro",
                    thinkingMode = "enabled"
                ),
                apiKey = "test-key",
                baseUrl = baseUrl,
                onDelta = deltas::add
            )
        }

        val payload = json.parseToJsonElement(requestBody).jsonObject
        val messages = payload["messages"]!!.jsonArray
        assertEquals(3, messages.size)
        assertEquals("", messages[0].jsonObject["content"]?.jsonPrimitive?.content)
        assertEquals(
            "First inspect service.sh.",
            messages[0].jsonObject["reasoning_content"]?.jsonPrimitive?.content
        )
        assertEquals("tool", messages[1].jsonObject["role"]?.jsonPrimitive?.content)
        assertEquals(INTERRUPTED_TOOL_RESULT, messages[1].jsonObject["content"]?.jsonPrimitive?.content)

        assertNull(response.content)
        assertEquals("Need to write now.", response.reasoningContent)
        val toolCall = assertNotNull(response.toolCalls).single()
        assertEquals("call-2", toolCall.id)
        assertEquals("shell", toolCall.function.name)
        assertEquals("""{"command":"sed -i ..."}""", toolCall.function.arguments)
        assertTrue(deltas.any { it is StreamDelta.Reasoning })
        assertTrue(deltas.any { it is StreamDelta.ToolCallStart })
    }

    private suspend fun <T> withSingleResponseServer(
        responseBody: String,
        contentType: String,
        block: suspend (baseUrl: String) -> T
    ): Pair<T, String> {
        val server = ServerSocket(0)
        val capturedBody = AtomicReference<String>()
        val serverFailure = AtomicReference<Throwable?>()
        val responseBytes = responseBody.toByteArray(StandardCharsets.UTF_8)
        val serverThread = thread(name = "deepseek-provider-test-server") {
            try {
                server.accept().use { socket ->
                    val input = socket.getInputStream()
                    val headerBytes = ByteArrayOutputStream()
                    var matchedHeaderEnd = 0
                    while (matchedHeaderEnd < HTTP_HEADER_END.size) {
                        val value = input.read()
                        check(value >= 0) { "Client disconnected before HTTP headers completed" }
                        headerBytes.write(value)
                        matchedHeaderEnd = if (value.toByte() == HTTP_HEADER_END[matchedHeaderEnd]) {
                            matchedHeaderEnd + 1
                        } else if (value.toByte() == HTTP_HEADER_END[0]) {
                            1
                        } else {
                            0
                        }
                    }
                    val headers = headerBytes.toString(StandardCharsets.ISO_8859_1)
                    val contentLength = CONTENT_LENGTH_REGEX.find(headers)
                        ?.groupValues
                        ?.get(1)
                        ?.toInt()
                        ?: 0
                    capturedBody.set(
                        String(input.readNBytes(contentLength), StandardCharsets.UTF_8)
                    )

                    val responseHeaders = buildString {
                        append("HTTP/1.1 200 OK\r\n")
                        append("Content-Type: $contentType\r\n")
                        append("Content-Length: ${responseBytes.size}\r\n")
                        append("Connection: close\r\n\r\n")
                    }.toByteArray(StandardCharsets.ISO_8859_1)
                    socket.getOutputStream().apply {
                        write(responseHeaders)
                        write(responseBytes)
                        flush()
                    }
                }
            } catch (error: Throwable) {
                serverFailure.set(error)
            } finally {
                runCatching { server.close() }
            }
        }

        return try {
            val result = block("http://127.0.0.1:${server.localPort}")
            serverThread.join(5_000)
            serverFailure.get()?.let { throw AssertionError("Test server failed", it) }
            assertFalse(serverThread.isAlive, "Test server did not finish")
            result to assertNotNull(capturedBody.get())
        } finally {
            runCatching { server.close() }
            serverThread.join(1_000)
        }
    }

    private companion object {
        val HTTP_HEADER_END = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
        val CONTENT_LENGTH_REGEX = Regex("""(?im)^Content-Length:\s*(\d+)\s*$""")
    }
}
