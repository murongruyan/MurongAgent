package com.murong.agent.core.tool

import com.murong.agent.core.config.ProjectSubagentTemplate
import com.murong.agent.core.config.ProviderConfig
import com.murong.agent.core.provider.ChatRequest
import com.murong.agent.core.provider.ChatResponse
import com.murong.agent.core.provider.ModelProvider
import com.murong.agent.core.provider.StreamDelta
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SubagentToolTest {

    @Test
    fun execute_backgroundRun_inheritsTemplateModelAndReasoning() = runBlocking {
        val queuedEvents = mutableListOf<SubagentUiEvent.Queued>()
        val scheduledRunIds = mutableListOf<String>()
        val tool = createTool(
            baseConfig = ProviderConfig(
                activeProviderId = "openai-compatible",
                openaiModel = "gpt-5.5",
                openaiReasoningEffort = "medium"
            ),
            projectTemplates = listOf(
                ProjectSubagentTemplate(
                    id = "review-template",
                    title = "代码审查模板",
                    goalMatchers = listOf("审查", "review"),
                    preferredModel = "gpt-5.5-pro",
                    preferredReasoningEffort = "xhigh"
                )
            ),
            scheduleBackgroundExecution = { runId, _ ->
                scheduledRunIds += runId
                "scheduled:$runId"
            },
            onUiEvent = { event ->
                if (event is SubagentUiEvent.Queued) {
                    queuedEvents += event
                }
            }
        )

        val result = tool.execute(
            """
            {
              "goal": "帮我审查这个模块的潜在问题",
              "background": true
            }
            """.trimIndent()
        )

        val queued = queuedEvents.single()
        assertTrue(result.startsWith("scheduled:"))
        assertEquals(1, scheduledRunIds.size)
        assertEquals("review-template", queued.templateId)
        assertEquals("代码审查模板", queued.templateTitle)
        assertEquals("gpt-5.5-pro", queued.model)
        assertEquals("xhigh", queued.reasoningEffort)
        assertEquals(scheduledRunIds.single(), queued.runId)
    }

    @Test
    fun execute_batchGoals_capsExpandedTasksAtSix() = runBlocking {
        val batchQueuedEvents = mutableListOf<SubagentUiEvent.BatchQueued>()
        val queuedEvents = mutableListOf<SubagentUiEvent.Queued>()
        val scheduledRunIds = mutableListOf<String>()
        val tool = createTool(
            baseConfig = ProviderConfig(
                activeProviderId = "deepseek",
                deepseekModel = "deepseek-v4-flash",
                deepseekReasoningEffort = "high"
            ),
            scheduleBackgroundExecution = { runId, _ ->
                scheduledRunIds += runId
                "scheduled:$runId"
            },
            onUiEvent = { event ->
                when (event) {
                    is SubagentUiEvent.BatchQueued -> batchQueuedEvents += event
                    is SubagentUiEvent.Queued -> queuedEvents += event
                    else -> Unit
                }
            }
        )

        val result = tool.execute(
            """
            {
              "goal": "批量处理代码研究",
              "batchGoals": [
                "1. 搜索登录逻辑 2. 搜索登录逻辑 3. 分析会话状态",
                "修复设置页显示；验证 GitHub 回跳；总结风险",
                "补充发布说明"
              ]
            }
            """.trimIndent()
        )

        val batchQueued = batchQueuedEvents.single()
        assertTrue(result.contains("Tasks: 6"))
        assertEquals(6, batchQueued.runIds.size)
        assertEquals(6, queuedEvents.size)
        assertEquals(6, scheduledRunIds.size)
        assertTrue(queuedEvents.all { it.batchSize == 6 })
        assertEquals(
            scheduledRunIds.sorted(),
            queuedEvents.map { it.runId }.sorted()
        )
    }

    @Test
    fun execute_parallelTasks_schedulesDependentsAfterUpstreamCompletion() = runBlocking {
        val queuedEvents = mutableListOf<SubagentUiEvent.Queued>()
        val completedEvents = mutableListOf<SubagentUiEvent.Completed>()
        val scheduledRunIds = mutableListOf<String>()
        val scheduledExecutions = linkedMapOf<String, suspend () -> String>()
        val tool = createTool(
            provider = EchoModelProvider(),
            baseConfig = ProviderConfig(
                activeProviderId = "openai-compatible",
                openaiModel = "gpt-5.5",
                openaiReasoningEffort = "medium"
            ),
            scheduleBackgroundExecution = { runId, executeNow ->
                scheduledRunIds += runId
                scheduledExecutions[runId] = executeNow
                "scheduled:$runId"
            },
            onUiEvent = { event ->
                when (event) {
                    is SubagentUiEvent.Queued -> queuedEvents += event
                    is SubagentUiEvent.Completed -> completedEvents += event
                    else -> Unit
                }
            }
        )

        val result = tool.execute(
            """
            {
              "goal": "并行处理代码研究",
              "parallelTasks": [
                { "label": "discover", "goal": "扫描登录入口" },
                { "label": "review", "goal": "分析状态流转", "dependsOn": [1] },
                { "label": "summarize", "goal": "汇总结论", "dependsOn": [2] }
              ]
            }
            """.trimIndent()
        )

        assertTrue(result.contains("Subagent parallel batch queued"))
        assertEquals(3, queuedEvents.size)
        assertEquals(1, scheduledRunIds.size)

        val firstRunId = queuedEvents.single { it.batchIndex == 1 }.runId
        val secondRunId = queuedEvents.single { it.batchIndex == 2 }.runId
        val thirdRunId = queuedEvents.single { it.batchIndex == 3 }.runId

        scheduledExecutions.getValue(firstRunId).invoke()
        assertEquals(listOf(firstRunId, secondRunId), scheduledRunIds)

        scheduledExecutions.getValue(secondRunId).invoke()
        assertEquals(listOf(firstRunId, secondRunId, thirdRunId), scheduledRunIds)

        scheduledExecutions.getValue(thirdRunId).invoke()
        assertEquals(listOf(firstRunId, secondRunId, thirdRunId), completedEvents.map { it.runId })
    }

    @Test
    fun execute_parallelTasks_rejectsDependencyCycle() = runBlocking {
        val tool = createTool(
            baseConfig = ProviderConfig(
                activeProviderId = "deepseek",
                deepseekModel = "deepseek-v4-flash",
                deepseekReasoningEffort = "high"
            ),
            scheduleBackgroundExecution = { runId, _ -> "scheduled:$runId" },
            onUiEvent = {}
        )

        val result = tool.execute(
            """
            {
              "goal": "循环依赖测试",
              "parallelTasks": [
                { "goal": "任务一", "dependsOn": [2] },
                { "goal": "任务二", "dependsOn": [1] }
              ]
            }
            """.trimIndent()
        )

        assertEquals(
            "Subagent parallel batch ignored: parallelTasks contains a dependency cycle",
            result
        )
    }

    private fun createTool(
        provider: ModelProvider = FakeModelProvider(),
        baseConfig: ProviderConfig,
        projectTemplates: List<ProjectSubagentTemplate> = emptyList(),
        scheduleBackgroundExecution: suspend (String, suspend () -> String) -> String,
        onUiEvent: (SubagentUiEvent) -> Unit
    ): SubagentTool {
        return SubagentTool(
            provider = provider,
            baseConfig = baseConfig,
            projectTemplates = projectTemplates,
            allowedFileOperations = setOf("read", "list", "exists"),
            writableFileOperations = emptySet(),
            allowWebSearchTool = true,
            allowCodeEditTool = false,
            allowShellTool = false,
            mcpRegistry = null,
            isCancellationRequested = { false },
            requestApproval = { false },
            scheduleBackgroundExecution = scheduleBackgroundExecution,
            onUiEvent = onUiEvent
        )
    }

    private class EchoModelProvider : ModelProvider {
        override val name = "Echo"
        override val id = "echo"
        override val defaultBaseUrl = "https://example.invalid"
        override val defaultModel = "echo-model"
        override val supportsReasoning = true

        override suspend fun chatStream(
            request: ChatRequest,
            apiKey: String,
            baseUrl: String?,
            onDelta: (StreamDelta) -> Unit
        ): ChatResponse {
            return ChatResponse(
                content = request.messages.lastOrNull()?.content ?: "empty",
                toolCalls = null
            )
        }

        override suspend fun chat(
            request: ChatRequest,
            apiKey: String,
            baseUrl: String?
        ): ChatResponse {
            error("chat should not be called in SubagentTool dependency scheduling tests")
        }
    }

    private class FakeModelProvider : ModelProvider {
        override val name = "Fake"
        override val id = "fake"
        override val defaultBaseUrl = "https://example.invalid"
        override val defaultModel = "fake-model"
        override val supportsReasoning = true

        override suspend fun chatStream(
            request: ChatRequest,
            apiKey: String,
            baseUrl: String?,
            onDelta: (StreamDelta) -> Unit
        ): ChatResponse {
            error("chatStream should not run in SubagentTool background scheduling tests")
        }

        override suspend fun chat(
            request: ChatRequest,
            apiKey: String,
            baseUrl: String?
        ): ChatResponse {
            error("chat should not run in SubagentTool background scheduling tests")
        }
    }
}
