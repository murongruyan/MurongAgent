package com.murong.agent.core.tool

import kotlinx.serialization.Serializable

@Serializable
data class PhoneAgentTaskRequest(
    val task: String,
    val taskType: String = "general",
    val maxSteps: Int? = null,
    val platforms: List<String> = emptyList(),
    val quantity: Int = 1
)

@Serializable
data class PhoneAgentRunResult(
    val success: Boolean,
    val status: String,
    val message: String,
    val stepsExecuted: Int = 0,
    val requiresUserAction: Boolean = false,
    val currentApplication: String? = null,
    val trace: List<PhoneAgentStepRecord> = emptyList(),
    val foodDeliveryComparison: FoodDeliveryComparison? = null
)

@Serializable
data class PhoneAgentStepRecord(
    val step: Int,
    val application: String? = null,
    val action: String,
    val success: Boolean,
    val detail: String? = null
)

@Serializable
data class FoodDeliveryComparison(
    val query: String? = null,
    val offers: List<FoodDeliveryOffer> = emptyList(),
    val cheapestOffer: FoodDeliveryOffer? = null,
    val notes: List<String> = emptyList()
)

@Serializable
data class FoodDeliveryOffer(
    val platform: String,
    val merchant: String? = null,
    val item: String? = null,
    val specification: String? = null,
    val quantity: Int = 1,
    val itemPrice: Double? = null,
    val packingFee: Double? = null,
    val deliveryFee: Double? = null,
    val discount: Double? = null,
    val totalPrice: Double? = null,
    val eta: String? = null,
    val comparable: Boolean = true,
    val available: Boolean = true,
    val evidence: String? = null,
    val unavailableReason: String? = null
)

internal data class PhoneAgentScreen(
    val screenshot: GuiScreenshot,
    val application: String? = null,
    val windowClassName: String? = null,
    val semanticSummary: String = "",
    val displayWidth: Int = screenshot.width,
    val displayHeight: Int = screenshot.height,
    val textElements: List<PhoneAgentTextElement> = emptyList(),
)

internal data class PhoneAgentTextElement(
    val text: String,
    val centerX: Int,
    val centerY: Int,
)

/** Finds a likely app-owned search entry from OCR without package ids or app coordinates. */
internal fun List<PhoneAgentTextElement>.likelySearchEntry(
    allowGenericTopBar: Boolean = true,
): PhoneAgentTextElement? {
    val visibleTopEntries = filter { element ->
        element.centerY in 15..170 &&
            element.centerX in 100..900 &&
            element.text.length in 2..60
    }
    val explicitSearchEntry = visibleTopEntries
        .filter { element ->
            val text = element.text.lowercase()
            text.contains("搜索") || text.contains("查找") || text.contains("search")
        }
        .minByOrNull { it.centerY }
    if (explicitSearchEntry != null || !allowGenericTopBar) return explicitSearchEntry
    return visibleTopEntries
            .filterNot { element ->
                val normalized = element.text
                    .replace(Regex("[\\s·•|丨]+"), "")
                    .replace(Regex("[0-9+]+$"), "")
                element.text.matches(Regex("[0-9:.% ]+")) ||
                    normalized in setOf(
                        "推荐", "首页", "消息", "我的", "关注", "朋友", "商城", "直播",
                    )
            }
            .minByOrNull { it.centerY }
}

internal fun List<PhoneAgentTextElement>.toPhoneAgentOcrSummary(): String = asSequence()
    .take(80)
    .joinToString("\n") { element ->
        "ocr text=\"${element.text.replace("\"", "'").take(120)}\" center=[${element.centerX},${element.centerY}]"
    }
    .take(6_000)

/** Strong app-shell landmarks that must outweigh a model's guess based on feed content. */
internal fun PhoneAgentScreen.topLevelNavigationEvidence(): String? {
    if (application != PhoneAgentApps.PINDUODUO_PACKAGE) return null
    val bottomLabels = textElements.asSequence()
        .filter { it.centerY >= 850 }
        .map { it.text.replace(Regex("\\s+"), "").trim() }
        .filter(String::isNotBlank)
        .toSet()
    val expected = setOf("首页", "多多视频", "聊天", "个人中心")
    val matched = expected.filter { label ->
        bottomLabels.any { observed -> observed == label || observed.contains(label) }
    }
    if ("首页" !in matched || matched.size < 3) return null
    return "检测到拼多多底部一级导航：${matched.joinToString("、")}；" +
        "这是首页商品流，不是商品详情页"
}

internal fun topLevelNavigationActionConflict(
    task: String,
    screen: PhoneAgentScreen,
    command: PhoneAgentCommand,
): String? {
    val evidence = screen.topLevelNavigationEvidence() ?: return null
    if (isReadOnlyCurrentPageQuestion(task)) {
        return "$evidence；用户只要求识别当前页面，已拦截${command.action}，应直接回答并结束"
    }
    if (!command.action.equals("Back", ignoreCase = true)) return null
    if (listOf("返回", "退出", "关闭应用", "回到桌面").any(task::contains)) return null
    return "$evidence；返回会退出当前一级页面，已拦截"
}

private fun isReadOnlyCurrentPageQuestion(task: String): Boolean {
    val normalized = task.replace(Regex("\\s+"), "")
    return listOf(
        "当前页面是什么",
        "当前是什么页面",
        "现在是什么页面",
        "现在页面是什么",
        "告诉我当前页面",
        "告诉我现在页面",
    ).any(normalized::contains)
}

internal data class PhoneAgentMessageIntent(
    val recipient: String,
    val body: String,
) {
    companion object {
        private val CHINESE_MESSAGE_PATTERN = Regex(
            "给\\s*(.{1,40}?)\\s*(?:发送|发(?:送)?消息(?:[:：])?|发)\\s*(.{1,200})$",
            RegexOption.IGNORE_CASE,
        )

        fun parse(task: String): PhoneAgentMessageIntent? {
            val match = CHINESE_MESSAGE_PATTERN.find(task.trim()) ?: return null
            val recipient = match.groupValues[1].trim().trim('，', ',', '。', '.')
            val body = match.groupValues[2].trim().trim('“', '”', '"')
            if (recipient.isBlank() || body.isBlank()) return null
            return PhoneAgentMessageIntent(recipient, body)
        }
    }
}

internal data class PhoneAgentSearchIntent(
    val packageName: String,
    val query: String,
    val searchOnly: Boolean,
) {
    internal fun verifiedNativeSearchUri(): String? = when (packageName) {
        PhoneAgentApps.DOUYIN_PACKAGE -> {
            val encodedQuery = java.net.URLEncoder.encode(query, Charsets.UTF_8.name())
            "snssdk1128://search?keyword=$encodedQuery"
        }
        else -> null
    }

    companion object {
        private val FOLLOW_UP_MARKERS = listOf(
            "，", ",", "。", "；", ";", "然后", "并且", "给它", "给他", "给她",
            "并给", "再给", "后给", "并把", "再把", "后把", "并打开", "再打开",
        )

        fun parse(task: String): PhoneAgentSearchIntent? {
            val normalized = task.trim()
            val searchIndex = normalized.indexOf("搜索")
            if (searchIndex < 0) return null
            val packageName = PhoneAgentApps.packageMentionedIn(
                normalized.substring(0, searchIndex),
            ) ?: return null
            val remainder = normalized.substring(searchIndex + "搜索".length).trim()
            if (remainder.isBlank()) return null
            val markerIndex = FOLLOW_UP_MARKERS
                .map(remainder::indexOf)
                .filter { it >= 0 }
                .minOrNull()
            val query = remainder.substring(0, markerIndex ?: remainder.length)
                .trim()
                .trim('“', '”', '"', '，', ',', '。', '.')
            if (query.isBlank()) return null
            return PhoneAgentSearchIntent(
                packageName = packageName,
                query = query,
                searchOnly = markerIndex == null,
            )
        }
    }
}

internal fun GuiObservation.toPhoneAgentSemanticSummary(
    displayWidth: Int,
    displayHeight: Int,
): String {
    if (!success || nodes.isEmpty() || displayWidth <= 0 || displayHeight <= 0) return ""
    return nodes.asSequence()
        .filter { node ->
            node.visible && (
                !node.text.isNullOrBlank() ||
                    !node.contentDescription.isNullOrBlank() ||
                    !node.resourceId.isNullOrBlank() ||
                    node.clickable || node.editable || node.scrollable
                )
        }
        .take(60)
        .map { node ->
            val centerX = ((node.bounds.centerX.toLong() * 1000L) / displayWidth)
                .toInt().coerceIn(0, 1000)
            val centerY = ((node.bounds.centerY.toLong() * 1000L) / displayHeight)
                .toInt().coerceIn(0, 1000)
            buildString {
                append(node.role.ifBlank { "view" })
                node.text?.takeIf(String::isNotBlank)?.let { append(" text=\"").append(it).append('"') }
                node.contentDescription?.takeIf(String::isNotBlank)?.let {
                    append(" desc=\"").append(it).append('"')
                }
                node.resourceId?.substringAfterLast('/')?.takeIf(String::isNotBlank)?.let {
                    append(" id=").append(it)
                }
                append(" center=[").append(centerX).append(',').append(centerY).append(']')
                if (node.editable) append(" editable")
                if (node.clickable) append(" clickable")
                if (node.scrollable) append(" scrollable")
            }
        }
        .joinToString("\n")
        .take(6_000)
}

internal data class PhoneAgentDeviceResult(
    val success: Boolean,
    val detail: String? = null
)

internal data class PhoneAgentCommand(
    val action: String,
    val app: String? = null,
    val text: String? = null,
    val message: String? = null,
    val x: Int? = null,
    val y: Int? = null,
    val startX: Int? = null,
    val startY: Int? = null,
    val endX: Int? = null,
    val endY: Int? = null,
    val durationMs: Int? = null,
    val preferRoot: Boolean = false,
) {
    fun fingerprint(): String = listOf(
        action,
        app,
        text,
        x,
        y,
        startX,
        startY,
        endX,
        endY
    ).joinToString("|")
}

internal sealed interface PhoneAgentDecision {
    data class Execute(val command: PhoneAgentCommand) : PhoneAgentDecision
    data class Finish(val message: String) : PhoneAgentDecision
    data class Invalid(val reason: String) : PhoneAgentDecision
}

internal interface PhoneAgentDevice {
    suspend fun observe(): PhoneAgentScreen
    suspend fun execute(command: PhoneAgentCommand, screen: PhoneAgentScreen): PhoneAgentDeviceResult
}
