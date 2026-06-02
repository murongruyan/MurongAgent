package dev.reasonix.mobile.core.provider

import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Anthropic Claude Provider
 *
 * 使用 Anthropic Messages API（与 OpenAI 格式不同）。
 * 支持 tool_use（Claude 的 Function Calling 实现）。
 *
 * API 文档: https://docs.anthropic.com/en/api/messages
 */
class ClaudeProvider : ModelProvider {

    override val name = "Claude (Anthropic)"
    override val id = "claude"
    override val defaultBaseUrl = "https://api.anthropic.com"
    override val defaultModel = "claude-opus-4-8"
    override val supportsReasoning = true
    override val supportedReasoningEfforts = listOf("low", "medium", "high", "xhigh", "max")

    override fun formatModelDisplayName(modelId: String): String = when (modelId.trim()) {
        "claude-opus-4-8" -> "Claude 4.8"
        else -> super.formatModelDisplayName(modelId)
    }

    override fun buildReasoningHint(modelId: String, reasoningEffort: String?): String {
        return "当前请求: model=$modelId, effort=$reasoningEffort。Claude 4.8 支持自适应 thinking + effort。"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    override suspend fun chatStream(
        request: ChatRequest,
        apiKey: String,
        baseUrl: String?,
        onDelta: (StreamDelta) -> Unit
    ): ChatResponse {
        val endpoint = (baseUrl ?: defaultBaseUrl).trimEnd('/')
        val url = "$endpoint/v1/messages"

        // 转换我们的消息格式为 Anthropic 格式
        val anthropicMessages = convertMessages(request.messages)
        val systemPrompt = request.messages
            .filter { it.role == "system" }
            .joinToString("\n") { it.content ?: "" }

        val bodyJson = buildJsonObject {
            put("model", request.model)
            put("max_tokens", request.maxTokens)
            put("stream", true)
            put("temperature", request.temperature)
            buildClaudeReasoningConfig(request.reasoningEffort)?.let { (thinking, outputConfig) ->
                put("thinking", thinking)
                put("output_config", outputConfig)
            }
            if (systemPrompt.isNotBlank()) {
                putJsonArray("system") {
                    addJsonObject {
                        put("type", "text")
                        put("text", systemPrompt)
                    }
                }
            }
            putJsonArray("messages") {
                anthropicMessages.forEach { msg ->
                    addJsonObject {
                        put("role", msg["role"]!!.jsonPrimitive.content)
                        putJsonArray("content") {
                            msg["content"]!!.jsonArray.forEach { add(it) }
                        }
                    }
                }
            }
            // 工具
            if (request.tools != null) {
                put("tools", buildAnthropicToolsFromOpenAiSchema(json, request.tools))
            }
        }

        val body = bodyJson.toString().toRequestBody("application/json".toMediaType())
        return withContext(Dispatchers.IO) {
            val httpRequest = Request.Builder()
                .url(url)
                .addHeader("x-api-key", apiKey)
                .addHeader("anthropic-version", "2023-06-01")
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build()

            client.newCall(httpRequest).execute().use { response ->
                val responseBody = response.body
                    ?: throw java.io.IOException("Empty response body")

                if (!response.isSuccessful) {
                    val errorBody = responseBody.string()
                    throw java.io.IOException("HTTP ${response.code}: $errorBody")
                }

                parseSSEStream(responseBody.byteStream(), onDelta)
            }
        }
    }

    override suspend fun chat(
        request: ChatRequest,
        apiKey: String,
        baseUrl: String?
    ): ChatResponse {
        val endpoint = (baseUrl ?: defaultBaseUrl).trimEnd('/')
        val url = "$endpoint/v1/messages"

        val anthropicMessages = convertMessages(request.messages)
        val systemPrompt = request.messages
            .filter { it.role == "system" }
            .joinToString("\n") { it.content ?: "" }

        val bodyJson = buildJsonObject {
            put("model", request.model)
            put("max_tokens", request.maxTokens)
            put("stream", false)
            put("temperature", request.temperature)
            buildClaudeReasoningConfig(request.reasoningEffort)?.let { (thinking, outputConfig) ->
                put("thinking", thinking)
                put("output_config", outputConfig)
            }
            if (systemPrompt.isNotBlank()) {
                putJsonArray("system") {
                    addJsonObject {
                        put("type", "text")
                        put("text", systemPrompt)
                    }
                }
            }
            putJsonArray("messages") {
                anthropicMessages.forEach { msg -> add(msg) }
            }
            if (request.tools != null) {
                put("tools", buildAnthropicToolsFromOpenAiSchema(json, request.tools))
            }
        }

        val body = bodyJson.toString().toRequestBody("application/json".toMediaType())
        return withContext(Dispatchers.IO) {
            val httpRequest = Request.Builder()
                .url(url)
                .addHeader("x-api-key", apiKey)
                .addHeader("anthropic-version", "2023-06-01")
                .post(body)
                .build()

            client.newCall(httpRequest).execute().use { response ->
                val responseBody = response.body
                    ?: throw java.io.IOException("Empty response body")
                val responseStr = responseBody.string()
                if (!response.isSuccessful) {
                    throw java.io.IOException("HTTP ${response.code}: $responseStr")
                }

                val root = json.parseToJsonElement(responseStr).jsonObject
                val content = root["content"]?.jsonArray
                val textContent = content?.firstOrNull {
                    it.jsonObject["type"]?.jsonPrimitive?.content == "text"
                }?.jsonObject?.get("text")?.jsonPrimitive?.content

                val toolUseBlocks = content?.filter {
                    it.jsonObject["type"]?.jsonPrimitive?.content == "tool_use"
                }

                val toolCalls = toolUseBlocks?.mapNotNull { block ->
                    val obj = block.jsonObject
                    val id = obj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    val name = obj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    val args = obj["input"].toString()
                    ToolCall(id, "function", ToolCallFunction(name, args))
                }

                val usage = root["usage"]?.jsonObject?.let {
                    Usage(
                        promptTokens = it["input_tokens"]?.jsonPrimitive?.int ?: 0,
                        completionTokens = it["output_tokens"]?.jsonPrimitive?.int ?: 0,
                        totalTokens = (it["input_tokens"]?.jsonPrimitive?.int ?: 0) + (it["output_tokens"]?.jsonPrimitive?.int ?: 0)
                    )
                }

                ChatResponse(content = textContent, toolCalls = toolCalls?.ifEmpty { null }, usage = usage)
            }
        }
    }

    /**
     * 将通用消息格式转换为 Anthropic 格式
     */
    private fun convertMessages(messages: List<ChatMessage>): List<JsonObject> {
        return convertMessagesToAnthropic(json, messages)
    }

    private fun buildClaudeReasoningConfig(reasoningEffort: String?): Pair<JsonObject, JsonObject>? {
        val effort = reasoningEffort?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
        return buildJsonObject {
            put("type", "adaptive")
        } to buildJsonObject {
            put("effort", effort)
        }
    }

    /**
     * 解析 Anthropic SSE 流
     *
     * Anthropic 的 SSE 格式不同：每行是 event: <type>\ndata: <json>
     * event 类型: content_block_start, content_block_delta, content_block_stop,
     *             message_start, message_delta, message_stop
     */
    private fun parseSSEStream(
        inputStream: java.io.InputStream,
        onDelta: (StreamDelta) -> Unit
    ): ChatResponse {
        val reader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))
        var fullContent = StringBuilder()
        val toolCalls = mutableListOf<ToolCall>()
        var currentEventType = ""
        var currentToolId = ""
        var currentToolName = ""
        var currentToolArgs = StringBuilder()
        var usage: Usage? = null

        try {
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val l = line ?: continue

                when {
                    l.startsWith("event: ") -> {
                        currentEventType = l.removePrefix("event: ").trim()
                    }
                    l.startsWith("data: ") -> {
                        val data = l.removePrefix("data: ").trim()
                        if (data == "[DONE]") {
                            onDelta(StreamDelta.Done)
                            break
                        }

                        try {
                            val event = json.parseToJsonElement(data).jsonObject
                            val type = event["type"]?.jsonPrimitive?.contentOrNull ?: currentEventType

                            when (type) {
                                "message_start" -> {
                                    val msg = event["message"]?.jsonObject
                                    usage = msg?.get("usage")?.jsonObject?.let {
                                        Usage(
                                            promptTokens = it["input_tokens"]?.jsonPrimitive?.int ?: 0,
                                            completionTokens = it["output_tokens"]?.jsonPrimitive?.int ?: 0,
                                            totalTokens = (it["input_tokens"]?.jsonPrimitive?.int ?: 0) + (it["output_tokens"]?.jsonPrimitive?.int ?: 0)
                                        )
                                    }
                                }
                                "content_block_start" -> {
                                    val contentBlock = event["content_block"]?.jsonObject
                                    val blockType = contentBlock?.get("type")?.jsonPrimitive?.content
                                    if (blockType == "text") {
                                        val text = contentBlock["text"]?.jsonPrimitive?.contentOrNull
                                        if (text != null) {
                                            fullContent.append(text)
                                            onDelta(StreamDelta.Content(text))
                                        }
                                    } else if (blockType == "tool_use") {
                                        currentToolId = contentBlock["id"]?.jsonPrimitive?.content ?: ""
                                        currentToolName = contentBlock["name"]?.jsonPrimitive?.content ?: ""
                                        currentToolArgs = StringBuilder()
                                        onDelta(StreamDelta.ToolCallStart(currentToolId, currentToolName))
                                    } else if (blockType == "thinking") {
                                        val thinking = contentBlock["thinking"]?.jsonPrimitive?.contentOrNull
                                        if (thinking != null) {
                                            onDelta(StreamDelta.Reasoning(thinking))
                                        }
                                    }
                                }
                                "content_block_delta" -> {
                                    val delta = event["delta"]?.jsonObject
                                    val deltaType = delta?.get("type")?.jsonPrimitive?.content
                                    when (deltaType) {
                                        "text_delta" -> {
                                            val text = delta["text"]?.jsonPrimitive?.contentOrNull
                                            if (text != null) {
                                                fullContent.append(text)
                                                onDelta(StreamDelta.Content(text))
                                            }
                                        }
                                        "input_json_delta" -> {
                                            val partial = delta["partial_json"]?.jsonPrimitive?.contentOrNull
                                            if (partial != null) {
                                                currentToolArgs.append(partial)
                                                onDelta(StreamDelta.ToolCallDelta(currentToolId, partial))
                                            }
                                        }
                                        "thinking_delta" -> {
                                            val thinking = delta["thinking"]?.jsonPrimitive?.contentOrNull
                                            if (thinking != null) {
                                                onDelta(StreamDelta.Reasoning(thinking))
                                            }
                                        }
                                    }
                                }
                                "content_block_stop" -> {
                                    if (currentToolId.isNotBlank() && currentToolName.isNotBlank()) {
                                        toolCalls.add(ToolCall(
                                            id = currentToolId,
                                            function = ToolCallFunction(currentToolName, currentToolArgs.toString())
                                        ))
                                        currentToolId = ""
                                        currentToolName = ""
                                        currentToolArgs = StringBuilder()
                                    }
                                }
                                "message_delta" -> {
                                    val deltaUsage = event["usage"]?.jsonObject
                                    if (deltaUsage != null) {
                                        usage = Usage(
                                            promptTokens = usage?.promptTokens ?: 0,
                                            completionTokens = deltaUsage["output_tokens"]?.jsonPrimitive?.int ?: 0,
                                            totalTokens = (usage?.promptTokens ?: 0) + (deltaUsage["output_tokens"]?.jsonPrimitive?.int ?: 0)
                                        )
                                    }

                                    val stopReason = event["delta"]?.jsonObject?.get("stop_reason")?.jsonPrimitive?.contentOrNull
                                    if (stopReason == "tool_use" && currentToolId.isNotBlank()) {
                                        toolCalls.add(ToolCall(
                                            id = currentToolId,
                                            function = ToolCallFunction(currentToolName, currentToolArgs.toString())
                                        ))
                                        currentToolId = ""
                                        currentToolName = ""
                                        currentToolArgs = StringBuilder()
                                    }
                                }
                                "message_stop" -> {
                                    onDelta(StreamDelta.Done)
                                }
                                "error" -> {
                                    val errorMsg = event["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
                                    onDelta(StreamDelta.Error(errorMsg ?: "Claude API error"))
                                }
                            }
                        } catch (_: Exception) {}
                    }
                }
            }
        } catch (e: Exception) {
            onDelta(StreamDelta.Error(e.message ?: "Stream error"))
        } finally {
            try { reader.close() } catch (_: Exception) {}
            try { inputStream.close() } catch (_: Exception) {}
        }

        return ChatResponse(
            content = fullContent.toString().ifBlank { null },
            toolCalls = toolCalls.ifEmpty { null },
            usage = usage
        )
    }
}

internal fun buildAnthropicToolsFromOpenAiSchema(json: Json, rawTools: String): JsonArray {
    val parsed = json.parseToJsonElement(rawTools).jsonArray
    return buildJsonArray {
        parsed.forEach { element ->
            val obj = element.jsonObject
            val function = obj["function"]?.jsonObject
            if (function != null) {
                addJsonObject {
                    put("name", function["name"]?.jsonPrimitive?.content.orEmpty())
                    put("description", function["description"]?.jsonPrimitive?.content.orEmpty())
                    put(
                        "input_schema",
                        function["parameters"] ?: buildJsonObject { put("type", "object") }
                    )
                }
            } else {
                add(element)
            }
        }
    }
}

internal fun convertMessagesToAnthropic(json: Json, messages: List<ChatMessage>): List<JsonObject> {
    val result = mutableListOf<JsonObject>()
    val nonSystemMessages = messages.filter { it.role != "system" }

    for (msg in nonSystemMessages) {
        val role = when (msg.role) {
            "assistant" -> "assistant"
            "tool" -> "user"
            else -> msg.role
        }
        val contentArray = if (msg.toolCallId != null) {
            buildJsonArray {
                addJsonObject {
                    put("type", "tool_result")
                    put("tool_use_id", msg.toolCallId)
                    put("content", msg.content ?: "")
                }
            }
        } else {
            buildJsonArray {
                if (msg.content != null) {
                    addJsonObject {
                        put("type", "text")
                        put("text", msg.content)
                    }
                }
                msg.images.forEach { image ->
                    addJsonObject {
                        put("type", "image")
                        putJsonObject("source") {
                            put("type", "base64")
                            put("media_type", image.mimeType)
                            put("data", image.base64Data)
                        }
                    }
                }
                if (msg.toolCalls != null) {
                    for (tc in msg.toolCalls) {
                        addJsonObject {
                            put("type", "tool_use")
                            put("id", tc.id)
                            put("name", tc.function.name)
                            put("input", json.parseToJsonElement(tc.function.arguments))
                        }
                    }
                }
            }
        }

        result.add(buildJsonObject {
            put("role", role)
            put("content", contentArray)
        })
    }

    return result
}
