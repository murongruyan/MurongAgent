package com.murong.agent.core.provider

import com.murong.agent.core.tool.BuiltinVisionRuntime
import com.murong.agent.core.tool.BuiltinVisionEngine
import com.murong.agent.core.tool.BuiltinVisionStreamChunk
import com.murong.agent.core.tool.BuiltinVisionStreamKind
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal const val BUILTIN_LOCAL_VISIBLE_LANGUAGE_POLICY =
    "当用户主要使用中文时，所有可见的思考过程和最终回答都必须使用中文；" +
        "代码、日志、错误、协议字段、专有名词及用户要求原样输出的文本除外。"

/**
 * Uses the model selected in the private on-device model center as a normal
 * chat/Agent provider. The runtime is shared with GUI vision so weights are
 * loaded only once and no loopback HTTP server or API key is required.
 */
class BuiltinLocalProvider : ModelProvider {
    override val name: String = "内置本地模型"
    override val id: String = ID
    override val defaultBaseUrl: String = ""
    override val defaultModel: String = "builtin-selected"
    override val supportsReasoning: Boolean
        get() = supportedReasoningEfforts.isNotEmpty()
    override val supportedReasoningEfforts: List<String>
        get() = BuiltinVisionRuntime.activeModel()?.reasoningModes?.map { it.id }.orEmpty()

    override fun formatReasoningDisplayName(reasoningEffort: String?): String? =
        BuiltinVisionRuntime.activeModel()?.reasoningDisplayName(reasoningEffort)

    override fun buildReasoningHint(modelId: String, reasoningEffort: String?): String? {
        val descriptor = BuiltinVisionRuntime.model(modelId) ?: return null
        if (descriptor.reasoningModes.isEmpty()) {
            return "${descriptor.displayName} 不提供思考模式。"
        }
        return when (descriptor.resolveReasoningMode(reasoningEffort)) {
            "on" -> "${descriptor.displayName} 将输出并在本机展示思考过程；速度会明显变慢。"
            else -> "${descriptor.displayName} 已关闭思考，以获得更快的首字响应。"
        }
    }

    override fun formatModelDisplayName(modelId: String): String =
        BuiltinVisionRuntime.model(modelId)?.displayName
            ?: BuiltinVisionRuntime.activeModelDisplayName()
            ?: "尚未安装本地模型"

    override suspend fun chatStream(
        request: ChatRequest,
        apiKey: String,
        baseUrl: String?,
        onDelta: (StreamDelta) -> Unit
    ): ChatResponse {
        val descriptor = BuiltinVisionRuntime.model(request.model)
            ?: error("尚未安装或选择内置本地模型")
        val reasoningMode = descriptor.resolveReasoningMode(request.reasoningEffort)
        val thinkingEnabled = reasoningMode == "on"
        if (thinkingEnabled) onDelta(StreamDelta.ReasoningStart)
        val visibleStream = LocalVisibleStream(
            startInReasoning = thinkingEnabled && descriptor.engine == BuiltinVisionEngine.MNN,
            emitContent = { text -> onDelta(StreamDelta.Content(text)) },
            emitReasoning = { text -> onDelta(StreamDelta.Reasoning(text)) }
        )
        val response = complete(request) { chunk ->
            when (chunk.kind) {
                BuiltinVisionStreamKind.REASONING ->
                    onDelta(StreamDelta.Reasoning(chunk.text))
                BuiltinVisionStreamKind.RAW,
                BuiltinVisionStreamKind.CONTENT ->
                    visibleStream.accept(chunk.text)
            }
        }
        visibleStream.finish()
        if (!visibleStream.receivedContentChunks) {
            response.content?.takeIf { it.isNotBlank() }?.let {
                onDelta(StreamDelta.Content(it))
            }
        }
        response.toolCalls.orEmpty().forEach { call ->
            onDelta(StreamDelta.ToolCallStart(call.id, call.function.name))
            onDelta(StreamDelta.ToolCallDelta(call.id, call.function.arguments))
        }
        onDelta(StreamDelta.Done)
        return response
    }

    override suspend fun chat(
        request: ChatRequest,
        apiKey: String,
        baseUrl: String?
    ): ChatResponse = complete(request)

    private suspend fun complete(
        request: ChatRequest,
        onRawChunk: ((BuiltinVisionStreamChunk) -> Unit)? = null
    ): ChatResponse {
        check(BuiltinVisionRuntime.isReady(request.model)) {
            "所选内置本地模型尚未安装或运行时不可用，请在工具页的“本地模型中心”检查"
        }
        val toolProtocolEnabled = compactToolDefinitions(request.tools).isNotBlank()
        val prompt = buildLocalPrompt(request)
        val image = request.messages.asReversed()
            .firstOrNull { message -> message.role.equals("user", ignoreCase = true) }
            ?.images
            ?.lastOrNull()
        val raw = BuiltinVisionRuntime.chat(
            prompt = prompt,
            image = image,
            maxTokens = request.maxTokens.coerceAtMost(1_024),
            reasoningMode = request.reasoningEffort,
            modelId = request.model,
            onToken = onRawChunk
        )
        val descriptor = BuiltinVisionRuntime.model(request.model)
        val parsed = parseLocalResponse(
            raw = raw,
            startsInReasoning = descriptor?.engine == BuiltinVisionEngine.MNN &&
                descriptor.resolveReasoningMode(request.reasoningEffort) == "on",
            allowToolCalls = toolProtocolEnabled,
        )
        val promptTokens = estimateTokens(prompt)
        val completionTokens = estimateTokens(raw)
        return ChatResponse(
            content = parsed.content,
            toolCalls = parsed.toolCalls.takeIf { it.isNotEmpty() },
            usage = Usage(
                promptTokens = promptTokens,
                completionTokens = completionTokens,
                totalTokens = promptTokens + completionTokens
            )
        )
    }

    private fun buildLocalPrompt(request: ChatRequest): String {
        val tools = compactToolDefinitions(request.tools).take(3_000)
        val phoneActionRequest = isPhoneActionRequest(request.tools)
        val protocol = if (tools.isBlank()) {
            """
                你是 Murong 的极速离线对话助手。本轮只进行自然语言聊天、问答或总结，
                没有任何工具、手机操作或代码执行能力。
                $BUILTIN_LOCAL_VISIBLE_LANGUAGE_POLICY
                直接给出简洁、自然且有帮助的回答。绝对不要输出、解释、模仿或包裹
                <tool_call>、[TOOL]、JSON 工具参数、函数名或系统协议；这些内容对用户无效。
                如果问题需要联网、看屏幕或执行操作，明确说明当前轻量本地对话模型做不到，
                并建议用户切换到相应任务模型，不要伪造工具调用。
            """.trimIndent()
        } else if (phoneActionRequest) {
            """
                你是 Murong 的手机单步动作执行器。根据当前截图和任务，每轮只决定一个动作。
                先用一行不超过 32 个字的中文说明当前判断，并把同一句判断写入动作的 message 字段，
                这句话会原样显示给用户。然后必须且只能输出下面两种动作格式之一。
                禁止长篇分析、Markdown 或第二个动作：
                <tool_call>{"name":"phone_action","arguments":{"action":"tap","message":"看到目标按钮，准备点击","x":500,"y":500}}</tool_call>
                <tool_call>{"name":"phone_finish","arguments":{"message":"已完成并验证"}}</tool_call>
                arguments 必须是合法 JSON 对象，并严格使用系统协议列出的字段。看不清时用 wait、back 或 take_over，禁止猜坐标。
                $BUILTIN_LOCAL_VISIBLE_LANGUAGE_POLICY
            """.trimIndent()
        } else {
            """
            你是 Murong 内置的离线多模态助手。你可以聊天、看图、写代码，也可以调用工具完成任务。
            必须遵守系统指令。需要工具时，只输出以下格式，不要把工具调用写进普通解释：
            $BUILTIN_LOCAL_VISIBLE_LANGUAGE_POLICY
            <tool_call>{"name":"工具名","arguments":{"参数":"值"}}</tool_call>
            一次可输出多个 tool_call。arguments 必须是 JSON 对象。工具结果会在下一轮以 [TOOL] 提供。
            [TOOL] 只表示系统回传的工具结果，助手绝对不能输出或仿写 [TOOL]。
            不需要工具时直接回答，不要输出 tool_call 标签。若本轮附带图片，应结合图片回答。
            只填写本次动作需要的参数；禁止用字符串 "null" 代替空值，禁止猜测不存在的参数。
            Android 的 gui 启动应用必须用 action="launch"、packageName=已安装包名，target 可省略；
            例如打开抖音只能输出 <tool_call>{"name":"gui","arguments":{"action":"launch","packageName":"com.ss.android.ugc.aweme"}}</tool_call>，
            不得把 gui 的其他可选参数全部补齐。
            不确定包名时先用 shell/Android 工具查询，抖音中国版通常是 com.ss.android.ugc.aweme，
            com.zhiliaoapp.musically 是 TikTok 国际版。不要重复执行已经返回参数错误的相同调用。
            ask_user 只在确实无法自行继续时使用，格式必须是：
            {"questions":[{"header":"短标题","question":"问题","options":[{"label":"推荐项","description":"说明"},{"label":"另一项","description":"说明"}]}]}
            """.trimIndent()
        }
        val system = request.messages
            .filter { it.role.equals("system", ignoreCase = true) }
            .joinToString("\n") { it.content.orEmpty() }
            .trim()
            .take(if (tools.isBlank()) 1_000 else 6_000)
        val nonSystemMessages = request.messages
            .filterNot { it.role.equals("system", ignoreCase = true) }
        val history = if (phoneActionRequest) {
            compactPhoneActionHistory(nonSystemMessages)
        } else {
            nonSystemMessages
                .joinToString("\n\n", transform = ::formatMessage)
                .takeLast(if (tools.isBlank()) 5_000 else 2_400)
        }
        return buildString {
            append(protocol)
            if (system.isNotBlank()) {
                append("\n\n[SYSTEM]\n")
                append(system)
            }
            if (tools.isNotBlank()) {
                append("\n\n[AVAILABLE TOOLS]\n")
                append(tools)
            }
            if (history.isNotBlank()) {
                append("\n\n[CONVERSATION]\n")
                append(history)
            }
            append("\n\n[ASSISTANT]\n")
        }
    }

    private fun compactPhoneActionHistory(messages: List<ChatMessage>): String {
        if (messages.isEmpty()) return ""
        val task = messages.first()
        val recent = messages.drop(1).takeLast(4)
        return buildString {
            append(formatMessage(task).take(1_400))
            recent.forEach { message ->
                append("\n\n")
                // Keep the beginning of each observation: it contains the step, current app,
                // task facts and semantic-coordinate header. Tail-only truncation can erase the
                // original task and was the cause of unrelated taps in long Phone Agent runs.
                append(formatMessage(message).take(1_500))
            }
        }
    }

    private fun isPhoneActionRequest(raw: String?): Boolean {
        val parsed = runCatching { Json.parseToJsonElement(raw.orEmpty()) }.getOrNull()
            as? JsonArray ?: return false
        val names = parsed.mapNotNull { element ->
            val root = element as? JsonObject ?: return@mapNotNull null
            val function = root["function"] as? JsonObject ?: root
            function.string("name").lowercase().takeIf { it.isNotBlank() }
        }.toSet()
        return "phone_action" in names && names.all { it in PHONE_ACTION_TOOL_NAMES }
    }

    private fun formatMessage(message: ChatMessage): String = buildString {
        append('[')
        append(message.role.uppercase())
        message.name?.takeIf { it.isNotBlank() }?.let { append(' ').append(it) }
        message.toolCallId?.takeIf { it.isNotBlank() }?.let { append(" id=").append(it) }
        append("]\n")
        append(message.content.orEmpty())
        if (message.images.isNotEmpty()) append("\n[本消息附带图片 ${message.images.size} 张]")
        message.toolCalls.orEmpty().forEach { call ->
            append("\n已请求工具 ")
            append(call.function.name)
            append(' ')
            append(call.function.arguments)
        }
    }

    private fun compactToolDefinitions(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val parsed = runCatching { Json.parseToJsonElement(raw) }.getOrNull() as? JsonArray
            ?: return raw.replace(Regex("\\s+"), " ").take(2_400)
        val functions = parsed.mapNotNull { element ->
            val root = element as? JsonObject ?: return@mapNotNull null
            val function = root["function"] as? JsonObject ?: root
            function.takeIf { it.string("name").isNotBlank() }
        }
        val allNames = functions.joinToString(",") { it.string("name") }
        val details = functions
            .sortedBy { function -> toolPriority(function.string("name")) }
            .joinToString("\n") { function ->
                val name = function.string("name")
                val description = function.string("description")
                    .replace(Regex("\\s+"), " ")
                    .take(100)
                val parameters = function["parameters"] as? JsonObject
                val properties = parameters?.get("properties") as? JsonObject
                val parameterNames = when (name.lowercase()) {
                    "gui" -> "action；按动作只选所需字段：" +
                        "启动=packageName，自主手机任务=task，点击=nodeId或x/y，" +
                        "输入=text，滑动=startX/startY/endX/endY"
                    else -> properties?.keys.orEmpty().joinToString(",")
                }
                val required = (parameters?.get("required") as? JsonArray)
                    ?.joinToString(",") { it.jsonPrimitive.contentOrNull.orEmpty() }
                    .orEmpty()
                buildString {
                    append("- ").append(name).append("：").append(description)
                    if (parameterNames.isNotBlank()) append("；参数=").append(parameterNames)
                    if (required.isNotBlank()) append("；必填=").append(required)
                }
            }
        return "全部工具名：$allNames\n$details"
    }

    private fun toolPriority(name: String): Int {
        val normalized = name.lowercase()
        return TOOL_PRIORITY.indexOfFirst { keyword ->
            normalized == keyword || normalized.contains(keyword)
        }.takeIf { it >= 0 } ?: Int.MAX_VALUE
    }

    private fun JsonObject.string(key: String): String =
        (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun estimateTokens(value: String): Int =
        ((value.length + 2) / 3).coerceAtLeast(1)

    private data class ParsedLocalResponse(
        val content: String?,
        val toolCalls: List<ToolCall>
    )

    companion object {
        const val ID = "murong-local"
        private val TOOL_PRIORITY = listOf(
            "gui",
            "ask_user",
            "run_terminal",
            "read_file",
            "list_files",
            "write_file",
            "code_search",
            "code_edit",
            "workspace_diff",
            "file_exists",
            "web_search",
            "web_fetch",
            "session_history",
            "complete_step",
            "github",
            "mcp"
        )
        private val PHONE_ACTION_TOOL_NAMES = setOf("phone_action", "phone_finish")
        private val TOOL_CALL_PATTERN = Regex(
            "<tool_call>\\s*([\\s\\S]*?)\\s*</tool_call>",
            RegexOption.IGNORE_CASE
        )
        private val THINKING_PATTERN = Regex(
            "<think>\\s*[\\s\\S]*?\\s*</think>",
            RegexOption.IGNORE_CASE
        )
        private const val TOOL_CALL_START = "<tool_call>"
        private const val TOOL_CALL_END = "</tool_call>"
        private const val LEGACY_TOOL_START = "[TOOL]"
        private const val THINKING_START = "<think>"
        private const val THINKING_END = "</think>"
    }

    private fun parseLocalResponse(
        raw: String,
        startsInReasoning: Boolean = false,
        allowToolCalls: Boolean = true,
    ): ParsedLocalResponse {
        val normalized = if (
            startsInReasoning &&
            !raw.contains(THINKING_START, ignoreCase = true) &&
            raw.contains(THINKING_END, ignoreCase = true)
        ) {
            THINKING_START + raw
        } else {
            raw
        }
        val withoutThinking = THINKING_PATTERN.replace(normalized, "")
        if (!allowToolCalls) {
            val visibleContent = TOOL_CALL_PATTERN.replace(withoutThinking, "")
                .substringBefore(LEGACY_TOOL_START, withoutThinking)
                .replace(Regex("^```(?:json)?\\s*|\\s*```$", RegexOption.IGNORE_CASE), "")
                .trim()
                .takeIf { it.isNotBlank() }
                ?: "我现在只进行本地对话。请换一种问法，或在需要操作、看屏幕时切换到对应任务模型。"
            return ParsedLocalResponse(visibleContent, emptyList())
        }
        val taggedCalls = TOOL_CALL_PATTERN.findAll(withoutThinking).mapNotNull { match ->
            parseToolCall(match.groupValues[1])
        }.toList()
        val legacyMarkerIndex = withoutThinking.indexOf(
            LEGACY_TOOL_START,
            ignoreCase = true
        )
        val legacyCall = legacyMarkerIndex
            .takeIf { it >= 0 }
            ?.let { index ->
                parseToolCall(withoutThinking.substring(index + LEGACY_TOOL_START.length))
            }
        val calls = buildList {
            addAll(taggedCalls)
            legacyCall?.let(::add)
        }
        val visibleSection = if (legacyMarkerIndex >= 0) {
            withoutThinking.substring(0, legacyMarkerIndex)
        } else {
            withoutThinking
        }
        val visibleContent = TOOL_CALL_PATTERN.replace(visibleSection, "")
            .replace(Regex("^```(?:json)?\\s*|\\s*```$", RegexOption.IGNORE_CASE), "")
            .trim()
            .takeIf { it.isNotBlank() }
        val content = visibleContent ?: if (legacyMarkerIndex >= 0 && legacyCall == null) {
            "本地模型返回了无效的工具调用，未执行任何操作。请为此任务选择支持工具调用的模型后重试。"
        } else {
            null
        }
        return ParsedLocalResponse(content, calls)
    }

    private fun parseToolCall(payload: String): ToolCall? {
        val value = runCatching {
            Json.parseToJsonElement(
                payload.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            ).jsonObject
        }.getOrNull() ?: return null
        val name = value.string("name").trim()
        if (name.isBlank()) return null
        val argumentsElement = value["arguments"] ?: JsonObject(emptyMap())
        val decodedArguments = when (argumentsElement) {
            is JsonPrimitive -> argumentsElement.contentOrNull
                ?.takeIf { it.trim().startsWith("{") }
                ?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
                ?: JsonObject(emptyMap())
            is JsonObject -> argumentsElement
            else -> JsonObject(emptyMap())
        }
        val arguments = normalizeLocalToolArguments(name, decodedArguments).toString()
        return ToolCall(
            id = "local-${UUID.randomUUID()}",
            function = ToolCallFunction(name = name, arguments = arguments)
        )
    }

    private fun normalizeLocalToolArguments(name: String, arguments: JsonObject): JsonObject {
        val cleaned = cleanNullSentinels(arguments) as? JsonObject ?: JsonObject(emptyMap())
        if (!name.equals("ask_user", ignoreCase = true)) return cleaned
        val questions = (cleaned["questions"] as? JsonArray)?.take(4).orEmpty()
        val normalizedQuestions = questions.mapNotNull { questionElement ->
            val question = questionElement as? JsonObject ?: return@mapNotNull null
            val options = (question["options"] as? JsonArray)?.take(4).orEmpty().mapNotNull { option ->
                when (option) {
                    is JsonObject -> option
                    is JsonPrimitive -> option.contentOrNull
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }
                        ?.let { JsonObject(mapOf("label" to JsonPrimitive(it))) }
                    else -> null
                }
            }
            JsonObject(
                question.toMutableMap().apply {
                    put("options", JsonArray(options))
                }
            )
        }
        return JsonObject(
            cleaned.toMutableMap().apply {
                put("questions", JsonArray(normalizedQuestions))
            }
        )
    }

    private fun cleanNullSentinels(element: JsonElement): JsonElement? = when (element) {
        JsonNull -> null
        is JsonPrimitive -> element.takeUnless {
            it.isString && it.contentOrNull?.trim()?.equals("null", ignoreCase = true) == true
        }
        is JsonArray -> JsonArray(element.mapNotNull(::cleanNullSentinels))
        is JsonObject -> JsonObject(
            element.mapNotNull { (key, value) ->
                cleanNullSentinels(value)?.let { key to it }
            }.toMap()
        )
    }

    private class LocalVisibleStream(
        startInReasoning: Boolean,
        private val emitContent: (String) -> Unit,
        private val emitReasoning: (String) -> Unit
    ) {
        private enum class Mode { CONTENT, REASONING, TOOL }

        private val pending = StringBuilder()
        private var mode = if (startInReasoning) Mode.REASONING else Mode.CONTENT
        var receivedContentChunks: Boolean = false
            private set

        fun accept(chunk: String) {
            if (chunk.isEmpty()) return
            pending.append(chunk)
            drain(final = false)
        }

        fun finish() {
            drain(final = true)
        }

        private fun drain(final: Boolean) {
            while (pending.isNotEmpty()) {
                val markers = when (mode) {
                    Mode.CONTENT -> listOf(
                        TOOL_CALL_START to Mode.TOOL,
                        LEGACY_TOOL_START to Mode.TOOL,
                        THINKING_START to Mode.REASONING
                    )
                    Mode.REASONING -> listOf(
                        THINKING_END to Mode.CONTENT,
                        THINKING_START to Mode.REASONING
                    )
                    Mode.TOOL -> listOf(TOOL_CALL_END to Mode.CONTENT)
                }
                val match = markers
                    .map { (marker, nextMode) ->
                        Triple(pending.indexOf(marker, ignoreCase = true), marker, nextMode)
                    }
                    .filter { it.first >= 0 }
                    .minByOrNull { it.first }
                if (match != null) {
                    if (match.first > 0) emitVisible(pending.substring(0, match.first))
                    pending.delete(0, match.first + match.second.length)
                    mode = match.third
                    continue
                }
                val keep = if (final) {
                    0
                } else {
                    markers.maxOfOrNull { (marker, _) ->
                        pending.longestMarkerPrefixSuffix(marker)
                    } ?: 0
                }
                val emitLength = pending.length - keep
                if (emitLength > 0) {
                    emitVisible(pending.substring(0, emitLength))
                    pending.delete(0, emitLength)
                }
                if (final) pending.clear()
                return
            }
        }

        private fun emitVisible(text: String) {
            if (text.isEmpty()) return
            when (mode) {
                Mode.CONTENT -> {
                    receivedContentChunks = true
                    emitContent(text)
                }
                Mode.REASONING -> emitReasoning(text)
                Mode.TOOL -> Unit
            }
        }

        private fun StringBuilder.indexOf(value: String, ignoreCase: Boolean): Int =
            toString().indexOf(value, ignoreCase = ignoreCase)

        private fun StringBuilder.longestMarkerPrefixSuffix(marker: String): Int {
            val maxLength = minOf(length, marker.length - 1)
            for (size in maxLength downTo 1) {
                if (substring(length - size).equals(marker.substring(0, size), ignoreCase = true)) {
                    return size
                }
            }
            return 0
        }
    }

}
