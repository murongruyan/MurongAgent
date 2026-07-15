package com.murong.agent.core.loop

import com.murong.agent.core.config.ProviderConfig
import com.murong.agent.core.provider.ChatMessage
import com.murong.agent.core.provider.ChatRequest
import com.murong.agent.core.provider.ChatResponse
import com.murong.agent.core.provider.ModelProvider
import com.murong.agent.core.provider.StreamDelta
import com.murong.agent.core.provider.ToolCall
import com.murong.agent.core.provider.ToolCallFunction
import com.murong.agent.core.tool.CompleteStepTool
import com.murong.agent.core.tool.SessionHistoryToolPayload
import com.murong.agent.core.tool.StepSignOffReceipt
import com.murong.agent.core.tool.Tool
import com.murong.agent.core.tool.ToolExecutionResult
import com.murong.agent.core.tool.ToolRuntimeContext
import com.murong.agent.core.tool.ToolRegistry
import com.murong.agent.core.tool.ToolStructuredPayload
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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

    @Test
    fun processMessage_whenToolOutputIsHuge_onlyTruncatedResultIsFedBackToModel() = runBlocking {
        val hugePrefix = "A".repeat(20_000)
        val omittedMarker = "SECRET-MIDDLE-CONTENT"
        val hugeSuffix = "Z".repeat(20_000)
        val hugeOutput = buildString {
            append(hugePrefix)
            append(omittedMarker)
            append(hugeSuffix)
        }
        val provider = FakeModelProvider(
            responses = ArrayDeque(
                listOf(
                    ChatResponse(
                        content = null,
                        toolCalls = listOf(
                            ToolCall(
                                id = "call-1",
                                function = ToolCallFunction(
                                    name = "huge_tool",
                                    arguments = """{"path":"big.log"}"""
                                )
                            )
                        )
                    ),
                    ChatResponse(content = "done", toolCalls = null)
                )
            )
        )
        val registry = ToolRegistry().apply {
            register(HugeOutputTool(hugeOutput))
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
            userMessage = ChatMessage(role = "user", content = "读取这个超大日志"),
            history = emptyList(),
            onEvent = events::add
        )

        val toolEvent = events.filterIsInstance<AgentEvent.ToolResult>().single()
        assertEquals(hugeOutput, toolEvent.result, "UI/会话侧应继续保留完整工具结果")
        assertTrue(
            toolEvent.modelContextResult.contains("[tool output truncated for model context]"),
            "写回模型的工具结果应带截断标记"
        )
        assertTrue(
            !toolEvent.modelContextResult.contains(omittedMarker),
            "被省略的中段内容不应继续写回模型上下文"
        )

        val secondRequest = provider.requests.getOrNull(1)
        assertNotNull(secondRequest, "第二轮请求应把工具结果写回模型")
        val toolMessage = secondRequest.messages.lastOrNull { it.role == "tool" }
        assertNotNull(toolMessage, "第二轮请求里应存在 tool message")
        assertEquals(toolEvent.modelContextResult, toolMessage.content)
        assertTrue(toolMessage.content?.contains(omittedMarker) == false)
    }

    @Test
    fun processMessage_whenCompleteStepRunsAfterTool_canSeeSameTurnEvidence() = runBlocking {
        val provider = FakeModelProvider(
            responses = ArrayDeque(
                listOf(
                    ChatResponse(
                        content = null,
                        toolCalls = listOf(
                            ToolCall(
                                id = "call-1",
                                function = ToolCallFunction(
                                    name = "shell",
                                    arguments = """{"command":"./gradlew test"}"""
                                )
                            ),
                            ToolCall(
                                id = "call-2",
                                function = ToolCallFunction(
                                    name = "complete_step",
                                    arguments = """
                                    {
                                      "step": "运行测试",
                                      "result": "测试已执行",
                                      "evidence": [
                                        {
                                          "summary": "已运行 gradlew test",
                                          "toolName": "shell",
                                          "command": "./gradlew test"
                                        }
                                      ]
                                    }
                                    """.trimIndent()
                                )
                            )
                        )
                    ),
                    ChatResponse(content = "done", toolCalls = null)
                )
            )
        )
        val registry = ToolRegistry().apply {
            register(FakeShellTool())
            register(CompleteStepTool())
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
            userMessage = ChatMessage(role = "user", content = "先跑测试，再确认这一步完成"),
            history = emptyList(),
            onEvent = events::add
        )

        val toolResults = events.filterIsInstance<AgentEvent.ToolResult>()
        assertEquals(2, toolResults.size)
        assertTrue(toolResults[1].result.contains("Step signed off."), "complete_step 应基于同轮工具收据完成签收")
        assertTrue(toolResults[1].result.contains("matched_evidence=1/1"))
    }

    @Test
    fun processMessage_whenToolExecutionGuardBlocksWriteTool_doesNotExecuteTool() = runBlocking {
        val provider = FakeModelProvider(
            responses = ArrayDeque(
                listOf(
                    ChatResponse(
                        content = null,
                        toolCalls = listOf(
                            ToolCall(
                                id = "call-1",
                                function = ToolCallFunction(
                                    name = "mutating_tool",
                                    arguments = """{"path":"demo.txt"}"""
                                )
                            )
                        )
                    ),
                    ChatResponse(content = "done", toolCalls = null)
                )
            )
        )
        val mutatingTool = CountingTool("mutating_tool", "should not run")
        val registry = ToolRegistry().apply {
            register(mutatingTool)
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
            userMessage = ChatMessage(role = "user", content = "先只做计划，不要真正写文件"),
            history = emptyList(),
            onEvent = events::add,
            toolExecutionGuard = { toolName, _ ->
                if (toolName == "mutating_tool") {
                    "Blocked by planning mode"
                } else {
                    null
                }
            }
        )

        assertEquals(0, mutatingTool.executionCount, "被门控拦截后不应真的执行写工具")
        val toolResult = events.filterIsInstance<AgentEvent.ToolResult>().single()
        assertEquals("Blocked by planning mode", toolResult.result)
    }

    @Test
    fun processMessage_whenPromptExposedButDisabledWriteToolIsCalled_returnsGuardBlockInsteadOfUnknownTool() = runBlocking {
        val provider = FakeModelProvider(
            responses = ArrayDeque(
                listOf(
                    ChatResponse(
                        content = null,
                        toolCalls = listOf(
                            ToolCall(
                                id = "call-1",
                                function = ToolCallFunction(
                                    name = "mutating_tool",
                                    arguments = """{"path":"demo.txt"}"""
                                )
                            )
                        )
                    ),
                    ChatResponse(content = "done", toolCalls = null)
                )
            )
        )
        val mutatingTool = CountingTool("mutating_tool", "should not run")
        val registry = ToolRegistry().apply {
            register(
                tool = mutatingTool,
                isEnabled = { false },
                isPromptExposed = { true }
            )
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
            userMessage = ChatMessage(role = "user", content = "先规划"),
            history = emptyList(),
            onEvent = events::add,
            toolExecutionGuard = { toolName, _ ->
                if (toolName == "mutating_tool") {
                    "Blocked by planning mode"
                } else {
                    null
                }
            }
        )

        assertTrue(provider.requests.first().tools.orEmpty().contains("mutating_tool"))
        val toolResult = events.filterIsInstance<AgentEvent.ToolResult>().single()
        assertEquals("Blocked by planning mode", toolResult.result)
        assertEquals(0, mutatingTool.executionCount)
    }

    @Test
    fun processMessage_whenHighRiskRemoteWriteFails_retriesWithReadOnlyInspectionInsteadOfStoppingImmediately() = runBlocking {
        val provider = FakeModelProvider(
            responses = ArrayDeque(
                listOf(
                    ChatResponse(
                        content = null,
                        toolCalls = listOf(
                            ToolCall(
                                id = "call-1",
                                function = ToolCallFunction(
                                    name = "task_repo_update_file",
                                    arguments = """{"path":"ChatScreen.kt"}"""
                                )
                            ),
                            ToolCall(
                                id = "call-2",
                                function = ToolCallFunction(
                                    name = "followup_tool",
                                    arguments = """{"path":"should-not-run"}"""
                                )
                            )
                        )
                    )
                    ,
                    ChatResponse(content = "我先读取远端状态，再决定下一步。", toolCalls = null)
                )
            )
        )
        val followupTool = CountingTool("followup_tool", "should not run")
        val registry = ToolRegistry().apply {
            register(
                StaticOutputTool(
                    name = "task_repo_update_file",
                    output = "Error: 写后校验失败，expectedLength=2400, actualLength=1200"
                )
            )
            register(followupTool)
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
            userMessage = ChatMessage(role = "user", content = "更新远端文件"),
            history = emptyList(),
            onEvent = events::add
        )

        assertEquals(0, followupTool.executionCount, "高风险远端写失败后不应继续执行后续工具")
        assertEquals(2, provider.callCount, "写后校验异常后应再给模型一次只读排查机会")
        val retryRequest = provider.requests.getOrNull(1)
        assertNotNull(retryRequest, "应存在携带远端写保护提醒的重试请求")
        val reminder = retryRequest.messages.lastOrNull { it.role == "system" }?.content.orEmpty()
        assertTrue(reminder.contains("Remote Write Guard"))
        assertTrue(reminder.contains("task_repo_read_file"))
        assertTrue(events.filterIsInstance<AgentEvent.Error>().isEmpty(), "首轮高风险远端写失败后不应立刻把错误抛给用户")
        assertTrue(events.last() is AgentEvent.Done)
    }

    @Test
    fun processMessage_whenCompleteStepReferencesBlockedWriteToolEvidence_itIsRejected() = runBlocking {
        val provider = FakeModelProvider(
            responses = ArrayDeque(
                listOf(
                    ChatResponse(
                        content = null,
                        toolCalls = listOf(
                            ToolCall(
                                id = "call-1",
                                function = ToolCallFunction(
                                    name = "mutating_tool",
                                    arguments = """{"path":"demo.txt"}"""
                                )
                            ),
                            ToolCall(
                                id = "call-2",
                                function = ToolCallFunction(
                                    name = "complete_step",
                                    arguments = """
                                    {
                                      "step": "修改文件",
                                      "result": "文件已修改",
                                      "evidence": [
                                        {
                                          "summary": "已写入 demo.txt",
                                          "toolName": "mutating_tool",
                                          "path": "demo.txt"
                                        }
                                      ]
                                    }
                                    """.trimIndent()
                                )
                            )
                        )
                    ),
                    ChatResponse(content = "done", toolCalls = null)
                )
            )
        )
        val mutatingTool = CountingTool("mutating_tool", "should not run")
        val registry = ToolRegistry().apply {
            register(mutatingTool)
            register(CompleteStepTool())
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
            userMessage = ChatMessage(role = "user", content = "现在先规划，不要真的写文件"),
            history = emptyList(),
            onEvent = events::add,
            toolExecutionGuard = { toolName, _ ->
                if (toolName == "mutating_tool") "Blocked by planning mode" else null
            }
        )

        assertEquals(0, mutatingTool.executionCount)
        val toolResults = events.filterIsInstance<AgentEvent.ToolResult>()
        assertEquals(2, toolResults.size)
        assertEquals("Blocked by planning mode", toolResults[0].result)
        assertTrue(
            toolResults[1].result.startsWith("Error:"),
            "当证据对应的写工具实际上被拦截时，complete_step 不应假签收"
        )
        assertTrue(toolResults[1].result.contains("没有匹配到真实工具收据"))
    }

    @Test
    fun processMessage_whenSuccessfulWriteToolRepeatsWithSameArgs_thirdCallIsBlockedByRepeatGuard() = runBlocking {
        val provider = FakeModelProvider(
            responses = ArrayDeque(
                listOf(
                    ChatResponse(
                        content = null,
                        toolCalls = listOf(
                            ToolCall(
                                id = "call-1",
                                function = ToolCallFunction(
                                    name = "mutating_tool",
                                    arguments = """{"path":"demo.txt","content":"hello"}"""
                                )
                            )
                        )
                    ),
                    ChatResponse(
                        content = null,
                        toolCalls = listOf(
                            ToolCall(
                                id = "call-2",
                                function = ToolCallFunction(
                                    name = "mutating_tool",
                                    arguments = """{"path":"demo.txt","content":"hello"}"""
                                )
                            )
                        )
                    ),
                    ChatResponse(
                        content = null,
                        toolCalls = listOf(
                            ToolCall(
                                id = "call-3",
                                function = ToolCallFunction(
                                    name = "mutating_tool",
                                    arguments = """{"path":"demo.txt","content":"hello"}"""
                                )
                            )
                        )
                    ),
                    ChatResponse(content = "done", toolCalls = null)
                )
            )
        )
        val mutatingTool = CountingTool("mutating_tool", "write succeeded")
        val registry = ToolRegistry().apply {
            register(mutatingTool)
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
            userMessage = ChatMessage(role = "user", content = "连续改三次同一个文件"),
            history = emptyList(),
            onEvent = events::add
        )

        assertEquals(2, mutatingTool.executionCount, "第三次完全相同的成功写工具调用应被 repeat guard 拦住")
        val toolResults = events.filterIsInstance<AgentEvent.ToolResult>()
        assertEquals(3, toolResults.size)
        assertEquals("write succeeded", toolResults[0].result)
        assertEquals("write succeeded", toolResults[1].result)
        assertTrue(toolResults[2].result.startsWith("Blocked by repeat guard:"))
        assertTrue(toolResults[2].result.contains("mutating_tool"))
    }

    @Test
    fun processMessage_whenReadOnlyToolRepeatsWithSameArgs_itIsNotBlockedByRepeatGuard() = runBlocking {
        val provider = FakeModelProvider(
            responses = ArrayDeque(
                listOf(
                    ChatResponse(
                        content = null,
                        toolCalls = listOf(
                            ToolCall(
                                id = "call-1",
                                function = ToolCallFunction(
                                    name = "read_tool",
                                    arguments = """{"path":"demo.txt"}"""
                                )
                            )
                        )
                    ),
                    ChatResponse(
                        content = null,
                        toolCalls = listOf(
                            ToolCall(
                                id = "call-2",
                                function = ToolCallFunction(
                                    name = "read_tool",
                                    arguments = """{"path":"demo.txt"}"""
                                )
                            )
                        )
                    ),
                    ChatResponse(
                        content = null,
                        toolCalls = listOf(
                            ToolCall(
                                id = "call-3",
                                function = ToolCallFunction(
                                    name = "read_tool",
                                    arguments = """{"path":"demo.txt"}"""
                                )
                            )
                        )
                    ),
                    ChatResponse(content = "done", toolCalls = null)
                )
            )
        )
        val readTool = CountingTool("read_tool", "read succeeded")
        val registry = ToolRegistry().apply {
            register(readTool)
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
            userMessage = ChatMessage(role = "user", content = "连续读三次同一个文件"),
            history = emptyList(),
            onEvent = events::add
        )

        assertEquals(3, readTool.executionCount, "读工具不应被 repeat guard 误伤")
        val toolResults = events.filterIsInstance<AgentEvent.ToolResult>()
        assertEquals(3, toolResults.size)
        assertTrue(toolResults.none { it.result.startsWith("Blocked by repeat guard:") })
    }

    @Test
    fun processMessage_whenWriteToolHasNoCompleteStep_finalReadinessRetriesBeforeAllowingFinish() = runBlocking {
        val provider = FakeModelProvider(
            responses = ArrayDeque(
                listOf(
                    ChatResponse(
                        content = null,
                        toolCalls = listOf(
                            ToolCall(
                                id = "call-1",
                                function = ToolCallFunction(
                                    name = "mutating_tool",
                                    arguments = """{"path":"demo.txt","content":"hello"}"""
                                )
                            )
                        )
                    ),
                    ChatResponse(content = "done too early", toolCalls = null),
                    ChatResponse(
                        content = null,
                        toolCalls = listOf(
                            ToolCall(
                                id = "call-2",
                                function = ToolCallFunction(
                                    name = "complete_step",
                                    arguments = """
                                    {
                                      "step": "修改文件",
                                      "result": "demo.txt 已更新",
                                      "evidence": [
                                        {
                                          "summary": "已写入 demo.txt",
                                          "toolName": "mutating_tool",
                                          "path": "demo.txt"
                                        }
                                      ]
                                    }
                                    """.trimIndent()
                                )
                            )
                        )
                    ),
                    ChatResponse(content = "now done", toolCalls = null)
                )
            )
        )
        val registry = ToolRegistry().apply {
            register(CountingTool("mutating_tool", "write succeeded"))
            register(CompleteStepTool())
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
            userMessage = ChatMessage(role = "user", content = "改完文件后再结束"),
            history = emptyList(),
            onEvent = events::add
        )

        assertEquals(4, provider.callCount, "缺少 complete_step 时应先触发 final readiness 重试，再允许结束")
        val thirdRequest = provider.requests.getOrNull(2)
        assertNotNull(thirdRequest, "应有带 final readiness 提醒的补充请求")
        val reminder = thirdRequest.messages.lastOrNull { it.role == "system" }?.content.orEmpty()
        assertTrue(reminder.contains("Final Readiness Gate"))
        assertTrue(reminder.contains("complete_step"))
        val toolResults = events.filterIsInstance<AgentEvent.ToolResult>()
        assertEquals(2, toolResults.size)
        assertTrue(toolResults[1].result.contains("Step signed off."))
        assertTrue(events.filterIsInstance<AgentEvent.Error>().isEmpty(), "补签收成功后不应报错")
        val audits = events.filterIsInstance<AgentEvent.ReadinessAudit>().map(AgentEvent.ReadinessAudit::audit)
        assertEquals(2, audits.size, "final readiness 应先记录 blocked，再在补签收后记录 recovered")
        assertEquals(FinalReadinessAuditResult.BLOCKED, audits[0].result)
        assertEquals(false, audits[0].recovered)
        assertEquals(FinalReadinessReceiptKind.MISSING_COMPLETE_STEP_AFTER_WRITE, audits[0].receiptKind)
        assertEquals(FinalReadinessAuditResult.ALLOWED, audits[1].result)
        assertEquals(true, audits[1].recovered)
        assertEquals(FinalReadinessReceiptKind.MISSING_COMPLETE_STEP_AFTER_WRITE, audits[1].receiptKind)
    }

    @Test
    fun processMessage_whenCompleteStepProvidesStructuredReceipt_finalReadinessDoesNotDependOnLegacyText() = runBlocking {
        val provider = FakeModelProvider(
            responses = ArrayDeque(
                listOf(
                    ChatResponse(
                        content = null,
                        toolCalls = listOf(
                            ToolCall(
                                id = "call-1",
                                function = ToolCallFunction(
                                    name = "mutating_tool",
                                    arguments = """{"path":"demo.txt","content":"hello"}"""
                                )
                            )
                        )
                    ),
                    ChatResponse(content = "done too early", toolCalls = null),
                    ChatResponse(
                        content = null,
                        toolCalls = listOf(
                            ToolCall(
                                id = "call-2",
                                function = ToolCallFunction(
                                    name = "complete_step",
                                    arguments = """{"step":"修改文件"}"""
                                )
                            )
                        )
                    ),
                    ChatResponse(content = "now done", toolCalls = null)
                )
            )
        )
        val registry = ToolRegistry().apply {
            register(CountingTool("mutating_tool", "write succeeded"))
            register(StructuredCompleteStepTool())
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
            userMessage = ChatMessage(role = "user", content = "改完文件后再结束"),
            history = emptyList(),
            onEvent = events::add
        )

        assertEquals(4, provider.callCount)
        assertTrue(events.filterIsInstance<AgentEvent.Error>().isEmpty(), "结构化签收成功后不应再被 final readiness 拦截")
        val toolResults = events.filterIsInstance<AgentEvent.ToolResult>()
        assertEquals("structured sign-off receipt", toolResults[1].result)
    }

    @Test
    fun processMessage_whenWriteToolStillHasNoCompleteStepAfterReminder_stopsWithFinalReadinessError() = runBlocking {
        val provider = FakeModelProvider(
            responses = ArrayDeque(
                listOf(
                    ChatResponse(
                        content = null,
                        toolCalls = listOf(
                            ToolCall(
                                id = "call-1",
                                function = ToolCallFunction(
                                    name = "mutating_tool",
                                    arguments = """{"path":"demo.txt","content":"hello"}"""
                                )
                            )
                        )
                    ),
                    ChatResponse(content = "done too early", toolCalls = null),
                    ChatResponse(content = "still done too early", toolCalls = null)
                )
            )
        )
        val mutatingTool = CountingTool("mutating_tool", "write succeeded")
        val registry = ToolRegistry().apply {
            register(mutatingTool)
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
            userMessage = ChatMessage(role = "user", content = "修改完别忘了签收"),
            history = emptyList(),
            onEvent = events::add
        )

        assertEquals(1, mutatingTool.executionCount)
        assertEquals(3, provider.callCount, "提醒一次后若仍缺签收，应直接停止")
        val errorEvent = events.filterIsInstance<AgentEvent.Error>().lastOrNull()
        assertNotNull(errorEvent, "最终收口失败时应发错误事件")
        assertTrue(errorEvent.message.contains("complete_step"))
        assertTrue(errorEvent.message.contains("已成功执行"))
        assertNotNull(errorEvent.userVisibleMessage)
        assertTrue(errorEvent.userVisibleMessage.contains("当前执行已暂停"))
        assertTrue(!errorEvent.userVisibleMessage.contains("complete_step"))
        assertEquals(
            FinalReadinessReceiptKind.MISSING_COMPLETE_STEP_AFTER_WRITE,
            errorEvent.finalReadinessReceipt?.kind
        )
        val audits = events.filterIsInstance<AgentEvent.ReadinessAudit>().map(AgentEvent.ReadinessAudit::audit)
        assertEquals(1, audits.size, "提醒后仍失败时只应记录一次 blocked audit")
        assertEquals(FinalReadinessAuditResult.BLOCKED, audits.single().result)
        assertEquals(false, audits.single().recovered)
        assertEquals(FinalReadinessReceiptKind.MISSING_COMPLETE_STEP_AFTER_WRITE, audits.single().receiptKind)
        assertTrue(events.last() is AgentEvent.Done)
    }

    @Test
    fun processMessage_whenCanonicalWorkflowGuardFailsAfterReadOnlyTool_retriesThenStops() = runBlocking {
        val provider = FakeModelProvider(
            responses = ArrayDeque(
                listOf(
                    ChatResponse(
                        content = null,
                        toolCalls = listOf(
                            ToolCall(
                                id = "call-1",
                                function = ToolCallFunction(
                                    name = "read_tool",
                                    arguments = """{"path":"demo.txt"}"""
                                )
                            )
                        )
                    ),
                    ChatResponse(content = "先这样，结束", toolCalls = null),
                    ChatResponse(content = "还是结束", toolCalls = null)
                )
            )
        )
        val registry = ToolRegistry().apply {
            register(CountingTool("read_tool", "read succeeded"))
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
            userMessage = ChatMessage(role = "user", content = "继续同一份计划"),
            history = emptyList(),
            onEvent = events::add,
            finalReadinessGuard = {
                FinalReadinessReceipt(
                    kind = FinalReadinessReceiptKind.INCOMPLETE_CANONICAL_WORKFLOW,
                    requiredAction = FinalReadinessRequiredAction.COMPLETE_REMAINING_PLAN,
                    message = "跨轮次计划仍未完成：当前目标仍绑定到计划 `修复发布流程`，还剩 1 个未签收步骤。",
                    remainingUnsignedSteps = 1,
                    nextRequiredStep = "验证发布"
                )
            }
        )

        assertEquals(3, provider.callCount)
        val retryRequest = provider.requests.getOrNull(2)
        assertNotNull(retryRequest)
        val reminder = retryRequest.messages.lastOrNull { it.role == "system" }?.content.orEmpty()
        assertTrue(reminder.contains("Final Readiness Gate"))
        assertTrue(reminder.contains("跨轮次计划仍未完成"))
        val errorEvent = events.filterIsInstance<AgentEvent.Error>().lastOrNull()
        assertNotNull(errorEvent)
        assertTrue(errorEvent.message.contains("跨轮次计划仍未完成"))
        assertNotNull(errorEvent.userVisibleMessage)
        assertTrue(errorEvent.userVisibleMessage.contains("当前执行已暂停"))
        assertEquals(
            FinalReadinessReceiptKind.INCOMPLETE_CANONICAL_WORKFLOW,
            errorEvent.finalReadinessReceipt?.kind
        )
    }

    @Test
    fun processMessage_whenPersistentFinalReadinessExistsWithoutCurrentTools_retriesThenStops() = runBlocking {
        val provider = FakeModelProvider(
            responses = ArrayDeque(
                listOf(
                    ChatResponse(content = "done too early", toolCalls = null),
                    ChatResponse(content = "still done too early", toolCalls = null)
                )
            )
        )
        val loop = AgentLoop(
            provider = provider,
            toolRegistry = ToolRegistry(),
            config = ProviderConfig(
                activeProviderId = "openai-compatible",
                openaiModel = "fake-model"
            )
        )
        val events = mutableListOf<AgentEvent>()

        loop.processMessage(
            userMessage = ChatMessage(role = "user", content = "继续收口"),
            history = emptyList(),
            onEvent = events::add,
            finalReadinessGuard = {
                FinalReadinessReceipt(
                    kind = FinalReadinessReceiptKind.MISSING_COMPLETE_STEP_AFTER_WRITE,
                    requiredAction = FinalReadinessRequiredAction.SIGN_OFF_WITH_EVIDENCE,
                    message = "最终收口校验未通过：写工具 `mutating_tool` 已成功执行，但之后还没有成功且关联到该写工具的 `complete_step` 签收记录。",
                    latestSuccessfulWriteToolName = "mutating_tool"
                )
            },
            enforceFinalReadinessWithoutCurrentToolRuns = true
        )

        assertEquals(2, provider.callCount, "跨轮仍有未完成签收时，即使本轮没跑工具也应继续 gate")
        val retryRequest = provider.requests.getOrNull(1)
        assertNotNull(retryRequest)
        val reminder = retryRequest.messages.lastOrNull { it.role == "system" }?.content.orEmpty()
        assertTrue(reminder.contains("Final Readiness Gate"))
        assertTrue(reminder.contains("complete_step"))
        val errorEvent = events.filterIsInstance<AgentEvent.Error>().lastOrNull()
        assertNotNull(errorEvent)
        assertNotNull(errorEvent.userVisibleMessage)
        assertTrue(errorEvent.userVisibleMessage.contains("当前执行已暂停"))
        assertEquals(
            FinalReadinessReceiptKind.MISSING_COMPLETE_STEP_AFTER_WRITE,
            errorEvent.finalReadinessReceipt?.kind
        )
    }

    @Test
    fun processMessage_whenPersistentFinalReadinessIsClearedByCompleteStep_allowsFinishAfterReminder() = runBlocking {
        val provider = FakeModelProvider(
            responses = ArrayDeque(
                listOf(
                    ChatResponse(content = "done too early", toolCalls = null),
                    ChatResponse(
                        content = null,
                        toolCalls = listOf(
                            ToolCall(
                                id = "call-1",
                                function = ToolCallFunction(
                                    name = "complete_step",
                                    arguments = """{"step":"修改文件"}"""
                                )
                            )
                        )
                    ),
                    ChatResponse(content = "now done", toolCalls = null)
                )
            )
        )
        var persistentReceiptCleared = false
        val registry = ToolRegistry().apply {
            register(
                object : Tool {
                    override val name: String = "complete_step"
                    override val description: String = "complete step"
                    override val parameters: Map<String, Any> = emptyMap()

                    override suspend fun execute(args: String): String = "structured sign-off receipt"

                    override suspend fun executeWithResult(args: String): ToolExecutionResult {
                        persistentReceiptCleared = true
                        return ToolExecutionResult(
                            output = "structured sign-off receipt",
                            stepSignOffReceipt = StepSignOffReceipt(
                                reportedStep = "修改文件",
                                resultSummary = "demo.txt 已更新",
                                matchedEvidenceCount = 1,
                                totalEvidenceCount = 1,
                                matchedToolNames = listOf("mutating_tool"),
                                signOffTimestamp = 123L
                            )
                        )
                    }
                }
            )
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
            userMessage = ChatMessage(role = "user", content = "继续收口"),
            history = emptyList(),
            onEvent = events::add,
            finalReadinessGuard = {
                if (persistentReceiptCleared) {
                    null
                } else {
                    FinalReadinessReceipt(
                        kind = FinalReadinessReceiptKind.MISSING_COMPLETE_STEP_AFTER_WRITE,
                        requiredAction = FinalReadinessRequiredAction.SIGN_OFF_WITH_EVIDENCE,
                        message = "最终收口校验未通过：写工具 `mutating_tool` 已成功执行，但之后还没有成功且关联到该写工具的 `complete_step` 签收记录。",
                        latestSuccessfulWriteToolName = "mutating_tool"
                    )
                }
            },
            enforceFinalReadinessWithoutCurrentToolRuns = true
        )

        assertEquals(3, provider.callCount, "跨轮未签收应先提醒一次，补签收后可直接结束")
        val retryRequest = provider.requests.getOrNull(1)
        assertNotNull(retryRequest)
        val reminder = retryRequest.messages.lastOrNull { it.role == "system" }?.content.orEmpty()
        assertTrue(reminder.contains("Final Readiness Gate"))
        assertTrue(reminder.contains("complete_step"))
        assertTrue(events.filterIsInstance<AgentEvent.Error>().isEmpty(), "补签收成功后不应继续报 final readiness 错误")
        val toolResults = events.filterIsInstance<AgentEvent.ToolResult>()
        assertEquals(1, toolResults.size)
        assertEquals("structured sign-off receipt", toolResults.single().result)
        assertTrue(events.last() is AgentEvent.Done)
    }

    @Test
    fun processMessage_whenFinalReadinessStillFailsAfterReminderAndUnrelatedTool_stopsWithoutSecondReminder() = runBlocking {
        val provider = FakeModelProvider(
            responses = ArrayDeque(
                listOf(
                    ChatResponse(
                        content = null,
                        toolCalls = listOf(
                            ToolCall(
                                id = "call-1",
                                function = ToolCallFunction(
                                    name = "mutating_tool",
                                    arguments = """{"path":"demo.txt","content":"hello"}"""
                                )
                            )
                        )
                    ),
                    ChatResponse(content = "done too early", toolCalls = null),
                    ChatResponse(
                        content = null,
                        toolCalls = listOf(
                            ToolCall(
                                id = "call-2",
                                function = ToolCallFunction(
                                    name = "read_tool",
                                    arguments = """{"path":"demo.txt"}"""
                                )
                            )
                        )
                    ),
                    ChatResponse(content = "still done too early", toolCalls = null)
                )
            )
        )
        val registry = ToolRegistry().apply {
            register(CountingTool("mutating_tool", "write succeeded"))
            register(CountingTool("read_tool", "read succeeded"))
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
            userMessage = ChatMessage(role = "user", content = "改完后继续确认"),
            history = emptyList(),
            onEvent = events::add
        )

        assertEquals(4, provider.callCount, "提醒后即使执行了无关工具，也不应再获得第二次 final readiness 提醒")
        val finalRequest = provider.requests.getOrNull(3)
        assertNotNull(finalRequest)
        val finalSystemMessages = finalRequest.messages.filter { it.role == "system" }
        assertEquals(1, finalSystemMessages.count { it.content.orEmpty().contains("Final Readiness Gate") })
        val errorEvent = events.filterIsInstance<AgentEvent.Error>().lastOrNull()
        assertNotNull(errorEvent)
        assertEquals(
            FinalReadinessReceiptKind.MISSING_COMPLETE_STEP_AFTER_WRITE,
            errorEvent.finalReadinessReceipt?.kind
        )
    }

    @Test
    fun processMessage_whenSessionHistoryToolRuns_followupToolCanReadStructuredPayloadFromSameTurnReceipt() = runBlocking {
        val provider = FakeModelProvider(
            responses = ArrayDeque(
                listOf(
                    ChatResponse(
                        content = null,
                        toolCalls = listOf(
                            ToolCall(
                                id = "call-1",
                                function = ToolCallFunction(
                                    name = "session_history_search",
                                    arguments = """{"query":"登录"}"""
                                )
                            ),
                            ToolCall(
                                id = "call-2",
                                function = ToolCallFunction(
                                    name = "inspect_history_payload",
                                    arguments = """{}"""
                                )
                            )
                        )
                    ),
                    ChatResponse(content = "done", toolCalls = null)
                )
            )
        )
        val registry = ToolRegistry().apply {
            register(FakeSessionHistoryStructuredTool())
            register(HistoryPayloadInspectorTool())
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
            userMessage = ChatMessage(role = "user", content = "回看历史登录问题"),
            history = emptyList(),
            onEvent = events::add
        )

        val toolResults = events.filterIsInstance<AgentEvent.ToolResult>()
        assertEquals(2, toolResults.size)
        assertEquals(
            listOf("session-login#21"),
            toolResults.first().structuredPayload?.sessionHistory?.messageReferences
        )
        assertTrue(
            toolResults[1].result.contains("session_history_refs=session-login#21"),
            "同轮后续工具应能从 runtime receipt 直接读取 session history 的结构化引用"
        )
    }

    @Test
    fun processMessage_whenCompleteStepReferencesSessionHistoryPayload_itSignsOffFromSameTurnReceipt() = runBlocking {
        val provider = FakeModelProvider(
            responses = ArrayDeque(
                listOf(
                    ChatResponse(
                        content = null,
                        toolCalls = listOf(
                            ToolCall(
                                id = "call-1",
                                function = ToolCallFunction(
                                    name = "session_history_search",
                                    arguments = """{"query":"登录"}"""
                                )
                            ),
                            ToolCall(
                                id = "call-2",
                                function = ToolCallFunction(
                                    name = "complete_step",
                                    arguments = """
                                    {
                                      "step": "复用历史登录经验",
                                      "result": "已确认可复用历史登录案例",
                                      "evidence": [
                                        {
                                          "summary": "已命中历史登录记录",
                                          "toolName": "session_history_search",
                                          "message_reference": "session-login#21"
                                        }
                                      ]
                                    }
                                    """.trimIndent()
                                )
                            )
                        )
                    ),
                    ChatResponse(content = "done", toolCalls = null)
                )
            )
        )
        val registry = ToolRegistry().apply {
            register(FakeSessionHistoryStructuredTool())
            register(CompleteStepTool())
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
            userMessage = ChatMessage(role = "user", content = "回看历史登录问题并完成这一步"),
            history = emptyList(),
            onEvent = events::add
        )

        val toolResults = events.filterIsInstance<AgentEvent.ToolResult>()
        assertEquals(2, toolResults.size)
        assertEquals(
            listOf("session-login#21"),
            toolResults.first().structuredPayload?.sessionHistory?.messageReferences
        )
        assertTrue(toolResults[1].result.contains("Step signed off."))
        assertTrue(toolResults[1].result.contains("matched_tools=session_history_search"))
        assertTrue(toolResults[1].result.contains("matched_evidence=1/1"))
    }

    @Test
    fun processMessage_whenHookBusMutatesToolContext_appliesPreAndPostToolHooks() = runBlocking {
        val provider = FakeModelProvider(
            responses = ArrayDeque(
                listOf(
                    ChatResponse(
                        content = null,
                        toolCalls = listOf(
                            ToolCall(
                                id = "call-1",
                                function = ToolCallFunction(
                                    name = "echo_tool",
                                    arguments = """{"query":"before"}"""
                                )
                            )
                        )
                    ),
                    ChatResponse(content = "done", toolCalls = null)
                )
            )
        )
        val echoTool = EchoArgsTool()
        val registry = ToolRegistry().apply {
            register(echoTool)
        }
        val loop = AgentLoop(
            provider = provider,
            toolRegistry = registry,
            config = ProviderConfig(
                activeProviderId = "openai-compatible",
                openaiModel = "fake-model"
            ),
            hookBus = HookBusRunner(
                observers = listOf(
                    object : HookBusObserver {
                        override fun onPreToolUse(context: PreToolUseHookContext): PreToolUseHookContext {
                            return context.copy(args = """{"query":"after"}""")
                        }

                        override fun onPostToolUse(context: PostToolUseHookContext): PostToolUseHookContext {
                            return context.copy(
                                result = context.result + " [post-hook]",
                                modelContextResult = context.modelContextResult + " [post-hook]"
                            )
                        }
                    }
                )
            )
        )
        val events = mutableListOf<AgentEvent>()

        loop.processMessage(
            userMessage = ChatMessage(role = "user", content = "调用 echo_tool"),
            history = emptyList(),
            onEvent = events::add
        )

        val toolExecution = events.filterIsInstance<AgentEvent.ToolExecution>().single()
        val toolResult = events.filterIsInstance<AgentEvent.ToolResult>().single()
        assertEquals("""{"query":"after"}""", toolExecution.args)
        assertEquals("""{"query":"after"}""", toolResult.args)
        assertEquals("""echo:{"query":"after"} [post-hook]""", toolResult.result)
        assertEquals("""echo:{"query":"after"} [post-hook]""", toolResult.modelContextResult)
        assertEquals("""{"query":"after"}""", echoTool.lastArgs)
        val secondRequest = provider.requests.getOrNull(1)
        assertNotNull(secondRequest)
        val toolMessage = secondRequest.messages.lastOrNull { it.role == "tool" }
        assertNotNull(toolMessage)
        assertEquals("""echo:{"query":"after"} [post-hook]""", toolMessage.content)
    }

    private class FakeTool : Tool {
        override val name: String = "fake_tool"
        override val description: String = "fake"
        override val parameters: Map<String, Any> = emptyMap()

        override suspend fun execute(args: String): String {
            return "已定位到 2 处匹配，建议继续读取附近 20 行上下文。"
        }
    }

    private class HugeOutputTool(
        private val output: String
    ) : Tool {
        override val name: String = "huge_tool"
        override val description: String = "huge"
        override val parameters: Map<String, Any> = emptyMap()

        override suspend fun execute(args: String): String = output
    }

    private class FakeShellTool : Tool {
        override val name: String = "shell"
        override val description: String = "shell"
        override val parameters: Map<String, Any> = emptyMap()

        override suspend fun execute(args: String): String {
            return "Command completed successfully: ./gradlew test"
        }
    }

    private class StructuredCompleteStepTool : Tool {
        override val name: String = "complete_step"
        override val description: String = "complete step"
        override val parameters: Map<String, Any> = emptyMap()

        override suspend fun execute(args: String): String = "structured sign-off receipt"

        override suspend fun executeWithResult(args: String): ToolExecutionResult {
            return ToolExecutionResult(
                output = "structured sign-off receipt",
                stepSignOffReceipt = StepSignOffReceipt(
                    reportedStep = "修改文件",
                    resultSummary = "demo.txt 已更新",
                    matchedEvidenceCount = 1,
                    totalEvidenceCount = 1,
                    matchedToolNames = listOf("mutating_tool"),
                    signOffTimestamp = 123L
                )
            )
        }
    }

    private class FakeSessionHistoryStructuredTool : Tool {
        override val name: String = "session_history_search"
        override val description: String = "session history"
        override val parameters: Map<String, Any> = emptyMap()

        override suspend fun execute(args: String): String = "history output"

        override suspend fun executeWithResult(args: String): ToolExecutionResult {
            return ToolExecutionResult(
                output = "历史会话命中 1 条",
                structuredPayload = ToolStructuredPayload(
                    sessionHistory = SessionHistoryToolPayload(
                        kind = "search",
                        query = "登录",
                        sessionIds = listOf("session-login"),
                        messageReferences = listOf("session-login#21"),
                        anchorMessageIds = listOf(21L),
                        matchedFields = listOf("消息正文")
                    )
                )
            )
        }
    }

    private class HistoryPayloadInspectorTool : Tool {
        override val name: String = "inspect_history_payload"
        override val description: String = "inspect history payload"
        override val parameters: Map<String, Any> = emptyMap()

        override suspend fun execute(args: String): String = "missing runtime context"

        override suspend fun executeWithContext(
            args: String,
            runtimeContext: ToolRuntimeContext
        ): ToolExecutionResult {
            val refs = runtimeContext.currentTurnToolReceipts
                .mapNotNull { it.structuredPayload?.sessionHistory }
                .flatMap { it.messageReferences }
                .distinct()
            return ToolExecutionResult(
                output = "session_history_refs=${refs.joinToString(",")}"
            )
        }
    }

    private class CountingTool(
        override val name: String,
        private val output: String
    ) : Tool {
        override val description: String = "counting"
        override val parameters: Map<String, Any> = emptyMap()
        var executionCount: Int = 0
            private set

        override suspend fun execute(args: String): String {
            executionCount++
            return output
        }
    }

    private class StaticOutputTool(
        override val name: String,
        private val output: String
    ) : Tool {
        override val description: String = "static"
        override val parameters: Map<String, Any> = emptyMap()

        override suspend fun execute(args: String): String = output
    }

    private class EchoArgsTool : Tool {
        override val name: String = "echo_tool"
        override val description: String = "echo"
        override val parameters: Map<String, Any> = emptyMap()
        var lastArgs: String? = null
            private set

        override suspend fun execute(args: String): String {
            lastArgs = args
            return "echo:$args"
        }
    }

    private class FakeModelProvider(
        private val responses: ArrayDeque<ChatResponse>
    ) : ModelProvider {
        var callCount: Int = 0
            private set
        val requests = mutableListOf<ChatRequest>()

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
            requests += request
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
