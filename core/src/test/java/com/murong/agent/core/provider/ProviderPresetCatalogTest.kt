package com.murong.agent.core.provider

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProviderPresetCatalogTest {
    @Test
    fun `every remote provider has structured preset metadata`() {
        ProviderRegistry.getAllProviders().forEach { provider ->
            val preset = assertNotNull(ProviderPresetCatalog.get(provider.id), provider.id)
            assertTrue(preset.websiteUrl.startsWith("https://"), provider.id)
            assertTrue(preset.apiKeyUrl.startsWith("https://"), provider.id)
            assertTrue(preset.endpointCandidates.contains(provider.defaultBaseUrl), provider.id)
        }
    }

    @Test
    fun `native protocols stay distinct from OpenAI compatible presets`() {
        assertEquals(ProviderApiProtocol.ANTHROPIC, ProviderPresetCatalog.get("claude")?.protocol)
        assertEquals(ProviderApiProtocol.GEMINI, ProviderPresetCatalog.get("gemini")?.protocol)
    }
}
