package com.murong.agent.core.tool

import com.murong.agent.core.config.ProviderConfig
import com.murong.agent.core.config.RelayConfig
import com.murong.agent.core.provider.ChatMessage
import com.murong.agent.core.provider.ChatRequest
import com.murong.agent.core.provider.ChatResponse
import com.murong.agent.core.provider.ModelProvider
import com.murong.agent.core.provider.ProviderRegistry
import com.murong.agent.core.provider.StreamDelta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.system.measureTimeMillis
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
            ),
            onVisibleText = {},
        )

        assertEquals("finish(message=\"完成\")", response.content)
        assertEquals("selected-model", provider.request?.model)
        assertEquals("high", provider.request?.reasoningEffort)
        assertTrue(provider.request?.tools?.contains("phone_action") == true)
        assertEquals(160, provider.request?.maxTokens)
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
        assertEquals(
            listOf(1, 2, 3),
            client.requests.map { messages -> messages.count { it.images.isNotEmpty() } },
        )
    }

    @Test
    fun invalidModelReplyIsVisibleWithoutThinkingContent() = runBlocking {
        val result = PhoneAgentController(
            QueueModelClient(
                "<think>不应展示的内部分析</think>我不能发送消息",
                "仍然没有调用工具",
            )
        ).run(
            request = PhoneAgentTaskRequest("发送消息"),
            config = localConfig(),
            device = FakeDevice(),
        )

        assertFalse(result.success)
        assertTrue(result.message.contains("仍然没有调用工具"))
        assertFalse(result.trace.first().detail.orEmpty().contains("内部分析"))
        assertTrue(result.trace.first().detail.orEmpty().contains("我不能发送消息"))
    }

    @Test
    fun naturalLanguageBeforeActionIsPublishedToProgress() = runBlocking {
        val progress = mutableListOf<String>()
        val result = PhoneAgentController(
            QueueModelClient(
                "我先返回上一页。\ndo(action=\"Back\")",
                "finish(message=\"已返回\")",
            )
        ).run(
            request = PhoneAgentTaskRequest("返回上一页"),
            config = localConfig(),
            device = FakeDevice(),
            onProgress = progress::add,
        )

        assertTrue(result.success)
        assertTrue(progress.any { it == "模型回复：我先返回上一页。" })
        assertTrue(progress.any { "模型动作：正在返回上一页" in it })
    }

    @Test
    fun explicitRecipientAndMessageAreAuthorizedByPrompt() {
        val prompt = PhoneAgentPrompts.taskPrompt(
            PhoneAgentTaskRequest("打开微信给慕容茹艳发送你好")
        )

        assertTrue(prompt.contains("该单条普通消息已经获得用户授权"))
        assertTrue(prompt.contains("禁止点击聊天记录中的红包"))
        assertTrue(prompt.contains("直接 Type 指定正文"))
        assertTrue(prompt.contains("按住说话"))
        assertTrue(PhoneAgentPrompts.systemPrompt().contains("中文自然语言说明"))
        assertTrue(PhoneAgentPrompts.systemPrompt().contains("message 字段"))
        assertTrue(PhoneAgentPrompts.systemPrompt().contains("绝不能用“关注”按钮代替"))
        assertTrue(PhoneAgentPrompts.systemPrompt().contains("跳过带“置顶”标记"))
        assertFalse(PhoneAgentPrompts.systemPrompt().contains("不得自行付款、下单、发送消息"))
    }

    @Test
    fun parsesGenericExplicitChineseMessageIntentWithoutAppSpecificCoordinates() {
        val intent = PhoneAgentMessageIntent.parse("打开微信给慕容茹艳发送你好")

        assertEquals("慕容茹艳", intent?.recipient)
        assertEquals("你好", intent?.body)
    }

    @Test
    fun semanticObservationProvidesNormalizedControlCenters() {
        val summary = GuiObservation(
            target = "android",
            observationId = "test",
            width = 1000,
            height = 2000,
            nodes = listOf(
                GuiNodeSnapshot(
                    id = "volatile-id",
                    role = "edit",
                    text = "你好",
                    resourceId = "com.example:id/input",
                    bounds = GuiRect(100, 1800, 900, 2000),
                    editable = true,
                    clickable = true,
                )
            ),
            source = "accessibility",
        ).toPhoneAgentSemanticSummary(1000, 2000)

        assertTrue(summary.contains("text=\"你好\""))
        assertTrue(summary.contains("center=[500,950]"))
        assertTrue(summary.contains("editable"))
        assertFalse(summary.contains("volatile-id"))
    }

    @Test
    fun explicitMessageCannotFinishUntilTypeSendAndChangedScreenAreVerified() = runBlocking {
        val client = QueueModelClient(
            "finish(message=\"已发送\")",
            "do(action=\"Type\", text=\"你好\")",
            "do(action=\"Tap\", element=[850,950])",
            "finish(message=\"已发送并验证\")",
        )
        val device = ChangingMessageDevice()

        val result = PhoneAgentController(client).run(
            request = PhoneAgentTaskRequest("打开微信给慕容茹艳发送你好"),
            config = localConfig(),
            device = device,
        )

        assertTrue(result.success)
        assertEquals(listOf("Type", "Tap"), device.commands.map { it.action })
        assertTrue(result.trace.any { it.action == "unverified_finish" })
    }

    @Test
    fun typeIsRejectedWhenSemanticTreeHasNoEditableControl() = runBlocking {
        val device = object : PhoneAgentDevice {
            val commands = mutableListOf<PhoneAgentCommand>()

            override suspend fun observe(): PhoneAgentScreen = PhoneAgentScreen(
                screenshot = GuiScreenshot(
                    mimeType = "image/png",
                    base64Data = "aGVsbG8=",
                    width = 1000,
                    height = 2000,
                ),
                application = "com.example.home",
                semanticSummary = "button text=\"发现\" center=[620,950] clickable",
            )

            override suspend fun execute(
                command: PhoneAgentCommand,
                screen: PhoneAgentScreen,
            ): PhoneAgentDeviceResult {
                commands += command
                return PhoneAgentDeviceResult(success = true, detail = "ok")
            }
        }
        val result = PhoneAgentController(
            QueueModelClient(
                "do(action=\"Type\", text=\"你好\")",
                "do(action=\"Back\")",
                "finish(message=\"已恢复\")",
            )
        ).run(
            request = PhoneAgentTaskRequest("返回上一页"),
            config = localConfig(),
            device = device,
        )

        assertTrue(result.success)
        assertEquals(listOf("Back"), device.commands.map { it.action })
        assertTrue(result.trace.any { it.detail?.contains("没有可编辑控件") == true })
    }

    @Test
    fun preparedShareUsesOnDeviceTextCoordinatesWithoutCallingModel() = runBlocking {
        val device = object : PhoneAgentDevice {
            var observation = 0
            val commands = mutableListOf<PhoneAgentCommand>()

            override suspend fun observe(): PhoneAgentScreen {
                observation++
                return PhoneAgentScreen(
                    screenshot = GuiScreenshot(
                        mimeType = "image/png",
                        base64Data = when (observation) {
                            1 -> "b25l"
                            2 -> "dHdv"
                            else -> "dGhyZWU="
                        },
                        width = 1000,
                        height = 2000,
                    ),
                    application = "com.example.chat",
                    textElements = when (observation) {
                        1 -> listOf(PhoneAgentTextElement("慕容茹艳", 220, 410))
                        2 -> listOf(
                            PhoneAgentTextElement("慕容茹艳", 500, 400),
                            PhoneAgentTextElement("你好", 500, 520),
                            PhoneAgentTextElement("发送", 700, 700),
                        )
                        else -> emptyList()
                    },
                )
            }

            override suspend fun execute(
                command: PhoneAgentCommand,
                screen: PhoneAgentScreen,
            ): PhoneAgentDeviceResult {
                commands += command
                return PhoneAgentDeviceResult(true, "ok")
            }
        }
        val client = QueueModelClient()

        val result = PhoneAgentController(client).run(
            request = PhoneAgentTaskRequest("打开微信给慕容茹艳发送你好"),
            config = localConfig(),
            device = device,
            messagePreparedByShare = true,
        )

        assertTrue(result.success)
        assertTrue(client.requests.isEmpty())
        assertEquals(listOf("Tap", "Tap"), device.commands.map { it.action })
        assertEquals(listOf(220, 700), device.commands.map { it.x })
        assertTrue(result.trace.any { it.action == "verified_share_finish" })
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
    fun pinduoduoHomeNavigationPreventsModelFromBackingOutOfTheHomepage() = runBlocking {
        val client = QueueModelClient(
            "当前页面是商品详情页，准备返回。do(action=\"Back\")",
            "finish(message=\"当前是拼多多首页商品流\")",
        )
        val device = object : PhoneAgentDevice {
            val commands = mutableListOf<PhoneAgentCommand>()

            override suspend fun observe(): PhoneAgentScreen = PhoneAgentScreen(
                screenshot = GuiScreenshot(
                    mimeType = "image/png",
                    base64Data = "aG9tZQ==",
                    width = 1000,
                    height = 2000,
                ),
                application = PhoneAgentApps.PINDUODUO_PACKAGE,
                textElements = listOf(
                    PhoneAgentTextElement("首页", 100, 960),
                    PhoneAgentTextElement("多多视频", 300, 960),
                    PhoneAgentTextElement("聊天", 700, 960),
                    PhoneAgentTextElement("个人中心", 900, 960),
                ),
            )

            override suspend fun execute(
                command: PhoneAgentCommand,
                screen: PhoneAgentScreen,
            ): PhoneAgentDeviceResult {
                commands += command
                return PhoneAgentDeviceResult(true, "ok")
            }
        }

        val result = PhoneAgentController(client).run(
            request = PhoneAgentTaskRequest("打开拼多多告诉我当前页面是什么"),
            config = localConfig(),
            device = device,
        )

        assertTrue(result.success)
        assertTrue(device.commands.isEmpty())
        assertTrue(result.trace.any { it.detail?.contains("首页商品流") == true })
        assertTrue(
            client.requests.first().last().content.orEmpty().contains("结构化界面锚点"),
        )
    }

    @Test
    fun readOnlyPinduoduoPageQuestionPreventsClickingTheAlreadySelectedHomeTab() = runBlocking {
        val client = QueueModelClient(
            "看到底部导航首页按钮，准备点击。do(action=\"Tap\", element=[100,960])",
            "finish(message=\"当前是拼多多首页商品流\")",
        )
        val device = object : PhoneAgentDevice {
            val commands = mutableListOf<PhoneAgentCommand>()

            override suspend fun observe(): PhoneAgentScreen = PhoneAgentScreen(
                screenshot = GuiScreenshot(
                    mimeType = "image/png",
                    base64Data = "aG9tZQ==",
                    width = 1000,
                    height = 2000,
                ),
                application = PhoneAgentApps.PINDUODUO_PACKAGE,
                textElements = listOf(
                    PhoneAgentTextElement("首页", 100, 960),
                    PhoneAgentTextElement("多多视频", 300, 960),
                    PhoneAgentTextElement("聊天", 700, 960),
                    PhoneAgentTextElement("个人中心", 900, 960),
                ),
            )

            override suspend fun execute(
                command: PhoneAgentCommand,
                screen: PhoneAgentScreen,
            ): PhoneAgentDeviceResult {
                commands += command
                return PhoneAgentDeviceResult(true, "ok")
            }
        }

        val result = PhoneAgentController(client).run(
            request = PhoneAgentTaskRequest(
                "打开拼多多告诉我当前页面是什么，不要点击任何商品",
            ),
            config = localConfig(),
            device = device,
        )

        assertTrue(result.success)
        assertTrue(device.commands.isEmpty())
        assertTrue(
            result.trace.any {
                it.detail?.contains("只要求识别当前页面") == true && it.action == "Tap"
            },
        )
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

    @Test
    fun remoteModelHardDeadlineReturnsEvenWhenCallIgnoresCoroutineCancellation() = runBlocking {
        val blockingClient = PhoneAgentModelClient { _, _, _ ->
            withContext(Dispatchers.IO) {
                Thread.sleep(800)
                PhoneAgentModelResponse(content = "finish(message=\"太晚了\")")
            }
        }
        lateinit var result: PhoneAgentRunResult

        val elapsed = measureTimeMillis {
            result = PhoneAgentController(
                modelClient = blockingClient,
                modelStepTimeoutMillis = 50L,
            ).run(
                request = PhoneAgentTaskRequest("返回上一页"),
                config = ProviderConfig(
                    activeProviderId = "openai-compatible",
                    openaiBaseUrl = "https://example.test/v1",
                    openaiApiKey = "test-key",
                    openaiModel = "remote-vision-model",
                    phoneAgentAllowRemoteScreenshots = true,
                ),
                device = FakeDevice(),
            )
        }

        assertFalse(result.success)
        assertTrue(result.message.contains("已停止本轮推理"))
        assertTrue(elapsed < 500L, "hard deadline returned after ${elapsed}ms")
    }

    @Test
    fun localModelIsNotKilledByRemoteWallClockDeadline() = runBlocking {
        val slowLocalClient = PhoneAgentModelClient { _, _, _ ->
            delay(90L)
            PhoneAgentModelResponse(content = "finish(message=\"本地推理完成\")")
        }

        val result = PhoneAgentController(
            modelClient = slowLocalClient,
            modelStepTimeoutMillis = 20L,
        ).run(
            request = PhoneAgentTaskRequest("返回上一页"),
            config = localConfig(),
            device = FakeDevice(),
        )

        assertTrue(result.success)
        assertEquals("本地推理完成", result.message)
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
            connection: PhoneAgentConnection,
            onVisibleText: (String) -> Unit,
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

    private class ChangingMessageDevice : PhoneAgentDevice {
        val commands = mutableListOf<PhoneAgentCommand>()
        private var observation = 0

        override suspend fun observe(): PhoneAgentScreen {
            observation++
            val sent = observation >= 4
            return PhoneAgentScreen(
                screenshot = GuiScreenshot(
                    mimeType = "image/png",
                    base64Data = if (sent) "c2VudA==" else "YmVmb3Jl",
                    width = 1000,
                    height = 2000,
                ),
                application = "com.example.chat",
                semanticSummary = buildString {
                    append("text text=\"慕容茹艳\" center=[500,80]\n")
                    append("edit center=[500,950] editable clickable")
                    if (sent) append("\ntext text=\"你好\" center=[750,700]")
                },
            )
        }

        override suspend fun execute(
            command: PhoneAgentCommand,
            screen: PhoneAgentScreen,
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
