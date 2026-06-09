package com.murong.agent.core.provider

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProviderMetadataTest {

    @Test
    fun deepSeekProvider_exposesExpectedMetadata() {
        val provider = DeepSeekProvider()

        assertEquals(
            listOf("low", "medium", "high", "max"),
            provider.supportedReasoningEfforts
        )
        assertEquals("DeepSeek V4 Flash", provider.formatModelDisplayName("deepseek-v4-flash"))
        assertEquals("DeepSeek V4 Pro 最大推理", provider.buildExecutionProfileLabel("deepseek-v4-pro", "max"))
        assertEquals(
            "当前请求: model=deepseek-v4-pro, effort=max",
            provider.buildReasoningHint("deepseek-v4-pro", "max")
        )
    }

    @Test
    fun openAiProvider_exposesExpectedMetadata() {
        val provider = OpenAIProvider()

        assertEquals(
            listOf("low", "medium", "high", "xhigh", "max"),
            provider.supportedReasoningEfforts
        )
        assertEquals("GPT-5.5", provider.formatModelDisplayName("gpt-5.5"))
        assertEquals("GPT-5.5 超高推理", provider.buildExecutionProfileLabel("gpt-5.5", "xhigh"))
        assertTrue(
            provider.buildReasoningHint("gpt-5.5", "medium")
                ?.contains("GPT-5.5 推荐从 medium 起步") == true
        )
    }

    @Test
    fun claudeProvider_exposesExpectedMetadata() {
        val provider = ClaudeProvider()

        assertEquals(
            listOf("low", "medium", "high", "xhigh", "max"),
            provider.supportedReasoningEfforts
        )
        assertEquals("Claude 4.8", provider.formatModelDisplayName("claude-opus-4-8"))
        assertEquals("Claude 4.8 高推理", provider.buildExecutionProfileLabel("claude-opus-4-8", "high"))
        assertTrue(
            provider.buildReasoningHint("claude-opus-4-8", "high")
                ?.contains("Claude 4.8 支持自适应 thinking + effort") == true
        )
    }

    @Test
    fun defaultReasoningLabels_coverCommonEfforts() {
        val provider = OpenAIProvider()

        assertEquals("低推理", provider.formatReasoningDisplayName("low"))
        assertEquals("中推理", provider.formatReasoningDisplayName("medium"))
        assertEquals("高推理", provider.formatReasoningDisplayName("high"))
        assertEquals("超高推理", provider.formatReasoningDisplayName("xhigh"))
        assertEquals("最大推理", provider.formatReasoningDisplayName("max"))
    }
}
