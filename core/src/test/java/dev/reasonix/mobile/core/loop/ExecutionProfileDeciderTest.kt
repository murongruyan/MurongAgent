package dev.reasonix.mobile.core.loop

import dev.reasonix.mobile.core.config.ProviderConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExecutionProfileDeciderTest {

    @Test
    fun isComplexTask_detectsLongAndKeywordHeavyRequests() {
        assertTrue(
            ExecutionProfileDecider.isComplexTask(
                goal = "请帮我分析这个模块的架构问题并调试登录流程，然后审查会话切换逻辑有没有潜在 bug",
                mentionedFileCount = 0
            )
        )
        assertTrue(
            ExecutionProfileDecider.isComplexTask(
                goal = "第一步定位问题\n第二步分析原因\n第三步给出修复方案",
                mentionedFileCount = 0
            )
        )
        assertFalse(ExecutionProfileDecider.isComplexTask("帮我改个标题", 0))
    }

    @Test
    fun resolveExecutionConfig_upgradesDeepSeekComplexTask() {
        val baseConfig = ProviderConfig(
            activeProviderId = "deepseek",
            deepseekModelPreset = "flash",
            deepseekModel = "deepseek-v4-flash",
            deepseekReasoningEffort = "high"
        )

        val resolved = ExecutionProfileDecider.resolveExecutionConfig(
            baseConfig = baseConfig,
            goal = "请帮我分析、调试并审查这个登录模块的完整实现风险",
            mentionedFileCount = 2
        )

        assertEquals("deepseek-v4-pro", resolved.deepseekModel)
        assertEquals("pro", resolved.deepseekModelPreset)
        assertEquals("max", resolved.deepseekReasoningEffort)
    }

    @Test
    fun resolveExecutionConfig_upgradesOpenAiOnlyOnOfficialEndpoints() {
        val officialConfig = ProviderConfig(
            activeProviderId = "openai-compatible",
            openaiBaseUrl = "https://api.openai.com/v1",
            openaiModel = "gpt-4.1-mini",
            openaiReasoningEffort = "medium"
        )
        val customGatewayConfig = officialConfig.copy(openaiBaseUrl = "https://example-proxy.invalid/v1")

        val officialResolved = ExecutionProfileDecider.resolveExecutionConfig(
            baseConfig = officialConfig,
            goal = "请帮我分析、调试并重构这个工作流路由",
            mentionedFileCount = 0
        )
        val customResolved = ExecutionProfileDecider.resolveExecutionConfig(
            baseConfig = customGatewayConfig,
            goal = "请帮我分析、调试并重构这个工作流路由",
            mentionedFileCount = 0
        )

        assertEquals("gpt-5.5", officialResolved.openaiModel)
        assertEquals("xhigh", officialResolved.openaiReasoningEffort)
        assertEquals("gpt-4.1-mini", customResolved.openaiModel)
        assertEquals("xhigh", customResolved.openaiReasoningEffort)
    }

    @Test
    fun resolveExecutionConfig_respectsAutoUpgradeToggle() {
        val baseConfig = ProviderConfig(
            activeProviderId = "claude",
            claudeModel = "claude-sonnet-4",
            claudeReasoningEffort = "medium",
            autoUpgradeExecutionProfile = false
        )

        val resolved = ExecutionProfileDecider.resolveExecutionConfig(
            baseConfig = baseConfig,
            goal = "请帮我分析、调试并重构这个模块",
            mentionedFileCount = 3
        )

        assertEquals("claude-sonnet-4", resolved.claudeModel)
        assertEquals("medium", resolved.claudeReasoningEffort)
    }

    @Test
    fun resolveExecutionConfig_enablesWebToolsForDiscoveryRequests() {
        val baseConfig = ProviderConfig(
            enabledBuiltinTools = listOf("read", "glob")
        )

        val resolved = ExecutionProfileDecider.resolveExecutionConfig(
            baseConfig = baseConfig,
            goal = "帮我搜索并接入一个 GitHub MCP server",
            mentionedFileCount = 0
        )

        assertTrue(resolved.enabledBuiltinTools.contains("web_search"))
        assertTrue(resolved.enabledBuiltinTools.contains("web_fetch"))
    }

    @Test
    fun buildExecutionProfileToast_formatsDifferentUpgradeCases() {
        val deepSeekBase = ProviderConfig(
            activeProviderId = "deepseek",
            deepseekModel = "deepseek-v4-flash",
            deepseekReasoningEffort = "high"
        )
        val deepSeekUpgraded = deepSeekBase.copy(
            deepseekModelPreset = "pro",
            deepseekModel = "deepseek-v4-pro",
            deepseekReasoningEffort = "max"
        )
        val reasoningOnly = deepSeekBase.copy(deepseekReasoningEffort = "max")

        assertEquals(
            "复杂任务，已自动升档到 DeepSeek V4 Pro 最大推理",
            ExecutionProfileDecider.buildExecutionProfileToast(deepSeekBase, deepSeekUpgraded)
        )
        assertEquals(
            "复杂任务，已自动提升到 DeepSeek V4 Flash 最大推理",
            ExecutionProfileDecider.buildExecutionProfileToast(deepSeekBase, reasoningOnly)
        )
        assertNull(ExecutionProfileDecider.buildExecutionProfileToast(deepSeekBase, deepSeekBase))
    }
}
