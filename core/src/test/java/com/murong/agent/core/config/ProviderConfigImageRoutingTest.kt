package com.murong.agent.core.config

import com.murong.agent.core.provider.ProviderWireFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProviderConfigImageRoutingTest {
    @Test
    fun visionRoute_reusesSelectedProviderProfile() {
        val relay = RelayConfig(
            id = "vision-profile",
            baseUrl = "https://vision.example/v1",
            apiKey = "secret",
            model = "qwen-vl-max",
            apiFormat = ProviderWireFormat.CHAT_COMPLETIONS,
        )
        val config = ProviderConfig(
            visionRoutingEnabled = true,
            visionProviderId = "qwen",
            visionRelayId = relay.id,
            externalProviderRelays = mapOf("qwen" to listOf(relay)),
            activeExternalProviderRelayIds = mapOf("qwen" to relay.id),
        )

        val route = config.resolveVisionRoute() ?: error("vision route missing")
        assertEquals("vision-profile", route.relayId)
        assertEquals("secret", route.apiKey)
        assertEquals("qwen-vl-max", route.model)
    }

    @Test
    fun customImageRoute_takesPrecedenceWithoutChangingChatProvider() {
        val config = ProviderConfig(
            activeProviderId = "deepseek",
            imageGenerationProviderId = "openai-compatible",
            imageGenerationModel = "gpt-image-2",
            imageGenerationCustomBaseUrl = "https://images.example/v1",
            imageGenerationCustomApiKey = "image-secret",
        )

        val route = config.resolveImageGenerationRoute() ?: error("image route missing")
        assertEquals("custom", route.providerId)
        assertEquals("https://images.example/v1", route.baseUrl)
        assertEquals("image-secret", route.apiKey)
        assertEquals("deepseek", config.activeProviderId)
    }

    @Test
    fun disabledVisionRoute_isAbsent() {
        assertNull(ProviderConfig(visionProviderId = "openai-compatible").resolveVisionRoute())
    }
}
