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
 * DeepSeek Provider
 *
 * 支持：
 * - extra_body.thinking（DeepSeek 专有）
 * - reasoning_content 字段解析
 * - prompt_cache_hit_tokens / prompt_cache_miss_tokens 缓存统计
 * - 中转站兼容（通过 baseUrl 指向任何兼容端点）
 */
class DeepSeekProvider : ModelProvider {
    private data class PartialToolCall(
        var id: String = "",
        var name: String = "",
        val arguments: StringBuilder = StringBuilder(),
        var announced: Boolean = false
    )

    override val name = "DeepSeek"
    override val id = "deepseek"
    override val defaultBaseUrl = "https://api.deepseek.com"
    override val defaultModel = "deepseek-v4-flash"
    override val supportsReasoning = true
    override val supportedReasoningEfforts = listOf("low", "medium", "high", "max")

    override fun formatModelDisplayName(modelId: String): String = when (modelId.trim()) {
        "deepseek-v4-flash" -> "DeepSeek V4 Flash"
        "deepseek-v4-pro" -> "DeepSeek V4 Pro"
        else -> super.formatModelDisplayName(modelId)
    }

    override fun buildReasoningHint(modelId: String, reasoningEffort: String?): String {
        return "当前请求: model=$modelId, effort=$reasoningEffort"
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

    private fun isAzureEndpoint(endpoint: String): Boolean {
        return endpoint.contains(".openai.azure.com", ignoreCase = true)
    }

    override suspend fun chatStream(
        request: ChatRequest,
        apiKey: String,
        baseUrl: String?,
        onDelta: (StreamDelta) -> Unit
    ): ChatResponse {
        val endpoint = (baseUrl ?: defaultBaseUrl).trimEnd('/')
        val url = "$endpoint/chat/completions"

        val messagesJson = buildJsonArray {
            request.messages.forEach { msg ->
                addJsonObject {
                    put("role", msg.role)
                    if (msg.images.isNotEmpty()) {
                        put("content", buildOpenAiContent(msg))
                    } else if (msg.content != null) {
                        put("content", msg.content)
                    }
                    if (msg.toolCalls != null) {
                        putJsonArray("tool_calls") {
                            msg.toolCalls.forEach { tc ->
                                addJsonObject {
                                    put("id", tc.id)
                                    put("type", tc.type)
                                    putJsonObject("function") {
                                        put("name", tc.function.name)
                                        put("arguments", tc.function.arguments)
                                    }
                                }
                            }
                        }
                    }
                    if (msg.toolCallId != null) {
                        put("tool_call_id", msg.toolCallId)
                    }
                }
            }
        }

        val bodyJson = buildJsonObject {
            put("model", request.model)
            put("messages", messagesJson)
            put("temperature", request.temperature)
            put("max_tokens", request.maxTokens)
            put("stream", true)
            if (!request.reasoningEffort.isNullOrBlank()) {
                put("reasoning_effort", request.reasoningEffort)
            }
            if (!request.thinkingMode.isNullOrBlank() && !isAzureEndpoint(endpoint)) {
                putJsonObject("extra_body") {
                    putJsonObject("thinking") {
                        put("type", request.thinkingMode)
                    }
                }
            }
            if (request.tools != null) {
                put("tools", json.parseToJsonElement(request.tools))
            }
        }

        val body = bodyJson.toString()
        val requestBody = body.toRequestBody("application/json".toMediaType())

        return withContext(Dispatchers.IO) {
            val httpRequest = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Accept", "text/event-stream")
                .addHeader("Content-Type", "application/json")
                .post(requestBody)
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
        val url = "$endpoint/chat/completions"

        val bodyJson = buildJsonObject {
            put("model", request.model)
            put("messages", buildJsonArray {
                request.messages.forEach { msg ->
                    addJsonObject {
                        put("role", msg.role)
                        if (msg.images.isNotEmpty()) {
                            put("content", buildOpenAiContent(msg))
                        } else if (msg.content != null) {
                            put("content", msg.content)
                        }
                    }
                }
            })
            put("temperature", request.temperature)
            put("max_tokens", request.maxTokens)
            put("stream", false)
            if (!request.reasoningEffort.isNullOrBlank()) {
                put("reasoning_effort", request.reasoningEffort)
            }
            if (!request.thinkingMode.isNullOrBlank() && !isAzureEndpoint(endpoint)) {
                putJsonObject("extra_body") {
                    putJsonObject("thinking") {
                        put("type", request.thinkingMode)
                    }
                }
            }
        }

        val body = bodyJson.toString().toRequestBody("application/json".toMediaType())
        return withContext(Dispatchers.IO) {
            val httpRequest = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $apiKey")
                .post(body)
                .build()

            val response = client.newCall(httpRequest).execute()
            val responseStr = response.body?.string() ?: ""

            try {
                val root = json.parseToJsonElement(responseStr).jsonObject
            val choice = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            val message = choice?.get("message")?.jsonObject
            val content = message?.get("content")?.jsonPrimitive?.contentOrNull
            val usage = root["usage"]?.jsonObject

            ChatResponse(
                content = content,
                toolCalls = parseToolCalls(message?.get("tool_calls")),
                usage = usage?.let { parseUsage(it) }
            )
        } catch (e: Exception) {
            ChatResponse(content = "Parse error: ${e.message}", toolCalls = null)
        }
        }
    }

    /**
     * 解析 SSE 流
     */
    private fun parseSSEStream(
        inputStream: java.io.InputStream,
        onDelta: (StreamDelta) -> Unit
    ): ChatResponse {
        val reader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))
        val fullContent = StringBuilder()
        val fullReasoning = StringBuilder()
        val toolCalls = mutableListOf<ToolCall>()
        val partialToolCalls = linkedMapOf<Int, PartialToolCall>()
        val committedToolIndexes = mutableSetOf<Int>()
        var usage: Usage? = null
        var doneSent = false

        fun ensureToolCallAnnounced(partial: PartialToolCall) {
            if (!partial.announced && partial.id.isNotBlank() && partial.name.isNotBlank()) {
                partial.announced = true
                onDelta(StreamDelta.ToolCallStart(partial.id, partial.name))
            }
        }

        fun commitPendingToolCalls() {
            partialToolCalls.toSortedMap().forEach { (index, partial) ->
                if (index in committedToolIndexes) return@forEach
                if (partial.id.isBlank() || partial.name.isBlank()) return@forEach
                toolCalls.add(
                    ToolCall(
                        id = partial.id,
                        type = "function",
                        function = ToolCallFunction(
                            name = partial.name,
                            arguments = partial.arguments.toString()
                        )
                    )
                )
                committedToolIndexes.add(index)
            }
        }

        try {
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val l = line ?: continue

                if (l.startsWith("data: ")) {
                    val data = l.removePrefix("data: ").trim()

                    if (data == "[DONE]") {
                        commitPendingToolCalls()
                        if (!doneSent) {
                            doneSent = true
                            onDelta(StreamDelta.Done)
                        }
                        break
                    }

                    try {
                        val event = json.parseToJsonElement(data).jsonObject

                        // Usage 信息（最后一个 event 携带）
                        if (event.containsKey("usage") && !event["usage"]!!.jsonObject.isEmpty()) {
                            usage = parseUsage(event["usage"]!!.jsonObject)
                        }

                        val choices = event["choices"]?.jsonArray
                        if (choices != null && choices.isNotEmpty()) {
                            val delta = choices[0].jsonObject["delta"]?.jsonObject

                            if (delta != null) {
                                // reasoning_content — DeepSeek 专有
                                val reasoning = delta["reasoning_content"]?.jsonPrimitive?.contentOrNull
                                if (reasoning != null && reasoning.isNotEmpty()) {
                                    fullReasoning.append(reasoning)
                                    onDelta(StreamDelta.Reasoning(reasoning))
                                }

                                val content = delta["content"]?.jsonPrimitive?.contentOrNull
                                if (content != null && content.isNotEmpty()) {
                                    fullContent.append(content)
                                    onDelta(StreamDelta.Content(content))
                                }

                                // tool_calls
                                val toolCallsDelta = delta["tool_calls"]?.jsonArray
                                if (toolCallsDelta != null) {
                                    for (tcElement in toolCallsDelta) {
                                        val tcObj = tcElement.jsonObject
                                        val index = tcObj["index"]?.jsonPrimitive?.intOrNull
                                            ?: partialToolCalls.size
                                        val id = tcObj["id"]?.jsonPrimitive?.contentOrNull
                                        val funcName = tcObj["function"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull
                                        val funcArgs = tcObj["function"]?.jsonObject?.get("arguments")?.jsonPrimitive?.contentOrNull
                                        val partial = partialToolCalls.getOrPut(index) { PartialToolCall() }

                                        if (id != null && partial.id.isBlank()) {
                                            partial.id = id
                                        }
                                        if (funcName != null && partial.name.isBlank()) {
                                            partial.name = funcName
                                        }
                                        ensureToolCallAnnounced(partial)

                                        if (funcArgs != null && funcArgs.isNotEmpty()) {
                                            partial.arguments.append(funcArgs)
                                            ensureToolCallAnnounced(partial)
                                            if (partial.id.isNotBlank()) {
                                                onDelta(StreamDelta.ToolCallDelta(partial.id, funcArgs))
                                            }
                                        }
                                    }
                                }
                            }

                            // finish_reason 也可能在 choice 级别
                            val finishReason = choices[0].jsonObject["finish_reason"]?.jsonPrimitive?.contentOrNull
                            if (finishReason == "tool_calls") {
                                commitPendingToolCalls()
                            }
                        }
                    } catch (_: Exception) {
                        // 跳过解析失败的 chunk
                    }
                }
            }
        } catch (e: Exception) {
            onDelta(StreamDelta.Error(e.message ?: "Stream error"))
        } finally {
            try { reader.close() } catch (_: Exception) {}
            try { inputStream.close() } catch (_: Exception) {}
        }

        commitPendingToolCalls()

        val contentText = fullContent.toString()
        val reasoningText = fullReasoning.toString()
        val content = contentText.ifBlank {
            reasoningText.ifBlank { null }
        }

        return ChatResponse(
            content = content,
            toolCalls = toolCalls.ifEmpty { null },
            usage = usage
        )
    }

    private fun parseToolCalls(element: JsonElement?): List<ToolCall>? {
        if (element == null) return null
        return try {
            element.jsonArray.map { tc ->
                val obj = tc.jsonObject
                ToolCall(
                    id = obj["id"]?.jsonPrimitive?.content ?: "",
                    type = obj["type"]?.jsonPrimitive?.content ?: "function",
                    function = ToolCallFunction(
                        name = obj["function"]?.jsonObject?.get("name")?.jsonPrimitive?.content ?: "",
                        arguments = obj["function"]?.jsonObject?.get("arguments")?.jsonPrimitive?.content ?: "{}"
                    )
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun parseUsage(obj: JsonObject): Usage {
        return Usage(
            promptTokens = obj["prompt_tokens"]?.jsonPrimitive?.int ?: 0,
            completionTokens = obj["completion_tokens"]?.jsonPrimitive?.int ?: 0,
            totalTokens = obj["total_tokens"]?.jsonPrimitive?.int ?: 0,
            promptCacheHitTokens = obj["prompt_cache_hit_tokens"]?.jsonPrimitive?.intOrNull,
            promptCacheMissTokens = obj["prompt_cache_miss_tokens"]?.jsonPrimitive?.intOrNull
        )
    }

    private fun buildOpenAiContent(msg: ChatMessage): JsonArray {
        return buildJsonArray {
            msg.content?.takeIf { it.isNotBlank() }?.let { text ->
                addJsonObject {
                    put("type", "text")
                    put("text", text)
                }
            }
            msg.images.forEach { image ->
                addJsonObject {
                    put("type", "image_url")
                    putJsonObject("image_url") {
                        put("url", "data:${image.mimeType};base64,${image.base64Data}")
                    }
                }
            }
        }
    }
}
