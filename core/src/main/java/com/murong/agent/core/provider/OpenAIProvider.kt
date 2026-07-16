package com.murong.agent.core.provider

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * OpenAI Provider.
 *
 * api.openai.com defaults to the native Responses API. Arbitrary OpenAI-compatible
 * endpoints keep the older Chat Completions wire format unless their configured
 * URL explicitly ends in /responses.
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
    override val defaultModel = "gpt-5.6-sol"
    override val supportsReasoning = true
    override val supportedReasoningEfforts = listOf("low", "medium", "high", "xhigh", "max")

    override fun formatModelDisplayName(modelId: String): String = when (modelId.trim()) {
        "gpt-5.6-sol" -> "GPT-5.6 Sol"
        "gpt-5.6-terra" -> "GPT-5.6 Terra"
        "gpt-5.6-luna" -> "GPT-5.6 Luna"
        "gpt-5.5" -> "GPT-5.5"
        else -> super.formatModelDisplayName(modelId)
    }

    override fun buildReasoningHint(modelId: String, reasoningEffort: String?): String {
        return when (modelId.trim()) {
            "gpt-5.6-sol" ->
                "当前请求: model=$modelId, effort=$reasoningEffort。GPT-5.6 Sol 适合高复杂度任务，建议从 high 起步，特别难的任务可升到 xhigh / max。"
            "gpt-5.6-terra" ->
                "当前请求: model=$modelId, effort=$reasoningEffort。GPT-5.6 Terra 更均衡，适合日常编码与多步分析。"
            "gpt-5.6-luna" ->
                "当前请求: model=$modelId, effort=$reasoningEffort。GPT-5.6 Luna 偏速度与成本，适合轻量请求。"
            else ->
                "当前请求: model=$modelId, effort=$reasoningEffort。GPT 系列推荐从 medium 起步，复杂任务再升到 xhigh。"
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
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
        val safeRequest = normalizeChatRequestForProvider(request)
        val endpoint = resolveOpenAIEndpoint(baseUrl ?: defaultBaseUrl)
        return when (endpoint.protocol) {
            OpenAIWireProtocol.CHAT_COMPLETIONS ->
                executeChatCompletionsStream(safeRequest, apiKey, endpoint.url, onDelta)

            OpenAIWireProtocol.RESPONSES -> try {
                executeResponsesStream(safeRequest, apiKey, endpoint.url, onDelta)
            } catch (error: ProviderHttpException) {
                val fallbackUrl = endpoint.chatCompletionsFallbackUrl
                if (fallbackUrl != null && error.statusCode in RESPONSES_UNSUPPORTED_STATUS_CODES) {
                    executeChatCompletionsStream(safeRequest, apiKey, fallbackUrl, onDelta)
                } else {
                    throw error
                }
            }
        }
    }

    override suspend fun chat(
        request: ChatRequest,
        apiKey: String,
        baseUrl: String?
    ): ChatResponse {
        val safeRequest = normalizeChatRequestForProvider(request)
        val endpoint = resolveOpenAIEndpoint(baseUrl ?: defaultBaseUrl)
        return when (endpoint.protocol) {
            OpenAIWireProtocol.CHAT_COMPLETIONS ->
                executeChatCompletions(safeRequest, apiKey, endpoint.url)

            OpenAIWireProtocol.RESPONSES -> try {
                executeResponses(safeRequest, apiKey, endpoint.url)
            } catch (error: ProviderHttpException) {
                val fallbackUrl = endpoint.chatCompletionsFallbackUrl
                if (fallbackUrl != null && error.statusCode in RESPONSES_UNSUPPORTED_STATUS_CODES) {
                    executeChatCompletions(safeRequest, apiKey, fallbackUrl)
                } else {
                    throw error
                }
            }
        }
    }

    private suspend fun executeResponsesStream(
        request: ChatRequest,
        apiKey: String,
        url: String,
        onDelta: (StreamDelta) -> Unit
    ): ChatResponse {
        val body = buildResponsesPayload(request, stream = true)
            .toString()
            .toRequestBody(JSON_MEDIA_TYPE)
        return withContext(Dispatchers.IO) {
            val httpRequest = authorizedRequest(url, apiKey, acceptSse = true).post(body).build()
            client.newCall(httpRequest).execute().use { response ->
                val responseBody = response.body ?: throw java.io.IOException("Empty response body")
                if (!response.isSuccessful) {
                    throw ProviderHttpException(
                        statusCode = response.code,
                        retryAfterMillis = parseRetryAfterMillis(response.header("Retry-After")),
                        body = responseBody.string()
                    )
                }
                parseResponsesSse(responseBody.byteStream(), onDelta)
            }
        }
    }

    private suspend fun executeResponses(
        request: ChatRequest,
        apiKey: String,
        url: String
    ): ChatResponse {
        val body = buildResponsesPayload(request, stream = false)
            .toString()
            .toRequestBody(JSON_MEDIA_TYPE)
        return withContext(Dispatchers.IO) {
            val httpRequest = authorizedRequest(url, apiKey).post(body).build()
            client.newCall(httpRequest).execute().use { response ->
                val responseBody = response.body ?: throw java.io.IOException("Empty response body")
                val responseText = responseBody.string()
                if (!response.isSuccessful) {
                    throw ProviderHttpException(
                        statusCode = response.code,
                        retryAfterMillis = parseRetryAfterMillis(response.header("Retry-After")),
                        body = responseText
                    )
                }
                parseResponsesResponse(json.parseToJsonElement(responseText).jsonObject)
            }
        }
    }

    private suspend fun executeChatCompletionsStream(
        request: ChatRequest,
        apiKey: String,
        url: String,
        onDelta: (StreamDelta) -> Unit
    ): ChatResponse {
        val body = buildChatCompletionsPayload(request, stream = true)
            .toString()
            .toRequestBody(JSON_MEDIA_TYPE)
        return withContext(Dispatchers.IO) {
            val httpRequest = authorizedRequest(url, apiKey, acceptSse = true).post(body).build()
            client.newCall(httpRequest).execute().use { response ->
                val responseBody = response.body ?: throw java.io.IOException("Empty response body")
                if (!response.isSuccessful) {
                    throw ProviderHttpException(
                        statusCode = response.code,
                        retryAfterMillis = parseRetryAfterMillis(response.header("Retry-After")),
                        body = responseBody.string()
                    )
                }
                parseChatCompletionsSse(responseBody.byteStream(), onDelta)
            }
        }
    }

    private suspend fun executeChatCompletions(
        request: ChatRequest,
        apiKey: String,
        url: String
    ): ChatResponse {
        val body = buildChatCompletionsPayload(request, stream = false)
            .toString()
            .toRequestBody(JSON_MEDIA_TYPE)
        return withContext(Dispatchers.IO) {
            val httpRequest = authorizedRequest(url, apiKey).post(body).build()
            client.newCall(httpRequest).execute().use { response ->
                val responseBody = response.body ?: throw java.io.IOException("Empty response body")
                val responseText = responseBody.string()
                if (!response.isSuccessful) {
                    throw ProviderHttpException(
                        statusCode = response.code,
                        retryAfterMillis = parseRetryAfterMillis(response.header("Retry-After")),
                        body = responseText
                    )
                }

                val root = json.parseToJsonElement(responseText).jsonObject
                val choice = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                val message = choice?.get("message")?.jsonObject
                ChatResponse(
                    content = message?.get("content")?.jsonPrimitive?.contentOrNull,
                    toolCalls = parseChatCompletionsToolCalls(message?.get("tool_calls")),
                    usage = root["usage"]?.jsonObject?.let(::parseChatCompletionsUsage)
                )
            }
        }
    }

    private fun authorizedRequest(url: String, apiKey: String, acceptSse: Boolean = false): Request.Builder {
        return Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .apply { if (acceptSse) addHeader("Accept", "text/event-stream") }
    }

    private fun parseChatCompletionsSse(
        inputStream: InputStream,
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
                if (index in committedToolIndexes || partial.id.isBlank() || partial.name.isBlank()) return@forEach
                toolCalls += ToolCall(
                    id = partial.id,
                    function = ToolCallFunction(partial.name, partial.arguments.toString())
                )
                committedToolIndexes += index
            }
        }

        try {
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val current = line ?: continue
                if (!current.startsWith("data:")) continue
                val data = current.removePrefix("data:").trim()
                if (data == "[DONE]") {
                    commitPendingToolCalls()
                    if (!doneSent) {
                        doneSent = true
                        onDelta(StreamDelta.Done)
                    }
                    break
                }

                val event = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: continue
                (event["usage"] as? JsonObject)?.takeIf { it.isNotEmpty() }?.let {
                    usage = parseChatCompletionsUsage(it)
                }
                val choice = event["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: continue
                val delta = choice["delta"] as? JsonObject
                val content = delta?.get("content")?.jsonPrimitive?.contentOrNull
                if (!content.isNullOrEmpty()) {
                    fullContent.append(content)
                    onDelta(StreamDelta.Content(content))
                }

                (delta?.get("tool_calls") as? kotlinx.serialization.json.JsonArray)?.forEach { toolElement ->
                    val tool = toolElement.jsonObject
                    val index = tool["index"]?.jsonPrimitive?.intOrNull ?: partialToolCalls.size
                    val partial = partialToolCalls.getOrPut(index) { PartialToolCall() }
                    tool["id"]?.jsonPrimitive?.contentOrNull?.let { if (partial.id.isBlank()) partial.id = it }
                    val function = tool["function"] as? JsonObject
                    function?.get("name")?.jsonPrimitive?.contentOrNull?.let {
                        if (partial.name.isBlank()) partial.name = it
                    }
                    ensureToolCallAnnounced(partial)
                    function?.get("arguments")?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotEmpty)?.let { args ->
                        partial.arguments.append(args)
                        ensureToolCallAnnounced(partial)
                        if (partial.id.isNotBlank()) onDelta(StreamDelta.ToolCallDelta(partial.id, args))
                    }
                }
                if (choice["finish_reason"]?.jsonPrimitive?.contentOrNull == "tool_calls") {
                    commitPendingToolCalls()
                }
            }
        } finally {
            runCatching { reader.close() }
            runCatching { inputStream.close() }
        }

        if (!doneSent) {
            val emittedVisibleOutput = fullContent.isNotEmpty() || partialToolCalls.isNotEmpty()
            if (!emittedVisibleOutput) {
                throw IncompleteSseException("OpenAI-compatible stream ended before a completion marker")
            }
            onDelta(StreamDelta.Error("Stream ended before [DONE]; tool calls were not executed."))
            return ChatResponse(content = fullContent.toString().ifBlank { null }, toolCalls = null, usage = usage)
        }

        commitPendingToolCalls()
        return ChatResponse(
            content = fullContent.toString().ifBlank { null },
            toolCalls = toolCalls.ifEmpty { null },
            usage = usage
        )
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
        val RESPONSES_UNSUPPORTED_STATUS_CODES = setOf(404, 405, 501)
    }
}
