package com.murong.agent.ui.tools

import com.murong.agent.core.loop.ApprovalInvalidationRecordUi
import com.murong.agent.core.loop.ProjectApprovalHistoryTelemetry
import com.murong.agent.core.loop.ProjectApprovalItemKind
import com.murong.agent.core.loop.ProjectApprovalItemTelemetry
import com.murong.agent.core.loop.ApprovedApprovalScopeUi
import com.murong.agent.core.loop.InheritedApprovalScopeUi
import com.murong.agent.core.loop.ProjectApprovalHistoryUi
import com.murong.agent.core.loop.ProjectApprovalScopeTrendUi
import com.murong.agent.ui.sanitizeForUiDisplay

internal fun buildProjectApprovalHistorySummary(history: ProjectApprovalHistoryUi?): String {
    return history?.let {
        ProjectApprovalHistoryTelemetry(
            analyzedSessionCount = it.analyzedSessionCount,
            sessionsWithApprovedScopes = it.sessionsWithApprovedScopes,
            distinctScopeCount = it.distinctScopeCount
        )
    }.let(::buildProjectApprovalHistorySummary)
}

internal fun buildProjectApprovalHistorySummary(history: ProjectApprovalHistoryTelemetry?): String {
    if (history == null) return "当前项目还没有可复用的授权历史。"
    return buildList {
        add("已分析 ${history.analyzedSessionCount} 个会话")
        add("含授权会话 ${history.sessionsWithApprovedScopes} 个")
        add("范围趋势 ${history.distinctScopeCount} 项")
    }.joinToString("，")
}

internal fun ApprovedApprovalScopeUi.toApprovedScopeSubtitle(): String {
    return ProjectApprovalItemTelemetry(
        kind = ProjectApprovalItemKind.CURRENT_SCOPE,
        summary = summary,
        sourceLabel = sourceLabel,
        sourceDetail = sourceDetail
    ).toProjectApprovalItemSubtitle()
}

internal fun ProjectApprovalItemTelemetry.toProjectApprovalItemSubtitle(): String {
    return when (kind) {
        ProjectApprovalItemKind.CURRENT_SCOPE -> toApprovedScopeSubtitle()
        ProjectApprovalItemKind.INHERITED_SCOPE -> toInheritedScopeSubtitle()
        ProjectApprovalItemKind.TREND -> toTrendSubtitle()
        ProjectApprovalItemKind.INVALIDATION -> toInvalidationSubtitle()
    }
}

private fun ProjectApprovalItemTelemetry.toApprovedScopeSubtitle(): String {
    return buildList {
        add("来源：${sanitizeNullableForUiDisplay(sourceLabel)}")
        sourceDetail?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { add(sanitizeForUiDisplay(it)) }
    }.joinToString("\n")
}

internal fun InheritedApprovalScopeUi.toInheritedScopeSubtitle(): String {
    return ProjectApprovalItemTelemetry(
        kind = ProjectApprovalItemKind.INHERITED_SCOPE,
        summary = summary,
        policyLabel = policyLabel,
        policyDetail = policyDetail,
        sourceSessionTitle = sourceSessionTitle
    ).toProjectApprovalItemSubtitle()
}

private fun ProjectApprovalItemTelemetry.toInheritedScopeSubtitle(): String {
    return buildList {
        add("策略：${sanitizeNullableForUiDisplay(policyLabel)}")
        add(sanitizeNullableForUiDisplay(policyDetail))
        add("来源会话：${sanitizeNullableForUiDisplay(sourceSessionTitle)}")
    }.joinToString("\n")
}

internal fun ProjectApprovalScopeTrendUi.toTrendSubtitle(): String {
    return ProjectApprovalItemTelemetry(
        kind = ProjectApprovalItemKind.TREND,
        summary = summary,
        policyLabel = policyLabel,
        policyDetail = policyDetail,
        sessionCount = sessionCount,
        directSessionCount = directSessionCount,
        importedSessionCount = importedSessionCount,
        autoInheritedSessionCount = autoInheritedSessionCount
    ).toProjectApprovalItemSubtitle()
}

private fun ProjectApprovalItemTelemetry.toTrendSubtitle(): String {
    return buildList {
        add("命中会话 ${sessionCount ?: 0} 个")
        add("直接批准 ${directSessionCount ?: 0} 个")
        if ((importedSessionCount ?: 0) > 0) {
            add("手动导入 ${importedSessionCount} 个")
        }
        if ((autoInheritedSessionCount ?: 0) > 0) {
            add("自动继承 ${autoInheritedSessionCount} 个")
        }
        add("策略：${sanitizeNullableForUiDisplay(policyLabel)}")
    }.joinToString("，").let { summary ->
        "$summary\n${sanitizeNullableForUiDisplay(policyDetail)}"
    }
}

internal fun ApprovalInvalidationRecordUi.toInvalidationSubtitle(): String {
    return ProjectApprovalItemTelemetry(
        kind = ProjectApprovalItemKind.INVALIDATION,
        summary = summary,
        sourceLabel = sourceLabel,
        sourceDetail = sourceDetail,
        reasonLabel = reasonLabel,
        reasonDetail = reasonDetail
    ).toProjectApprovalItemSubtitle()
}

private fun ProjectApprovalItemTelemetry.toInvalidationSubtitle(): String {
    return buildList {
        add("来源：${sanitizeNullableForUiDisplay(sourceLabel)}")
        sourceDetail?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { add(sanitizeForUiDisplay(it)) }
        add("失效原因：${sanitizeNullableForUiDisplay(reasonLabel)}")
        add(sanitizeNullableForUiDisplay(reasonDetail))
    }.joinToString("\n")
}

private fun sanitizeNullableForUiDisplay(value: String?): String {
    return value?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.let(::sanitizeForUiDisplay)
        ?: "未知"
}
