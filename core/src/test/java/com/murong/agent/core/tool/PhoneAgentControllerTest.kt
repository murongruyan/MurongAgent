package com.murong.agent.core.tool

import com.murong.agent.core.config.ProviderConfig
import com.murong.agent.core.config.RelayConfig
import com.murong.agent.core.provider.ChatMessage
import com.murong.agent.core.provider.ChatRequest
import com.murong.agent.core.provider.ChatResponse
import com.murong.agent.core.provider.ModelProvider
import com.murong.agent.core.provider.ProviderRegistry
import com.murong.agent.core.provider.StreamDelta
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PhoneAgentControllerTest {
    @Test
    fun configuredClientDispatchesThroughSelectedProviderAndOffersPhoneTools() = runBlocking {
        val provider = RecordingProvider()
        ProviderRegistry.register(provider)
        val response = OpenAICompatiblePhoneAgentClient().complete(
            messages = listOf(ChatMessage(role = "user", content = "观察截图")),
            connection = PhoneAgentConnection(
                providerId = provider.id,
                baseUrl = "https://selected.example/v1",
                apiKey = "selected-key",
                model = "selected-model",
                reasoningEffort = "high"
            )
        )

        assertEquals("finish(message=\"完成\")", response.content)
        assertEquals("selected-model", provider.request?.model)
        assertEquals("high", provider.request?.reasoningEffort)
        assertTrue(provider.request?.tools?.contains("phone_action") == true)
        assertEquals("selected-key", provider.apiKey)
        assertEquals("https://selected.example/v1", provider.baseUrl)
    }

    @Test
    fun completesContinuousObservationActionLoop() = runBlocking {
        val client = QueueModelClient(
            "do(action=\"Launch\", app=\"美团\")",
            "do(action=\"Tap\", element=[500,250])",
            "finish(message=\"已找到目标页面\")"
        )
        val device = FakeDevice()

        val result = PhoneAgentController(client).run(
            request = PhoneAgentTaskRequest("打开美团并查看页面"),
            config = localConfig(),
            device = device
        )

        assertTrue(result.success)
        assertEquals("completed", result.status)
        assertEquals(listOf("Launch", "Tap"), device.commands.map { it.action })
        assertTrue(client.requests.all { messages ->
            messages.count { it.images.isNotEmpty() } == 1
        })
    }

    @Test
    fun sensitivePaymentTapRequiresTakeoverWithoutExecuting() = runBlocking {
        val device = FakeDevice()
        val result = PhoneAgentController(
            QueueModelClient("准备点击立即支付。do(action=\"Tap\", element=[500,900])")
        ).run(
            request = PhoneAgentTaskRequest("购买商品"),
            config = localConfig(),
            device = device
        )

        assertTrue(result.success)
        assertEquals("takeover", result.status)
        assertTrue(result.requiresUserAction)
        assertTrue(device.commands.isEmpty())
    }

    @Test
    fun repeatedUnchangedActionIsInterceptedAndAlternativeCanRecover() = runBlocking {
        val device = FakeDevice()
        val result = PhoneAgentController(
            QueueModelClient(
                "do(action=\"Tap\", element=[500,500])",
                "do(action=\"Tap\", element=[500,500])",
                "do(action=\"Back\")",
                "finish(message=\"已恢复\")"
            )
        ).run(
            request = PhoneAgentTaskRequest("处理卡住的页面"),
            config = localConfig(),
            device = device
        )

        assertTrue(result.success)
        assertEquals(listOf("Tap", "Back"), device.commands.map { it.action })
        assertTrue(result.trace.any { it.detail?.contains("拦截重复动作") == true })
    }

    @Test
    fun remoteEndpointRequiresExplicitScreenshotConsent() = runBlocking {
        val client = QueueModelClient("finish(message=\"不应调用\")")
        val result = PhoneAgentController(client).run(
            request = PhoneAgentTaskRequest("测试"),
            config = ProviderConfig(
                activeProviderId = "openai-compatible",
                openaiBaseUrl = "https://example.com/v1",
                openaiApiKey = "secret",
                openaiModel = "phone-model",
                phoneAgentAllowRemoteScreenshots = false
            ),
            device = FakeDevice()
        )

        assertFalse(result.success)
        assertTrue(result.message.contains("明确允许"))
        assertTrue(client.requests.isEmpty())
    }

    @Test
    fun textOnlyBuiltinModelIsRejectedBeforeAnyScreenshotIsCaptured() = runBlocking {
        val client = QueueModelClient("finish(message=\"不应调用\")")
        val device = FakeDevice()
        val result = PhoneAgentController(client).run(
            request = PhoneAgentTaskRequest("打开微信并发送消息"),
            config = ProviderConfig(
                activeProviderId = "murong-local",
                builtinLocalModelOverride = BuiltinVisionModels.GLM_EDGE_1_5B_CHAT.id,
            ),
            device = device,
        )

        assertFalse(result.success)
        assertTrue(result.message.contains("纯文本模型"))
        assertTrue(client.requests.isEmpty())
        assertTrue(device.commands.isEmpty())
    }

    @Test
    fun reusesCurrentlySelectedProviderModelAndCredential() = runBlocking {
        val client = QueueModelClient("finish(message=\"完成\")")
        val result = PhoneAgentController(client).run(
            request = PhoneAgentTaskRequest("测试当前模型"),
            config = ProviderConfig(
                activeProviderId = "deepseek",
                deepseekBaseUrl = "https://configured.example/v1",
                deepseekApiKey = "shared-secret",
                deepseekModel = "configured-vision-model",
                deepseekReasoningEffort = "medium",
                phoneAgentAllowRemoteScreenshots = true
            ),
            device = FakeDevice()
        )

        assertTrue(result.success)
        val connection = client.connections.single()
        assertEquals("deepseek", connection.providerId)
        assertEquals("https://configured.example/v1", connection.baseUrl)
        assertEquals("shared-secret", connection.apiKey)
        assertEquals("configured-vision-model", connection.model)
        assertEquals("medium", connection.reasoningEffort)
    }

    @Test
    fun independentlySelectedRelayOverridesChatModelWithoutDuplicatingCredentials() = runBlocking {
        val selectedRelay = RelayConfig(
            id = "phone-vision",
            name = "智谱视觉",
            baseUrl = "https://open.bigmodel.cn/api/paas/v4",
            apiKey = "zhipu-secret",
            model = "glm-4.5v",
            reasoningEffort = "high"
        )
        val client = QueueModelClient("finish(message=\"完成\")")
        val result = PhoneAgentController(client).run(
            request = PhoneAgentTaskRequest("打开应用"),
            config = ProviderConfig(
                activeProviderId = "deepseek",
                deepseekApiKey = "chat-secret",
                deepseekModel = "deepseek-chat",
                openaiRelays = listOf(selectedRelay),
                phoneAgentProviderId = "openai-compatible",
                phoneAgentRelayId = selectedRelay.id,
                phoneAgentAllowRemoteScreenshots = true
            ),
            device = FakeDevice()
        )

        assertTrue(result.success)
        val connection = client.connections.single()
        assertEquals("openai-compatible", connection.providerId)
        assertEquals("https://open.bigmodel.cn/api/paas/v4", connection.baseUrl)
        assertEquals("zhipu-secret", connection.apiKey)
        assertEquals("glm-4.5v", connection.model)
        assertEquals("high", connection.reasoningEffort)
    }

    @Test
    fun accountBackendDelegatesToCurrentConversationWithoutSecondLogin() = runBlocking {
        val client = QueueModelClient("finish(message=\"不应调用\")")
        val result = PhoneAgentController(client).run(
            request = PhoneAgentTaskRequest("打开抖音"),
            config = ProviderConfig(
                activeAgentBackend = com.murong.agent.core.config.AgentBackendKind.CODEX_CHATGPT
            ),
            device = FakeDevice()
        )

        assertTrue(result.success)
        assertEquals("delegated", result.status)
        assertTrue(result.message.contains("无需第二个登录入口"))
        assertTrue(client.requests.isEmpty())
    }

    @Test
    fun parsesFoodComparisonFromFinishAndMergesNotes() = runBlocking {
        val result = PhoneAgentController(
            QueueModelClient(
                "do(action=\"Note\", message=\"美团价格来自结算页\")",
                """finish(message="{\"query\":\"咖啡\",\"offers\":[{\"platform\":\"美团\",\"totalPrice\":21},{\"platform\":\"饿了么\",\"totalPrice\":19}]}")"""
            )
        ).run(
            request = PhoneAgentTaskRequest(
                task = "比较咖啡外卖最低价",
                taskType = "food_delivery_compare"
            ),
            config = localConfig(),
            device = FakeDevice()
        )

        assertEquals("饿了么", result.foodDeliveryComparison?.cheapestOffer?.platform)
        assertTrue(
            result.foodDeliveryComparison?.notes?.contains("美团价格来自结算页") == true
        )
    }

    private fun localConfig() = ProviderConfig(
        activeProviderId = "openai-compatible",
        openaiBaseUrl = "http://127.0.0.1:8080/v1",
        openaiModel = "configured-local-vision-model",
        phoneAgentAllowRemoteScreenshots = false
    )

    private class QueueModelClient(vararg responses: String) : PhoneAgentModelClient {
        private val queue = ArrayDeque(
            responses.map { response -> PhoneAgentModelResponse(content = response) }
        )
        val requests = mutableListOf<List<ChatMessage>>()
        val connections = mutableListOf<PhoneAgentConnection>()

        override suspend fun complete(
            messages: List<ChatMessage>,
            connection: PhoneAgentConnection
        ): PhoneAgentModelResponse {
            requests += messages
            connections += connection
            return queue.removeFirst()
        }
    }

    private class FakeDevice : PhoneAgentDevice {
        val commands = mutableListOf<PhoneAgentCommand>()

        override suspend fun observe(): PhoneAgentScreen = PhoneAgentScreen(
            screenshot = GuiScreenshot(
                mimeType = "image/png",
                base64Data = "aGVsbG8=",
                width = 1000,
                height = 2000
            ),
            application = "com.murong.test"
        )

        override suspend fun execute(
            command: PhoneAgentCommand,
            screen: PhoneAgentScreen
        ): PhoneAgentDeviceResult {
            commands += command
            return PhoneAgentDeviceResult(success = true, detail = "ok")
        }
    }

    private class RecordingProvider : ModelProvider {
        override val name = "Recording"
        override val id = "phone-agent-recording-provider"
        override val defaultBaseUrl = "https://default.example"
        override val defaultModel = "default"
        override val supportsReasoning = true
        var request: ChatRequest? = null
        var apiKey: String? = null
        var baseUrl: String? = null

        override suspend fun chatStream(
            request: ChatRequest,
            apiKey: String,
            baseUrl: String?,
            onDelta: (StreamDelta) -> Unit
        ): ChatResponse = chat(request, apiKey, baseUrl)

        override suspend fun chat(
            request: ChatRequest,
            apiKey: String,
            baseUrl: String?
        ): ChatResponse {
            this.request = request
            this.apiKey = apiKey
            this.baseUrl = baseUrl
            return ChatResponse(
                content = "finish(message=\"完成\")",
                toolCalls = null
            )
        }
    }
}
