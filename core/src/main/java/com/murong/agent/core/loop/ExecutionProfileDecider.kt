package com.murong.agent.core.loop

import com.murong.agent.core.config.ProviderConfig
import com.murong.agent.core.provider.ProviderRegistry

internal object ExecutionProfileDecider {
    private val autoDiscoveryActionKeywords = listOf(
        "安装", "接入", "添加", "导入", "配置", "启用", "连接", "加载",
        "下载", "搜索", "查找", "接好", "接上", "install", "import",
        "add", "connect", "enable", "setup", "configure", "find"
    )

    private val autoDiscoveryTargetKeywords = listOf(
        "mcp", "skill", "server", "工具", "插件", "tool", "prompt"
    )

    val autoComplexityKeywords = listOf(
        "自动", "下载", "接入", "集成", "重构", "架构", "规划", "计划", "迁移",
        "批量", "排查", "调试", "分析", "审查", "搜索", "联网", "修复", "实现",
        "mcp", "skill", "workflow", "subagent", "review", "debug", "refactor"
    )

    fun resolveExecutionConfig(
        baseConfig: ProviderConfig,
        goal: String,
        mentionedFileCount: Int = 0,
        matchedSkillPreferredModel: String? = null
    ): ProviderConfig {
        val complexTask = isComplexTask(goal, mentionedFileCount)
        val shouldAutoModel = complexTask && baseConfig.isModelAutoSelectionEnabled()
        val shouldAutoReasoning = complexTask && baseConfig.isReasoningAutoSelectionEnabled()
        val discoveryRequest = isAutoDiscoveryRequest(goal)
        val requestedModel = matchedSkillPreferredModel?.trim().orEmpty().ifBlank {
            when (baseConfig.activeProviderId) {
                "deepseek" -> if (shouldAutoModel) "deepseek-v4-pro" else ""
                "claude" -> if (shouldAutoModel) "claude-opus-4-8" else ""
                "openai-compatible" -> if (shouldAutoModel && shouldPreferLatestOpenAiProfile(baseConfig)) "gpt-5.5" else ""
                else -> ""
            }
        }
        val requestedReasoning = when {
            !shouldAutoReasoning -> null
            baseConfig.activeProviderId == "deepseek" -> "max"
            baseConfig.activeProviderId == "claude" -> "xhigh"
            baseConfig.activeProviderId == "openai-compatible" -> "xhigh"
            else -> "high"
        }
        val targetBuiltinTools = if (discoveryRequest) {
            (baseConfig.enabledBuiltinTools + listOf("web_search", "web_fetch")).distinct()
        } else {
            baseConfig.enabledBuiltinTools
        }
        val resolvedConfig = when (baseConfig.activeProviderId) {
            "deepseek" -> {
                val targetModel = requestedModel.ifBlank { baseConfig.getActiveModel() }
                val preset = when (targetModel) {
                    "deepseek-v4-flash" -> "flash"
                    "deepseek-v4-pro" -> "pro"
                    else -> "custom"
                }
                baseConfig.copy(
                    deepseekModelPreset = preset,
                    deepseekModel = targetModel,
                    deepseekReasoningEffort = requestedReasoning ?: baseConfig.deepseekReasoningEffort
                )
            }

            "openai-compatible" -> baseConfig.copy(
                openaiModel = requestedModel.ifBlank { baseConfig.openaiModel },
                openaiReasoningEffort = requestedReasoning ?: baseConfig.openaiReasoningEffort
            )

            "claude" -> baseConfig.copy(
                claudeModel = requestedModel.ifBlank { baseConfig.claudeModel },
                claudeReasoningEffort = requestedReasoning ?: baseConfig.claudeReasoningEffort
            )

            else -> baseConfig
        }
        return resolvedConfig.copy(enabledBuiltinTools = targetBuiltinTools)
    }

    fun shouldPreferLatestOpenAiProfile(config: ProviderConfig): Boolean {
        val baseUrl = config.getActiveBaseUrl()?.trimEnd('/')?.lowercase().orEmpty()
        if (baseUrl.isBlank()) return true
        return baseUrl == "https://api.openai.com" ||
            baseUrl.startsWith("https://api.openai.com/") ||
            baseUrl.contains(".openai.azure.com")
    }

    fun buildExecutionProfileToast(
        baseConfig: ProviderConfig,
        executionConfig: ProviderConfig
    ): String? {
        val modelChanged = executionConfig.getActiveModel() != baseConfig.getActiveModel()
        val reasoningChanged = executionConfig.getActiveReasoningEffort() != baseConfig.getActiveReasoningEffort()
        if (!modelChanged && !reasoningChanged) return null
        val profileLabel = buildExecutionProfileLabel(
            providerId = executionConfig.activeProviderId,
            model = executionConfig.getActiveModel(),
            reasoning = executionConfig.getActiveReasoningEffort()
        )
        return when {
            modelChanged && reasoningChanged ->
                "复杂任务，已自动升档到 $profileLabel"
            modelChanged ->
                "复杂任务，已自动切换到 $profileLabel"
            else ->
                "复杂任务，已自动提升到 $profileLabel"
        }
    }

    fun buildExecutionProfileLabel(
        providerId: String,
        model: String,
        reasoning: String?
    ): String {
        val provider = ProviderRegistry.getProvider(providerId)
        return provider?.buildExecutionProfileLabel(model, reasoning)
            ?: model.trim().ifBlank { "当前模型" }
    }

    fun isAutoDiscoveryRequest(goal: String): Boolean {
        val normalized = goal.trim().lowercase()
        if (normalized.isBlank()) return false
        val actionHit = autoDiscoveryActionKeywords.any { keyword -> keyword in normalized }
        val targetHit = autoDiscoveryTargetKeywords.any { keyword -> keyword in normalized }
        return actionHit && targetHit
    }

    fun isComplexTask(goal: String, mentionedFileCount: Int = 0): Boolean {
        val normalized = goal.trim().lowercase()
        if (normalized.isBlank()) return false
        val keywordHits = autoComplexityKeywords.count { keyword -> keyword in normalized }
        val lineCount = goal.lines().count { it.isNotBlank() }
        return goal.length >= 90 ||
            lineCount >= 3 ||
            mentionedFileCount >= 2 ||
            keywordHits >= 2
    }
}
