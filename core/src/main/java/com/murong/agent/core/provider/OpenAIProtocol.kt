package com.murong.agent.core.provider

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.URI
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

internal enum class OpenAIWireProtocol {
    RESPONSES,
    CHAT_COMPLETIONS
}

internal data class OpenAIEndpoint(
    val protocol: OpenAIWireProtocol,
    val url: String,
    val chatCompletionsFallbackUrl: String? = null
)

private val protocolJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

/**
 * Official OpenAI uses Responses by default. Custom OpenAI-compatible endpoints
 * stay on Chat Completions unless the user explicitly supplies a /responses URL.
 * Supplying /chat/completions always opts out, including for api.openai.com.
 */
internal fun resolveOpenAIEndpoint(baseUrl: String): OpenAIEndpoint {
    val endpoint = baseUrl.trim().trimEnd('/')
    require(endpoint.isNotBlank()) { "OpenAI base URL must not be blank" }

    val path = runCatching { URI(endpoint).path.orEmpty().trimEnd('/') }.getOrDefault("")
    val host = runCatching { URI(endpoint).host.orEmpty().lowercase() }.getOrDefault("")
    val explicitChatCompletions = path.endsWith("/chat/completions")
    val explicitResponses = path.endsWith("/responses")

    if (explicitChatCompletions) {
        return OpenAIEndpoint(OpenAIWireProtocol.CHAT_COMPLETIONS, endpoint)
    }
    if (explicitResponses) {
        return OpenAIEndpoint(
            protocol = OpenAIWireProtocol.RESPONSES,
            url = endpoint,
            chatCompletionsFallbackUrl = endpoint.removeSuffix("/responses") + "/chat/completions"
        )
    }

    if (host == "api.openai.com") {
        val versionedBase = if (path.isBlank() || path == "/") "$endpoint/v1" else endpoint
        return OpenAIEndpoint(
            protocol = OpenAIWireProtocol.RESPONSES,
            url = "$versionedBase/responses",
            chatCompletionsFallbackUrl = "$versionedBase/chat/completions"
        )
    }

    return OpenAIEndpoint(
        protocol = OpenAIWireProtocol.CHAT_COMPLETIONS,
        url = "$endpoint/chat/completions"
    )
}

internal fun buildChatCompletionsPayload(request: ChatRequest, stream: Boolean): JsonObject {
    return buildJsonObject {
        put("model", request.model)
        put("messages", buildJsonArray {
            request.messages.forEach { message -> add(buildChatCompletionsMessage(message)) }
        })
        put("temperature", request.temperature)
        put("max_tokens", request.maxTokens)
        put("stream", stream)
        if (!request.reasoningEffort.isNullOrBlank()) {
            put("reasoning_effort", request.reasoningEffort)
        }
        if (stream) {
            putJsonObject("stream_options") { put("include_usage", true) }
        }
        request.tools?.let { tools -> put("tools", protocolJson.parseToJsonElement(tools)) }
    }
}

internal fun buildResponsesPayload(request: ChatRequest, stream: Boolean): JsonObject {
    val continuation = request.responsesContinuation
    val instructions = request.messages
        .filter { it.role == "system" || it.role == "developer" }
        .mapNotNull { it.content?.takeIf(String::isNotBlank) }
        .joinToString("\n\n")

    return buildJsonObject {
        put("model", request.model)
        put("input", buildResponsesInput(request))
        if (instructions.isNotBlank()) put("instructions", instructions)
        put("max_output_tokens", request.maxTokens)
        put("stream", stream)
        put("store", true)
        continuation?.previousResponseId?.takeIf(String::isNotBlank)?.let {
            put("previous_response_id", it)
        }
        if (!request.reasoningEffort.isNullOrBlank()) {
            putJsonObject("reasoning") {
                put("effort", request.reasoningEffort)
                put("summary", "auto")
            }
            putJsonArray("include") { add("reasoning.encrypted_content") }
        }
        request.tools?.let { tools ->
            put("tools", flattenResponsesTools(protocolJson.parseToJsonElement(tools)))
            put("parallel_tool_calls", true)
        }
    }
}

private fun buildResponsesInput(request: ChatRequest): JsonArray = buildJsonArray {
    val continuation = request.responsesContinuation
    if (continuation?.previousResponseId.isNullOrBlank()) {
        continuation?.reasoningItems.orEmpty().forEach { rawItem ->
            runCatching { protocolJson.parseToJsonElement(rawItem) }.getOrNull()?.let { add(it) }
        }
    }

    selectResponsesMessages(request).forEach { message ->
        when (message.role) {
            "tool" -> {
                val callId = message.toolCallId?.takeIf(String::isNotBlank) ?: return@forEach
                addJsonObject {
                    put("type", "function_call_output")
                    put("call_id", callId)
                    put("output", message.content.orEmpty())
                }
            }

            "assistant" -> {
                message.content?.takeIf(String::isNotBlank)?.let { content ->
                    addJsonObject {
                        put("type", "message")
                        put("role", "assistant")
                        put("content", content)
                    }
                }
                message.toolCalls.orEmpty().forEach { toolCall ->
                    addJsonObject {
                        put("type", "function_call")
                        put("call_id", toolCall.id)
                        put("name", toolCall.function.name)
                        put("arguments", toolCall.function.arguments.ifBlank { "{}" })
                    }
                }
            }

            "user" -> add(buildResponsesUserMessage(message))
            else -> message.content?.takeIf(String::isNotBlank)?.let { content ->
                addJsonObject {
                    put("type", "message")
                    put("role", message.role)
                    put("content", content)
                }
            }
        }
    }
}

/** Avoid resending history already represented by previous_response_id. */
private fun selectResponsesMessages(request: ChatRequest): List<ChatMessage> {
    val conversation = request.messages.filterNot { it.role == "system" || it.role == "developer" }
    if (request.responsesContinuation?.previousResponseId.isNullOrBlank()) return conversation

    val lastAssistantIndex = conversation.indexOfLast { it.role == "assistant" }
    if (lastAssistantIndex >= 0 && lastAssistantIndex < conversation.lastIndex) {
        return conversation.drop(lastAssistantIndex + 1)
    }
    return conversation.takeLast(1)
}

private fun buildResponsesUserMessage(message: ChatMessage): JsonObject = buildJsonObject {
    put("type", "message")
    put("role", "user")
    if (message.images.isEmpty()) {
        put("content", message.content.orEmpty())
    } else {
        putJsonArray("content") {
            message.content?.takeIf(String::isNotBlank)?.let { text ->
                addJsonObject {
                    put("type", "input_text")
                    put("text", text)
                }
            }
            message.images.forEach { image ->
                addJsonObject {
                    put("type", "input_image")
                    put("image_url", "data:${image.mimeType};base64,${image.base64Data}")
                }
            }
        }
    }
}

private fun flattenResponsesTools(element: JsonElement): JsonArray = buildJsonArray {
    element.jsonArray.forEach { toolElement ->
        val tool = toolElement.jsonObject
        val function = tool["function"] as? JsonObject
        if (tool["type"]?.jsonPrimitive?.contentOrNull == "function" && function != null) {
            addJsonObject {
                put("type", "function")
                function["name"]?.let { put("name", it) }
                function["description"]?.let { put("description", it) }
                function["parameters"]?.let { put("parameters", it) }
                function["strict"]?.let { put("strict", it) }
                tool["strict"]?.let { put("strict", it) }
            }
        } else {
            add(toolElement)
        }
    }
}

private fun buildChatCompletionsMessage(message: ChatMessage): JsonObject = buildJsonObject {
    put("role", message.role)
    if (message.images.isNotEmpty()) {
        put("content", buildJsonArray {
            message.content?.takeIf(String::isNotBlank)?.let { text ->
                addJsonObject {
                    put("type", "text")
                    put("text", text)
                }
            }
            message.images.forEach { image ->
                addJsonObject {
                    put("type", "image_url")
                    putJsonObject("image_url") {
                        put("url", "data:${image.mimeType};base64,${image.base64Data}")
                    }
                }
            }
        })
    } else if (message.content != null) {
        put("content", message.content)
    }
    message.toolCalls?.let { toolCalls ->
        putJsonArray("tool_calls") {
            toolCalls.forEach { toolCall ->
                addJsonObject {
                    put("id", toolCall.id)
                    put("type", toolCall.type)
                    putJsonObject("function") {
                        put("name", toolCall.function.name)
                        put("arguments", toolCall.function.arguments)
                    }
                }
            }
        }
    }
    message.toolCallId?.let { put("tool_call_id", it) }
}

internal fun parseResponsesResponse(root: JsonObject): ChatResponse {
    val output = root["output"]?.jsonArray.orEmpty()
    val content = buildString {
        output.forEach { itemElement ->
            val item = itemElement.jsonObject
            if (item["type"]?.jsonPrimitive?.contentOrNull != "message") return@forEach
            item["content"]?.jsonArray.orEmpty().forEach { contentElement ->
                val contentObject = contentElement.jsonObject
                when (contentObject["type"]?.jsonPrimitive?.contentOrNull) {
                    "output_text", "refusal" -> append(contentObject["text"]?.jsonPrimitive?.contentOrNull.orEmpty())
                }
            }
        }
    }.ifBlank { null }

    val toolCalls = output.mapNotNull { itemElement ->
        val item = itemElement.jsonObject
        if (item["type"]?.jsonPrimitive?.contentOrNull != "function_call") return@mapNotNull null
        ToolCall(
            id = item["call_id"]?.jsonPrimitive?.contentOrNull
                ?: item["id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            function = ToolCallFunction(
                name = item["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                arguments = item["arguments"]?.jsonPrimitive?.contentOrNull ?: "{}"
            )
        )
    }.ifEmpty { null }

    val responseId = root["id"]?.jsonPrimitive?.contentOrNull
    val reasoningItems = output
        .filter { it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "reasoning" }
        .map(JsonElement::toString)
    val continuation = if (!responseId.isNullOrBlank() || reasoningItems.isNotEmpty()) {
        ResponsesContinuation(responseId, reasoningItems)
    } else {
        null
    }

    return ChatResponse(
        content = content,
        toolCalls = toolCalls,
        usage = root["usage"]?.jsonObject?.let(::parseResponsesUsage),
        responsesContinuation = continuation
    )
}

internal fun parseChatCompletionsToolCalls(element: JsonElement?): List<ToolCall>? {
    if (element == null) return null
    return runCatching {
        element.jsonArray.map { toolElement ->
            val tool = toolElement.jsonObject
            ToolCall(
                id = tool["id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                type = tool["type"]?.jsonPrimitive?.contentOrNull ?: "function",
                function = ToolCallFunction(
                    name = tool["function"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull.orEmpty(),
                    arguments = tool["function"]?.jsonObject?.get("arguments")?.jsonPrimitive?.contentOrNull ?: "{}"
                )
            )
        }
    }.getOrNull()
}

internal fun parseChatCompletionsUsage(objectValue: JsonObject): Usage {
    val promptTokens = objectValue["prompt_tokens"]?.jsonPrimitive?.intOrNull ?: 0
    val promptDetails = objectValue["prompt_tokens_details"] as? JsonObject
    val topLevelHit = objectValue["prompt_cache_hit_tokens"]?.jsonPrimitive?.intOrNull ?: 0
    val topLevelMiss = objectValue["prompt_cache_miss_tokens"]?.jsonPrimitive?.intOrNull ?: 0
    val nestedHit = promptDetails?.get("cached_tokens")?.jsonPrimitive?.intOrNull ?: 0
    val cacheHit = topLevelHit.takeIf { it > 0 } ?: nestedHit
    val cacheMiss = when {
        topLevelMiss > 0 -> topLevelMiss
        cacheHit > 0 && promptTokens > cacheHit -> promptTokens - cacheHit
        else -> 0
    }
    return Usage(
        promptTokens = promptTokens,
        completionTokens = objectValue["completion_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
        totalTokens = objectValue["total_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
        promptCacheHitTokens = cacheHit.takeIf { it > 0 },
        promptCacheMissTokens = cacheMiss.takeIf { it > 0 }
    )
}

private fun parseResponsesUsage(objectValue: JsonObject): Usage {
    val inputTokens = objectValue["input_tokens"]?.jsonPrimitive?.intOrNull ?: 0
    val outputTokens = objectValue["output_tokens"]?.jsonPrimitive?.intOrNull ?: 0
    val cachedTokens = (objectValue["input_tokens_details"] as? JsonObject)
        ?.get("cached_tokens")?.jsonPrimitive?.intOrNull ?: 0
    val reasoningTokens = (objectValue["output_tokens_details"] as? JsonObject)
        ?.get("reasoning_tokens")?.jsonPrimitive?.intOrNull ?: 0
    return Usage(
        promptTokens = inputTokens,
        completionTokens = outputTokens,
        totalTokens = objectValue["total_tokens"]?.jsonPrimitive?.intOrNull ?: inputTokens + outputTokens,
        promptCacheHitTokens = cachedTokens.takeIf { it > 0 },
        promptCacheMissTokens = (inputTokens - cachedTokens).takeIf { it > 0 },
        reasoningTokens = reasoningTokens.takeIf { it > 0 }
    )
}

private data class PartialResponsesToolCall(
    var itemId: String = "",
    var callId: String = "",
    var name: String = "",
    val arguments: StringBuilder = StringBuilder(),
    var announced: Boolean = false
)

/** Parses the native Responses API SSE event protocol. */
internal fun parseResponsesSse(
    inputStream: InputStream,
    onDelta: (StreamDelta) -> Unit
): ChatResponse {
    val reader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))
    val fullContent = StringBuilder()
    val partialCalls = linkedMapOf<Int, PartialResponsesToolCall>()
    var completedResponse: ChatResponse? = null
    var terminal = false
    var terminalError: String? = null
    var doneSent = false

    fun announce(call: PartialResponsesToolCall) {
        if (!call.announced && call.callId.isNotBlank() && call.name.isNotBlank()) {
            call.announced = true
            onDelta(StreamDelta.ToolCallStart(call.callId, call.name))
        }
    }

    fun emitDone() {
        if (!doneSent) {
            doneSent = true
            onDelta(StreamDelta.Done)
        }
    }

    try {
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            val current = line ?: continue
            if (!current.startsWith("data:")) continue
            val data = current.removePrefix("data:").trim()
            if (data.isBlank()) continue
            if (data == "[DONE]") {
                terminal = true
                emitDone()
                break
            }

            val event = runCatching { protocolJson.parseToJsonElement(data).jsonObject }.getOrNull() ?: continue
            when (event["type"]?.jsonPrimitive?.contentOrNull) {
                "response.output_text.delta" -> {
                    val delta = event["delta"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    if (delta.isNotEmpty()) {
                        fullContent.append(delta)
                        onDelta(StreamDelta.Content(delta))
                    }
                }

                "response.reasoning_summary_text.delta" -> {
                    val delta = event["delta"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    if (delta.isNotEmpty()) onDelta(StreamDelta.Reasoning(delta))
                }

                "response.output_item.added" -> {
                    val item = event["item"] as? JsonObject ?: continue
                    if (item["type"]?.jsonPrimitive?.contentOrNull != "function_call") continue
                    val index = event["output_index"]?.jsonPrimitive?.intOrNull ?: partialCalls.size
                    val call = partialCalls.getOrPut(index) { PartialResponsesToolCall() }
                    call.itemId = item["id"]?.jsonPrimitive?.contentOrNull ?: call.itemId
                    call.callId = item["call_id"]?.jsonPrimitive?.contentOrNull ?: call.callId
                    call.name = item["name"]?.jsonPrimitive?.contentOrNull ?: call.name
                    item["arguments"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotEmpty)?.let {
                        call.arguments.append(it)
                    }
                    announce(call)
                }

                "response.function_call_arguments.delta" -> {
                    val itemId = event["item_id"]?.jsonPrimitive?.contentOrNull
                    val index = event["output_index"]?.jsonPrimitive?.intOrNull
                    val call = index?.let { partialCalls.getOrPut(it) { PartialResponsesToolCall() } }
                        ?: partialCalls.values.firstOrNull { it.itemId == itemId }
                        ?: continue
                    val delta = event["delta"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    if (delta.isNotEmpty()) {
                        call.arguments.append(delta)
                        announce(call)
                        if (call.callId.isNotBlank()) {
                            onDelta(StreamDelta.ToolCallDelta(call.callId, delta))
                        }
                    }
                }

                "response.output_item.done" -> {
                    val item = event["item"] as? JsonObject ?: continue
                    if (item["type"]?.jsonPrimitive?.contentOrNull != "function_call") continue
                    val index = event["output_index"]?.jsonPrimitive?.intOrNull ?: partialCalls.size
                    val call = partialCalls.getOrPut(index) { PartialResponsesToolCall() }
                    call.itemId = item["id"]?.jsonPrimitive?.contentOrNull ?: call.itemId
                    call.callId = item["call_id"]?.jsonPrimitive?.contentOrNull ?: call.callId
                    call.name = item["name"]?.jsonPrimitive?.contentOrNull ?: call.name
                    item["arguments"]?.jsonPrimitive?.contentOrNull?.let { finalArguments ->
                        if (finalArguments.isNotEmpty() && finalArguments != call.arguments.toString()) {
                            call.arguments.clear()
                            call.arguments.append(finalArguments)
                        }
                    }
                    announce(call)
                }

                "response.completed" -> {
                    val response = event["response"] as? JsonObject
                    completedResponse = response?.let(::parseResponsesResponse)
                    terminal = true
                    emitDone()
                }

                "response.incomplete" -> {
                    val responseObject = event["response"] as? JsonObject
                    val partialResponse = responseObject?.let(::parseResponsesResponse)
                    if (!partialResponse?.content.isNullOrBlank() && partialResponse?.toolCalls.isNullOrEmpty()) {
                        completedResponse = partialResponse
                        terminal = true
                        onDelta(
                            StreamDelta.Error(
                                "OpenAI response was incomplete (${extractIncompleteReason(responseObject)}); " +
                                    "partial text was preserved."
                            )
                        )
                        emitDone()
                    } else {
                        terminal = true
                        terminalError = "OpenAI response was incomplete (${extractIncompleteReason(responseObject)})"
                        onDelta(StreamDelta.Error(terminalError!!))
                    }
                }

                "response.failed", "error" -> {
                    terminal = true
                    terminalError = extractResponsesError(event)
                    onDelta(StreamDelta.Error(terminalError ?: "OpenAI Responses stream failed"))
                }
            }
        }
    } finally {
        runCatching { reader.close() }
        runCatching { inputStream.close() }
    }

    terminalError?.let { throw java.io.IOException(it) }
    if (!terminal) {
        val visibleOutput = fullContent.isNotEmpty() || partialCalls.isNotEmpty()
        if (!visibleOutput) throw IncompleteSseException("OpenAI Responses stream ended before response.completed")
        onDelta(StreamDelta.Error("Stream ended before response.completed; tool calls were not executed."))
        return ChatResponse(content = fullContent.toString().ifBlank { null }, toolCalls = null)
    }

    val completed = completedResponse
    val calls = completed?.toolCalls ?: partialCalls.toSortedMap().values.mapNotNull { call ->
        if (call.callId.isBlank() || call.name.isBlank()) return@mapNotNull null
        ToolCall(
            id = call.callId,
            function = ToolCallFunction(call.name, call.arguments.toString().ifBlank { "{}" })
        )
    }.ifEmpty { null }

    return ChatResponse(
        content = completed?.content ?: fullContent.toString().ifBlank { null },
        toolCalls = calls,
        usage = completed?.usage,
        responsesContinuation = completed?.responsesContinuation
    )
}

private fun extractIncompleteReason(response: JsonObject?): String {
    return (response?.get("incomplete_details") as? JsonObject)
        ?.get("reason")
        ?.jsonPrimitive
        ?.contentOrNull
        ?: "unknown reason"
}

private fun extractResponsesError(event: JsonObject): String {
    val responseError = (event["response"] as? JsonObject)?.get("error") as? JsonObject
    val directError = event["error"] as? JsonObject
    return responseError?.get("message")?.jsonPrimitive?.contentOrNull
        ?: directError?.get("message")?.jsonPrimitive?.contentOrNull
        ?: event["message"]?.jsonPrimitive?.contentOrNull
        ?: "OpenAI Responses stream failed"
}

private fun JsonArray?.orEmpty(): JsonArray = this ?: JsonArray(emptyList())
