package com.murong.agent.core.loop

/**
 * Whether an assistant turn's final text looks like a workflow plan
 * (SUMMARY:/STEPS: structure or an explicit numbered/bulleted step list)
 * rather than a plain chat answer. Used by the plan-first round to decide
 * whether to capture the output as an inline plan card.
 */
internal fun looksLikeWorkflowPlanText(raw: String): Boolean {
    val normalized = raw.trim()
    if (normalized.isBlank()) return false
    if (normalized.contains("SUMMARY:", ignoreCase = true)) return true
    return normalized.lines().any { line ->
        val trimmed = line.trim()
        trimmed.matches(Regex("""^\d+[.)]\s*.+""")) ||
            trimmed.startsWith("- ") ||
            trimmed.startsWith("* ")
    }
}
