package com.murong.agent.lan

import android.content.Context
import com.murong.agent.automation.SavedWorkflowScheduler
import com.murong.agent.core.automation.SavedWorkflowDefinition
import com.murong.agent.core.automation.SavedWorkflowNode
import com.murong.agent.core.automation.SavedWorkflowTemplate
import com.murong.agent.core.automation.validate
import com.murong.agent.core.codex.CodexAppServerClient
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
import com.murong.agent.core.mcp.McpConfigSource
import com.murong.agent.core.mcp.McpRegistry
import com.murong.agent.core.mcp.McpServerConfig
import com.murong.agent.core.mcp.McpTransportType
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton
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
    private val mcpRegistry: McpRegistry,
) {
    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true; explicitNulls = false }
    private val importMutex = Mutex()
    private val workflowScheduler by lazy { SavedWorkflowScheduler(context, reconcileInterruptedRuns = false) }

    suspend fun exportBundle(options: LanWebDeviceSyncOptions): LanWebCredentialSyncBundle {
        val config = configRepository.getConfig()
        val providers = if (options.includeProviderCredentials) exportProviders(config) else emptyList()
        val auth = if (options.includeCodexLogin) readValidatedCodexAuth() else null
        return LanWebCredentialSyncBundle(
            sourcePlatform = "android",
            generatedAt = System.currentTimeMillis(),
            activeProviderId = if (config.activeAgentBackend == AgentBackendKind.CODEX_CHATGPT) {
                "codex"
            } else {
                config.activeProviderId
            },
            activeProfileId = config.getActiveRelayId(config.activeProviderId),
            providers = providers,
            codexAuthJson = auth,
            github = if (options.includeGitHubCredentials) {
                LanWebSyncedGitHubCredential(
                    apiBaseUrl = config.getGitHubApiBaseUrl(),
                    token = config.githubToken.takeIf { it.isNotBlank() },
                    viewerLogin = config.githubViewerLogin,
                )
            } else {
                null
            },
            agentSettings = if (options.includeAgentSettings) exportAgentSettings(config) else null,
            knowledge = if (options.includeKnowledge) exportKnowledge(config) else null,
            mcpServers = if (options.includeMcp) exportMcpServers(options.includeMcpCredentials) else emptyList(),
            mcpCredentialsIncluded = options.includeMcp && options.includeMcpCredentials,
            savedWorkflows = if (options.includeSavedWorkflows) exportSavedWorkflows() else emptyList(),
        )
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
                config.activeProviderId
            },
            activeProfileId = config.getActiveRelayId(config.activeProviderId),
            providers = exportProviders(config).map { it.copy(apiKey = null) },
            codexAuthJson = null,
            github = LanWebSyncedGitHubCredential(
                apiBaseUrl = config.getGitHubApiBaseUrl(),
                token = null,
                viewerLogin = ""
            ),
            agentSettings = exportAgentSettings(config),
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
        require(bundle.providers.all { it.apiKey == null }) { "跨端备份不得包含 API Key" }
        require(bundle.github?.token == null && bundle.github?.viewerLogin.orEmpty().isBlank()) {
            "跨端备份不得包含 GitHub 登录状态"
        }
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
        val introducedRelayIds = mutableMapOf<String, MutableSet<String>>()
        var importedProviders = 0
        var importedApiKeys = 0
        var importedGitHubToken = false
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
                            reasoningEffort = profile.reasoningEffort.ifBlank { previous?.reasoningEffort ?: "high" },
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
            bundle.github?.let { github ->
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

            var importedCodex = false
            var accountEmail: String? = null
            bundle.codexAuthJson?.let { auth ->
                accountEmail = replaceCodexAuthAndVerify(auth)
                importedCodex = true
            }
            return LanWebCredentialSyncResult(
                importedProviders = importedProviders,
                importedApiKeys = importedApiKeys,
                importedCodexLogin = importedCodex,
                importedGitHubToken = importedGitHubToken,
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
        require(bundle.schemaVersion in 1..4) { "设备同步格式版本不受支持" }
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
            require(profile.reasoningEffort in setOf("", "low", "medium", "high", "xhigh", "max")) { "推理强度无效" }
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

    private fun codexAuthFile(): File = File(context.filesDir, "codex-home/auth.json")

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
        else -> value.trim().lowercase()
    }

    private companion object {
        val SUPPORTED_PROVIDERS = listOf("deepseek", "openai-compatible", "claude")
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
    }
}

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
