package com.murong.agent.core.config

import com.murong.agent.core.loop.PersistedRepoScopedProjectConfig
import com.murong.agent.core.loop.PersistedSession

data class SessionProjectConfig(
    val projectRules: List<GlobalRule> = emptyList(),
    val projectMemories: List<GlobalMemory> = emptyList(),
    val projectSkills: List<GlobalSkill> = emptyList(),
    val projectToolPreferences: ProjectToolPreferences? = null
)

internal fun buildSessionProjectConfig(
    projectRules: List<GlobalRule> = emptyList(),
    projectMemories: List<GlobalMemory> = emptyList(),
    projectSkills: List<GlobalSkill> = emptyList(),
    projectToolPreferences: ProjectToolPreferences? = null
): SessionProjectConfig {
    return SessionProjectConfig(
        projectRules = projectRules,
        projectMemories = projectMemories,
        projectSkills = projectSkills,
        projectToolPreferences = projectToolPreferences
    )
}

internal fun PersistedSession.toLegacySessionProjectConfig(): SessionProjectConfig {
    return buildSessionProjectConfig(
        projectRules = projectRules,
        projectMemories = projectMemories,
        projectSkills = projectSkills,
        projectToolPreferences = projectToolPreferences
    )
}

internal fun PersistedRepoScopedProjectConfig.toSessionProjectConfig(): SessionProjectConfig {
    return buildSessionProjectConfig(
        projectRules = projectRules,
        projectMemories = projectMemories,
        projectSkills = projectSkills,
        projectToolPreferences = projectToolPreferences
    )
}

internal fun Map<String, PersistedRepoScopedProjectConfig>.toSessionProjectConfigMap(): Map<String, SessionProjectConfig> {
    return mapValues { (_, config) -> config.toSessionProjectConfig() }
}

internal fun SessionProjectConfig.toPersistedRepoScopedProjectConfig(): PersistedRepoScopedProjectConfig {
    return PersistedRepoScopedProjectConfig(
        projectRules = projectRules,
        projectMemories = projectMemories,
        projectSkills = projectSkills,
        projectToolPreferences = projectToolPreferences
    )
}

internal fun Map<String, SessionProjectConfig>.toPersistedRepoScopedProjectConfigMap(): Map<String, PersistedRepoScopedProjectConfig> {
    return mapValues { (_, config) -> config.toPersistedRepoScopedProjectConfig() }
}

internal data class PersistedSessionProjectConfigSeed(
    val legacyProjectConfig: SessionProjectConfig,
    val repoScopedConfigs: Map<String, SessionProjectConfig>
)

internal data class PersistedSessionProjectConfigProjection(
    val legacyProjectConfig: SessionProjectConfig,
    val repoScopedConfigs: Map<String, PersistedRepoScopedProjectConfig>
)

internal fun PersistedSession.toPersistedSessionProjectConfigSeed(): PersistedSessionProjectConfigSeed {
    return PersistedSessionProjectConfigSeed(
        legacyProjectConfig = toLegacySessionProjectConfig(),
        repoScopedConfigs = repoScopedConfigs.toSessionProjectConfigMap()
    )
}

internal fun PersistedProjectConfigSnapshot.toPersistedSessionProjectConfigProjection(): PersistedSessionProjectConfigProjection {
    return PersistedSessionProjectConfigProjection(
        legacyProjectConfig = legacyProjectConfig,
        repoScopedConfigs = repoScopedConfigs.toPersistedRepoScopedProjectConfigMap()
    )
}

internal data class ProjectConfigUpdateResult(
    val activeProjectConfig: SessionProjectConfig,
    val repoScopedConfigs: Map<String, SessionProjectConfig>
)

internal data class ActiveProjectScopeResolution(
    val activeScopePath: String?,
    val activeProjectConfig: SessionProjectConfig
)

internal data class PersistedProjectConfigSnapshot(
    val legacyProjectConfig: SessionProjectConfig,
    val repoScopedConfigs: Map<String, SessionProjectConfig>
)

internal data class RestoredProjectConfigSnapshot(
    val repoScopedConfigs: Map<String, SessionProjectConfig>,
    val activeProjectScope: ActiveProjectScopeResolution
)

internal data class ResolvedSessionConfig(
    val effectiveProviderConfig: ProviderConfig,
    val effectiveProjectRules: List<GlobalRule>,
    val effectiveProjectMemories: List<GlobalMemory>,
    val effectiveProjectSkills: List<GlobalSkill>,
    val effectiveProjectToolPreferences: ProjectToolPreferences?
)

fun resolveEffectiveProviderConfig(
    globalConfig: ProviderConfig,
    projectToolPreferences: ProjectToolPreferences? = null
): ProviderConfig {
    return resolveSessionConfig(
        globalConfig = globalConfig,
        projectToolPreferences = projectToolPreferences
    ).effectiveProviderConfig
}

internal fun resolveProjectScopeConfig(
    scopePath: String?,
    repoScopedConfigs: Map<String, SessionProjectConfig>,
    fallbackProjectConfig: SessionProjectConfig = SessionProjectConfig()
): SessionProjectConfig {
    return scopePath?.let(repoScopedConfigs::get) ?: fallbackProjectConfig
}

internal fun persistProjectScopeConfig(
    scopePath: String?,
    projectConfig: SessionProjectConfig,
    repoScopedConfigs: Map<String, SessionProjectConfig>
): Map<String, SessionProjectConfig> {
    if (scopePath == null) return repoScopedConfigs
    return repoScopedConfigs + (scopePath to projectConfig)
}

internal fun applyProjectConfigUpdate(
    scopePath: String?,
    workspaceScopePath: String?,
    currentProjectConfig: SessionProjectConfig,
    repoScopedConfigs: Map<String, SessionProjectConfig>,
    transform: (SessionProjectConfig) -> SessionProjectConfig
): ProjectConfigUpdateResult {
    val updatedScopedConfigs = if (scopePath != null) {
        persistProjectScopeConfig(
            scopePath = scopePath,
            projectConfig = transform(
                resolveProjectScopeConfig(
                    scopePath = scopePath,
                    repoScopedConfigs = repoScopedConfigs,
                    fallbackProjectConfig = currentProjectConfig
                )
            ),
            repoScopedConfigs = repoScopedConfigs
        )
    } else {
        repoScopedConfigs
    }
    val shouldUpdateLegacyFields = scopePath == null || scopePath == workspaceScopePath
    return ProjectConfigUpdateResult(
        activeProjectConfig = if (shouldUpdateLegacyFields) {
            transform(currentProjectConfig)
        } else {
            currentProjectConfig
        },
        repoScopedConfigs = updatedScopedConfigs
    )
}

internal fun resolveActiveProjectScope(
    activeScopePath: String?,
    repoScopedConfigs: Map<String, SessionProjectConfig>,
    fallbackProjectConfig: SessionProjectConfig = SessionProjectConfig()
): ActiveProjectScopeResolution {
    return ActiveProjectScopeResolution(
        activeScopePath = activeScopePath,
        activeProjectConfig = resolveProjectScopeConfig(
            scopePath = activeScopePath,
            repoScopedConfigs = repoScopedConfigs,
            fallbackProjectConfig = fallbackProjectConfig
        )
    )
}

internal fun preparePersistedProjectConfig(
    activeScopePath: String?,
    currentProjectConfig: SessionProjectConfig,
    repoScopedConfigs: Map<String, SessionProjectConfig>
): PersistedProjectConfigSnapshot {
    val persistedRepoScopedConfigs = persistProjectScopeConfig(
        scopePath = activeScopePath,
        projectConfig = currentProjectConfig,
        repoScopedConfigs = repoScopedConfigs
    )
    return PersistedProjectConfigSnapshot(
        legacyProjectConfig = resolveProjectScopeConfig(
            scopePath = activeScopePath,
            repoScopedConfigs = persistedRepoScopedConfigs,
            fallbackProjectConfig = currentProjectConfig
        ),
        repoScopedConfigs = persistedRepoScopedConfigs
    )
}

internal fun restorePersistedProjectConfig(
    workspaceScopePath: String?,
    activeScopePath: String?,
    legacyProjectConfig: SessionProjectConfig,
    repoScopedConfigs: Map<String, SessionProjectConfig>
): RestoredProjectConfigSnapshot {
    val restoredRepoScopedConfigs = if (workspaceScopePath != null && !repoScopedConfigs.containsKey(workspaceScopePath)) {
        persistProjectScopeConfig(
            scopePath = workspaceScopePath,
            projectConfig = legacyProjectConfig,
            repoScopedConfigs = repoScopedConfigs
        )
    } else {
        repoScopedConfigs
    }
    return RestoredProjectConfigSnapshot(
        repoScopedConfigs = restoredRepoScopedConfigs,
        activeProjectScope = resolveActiveProjectScope(
            activeScopePath = activeScopePath,
            repoScopedConfigs = restoredRepoScopedConfigs,
            fallbackProjectConfig = legacyProjectConfig
        )
    )
}

internal fun resolveSessionConfig(
    globalConfig: ProviderConfig,
    projectConfig: SessionProjectConfig = SessionProjectConfig()
): ResolvedSessionConfig {
    val effectiveProviderConfig = globalConfig.applyProjectToolPreferences(projectConfig.projectToolPreferences)
    return ResolvedSessionConfig(
        effectiveProviderConfig = effectiveProviderConfig,
        effectiveProjectRules = projectConfig.projectRules,
        effectiveProjectMemories = projectConfig.projectMemories,
        effectiveProjectSkills = projectConfig.projectSkills,
        effectiveProjectToolPreferences = effectiveProviderConfig.projectToolPreferences
    )
}

internal fun resolveSessionConfig(
    globalConfig: ProviderConfig,
    projectToolPreferences: ProjectToolPreferences? = null,
    projectRules: List<GlobalRule> = emptyList(),
    projectMemories: List<GlobalMemory> = emptyList(),
    projectSkills: List<GlobalSkill> = emptyList()
): ResolvedSessionConfig {
    return resolveSessionConfig(
        globalConfig = globalConfig,
        projectConfig = buildSessionProjectConfig(
            projectRules = projectRules,
            projectMemories = projectMemories,
            projectSkills = projectSkills,
            projectToolPreferences = projectToolPreferences
        )
    )
}
