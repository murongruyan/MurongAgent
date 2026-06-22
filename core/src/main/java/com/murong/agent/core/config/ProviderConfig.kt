package com.murong.agent.core.config

import com.murong.agent.core.tool.ApprovalRiskLevel
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

@Serializable
data class GlobalRule(val id: String, val title: String, val content: String, val enabled: Boolean = true)

@Serializable
data class GlobalMemory(val id: String, val title: String, val content: String, val enabled: Boolean = true)

@Serializable
enum class SkillRunAs { INLINE, SUBAGENT }

@Serializable
data class GlobalSkill(
    val id: String, val title: String, val description: String = "", val content: String,
    val runAs: SkillRunAs = SkillRunAs.INLINE, val allowedTools: List<String> = emptyList(),
    val preferredModel: String = "", val enabled: Boolean = true
)

@Serializable
data class ProjectSubagentTemplate(
    val id: String, val title: String, val description: String = "",
    val goalMatchers: List<String> = emptyList(), val preferredModel: String = "",
    val preferredReasoningEffort: String = "", val enableWebSearch: Boolean = true,
    val allowWriteAccess: Boolean = false, val allowCodeEdits: Boolean = false,
    val allowShell: Boolean = false, val enabled: Boolean = true
)

enum class ToolApprovalMode { READ_ONLY, ALL_APPROVAL, WHITELIST_AUTO, ALL_AUTO }

@Serializable
enum class ResponseVerbosity { CONCISE, BALANCED, DETAILED }

@Serializable
enum class WorkflowExecutionMode { SINGLE_PASS }

@Serializable
enum class WorkflowFailureFallbackMode { FOLLOW_SCENARIO_DEFAULT, DIRECT_EXECUTION, LOCAL_CLARIFICATION }

enum class WorkflowFailureType { AUTO_ROUTE_FAILURE, CLARIFICATION_GENERATION_FAILURE, CLARIFICATION_FOLLOW_UP_FAILURE, EXECUTION_INTERRUPT_FORMAT_FAILURE }
enum class WorkflowFailureFallbackSource { MANUAL_CLARIFICATION, AUTO_ROUTE, AUTO_INTERRUPT }
enum class ProjectWorkflowType { APPLICATION, LIBRARY, AUTOMATION }
enum class ProjectWorkflowRiskLevel { LOW, MEDIUM, HIGH }

data class WorkflowFailureFallbackContext(
    val hasMentionedFiles: Boolean = false, val source: WorkflowFailureFallbackSource? = null,
    val clarificationTurnIndex: Int? = null, val projectType: ProjectWorkflowType? = null,
    val providerId: String? = null, val projectRiskLevel: ProjectWorkflowRiskLevel? = null
)

@Serializable
data class ProjectToolPreferences(
    val workflowExecutionMode: WorkflowExecutionMode? = null,
    val autoRouteBeforeExecution: Boolean? = null,
    val failureFallbackMode: WorkflowFailureFallbackMode? = null,
    val approvalMode: ToolApprovalMode? = null,
    val enabledBuiltinTools: List<String>? = null,
    val enabledFileToolOperations: List<String>? = null,
    val allowAllMcpTools: Boolean? = null,
    val allowedMcpTools: List<String>? = null,
    val allowedShellCommandPrefixes: List<String>? = null,
    val allowedPathPrefixes: List<String>? = null,
    val subagentTemplates: List<ProjectSubagentTemplate>? = null
)

@Serializable
data class ProviderConfig(
    val activeProviderId: String = "deepseek",
    val deepseekApiKey: String = "", val deepseekBaseUrl: String = "",
    val deepseekModelPreset: String = "custom", val deepseekModel: String = "deepseek-v4-flash",
    val deepseekReasoningEffort: String = "high",
    val deepseekPromptPricePer1M: Double = 0.0, val deepseekCompletionPricePer1M: Double = 0.0,
    val deepseekBalanceUsd: Double = 0.0, val deepseekBalanceCurrency: String = "CNY",
    val deepseekBalanceSyncedAt: Long? = null,
    val openaiApiKey: String = "", val openaiBaseUrl: String = "",
    val openaiModel: String = "gpt-5.5", val openaiReasoningEffort: String = "medium",
    val openaiPromptPricePer1M: Double = 0.0, val openaiCompletionPricePer1M: Double = 0.0,
    val openaiBalanceUsd: Double = 0.0, val openaiBalanceCurrency: String = "USD",
    val openaiBalanceSyncedAt: Long? = null, val openaiBalanceApiPath: String = "",
    val claudeApiKey: String = "", val claudeBaseUrl: String = "",
    val claudeModel: String = "claude-opus-4-8", val claudeReasoningEffort: String = "high",
    val claudePromptPricePer1M: Double = 0.0, val claudeCompletionPricePer1M: Double = 0.0,
    val claudeBalanceUsd: Double = 0.0, val claudeBalanceCurrency: String = "USD",
    val claudeBalanceSyncedAt: Long? = null, val claudeBalanceApiPath: String = "",
    val githubToken: String = "", val githubApiBaseUrl: String = "https://api.github.com",
    val githubClientId: String = "", val githubClientSecret: String = "",
    val githubBackendSessionToken: String = "", val githubViewerLogin: String = "",
    val githubViewerName: String = "", val githubViewerAvatarUrl: String = "",
    val skippedAppUpdateVersionCode: Int? = null,
    val ignoredExtensionUpdateVersionCode: Int? = null,
    val systemPrompt: String = """
        You are Murong Agent, a coding assistant running on an Android device with root access. You have shell access and file system access.
        Match the user's language by default.
        If the user primarily speaks Chinese, keep responses, progress updates, and any visible reasoning in Chinese unless quoting code, logs, errors, or other source text that should stay verbatim.

        Default to a detailed, explanatory, highly communicative style.
        Do not be overly brief unless the user explicitly asks for a short answer.
        Explain what you are doing, why you are doing it, what you found, and what the result means.
        For coding and debugging tasks, prefer a short conclusion first, then key findings, then concrete changes or next steps, and finally any important risks or follow-up notes.
        When using tools, briefly narrate important intent and summarize the outcome in natural language after the tool result arrives.
        For large files, prefer file.read with line offsets or code_search instead of dumping whole files with shell cat.
        For code lookup, first locate the real source file, then read only the local context you need.
        When a class, symbol, or file name is known, prefer finding the exact file path first, then run code_search inside source trees or read that file directly.
        Prefer source directories such as src/main, src/test, app/src, core/src, common/src, and avoid treating build, .gradle, out, target, intermediates, and mapping outputs as primary evidence unless the user explicitly asks for generated artifacts.
        If an initial search only hits generated outputs, compiled artifacts, or unrelated noise, change strategy instead of stopping: narrow to source directories, try the exact class/file name, then read nearby lines for confirmation.
        If a result is complex, break it into clear sections and keep it easy to scan.
        Be proactive, specific, and helpful. Prefer slightly verbose explanations over terse replies.
        Never invent your own model identity. If the user asks what model you are, answer according to the actual runtime provider/model configuration supplied by the app, not by guessing from style.
    """.trimIndent(),
    val globalRules: List<GlobalRule> = emptyList(),
    val globalMemories: List<GlobalMemory> = emptyList(),
    val globalSkills: List<GlobalSkill> = emptyList(),
    @Contextual
    val projectToolPreferences: ProjectToolPreferences? = null,
    val approvalMode: ToolApprovalMode = ToolApprovalMode.ALL_APPROVAL,
    val workflowExecutionMode: WorkflowExecutionMode = WorkflowExecutionMode.SINGLE_PASS,
    val autoRouteBeforeExecution: Boolean = true,
    val enabledBuiltinTools: List<String> = DEFAULT_ENABLED_BUILTIN_TOOLS,
    val enabledFileToolOperations: List<String> = DEFAULT_ENABLED_FILE_TOOL_OPERATIONS,
    val allowAllMcpTools: Boolean = true,
    val allowedMcpTools: List<String> = emptyList(),
    val allowedShellCommandPrefixes: List<String> = emptyList(),
    val allowedPathPrefixes: List<String> = emptyList(),
    val autoUpgradeExecutionProfile: Boolean = true,
    val executionProfileAutoControlsInitialized: Boolean = false,
    val deepseekAutoModelSelection: Boolean = true,
    val deepseekAutoReasoningEffort: Boolean = true,
    val openaiAutoModelSelection: Boolean = true,
    val openaiAutoReasoningEffort: Boolean = true,
    val claudeAutoModelSelection: Boolean = true,
    val claudeAutoReasoningEffort: Boolean = true,
    val plannerProfileEnabled: Boolean = false,
    val plannerModel: String = "",
    val plannerReasoningEffort: String = "",
    val subagentDefaultProfileEnabled: Boolean = false,
    val subagentDefaultModel: String = "",
    val subagentDefaultReasoningEffort: String = "",
    val enableStreamingResponses: Boolean = true,
    val enableMultimodalMessages: Boolean = true,
    val responseVerbosity: ResponseVerbosity = ResponseVerbosity.DETAILED,
    val webSearchSearxngBaseUrl: String = "",
    val webSearchBingApiKey: String = "",
    val temperature: Double = 0.7,
    val maxTokens: Int = 8192
) {
    data class ApprovalDecisionExplanation(
        val requiresApproval: Boolean, val explanationLabel: String, val explanationDetail: String
    )

    fun getApiKey(providerId: String = activeProviderId): String = when (providerId) {
        "deepseek" -> deepseekApiKey; "openai-compatible" -> openaiApiKey; "claude" -> claudeApiKey; else -> ""
    }
    fun getActiveApiKey(): String = getApiKey(activeProviderId)
    fun getBaseUrl(providerId: String = activeProviderId): String? = normalizeBaseUrl(
        when (providerId) {
            "deepseek" -> deepseekBaseUrl
            "openai-compatible" -> openaiBaseUrl
            "claude" -> claudeBaseUrl
            else -> null
        }
    )
    fun getActiveBaseUrl(): String? = getBaseUrl(activeProviderId)
    fun getResolvedModel(providerId: String): String = when (providerId) {
        "deepseek" -> deepseekModel; "openai-compatible" -> openaiModel; "claude" -> claudeModel; else -> ""
    }
    fun getActiveModel(): String = when (activeProviderId) {
        "deepseek" -> deepseekModel; "openai-compatible" -> openaiModel; "claude" -> claudeModel; else -> ""
    }
    fun getActiveReasoningEffort(): String? = when (activeProviderId) {
        "deepseek" -> deepseekReasoningEffort
        "openai-compatible" -> openaiReasoningEffort
        "claude" -> claudeReasoningEffort
        else -> null
    }
    fun getPlannerResolvedConfig(): ProviderConfig {
        return if (!plannerProfileEnabled) this else applyActiveProviderProfileOverrides(
            modelOverride = plannerModel,
            reasoningOverride = plannerReasoningEffort
        )
    }
    fun getSubagentDefaultResolvedConfig(): ProviderConfig {
        return if (!subagentDefaultProfileEnabled) this else applyActiveProviderProfileOverrides(
            modelOverride = subagentDefaultModel,
            reasoningOverride = subagentDefaultReasoningEffort
        )
    }
    fun isStreamingResponsesEnabled(): Boolean = enableStreamingResponses
    fun isMultimodalEnabled(): Boolean = enableMultimodalMessages
    fun isModelAutoSelectionEnabled(providerId: String = activeProviderId): Boolean {
        if (!executionProfileAutoControlsInitialized) return autoUpgradeExecutionProfile
        return when (providerId) {
            "deepseek" -> deepseekAutoModelSelection
            "openai-compatible" -> openaiAutoModelSelection
            "claude" -> claudeAutoModelSelection
            else -> false
        }
    }
    fun isReasoningAutoSelectionEnabled(providerId: String = activeProviderId): Boolean {
        if (!executionProfileAutoControlsInitialized) return autoUpgradeExecutionProfile
        return when (providerId) {
            "deepseek" -> deepseekAutoReasoningEffort
            "openai-compatible" -> openaiAutoReasoningEffort
            "claude" -> claudeAutoReasoningEffort
            else -> false
        }
    }
    fun withModelAutoSelection(providerId: String, enabled: Boolean): ProviderConfig {
        val updated = when (providerId) {
            "deepseek" -> copy(deepseekAutoModelSelection = enabled)
            "openai-compatible" -> copy(openaiAutoModelSelection = enabled)
            "claude" -> copy(claudeAutoModelSelection = enabled)
            else -> this
        }
        return updated.copy(executionProfileAutoControlsInitialized = true)
    }
    fun withReasoningAutoSelection(providerId: String, enabled: Boolean): ProviderConfig {
        val updated = when (providerId) {
            "deepseek" -> copy(deepseekAutoReasoningEffort = enabled)
            "openai-compatible" -> copy(openaiAutoReasoningEffort = enabled)
            "claude" -> copy(claudeAutoReasoningEffort = enabled)
            else -> this
        }
        return updated.copy(executionProfileAutoControlsInitialized = true)
    }
    fun getActiveThinkingMode(): String? {
        val effort = getActiveReasoningEffort(); return if (effort.isNullOrBlank()) null else "reasoning/$effort"
    }
    fun getPromptPricePer1M(providerId: String = activeProviderId): Double = when (providerId) {
        "deepseek" -> deepseekPromptPricePer1M; "openai-compatible" -> openaiPromptPricePer1M
        "claude" -> claudePromptPricePer1M; else -> 0.0
    }
    fun getCompletionPricePer1M(providerId: String = activeProviderId): Double = when (providerId) {
        "deepseek" -> deepseekCompletionPricePer1M; "openai-compatible" -> openaiCompletionPricePer1M
        "claude" -> claudeCompletionPricePer1M; else -> 0.0
    }
    fun getPriceCurrency(providerId: String = activeProviderId): String = when (providerId) {
        "deepseek" -> "CNY"
        "openai-compatible" -> openaiBalanceCurrency.ifBlank { "USD" }.uppercase()
        "claude" -> claudeBalanceCurrency.ifBlank { "USD" }.uppercase()
        else -> "USD"
    }
    fun getBalanceUsd(providerId: String = activeProviderId): Double = when (providerId) {
        "deepseek" -> deepseekBalanceUsd; "openai-compatible" -> openaiBalanceUsd
        "claude" -> claudeBalanceUsd; else -> 0.0
    }
    fun getBalanceAmount(providerId: String = activeProviderId): Double = getBalanceUsd(providerId)
    fun getBalanceCurrency(providerId: String = activeProviderId): String = when (providerId) {
        "deepseek" -> deepseekBalanceCurrency.ifBlank { "CNY" }.uppercase()
        "openai-compatible" -> openaiBalanceCurrency.ifBlank { "USD" }.uppercase()
        "claude" -> claudeBalanceCurrency.ifBlank { "USD" }.uppercase()
        else -> "USD"
    }
    fun getBalanceSyncedAt(providerId: String = activeProviderId): Long? = when (providerId) {
        "deepseek" -> deepseekBalanceSyncedAt; "openai-compatible" -> openaiBalanceSyncedAt
        "claude" -> claudeBalanceSyncedAt; else -> null
    }
    fun getBalanceApiPath(providerId: String = activeProviderId): String = when (providerId) {
        "deepseek" -> ""; "openai-compatible" -> openaiBalanceApiPath; "claude" -> claudeBalanceApiPath; else -> ""
    }
    fun getGitHubApiBaseUrl(): String = githubApiBaseUrl
    fun getGitHubClientId(): String = githubClientId
    fun getGitHubClientSecret(): String = githubClientSecret
    fun getGitHubOAuthRedirectUri(): String = GITHUB_OAUTH_REDIRECT_URI
    fun getMurongBackendAuthApiUrl(): String = MURONG_BACKEND_AUTH_API_URL
    fun getMurongGitHubRedirectUri(): String = MURONG_APP_GITHUB_REDIRECT_URI
    fun getMurongReleasesApiUrl(): String = MURONG_BACKEND_RELEASES_API_URL
    fun getMurongUsageApiUrl(): String = MURONG_BACKEND_USAGE_API_URL
    fun getMurongDownloadsPageUrl(): String = MURONG_DOWNLOADS_PAGE_URL
    fun getMurongAppReleaseArtifactKey(): String = MURONG_APP_RELEASE_ARTIFACT_KEY
    fun getMurongExtensionReleaseArtifactKey(): String = MURONG_EXTENSION_RELEASE_ARTIFACT_KEY
    fun isAppUpdateSkipped(versionCode: Int?): Boolean {
        return versionCode != null &&
            skippedAppUpdateVersionCode != null &&
            versionCode == skippedAppUpdateVersionCode
    }
    fun isExtensionUpdateIgnored(versionCode: Int?): Boolean {
        return versionCode != null &&
            ignoredExtensionUpdateVersionCode != null &&
            versionCode == ignoredExtensionUpdateVersionCode
    }
    fun getNormalizedWebSearchBackendUrl(): String? = normalizeBaseUrl(webSearchSearxngBaseUrl)
    fun getTrimmedWebSearchApiKey(): String = webSearchBingApiKey.trim()
    fun isGitHubSignedIn(): Boolean = githubBackendSessionToken.isNotBlank() && githubToken.isNotBlank()
    private fun normalizeBaseUrl(raw: String?): String? {
        val trimmed = raw
            ?.trim()
            ?.trim('`', '"', '\'')
            .orEmpty()
        if (trimmed.isBlank()) return null
        if (trimmed.startsWith("/")) return null
        if ("://" !in trimmed) {
            val shouldUseHttp = trimmed.startsWith("localhost", ignoreCase = true) ||
                trimmed.startsWith("127.", ignoreCase = true) ||
                Regex("""^\d{1,3}(\.\d{1,3}){3}(:\d+)?(/.*)?$""").matches(trimmed)
            return if (shouldUseHttp) "http://$trimmed" else "https://$trimmed"
        }
        return trimmed
    }
    private fun applyActiveProviderProfileOverrides(
        modelOverride: String?,
        reasoningOverride: String?
    ): ProviderConfig {
        val normalizedModel = modelOverride?.trim().orEmpty().ifBlank { null }
        val normalizedReasoning = reasoningOverride?.trim().orEmpty().ifBlank { null }
        return when (activeProviderId) {
            "deepseek" -> {
                val targetModel = normalizedModel ?: deepseekModel
                val targetPreset = when (targetModel) {
                    "deepseek-v4-flash" -> "flash"
                    "deepseek-v4-pro" -> "pro"
                    else -> "custom"
                }
                copy(
                    deepseekModelPreset = targetPreset,
                    deepseekModel = targetModel,
                    deepseekReasoningEffort = normalizedReasoning ?: deepseekReasoningEffort
                )
            }
            "openai-compatible" -> copy(
                openaiModel = normalizedModel ?: openaiModel,
                openaiReasoningEffort = normalizedReasoning ?: openaiReasoningEffort
            )
            "claude" -> copy(
                claudeModel = normalizedModel ?: claudeModel,
                claudeReasoningEffort = normalizedReasoning ?: claudeReasoningEffort
            )
            else -> this
        }
    }
    fun withBalanceInfo(providerId: String, balanceUsd: Double, balanceCurrency: String, syncedAt: Long): ProviderConfig {
        val normalizedCurrency = balanceCurrency.ifBlank { getBalanceCurrency(providerId) }.uppercase()
        return when (providerId) {
            "deepseek" -> copy(deepseekBalanceUsd = balanceUsd, deepseekBalanceCurrency = normalizedCurrency, deepseekBalanceSyncedAt = syncedAt)
            "openai-compatible" -> copy(openaiBalanceUsd = balanceUsd, openaiBalanceCurrency = normalizedCurrency, openaiBalanceSyncedAt = syncedAt)
            "claude" -> copy(claudeBalanceUsd = balanceUsd, claudeBalanceCurrency = normalizedCurrency, claudeBalanceSyncedAt = syncedAt)
            else -> this
        }
    }
    fun estimateCostAmount(promptTokens: Int, completionTokens: Int, providerId: String = activeProviderId): Double {
        val promptCost = (promptTokens / 1_000_000.0) * getPromptPricePer1M(providerId)
        val completionCost = (completionTokens / 1_000_000.0) * getCompletionPricePer1M(providerId)
        return promptCost + completionCost
    }
    fun estimateCostCurrency(providerId: String = activeProviderId): String = getPriceCurrency(providerId)
    fun estimateCostUsd(promptTokens: Int, completionTokens: Int, providerId: String = activeProviderId): Double {
        return if (getPriceCurrency(providerId) == "USD") {
            estimateCostAmount(promptTokens, completionTokens, providerId)
        } else {
            0.0
        }
    }

    fun buildEffectiveSystemPrompt(): String {
        val enabledRules = globalRules.filter { it.enabled }
        val enabledMemories = globalMemories.filter { it.enabled }
        val enabledSkills = buildSkillsInstruction(skills = globalSkills, heading = "Active Global Skills")
        val rulesText = enabledRules.joinToString("\n\n") { "Rule: ${it.title}\n${it.content.trim()}" }
        val memoriesText = enabledMemories.joinToString("\n\n") { "Memory: ${it.title.ifBlank { "δ��������" }}\n${it.content.trim()}" }
        return buildString {
            append(systemPrompt.trim())
            val wfInstr = buildWorkflowInstruction()
            if (wfInstr.isNotEmpty()) append("\n\n$wfInstr")
            val verbosityInstr = buildResponseVerbosityInstruction()
            if (verbosityInstr.isNotEmpty()) append("\n\n$verbosityInstr")
            if (enabledRules.isNotEmpty()) append("\n\nActive Global Rules:\n$rulesText")
            if (enabledMemories.isNotEmpty()) append("\n\nActive Global Memories:\n$memoriesText")
            if (enabledSkills.isNotEmpty()) append("\n\n$enabledSkills")
            append(
                """

Global configuration management:
- If the user explicitly asks to save or add a reusable global rule, use `create_global_rule`.
- If the user explicitly asks to save or add a reusable global memory, use `create_global_memory`.
- If the user explicitly asks to import or save a reusable global skill, use `create_global_skill`.
- If the user explicitly asks to import or add an MCP server, use `create_mcp_server`.
- Never auto-create global rules, memories, skills, or MCP entries from ordinary conversation.
                """.trimIndent()
            )
        }
    }
    fun buildSkillsInstruction(skills: List<GlobalSkill>, heading: String): String {
        val enabled = skills.filter { it.enabled }; if (enabled.isEmpty()) return ""
        return buildString {
            appendLine("$heading:")
            enabled.forEach { appendLine(buildSkillInstruction(it)) }
        }.trimEnd()
    }
    private fun buildSkillInstruction(skill: GlobalSkill): String {
        val tools = skill.allowedTools.joinToString(", ").ifBlank { "all (no restriction)" }
        return buildString {
            append("- ${skill.title.ifBlank { "unnamed" }}")
            if (skill.description.isNotBlank()) append(": ${skill.description}")
            append(" [runAs=${skill.runAs}, tools=$tools]")
        }
    }
    private fun matchesToolTokenName(token: String, toolName: String): Boolean {
        val normalizedToken = token.trim().lowercase(); val normalizedToolName = toolName.trim().lowercase()
        val aliases = when (normalizedToolName) {
            "shell" -> setOf("shell", "command", "bash", "sh"); "file" -> setOf("file", "files", "filesystem")
            "code_edit" -> setOf("code_edit", "edit", "apply_diff")
            "code_search" -> setOf("code_search", "search_code", "grep", "rg", "ripgrep")
            "web_fetch" -> setOf("web_fetch", "fetch", "http")
            "web_search" -> setOf("web_search", "search"); else -> setOf(normalizedToolName)
        }
        return normalizedToken in aliases
    }
    private fun containsWritableFileBudget(token: String): Boolean {
        val normalized = token.trim().lowercase()
        return normalized in setOf("writable", "write", "allow_write", "write_access", "full")
    }

    fun buildWorkflowInstruction(): String = """
        Prefer completing the whole workflow in as few assistant turns as practical.
        Plan internally first, then batch related analysis and execution together.
        Keep the workflow efficient, but narrate meaningful progress in a user-friendly way.
        Only interrupt for user confirmation when information is missing, a decision is required, or an approval gate blocks execution.
        When a task is long or complex, aim to finish the full chain in one continuous run while still providing richer progress updates and a detailed final summary.
        After tool execution, do not stop abruptly. Explain what changed, what was learned, and what should happen next.
    """.trimIndent()

    fun buildResponseVerbosityInstruction(): String = when (responseVerbosity) {
        ResponseVerbosity.CONCISE -> """
            Response style: concise.
            Prefer short, direct answers and compact progress updates.
            Summarize tool results briefly and avoid extended explanation unless the user asks for more detail.
        """.trimIndent()
        ResponseVerbosity.BALANCED -> """
            Response style: balanced.
            Give a clear conclusion, the key findings, and the next step without being too terse or too verbose.
            Summarize tool results naturally and expand only where the extra context is useful.
        """.trimIndent()
        ResponseVerbosity.DETAILED -> """
            Response style: detailed.
            Prefer rich explanations, clearer progress narration, and a more complete final summary.
            After tool use, explain what was done, what changed, what was learned, and what should happen next.
        """.trimIndent()
    }

    fun shouldAutoRouteBeforeExecution(): Boolean = autoRouteBeforeExecution

    fun getFailureFallbackMode(): WorkflowFailureFallbackMode {
        return projectToolPreferences?.failureFallbackMode ?: WorkflowFailureFallbackMode.DIRECT_EXECUTION
    }

    fun getWorkflowFailureFallbackMode(failureType: WorkflowFailureType, context: WorkflowFailureFallbackContext = WorkflowFailureFallbackContext()): WorkflowFailureFallbackMode {
        return projectToolPreferences?.failureFallbackMode ?: WorkflowFailureFallbackMode.DIRECT_EXECUTION
    }

    fun isBuiltinToolEnabled(toolName: String): Boolean {
        val normalized = toolName.trim()
        val subagentPresetTools = setOf("explore", "research", "review", "security_review")
        val hasPresetOverrides = enabledBuiltinTools.any { it in subagentPresetTools }
        return when (normalized) {
            "subagent" -> "subagent_launch" in enabledBuiltinTools || "subagent" in enabledBuiltinTools
            "subagent_launch" -> "subagent_launch" in enabledBuiltinTools || "subagent" in enabledBuiltinTools
            in subagentPresetTools -> when {
                normalized in enabledBuiltinTools -> true
                hasPresetOverrides -> false
                else -> "subagent_launch" in enabledBuiltinTools || "subagent" in enabledBuiltinTools
            }
            else -> normalized in enabledBuiltinTools
        }
    }
    fun isMcpToolEnabled(toolName: String): Boolean = allowAllMcpTools || allowedMcpTools.contains(toolName)
    fun getEnabledFileToolOperations(): Set<String> = DEFAULT_ENABLED_FILE_TOOL_OPERATIONS.filter { it in enabledFileToolOperations }.toSet()
    fun isFileToolOperationEnabled(operation: String): Boolean = operation in getEnabledFileToolOperations()
    fun getEnabledSubagentFileOperations(): Set<String> = DEFAULT_SUBAGENT_FILE_TOOL_OPERATIONS.filter { isFileToolOperationEnabled(it) }.toSet()

    fun getNormalizedShellCommandPrefixes(): List<String> = allowedShellCommandPrefixes
        .map(::normalizeApprovalCommandPrefix).filter { it.isNotBlank() }.distinct()
    fun getNormalizedPathPrefixes(): List<String> = allowedPathPrefixes
        .map(::normalizeApprovalPathPrefix).filter { it.isNotBlank() }.distinct()

    fun evaluateApprovalRequirement(
        riskLevel: ApprovalRiskLevel, toolName: String,
        approvalScopeTokens: Set<String> = emptySet(),
        commandBoundaryValue: String? = null, pathBoundaryValue: String? = null
    ): ApprovalDecisionExplanation {
        return when (approvalMode) {
            ToolApprovalMode.READ_ONLY -> {
                if (toolName in setOf("file", "web_fetch", "web_search", "subagent_launch")) {
                    ApprovalDecisionExplanation(requiresApproval = false,
                        explanationLabel = "只读模式放行",
                        explanationDetail = "只读模式下仅允许文件读取、信息获取这类工具自动运行。"
                    )
                } else ApprovalDecisionExplanation(requiresApproval = true,
                    explanationLabel = "只读模式拦截",
                    explanationDetail = "只读模式下不允许直接执行修改类操作。"
                )
            }
            ToolApprovalMode.ALL_APPROVAL -> ApprovalDecisionExplanation(
                requiresApproval = true, explanationLabel = "全部审批",
                explanationDetail = "当前审批模式为全部审批，所有工具调用都需要人工确认。"
            )
            ToolApprovalMode.WHITELIST_AUTO -> {
                if (approvalScopeTokens.isNotEmpty()) ApprovalDecisionExplanation(
                    requiresApproval = true, explanationLabel = "涉及额外授权范围",
                    explanationDetail = "当前请求包含额外授权范围，仍需要人工确认。"
                ) else {
                    val toolWhitelisted = isBuiltinToolEnabled(toolName) || isMcpToolEnabled(toolName)
                    if (!toolWhitelisted) ApprovalDecisionExplanation(
                        requiresApproval = true, explanationLabel = "未命中白名单",
                        explanationDetail = "工具 `$toolName` 不在项目白名单内，需要人工审批。"
                    ) else when (toolName) {
                        "shell" -> {
                            val boundaries = getNormalizedShellCommandPrefixes()
                            if (boundaries.isEmpty()) ApprovalDecisionExplanation(requiresApproval = false,
                                explanationLabel = "白名单 Shell 已放行",
                                explanationDetail = "Shell 已在白名单工具范围内，且没有额外命令边界限制。"
                            ) else {
                                val matched = findMatchedShellCommandPrefix(commandBoundaryValue)
                                if (matched != null) ApprovalDecisionExplanation(requiresApproval = false,
                                    explanationLabel = "命中命令边界",
                                    explanationDetail = "Shell 命令命中了允许前缀 `$matched`，可直接执行。"
                                ) else ApprovalDecisionExplanation(requiresApproval = true,
                                    explanationLabel = "未命中命令边界",
                                    explanationDetail = "Shell 命令未命中允许前缀，需要人工审批。"
                                )
                            }
                        }
                        "file", "code_edit" -> {
                            val boundaries = getNormalizedPathPrefixes()
                            if (boundaries.isEmpty()) ApprovalDecisionExplanation(requiresApproval = false,
                                explanationLabel = "白名单文件操作已放行",
                                explanationDetail = "当前请求在白名单工具范围内，且没有额外路径边界限制。"
                            ) else {
                                val matched = findMatchedPathPrefix(pathBoundaryValue)
                                if (matched != null) ApprovalDecisionExplanation(requiresApproval = false,
                                    explanationLabel = "命中路径边界",
                                    explanationDetail = "路径命中了允许前缀 `$matched`，可直接执行。"
                                ) else ApprovalDecisionExplanation(requiresApproval = true,
                                    explanationLabel = "未命中路径边界",
                                    explanationDetail = "路径未命中允许前缀，需要人工审批。"
                                )
                            }
                        }
                        else -> ApprovalDecisionExplanation(requiresApproval = false,
                            explanationLabel = "白名单工具已放行",
                            explanationDetail = "当前工具已在白名单内，可直接执行。"
                        )
                    }
                }
            }
            ToolApprovalMode.ALL_AUTO -> ApprovalDecisionExplanation(
                requiresApproval = false, explanationLabel = "全部自动通过",
                explanationDetail = "当前模式为全部自动通过，默认不再弹出人工审批。"
            )
        }
    }

    fun requiresApproval(riskLevel: ApprovalRiskLevel, toolName: String, approvalScopeTokens: Set<String> = emptySet(),
                         commandBoundaryValue: String? = null, pathBoundaryValue: String? = null): Boolean =
        evaluateApprovalRequirement(riskLevel, toolName, approvalScopeTokens, commandBoundaryValue, pathBoundaryValue).requiresApproval

    fun applyProjectToolPreferences(preferences: ProjectToolPreferences?): ProviderConfig {
        if (preferences == null) return this
        return copy(
            workflowExecutionMode = preferences.workflowExecutionMode ?: workflowExecutionMode,
            autoRouteBeforeExecution = preferences.autoRouteBeforeExecution ?: autoRouteBeforeExecution,
            approvalMode = preferences.approvalMode ?: approvalMode,
            enabledBuiltinTools = preferences.enabledBuiltinTools ?: enabledBuiltinTools,
            enabledFileToolOperations = preferences.enabledFileToolOperations ?: enabledFileToolOperations,
            allowAllMcpTools = preferences.allowAllMcpTools ?: allowAllMcpTools,
            allowedMcpTools = preferences.allowedMcpTools ?: allowedMcpTools,
            allowedShellCommandPrefixes = preferences.allowedShellCommandPrefixes ?: allowedShellCommandPrefixes,
            allowedPathPrefixes = preferences.allowedPathPrefixes ?: allowedPathPrefixes
        )
    }

    private fun findMatchedShellCommandPrefix(command: String?): String? {
        val normalizedCommand = normalizeApprovalCommandPrefix(command)
        if (normalizedCommand.isBlank()) return null
        return getNormalizedShellCommandPrefixes().firstOrNull { prefix ->
            normalizedCommand == prefix || normalizedCommand.startsWith("$prefix ")
        }
    }
    private fun findMatchedPathPrefix(path: String?): String? {
        val normalizedPath = normalizeApprovalPathPrefix(path)
        if (normalizedPath.isBlank()) return null
        return getNormalizedPathPrefixes().firstOrNull { prefix ->
            normalizedPath == prefix || normalizedPath.startsWith("$prefix/")
        }
    }
}

private fun normalizeApprovalCommandPrefix(value: String?): String {
    return value.orEmpty().trim().removePrefix("\"").removeSuffix("\"").trim()
}
private fun normalizeApprovalPathPrefix(value: String?): String {
    return value.orEmpty().trim().replace('\\', '/').removeSuffix("/")
}

fun ProjectToolPreferences?.isUsingGlobalToolPreferences(): Boolean {
    if (this == null) return true
    return approvalMode == null && enabledBuiltinTools == null && enabledFileToolOperations == null &&
        allowAllMcpTools == null && allowedMcpTools == null && allowedShellCommandPrefixes == null &&
        allowedPathPrefixes == null && subagentTemplates == null
}

val DEFAULT_ENABLED_BUILTIN_TOOLS = listOf(
    "shell",
    "file",
    "code_edit",
    "code_search",
    "web_fetch",
    "web_search",
    "subagent_launch",
    "explore",
    "research",
    "review",
    "security_review"
)
val DEFAULT_ENABLED_FILE_TOOL_OPERATIONS = listOf("read", "list", "exists", "write", "delete", "chmod")
val DEFAULT_SUBAGENT_FILE_TOOL_OPERATIONS = listOf("read", "list", "exists", "write", "delete")
const val GITHUB_OAUTH_REDIRECT_URI = "murongagent://github/callback"
const val MURONG_BACKEND_AUTH_API_URL = "https://murongagent.rl1.cc/api/github_auth.php"
const val MURONG_APP_GITHUB_REDIRECT_URI = "murongagent://auth/github"
const val MURONG_BACKEND_RELEASES_API_URL = "https://murongagent.rl1.cc/api/releases.php"
const val MURONG_BACKEND_USAGE_API_URL = "https://murongagent.rl1.cc/api/usage.php"
const val MURONG_DOWNLOADS_PAGE_URL = "https://murongagent.rl1.cc/downloads.html"
const val MURONG_APP_RELEASE_ARTIFACT_KEY = "murongagent-app"
const val MURONG_EXTENSION_RELEASE_ARTIFACT_KEY = "murong-terminal-extension"
