package com.murong.agent.core.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class McpTypesSerializationTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun jsonRpcRequest_serializesParamsWithoutLegacyInputWrapper() {
        val request = JsonRpcRequest(
            id = "req-1",
            method = "tools/call",
            params = buildJsonObject {
                put("name", "search")
                putJsonObject("arguments") {
                    put("query", "murongagent")
                }
            }
        )

        val encoded = json.encodeToString(JsonRpcRequest.serializer(), request)
        val root = json.parseToJsonElement(encoded).jsonObject
        val params = root["params"]?.jsonObject ?: error("missing params")

        assertEquals("search", params["name"]?.jsonPrimitive?.content)
        assertEquals("murongagent", params["arguments"]?.jsonObject?.get("query")?.jsonPrimitive?.content)
        assertNull(params["input"])
    }

    @Test
    fun buildInitializeParams_includesProtocolCapabilitiesAndClientInfo() {
        val params = buildInitializeParams()

        assertEquals(McpTransport.PROTOCOL_VERSION, params["protocolVersion"]?.jsonPrimitive?.content)
        assertEquals(0, params["capabilities"]?.jsonObject?.size)
        assertEquals(McpTransport.CLIENT_NAME, params["clientInfo"]?.jsonObject?.get("name")?.jsonPrimitive?.content)
        assertEquals(McpTransport.CLIENT_VERSION, params["clientInfo"]?.jsonObject?.get("version")?.jsonPrimitive?.content)
    }

    @Test
    fun jsonRpcNotification_serializesWithoutId() {
        val notification = JsonRpcNotification(
            method = "notifications/initialized",
            params = buildJsonObject { }
        )

        val encoded = json.encodeToString(JsonRpcNotification.serializer(), notification)
        val root = json.parseToJsonElement(encoded).jsonObject

        assertEquals("notifications/initialized", root["method"]?.jsonPrimitive?.content)
        assertNull(root["id"])
    }
}
