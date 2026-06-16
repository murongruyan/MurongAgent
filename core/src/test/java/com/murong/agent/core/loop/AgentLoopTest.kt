package com.murong.agent.core.loop

import com.murong.agent.core.config.ProviderConfig
import com.murong.agent.core.provider.ChatMessage
import com.murong.agent.core.provider.ChatRequest
import com.murong.agent.core.provider.ChatResponse
import com.murong.agent.core.provider.ModelProvider
import com.murong.agent.core.provider.StreamDelta
import com.murong.agent.core.provider.ToolCall
import com.murong.agent.core.provider.ToolCallFunction
import com.murong.agent.core.tool.Tool
import com.murong.agent.core.tool.ToolRegistry
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentLoopTest {

    @Test
    fun processMessage_whenModelStopsAfterToolResults_emitsSyntheticSummaryInsteadOfSilentDone() = runBlocking {
        val provider = FakeModelProvider(
            responses = ArrayDeque(
                listOf(
                    ChatResponse(
                        content = null,
                        toolCalls = listOf(
                            ToolCall(
                                id = "call-1",
                                function = ToolCallFunction(
                                    name = "fake_tool",
                                    arguments = """{"query":"ProjectScreen"}"""
                                )
                            )
                        )
                    ),
                    ChatResponse(content = null, toolCalls = null),
                    ChatResponse(content = null, toolCalls = null)
                )
            )
        )
        val registry = ToolRegistry().apply {
            register(FakeTool())
        }
        val loop = AgentLoop(
            provider = provider,
            toolRegistry = registry,
            config = ProviderConfig(
                activeProviderId = "openai-compatible",
                openaiModel = "fake-model"
            )
        )
        val events = mutableListOf<AgentEvent>()

        loop.processMessage(
            userMessage = ChatMessage(role = "user", content = "帮我搜索 ProjectScreen 相关代码"),
            history = emptyList(),
            onEvent = events::add
        )

        val contentEvents = events.filterIsInstance<AgentEvent.ContentDelta>()
        assertTrue(contentEvents.isNotEmpty(), "工具后不应静默结束，至少要有一条总结内容")
        assertTrue(
            contentEvents.last().text.contains("fake_tool") &&
                contentEvents.last().text.contains("定位到 2 处"),
            "兜底总结应包含工具名和工具结果摘要"
        )
        assertTrue(events.last() is AgentEvent.Done)
        assertEquals(3, provider.callCount, "工具后空回复时应补一次总结重试，再走最终兜底")
    }

    private class FakeTool : Tool {
        override val name: String = "fake_tool"
        override val description: String = "fake"
        override val parameters: Map<String, Any> = emptyMap()

        override suspend fun execute(args: String): String {
            return "已定位到 2 处匹配，建议继续读取附近 20 行上下文。"
        }
    }

    private class FakeModelProvider(
        private val responses: ArrayDeque<ChatResponse>
    ) : ModelProvider {
        var callCount: Int = 0
            private set

        override val name: String = "Fake"
        override val id: String = "fake"
        override val defaultBaseUrl: String = "https://example.invalid"
        override val defaultModel: String = "fake-model"
        override val supportsReasoning: Boolean = false

        override suspend fun chatStream(
            request: ChatRequest,
            apiKey: String,
            baseUrl: String?,
            onDelta: (StreamDelta) -> Unit
        ): ChatResponse {
            callCount++
            return responses.removeFirstOrNull() ?: ChatResponse(content = "unexpected", toolCalls = null)
        }

        override suspend fun chat(
            request: ChatRequest,
            apiKey: String,
            baseUrl: String?
        ): ChatResponse {
            error("chat() should not be called in AgentLoopTest")
        }
    }
}
