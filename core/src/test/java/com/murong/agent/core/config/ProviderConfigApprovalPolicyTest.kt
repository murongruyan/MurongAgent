package com.murong.agent.core.config

import com.murong.agent.core.tool.ApprovalRiskLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProviderConfigApprovalPolicyTest {

    @Test
    fun evaluateApprovalRequirement_allAuto_requiresFreshApprovalForCriticalTool() {
        val config = ProviderConfig(approvalMode = ToolApprovalMode.ALL_AUTO)

        val decision = config.evaluateApprovalRequirement(
            riskLevel = ApprovalRiskLevel.LOW,
            toolName = "ask_user"
        )

        assertTrue(decision.requiresApproval)
        assertTrue(decision.explanationDetail.contains("重新人工确认"))
    }

    @Test
    fun evaluateApprovalRequirement_allAuto_keepsOrdinaryToolAutoApproved() {
        val config = ProviderConfig(approvalMode = ToolApprovalMode.ALL_AUTO)

        val decision = config.evaluateApprovalRequirement(
            riskLevel = ApprovalRiskLevel.HIGH,
            toolName = "shell"
        )

        assertFalse(decision.requiresApproval)
    }

    @Test
    fun evaluateApprovalRequirement_whitelistAuto_requiresFreshApprovalEvenWhenToolIsEnabled() {
        val config = ProviderConfig(
            approvalMode = ToolApprovalMode.WHITELIST_AUTO,
            enabledBuiltinTools = DEFAULT_ENABLED_BUILTIN_TOOLS + "ask_user"
        )

        val decision = config.evaluateApprovalRequirement(
            riskLevel = ApprovalRiskLevel.LOW,
            toolName = " ask_user "
        )

        assertTrue(decision.requiresApproval)
        assertTrue(decision.explanationLabel.contains("始终审批"))
    }

    @Test
    fun evaluateApprovalRequirement_whitelistAuto_keepsRegularWhitelistedToolAutoApproved() {
        val config = ProviderConfig(
            approvalMode = ToolApprovalMode.WHITELIST_AUTO,
            enabledBuiltinTools = DEFAULT_ENABLED_BUILTIN_TOOLS
        )

        val decision = config.evaluateApprovalRequirement(
            riskLevel = ApprovalRiskLevel.MEDIUM,
            toolName = "web_fetch"
        )

        assertFalse(decision.requiresApproval)
    }

    @Test
    fun evaluateApprovalRequirement_readOnly_allowsTrustedReadOnlyMcpTool() {
        val config = ProviderConfig(approvalMode = ToolApprovalMode.READ_ONLY)

        val decision = config.evaluateApprovalRequirement(
            riskLevel = ApprovalRiskLevel.LOW,
            toolName = "mcp_fetch_docs",
            approvalScopeTokens = setOf("mcp:trusted_read_only", "mcp:trusted_read_only:fetch_docs")
        )

        assertFalse(decision.requiresApproval)
        assertTrue(decision.explanationLabel.contains("只读模式放行"))
    }

    @Test
    fun evaluateApprovalRequirement_whitelistAuto_allowsTrustedReadOnlyMcpToolEvenWithExtraScopeTokens() {
        val config = ProviderConfig(approvalMode = ToolApprovalMode.WHITELIST_AUTO)

        val decision = config.evaluateApprovalRequirement(
            riskLevel = ApprovalRiskLevel.LOW,
            toolName = "mcp_fetch_docs",
            approvalScopeTokens = setOf("mcp:trusted_read_only:fetch_docs")
        )

        assertFalse(decision.requiresApproval)
        assertTrue(decision.explanationLabel.contains("可信只读"))
    }

    @Test
    fun evaluateApprovalRequirement_allAuto_requiresFreshApprovalForLegacyMigrationTool() {
        val config = ProviderConfig(approvalMode = ToolApprovalMode.ALL_AUTO)

        val decision = config.evaluateApprovalRequirement(
            riskLevel = ApprovalRiskLevel.LOW,
            toolName = "migrate_legacy_memories"
        )

        assertTrue(decision.requiresApproval)
        assertTrue(decision.explanationDetail.contains("重新人工确认"))
    }

    @Test
    fun buildEffectiveSystemPrompt_doesNotInlineLegacyGlobalMemoryContent() {
        val prompt = ProviderConfig(
            globalMemories = listOf(
                GlobalMemory(
                    id = "legacy-release",
                    title = "发布约定",
                    content = "先核对版本号，再检查 changelog。"
                )
            )
        ).buildEffectiveSystemPrompt()

        assertTrue(prompt.contains("Legacy Global Memories Detected"))
        assertTrue(prompt.contains("migrate_legacy_memories"))
        assertFalse(prompt.contains("Active Global Memories"))
        assertFalse(prompt.contains("先核对版本号，再检查 changelog。"))
    }

    @Test
    fun defaultConfig_usesBalancedResponsesAndLeanPrompt() {
        val config = ProviderConfig()

        assertEquals(ResponseVerbosity.BALANCED, config.responseVerbosity)
        assertEquals(DEFAULT_SYSTEM_PROMPT, config.systemPrompt)
        assertFalse(config.buildEffectiveSystemPrompt().contains("Default to a detailed"))
    }

    @Test
    fun buildEffectiveSystemPrompt_migratesPersistedLegacyDefault() {
        val prompt = ProviderConfig(systemPrompt = LEGACY_DEFAULT_SYSTEM_PROMPT)
            .buildEffectiveSystemPrompt()

        assertTrue(prompt.startsWith(DEFAULT_SYSTEM_PROMPT))
        assertFalse(prompt.contains("highly communicative style"))
    }

    @Test
    fun withCurrentAgentBehaviorDefaults_migratesLegacyDetailedDefaults() {
        val migrated = ProviderConfig(
            systemPrompt = LEGACY_DEFAULT_SYSTEM_PROMPT,
            responseVerbosity = ResponseVerbosity.DETAILED
        ).withCurrentAgentBehaviorDefaults()

        assertEquals(DEFAULT_SYSTEM_PROMPT, migrated.systemPrompt)
        assertEquals(ResponseVerbosity.BALANCED, migrated.responseVerbosity)
    }

    @Test
    fun withCurrentAgentBehaviorDefaults_preservesCustomDetailedConfiguration() {
        val migrated = ProviderConfig(
            systemPrompt = "Custom prompt",
            responseVerbosity = ResponseVerbosity.DETAILED
        ).withCurrentAgentBehaviorDefaults()

        assertEquals("Custom prompt", migrated.systemPrompt)
        assertEquals(ResponseVerbosity.DETAILED, migrated.responseVerbosity)
    }

    @Test
    fun buildEffectiveSystemPrompt_preservesCustomPrompt() {
        val prompt = ProviderConfig(systemPrompt = "My deliberately custom system prompt.")
            .buildEffectiveSystemPrompt()

        assertTrue(prompt.startsWith("My deliberately custom system prompt."))
    }
}
