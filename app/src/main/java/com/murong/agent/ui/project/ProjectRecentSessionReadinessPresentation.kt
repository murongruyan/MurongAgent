package com.murong.agent.ui.project

import com.murong.agent.core.loop.SessionSummary
import com.murong.agent.ui.toSessionReadinessPresentation
import java.util.Locale

internal data class ProjectRecentSessionReadinessCardModel(
    val session: SessionSummary,
    val sourceLabel: String,
    val statusLabel: String,
    val actionLabel: String,
    val supportText: String,
    val blocked: Boolean,
    val readinessSummary: String,
    val readinessReasonSummary: String? = null
)

internal fun buildProjectRecentSessionReadinessCardModel(
    sessions: List<SessionSummary>,
    currentProjectPath: String?,
    selectedRepoRoot: String?
): ProjectRecentSessionReadinessCardModel? {
    val candidates = sessions.filter { it.toSessionReadinessPresentation() != null }
    if (candidates.isEmpty()) return null
    selectedRepoRoot?.let { repoRoot ->
        candidates
            .filter { sessionPathMatchesProjectScope(it.projectPath, repoRoot) }
            .maxByOrNull { normalizeProjectScopePath(it.projectPath).length }
            ?.let { return createProjectRecentSessionReadinessCardModel(it, "当前仓库") }
    }
    currentProjectPath?.let { projectPath ->
        candidates
            .filter { sessionPathMatchesProjectScope(it.projectPath, projectPath) }
            .maxByOrNull { normalizeProjectScopePath(it.projectPath).length }
            ?.let { return createProjectRecentSessionReadinessCardModel(it, "当前项目") }
    }
    return candidates.firstOrNull()?.let {
        createProjectRecentSessionReadinessCardModel(it, "最近活跃会话")
    }
}

private fun createProjectRecentSessionReadinessCardModel(
    session: SessionSummary,
    sourceLabel: String
): ProjectRecentSessionReadinessCardModel {
    val readinessPresentation = requireNotNull(session.toSessionReadinessPresentation()) {
        "session readiness presentation should exist for filtered candidates"
    }
    return ProjectRecentSessionReadinessCardModel(
        session = session,
        sourceLabel = sourceLabel,
        statusLabel = readinessPresentation.statusLabel,
        actionLabel = readinessPresentation.actionLabel,
        supportText = buildProjectRecentSessionSupportText(session, sourceLabel),
        blocked = readinessPresentation.blocked,
        readinessSummary = readinessPresentation.summary,
        readinessReasonSummary = readinessPresentation.reasonSummary
            ?.takeIf { it.isNotBlank() && it != readinessPresentation.summary }
    )
}

private fun buildProjectRecentSessionSupportText(
    session: SessionSummary,
    sourceLabel: String
): String {
    return buildString {
        append(sourceLabel)
        append(" · ")
        append(session.messageCount)
        append(" 条消息")
        if (session.finalReadinessAuditCount > 0) {
            append(" · 收口 ")
            append(session.finalReadinessAuditCount)
            append(" 次")
        }
        if (session.providerId.isNotBlank()) {
            append(" · ")
            append(session.providerId)
        }
    }
}

internal fun sessionPathMatchesProjectScope(sessionPath: String?, scopePath: String): Boolean {
    val normalizedSessionPath = normalizeProjectScopePath(sessionPath)
    val normalizedScopePath = normalizeProjectScopePath(scopePath)
    if (normalizedSessionPath.isBlank() || normalizedScopePath.isBlank()) return false
    return normalizedSessionPath == normalizedScopePath ||
        normalizedSessionPath.startsWith("$normalizedScopePath/") ||
        normalizedScopePath.startsWith("$normalizedSessionPath/")
}

private fun normalizeProjectScopePath(path: String?): String {
    return path?.replace('\\', '/')?.trimEnd('/')?.lowercase(Locale.ROOT).orEmpty()
}
