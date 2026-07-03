package com.murong.agent.core.tool

import com.murong.agent.core.loop.ConversationStore
import com.murong.agent.core.loop.PersistedMessage
import com.murong.agent.core.loop.PersistedSession
import com.murong.agent.core.loop.SessionSummary
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal class SessionHistorySearchTool(
    private val sessionsProvider: () -> List<SessionSummary>,
    private val sessionLoader: (String) -> PersistedSession?,
    private val currentSessionIdProvider: () -> String?,
    private val currentProjectPathProvider: () -> String?
) : Tool {
    override val name: String = "session_history_search"
    override val description: String =
        "检索本地历史会话，按关键词或当前项目过滤最近的 session；传入 session_id、message_reference 或 message_references 时可继续读取更完整的历史摘录。适合回看之前同类问题、跨会话延续上下文。"
    override val parameters: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "session_id" to mapOf(
                "type" to "string",
                "description" to "可选。指定后进入会话摘录读取模式，返回该历史 session 的更完整上下文摘录。"
            ),
            "message_reference" to mapOf(
                "type" to "string",
                "description" to "可选。稳定消息引用，格式为 session_id#message_id；指定后可直接围绕该历史消息回看摘录。"
            ),
            "message_references" to mapOf(
                "type" to "array",
                "items" to mapOf("type" to "string"),
                "description" to "可选。多条稳定消息引用列表，格式为 [session_id#message_id]；指定后可一次读取多段跨会话摘录。"
            ),
            "query" to mapOf(
                "type" to "string",
                "description" to "可选。检索关键词，会匹配会话标题、任务目标、项目路径和消息正文；读取摘录时也会优先围绕命中消息取窗口。"
            ),
            "limit" to mapOf(
                "type" to "integer",
                "description" to "可选。返回条目上限，默认 5，最大 20。"
            ),
            "excerpt_message_limit" to mapOf(
                "type" to "integer",
                "description" to "可选。读取 session_id 摘录时返回的消息条数，默认 6，最大 12。"
            ),
            "anchor_message_id" to mapOf(
                "type" to "integer",
                "description" to "可选。读取 session_id 摘录时，围绕指定 message_id 继续回看上下文窗口。"
            ),
            "project_only" to mapOf(
                "type" to "boolean",
                "description" to "可选。是否只看当前项目下的历史会话；默认 false。"
            ),
            "include_current_session" to mapOf(
                "type" to "boolean",
                "description" to "可选。是否包含当前会话；默认 false。"
            )
        )
    )

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(args: String): String = executeWithStructuredResult(args).output

    override suspend fun executeWithResult(args: String): ToolExecutionResult {
        val response = executeWithStructuredResult(args)
        return ToolExecutionResult(
            output = response.output,
            structuredPayload = buildSessionHistoryToolStructuredPayload(response)
        )
    }

    internal suspend fun executeWithStructuredResult(args: String): SessionHistoryToolResponse {
        return try {
            when (val requestResolution = parseSessionHistoryToolRequest(args, json)) {
                is SessionHistoryToolRequestResolution.Invalid ->
                    SessionHistoryToolResponse.InvalidRequest(requestResolution.message)
                is SessionHistoryToolRequestResolution.Valid -> executeSessionHistoryToolRequest(
                    request = requestResolution.request,
                    sessions = sessionsProvider(),
                    sessionLoader = sessionLoader,
                    currentSessionId = currentSessionIdProvider(),
                    currentProjectPath = currentProjectPathProvider()
                )
            }
        } catch (e: Exception) {
            SessionHistoryToolResponse.Failure(
                output = "Error: 检索本地历史会话失败: ${e.message}"
            )
        }
    }
}

internal data class SessionHistoryToolRequest(
    val sessionId: String?,
    val messageReference: String?,
    val messageReferences: List<String>,
    val query: String,
    val limit: Int,
    val excerptMessageLimit: Int,
    val anchorMessageId: Long?,
    val projectOnly: Boolean,
    val includeCurrentSession: Boolean
)

internal sealed interface SessionHistoryToolRequestResolution {
    data class Valid(val request: SessionHistoryToolRequest) : SessionHistoryToolRequestResolution

    data class Invalid(val message: String) : SessionHistoryToolRequestResolution
}

internal sealed interface SessionHistoryToolResponse {
    val output: String

    data class Search(
        val request: SessionHistoryToolRequest,
        val matches: List<SessionHistoryMatch>,
        override val output: String
    ) : SessionHistoryToolResponse

    data class Excerpt(
        val request: SessionHistoryToolRequest,
        val sessionId: String,
        val anchorMessageId: Long?,
        val excerpt: SessionHistoryExcerpt?,
        override val output: String
    ) : SessionHistoryToolResponse

    data class MultiExcerpt(
        val request: SessionHistoryToolRequest,
        val requestedReferences: List<String>,
        val excerpts: List<SessionHistoryReferencedExcerpt>,
        override val output: String
    ) : SessionHistoryToolResponse

    data class InvalidRequest(
        override val output: String
    ) : SessionHistoryToolResponse

    data class Failure(
        override val output: String
    ) : SessionHistoryToolResponse
}

internal fun buildSessionHistoryToolStructuredPayload(
    response: SessionHistoryToolResponse
): ToolStructuredPayload? {
    val payload = when (response) {
        is SessionHistoryToolResponse.Search -> SessionHistoryToolPayload(
            kind = "search",
            query = response.request.query.takeIf { it.isNotBlank() },
            projectOnly = response.request.projectOnly,
            sessionIds = response.matches.map { it.summary.id }.distinct(),
            messageReferences = response.matches.mapNotNull { it.messageReference }.distinct(),
            anchorMessageIds = response.matches.mapNotNull { it.anchorMessageId }.distinct(),
            matchedFields = response.matches.map { it.matchedField }.distinct(),
            snippets = response.matches.map { it.snippet }.distinct()
        )

        is SessionHistoryToolResponse.Excerpt -> SessionHistoryToolPayload(
            kind = "excerpt",
            query = response.request.query.takeIf { it.isNotBlank() },
            projectOnly = response.request.projectOnly,
            sessionIds = listOf(response.sessionId),
            messageReferences = listOfNotNull(
                response.request.messageReference,
                response.excerpt?.messageReference
            ).distinct(),
            anchorMessageIds = listOfNotNull(
                response.anchorMessageId,
                response.excerpt?.anchorMessageId
            ).distinct(),
            matchedFields = listOfNotNull(response.excerpt?.matchedField).distinct(),
            snippets = listOfNotNull(response.excerpt?.focusSnippet).distinct(),
            excerptWindows = listOfNotNull(
                response.excerpt?.let(::buildSessionHistoryExcerptWindowSummary)
            ).distinct()
        )

        is SessionHistoryToolResponse.MultiExcerpt -> SessionHistoryToolPayload(
            kind = "multi_excerpt",
            query = response.request.query.takeIf { it.isNotBlank() },
            projectOnly = response.request.projectOnly,
            sessionIds = response.excerpts.mapNotNull { it.excerpt?.summary?.id }.distinct(),
            messageReferences = (
                response.requestedReferences +
                    response.excerpts.mapNotNull { it.excerpt?.messageReference }
                ).distinct(),
            anchorMessageIds = response.excerpts.mapNotNull { it.excerpt?.anchorMessageId }.distinct(),
            matchedFields = response.excerpts.mapNotNull { it.excerpt?.matchedField }.distinct(),
            snippets = response.excerpts.mapNotNull { it.excerpt?.focusSnippet }.distinct(),
            excerptWindows = response.excerpts.mapNotNull { entry ->
                entry.excerpt?.let(::buildSessionHistoryExcerptWindowSummary)
            }.distinct()
        )

        is SessionHistoryToolResponse.InvalidRequest,
        is SessionHistoryToolResponse.Failure -> null
    }
    return payload?.let { ToolStructuredPayload(sessionHistory = it) }
}

internal fun parseSessionHistoryToolRequest(
    args: String,
    json: Json = Json { ignoreUnknownKeys = true }
): SessionHistoryToolRequestResolution {
    val obj = json.parseToJsonElement(args).jsonObject
    val rawMessageReferences = obj["message_references"]?.let { element ->
        runCatching {
            element.jsonArray.mapNotNull { item ->
                item.jsonPrimitive.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
            }
        }.getOrNull()
    }
    if (obj.containsKey("message_references") && rawMessageReferences == null) {
        return SessionHistoryToolRequestResolution.Invalid(
            "未找到可读取的历史消息引用列表，message_references 参数格式无效。"
        )
    }
    return SessionHistoryToolRequestResolution.Valid(
        SessionHistoryToolRequest(
            sessionId = obj["session_id"]?.jsonPrimitive?.contentOrNull?.trim()?.ifBlank { null },
            messageReference = obj["message_reference"]?.jsonPrimitive?.contentOrNull?.trim()?.ifBlank { null },
            messageReferences = rawMessageReferences
                ?.map(String::trim)
                ?.filter(String::isNotBlank)
                ?.distinct()
                .orEmpty(),
            query = obj["query"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty(),
            limit = (obj["limit"]?.jsonPrimitive?.intOrNull ?: 5).coerceIn(1, 20),
            excerptMessageLimit = (obj["excerpt_message_limit"]?.jsonPrimitive?.intOrNull ?: 6)
                .coerceIn(1, 12),
            anchorMessageId = obj["anchor_message_id"]?.jsonPrimitive?.longOrNull,
            projectOnly = obj["project_only"]?.jsonPrimitive?.contentOrNull.toBooleanStrictOrFalse(),
            includeCurrentSession = obj["include_current_session"]?.jsonPrimitive?.contentOrNull
                .toBooleanStrictOrFalse()
        )
    )
}

internal fun executeSessionHistoryToolRequest(
    request: SessionHistoryToolRequest,
    sessions: List<SessionSummary>,
    sessionLoader: (String) -> PersistedSession?,
    currentSessionId: String?,
    currentProjectPath: String?
): SessionHistoryToolResponse {
    if (request.messageReferences.isNotEmpty()) {
        val invalidReferences = request.messageReferences.filter {
            parseSessionHistoryMessageReference(it) == null
        }
        if (invalidReferences.isNotEmpty()) {
            return SessionHistoryToolResponse.InvalidRequest(
                output = buildString {
                    append("未找到可读取的历史消息引用，message_references: ")
                    append(invalidReferences.joinToString(", "))
                    append("。")
                }
            )
        }
        val parsedReferences = request.messageReferences.mapNotNull {
            parseSessionHistoryMessageReference(it)
        }
        val excerpts = loadSessionHistoryExcerptsByReferences(
            sessions = sessions,
            sessionLoader = sessionLoader,
            messageReferences = parsedReferences,
            query = request.query,
            currentSessionId = currentSessionId,
            currentProjectPath = currentProjectPath,
            projectOnly = request.projectOnly,
            includeCurrentSession = request.includeCurrentSession,
            excerptMessageLimit = request.excerptMessageLimit
        )
        return SessionHistoryToolResponse.MultiExcerpt(
            request = request,
            requestedReferences = request.messageReferences,
            excerpts = excerpts,
            output = formatSessionHistoryMultiExcerptResult(
                excerpts = excerpts,
                query = request.query,
                projectOnly = request.projectOnly
            )
        )
    }
    val parsedReference = parseSessionHistoryMessageReference(request.messageReference)
    if (!request.messageReference.isNullOrBlank() && parsedReference == null) {
        return SessionHistoryToolResponse.InvalidRequest(
            output = "未找到可读取的历史消息引用，message_reference: ${request.messageReference}。"
        )
    }
    val sessionId = parsedReference?.sessionId ?: request.sessionId.orEmpty()
    val anchorMessageId = parsedReference?.messageId ?: request.anchorMessageId
    if (sessionId.isNotBlank()) {
        val excerpt = loadSessionHistoryExcerpt(
            sessions = sessions,
            sessionLoader = sessionLoader,
            sessionId = sessionId,
            query = request.query,
            currentSessionId = currentSessionId,
            currentProjectPath = currentProjectPath,
            projectOnly = request.projectOnly,
            includeCurrentSession = request.includeCurrentSession,
            excerptMessageLimit = request.excerptMessageLimit,
            anchorMessageId = anchorMessageId
        )
        return SessionHistoryToolResponse.Excerpt(
            request = request,
            sessionId = sessionId,
            anchorMessageId = anchorMessageId,
            excerpt = excerpt,
            output = formatSessionHistoryExcerptResult(
                excerpt = excerpt,
                sessionId = sessionId,
                query = request.query,
                projectOnly = request.projectOnly,
                messageReference = request.messageReference
            )
        )
    }
    val matches = searchSessionHistory(
        sessions = sessions,
        sessionLoader = sessionLoader,
        query = request.query,
        currentSessionId = currentSessionId,
        currentProjectPath = currentProjectPath,
        projectOnly = request.projectOnly,
        includeCurrentSession = request.includeCurrentSession,
        limit = request.limit
    )
    return SessionHistoryToolResponse.Search(
        request = request,
        matches = matches,
        output = formatSessionHistorySearchResult(
            matches = matches,
            query = request.query,
            projectOnly = request.projectOnly
        )
    )
}

internal data class SessionHistoryMatch(
    val summary: SessionSummary,
    val sessionGoal: String?,
    val matchedField: String,
    val snippet: String,
    val anchorMessageId: Long?,
    val messageReference: String?
)

internal data class SessionHistoryExcerpt(
    val summary: SessionSummary,
    val sessionGoal: String?,
    val matchedField: String?,
    val focusSnippet: String?,
    val excerptStrategy: String,
    val messageReference: String?,
    val anchorMessageId: Long?,
    val excerptStartIndex: Int,
    val excerptMessages: List<PersistedMessage>,
    val totalMessageCount: Int
)

internal data class SessionHistoryReferencedExcerpt(
    val requestedMessageReference: String,
    val excerpt: SessionHistoryExcerpt?
)

private data class SessionHistoryMessageReference(
    val sessionId: String,
    val messageId: Long
)

private data class SessionHistoryMessagePreview(
    val messageId: Long,
    val snippet: String
)

internal fun searchSessionHistory(
    sessions: List<SessionSummary>,
    sessionLoader: (String) -> PersistedSession?,
    query: String,
    currentSessionId: String?,
    currentProjectPath: String?,
    projectOnly: Boolean,
    includeCurrentSession: Boolean,
    limit: Int
): List<SessionHistoryMatch> {
    val normalizedQuery = query.trim()
    if (normalizedQuery.isBlank()) return emptyList()
    val normalizedCurrentProjectPath = normalizeSessionHistoryProjectPath(currentProjectPath)

    // Build a combined text per session for BM25 scoring
    val candidates = sessions
        .asSequence()
        .filter { includeCurrentSession || it.id != currentSessionId }
        .filter { summary ->
            !projectOnly || normalizeSessionHistoryProjectPath(summary.projectPath) == normalizedCurrentProjectPath
        }
        .mapNotNull { summary ->
            val session = sessionLoader(summary.id)
            val texts = buildList {
                add(summary.title.trim())
                session?.sessionGoal?.trim()?.takeIf { it.isNotBlank() }?.let { add(it) }
                summary.projectPath?.trim()?.takeIf { it.isNotBlank() }?.let { add(it) }
                summary.latestFinalReadinessAuditSummary?.trim()?.takeIf { it.isNotBlank() }?.let { add(it) }
                session?.messages?.joinToString(" ") { msg ->
                    listOfNotNull(msg.content, msg.reasoning).joinToString(" ")
                }?.takeIf { it.isNotBlank() }?.let { add(it) }
            }
            if (texts.isEmpty()) return@mapNotNull null
            SessionDoc(summary, session, texts.joinToString(" "))
        }
        .toList()

    if (candidates.isEmpty()) return emptyList()

    val queryTokens = tokenizeForBm25(normalizedQuery)
    if (queryTokens.isEmpty()) return emptyList()

    // BM25 score all candidates
    val scored = bm25ScoreSessionHistory(candidates, queryTokens)
    if (scored.isEmpty()) return emptyList()

    return scored.mapNotNull { (index, _) ->
        val doc = candidates[index]
        buildSessionHistoryMatch(doc.summary, doc.session, normalizedQuery)
    }.take(limit.coerceIn(1, 20))
}

internal fun formatSessionHistorySearchResult(
    matches: List<SessionHistoryMatch>,
    query: String,
    projectOnly: Boolean
): String {
    if (matches.isEmpty()) {
        return buildString {
            append("未找到匹配的历史会话")
            if (query.isNotBlank()) {
                append("，关键词：")
                append(query)
            }
            if (projectOnly) {
                append("，范围：当前项目")
            }
            append("。")
        }
    }
    return buildString {
        append("历史会话命中 ")
        append(matches.size)
        append(" 条")
        if (query.isNotBlank()) {
            append("，关键词：")
            append(query)
        }
        if (projectOnly) {
            append("，范围：当前项目")
        }
        matches.forEachIndexed { index, match ->
            appendLine()
            append(index + 1)
            append(". ")
            append(match.summary.title.ifBlank { "未命名会话" })
            appendLine()
            append("   session_id: ")
            append(match.summary.id)
            appendLine()
            append("   updated_at: ")
            append(formatSessionHistoryTimestamp(match.summary.updatedAt))
            appendLine()
            match.summary.projectPath?.takeIf { it.isNotBlank() }?.let { projectPath ->
                append("   project: ")
                append(projectPath)
                appendLine()
            }
            match.sessionGoal?.takeIf { it.isNotBlank() }?.let { goal ->
                append("   goal: ")
                append(goal)
                appendLine()
            }
            append("   matched_field: ")
            append(match.matchedField)
            appendLine()
            match.anchorMessageId?.let { anchorMessageId ->
                append("   anchor_message_id: ")
                append(anchorMessageId)
                appendLine()
            }
            match.messageReference?.takeIf { it.isNotBlank() }?.let { messageReference ->
                append("   message_reference: ")
                append(messageReference)
                appendLine()
            }
            append("   snippet: ")
            append(match.snippet)
        }
    }.trim()
}

internal fun loadSessionHistoryExcerpt(
    sessions: List<SessionSummary>,
    sessionLoader: (String) -> PersistedSession?,
    sessionId: String,
    query: String,
    currentSessionId: String?,
    currentProjectPath: String?,
    projectOnly: Boolean,
    includeCurrentSession: Boolean,
    excerptMessageLimit: Int,
    anchorMessageId: Long?
): SessionHistoryExcerpt? {
    val normalizedSessionId = sessionId.trim()
    if (normalizedSessionId.isBlank()) return null
    if (!includeCurrentSession && normalizedSessionId == currentSessionId) return null
    val session = sessionLoader(normalizedSessionId) ?: return null
    val summary = sessions.firstOrNull { it.id == normalizedSessionId }
        ?: buildSessionHistorySessionSummary(session)
    val normalizedCurrentProjectPath = normalizeSessionHistoryProjectPath(currentProjectPath)
    if (projectOnly && normalizeSessionHistoryProjectPath(summary.projectPath) != normalizedCurrentProjectPath) {
        return null
    }
    val match = buildSessionHistoryMatch(summary, session, query)
    val excerptWindow = buildSessionHistoryExcerptWindow(
        messages = session.messages,
        query = query,
        limit = excerptMessageLimit,
        anchorMessageId = anchorMessageId
    )
    return SessionHistoryExcerpt(
        summary = summary,
        sessionGoal = session.sessionGoal?.trim()?.ifBlank { null },
        matchedField = match?.matchedField,
        focusSnippet = match?.snippet,
        excerptStrategy = excerptWindow.strategy,
        messageReference = excerptWindow.anchorMessageId?.let {
            buildSessionHistoryMessageReference(summary.id, it)
        },
        anchorMessageId = excerptWindow.anchorMessageId,
        excerptStartIndex = excerptWindow.startIndex,
        excerptMessages = excerptWindow.messages,
        totalMessageCount = session.messages.size
    )
}

internal fun formatSessionHistoryExcerptResult(
    excerpt: SessionHistoryExcerpt?,
    sessionId: String,
    query: String,
    projectOnly: Boolean,
    messageReference: String? = null
): String {
    if (excerpt == null) {
        return buildString {
            append("未找到可读取的历史会话摘录")
            messageReference?.takeIf { it.isNotBlank() }?.let { reference ->
                append("，message_reference: ")
                append(reference)
            } ?: run {
                append("，session_id: ")
                append(sessionId)
            }
            if (query.isNotBlank()) {
                append("，关键词：")
                append(query)
            }
            if (projectOnly) {
                append("，范围：当前项目")
            }
            append("。")
        }
    }
    return buildString {
        append("历史会话摘录")
        if (query.isNotBlank()) {
            append("，关键词：")
            append(query)
        }
        if (projectOnly) {
            append("，范围：当前项目")
        }
        appendLine()
        appendSessionHistoryExcerptBody(
            excerpt = excerpt,
            requestedMessageReference = messageReference
        )
    }.trim()
}

private fun loadSessionHistoryExcerptsByReferences(
    sessions: List<SessionSummary>,
    sessionLoader: (String) -> PersistedSession?,
    messageReferences: List<SessionHistoryMessageReference>,
    query: String,
    currentSessionId: String?,
    currentProjectPath: String?,
    projectOnly: Boolean,
    includeCurrentSession: Boolean,
    excerptMessageLimit: Int
): List<SessionHistoryReferencedExcerpt> {
    return messageReferences.map { reference ->
        val normalizedReference = buildSessionHistoryMessageReference(
            sessionId = reference.sessionId,
            messageId = reference.messageId
        )
        SessionHistoryReferencedExcerpt(
            requestedMessageReference = normalizedReference,
            excerpt = loadSessionHistoryExcerpt(
                sessions = sessions,
                sessionLoader = sessionLoader,
                sessionId = reference.sessionId,
                query = query,
                currentSessionId = currentSessionId,
                currentProjectPath = currentProjectPath,
                projectOnly = projectOnly,
                includeCurrentSession = includeCurrentSession,
                excerptMessageLimit = excerptMessageLimit,
                anchorMessageId = reference.messageId
            )
        )
    }
}

internal fun formatSessionHistoryMultiExcerptResult(
    excerpts: List<SessionHistoryReferencedExcerpt>,
    query: String,
    projectOnly: Boolean
): String {
    if (excerpts.isEmpty()) {
        return "未找到可读取的跨会话摘录。"
    }
    val foundCount = excerpts.count { it.excerpt != null }
    if (foundCount == 0) {
        return buildString {
            append("未找到可读取的跨会话摘录，message_references: ")
            append(excerpts.joinToString(", ") { it.requestedMessageReference })
            if (query.isNotBlank()) {
                append("，关键词：")
                append(query)
            }
            if (projectOnly) {
                append("，范围：当前项目")
            }
            append("。")
        }
    }
    return buildString {
        append("跨会话历史摘录命中 ")
        append(foundCount)
        append("/")
        append(excerpts.size)
        append(" 条")
        if (query.isNotBlank()) {
            append("，关键词：")
            append(query)
        }
        if (projectOnly) {
            append("，范围：当前项目")
        }
        appendLine()
        append("message_references: ")
        append(excerpts.joinToString(", ") { it.requestedMessageReference })
        excerpts.forEachIndexed { index, entry ->
            appendLine()
            append(index + 1)
            append(". ")
            val excerpt = entry.excerpt
            if (excerpt == null) {
                append("requested_message_reference: ")
                append(entry.requestedMessageReference)
                appendLine()
                append("   status: 未找到可读取的历史会话摘录")
            } else {
                appendLine()
                appendIndentedSessionHistoryExcerptBody(
                    excerpt = excerpt,
                    requestedMessageReference = entry.requestedMessageReference,
                    indent = "   "
                )
            }
        }
    }.trim()
}

private fun buildSessionHistoryMatch(
    summary: SessionSummary,
    session: PersistedSession?,
    query: String
): SessionHistoryMatch? {
    val sessionGoal = session?.sessionGoal?.trim().orEmpty()
    val normalizedQuery = query.lowercase(Locale.getDefault())
    val referencePreview = buildSessionHistoryReferencePreview(session)
    if (normalizedQuery.isBlank()) {
        return SessionHistoryMatch(
            summary = summary,
            sessionGoal = sessionGoal.ifBlank { null },
            matchedField = "最新会话",
            snippet = buildSessionHistoryFallbackSnippet(summary, session),
            anchorMessageId = referencePreview?.messageId,
            messageReference = referencePreview?.let {
                buildSessionHistoryMessageReference(summary.id, it.messageId)
            }
        )
    }
    val title = summary.title.trim()
    if (title.contains(normalizedQuery, ignoreCase = true)) {
        return SessionHistoryMatch(
            summary = summary,
            sessionGoal = sessionGoal.ifBlank { null },
            matchedField = "标题",
            snippet = title,
            anchorMessageId = referencePreview?.messageId,
            messageReference = referencePreview?.let {
                buildSessionHistoryMessageReference(summary.id, it.messageId)
            }
        )
    }
    if (sessionGoal.contains(normalizedQuery, ignoreCase = true)) {
        return SessionHistoryMatch(
            summary = summary,
            sessionGoal = sessionGoal.ifBlank { null },
            matchedField = "任务目标",
            snippet = sessionGoal,
            anchorMessageId = referencePreview?.messageId,
            messageReference = referencePreview?.let {
                buildSessionHistoryMessageReference(summary.id, it.messageId)
            }
        )
    }
    val projectPath = summary.projectPath?.trim().orEmpty()
    if (projectPath.contains(normalizedQuery, ignoreCase = true)) {
        return SessionHistoryMatch(
            summary = summary,
            sessionGoal = sessionGoal.ifBlank { null },
            matchedField = "项目路径",
            snippet = projectPath,
            anchorMessageId = referencePreview?.messageId,
            messageReference = referencePreview?.let {
                buildSessionHistoryMessageReference(summary.id, it.messageId)
            }
        )
    }
    val finalReadiness = summary.latestFinalReadinessAuditSummary?.trim().orEmpty()
    if (finalReadiness.contains(normalizedQuery, ignoreCase = true)) {
        return SessionHistoryMatch(
            summary = summary,
            sessionGoal = sessionGoal.ifBlank { null },
            matchedField = "收口摘要",
            snippet = finalReadiness,
            anchorMessageId = referencePreview?.messageId,
            messageReference = referencePreview?.let {
                buildSessionHistoryMessageReference(summary.id, it.messageId)
            }
        )
    }
    val messageMatch = session?.messages
        ?.asReversed()
        ?.firstNotNullOfOrNull { message ->
            buildSessionHistoryMessagePreview(message, normalizedQuery)
        }
    if (messageMatch != null) {
        return SessionHistoryMatch(
            summary = summary,
            sessionGoal = sessionGoal.ifBlank { null },
            matchedField = "消息正文",
            snippet = messageMatch.snippet,
            anchorMessageId = messageMatch.messageId,
            messageReference = buildSessionHistoryMessageReference(summary.id, messageMatch.messageId)
        )
    }
    return null
}

private fun buildSessionHistoryFallbackSnippet(
    summary: SessionSummary,
    session: PersistedSession?
): String {
    return buildSessionHistoryReferencePreview(session)?.snippet
        ?: summary.latestFinalReadinessAuditSummary?.takeIf { it.isNotBlank() }
        ?: summary.projectPath?.takeIf { it.isNotBlank() }
        ?: "provider=${summary.providerId} model=${summary.modelName} messages=${summary.messageCount}"
}

private fun buildSessionHistoryMessageSnippet(
    message: PersistedMessage,
    query: String?
): String? {
    return buildSessionHistoryMessagePreview(message, query)?.snippet
}

private fun buildSessionHistoryMessagePreview(
    message: PersistedMessage,
    query: String?
): SessionHistoryMessagePreview? {
    val content = formatSessionHistoryMessageContent(message.content, limit = 180)
    if (content.isBlank()) return null
    if (query.isNullOrBlank()) {
        return SessionHistoryMessagePreview(
            messageId = message.id,
            snippet = "${message.role}: $content"
        )
    }
    return if (content.contains(query, ignoreCase = true)) {
        SessionHistoryMessagePreview(
            messageId = message.id,
            snippet = "${message.role}: $content"
        )
    } else {
        null
    }
}

private fun buildSessionHistoryReferencePreview(session: PersistedSession?): SessionHistoryMessagePreview? {
    return session?.messages
        ?.asReversed()
        ?.firstNotNullOfOrNull { message ->
            buildSessionHistoryMessagePreview(message, query = null)
        }
}

private fun sessionHistoryMatchScore(match: SessionHistoryMatch, query: String): Int {
    if (query.isBlank()) return 0
    return when (match.matchedField) {
        "标题" -> 400
        "任务目标" -> 300
        "项目路径" -> 200
        "收口摘要" -> 120
        "消息正文" -> 100
        else -> 0
    }
}

// ── BM25 scoring for session history search ──

private fun tokenizeForBm25(text: String): List<String> {
    val result = mutableListOf<String>()
    val latinBuf = StringBuilder()
    for (ch in text.lowercase()) {
        when {
            ch in '\u4E00'..'\u9FFF' || ch in '\uAC00'..'\uD7AF' ||
                ch in '\u3040'..'\u309F' || ch in '\u30A0'..'\u30FF' -> {
                if (latinBuf.isNotEmpty()) { result.add(latinBuf.toString()); latinBuf.clear() }
                result.add(ch.toString())
            }
            ch.isLetterOrDigit() || ch == '_' || ch == '-' -> latinBuf.append(ch)
            else -> {
                if (latinBuf.isNotEmpty()) { result.add(latinBuf.toString()); latinBuf.clear() }
            }
        }
    }
    if (latinBuf.isNotEmpty()) result.add(latinBuf.toString())
    return result
}

private data class SessionDoc(
    val summary: SessionSummary,
    val session: PersistedSession?,
    val combinedText: String
)

private fun bm25ScoreSessionHistory(
    docs: List<SessionDoc>,
    queryTokens: List<String>,
    k1: Double = 1.2,
    b: Double = 0.75
): List<Pair<Int, Double>> {
    if (queryTokens.isEmpty() || docs.isEmpty()) return emptyList()

    data class DocTokens(val index: Int, val tokens: List<String>, val termFreq: Map<String, Int>)
    val tokenized = docs.mapIndexed { index, doc ->
        val tokens = tokenizeForBm25(doc.combinedText)
        val termFreq = tokens.groupingBy { it }.eachCount()
        DocTokens(index, tokens, termFreq)
    }

    val numDocs = tokenized.size.toDouble()
    val avgDocLen = tokenized.map { it.tokens.size }.average().coerceAtLeast(1.0)
    val uniqueTerms = queryTokens.toSet()

    val docFreqs = mutableMapOf<String, Int>()
    for (term in uniqueTerms) {
        docFreqs[term] = tokenized.count { it.termFreq.containsKey(term) }
    }

    return tokenized.mapNotNull { doc ->
        val docLen = doc.tokens.size.coerceAtLeast(1).toDouble()
        var score = 0.0
        for (term in queryTokens) {
            val tf = doc.termFreq[term]?.toDouble() ?: continue
            val df = docFreqs[term] ?: continue
            if (df <= 0) continue
            val idf = Math.log(1.0 + (numDocs - df + 0.5) / (df + 0.5))
            score += idf * (tf * (k1 + 1.0)) / (tf + k1 * (1.0 - b + b * docLen / avgDocLen))
        }
        if (score <= 0.0) null else doc.index to score
    }.sortedByDescending { it.second }
}

private fun buildSessionHistorySessionSummary(session: PersistedSession): SessionSummary {
    return SessionSummary(
        id = session.id,
        title = session.title,
        createdAt = session.createdAt,
        updatedAt = session.updatedAt,
        messageCount = session.messages.size,
        providerId = session.providerId,
        modelName = session.modelName,
        projectPath = session.projectPath
    )
}

private data class SessionHistoryExcerptWindow(
    val messages: List<PersistedMessage>,
    val startIndex: Int,
    val anchorMessageId: Long?,
    val strategy: String
)

private fun buildSessionHistoryExcerptWindow(
    messages: List<PersistedMessage>,
    query: String,
    limit: Int,
    anchorMessageId: Long?
): SessionHistoryExcerptWindow {
    if (messages.isEmpty()) {
        return SessionHistoryExcerptWindow(
            messages = emptyList(),
            startIndex = 0,
            anchorMessageId = anchorMessageId,
            strategy = "空会话"
        )
    }
    if (query.isBlank()) {
        val anchorIndex = anchorMessageId
            ?.let { id -> messages.indexOfFirst { it.id == id } }
            ?.takeIf { it >= 0 }
        return buildSessionHistoryExcerptWindowAroundIndex(
            messages = messages,
            centerIndex = anchorIndex,
            limit = limit,
            fallbackStrategy = if (anchorIndex != null) "指定消息附近" else "最近消息",
            anchorMessageId = anchorIndex?.let { messages[it].id }
        )
    }
    val hitIndex = messages.indexOfFirst { message ->
        message.content.contains(query, ignoreCase = true) ||
            (message.reasoning?.contains(query, ignoreCase = true) == true)
    }
    if (anchorMessageId != null) {
        val anchorIndex = messages.indexOfFirst { it.id == anchorMessageId }.takeIf { it >= 0 }
        val resolvedCenterIndex = anchorIndex ?: hitIndex.takeIf { it >= 0 }
        return buildSessionHistoryExcerptWindowAroundIndex(
            messages = messages,
            centerIndex = resolvedCenterIndex,
            limit = limit,
            fallbackStrategy = when {
                anchorIndex != null -> "指定消息附近"
                hitIndex >= 0 -> "关键词命中附近"
                else -> "最近消息"
            },
            anchorMessageId = anchorIndex?.let { messages[it].id } ?: hitIndex.takeIf { it >= 0 }?.let { messages[it].id }
        )
    }
    if (hitIndex < 0) {
        return buildSessionHistoryExcerptWindowAroundIndex(
            messages = messages,
            centerIndex = null,
            limit = limit,
            fallbackStrategy = "最近消息",
            anchorMessageId = null
        )
    }
    return buildSessionHistoryExcerptWindowAroundIndex(
        messages = messages,
        centerIndex = hitIndex,
        limit = limit,
        fallbackStrategy = "关键词命中附近",
        anchorMessageId = messages[hitIndex].id
    )
}

private fun buildSessionHistoryExcerptWindowAroundIndex(
    messages: List<PersistedMessage>,
    centerIndex: Int?,
    limit: Int,
    fallbackStrategy: String,
    anchorMessageId: Long?
): SessionHistoryExcerptWindow {
    if (messages.isEmpty()) {
        return SessionHistoryExcerptWindow(
            messages = emptyList(),
            startIndex = 0,
            anchorMessageId = anchorMessageId,
            strategy = "空会话"
        )
    }
    val resolvedCenterIndex = centerIndex ?: (messages.lastIndex)
    val start = (resolvedCenterIndex - ((limit - 1) / 2)).coerceAtLeast(0)
    val endExclusive = (start + limit).coerceAtMost(messages.size)
    val adjustedStart = (endExclusive - limit).coerceAtLeast(0)
    return SessionHistoryExcerptWindow(
        messages = messages.subList(adjustedStart, endExclusive),
        startIndex = adjustedStart,
        anchorMessageId = anchorMessageId,
        strategy = fallbackStrategy
    )
}

private fun formatSessionHistoryMessageContent(content: String, limit: Int): String {
    return content
        .lineSequence()
        .map(String::trim)
        .filter { it.isNotBlank() }
        .joinToString(" / ")
        .take(limit)
}

private fun buildSessionHistoryMessageReference(sessionId: String, messageId: Long): String {
    return "$sessionId#$messageId"
}

private fun StringBuilder.appendSessionHistoryExcerptBody(
    excerpt: SessionHistoryExcerpt,
    requestedMessageReference: String?
) {
    appendIndentedSessionHistoryExcerptBody(
        excerpt = excerpt,
        requestedMessageReference = requestedMessageReference,
        indent = ""
    )
}

private fun StringBuilder.appendIndentedSessionHistoryExcerptBody(
    excerpt: SessionHistoryExcerpt,
    requestedMessageReference: String?,
    indent: String
) {
    append(indent)
    append("session_id: ")
    append(excerpt.summary.id)
    appendLine()
    requestedMessageReference?.takeIf { it.isNotBlank() }?.let { reference ->
        append(indent)
        append("message_reference: ")
        append(reference)
        appendLine()
    }
    append(indent)
    append("title: ")
    append(excerpt.summary.title.ifBlank { "未命名会话" })
    appendLine()
    append(indent)
    append("updated_at: ")
    append(formatSessionHistoryTimestamp(excerpt.summary.updatedAt))
    appendLine()
    excerpt.summary.projectPath?.takeIf { it.isNotBlank() }?.let { projectPath ->
        append(indent)
        append("project: ")
        append(projectPath)
        appendLine()
    }
    excerpt.sessionGoal?.let { goal ->
        append(indent)
        append("goal: ")
        append(goal)
        appendLine()
    }
    excerpt.matchedField?.let { matchedField ->
        append(indent)
        append("matched_field: ")
        append(matchedField)
        appendLine()
    }
    excerpt.focusSnippet?.takeIf { it.isNotBlank() }?.let { snippet ->
        append(indent)
        append("focus_snippet: ")
        append(snippet)
        appendLine()
    }
    append(indent)
    append("excerpt_strategy: ")
    append(excerpt.excerptStrategy)
    appendLine()
    append(indent)
    append("excerpt_messages: ")
    append(excerpt.excerptMessages.size)
    append("/")
    append(excerpt.totalMessageCount)
    appendLine()
    append(indent)
    append("message_range: ")
    append(buildSessionHistoryMessageRange(excerpt))
    excerpt.anchorMessageId?.let { anchorMessageId ->
        appendLine()
        append(indent)
        append("anchor_message_id: ")
        append(anchorMessageId)
    }
    excerpt.messageReference?.takeIf { it.isNotBlank() }?.let { resolvedReference ->
        appendLine()
        append(indent)
        append("resolved_message_reference: ")
        append(resolvedReference)
    }
    excerpt.excerptMessages.forEachIndexed { index, message ->
        appendLine()
        append(indent)
        append(index + 1)
        append(". ref=")
        append(buildSessionHistoryMessageReference(excerpt.summary.id, message.id))
        append(" [")
        append(formatSessionHistoryTimestamp(message.timestamp))
        append("] ")
        append(message.role)
        append(": ")
        append(formatSessionHistoryMessageContent(message.content, limit = 240))
    }
}

private fun buildSessionHistoryMessageRange(excerpt: SessionHistoryExcerpt): String {
    return buildString {
        append(excerpt.excerptStartIndex + 1)
        append("-")
        append(excerpt.excerptStartIndex + excerpt.excerptMessages.size)
        append("/")
        append(excerpt.totalMessageCount)
    }
}

internal fun buildSessionHistoryExcerptWindowSummary(excerpt: SessionHistoryExcerpt): String {
    return buildString {
        append(buildSessionHistoryMessageRange(excerpt))
        append("（")
        append(excerpt.excerptStrategy)
        append("）")
    }
}

private fun parseSessionHistoryMessageReference(reference: String?): SessionHistoryMessageReference? {
    val normalizedReference = reference?.trim().orEmpty()
    if (normalizedReference.isBlank()) return null
    val separatorIndex = normalizedReference.lastIndexOf('#')
    if (separatorIndex <= 0 || separatorIndex == normalizedReference.lastIndex) return null
    val sessionId = normalizedReference.substring(0, separatorIndex).trim()
    val messageId = normalizedReference.substring(separatorIndex + 1).trim().toLongOrNull()
    if (sessionId.isBlank() || messageId == null) return null
    return SessionHistoryMessageReference(
        sessionId = sessionId,
        messageId = messageId
    )
}

private fun String?.toBooleanStrictOrFalse(): Boolean {
    return when (this?.trim()?.lowercase(Locale.getDefault())) {
        "true" -> true
        else -> false
    }
}

private fun normalizeSessionHistoryProjectPath(path: String?): String? {
    return path
        ?.trim()
        ?.replace('\\', '/')
        ?.removeSuffix("/")
        ?.lowercase(Locale.getDefault())
        ?.ifBlank { null }
}

private fun formatSessionHistoryTimestamp(timestamp: Long): String {
    return SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
}
