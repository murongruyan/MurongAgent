package com.murong.agent.core.loop

import java.security.MessageDigest

internal data class PromptCacheShape(
    val prefixHash: String,
    val stableSystemHash: String,
    val toolsHash: String,
    val compressionHash: String,
    val projectContextHash: String,
    val sessionGoalHash: String,
    val projectSkillsHash: String,
    val mcpToolsHash: String,
    val planModeHash: String
)

internal data class PromptCacheDiagnostics(
    val prefixChanged: Boolean,
    val prefixChangeReasons: List<String>,
    val shape: PromptCacheShape
)

internal fun capturePromptCacheShape(
    stableSystemContext: String?,
    toolsJson: String,
    compressionContext: String?,
    projectContext: String?,
    sessionGoalContext: String?,
    projectSkillsContext: String?,
    mcpToolsContext: String?,
    readOnlyPlanMode: Boolean
): PromptCacheShape {
    val stableSystemHash = sha256OrAbsent(stableSystemContext)
    val toolsHash = sha256OrAbsent(toolsJson)
    val compressionHash = sha256OrAbsent(compressionContext)
    val projectContextHash = sha256OrAbsent(projectContext)
    val sessionGoalHash = sha256OrAbsent(sessionGoalContext)
    val projectSkillsHash = sha256OrAbsent(projectSkillsContext)
    val mcpToolsHash = sha256OrAbsent(mcpToolsContext)
    val planModeHash = sha256OrAbsent("readOnlyPlanMode=$readOnlyPlanMode")
    val prefixHash = sha256OrAbsent(
        listOf(
            stableSystemHash,
            toolsHash,
            compressionHash,
            projectContextHash,
            sessionGoalHash,
            projectSkillsHash,
            mcpToolsHash,
            planModeHash
        ).joinToString("|")
    )
    return PromptCacheShape(
        prefixHash = prefixHash,
        stableSystemHash = stableSystemHash,
        toolsHash = toolsHash,
        compressionHash = compressionHash,
        projectContextHash = projectContextHash,
        sessionGoalHash = sessionGoalHash,
        projectSkillsHash = projectSkillsHash,
        mcpToolsHash = mcpToolsHash,
        planModeHash = planModeHash
    )
}

internal fun buildPromptCacheDiagnostics(
    previous: UsageSummarySnapshot,
    current: PromptCacheShape
): PromptCacheDiagnostics {
    val previousPrefixHash = previous.lastCachePrefixHash?.takeIf { it.isNotBlank() }
    if (previousPrefixHash == null) {
        return PromptCacheDiagnostics(
            prefixChanged = false,
            prefixChangeReasons = emptyList(),
            shape = current
        )
    }
    val reasons = buildList {
        if (previous.lastCacheToolsHash != current.toolsHash) add("tools")
        if (previous.lastCacheCompressionHash != current.compressionHash) add("compression")
        if (previous.lastCacheProjectContextHash != current.projectContextHash) add("project_context")
        if (previous.lastCacheSessionGoalHash != current.sessionGoalHash) add("session_goal")
        if (previous.lastCacheProjectSkillsHash != current.projectSkillsHash) add("project_skills")
        if (previous.lastCacheMcpToolsHash != current.mcpToolsHash) add("mcp_tools")
        if (previous.lastCachePlanModeHash != current.planModeHash) add("plan_mode")
        if (previous.lastCacheStableSystemHash != current.stableSystemHash) add("stable_system")
    }
    val prefixChanged = previousPrefixHash != current.prefixHash
    return PromptCacheDiagnostics(
        prefixChanged = prefixChanged,
        prefixChangeReasons = if (prefixChanged) reasons.distinct() else emptyList(),
        shape = current
    )
}

private fun sha256OrAbsent(text: String?): String {
    val normalized = text?.trim().takeUnless { it.isNullOrEmpty() } ?: "<absent>"
    val bytes = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray())
    return bytes.joinToString("") { "%02x".format(it) }
}
