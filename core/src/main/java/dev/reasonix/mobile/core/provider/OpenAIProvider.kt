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
 * OpenAI 兼容 Provider
 *
 * 适用于：
 * - OpenAI GPT (api.openai.com)
 * - 任何 OpenAI 兼容的中转站 / OneAPI / NewAPI
 * - 本地运行的 LLM（Ollama, vLLM, LM Studio）
 *
 * 用户只需设置 baseUrl 指向任意兼容端点。
 */
class OpenAIProvider : ModelProvider {
    private data class PartialToolCall(
        var id: String = "",
        var name: String = "",
        val arguments: StringBuilder = StringBuilder(),
        var announced: Boolean = false
    )

    override val name = "OpenAI Compatible"
    override val id = "openai-compatible"
    override val defaultBaseUrl = "https://api.openai.com"
    override val defaultModel = "gpt-5.5"
    override val supportsReasoning = true
    override val supportedReasoningEfforts = listOf("low", "medium", "high", "xhigh", "max")

    override fun formatModelDisplayName(modelId: String): String = when (modelId.trim()) {
        "gpt-5.5" -> "GPT-5.5"
        else -> super.formatModelDisplayName(modelId)
    }

    override fun buildReasoningHint(modelId: String, reasoningEffort: String?): String {
        return "当前请求: model=$modelId, effort=$reasoningEffort。GPT-5.5 推荐从 medium 起步，复杂任务再升到 xhigh。"
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
            putJsonObject("stream_options") {
                put("include_usage", true)
            }
            // 工具
            if (request.tools != null) {
                put("tools", json.parseToJsonElement(request.tools))
            }
        }

        val body = bodyJson.toString().toRequestBody("application/json".toMediaType())
        return withContext(Dispatchers.IO) {
            val httpRequest = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Accept", "text/event-stream")
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
        }

        val body = bodyJson.toString().toRequestBody("application/json".toMediaType())
        return withContext(Dispatchers.IO) {
            val httpRequest = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $apiKey")
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
                val choice = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                val message = choice?.get("message")?.jsonObject
                val content = message?.get("content")?.jsonPrimitive?.contentOrNull
                val usage = root["usage"]?.jsonObject

                ChatResponse(
                    content = content,
                    toolCalls = parseToolCalls(message?.get("tool_calls")),
                    usage = usage?.let { parseUsage(it) }
                )
            }
        }
    }

    private fun parseSSEStream(
        inputStream: java.io.InputStream,
        onDelta: (StreamDelta) -> Unit
    ): ChatResponse {
        val reader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))
        val fullContent = StringBuilder()
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

                        if (event.containsKey("usage") && !event["usage"]!!.jsonObject.isEmpty()) {
                            usage = parseUsage(event["usage"]!!.jsonObject)
                        }

                        val choices = event["choices"]?.jsonArray
                        if (choices != null && choices.isNotEmpty()) {
                            val delta = choices[0].jsonObject["delta"]?.jsonObject

                            if (delta != null) {
                                val content = delta["content"]?.jsonPrimitive?.contentOrNull
                                if (content != null && content.isNotEmpty()) {
                                    fullContent.append(content)
                                    onDelta(StreamDelta.Content(content))
                                }

                                val toolCallsDelta = delta["tool_calls"]?.jsonArray
                                if (toolCallsDelta != null) {
                                    for (tcElement in toolCallsDelta) {
                                        val tcObj = tcElement.jsonObject
                                        val index = tcObj["index"]?.jsonPrimitive?.intOrNull
                                            ?: partialToolCalls.size
                                        val id = tcObj["id"]?.jsonPrimitive?.contentOrNull
                                        val funcName = tcObj["function"]?.jsonObject
                                            ?.get("name")
                                            ?.jsonPrimitive
                                            ?.contentOrNull
                                        val funcArgs = tcObj["function"]?.jsonObject
                                            ?.get("arguments")
                                            ?.jsonPrimitive
                                            ?.contentOrNull
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

                            val finishReason = choices[0].jsonObject["finish_reason"]?.jsonPrimitive?.contentOrNull
                            if (finishReason == "tool_calls") {
                                commitPendingToolCalls()
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            onDelta(StreamDelta.Error(e.message ?: "Stream error"))
        } finally {
            try { reader.close() } catch (_: Exception) {}
            try { inputStream.close() } catch (_: Exception) {}
        }

        commitPendingToolCalls()

        return ChatResponse(
            content = fullContent.toString().ifBlank { null },
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
        } catch (e: Exception) { null }
    }

    private fun parseUsage(obj: JsonObject): Usage {
        val promptTokens = obj["prompt_tokens"]?.jsonPrimitive?.int ?: 0
        val promptTokensDetails = obj["prompt_tokens_details"]?.jsonObject
        val topLevelCacheHit = obj["prompt_cache_hit_tokens"]?.jsonPrimitive?.intOrNull ?: 0
        val topLevelCacheMiss = obj["prompt_cache_miss_tokens"]?.jsonPrimitive?.intOrNull ?: 0
        val nestedCacheHit = promptTokensDetails
            ?.get("cached_tokens")
            ?.jsonPrimitive
            ?.intOrNull ?: 0
        val cacheHit = if (topLevelCacheHit > 0) topLevelCacheHit else nestedCacheHit
        val cacheMiss = when {
            topLevelCacheMiss > 0 -> topLevelCacheMiss
            cacheHit > 0 && promptTokens > cacheHit -> promptTokens - cacheHit
            else -> 0
        }
        return Usage(
            promptTokens = promptTokens,
            completionTokens = obj["completion_tokens"]?.jsonPrimitive?.int ?: 0,
            totalTokens = obj["total_tokens"]?.jsonPrimitive?.int ?: 0,
            promptCacheHitTokens = cacheHit.takeIf { it > 0 },
            promptCacheMissTokens = cacheMiss.takeIf { it > 0 }
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
