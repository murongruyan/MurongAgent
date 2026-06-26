package com.murong.agent.core.loop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PromptCacheDiagnosticsPolicyTest {

    @Test
    fun buildPromptCacheDiagnostics_whenNoPreviousPrefix_doesNotReportChange() {
        val currentShape = capturePromptCacheShape(
            stableSystemContext = "system",
            toolsJson = """[{"name":"read_file"}]""",
            compressionContext = null,
            projectContext = "project",
            sessionGoalContext = "goal",
            projectSkillsContext = null,
            mcpToolsContext = null,
            readOnlyPlanMode = false
        )

        val diagnostics = buildPromptCacheDiagnostics(
            previous = UsageSummarySnapshot(),
            current = currentShape
        )

        assertFalse(diagnostics.prefixChanged)
        assertTrue(diagnostics.prefixChangeReasons.isEmpty())
    }

    @Test
    fun buildPromptCacheDiagnostics_reportsConcretePrefixChangeReasons() {
        val previousShape = capturePromptCacheShape(
            stableSystemContext = "system-a",
            toolsJson = """[{"name":"read_file"}]""",
            compressionContext = "compression-a",
            projectContext = "project-a",
            sessionGoalContext = "goal-a",
            projectSkillsContext = "skills-a",
            mcpToolsContext = "mcp-a",
            readOnlyPlanMode = false
        )
        val currentShape = capturePromptCacheShape(
            stableSystemContext = "system-b",
            toolsJson = """[{"name":"read_file"},{"name":"shell"}]""",
            compressionContext = "compression-b",
            projectContext = "project-a",
            sessionGoalContext = "goal-a",
            projectSkillsContext = "skills-a",
            mcpToolsContext = null,
            readOnlyPlanMode = true
        )

        val diagnostics = buildPromptCacheDiagnostics(
            previous = UsageSummarySnapshot(
                lastCachePrefixHash = previousShape.prefixHash,
                lastCacheStableSystemHash = previousShape.stableSystemHash,
                lastCacheToolsHash = previousShape.toolsHash,
                lastCacheCompressionHash = previousShape.compressionHash,
                lastCacheProjectContextHash = previousShape.projectContextHash,
                lastCacheSessionGoalHash = previousShape.sessionGoalHash,
                lastCacheProjectSkillsHash = previousShape.projectSkillsHash,
                lastCacheMcpToolsHash = previousShape.mcpToolsHash,
                lastCachePlanModeHash = previousShape.planModeHash
            ),
            current = currentShape
        )

        assertTrue(diagnostics.prefixChanged)
        assertEquals(
            listOf("tools", "compression", "mcp_tools", "plan_mode", "stable_system"),
            diagnostics.prefixChangeReasons
        )
    }
}
