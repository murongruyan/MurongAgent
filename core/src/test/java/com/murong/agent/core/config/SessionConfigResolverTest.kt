package com.murong.agent.core.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SessionConfigResolverTest {

    @Test
    fun buildSessionProjectConfig_capturesAllProjectFields() {
        val config = buildSessionProjectConfig(
            projectRules = listOf(
                GlobalRule(
                    id = "rule-1",
                    title = "Rule",
                    content = "Use tests."
                )
            ),
            projectMemories = listOf(
                GlobalMemory(
                    id = "memory-1",
                    title = "Memory",
                    content = "Remember release flow."
                )
            ),
            projectSkills = listOf(
                GlobalSkill(
                    id = "skill-1",
                    title = "Skill",
                    content = "Run checks."
                )
            ),
            projectToolPreferences = ProjectToolPreferences(
                approvalMode = ToolApprovalMode.WHITELIST_AUTO
            )
        )

        assertEquals("rule-1", config.projectRules.single().id)
        assertEquals("memory-1", config.projectMemories.single().id)
        assertEquals("skill-1", config.projectSkills.single().id)
        assertEquals(ToolApprovalMode.WHITELIST_AUTO, config.projectToolPreferences?.approvalMode)
    }

    @Test
    fun persistedProjectConfigProjection_roundTripsLegacyAndScopedConfigs() {
        val session = com.murong.agent.core.loop.PersistedSession(
            id = "session-1",
            title = "Test",
            createdAt = 1L,
            updatedAt = 2L,
            providerId = "deepseek",
            modelName = "model",
            messages = listOf(
                com.murong.agent.core.loop.PersistedMessage(
                    id = 1L,
                    role = "user",
                    content = "hi"
                )
            ),
            projectRules = listOf(
                GlobalRule(
                    id = "legacy-rule",
                    title = "Legacy Rule",
                    content = "Use legacy."
                )
            ),
            projectToolPreferences = ProjectToolPreferences(
                approvalMode = ToolApprovalMode.WHITELIST_AUTO
            ),
            repoScopedConfigs = mapOf(
                "/workspace/repo" to com.murong.agent.core.loop.PersistedRepoScopedProjectConfig(
                    projectMemories = listOf(
                        GlobalMemory(
                            id = "memory-1",
                            title = "Scoped Memory",
                            content = "Use scoped."
                        )
                    )
                )
            )
        )

        val legacy = session.toLegacySessionProjectConfig()
        val scoped = session.repoScopedConfigs.toSessionProjectConfigMap()
        val persisted = scoped.toPersistedRepoScopedProjectConfigMap()

        assertEquals("legacy-rule", legacy.projectRules.single().id)
        assertEquals(ToolApprovalMode.WHITELIST_AUTO, legacy.projectToolPreferences?.approvalMode)
        assertEquals("memory-1", scoped.getValue("/workspace/repo").projectMemories.single().id)
        assertEquals("memory-1", persisted.getValue("/workspace/repo").projectMemories.single().id)
    }

    @Test
    fun persistedSessionProjectConfigSeedAndProjection_bridgeSessionAndSnapshot() {
        val session = com.murong.agent.core.loop.PersistedSession(
            id = "session-2",
            title = "Test",
            createdAt = 1L,
            updatedAt = 2L,
            providerId = "deepseek",
            modelName = "model",
            messages = listOf(
                com.murong.agent.core.loop.PersistedMessage(
                    id = 1L,
                    role = "user",
                    content = "hi"
                )
            ),
            projectMemories = listOf(
                GlobalMemory(
                    id = "legacy-memory",
                    title = "Legacy Memory",
                    content = "Use legacy."
                )
            ),
            repoScopedConfigs = mapOf(
                "/workspace/repo" to com.murong.agent.core.loop.PersistedRepoScopedProjectConfig(
                    projectSkills = listOf(
                        GlobalSkill(
                            id = "skill-1",
                            title = "Scoped Skill",
                            content = "Use scoped."
                        )
                    )
                )
            )
        )

        val seed = session.toPersistedSessionProjectConfigSeed()
        val projection = PersistedProjectConfigSnapshot(
            legacyProjectConfig = seed.legacyProjectConfig,
            repoScopedConfigs = seed.repoScopedConfigs
        ).toPersistedSessionProjectConfigProjection()

        assertEquals("legacy-memory", seed.legacyProjectConfig.projectMemories.single().id)
        assertEquals("skill-1", seed.repoScopedConfigs.getValue("/workspace/repo").projectSkills.single().id)
        assertEquals("skill-1", projection.repoScopedConfigs.getValue("/workspace/repo").projectSkills.single().id)
    }

    @Test
    fun applyProjectToolPreferences_mergesFallbackModeAndSubagentTemplates() {
        val merged = ProviderConfig(
            projectToolPreferences = ProjectToolPreferences(
                subagentTemplates = listOf(
                    ProjectSubagentTemplate(
                        id = "existing-template",
                        title = "旧模板"
                    )
                )
            )
        ).applyProjectToolPreferences(
            ProjectToolPreferences(
                failureFallbackMode = WorkflowFailureFallbackMode.LOCAL_CLARIFICATION
            )
        )

        assertEquals(
            WorkflowFailureFallbackMode.LOCAL_CLARIFICATION,
            merged.getFailureFallbackMode()
        )
        assertEquals(1, merged.projectToolPreferences?.subagentTemplates?.size)
        assertEquals(
            "existing-template",
            merged.projectToolPreferences?.subagentTemplates?.single()?.id
        )
    }

    @Test
    fun resolveSessionConfig_returnsMergedProviderAndEffectiveProjectState() {
        val resolved = resolveSessionConfig(
            globalConfig = ProviderConfig(
                approvalMode = ToolApprovalMode.ALL_APPROVAL,
                enabledBuiltinTools = listOf("shell")
            ),
            projectToolPreferences = ProjectToolPreferences(
                approvalMode = ToolApprovalMode.WHITELIST_AUTO,
                enabledBuiltinTools = listOf("shell", "memory_search"),
                subagentTemplates = listOf(
                    ProjectSubagentTemplate(
                        id = "project-template",
                        title = "项目模板"
                    )
                )
            ),
            projectRules = listOf(
                GlobalRule(
                    id = "rule-1",
                    title = "项目规则",
                    content = "先跑测试。"
                )
            ),
            projectMemories = listOf(
                GlobalMemory(
                    id = "memory-1",
                    title = "项目记忆",
                    content = "发版前检查 changelog。"
                )
            ),
            projectSkills = listOf(
                GlobalSkill(
                    id = "skill-1",
                    title = "项目技能",
                    content = "执行发布检查。"
                )
            )
        )

        assertEquals(ToolApprovalMode.WHITELIST_AUTO, resolved.effectiveProviderConfig.approvalMode)
        assertTrue(resolved.effectiveProviderConfig.enabledBuiltinTools.contains("memory_search"))
        assertEquals(1, resolved.effectiveProjectRules.size)
        assertEquals(1, resolved.effectiveProjectMemories.size)
        assertEquals(1, resolved.effectiveProjectSkills.size)
        assertNotNull(resolved.effectiveProjectToolPreferences)
        assertEquals(
            "project-template",
            resolved.effectiveProjectToolPreferences.subagentTemplates?.single()?.id
        )
    }

    @Test
    fun resolveProjectScopeConfig_prefersScopedConfigOverLegacyFallback() {
        val scoped = resolveProjectScopeConfig(
            scopePath = "/workspace/repo",
            repoScopedConfigs = mapOf(
                "/workspace/repo" to SessionProjectConfig(
                    projectRules = listOf(
                        GlobalRule(
                            id = "repo-rule",
                            title = "Repo Rule",
                            content = "Use repo config."
                        )
                    ),
                    projectToolPreferences = ProjectToolPreferences(
                        approvalMode = ToolApprovalMode.WHITELIST_AUTO
                    )
                )
            ),
            fallbackProjectConfig = SessionProjectConfig(
                projectRules = listOf(
                    GlobalRule(
                        id = "legacy-rule",
                        title = "Legacy Rule",
                        content = "Use legacy config."
                    )
                ),
                projectToolPreferences = ProjectToolPreferences(
                    approvalMode = ToolApprovalMode.ALL_APPROVAL
                )
            )
        )

        assertEquals("repo-rule", scoped.projectRules.single().id)
        assertEquals(ToolApprovalMode.WHITELIST_AUTO, scoped.projectToolPreferences?.approvalMode)
    }

    @Test
    fun persistProjectScopeConfig_writesActiveProjectConfigIntoScopedMap() {
        val persisted = persistProjectScopeConfig(
            scopePath = "/workspace/repo",
            projectConfig = SessionProjectConfig(
                projectMemories = listOf(
                    GlobalMemory(
                        id = "memory-1",
                        title = "Repo Memory",
                        content = "Persist me."
                    )
                )
            ),
            repoScopedConfigs = mapOf(
                "/workspace/other" to SessionProjectConfig(
                    projectMemories = listOf(
                        GlobalMemory(
                            id = "memory-0",
                            title = "Other Memory",
                            content = "Keep me."
                        )
                    )
                )
            )
        )

        assertEquals(2, persisted.size)
        assertEquals("memory-1", persisted.getValue("/workspace/repo").projectMemories.single().id)
        assertEquals("memory-0", persisted.getValue("/workspace/other").projectMemories.single().id)
    }

    @Test
    fun applyProjectConfigUpdate_updatesLegacyAndScopedConfigForWorkspaceScope() {
        val result = applyProjectConfigUpdate(
            scopePath = "/workspace/repo",
            workspaceScopePath = "/workspace/repo",
            currentProjectConfig = SessionProjectConfig(
                projectRules = listOf(
                    GlobalRule(
                        id = "legacy-rule",
                        title = "Legacy Rule",
                        content = "Old"
                    )
                )
            ),
            repoScopedConfigs = emptyMap()
        ) { config ->
            config.copy(
                projectRules = listOf(
                    GlobalRule(
                        id = "updated-rule",
                        title = "Updated Rule",
                        content = "New"
                    )
                )
            )
        }

        assertEquals("updated-rule", result.activeProjectConfig.projectRules.single().id)
        assertEquals("updated-rule", result.repoScopedConfigs.getValue("/workspace/repo").projectRules.single().id)
    }

    @Test
    fun applyProjectConfigUpdate_keepsLegacyConfigWhenUpdatingOtherScopedProject() {
        val result = applyProjectConfigUpdate(
            scopePath = "/workspace/other",
            workspaceScopePath = "/workspace/repo",
            currentProjectConfig = SessionProjectConfig(
                projectToolPreferences = ProjectToolPreferences(
                    approvalMode = ToolApprovalMode.ALL_APPROVAL
                )
            ),
            repoScopedConfigs = emptyMap()
        ) { config ->
            config.copy(
                projectToolPreferences = ProjectToolPreferences(
                    approvalMode = ToolApprovalMode.WHITELIST_AUTO
                )
            )
        }

        assertEquals(ToolApprovalMode.ALL_APPROVAL, result.activeProjectConfig.projectToolPreferences?.approvalMode)
        assertEquals(
            ToolApprovalMode.WHITELIST_AUTO,
            result.repoScopedConfigs.getValue("/workspace/other").projectToolPreferences?.approvalMode
        )
    }

    @Test
    fun resolveActiveProjectScope_prefersRepoScopedConfigForActiveScope() {
        val resolved = resolveActiveProjectScope(
            activeScopePath = "/workspace/repo",
            repoScopedConfigs = mapOf(
                "/workspace/repo" to SessionProjectConfig(
                    projectSkills = listOf(
                        GlobalSkill(
                            id = "skill-1",
                            title = "Repo Skill",
                            content = "Use repo skill."
                        )
                    )
                )
            ),
            fallbackProjectConfig = SessionProjectConfig(
                projectSkills = listOf(
                    GlobalSkill(
                        id = "legacy-skill",
                        title = "Legacy Skill",
                        content = "Use legacy skill."
                    )
                )
            )
        )

        assertEquals("/workspace/repo", resolved.activeScopePath)
        assertEquals("skill-1", resolved.activeProjectConfig.projectSkills.single().id)
    }

    @Test
    fun resolveActiveProjectScope_fallsBackToLegacyProjectConfigWhenScopeMissing() {
        val resolved = resolveActiveProjectScope(
            activeScopePath = "/workspace/missing",
            repoScopedConfigs = emptyMap(),
            fallbackProjectConfig = SessionProjectConfig(
                projectMemories = listOf(
                    GlobalMemory(
                        id = "memory-1",
                        title = "Legacy Memory",
                        content = "Use fallback."
                    )
                )
            )
        )

        assertEquals("/workspace/missing", resolved.activeScopePath)
        assertEquals("memory-1", resolved.activeProjectConfig.projectMemories.single().id)
    }

    @Test
    fun preparePersistedProjectConfig_mirrorsActiveScopeIntoLegacyProjectFields() {
        val persisted = preparePersistedProjectConfig(
            activeScopePath = "/workspace/repo",
            currentProjectConfig = SessionProjectConfig(
                projectRules = listOf(
                    GlobalRule(
                        id = "active-rule",
                        title = "Active Rule",
                        content = "Persist active scope."
                    )
                )
            ),
            repoScopedConfigs = mapOf(
                "/workspace/other" to SessionProjectConfig(
                    projectRules = listOf(
                        GlobalRule(
                            id = "other-rule",
                            title = "Other Rule",
                            content = "Keep other scope."
                        )
                    )
                )
            )
        )

        assertEquals("active-rule", persisted.legacyProjectConfig.projectRules.single().id)
        assertEquals("active-rule", persisted.repoScopedConfigs.getValue("/workspace/repo").projectRules.single().id)
        assertEquals("other-rule", persisted.repoScopedConfigs.getValue("/workspace/other").projectRules.single().id)
    }

    @Test
    fun preparePersistedProjectConfig_keepsCurrentProjectConfigWhenNoActiveScope() {
        val persisted = preparePersistedProjectConfig(
            activeScopePath = null,
            currentProjectConfig = SessionProjectConfig(
                projectToolPreferences = ProjectToolPreferences(
                    approvalMode = ToolApprovalMode.WHITELIST_AUTO
                )
            ),
            repoScopedConfigs = emptyMap()
        )

        assertEquals(ToolApprovalMode.WHITELIST_AUTO, persisted.legacyProjectConfig.projectToolPreferences?.approvalMode)
        assertTrue(persisted.repoScopedConfigs.isEmpty())
    }

    @Test
    fun restorePersistedProjectConfig_injectsLegacyWorkspaceScopeWhenMissing() {
        val restored = restorePersistedProjectConfig(
            workspaceScopePath = "/workspace/repo",
            activeScopePath = "/workspace/repo",
            legacyProjectConfig = SessionProjectConfig(
                projectMemories = listOf(
                    GlobalMemory(
                        id = "legacy-memory",
                        title = "Legacy Memory",
                        content = "Mirror me."
                    )
                )
            ),
            repoScopedConfigs = emptyMap()
        )

        assertEquals("legacy-memory", restored.activeProjectScope.activeProjectConfig.projectMemories.single().id)
        assertEquals("legacy-memory", restored.repoScopedConfigs.getValue("/workspace/repo").projectMemories.single().id)
    }

    @Test
    fun restorePersistedProjectConfig_prefersScopedConfigOverLegacyFallbackForActiveScope() {
        val restored = restorePersistedProjectConfig(
            workspaceScopePath = "/workspace/repo",
            activeScopePath = "/workspace/repo/submodule",
            legacyProjectConfig = SessionProjectConfig(
                projectRules = listOf(
                    GlobalRule(
                        id = "legacy-rule",
                        title = "Legacy Rule",
                        content = "Use legacy."
                    )
                )
            ),
            repoScopedConfigs = mapOf(
                "/workspace/repo/submodule" to SessionProjectConfig(
                    projectRules = listOf(
                        GlobalRule(
                            id = "scoped-rule",
                            title = "Scoped Rule",
                            content = "Use scoped."
                        )
                    )
                )
            )
        )

        assertEquals("scoped-rule", restored.activeProjectScope.activeProjectConfig.projectRules.single().id)
        assertEquals("legacy-rule", restored.repoScopedConfigs.getValue("/workspace/repo").projectRules.single().id)
    }
}
