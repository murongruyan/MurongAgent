package com.murong.agent.core.tool

import com.murong.agent.core.provider.ToolCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Normalizes native function calls, plain JSON actions, and AutoGLM's legacy
 * do(...)/finish(...) syntax into one internal phone command.
 */
internal object PhoneAgentProtocol {
    val toolsJson: String = """
        [
          {
            "type": "function",
            "function": {
              "name": "phone_action",
              "description": "Execute exactly one Android screen action. Coordinates use a 0-1000 normalized space.",
              "parameters": {
                "type": "object",
                "additionalProperties": false,
                "properties": {
                  "action": {
                    "type": "string",
                    "enum": ["launch", "tap", "type", "swipe", "back", "home", "long_press", "double_tap", "wait", "take_over", "note"]
                  },
                  "app": {"type": "string"},
                  "text": {"type": "string"},
                  "message": {"type": "string"},
                  "x": {"type": "integer", "minimum": 0, "maximum": 1000},
                  "y": {"type": "integer", "minimum": 0, "maximum": 1000},
                  "startX": {"type": "integer", "minimum": 0, "maximum": 1000},
                  "startY": {"type": "integer", "minimum": 0, "maximum": 1000},
                  "endX": {"type": "integer", "minimum": 0, "maximum": 1000},
                  "endY": {"type": "integer", "minimum": 0, "maximum": 1000},
                  "durationMs": {"type": "integer", "minimum": 0, "maximum": 10000}
                },
                "required": ["action"]
              }
            }
          },
          {
            "type": "function",
            "function": {
              "name": "phone_finish",
              "description": "Finish the phone task and report the verified result.",
              "parameters": {
                "type": "object",
                "additionalProperties": false,
                "properties": {
                  "message": {"type": "string"}
                },
                "required": ["message"]
              }
            }
          }
        ]
    """.trimIndent()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun parse(response: PhoneAgentModelResponse): PhoneAgentDecision {
        response.toolCalls.asReversed().forEach { toolCall ->
            parseToolCall(toolCall)?.let { return it }
        }
        return parse(response.content.orEmpty())
    }

    fun parse(response: String): PhoneAgentDecision {
        parseJsonDecision(response)?.let { return it }

        val finish = extractCall(response, "finish")
        val action = extractCall(response, "do")
        if (finish != null && (action == null || finish.first > action.first)) {
            val args = parseArguments(finish.second)
            return PhoneAgentDecision.Finish(
                args["message"].orEmpty().ifBlank { "任务已完成" }
            )
        }
        if (action == null) {
            return PhoneAgentDecision.Invalid(
                "模型未调用 phone_action/phone_finish，也未返回动作 JSON、do(...) 或 finish(...)"
            )
        }

        val args = parseArguments(action.second)
        val actionName = args["action"]?.trim().orEmpty()
        if (actionName.isBlank()) {
            return PhoneAgentDecision.Invalid("do(...) 缺少 action")
        }
        if (actionName.equals("finish", ignoreCase = true)) {
            return PhoneAgentDecision.Finish(
                args["message"].orEmpty().ifBlank { "任务已完成" }
            )
        }
        val element = parseCoordinates(args["element"])
        val start = parseCoordinates(args["start"])
        val end = parseCoordinates(args["end"])
        return PhoneAgentDecision.Execute(
            PhoneAgentCommand(
                action = normalizeAction(actionName),
                app = args["app"],
                text = args["text"],
                message = args["message"],
                x = element?.first ?: args["x"].toProtocolInt(),
                y = element?.second ?: args["y"].toProtocolInt(),
                startX = start?.first ?: args["startX"].toProtocolInt()
                    ?: args["start_x"].toProtocolInt(),
                startY = start?.second ?: args["startY"].toProtocolInt()
                    ?: args["start_y"].toProtocolInt(),
                endX = end?.first ?: args["endX"].toProtocolInt()
                    ?: args["end_x"].toProtocolInt(),
                endY = end?.second ?: args["endY"].toProtocolInt()
                    ?: args["end_y"].toProtocolInt(),
                durationMs = args["duration"].toProtocolInt()
                    ?: args["durationMs"].toProtocolInt()
                    ?: args["duration_ms"].toProtocolInt()
            )
        )
    }

    private fun parseToolCall(toolCall: ToolCall): PhoneAgentDecision? {
        val name = toolCall.function.name.trim().lowercase()
        if (name !in ACTION_TOOL_NAMES && name !in FINISH_TOOL_NAMES) return null
        val arguments = parseJsonObject(toolCall.function.arguments)
            ?: return PhoneAgentDecision.Invalid("函数 $name 的 arguments 不是 JSON 对象")
        return if (name in FINISH_TOOL_NAMES) {
            PhoneAgentDecision.Finish(
                arguments.valueText("message", "result", "summary")
                    .ifBlank { "任务已完成" }
            )
        } else {
            decisionFromJson(arguments, requireAction = true)
        }
    }

    private fun parseJsonDecision(source: String): PhoneAgentDecision? {
        val trimmed = source.trim()
        if (trimmed.isBlank()) return null
        val candidates = buildList {
            val unfenced = trimmed
                .removePrefix("```json")
                .removePrefix("```JSON")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()
            add(unfenced)
            addAll(extractJsonObjects(trimmed).asReversed())
        }.distinct()
        candidates.forEach { candidate ->
            val root = parseJsonObject(candidate) ?: return@forEach
            decisionFromJson(root, requireAction = false)?.let { return it }
        }
        return null
    }

    private fun decisionFromJson(
        root: JsonObject,
        requireAction: Boolean
    ): PhoneAgentDecision? {
        val wrapperName = root.stringValue("name", "tool", "function").lowercase()
        if (wrapperName in ACTION_TOOL_NAMES || wrapperName in FINISH_TOOL_NAMES) {
            val nested = when (val arguments = root["arguments"]) {
                is JsonObject -> arguments
                is JsonPrimitive -> arguments.contentOrNull?.let(::parseJsonObject)
                else -> null
            } ?: JsonObject(emptyMap())
            return if (wrapperName in FINISH_TOOL_NAMES) {
                PhoneAgentDecision.Finish(
                    nested.valueText("message", "result", "summary")
                        .ifBlank { "任务已完成" }
                )
            } else {
                decisionFromJson(nested, requireAction = true)
            }
        }

        val state = root.stringValue("type", "status", "kind").trim().lowercase()
        val rawAction = root.stringValue("action", "command").trim()
        if (
            rawAction.equals("finish", ignoreCase = true) ||
            state in FINISHED_STATES
        ) {
            return PhoneAgentDecision.Finish(
                root.valueText("message", "result", "summary")
                    .ifBlank { "任务已完成" }
            )
        }
        if (rawAction.isBlank()) {
            return if (requireAction) {
                PhoneAgentDecision.Invalid("phone_action/JSON 缺少 action")
            } else {
                null
            }
        }

        val point = root.coordinates("element", "point", "coordinates")
        val start = root.coordinates("start", "from")
        val end = root.coordinates("end", "to")
        return PhoneAgentDecision.Execute(
            PhoneAgentCommand(
                action = normalizeAction(rawAction),
                app = root.stringValue("app", "application", "packageName")
                    .takeIf { it.isNotBlank() },
                text = root.stringValue("text", "input", "value")
                    .takeIf { it.isNotBlank() },
                message = root.stringValue("message", "reason", "note")
                    .takeIf { it.isNotBlank() },
                x = point?.first ?: root.intValue("x"),
                y = point?.second ?: root.intValue("y"),
                startX = start?.first ?: root.intValue("startX", "start_x"),
                startY = start?.second ?: root.intValue("startY", "start_y"),
                endX = end?.first ?: root.intValue("endX", "end_x"),
                endY = end?.second ?: root.intValue("endY", "end_y"),
                durationMs = root.intValue("durationMs", "duration_ms", "duration")
            )
        )
    }

    private fun normalizeAction(action: String): String = when (
        action.trim().lowercase().replace('-', '_')
    ) {
        "open", "launch" -> "Launch"
        "click", "tap" -> "Tap"
        "input", "type" -> "Type"
        "type_name" -> "Type_Name"
        "scroll", "swipe" -> "Swipe"
        "back" -> "Back"
        "home" -> "Home"
        "long press", "long_press", "long_click" -> "Long Press"
        "double tap", "double_tap", "double_click" -> "Double Tap"
        "wait" -> "Wait"
        "takeover", "take over", "take_over" -> "Take_over"
        "call_api", "call api" -> "Call_API"
        "note" -> "Note"
        else -> action.trim()
    }

    private fun parseJsonObject(raw: String): JsonObject? = runCatching {
        json.parseToJsonElement(raw.trim()).let { it as? JsonObject }
    }.getOrNull()

    private fun JsonObject.stringValue(vararg keys: String): String {
        keys.forEach { key ->
            val value = get(key) as? JsonPrimitive ?: return@forEach
            value.contentOrNull?.let { return it }
        }
        return ""
    }

    private fun JsonObject.valueText(vararg keys: String): String {
        keys.forEach { key ->
            val value = get(key) ?: return@forEach
            return when (value) {
                is JsonPrimitive -> value.contentOrNull.orEmpty()
                else -> value.toString()
            }
        }
        return ""
    }

    private fun JsonObject.intValue(vararg keys: String): Int? {
        keys.forEach { key ->
            val value = get(key) as? JsonPrimitive ?: return@forEach
            value.intOrNull?.let { return it }
            value.contentOrNull.toProtocolInt()?.let { return it }
        }
        return null
    }

    private fun JsonObject.coordinates(vararg keys: String): Pair<Int, Int>? {
        keys.forEach { key ->
            when (val value = get(key)) {
                is JsonArray -> {
                    val coordinates = value.mapNotNull { element ->
                        val scalar = element as? JsonPrimitive ?: return@mapNotNull null
                        scalar.intOrNull ?: scalar.contentOrNull.toProtocolInt()
                    }
                    if (coordinates.size >= 2) return coordinates[0] to coordinates[1]
                }
                is JsonPrimitive -> parseCoordinates(value.contentOrNull)?.let { return it }
                else -> Unit
            }
        }
        return null
    }

    private fun extractJsonObjects(source: String): List<String> {
        val results = mutableListOf<String>()
        var start = -1
        var depth = 0
        var quote: Char? = null
        var escaped = false
        source.forEachIndexed { index, char ->
            if (escaped) {
                escaped = false
                return@forEachIndexed
            }
            if (char == '\\' && quote != null) {
                escaped = true
                return@forEachIndexed
            }
            if (char == '"' || char == '\'') {
                if (quote == char) quote = null else if (quote == null) quote = char
                return@forEachIndexed
            }
            if (quote != null) return@forEachIndexed
            when (char) {
                '{' -> {
                    if (depth == 0) start = index
                    depth++
                }
                '}' -> if (depth > 0) {
                    depth--
                    if (depth == 0 && start >= 0) {
                        results += source.substring(start, index + 1)
                        start = -1
                    }
                }
            }
        }
        return results
    }

    private fun extractCall(source: String, name: String): Pair<Int, String>? {
        val regex = Regex("""(?i)\b${Regex.escape(name)}\s*\(""")
        var candidate: Pair<Int, String>? = null
        regex.findAll(source).forEach { match ->
            val open = source.indexOf('(', match.range.first)
            val close = findMatchingClose(source, open)
            if (open >= 0 && close > open) {
                candidate = match.range.first to source.substring(open + 1, close)
            }
        }
        return candidate
    }

    private fun findMatchingClose(source: String, open: Int): Int {
        if (open !in source.indices) return -1
        var depth = 0
        var quote: Char? = null
        var escaped = false
        for (index in open until source.length) {
            val char = source[index]
            if (escaped) {
                escaped = false
                continue
            }
            if (char == '\\' && quote != null) {
                escaped = true
                continue
            }
            if (char == '"' || char == '\'') {
                if (quote == char) quote = null else if (quote == null) quote = char
                continue
            }
            if (quote != null) continue
            if (char == '(') depth++
            if (char == ')' && --depth == 0) return index
        }
        return -1
    }

    private fun parseArguments(body: String): Map<String, String> {
        return splitTopLevel(body).mapNotNull { token ->
            val equals = findTopLevelEquals(token)
            if (equals <= 0) return@mapNotNull null
            val key = token.substring(0, equals).trim()
            val raw = token.substring(equals + 1).trim()
            key.takeIf(String::isNotBlank)?.let { it to decodeScalar(raw) }
        }.toMap()
    }

    private fun splitTopLevel(value: String): List<String> {
        val parts = mutableListOf<String>()
        var start = 0
        var quote: Char? = null
        var escaped = false
        var squareDepth = 0
        var roundDepth = 0
        value.forEachIndexed { index, char ->
            if (escaped) {
                escaped = false
                return@forEachIndexed
            }
            if (char == '\\' && quote != null) {
                escaped = true
                return@forEachIndexed
            }
            if (char == '"' || char == '\'') {
                if (quote == char) quote = null else if (quote == null) quote = char
                return@forEachIndexed
            }
            if (quote != null) return@forEachIndexed
            when (char) {
                '[' -> squareDepth++
                ']' -> squareDepth--
                '(' -> roundDepth++
                ')' -> roundDepth--
                ',' -> if (squareDepth == 0 && roundDepth == 0) {
                    parts += value.substring(start, index).trim()
                    start = index + 1
                }
            }
        }
        parts += value.substring(start).trim()
        return parts.filter(String::isNotBlank)
    }

    private fun findTopLevelEquals(value: String): Int {
        var quote: Char? = null
        var squareDepth = 0
        value.forEachIndexed { index, char ->
            if (char == '"' || char == '\'') {
                if (quote == char) quote = null else if (quote == null) quote = char
            } else if (quote == null) {
                if (char == '[') squareDepth++
                if (char == ']') squareDepth--
                if (char == '=' && squareDepth == 0) return index
            }
        }
        return -1
    }

    private fun decodeScalar(raw: String): String {
        if (raw.length < 2) return raw
        val quote = raw.first()
        if ((quote != '"' && quote != '\'') || raw.last() != quote) return raw
        return raw.substring(1, raw.length - 1)
            .replace("\\$quote", quote.toString())
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t")
            .replace("\\\\", "\\")
    }

    private fun parseCoordinates(raw: String?): Pair<Int, Int>? {
        val values = raw.orEmpty()
            .trim()
            .removePrefix("[")
            .removeSuffix("]")
            .removePrefix("(")
            .removeSuffix(")")
            .split(',')
            .mapNotNull { it.trim().toDoubleOrNull()?.toInt() }
        return if (values.size >= 2) values[0] to values[1] else null
    }

    private fun String?.toProtocolInt(): Int? =
        this?.filter { it.isDigit() || it == '-' }?.toIntOrNull()

    private val ACTION_TOOL_NAMES = setOf("phone_action", "gui_action", "do")
    private val FINISH_TOOL_NAMES = setOf("phone_finish", "finish")
    private val FINISHED_STATES = setOf("finish", "finished", "done", "completed", "complete")
}
