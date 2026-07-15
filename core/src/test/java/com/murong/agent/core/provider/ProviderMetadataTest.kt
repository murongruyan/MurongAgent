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
        assertEquals("GPT-5.6 Sol", provider.formatModelDisplayName("gpt-5.6-sol"))
        assertEquals("GPT-5.6 Sol 超高推理", provider.buildExecutionProfileLabel("gpt-5.6-sol", "xhigh"))
        assertTrue(
            provider.buildReasoningHint("gpt-5.6-sol", "high")
                ?.contains("GPT-5.6 Sol") == true
        )
    }

    @Test
    fun claudeProvider_exposesExpectedMetadata() {
        val provider = ClaudeProvider()

        assertEquals(
            listOf("low", "medium", "high", "xhigh", "max"),
            provider.supportedReasoningEfforts
        )
        assertEquals("Claude Fable 5", provider.formatModelDisplayName("claude-fable-5"))
        assertEquals("Claude Fable 5 高推理", provider.buildExecutionProfileLabel("claude-fable-5", "high"))
        assertTrue(
            provider.buildReasoningHint("claude-fable-5", "high")
                ?.contains("Claude Fable 5") == true
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
