package dev.reasonix.mobile.core.tool

import dev.reasonix.mobile.core.config.ProjectSubagentTemplate
import dev.reasonix.mobile.core.config.ProviderConfig
import dev.reasonix.mobile.core.provider.ChatRequest
import dev.reasonix.mobile.core.provider.ChatResponse
import dev.reasonix.mobile.core.provider.ModelProvider
import dev.reasonix.mobile.core.provider.StreamDelta
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

    private fun createTool(
        baseConfig: ProviderConfig,
        projectTemplates: List<ProjectSubagentTemplate> = emptyList(),
        scheduleBackgroundExecution: suspend (String, suspend () -> String) -> String,
        onUiEvent: (SubagentUiEvent) -> Unit
    ): SubagentTool {
        return SubagentTool(
            provider = FakeModelProvider(),
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
