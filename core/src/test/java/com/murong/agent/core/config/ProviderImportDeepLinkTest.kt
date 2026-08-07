package com.murong.agent.core.config

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProviderImportDeepLinkTest {
    private val usageScript = """
        ({
          request: {
            url: "{{baseUrl}}/v1/usage",
            method: "GET",
            headers: { "Authorization": "Bearer {{apiKey}}" }
          },
          extractor: function(response) {
            const remaining = response?.remaining ?? response?.quota?.remaining ?? response?.balance;
            const unit = response?.unit ?? response?.quota?.unit ?? "USD";
            return { isValid: response?.is_active ?? response?.isValid ?? true, remaining, unit };
          }
        })
    """.trimIndent()

    @Test
    fun `parses cc switch provider and converts supported usage script`() {
        val link = buildLink(usageScript = usageScript, usageEnabled = true)
        val payload = ProviderImportDeepLink.parse(link).getOrThrow()

        assertEquals("codex", payload.app)
        assertEquals("openai-compatible", payload.providerId)
        assertEquals("https://api.example.com/v1/usage", payload.usageRule?.endpoint)
        assertTrue(payload.requestedUsageEnabled)
        assertFalse(payload.maskedApiKey.contains("secret-value"))
    }

    @Test
    fun `imports as encrypted relay compatible config without activating by default`() {
        val payload = ProviderImportDeepLink.parse(buildLink()).getOrThrow()
        val original = ProviderConfig(activeProviderId = "deepseek").withLegacyRelayConfigurations()
        val updated = payload.applyTo(original, activate = false, enableUsage = false)

        assertEquals("deepseek", updated.activeProviderId)
        val relay = assertNotNull(updated.getRelayConfigs("openai-compatible").find { it.name == "Test relay" })
        assertEquals("https://api.example.com", relay.baseUrl)
        assertEquals("sk-secret-value", relay.apiKey)
        assertEquals("", relay.balanceApiPath)
    }

    @Test
    fun `duplicate import updates the same stable relay`() {
        val payload = ProviderImportDeepLink.parse(buildLink()).getOrThrow()
        val once = payload.applyTo(ProviderConfig(), activate = true, enableUsage = false)
        val twice = payload.applyTo(once, activate = true, enableUsage = false)

        assertEquals(1, twice.getRelayConfigs("openai-compatible").count { it.name == "Test relay" })
    }

    @Test
    fun `rejects duplicate parameters insecure endpoints and oversized fields`() {
        assertTrue(ProviderImportDeepLink.parse(buildLink() + "&apiKey=other").isFailure)
        assertTrue(ProviderImportDeepLink.parse(buildLink(endpoint = "http://api.example.com")).isFailure)
        assertTrue(ProviderImportDeepLink.parse(buildLink(name = "x".repeat(101))).isFailure)
    }

    @Test
    fun `does not convert scripts with cross origin or unsafe method`() {
        val crossOrigin = usageScript.replace("{{baseUrl}}/v1/usage", "https://evil.example/v1/usage")
        val post = usageScript.replace("method: \"GET\"", "method: \"POST\"")

        assertEquals(null, ProviderImportDeepLink.parse(buildLink(usageScript = crossOrigin)).getOrThrow().usageRule)
        assertEquals(null, ProviderImportDeepLink.parse(buildLink(usageScript = post)).getOrThrow().usageRule)
    }

    private fun buildLink(
        name: String = "Test relay",
        endpoint: String = "https://api.example.com",
        usageScript: String? = null,
        usageEnabled: Boolean = false,
    ): String {
        val values = linkedMapOf(
            "resource" to "provider",
            "app" to "codex",
            "name" to name,
            "homepage" to "https://example.com",
            "endpoint" to endpoint,
            "apiKey" to "sk-secret-value",
            "model" to "gpt-test",
            "usageEnabled" to usageEnabled.toString(),
        )
        usageScript?.let { values["usageScript"] = it }
        return "ccswitch://v1/import?" + values.entries.joinToString("&") { (key, value) ->
            "$key=${URLEncoder.encode(value, StandardCharsets.UTF_8)}"
        }
    }
}
