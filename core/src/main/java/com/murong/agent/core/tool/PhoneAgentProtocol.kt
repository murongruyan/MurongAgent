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
                  "message": {
                    "type": "string",
                    "description": "A concise Chinese explanation of what is visible and why this action is next; shown verbatim to the user."
                  },
                  "x": {"type": "integer", "minimum": 0, "maximum": 1000},
                  "y": {"type": "integer", "minimum": 0, "maximum": 1000},
                  "startX": {"type": "integer", "minimum": 0, "maximum": 1000},
                  "startY": {"type": "integer", "minimum": 0, "maximum": 1000},
                  "endX": {"type": "integer", "minimum": 0, "maximum": 1000},
                  "endY": {"type": "integer", "minimum": 0, "maximum": 1000},
                  "durationMs": {"type": "integer", "minimum": 0, "maximum": 10000}
                },
                "required": ["action", "message"]
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

    private val faraToolsJson: String = """
        [
          {
            "type": "function",
            "function": {
              "name": "computer_use",
              "description": "Execute one Android GUI action. Coordinates use a normalized 0-1000 space.",
              "parameters": {
                "type": "object",
                "additionalProperties": false,
                "properties": {
                  "action": {
                    "type": "string",
                    "enum": ["key", "type", "left_click", "right_click", "double_click", "triple_click", "scroll", "hscroll", "history_back", "pause_and_memorize_fact", "ask_user_question", "wait", "terminate"]
                  },
                  "keys": {"type": "array", "items": {"type": "string"}},
                  "text": {"type": "string"},
                  "coordinate": {"type": "array", "items": {"type": "integer"}, "minItems": 2, "maxItems": 2},
                  "pixels": {"type": "integer"},
                  "fact": {"type": "string"},
                  "question": {"type": "string"},
                  "time": {"type": "integer", "minimum": 0, "maximum": 10},
                  "answer": {"type": "string"}
                },
                "required": ["action"]
              }
            }
          }
        ]
    """.trimIndent()

    fun toolsJsonForModel(model: String): String =
        if (model.contains("fara", ignoreCase = true)) faraToolsJson else toolsJson

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

        val finish = latestCall(response, FINISH_TOOL_NAMES)
        val action = latestCall(response, ACTION_TOOL_NAMES)
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

        parseJsonObject(action.second)?.let { arguments ->
            return decisionFromJson(arguments, requireAction = true)
                ?: PhoneAgentDecision.Invalid("phone_action 的 arguments 缺少 action")
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
        val rawAction = root.stringValue("action", "command", "动作", "操作").trim()
        if (
            rawAction.equals("finish", ignoreCase = true) ||
            rawAction.equals("terminate", ignoreCase = true) ||
            state in FINISHED_STATES
        ) {
            return PhoneAgentDecision.Finish(
                root.valueText("message", "result", "summary", "answer")
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

        val point = root.coordinates(
            "element", "point", "coordinate", "coordinates", "坐标", "位置",
        )
        val start = root.coordinates("start", "from", "起点", "开始")
        val end = root.coordinates("end", "to", "终点", "结束")
        val normalizedAction = normalizeAction(rawAction)
        val faraScrollPixels = root.intValue("pixels")
        val generatedSwipe = when {
            normalizedAction != "Swipe" || faraScrollPixels == null -> null
            rawAction.equals("hscroll", ignoreCase = true) && faraScrollPixels >= 0 ->
                listOf(250, 500, 750, 500)
            rawAction.equals("hscroll", ignoreCase = true) -> listOf(750, 500, 250, 500)
            faraScrollPixels >= 0 -> listOf(500, 250, 500, 750)
            else -> listOf(500, 750, 500, 250)
        }
        val faraKeys = root.stringArray("keys")
        val actionText = when (normalizedAction) {
            "Key" -> faraKeys.firstOrNull()?.let(::androidKeyCodeForFara)
                ?: root.stringValue("key", "text")
            else -> root.stringValue("text", "input", "value", "文字", "内容")
        }
        if (normalizedAction == "Take_over") {
            return PhoneAgentDecision.Execute(
                PhoneAgentCommand(
                    action = normalizedAction,
                    message = root.valueText("question", "message", "reason")
                        .ifBlank { "模型需要用户补充信息" },
                )
            )
        }
        return PhoneAgentDecision.Execute(
            PhoneAgentCommand(
                action = normalizedAction,
                app = root.stringValue("app", "application", "packageName", "应用")
                    .takeIf { it.isNotBlank() },
                text = actionText.takeIf { it.isNotBlank() },
                message = root.stringValue(
                    "message", "reason", "note", "fact", "消息", "原因", "备注",
                )
                    .takeIf { it.isNotBlank() },
                x = point?.first ?: root.intValue("x"),
                y = point?.second ?: root.intValue("y"),
                startX = start?.first ?: root.intValue("startX", "start_x")
                    ?: generatedSwipe?.get(0),
                startY = start?.second ?: root.intValue("startY", "start_y")
                    ?: generatedSwipe?.get(1),
                endX = end?.first ?: root.intValue("endX", "end_x")
                    ?: generatedSwipe?.get(2),
                endY = end?.second ?: root.intValue("endY", "end_y")
                    ?: generatedSwipe?.get(3),
                durationMs = root.intValue("durationMs", "duration_ms", "duration")
                    ?: root.intValue("time")?.times(1_000),
            )
        )
    }

    private fun normalizeAction(action: String): String = when (
        action.trim().lowercase().replace('-', '_')
    ) {
        "open", "launch" -> "Launch"
        "打开", "启动", "打开应用", "启动应用" -> "Launch"
        "click", "tap", "left_click" -> "Tap"
        "点击", "点一下", "单击" -> "Tap"
        "input", "type" -> "Type"
        "输入", "填写", "打字" -> "Type"
        "type_name" -> "Type_Name"
        "scroll", "hscroll", "swipe", "left_click_drag" -> "Swipe"
        "滑动", "滚动", "下滑", "上滑" -> "Swipe"
        "back", "history_back" -> "Back"
        "返回" -> "Back"
        "home" -> "Home"
        "桌面", "返回桌面" -> "Home"
        "long press", "long_press", "long_click", "right_click" -> "Long Press"
        "长按" -> "Long Press"
        "double tap", "double_tap", "double_click", "triple_click" -> "Double Tap"
        "双击" -> "Double Tap"
        "wait" -> "Wait"
        "等待" -> "Wait"
        "takeover", "take over", "take_over", "ask_user_question" -> "Take_over"
        "接管", "人工接管", "用户接管" -> "Take_over"
        "call_api", "call api" -> "Call_API"
        "note", "pause_and_memorize_fact" -> "Note"
        "记录", "备注" -> "Note"
        "key" -> "Key"
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

    private fun JsonObject.stringArray(key: String): List<String> =
        (get(key) as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            .orEmpty()

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

    private fun latestCall(source: String, names: Set<String>): Pair<Int, String>? =
        names.mapNotNull { name -> extractCall(source, name) }
            .maxByOrNull { it.first }

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

    private fun androidKeyCodeForFara(key: String): String = when (key.trim().lowercase()) {
        "enter", "return" -> "KEYCODE_ENTER"
        "escape", "esc" -> "KEYCODE_ESCAPE"
        "backspace" -> "KEYCODE_DEL"
        "delete" -> "KEYCODE_FORWARD_DEL"
        "tab" -> "KEYCODE_TAB"
        "arrowup" -> "KEYCODE_DPAD_UP"
        "arrowdown" -> "KEYCODE_DPAD_DOWN"
        "arrowleft" -> "KEYCODE_DPAD_LEFT"
        "arrowright" -> "KEYCODE_DPAD_RIGHT"
        "pagedown" -> "KEYCODE_PAGE_DOWN"
        "pageup" -> "KEYCODE_PAGE_UP"
        else -> key
    }

    private val ACTION_TOOL_NAMES = setOf("phone_action", "gui_action", "do", "computer_use")
    private val FINISH_TOOL_NAMES = setOf("phone_finish", "finish")
    private val FINISHED_STATES = setOf("finish", "finished", "done", "completed", "complete")
}
