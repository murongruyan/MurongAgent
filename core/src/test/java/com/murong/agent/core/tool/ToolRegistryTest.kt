package com.murong.agent.core.tool

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToolRegistryTest {

    @Test
    fun buildToolsJson_isStableAcrossRegistrationAndParameterMapOrder() {
        val firstRegistry = ToolRegistry().apply {
            register(FakeTool("zeta", linkedMapOf("z" to 1, "a" to "first")))
            register(FakeTool("alpha", linkedMapOf("nested" to linkedMapOf("z" to true, "a" to false))))
        }
        val secondRegistry = ToolRegistry().apply {
            register(FakeTool("alpha", linkedMapOf("nested" to linkedMapOf("a" to false, "z" to true))))
            register(FakeTool("zeta", linkedMapOf("a" to "first", "z" to 1)))
        }

        val firstJson = firstRegistry.buildToolsJson()
        assertEquals(firstJson, secondRegistry.buildToolsJson())
        assertTrue(firstJson.contains("\"nested\":{\"a\":false,\"z\":true}"))
        assertTrue(firstJson.contains("\"a\":\"first\""))
    }

    @Test
    fun buildToolsJson_preservesMcpJsonObjectSchemas() {
        val registry = ToolRegistry().apply {
            register(
                FakeTool(
                    "mcp_schema",
                    mapOf(
                        "properties" to buildJsonObject {
                            put("z", "last")
                            put("a", "first")
                            put("array", buildJsonArray {
                                add(JsonPrimitive("first"))
                                add(JsonPrimitive("second"))
                            })
                            put("boolean", true)
                            put("number", 7)
                            put("nullValue", JsonNull)
                        }
                    )
                )
            )
        }

        val toolsJson = registry.buildToolsJson()
        val properties = Json.parseToJsonElement(toolsJson)
            .jsonArray
            .single()
            .jsonObject["function"]!!
            .jsonObject["parameters"]!!
            .jsonObject["properties"]!!
            .jsonObject
        assertEquals("first", properties["a"]!!.jsonPrimitive.content)
        assertIs<JsonArray>(properties["array"])
        assertEquals(listOf("first", "second"), properties["array"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("true", properties["boolean"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, properties["nullValue"])
        assertEquals("7", properties["number"]!!.jsonPrimitive.content)
    }

    @Test
    fun buildToolsJson_reusesCachedDefinitionWhilePromptVisibilityRemainsDynamic() {
        var exposed = false
        val tool = FakeTool("dynamic", mapOf("value" to "before"))
        val registry = ToolRegistry().apply {
            register(tool, isPromptExposed = { exposed })
        }

        assertEquals("[]", registry.buildToolsJson())

        exposed = true
        tool.descriptionOverride = "Tool changed after registration"
        tool.parametersOverride = mapOf("value" to "after")

        val toolsJson = registry.buildToolsJson()
        assertTrue(toolsJson.contains("Tool dynamic"))
        assertTrue(toolsJson.contains("\"before\""))
        assertFalse(toolsJson.contains("changed after registration"))
        assertFalse(toolsJson.contains("\"after\""))
    }

    @Test
    fun buildToolsJson_neverExposesDisabledToolEvenWhenPromptOverrideIsTrue() {
        var enabled = false
        val registry = ToolRegistry().apply {
            register(
                FakeTool("write_tool", emptyMap()),
                isEnabled = { enabled },
                isPromptExposed = { true }
            )
        }

        assertEquals("[]", registry.buildToolsJson())
        assertFalse(registry.isPromptExposed("write_tool"))
        assertTrue(registry.getPromptVisibleTools().isEmpty())

        enabled = true

        assertTrue(registry.buildToolsJson().contains("write_tool"))
        assertTrue(registry.isPromptExposed("write_tool"))
    }

    @Test
    fun buildToolsJson_appliesTurnContextFilterWithoutRemovingExecutionEntry() {
        val registry = ToolRegistry(promptExposureFilter = { tool -> tool.name == "relevant" }).apply {
            register(FakeTool("relevant", emptyMap()))
            register(FakeTool("high_noise", emptyMap()))
        }

        val toolsJson = registry.buildToolsJson()

        assertTrue(toolsJson.contains("relevant"))
        assertFalse(toolsJson.contains("high_noise"))
        assertEquals(setOf("relevant", "high_noise"), registry.getAllTools().map { it.name }.toSet())
        assertTrue(registry.hasTool("high_noise"))
        assertTrue(registry.getTool("high_noise") != null)
    }

    @Test
    fun buildToolsJson_escapesControlCharactersAndPreservesLists() {
        val registry = ToolRegistry().apply {
            register(
                FakeTool(
                    "escaped",
                    mapOf(
                        "type" to "object",
                        "values" to listOf("line\nbreak", true, 2)
                    ),
                    description = "quote \" and tab\t"
                )
            )
        }

        val parameters = Json.parseToJsonElement(registry.buildToolsJson())
            .jsonArray.single().jsonObject["function"]!!.jsonObject["parameters"]!!.jsonObject
        assertEquals("object", parameters["type"]!!.jsonPrimitive.content)
        assertEquals(listOf("line\nbreak", "true", "2"), parameters["values"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(JsonObject(emptyMap()), parameters["properties"])
    }

    private class FakeTool(
        override val name: String,
        parameters: Map<String, Any>,
        description: String = "Tool $name"
    ) : Tool {
        var descriptionOverride: String = description
        var parametersOverride: Map<String, Any> = parameters

        override val description: String
            get() = descriptionOverride
        override val parameters: Map<String, Any>
            get() = parametersOverride

        override suspend fun execute(args: String): String = "ok"
    }
}
