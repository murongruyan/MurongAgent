package com.murong.agent.lan

import android.content.Context
import com.murong.agent.automation.SavedWorkflowScheduler
import com.murong.agent.core.automation.SavedWorkflowDefinition
import com.murong.agent.core.automation.SavedWorkflowNode
import com.murong.agent.core.automation.SavedWorkflowTemplate
import com.murong.agent.core.automation.validate
import com.murong.agent.core.codex.CodexAppServerClient
import com.murong.agent.core.codex.AndroidCodexAccountManager
import com.murong.agent.core.codex.CodexAccountPoolSettings
import com.murong.agent.core.codex.CodexAccountPoolTransfer
import com.murong.agent.core.codex.CodexAccountTransfer
import com.murong.agent.core.config.AgentBackendKind
import com.murong.agent.core.config.ConfigRepository
import com.murong.agent.core.config.GlobalMemory
import com.murong.agent.core.config.GlobalRule
import com.murong.agent.core.config.GlobalSkill
import com.murong.agent.core.config.ProviderConfig
import com.murong.agent.core.config.RelayConfig
import com.murong.agent.core.config.ResponseVerbosity
import com.murong.agent.core.config.SkillRunAs
import com.murong.agent.core.config.ToolApprovalMode
import com.murong.agent.core.config.ToolPermissionCategory
import com.murong.agent.core.provider.ProviderPresetCatalog
import com.murong.agent.core.provider.ProviderWireFormat
import com.murong.agent.core.mcp.McpConfigSource
import com.murong.agent.core.mcp.McpRegistry
import com.murong.agent.core.mcp.McpServerConfig
import com.murong.agent.core.mcp.McpTransportType
import com.murong.agent.core.loop.ChatSessionManager
import com.murong.agent.core.github.AndroidGitHubAccountManager
import com.murong.agent.core.github.GitHubAccountPoolTransfer
import com.murong.agent.core.github.GitHubAccountTransfer
import com.murong.agent.core.loop.PortableConversationBackupRecord
import com.murong.agent.core.loop.PortableConversationBackupStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

@Singleton
class LanWebCredentialSyncBridge @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val configRepository: ConfigRepository,
    private val codexAppServer: CodexAppServerClient,
    private val codexAccountManager: AndroidCodexAccountManager,
    private val githubAccountManager: AndroidGitHubAccountManager,
    private val mcpRegistry: McpRegistry,
    private val chatSessionManager: ChatSessionManager? = null,
) {
    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true; explicitNulls = false }
    private val importMutex = Mutex()
    private val workflowScheduler by lazy { SavedWorkflowScheduler(context, reconcileInterruptedRuns = false) }

    suspend fun exportBundle(options: LanWebDeviceSyncOptions): LanWebCredentialSyncBundle {
        val config = configRepository.getConfig()
        val providers = if (options.includeProviderCredentials) exportProviders(config) else emptyList()
        val codexPool = if (options.includeCodexLogin) codexAccountManager.exportAccountPool() else null
        val auth = codexPool?.accounts?.firstOrNull { it.id == codexPool.activeAccountId }?.authJson
        val githubPool = if (options.includeGitHubCredentials) {
            githubAccountManager.migrateLegacyAccount(
                token = config.githubToken,
                login = config.githubViewerLogin,
                name = config.githubViewerName,
                avatarUrl = config.githubViewerAvatarUrl,
                apiBaseUrl = config.getGitHubApiBaseUrl(),
            )
            githubAccountManager.exportAccountPool()
        } else {
            null
        }
        val activeGitHub = githubPool?.accounts?.firstOrNull { it.id == githubPool.activeAccountId }
        val sessionPage = exportSessionPage(options)
        return LanWebCredentialSyncBundle(
            sourcePlatform = "android",
            generatedAt = System.currentTimeMillis(),
            activeProviderId = if (config.activeAgentBackend == AgentBackendKind.CODEX_CHATGPT) {
                "codex"
            } else {
                config.activeProviderId.takeUnless { it == "murong-local" }
            },
            activeProfileId = config.getActiveRelayId(config.activeProviderId)
                .takeUnless { config.activeProviderId == "murong-local" },
            providers = providers,
            codexAuthJson = auth,
            codexAccounts = codexPool?.accounts.orEmpty().map { account ->
                LanWebSyncedCodexAccount(
                    id = account.id,
                    label = account.label,
                    email = account.email,
                    planType = account.planType,
                    enabled = account.enabled,
                    authJson = account.authJson,
                    lastUsedAt = account.lastUsedAt,
                )
            },
            activeCodexAccountId = codexPool?.activeAccountId.orEmpty(),
            codexAccountSettings = codexPool?.settings?.let { settings ->
                LanWebSyncedCodexAccountSettings(
                    autoSwitch = settings.autoSwitch,
                    reservePercent = settings.reservePercent.toDouble(),
                    cooldownMinutes = settings.cooldownMinutes,
                )
            },
            github = if (options.includeGitHubCredentials) {
                LanWebSyncedGitHubCredential(
                    apiBaseUrl = activeGitHub?.apiBaseUrl ?: config.getGitHubApiBaseUrl(),
                    token = activeGitHub?.token,
                    viewerLogin = activeGitHub?.login.orEmpty(),
                )
            } else {
                null
            },
            githubAccounts = githubPool?.accounts.orEmpty().map { account ->
                LanWebSyncedGitHubAccount(
                    id = account.id,
                    label = account.label,
                    login = account.login,
                    name = account.name,
                    avatarUrl = account.avatarUrl,
                    apiBaseUrl = account.apiBaseUrl,
                    token = account.token,
                    lastUsedAt = account.lastUsedAt,
                )
            },
            activeGitHubAccountId = githubPool?.activeAccountId.orEmpty(),
            agentSettings = if (options.includeAgentSettings) exportAgentSettings(config) else null,
            mediaSettings = if (options.includeAgentSettings) exportMediaSettings(config) else null,
            mediaCredentials = if (options.includeAgentSettings && options.includeProviderCredentials) {
                exportMediaCredentials(config)
            } else {
                null
            },
            knowledge = if (options.includeKnowledge) exportKnowledge(config) else null,
            mcpServers = if (options.includeMcp) exportMcpServers(options.includeMcpCredentials) else emptyList(),
            mcpCredentialsIncluded = options.includeMcp && options.includeMcpCredentials,
            savedWorkflows = if (options.includeSavedWorkflows) exportSavedWorkflows() else emptyList(),
            sessions = sessionPage.sessions,
            sessionNextCursor = sessionPage.nextCursor,
        )
    }

    private fun exportSessionPage(options: LanWebDeviceSyncOptions): SessionPage {
        require(options.sessionCursor >= 0) { "聊天记录分页游标无效" }
        if (!options.includeSessions) {
            require(options.sessionCursor == 0) { "未同步聊天记录时不能指定分页游标" }
            return SessionPage(emptyList(), null)
        }
        val records = requireNotNull(chatSessionManager) { "聊天记录同步服务尚未初始化" }
            .exportPortableDeviceSyncSessions()
            .sortedBy(PortableConversationBackupRecord::sourceSessionId)
        return buildDeviceSyncSessionPage(records, options.sessionCursor, json)
    }

    /** Builds the non-secret, cross-platform subset embedded in complete backup v2 archives. */
    suspend fun exportPortableBackupBundle(generatedAt: Long = System.currentTimeMillis()): LanWebCredentialSyncBundle {
        require(generatedAt > 0L) { "跨端备份时间无效" }
        val config = configRepository.getConfig()
        val bundle = LanWebCredentialSyncBundle(
            sourcePlatform = "android",
            generatedAt = generatedAt,
            activeProviderId = if (config.activeAgentBackend == AgentBackendKind.CODEX_CHATGPT) {
                "codex"
            } else {
                config.activeProviderId.takeUnless { it == "murong-local" }
            },
            activeProfileId = config.getActiveRelayId(config.activeProviderId)
                .takeUnless { config.activeProviderId == "murong-local" },
            providers = exportProviders(config).map { it.copy(apiKey = null) },
            codexAuthJson = null,
            github = LanWebSyncedGitHubCredential(
                apiBaseUrl = config.getGitHubApiBaseUrl(),
                token = null,
                viewerLogin = ""
            ),
            agentSettings = exportAgentSettings(config),
            mediaSettings = exportMediaSettings(config),
            knowledge = exportKnowledge(config),
            mcpServers = exportMcpServers(includeCredentials = false),
            mcpCredentialsIncluded = false,
            savedWorkflows = exportSavedWorkflows()
        )
        validatePortableBackupBundle(bundle)
        return bundle
    }

    suspend fun importBundle(bundle: LanWebCredentialSyncBundle): LanWebCredentialSyncResult =
        importMutex.withLock { importBundleLocked(bundle) }

    /** Imports an old backup without weakening the five-minute freshness check used by live device sync. */
    suspend fun importPortableBackupBundle(bundle: LanWebCredentialSyncBundle): LanWebCredentialSyncResult =
        importMutex.withLock {
            validatePortableBackupBundle(bundle)
            importBundleLocked(bundle.copy(generatedAt = System.currentTimeMillis()))
        }

    fun validatePortableBackupBundle(bundle: LanWebCredentialSyncBundle) {
        require(bundle.generatedAt > 0L) { "跨端备份状态时间无效" }
        require(bundle.codexAuthJson == null) { "跨端备份不得包含 Codex 登录" }
        require(bundle.codexAccounts.isEmpty()) { "跨端备份不得包含 Codex 账号池" }
        require(bundle.providers.all { it.apiKey == null }) { "跨端备份不得包含 API Key" }
        require(bundle.mediaCredentials == null) { "跨端备份不得包含看图或生图 Key" }
        require(bundle.github?.token == null && bundle.github?.viewerLogin.orEmpty().isBlank()) {
            "跨端备份不得包含 GitHub 登录状态"
        }
        require(bundle.githubAccounts.isEmpty()) { "跨端备份不得包含 GitHub 账号池" }
        require(!bundle.mcpCredentialsIncluded) { "跨端备份不得声明 MCP 凭据" }
        require(bundle.mcpServers.all { it.environment.isEmpty() && it.headers.isEmpty() }) {
            "跨端备份不得包含 MCP 环境变量或请求头"
        }
        validateBundle(bundle.copy(generatedAt = System.currentTimeMillis()))
    }

    private suspend fun importBundleLocked(bundle: LanWebCredentialSyncBundle): LanWebCredentialSyncResult {
        validateBundle(bundle)
        val previousConfig = configRepository.getConfig()
        val previousMcp = mcpRegistry.loadConfigs()
        val previousWorkflows = workflowScheduler.list()
        val previousCodexPool = bundle.codexAccounts.takeIf { it.isNotEmpty() }?.let { codexAccountManager.exportAccountPool() }
        val previousGitHubPool = bundle.githubAccounts.takeIf { it.isNotEmpty() }?.let { githubAccountManager.exportAccountPool() }
        val introducedRelayIds = mutableMapOf<String, MutableSet<String>>()
        var importedProviders = 0
        var importedApiKeys = 0
        var importedGitHubToken = false
        var importedCodexAccounts = 0
        var importedGitHubAccounts = 0
        var importedCodex = false
        var accountEmail: String? = null
        var importedSettings = false
        var importedRules = 0
        var importedMemories = 0
        var importedSkills = 0
        var importedMcpServers = 0
        var importedWorkflows = 0
        var disabledMcpServers = 0
        var skippedWorkflows = 0
        var configChanged = false
        var mcpChanged = false
        var workflowsChanged = false
        var codexPoolChanged = false
        var githubPoolChanged = false
        try {
            var updated = previousConfig
            if (bundle.providers.isNotEmpty()) {
                SUPPORTED_PROVIDERS.forEach { providerId ->
                    val incoming = bundle.providers.filter { normalizeProviderId(it.providerId) == providerId }
                    if (incoming.isEmpty()) return@forEach
                    val existing = updated.getRelayConfigs(providerId)
                    val merged = existing.toMutableList()
                    incoming.forEach { profile ->
                        val index = merged.indexOfFirst { it.id == profile.profileId }
                        val previous = merged.getOrNull(index)
                        if (previous == null) {
                            introducedRelayIds.getOrPut(providerId, ::mutableSetOf) += profile.profileId
                        }
                        val relay = RelayConfig(
                            id = profile.profileId,
                            name = profile.name,
                            baseUrl = profile.baseUrl,
                            apiKey = profile.apiKey?.takeIf { it.isNotBlank() } ?: previous?.apiKey.orEmpty(),
                            model = profile.model,
                            reasoningEffort = profile.reasoningEffort.ifBlank {
                                previous?.reasoningEffort ?: defaultRelayReasoningEffort(providerId)
                            },
                            modelPreset = previous?.modelPreset ?: "custom",
                            autoModelSelection = previous?.autoModelSelection ?: false,
                            autoReasoningEffort = previous?.autoReasoningEffort ?: false,
                            promptPricePer1M = previous?.promptPricePer1M ?: 0.0,
                            completionPricePer1M = previous?.completionPricePer1M ?: 0.0,
                            balanceAmount = previous?.balanceAmount ?: 0.0,
                            balanceCurrency = previous?.balanceCurrency ?: "USD",
                            balanceSyncedAt = previous?.balanceSyncedAt,
                            balanceApiPath = previous?.balanceApiPath.orEmpty(),
                            contextWindowTokens = profile.contextWindowTokens ?: previous?.contextWindowTokens,
                            kind = previous?.kind ?: com.murong.agent.core.config.RelayKind.CUSTOM,
                            apiFormat = providerWireFormatFromSync(profile.apiMode, providerId, previous?.apiFormat),
                        )
                        if (index >= 0) merged[index] = relay else merged += relay
                        importedProviders++
                        if (!profile.apiKey.isNullOrBlank()) importedApiKeys++
                    }
                    val active = if (
                        normalizeProviderId(bundle.activeProviderId.orEmpty()) == providerId &&
                        incoming.any { it.profileId == bundle.activeProfileId }
                    ) {
                        bundle.activeProfileId.orEmpty()
                    } else {
                        updated.getActiveRelayId(providerId) ?: merged.first().id
                    }
                    updated = updated.withRelayConfigs(providerId, merged.take(MAX_PROFILES_PER_PROVIDER), active)
                }
                val activeProvider = normalizeProviderId(bundle.activeProviderId.orEmpty())
                updated = when {
                    bundle.activeProviderId == "codex" -> updated.copy(activeAgentBackend = AgentBackendKind.CODEX_CHATGPT)
                    activeProvider in SUPPORTED_PROVIDERS -> updated.copy(
                        activeAgentBackend = AgentBackendKind.PROVIDER_API,
                        activeProviderId = activeProvider,
                    )
                    else -> updated
                }
                configChanged = true
            }
            bundle.agentSettings?.let { settings ->
                updated = updated.copy(
                    approvalMode = approvalModeFromWire(settings.approvalMode),
                    systemPrompt = settings.systemPrompt.trim(),
                    responseVerbosity = ResponseVerbosity.valueOf(settings.responseVerbosity.uppercase()),
                    temperature = settings.temperature ?: updated.temperature,
                    maxTokens = settings.maxTokens ?: updated.maxTokens,
                    enableMultimodalMessages = settings.enableMultimodalMessages ?: updated.enableMultimodalMessages,
                    plannerProfileEnabled = settings.plannerProfileEnabled ?: updated.plannerProfileEnabled,
                    plannerModel = settings.plannerModel ?: updated.plannerModel,
                    plannerReasoningEffort = settings.plannerReasoningEffort ?: updated.plannerReasoningEffort,
                    subagentDefaultProfileEnabled = settings.subagentDefaultProfileEnabled ?: updated.subagentDefaultProfileEnabled,
                    subagentDefaultModel = settings.subagentDefaultModel ?: updated.subagentDefaultModel,
                    subagentDefaultReasoningEffort = settings.subagentDefaultReasoningEffort ?: updated.subagentDefaultReasoningEffort,
                )
                importedSettings = true
                configChanged = true
            }
            bundle.mediaSettings?.let { settings ->
                updated = updated.copy(
                    visionRoutingEnabled = settings.visionRoutingEnabled,
                    visionProviderId = settings.visionProviderId.trim(),
                    visionRelayId = settings.visionProfileId.trim(),
                    visionModel = settings.visionModel.trim(),
                    visionCustomBaseUrl = settings.visionCustomBaseUrl.trim(),
                    imageGenerationProviderId = settings.imageGenerationProviderId.trim(),
                    imageGenerationRelayId = settings.imageGenerationProfileId.trim(),
                    imageGenerationModel = settings.imageGenerationModel.trim(),
                    imageGenerationCustomBaseUrl = settings.imageGenerationCustomBaseUrl.trim(),
                    imageGenerationSize = settings.imageGenerationSize.trim(),
                    imageGenerationQuality = settings.imageGenerationQuality.trim(),
                    imageGenerationFormat = settings.imageGenerationFormat.trim(),
                    imageGenerationCompression = settings.imageGenerationCompression,
                    imageGenerationPartialImages = settings.imageGenerationPartialImages,
                    imageUpscaleBaseUrl = settings.imageUpscaleBaseUrl.trim(),
                    imageUpscaleModel = settings.imageUpscaleModel.trim(),
                    imageUpscaleScale = settings.imageUpscaleScale,
                )
                importedSettings = true
                configChanged = true
            }
            bundle.mediaCredentials?.let { credentials ->
                updated = updated.copy(
                    visionCustomApiKey = credentials.visionCustomApiKey?.trim()?.takeIf { it.isNotEmpty() }
                        ?: updated.visionCustomApiKey,
                    imageGenerationCustomApiKey = credentials.imageGenerationCustomApiKey?.trim()?.takeIf { it.isNotEmpty() }
                        ?: updated.imageGenerationCustomApiKey,
                    imageUpscaleApiKey = credentials.imageUpscaleApiKey?.trim()?.takeIf { it.isNotEmpty() }
                        ?: updated.imageUpscaleApiKey,
                )
                configChanged = true
            }
            bundle.github?.takeIf { bundle.githubAccounts.isEmpty() }?.let { github ->
                val merged = mergeSyncedGitHubCredential(updated, github)
                updated = merged.first
                importedGitHubToken = merged.second
                configChanged = true
            }
            bundle.knowledge?.let { knowledge ->
                updated = updated.copy(
                    globalRules = mergeRules(updated.globalRules, knowledge.rules),
                    globalMemories = mergeMemories(updated.globalMemories, knowledge.memories),
                    globalSkills = mergeSkills(updated.globalSkills, knowledge.skills),
                )
                importedRules = knowledge.rules.size
                importedMemories = knowledge.memories.size
                importedSkills = knowledge.skills.size
                configChanged = true
            }
            if (configChanged) configRepository.saveConfig(updated)

            if (bundle.mcpServers.isNotEmpty()) {
                val imported = importMcpServers(previousMcp, bundle.mcpServers, bundle.mcpCredentialsIncluded)
                mcpRegistry.saveConfigs(imported.first)
                importedMcpServers = bundle.mcpServers.size
                disabledMcpServers = imported.second
                mcpChanged = true
            }
            if (bundle.savedWorkflows.isNotEmpty()) {
                val imported = importSavedWorkflows(previousWorkflows, bundle.savedWorkflows)
                workflowScheduler.restoreAll(imported.first)
                importedWorkflows = imported.second
                skippedWorkflows = imported.third
                workflowsChanged = true
            }

            bundle.codexAuthJson?.takeIf { bundle.codexAccounts.isEmpty() }?.let { auth ->
                accountEmail = replaceCodexAuthAndVerify(auth)
                importedCodex = true
            }
            val sessionMerge = if (bundle.sessions.isNotEmpty()) {
                requireNotNull(chatSessionManager) { "聊天记录同步服务尚未初始化" }
                    .importPortableDeviceSyncSessions(
                        sourcePlatform = bundle.sourcePlatform,
                        records = bundle.sessions.map {
                            PortableConversationBackupRecord(
                                sourceSessionId = it.sourceSessionId,
                                portableJson = it.document.toString(),
                                originPlatform = it.originPlatform,
                                originSessionId = it.originSessionId,
                            )
                        }
                    )
            } else {
                null
            }
            if (bundle.codexAccounts.isNotEmpty()) {
                codexAppServer.stop()
                importedCodexAccounts = codexAccountManager.importAccountPool(
                    CodexAccountPoolTransfer(
                        activeAccountId = bundle.activeCodexAccountId,
                        settings = bundle.codexAccountSettings?.let { settings ->
                            CodexAccountPoolSettings(
                                autoSwitch = settings.autoSwitch,
                                reservePercent = settings.reservePercent.roundToInt(),
                                cooldownMinutes = settings.cooldownMinutes,
                            )
                        } ?: codexAccountManager.settings(),
                        accounts = bundle.codexAccounts.map { account ->
                            CodexAccountTransfer(
                                id = account.id,
                                label = account.label,
                                email = account.email,
                                planType = account.planType,
                                enabled = account.enabled,
                                authJson = account.authJson,
                                lastUsedAt = account.lastUsedAt,
                            )
                        },
                    ),
                )
                codexPoolChanged = true
                importedCodex = bundle.codexAccounts.any { !it.authJson.isNullOrBlank() }
                accountEmail = codexAccountManager.state.value.accounts.firstOrNull { it.active }?.email
            }
            if (bundle.githubAccounts.isNotEmpty()) {
                importedGitHubAccounts = githubAccountManager.importAccountPool(
                    GitHubAccountPoolTransfer(
                        activeAccountId = bundle.activeGitHubAccountId,
                        accounts = bundle.githubAccounts.map { account ->
                            GitHubAccountTransfer(
                                id = account.id,
                                label = account.label,
                                login = account.login,
                                name = account.name,
                                avatarUrl = account.avatarUrl,
                                apiBaseUrl = account.apiBaseUrl,
                                token = account.token,
                                lastUsedAt = account.lastUsedAt,
                            )
                        },
                    ),
                )
                githubPoolChanged = true
                val active = githubAccountManager.activeCredentials()
                if (active != null) {
                    val current = configRepository.getConfig()
                    configRepository.saveConfig(
                        current.copy(
                            githubToken = active.token,
                            githubApiBaseUrl = active.apiBaseUrl,
                            githubBackendSessionToken = active.backendSessionToken,
                            githubViewerLogin = active.login,
                            githubViewerName = active.name,
                            githubViewerAvatarUrl = active.avatarUrl,
                        ),
                    )
                    configChanged = true
                }
                importedGitHubToken = bundle.githubAccounts.any { !it.token.isNullOrBlank() }
            }
            return LanWebCredentialSyncResult(
                importedSessions = sessionMerge?.importedSessions ?: 0,
                conflictSessions = sessionMerge?.conflictCopies ?: 0,
                skippedSessions = sessionMerge?.skippedSessions ?: 0,
                importedProviders = importedProviders,
                importedApiKeys = importedApiKeys,
                importedCodexLogin = importedCodex,
                importedCodexAccounts = importedCodexAccounts,
                importedGitHubToken = importedGitHubToken,
                importedGitHubAccounts = importedGitHubAccounts,
                accountEmail = accountEmail,
                importedSettings = importedSettings,
                importedRules = importedRules,
                importedMemories = importedMemories,
                importedSkills = importedSkills,
                importedMcpServers = importedMcpServers,
                importedWorkflows = importedWorkflows,
                disabledMcpServers = disabledMcpServers,
                skippedWorkflows = skippedWorkflows,
            )
        } catch (error: Throwable) {
            val rollbackErrors = mutableListOf<Throwable>()
            if (githubPoolChanged && previousGitHubPool != null) {
                runCatching { githubAccountManager.importAccountPool(previousGitHubPool, replaceExisting = true) }
                    .exceptionOrNull()?.let(rollbackErrors::add)
            }
            if (codexPoolChanged && previousCodexPool != null) {
                runCatching {
                    codexAppServer.stop()
                    codexAccountManager.importAccountPool(previousCodexPool, replaceExisting = true)
                }.exceptionOrNull()?.let(rollbackErrors::add)
            }
            if (configChanged) {
                runCatching { configRepository.saveConfig(previousConfig) }.exceptionOrNull()?.let(rollbackErrors::add)
            }
            configRepository.clearRelayApiKeysForCredentialRollback(introducedRelayIds)
            if (mcpChanged) runCatching { mcpRegistry.saveConfigs(previousMcp) }.exceptionOrNull()?.let(rollbackErrors::add)
            if (workflowsChanged) {
                runCatching { workflowScheduler.restoreAll(previousWorkflows) }.exceptionOrNull()?.let(rollbackErrors::add)
            }
            if (rollbackErrors.isNotEmpty()) {
                error.addSuppressed(IllegalStateException("设备同步失败，且回滚出现 ${rollbackErrors.size} 个错误"))
                rollbackErrors.forEach(error::addSuppressed)
            }
            throw error
        }
    }

    private fun exportProviders(config: ProviderConfig): List<LanWebSyncedProviderCredential> =
        SUPPORTED_PROVIDERS.flatMap { providerId ->
            config.getRelayConfigs(providerId).take(MAX_PROFILES_PER_PROVIDER).map { relay ->
                LanWebSyncedProviderCredential(
                    profileId = relay.id,
                    providerId = providerId,
                    name = relay.name,
                    baseUrl = relay.baseUrl,
                    model = relay.model,
                    reasoningEffort = relay.reasoningEffort,
                    apiMode = providerWireFormatToSync(providerId, relay.apiFormat),
                    contextWindowTokens = relay.contextWindowTokens,
                    apiKey = relay.apiKey.takeIf { it.isNotBlank() },
                )
            }
        }

    private fun exportAgentSettings(config: ProviderConfig) = LanWebSyncedAgentSettings(
        approvalMode = approvalModeToWire(config.approvalMode),
        systemPrompt = config.systemPrompt,
        responseVerbosity = config.responseVerbosity.name.lowercase(),
        temperature = config.temperature,
        maxTokens = config.maxTokens,
        enableMultimodalMessages = config.enableMultimodalMessages,
        plannerProfileEnabled = config.plannerProfileEnabled,
        plannerModel = config.plannerModel,
        plannerReasoningEffort = config.plannerReasoningEffort,
        subagentDefaultProfileEnabled = config.subagentDefaultProfileEnabled,
        subagentDefaultModel = config.subagentDefaultModel,
        subagentDefaultReasoningEffort = config.subagentDefaultReasoningEffort,
    )

    private fun exportMediaSettings(config: ProviderConfig) = LanWebSyncedMediaSettings(
        visionRoutingEnabled = config.visionRoutingEnabled,
        visionProviderId = config.visionProviderId,
        visionProfileId = config.visionRelayId,
        visionModel = config.visionModel,
        visionCustomBaseUrl = config.visionCustomBaseUrl,
        imageGenerationProviderId = config.imageGenerationProviderId,
        imageGenerationProfileId = config.imageGenerationRelayId,
        imageGenerationModel = config.imageGenerationModel,
        imageGenerationCustomBaseUrl = config.imageGenerationCustomBaseUrl,
        imageGenerationSize = config.imageGenerationSize,
        imageGenerationQuality = config.imageGenerationQuality,
        imageGenerationFormat = config.imageGenerationFormat,
        imageGenerationCompression = config.imageGenerationCompression,
        imageGenerationPartialImages = config.imageGenerationPartialImages,
        imageUpscaleBaseUrl = config.imageUpscaleBaseUrl,
        imageUpscaleModel = config.imageUpscaleModel,
        imageUpscaleScale = config.imageUpscaleScale,
    )

    private fun exportMediaCredentials(config: ProviderConfig): LanWebSyncedMediaCredentials? {
        val visionKey = config.visionCustomApiKey.takeIf { it.isNotBlank() }
        val imageKey = config.imageGenerationCustomApiKey.takeIf { it.isNotBlank() }
        val upscaleKey = config.imageUpscaleApiKey.takeIf { it.isNotBlank() }
        return if (visionKey == null && imageKey == null && upscaleKey == null) null else LanWebSyncedMediaCredentials(
            visionCustomApiKey = visionKey,
            imageGenerationCustomApiKey = imageKey,
            imageUpscaleApiKey = upscaleKey,
        )
    }

    private fun exportKnowledge(config: ProviderConfig): LanWebSyncedKnowledge {
        val memories = (config.globalMemories + configRepository.listDurableGlobalMemories())
            .associateBy(GlobalMemory::id)
            .values
        return LanWebSyncedKnowledge(
            rules = config.globalRules.map { LanWebSyncedRule(it.id, it.title, it.content, it.enabled) },
            memories = memories.map { LanWebSyncedMemory(it.id, it.title, it.content, it.enabled) },
            skills = config.globalSkills.map {
                LanWebSyncedSkill(
                    id = it.id,
                    title = it.title,
                    description = it.description,
                    content = it.content,
                    runAs = it.runAs.name,
                    allowedTools = it.allowedTools,
                    preferredModel = it.preferredModel,
                    enabled = it.enabled,
                )
            },
        )
    }

    private fun exportMcpServers(includeCredentials: Boolean): List<LanWebSyncedMcpServer> {
        val configs = if (includeCredentials) mcpRegistry.loadConfigs() else mcpRegistry.exportBackupConfigs()
        return configs.map { server ->
            LanWebSyncedMcpServer(
                id = server.name,
                name = server.name,
                transport = when (server.transport) {
                    McpTransportType.STDIO -> "stdio"
                    McpTransportType.SSE -> "legacy_sse"
                    McpTransportType.STREAMABLE_HTTP -> "streamable_http"
                },
                command = server.command,
                args = server.args,
                url = server.url,
                requestTimeoutSeconds = ((server.requestTimeoutMs ?: 60_000L) / 1_000L).toInt().coerceIn(1, 600),
                trustedReadOnlyTools = server.trustedReadOnlyTools,
                enabled = server.enabled,
                autoStart = server.autoStart,
                environment = server.env,
                headers = server.headers,
            )
        }
    }

    private fun exportSavedWorkflows(): List<LanWebSyncedSavedWorkflow> = workflowScheduler.list().map { workflow ->
        LanWebSyncedSavedWorkflow(
            id = workflow.id,
            name = workflow.name,
            template = workflow.template.name,
            githubRepository = workflow.githubRepository,
            nodes = workflow.nodes.map { node ->
                LanWebSyncedWorkflowNode(
                    id = node.id,
                    label = node.label,
                    dependsOn = node.dependsOn,
                    requiredPermission = node.requiredPermission.name,
                    timeoutSeconds = node.timeoutSeconds.toInt(),
                    maxRetries = node.maxRetries,
                )
            },
            intervalMinutes = workflow.intervalMinutes.toInt(),
            createdAt = workflow.createdAt,
            updatedAt = workflow.updatedAt,
        )
    }

    private fun mergeRules(existing: List<GlobalRule>, incoming: List<LanWebSyncedRule>): List<GlobalRule> {
        val merged = existing.associateBy(GlobalRule::id).toMutableMap()
        incoming.forEach { merged[it.id] = GlobalRule(it.id, it.title.trim(), it.content.trim(), it.enabled) }
        return merged.values.toList()
    }

    private fun mergeMemories(existing: List<GlobalMemory>, incoming: List<LanWebSyncedMemory>): List<GlobalMemory> {
        val merged = existing.associateBy(GlobalMemory::id).toMutableMap()
        incoming.forEach { merged[it.id] = GlobalMemory(it.id, it.title.trim(), it.content.trim(), it.enabled) }
        return merged.values.toList()
    }

    private fun mergeSkills(existing: List<GlobalSkill>, incoming: List<LanWebSyncedSkill>): List<GlobalSkill> {
        val merged = existing.associateBy(GlobalSkill::id).toMutableMap()
        incoming.forEach {
            merged[it.id] = GlobalSkill(
                id = it.id,
                title = it.title.trim(),
                description = it.description.trim(),
                content = it.content.trim(),
                runAs = SkillRunAs.valueOf(it.runAs.uppercase()),
                allowedTools = it.allowedTools.distinct().take(500),
                preferredModel = it.preferredModel.trim(),
                enabled = it.enabled,
            )
        }
        return merged.values.toList()
    }

    private fun importMcpServers(
        existing: List<McpServerConfig>,
        incoming: List<LanWebSyncedMcpServer>,
        credentialsIncluded: Boolean,
    ): Pair<List<McpServerConfig>, Int> {
        val merged = existing.associateBy { it.name.lowercase() }.toMutableMap()
        var disabled = 0
        incoming.forEach { portable ->
            val key = portable.name.lowercase()
            val previous = merged[key]
            val transport = when (portable.transport) {
                "streamable_http" -> McpTransportType.STREAMABLE_HTTP
                "legacy_sse" -> McpTransportType.SSE
                else -> McpTransportType.STDIO
            }
            val crossPlatformStdio = transport == McpTransportType.STDIO
            if (crossPlatformStdio && portable.enabled) disabled++
            merged[key] = McpServerConfig(
                name = portable.name.trim(),
                transport = transport,
                command = portable.command.trim(),
                args = portable.args,
                cwd = "",
                env = if (credentialsIncluded) {
                    portable.environment
                } else {
                    previous?.env.orEmpty() + portable.environment
                },
                url = portable.url.trim(),
                headers = if (credentialsIncluded) {
                    portable.headers
                } else {
                    previous?.headers.orEmpty() + portable.headers
                },
                requestTimeoutMs = portable.requestTimeoutSeconds.toLong() * 1_000L,
                source = McpConfigSource.MANUAL,
                sourcePath = "",
                trustedReadOnlyTools = portable.trustedReadOnlyTools.distinct(),
                autoStart = portable.autoStart && !crossPlatformStdio,
                enabled = portable.enabled && !crossPlatformStdio,
            )
        }
        return merged.values.toList() to disabled
    }

    private fun importSavedWorkflows(
        existing: List<SavedWorkflowDefinition>,
        incoming: List<LanWebSyncedSavedWorkflow>,
    ): Triple<List<SavedWorkflowDefinition>, Int, Int> {
        val merged = existing.associateBy(SavedWorkflowDefinition::id).toMutableMap()
        var imported = 0
        var skipped = 0
        incoming.forEach { portable ->
            val template = runCatching { SavedWorkflowTemplate.valueOf(portable.template) }.getOrNull()
            if (template == null || template in PATH_BOUND_WORKFLOW_TEMPLATES) {
                skipped++
                return@forEach
            }
            val nodes = runCatching {
                portable.nodes.map { node ->
                    SavedWorkflowNode(
                        id = node.id,
                        label = node.label,
                        dependsOn = node.dependsOn,
                        requiredPermission = ToolPermissionCategory.valueOf(node.requiredPermission),
                        timeoutSeconds = node.timeoutSeconds.toLong(),
                        maxRetries = node.maxRetries,
                    )
                }
            }.getOrElse {
                skipped++
                return@forEach
            }
            val workflow = SavedWorkflowDefinition(
                id = portable.id,
                name = portable.name.trim(),
                template = template,
                projectPath = null,
                githubRepository = portable.githubRepository?.trim()?.ifBlank { null },
                nodes = nodes,
                intervalMinutes = portable.intervalMinutes.toLong(),
                enabled = false,
                createdAt = portable.createdAt,
                updatedAt = portable.updatedAt,
                lastRun = null,
            )
            if (!workflow.validate().isValid) {
                skipped++
            } else {
                merged[workflow.id] = workflow
                imported++
            }
        }
        return Triple(merged.values.toList(), imported, skipped)
    }

    private fun approvalModeToWire(mode: ToolApprovalMode): String = when (mode) {
        ToolApprovalMode.READ_ONLY -> "readonly"
        ToolApprovalMode.ALL_APPROVAL -> "ask"
        ToolApprovalMode.WHITELIST_AUTO -> "allowlist"
        ToolApprovalMode.ALL_AUTO -> "yolo"
    }

    private fun approvalModeFromWire(value: String): ToolApprovalMode = when (value.lowercase()) {
        "readonly" -> ToolApprovalMode.READ_ONLY
        "allowlist" -> ToolApprovalMode.WHITELIST_AUTO
        "yolo" -> ToolApprovalMode.ALL_AUTO
        "ask" -> ToolApprovalMode.ALL_APPROVAL
        else -> error("审批模式无效")
    }

    private fun validateBundle(bundle: LanWebCredentialSyncBundle) {
        require(bundle.schemaVersion in 1..8) { "设备同步格式版本不受支持" }
        require(bundle.sourcePlatform in setOf("windows", "darwin", "linux", "desktop", "android")) { "凭据同步来源无效" }
        val now = System.currentTimeMillis()
        require(bundle.generatedAt in (now - 5 * 60_000L)..(now + 60_000L)) { "设备同步时间无效" }
        require(bundle.providers.size <= MAX_PROFILES_TOTAL) { "模型连接数量过多" }
        bundle.providers.forEach { profile ->
            require(normalizeProviderId(profile.providerId) in SUPPORTED_PROVIDERS) { "模型连接类型不受支持" }
            require(profile.profileId.isNotBlank() && profile.profileId.length <= 100 && profile.profileId.none(Char::isISOControl)) {
                "模型连接 ID 无效"
            }
            require(profile.name.length <= 100 && profile.model.length <= 200) { "模型连接名称或模型过长" }
            require(profile.reasoningEffort in setOf("", "low", "medium", "high", "xhigh", "max", "on", "off")) { "推理强度无效" }
            require(profile.apiMode in setOf("", "auto", "chat-completions", "responses", "messages", "gemini")) { "上游格式无效" }
            require(profile.apiKey == null || profile.apiKey.length <= MAX_API_KEY_CHARS) { "API Key 过长" }
            profile.contextWindowTokens?.let { require(it in 4_096..2_000_000) { "上下文窗口无效" } }
            validateBaseUrl(profile.baseUrl)
        }
        bundle.github?.let { github ->
            require(github.apiBaseUrl.isNotBlank()) { "GitHub API 地址不能为空" }
            validateBaseUrl(github.apiBaseUrl)
            require(github.token == null || github.token.length <= MAX_API_KEY_CHARS) { "GitHub Token 过长" }
            require(github.viewerLogin.length <= 100 && github.viewerLogin.none(Char::isISOControl)) { "GitHub 用户名无效" }
        }
        require(bundle.githubAccounts.size <= MAX_ACCOUNT_POOL_SIZE) { "GitHub 账号数量过多" }
        require(bundle.githubAccounts.map { it.id }.distinct().size == bundle.githubAccounts.size) { "GitHub 账号 ID 重复" }
        bundle.githubAccounts.forEach { account ->
            require(SAFE_ACCOUNT_ID.matches(account.id)) { "GitHub 账号 ID 无效" }
            require(account.label.isNotBlank() && account.label.length <= 80 && account.label.none(Char::isISOControl)) { "GitHub 账号名称无效" }
            require(account.login.length <= 80 && account.name.length <= 160 && account.avatarUrl.length <= 2_048) { "GitHub 账号资料过长" }
            require((account.login + account.name + account.avatarUrl).none(Char::isISOControl)) { "GitHub 账号资料无效" }
            validateBaseUrl(account.apiBaseUrl)
            require(account.token == null || account.token.length <= MAX_API_KEY_CHARS) { "GitHub 账号 Token 过长" }
        }
        if (bundle.githubAccounts.isNotEmpty()) {
            require(bundle.githubAccounts.any { it.id == bundle.activeGitHubAccountId }) { "当前 GitHub 账号无效" }
        }
        require(bundle.codexAccounts.size <= MAX_ACCOUNT_POOL_SIZE) { "Codex 账号数量过多" }
        require(bundle.codexAccounts.map { it.id }.distinct().size == bundle.codexAccounts.size) { "Codex 账号 ID 重复" }
        bundle.codexAccounts.forEach { account ->
            require(SAFE_ACCOUNT_ID.matches(account.id)) { "Codex 账号 ID 无效" }
            require(account.label.isNotBlank() && account.label.length <= 80 && account.label.none(Char::isISOControl)) { "Codex 账号名称无效" }
            require(account.email.orEmpty().length <= 320 && account.planType.orEmpty().length <= 80) { "Codex 账号资料过长" }
            require((account.email.orEmpty() + account.planType.orEmpty()).none(Char::isISOControl)) { "Codex 账号资料无效" }
            account.authJson?.let(::validateCodexAuth)
        }
        if (bundle.codexAccounts.isNotEmpty()) {
            require(bundle.codexAccounts.any { it.id == bundle.activeCodexAccountId }) { "当前 Codex 账号无效" }
        }
        bundle.codexAccountSettings?.let { settings ->
            require(settings.reservePercent in 1.0..50.0 && settings.cooldownMinutes in 1..1440) { "Codex 账号池设置无效" }
        }
        bundle.agentSettings?.let { settings ->
            approvalModeFromWire(settings.approvalMode)
            require(settings.systemPrompt.isNotBlank() && settings.systemPrompt.length <= MAX_TEXT_CHARS) { "系统提示词无效或过长" }
            require(settings.responseVerbosity.uppercase() in ResponseVerbosity.entries.map { it.name }) { "回复详细度无效" }
            settings.temperature?.let { require(it in 0.0..2.0) { "Temperature 无效" } }
            settings.maxTokens?.let { require(it in 1..128_000) { "最大输出 Token 无效" } }
            settings.plannerModel?.let { requireValidExecutionProfileModel(it, "规划模型") }
            settings.subagentDefaultModel?.let { requireValidExecutionProfileModel(it, "子代理默认模型") }
            settings.plannerReasoningEffort?.let { requireValidExecutionProfileReasoning(it, "规划推理强度") }
            settings.subagentDefaultReasoningEffort?.let { requireValidExecutionProfileReasoning(it, "子代理默认推理强度") }
        }
        bundle.mediaSettings?.let(::validateMediaSettings)
        bundle.mediaCredentials?.let { credentials ->
            require(credentials.visionCustomApiKey == null || credentials.visionCustomApiKey.length <= MAX_API_KEY_CHARS) {
                "独立看图 API Key 过长"
            }
            require(credentials.imageGenerationCustomApiKey == null || credentials.imageGenerationCustomApiKey.length <= MAX_API_KEY_CHARS) {
                "图片生成 API Key 过长"
            }
            require(credentials.imageUpscaleApiKey == null || credentials.imageUpscaleApiKey.length <= MAX_API_KEY_CHARS) {
                "4K 超分 API Key 过长"
            }
        }
        bundle.knowledge?.let { knowledge ->
            require(knowledge.rules.size <= MAX_KNOWLEDGE_ITEMS && knowledge.memories.size <= MAX_KNOWLEDGE_ITEMS && knowledge.skills.size <= MAX_KNOWLEDGE_ITEMS) {
                "知识库条目数量过多"
            }
            val ids = (knowledge.rules.map { it.id } + knowledge.memories.map { it.id } + knowledge.skills.map { it.id })
            require(ids.all(::validPortableId)) { "知识库条目 ID 无效" }
            require(knowledge.rules.all { validKnowledgeText(it.title, it.content) }) { "规则内容无效或过长" }
            require(knowledge.memories.all { validKnowledgeText(it.title, it.content) }) { "记忆内容无效或过长" }
            require(knowledge.skills.all {
                validKnowledgeText(it.title, it.content) && it.description.length <= 2_000 &&
                    it.runAs.uppercase() in SkillRunAs.entries.map(SkillRunAs::name) && it.allowedTools.size <= 500
            }) { "Skill 内容无效或过长" }
        }
        require(bundle.mcpServers.size <= MAX_MCP_SERVERS) { "MCP 服务器数量过多" }
        require(bundle.mcpServers.map { it.name.lowercase() }.distinct().size == bundle.mcpServers.size) { "MCP 服务器名称重复" }
        bundle.mcpServers.forEach { server ->
            require(server.name.isNotBlank() && server.name.length <= 200) { "MCP 服务器名称无效" }
            require(server.transport in setOf("stdio", "streamable_http", "legacy_sse")) { "MCP 传输类型无效" }
            require(server.args.size <= 64 && server.requestTimeoutSeconds in 1..600) { "MCP 参数或超时无效" }
            require(server.environment.size <= 128 && server.headers.size <= 128) { "MCP 凭据条目过多" }
            require((server.environment + server.headers).all { (key, value) ->
                key.isNotBlank() && key.length <= 256 && value.length <= 65_536 && key.none(Char::isISOControl)
            }) { "MCP 凭据字段无效" }
            if (server.transport != "stdio" && server.url.isNotBlank()) validateBaseUrl(server.url)
        }
        require(bundle.savedWorkflows.size <= MAX_WORKFLOWS) { "保存的工作流数量过多" }
        require(bundle.savedWorkflows.map { it.id }.distinct().size == bundle.savedWorkflows.size) { "保存的工作流 ID 重复" }
        require(bundle.savedWorkflows.all {
            validPortableId(it.id) && it.name.isNotBlank() && it.name.length <= 500 && it.nodes.size <= 100 &&
                it.intervalMinutes in 15..10_080
        }) { "保存的工作流定义无效" }
        PortableConversationBackupStore(context).validateRecords(
            bundle.sourcePlatform,
            bundle.sessions.map { PortableConversationBackupRecord(it.sourceSessionId, it.document.toString()) }
        )
        bundle.codexAuthJson?.let(::validateCodexAuth)
    }

    private fun validPortableId(value: String): Boolean =
        value.isNotBlank() && value.length <= 200 && value.none(Char::isISOControl)

    private fun requireValidExecutionProfileModel(value: String, label: String) {
        require(value == value.trim() && value.length <= 200 && value.none(Char::isISOControl)) { "$label 无效或过长" }
    }

    private fun requireValidExecutionProfileReasoning(value: String, label: String) {
        require(value == value.trim() && value.lowercase() in setOf("", "low", "medium", "high", "xhigh", "max")) {
            "$label 无效"
        }
    }

    private fun validateMediaSettings(settings: LanWebSyncedMediaSettings) {
        listOf(
            "独立看图供应商" to settings.visionProviderId,
            "独立看图连接" to settings.visionProfileId,
            "独立看图模型" to settings.visionModel,
            "独立看图 Base URL" to settings.visionCustomBaseUrl,
            "生图供应商" to settings.imageGenerationProviderId,
            "生图连接" to settings.imageGenerationProfileId,
            "生图模型" to settings.imageGenerationModel,
            "生图 Base URL" to settings.imageGenerationCustomBaseUrl,
            "4K 超分 Base URL" to settings.imageUpscaleBaseUrl,
            "4K 超分模型" to settings.imageUpscaleModel,
        ).forEach { (label, value) ->
            require(value == value.trim() && value.length <= 500 && value.none(Char::isISOControl)) { "$label 无效或过长" }
        }
        settings.visionCustomBaseUrl.takeIf { it.isNotBlank() }?.let(::validateBaseUrl)
        settings.imageGenerationCustomBaseUrl.takeIf { it.isNotBlank() }?.let(::validateBaseUrl)
        settings.imageUpscaleBaseUrl.takeIf { it.isNotBlank() }?.let(::validateBaseUrl)
        require(settings.imageGenerationSize in setOf(
            "", "auto", "1024x1024", "1024x1536", "1536x1024",
            "2048x2048", "2048x1152", "3840x2160", "2160x3840",
        )) { "图片尺寸无效" }
        require(settings.imageGenerationQuality in setOf("", "auto", "low", "medium", "high")) { "图片质量无效" }
        require(settings.imageGenerationFormat in setOf("", "png", "jpeg", "webp")) { "图片格式无效" }
        require(settings.imageGenerationCompression in 0..100) { "图片压缩质量无效" }
        require(settings.imageGenerationPartialImages in 0..3) { "图片局部预览数量无效" }
        require(settings.imageUpscaleScale in 2..4) { "4K 超分倍率无效" }
    }

    private fun validKnowledgeText(title: String, content: String): Boolean =
        title.isNotBlank() && title.length <= 500 && content.isNotBlank() && content.length <= MAX_TEXT_CHARS

    private fun validateBaseUrl(value: String) {
        if (value.isBlank()) return
        require(value.length <= 2_048) { "模型地址过长" }
        val uri = runCatching { URI(value) }.getOrNull() ?: error("模型地址无效")
        require(uri.scheme in setOf("http", "https") && uri.host != null && uri.userInfo == null) { "模型地址无效" }
    }

    private fun readValidatedCodexAuth(): String? {
        val file = codexAuthFile()
        if (!file.isFile) return null
        val bytes = file.readBytes()
        require(bytes.size <= MAX_CODEX_AUTH_BYTES) { "Codex 登录文件过大" }
        val text = bytes.toString(Charsets.UTF_8)
        bytes.fill(0)
        validateCodexAuth(text)
        return text
    }

    private suspend fun replaceCodexAuthAndVerify(authJson: String): String? {
        validateCodexAuth(authJson)
        val target = codexAuthFile()
        val previous = target.takeIf(File::isFile)?.readBytes()
        codexAppServer.stop()
        try {
            writePrivateFileAtomic(target, authJson.toByteArray(Charsets.UTF_8))
            val account = codexAppServer.accountRead(refreshToken = true).account
                ?: error("同步的 ChatGPT 登录无法通过 Codex 验证")
            val rates = runCatching { codexAppServer.accountRateLimitsRead() }.getOrNull()
            codexAccountManager.captureRuntime(account, rates)
            return account.email
        } catch (error: Throwable) {
            runCatching { codexAppServer.stop() }
            if (previous == null) {
                target.delete()
            } else {
                writePrivateFileAtomic(target, previous)
            }
            runCatching { codexAppServer.start() }
            throw IllegalStateException("ChatGPT 登录同步失败，已恢复手机原登录：${error.message.orEmpty().take(300)}")
        } finally {
            previous?.fill(0)
        }
    }

    private fun validateCodexAuth(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        try {
            require(bytes.isNotEmpty() && bytes.size <= MAX_CODEX_AUTH_BYTES) { "Codex 登录文件大小无效" }
            val root = json.parseToJsonElement(value).jsonObject
            require(root["auth_mode"] != null) { "Codex 登录文件缺少 auth_mode" }
            require(root["tokens"] is JsonObject || root["OPENAI_API_KEY"] != null) { "Codex 登录文件缺少凭据" }
        } finally {
            bytes.fill(0)
        }
    }

    private fun codexAuthFile(): File = codexAccountManager.activeAuthFile()

    private fun writePrivateFileAtomic(target: File, bytes: ByteArray) {
        require(bytes.size <= MAX_CODEX_AUTH_BYTES)
        target.parentFile?.let { require(it.isDirectory || it.mkdirs()) }
        val temp = File(target.parentFile, ".${target.name}.${System.nanoTime()}.tmp")
        try {
            FileOutputStream(temp).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            temp.setReadable(false, false)
            temp.setWritable(false, false)
            temp.setReadable(true, true)
            temp.setWritable(true, true)
            check(temp.renameTo(target) || run {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }) { "无法写入 Codex 登录文件" }
        } finally {
            temp.delete()
            bytes.fill(0)
        }
    }

    private fun normalizeProviderId(value: String): String = when (value.trim().lowercase()) {
        "openai", "openai-compatible" -> "openai-compatible"
        "anthropic", "claude" -> "claude"
        "deepseek" -> "deepseek"
        "kimi", "moonshot" -> "kimi"
        "glm", "zhipu", "bigmodel" -> "glm"
        "qwen", "dashscope" -> "qwen"
        "minimax" -> "minimax"
        "grok", "xai" -> "grok"
        "mimo", "xiaomimimo" -> "mimo"
        "hy3", "hunyuan" -> "hy3"
        "gemini", "google" -> "gemini"
        else -> value.trim().lowercase()
    }

    private fun defaultRelayReasoningEffort(providerId: String): String = when (providerId) {
        "kimi" -> "on"
        "glm" -> "high"
        "qwen", "grok" -> "medium"
        "deepseek", "openai-compatible", "claude" -> "high"
        else -> ""
    }

    private fun providerWireFormatFromSync(
        apiMode: String,
        providerId: String,
        previous: ProviderWireFormat?,
    ): ProviderWireFormat = when (apiMode.trim().lowercase()) {
        "responses" -> ProviderWireFormat.RESPONSES
        "messages" -> ProviderWireFormat.ANTHROPIC_MESSAGES
        "chat-completions" -> ProviderWireFormat.CHAT_COMPLETIONS
        else -> previous ?: ProviderPresetCatalog.get(providerId)?.defaultWireFormat
            ?: ProviderWireFormat.CHAT_COMPLETIONS
    }

    private fun providerWireFormatToSync(providerId: String, format: ProviderWireFormat): String =
        if (providerId == "gemini") "gemini" else when (format) {
            ProviderWireFormat.CHAT_COMPLETIONS -> "chat-completions"
            ProviderWireFormat.RESPONSES -> "responses"
            ProviderWireFormat.ANTHROPIC_MESSAGES -> "messages"
        }

    private companion object {
        val SUPPORTED_PROVIDERS = ProviderPresetCatalog.all().map { it.providerId }
        val PATH_BOUND_WORKFLOW_TEMPLATES = setOf(
            SavedWorkflowTemplate.PROJECT_READ_DIAGNOSTIC,
            SavedWorkflowTemplate.DIRECTORY_CHANGE_SUMMARY,
        )
        const val MAX_PROFILES_PER_PROVIDER = 32
        const val MAX_PROFILES_TOTAL = 96
        const val MAX_API_KEY_CHARS = 16_384
        const val MAX_CODEX_AUTH_BYTES = 256 * 1024
        const val MAX_TEXT_CHARS = 1_048_576
        const val MAX_KNOWLEDGE_ITEMS = 10_000
        const val MAX_MCP_SERVERS = 100
        const val MAX_WORKFLOWS = 500
        const val MAX_ACCOUNT_POOL_SIZE = 20
        val SAFE_ACCOUNT_ID = Regex("[A-Za-z0-9_-]{1,96}")
    }
}

internal data class SessionPage(
    val sessions: List<LanWebSyncedSession>,
    val nextCursor: Int?,
)

internal fun buildDeviceSyncSessionPage(
    records: List<PortableConversationBackupRecord>,
    startCursor: Int,
    json: Json = Json { ignoreUnknownKeys = false; encodeDefaults = true; explicitNulls = false },
): SessionPage {
    require(startCursor in 0..records.size) { "聊天记录分页游标已失效，请重新同步" }
    val sessions = ArrayList<LanWebSyncedSession>()
    var pageBytes = 0
    var cursor = startCursor
    while (cursor < records.size) {
        val record = records[cursor]
        val recordBytes = record.portableJson.toByteArray(Charsets.UTF_8).size +
            record.sourceSessionId.toByteArray(Charsets.UTF_8).size +
            record.originPlatform.toByteArray(Charsets.UTF_8).size +
            record.originSessionId.toByteArray(Charsets.UTF_8).size + DEVICE_SYNC_SESSION_RECORD_JSON_OVERHEAD_BYTES
        require(recordBytes <= MAX_DEVICE_SYNC_SESSION_RECORD_BYTES) {
            "聊天记录 ${record.sourceSessionId} 单条超过安全传输上限"
        }
        if (sessions.isNotEmpty() && pageBytes + recordBytes > DEVICE_SYNC_SESSION_PAGE_TARGET_BYTES) break
        sessions += LanWebSyncedSession(
            sourceSessionId = record.sourceSessionId,
            originPlatform = record.originPlatform,
            originSessionId = record.originSessionId,
            document = json.parseToJsonElement(record.portableJson).jsonObject,
        )
        pageBytes += recordBytes
        cursor++
    }
    return SessionPage(sessions, cursor.takeIf { it < records.size })
}

private const val DEVICE_SYNC_SESSION_PAGE_TARGET_BYTES = 6 * 1024 * 1024
private const val MAX_DEVICE_SYNC_SESSION_RECORD_BYTES = 28 * 1024 * 1024
private const val DEVICE_SYNC_SESSION_RECORD_JSON_OVERHEAD_BYTES = 512

internal fun mergeSyncedGitHubCredential(
    current: ProviderConfig,
    incoming: LanWebSyncedGitHubCredential,
): Pair<ProviderConfig, Boolean> {
    val incomingToken = incoming.token?.trim()?.takeIf(String::isNotEmpty)
    return current.copy(
        githubApiBaseUrl = incoming.apiBaseUrl.trim().trimEnd('/'),
        githubToken = incomingToken ?: current.githubToken,
        githubViewerLogin = if (incomingToken != null) incoming.viewerLogin.trim() else current.githubViewerLogin,
        githubViewerName = if (incomingToken != null) "" else current.githubViewerName,
        githubViewerAvatarUrl = if (incomingToken != null) "" else current.githubViewerAvatarUrl,
    ) to (incomingToken != null)
}
