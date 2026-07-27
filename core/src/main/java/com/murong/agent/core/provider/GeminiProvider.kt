package com.murong.agent.core.provider

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Native Google Gemini GenerateContent implementation.
 *
 * Gemini 3.6 Flash rejects the generic temperature/topP/topK controls in its
 * current API.  Keeping this separate from OpenAI compatibility therefore
 * prevents a valid Gemini key from being sent an invalid OpenAI payload.
 */
class GeminiProvider : ModelProvider {
    override val name = "Google Gemini"
    override val id = "gemini"
    override val defaultBaseUrl = "https://generativelanguage.googleapis.com/v1beta"
    override val defaultModel = "gemini-3.6-flash"
    override val supportsReasoning = false

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun chatStream(
        request: ChatRequest,
        apiKey: String,
        baseUrl: String?,
        onDelta: (StreamDelta) -> Unit
    ): ChatResponse = execute(
        request = normalizeChatRequestForProvider(request),
        apiKey = apiKey,
        baseUrl = baseUrl ?: defaultBaseUrl,
        stream = true,
        onDelta = onDelta
    )

    override suspend fun chat(
        request: ChatRequest,
        apiKey: String,
        baseUrl: String?
    ): ChatResponse = execute(
        request = normalizeChatRequestForProvider(request),
        apiKey = apiKey,
        baseUrl = baseUrl ?: defaultBaseUrl,
        stream = false,
        onDelta = {}
    )

    private suspend fun execute(
        request: ChatRequest,
        apiKey: String,
        baseUrl: String,
        stream: Boolean,
        onDelta: (StreamDelta) -> Unit
    ): ChatResponse {
        val body = buildGeminiPayload(request).toString().toRequestBody(JSON_MEDIA_TYPE)
        val url = geminiEndpoint(baseUrl, request.model, stream)
        return withContext(Dispatchers.IO) {
            val httpRequest = Request.Builder()
                .url(url)
                .addHeader("Content-Type", "application/json")
                .addHeader("x-goog-api-key", apiKey.trim())
                .apply { if (stream) addHeader("Accept", "text/event-stream") }
                .post(body)
                .build()
            client.newCall(httpRequest).execute().use { response ->
                val responseBody = response.body ?: throw java.io.IOException("Empty Gemini response body")
                if (!response.isSuccessful) {
                    throw ProviderHttpException(
                        statusCode = response.code,
                        retryAfterMillis = parseRetryAfterMillis(response.header("Retry-After")),
                        body = responseBody.string()
                    )
                }
                if (stream) parseGeminiSse(responseBody.byteStream(), onDelta)
                else parseGeminiResponse(json.parseToJsonElement(responseBody.string()).jsonObject)
            }
        }
    }

    private fun buildGeminiPayload(request: ChatRequest): JsonObject {
        val system = request.messages
            .filter { it.role == "system" || it.role == "developer" }
            .mapNotNull { it.content?.trim()?.takeIf(String::isNotBlank) }
            .joinToString("\n\n")
        val callNames = mutableMapOf<String, String>()
        return buildJsonObject {
            if (system.isNotBlank()) {
                putJsonObject("system_instruction") {
                    putJsonArray("parts") { addJsonObject { put("text", system) } }
                }
            }
            putJsonArray("contents") {
                request.messages
                    .filterNot { it.role == "system" || it.role == "developer" }
                    .forEach { message ->
                        add(buildGeminiContent(message, callNames))
                    }
            }
            request.tools?.let { rawTools ->
                val declarations = parseGeminiToolDeclarations(rawTools)
                if (declarations.isNotEmpty()) {
                    putJsonArray("tools") {
                        addJsonObject { put("function_declarations", declarations) }
                    }
                }
            }
            // Gemini 3.6 Flash no longer accepts generic sampling parameters.
            putJsonObject("generationConfig") { put("maxOutputTokens", request.maxTokens) }
        }
    }

    private fun buildGeminiContent(
        message: ChatMessage,
        callNames: MutableMap<String, String>
    ): JsonObject = buildJsonObject {
        val isAssistant = message.role == "assistant"
        put("role", if (isAssistant) "model" else "user")
        putJsonArray("parts") {
            if (message.role == "tool") {
                val name = callNames[message.toolCallId] ?: message.name ?: message.toolCallId ?: "tool"
                addJsonObject {
                    putJsonObject("functionResponse") {
                        put("name", name)
                        putJsonObject("response") { put("result", message.content.orEmpty()) }
                    }
                }
            } else {
                message.content?.takeIf(String::isNotBlank)?.let { text ->
                    addJsonObject { put("text", text) }
                }
                message.images.forEach { image ->
                    addJsonObject {
                        putJsonObject("inlineData") {
                            put("mimeType", image.mimeType)
                            put("data", image.base64Data)
                        }
                    }
                }
                message.toolCalls.orEmpty().forEach { call ->
                    callNames[call.id] = call.function.name
                    addJsonObject {
                        putJsonObject("functionCall") {
                            put("name", call.function.name)
                            val args = runCatching { json.parseToJsonElement(call.function.arguments) }
                                .getOrDefault(JsonObject(emptyMap()))
                            put("args", args)
                        }
                    }
                }
                if (message.content.isNullOrBlank() && message.images.isEmpty() && message.toolCalls.isNullOrEmpty()) {
                    addJsonObject { put("text", "") }
                }
            }
        }
    }

    private fun parseGeminiToolDeclarations(rawTools: String): JsonArray = runCatching {
        buildJsonArray {
            json.parseToJsonElement(rawTools).jsonArray.forEach { toolElement ->
                val function = toolElement.jsonObject["function"]?.jsonObject ?: return@forEach
                addJsonObject {
                    function["name"]?.let { put("name", it) }
                    function["description"]?.let { put("description", it) }
                    function["parameters"]?.let { put("parametersJsonSchema", it) }
                }
            }
        }
    }.getOrDefault(JsonArray(emptyList()))

    private fun geminiEndpoint(baseUrl: String, model: String, stream: Boolean): String {
        val base = baseUrl.trim().trimEnd('/')
        require(base.isNotBlank()) { "Gemini base URL must not be blank" }
        val operation = if (stream) "streamGenerateContent?alt=sse" else "generateContent"
        return if (base.contains(":generateContent") || base.contains(":streamGenerateContent")) base
        else "$base/models/${model.trim()}:$operation"
    }

    private fun parseGeminiSse(input: InputStream, onDelta: (StreamDelta) -> Unit): ChatResponse {
        val content = StringBuilder()
        val reasoning = StringBuilder()
        val calls = mutableListOf<ToolCall>()
        var usage: Usage? = null
        BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
            reader.lineSequence().forEach { line ->
                if (!line.startsWith("data:")) return@forEach
                val payload = line.removePrefix("data:").trim()
                if (payload.isBlank() || payload == "[DONE]") return@forEach
                val parsed = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return@forEach
                val delta = extractGeminiDelta(parsed, calls)
                if (delta.reasoning.isNotEmpty()) {
                    reasoning.append(delta.reasoning)
                    onDelta(StreamDelta.Reasoning(delta.reasoning))
                }
                if (delta.content.isNotEmpty()) {
                    content.append(delta.content)
                    onDelta(StreamDelta.Content(delta.content))
                }
                usage = parsed["usageMetadata"]?.jsonObject?.let(::parseGeminiUsage) ?: usage
            }
        }
        onDelta(StreamDelta.Done)
        return ChatResponse(
            content = content.toString().ifBlank { null },
            toolCalls = calls.distinctBy { it.id }.ifEmpty { null },
            usage = usage,
            reasoningContent = reasoning.toString().ifBlank { null }
        )
    }

    private fun parseGeminiResponse(root: JsonObject): ChatResponse {
        val calls = mutableListOf<ToolCall>()
        val delta = extractGeminiDelta(root, calls)
        return ChatResponse(
            content = delta.content.ifBlank { null },
            toolCalls = calls.ifEmpty { null },
            usage = root["usageMetadata"]?.jsonObject?.let(::parseGeminiUsage),
            reasoningContent = delta.reasoning.ifBlank { null }
        )
    }

    private data class GeminiDelta(val content: String, val reasoning: String)

    private fun extractGeminiDelta(root: JsonObject, calls: MutableList<ToolCall>): GeminiDelta {
        val visible = StringBuilder()
        val reasoning = StringBuilder()
        root["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("content")?.jsonObject?.get("parts")?.jsonArray.orEmpty()
            .forEachIndexed { index, partElement ->
                val part = partElement.jsonObject
                val text = part["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (part["thought"]?.jsonPrimitive?.contentOrNull == "true") reasoning.append(text) else visible.append(text)
                part["functionCall"]?.jsonObject?.let { function ->
                    val name = function["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    if (name.isNotBlank()) {
                        calls += ToolCall(
                            id = "gemini-$name-$index",
                            function = ToolCallFunction(
                                name = name,
                                arguments = function["args"]?.toString() ?: "{}"
                            )
                        )
                    }
                }
            }
        return GeminiDelta(visible.toString(), reasoning.toString())
    }

    private fun parseGeminiUsage(value: JsonObject): Usage = Usage(
        promptTokens = value["promptTokenCount"]?.jsonPrimitive?.intOrNull ?: 0,
        completionTokens = value["candidatesTokenCount"]?.jsonPrimitive?.intOrNull ?: 0,
        totalTokens = value["totalTokenCount"]?.jsonPrimitive?.intOrNull ?: 0
    )

    private companion object {
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
