package com.murong.agent.ui.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProviderModelCatalogTest {
    @Test
    fun fetchedCatalog_replacesStaticRecommendationsInsteadOfMixingThemIn() {
        val models = mergeProviderModelCandidates(
            providerId = "openai-compatible",
            currentModel = "custom-production-model",
            fetchedModels = listOf("gpt-live-a", "gpt-live-b")
        )

        assertEquals(listOf("custom-production-model", "gpt-live-a", "gpt-live-b"), models)
        assertFalse(models.contains("gpt-5.6-sol"))
    }

    @Test
    fun officialCatalog_includesEveryBuiltInOfficialProvider() {
        val providers = listOf("kimi", "glm", "qwen", "minimax", "grok", "mimo", "hy3", "gemini")

        providers.forEach { providerId ->
            assertTrue(builtinProviderModels(providerId).isNotEmpty(), "missing $providerId recommendation")
        }
        assertTrue(builtinProviderModels("gemini").contains("gemini-3.6-flash"))
        assertTrue(builtinProviderModels("mimo").contains("mimo-v2.5-pro"))
    }
}
