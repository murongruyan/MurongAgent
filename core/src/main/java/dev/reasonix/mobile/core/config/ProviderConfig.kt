package dev.reasonix.mobile.core.config

import dev.reasonix.mobile.core.tool.ApprovalRiskLevel
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
    val deepseekBalanceUsd: Double = 0.0, val deepseekBalanceCurrency: String = "USD",
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
    val systemPrompt: String = """
        You are Reasonix Mobile, a coding assistant running on an Android device with root access. You have shell access and file system access.

        Default to a detailed, explanatory, highly communicative style.
        Do not be overly brief unless the user explicitly asks for a short answer.
        Explain what you are doing, why you are doing it, what you found, and what the result means.
        For coding and debugging tasks, prefer a short conclusion first, then key findings, then concrete changes or next steps, and finally any important risks or follow-up notes.
        When using tools, briefly narrate important intent and summarize the outcome in natural language after the tool result arrives.
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
    val responseVerbosity: ResponseVerbosity = ResponseVerbosity.DETAILED,
    val showDebugToolDetails: Boolean = false,
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
    fun getBalanceUsd(providerId: String = activeProviderId): Double = when (providerId) {
        "deepseek" -> deepseekBalanceUsd; "openai-compatible" -> openaiBalanceUsd
        "claude" -> claudeBalanceUsd; else -> 0.0
    }
    fun getBalanceCurrency(providerId: String = activeProviderId): String = when (providerId) {
        "deepseek" -> deepseekBalanceCurrency; "openai-compatible" -> "USD"; "claude" -> "USD"; else -> "USD"
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
    fun getReasonixBackendAuthApiUrl(): String = REASONIX_BACKEND_AUTH_API_URL
    fun getReasonixGitHubRedirectUri(): String = REASONIX_APP_GITHUB_REDIRECT_URI
    fun isGitHubSignedIn(): Boolean = githubBackendSessionToken.isNotBlank() && githubToken.isNotBlank()
    private fun normalizeBaseUrl(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
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
    fun withBalanceInfo(providerId: String, balanceUsd: Double, balanceCurrency: String, syncedAt: Long): ProviderConfig {
        return when (providerId) {
            "deepseek" -> copy(deepseekBalanceUsd = balanceUsd, deepseekBalanceCurrency = balanceCurrency, deepseekBalanceSyncedAt = syncedAt)
            "openai-compatible" -> copy(openaiBalanceUsd = balanceUsd, openaiBalanceCurrency = balanceCurrency, openaiBalanceSyncedAt = syncedAt)
            "claude" -> copy(claudeBalanceUsd = balanceUsd, claudeBalanceCurrency = balanceCurrency, claudeBalanceSyncedAt = syncedAt)
            else -> this
        }
    }
    fun estimateCostUsd(promptTokens: Int, completionTokens: Int, providerId: String = activeProviderId): Double {
        val promptCost = (promptTokens / 1_000_000.0) * getPromptPricePer1M(providerId)
        val completionCost = (completionTokens / 1_000_000.0) * getCompletionPricePer1M(providerId)
        return promptCost + completionCost
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
        }
    }
    fun buildSkillsInstruction(skills: List<GlobalSkill>, heading: String): String {
        val enabled = skills.filter { it.enabled }; if (enabled.isEmpty()) return ""
        return buildString {
            appendLine("\n$heading:")
            enabled.forEach { appendLine(buildSkillInstruction(it)) }
        }
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
            "code_edit" -> setOf("code_edit", "edit", "apply_diff"); "web_fetch" -> setOf("web_fetch", "fetch", "http")
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
        return when (normalized) {
            "subagent" -> "subagent_launch" in enabledBuiltinTools || "subagent" in enabledBuiltinTools
            "subagent_launch" -> "subagent_launch" in enabledBuiltinTools || "subagent" in enabledBuiltinTools
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
                        explanationLabel = "ֻ��ģʽ����",
                        explanationDetail = "ֻ��ģʽ�½������ļ���ȡ����Ϣ��ȡ���ߡ�"
                    )
                } else ApprovalDecisionExplanation(requiresApproval = true,
                    explanationLabel = "ֻ��ģʽ��ֹ",
                    explanationDetail = "ֻ��ģʽ�²�����ִ���޸Ĳ�����"
                )
            }
            ToolApprovalMode.ALL_APPROVAL -> ApprovalDecisionExplanation(
                requiresApproval = true, explanationLabel = "ȫ������",
                explanationDetail = "��ǰ����ģʽΪȫ�����������й����������˹�ȷ�ϡ�"
            )
            ToolApprovalMode.WHITELIST_AUTO -> {
                if (approvalScopeTokens.isNotEmpty()) ApprovalDecisionExplanation(
                    requiresApproval = true, explanationLabel = "�Ӵ�����Ȩ���˹�����",
                    explanationDetail = "��ǰ�������������Ȩ��Χ�����˹�ȷ�ϡ�"
                ) else {
                    val toolWhitelisted = isBuiltinToolEnabled(toolName) || isMcpToolEnabled(toolName)
                    if (!toolWhitelisted) ApprovalDecisionExplanation(
                        requiresApproval = true, explanationLabel = "δ���а�����",
                        explanationDetail = "���� `$toolName` ������Ŀ�������ڣ����˹�������"
                    ) else when (toolName) {
                        "shell" -> {
                            val boundaries = getNormalizedShellCommandPrefixes()
                            if (boundaries.isEmpty()) ApprovalDecisionExplanation(requiresApproval = false,
                                explanationLabel = "���� Shell ������",
                                explanationDetail = "shell ���ڰ���������������߽����ƣ�ֱ�ӷ��С�"
                            ) else {
                                val matched = findMatchedShellCommandPrefix(commandBoundaryValue)
                                if (matched != null) ApprovalDecisionExplanation(requiresApproval = false,
                                    explanationLabel = "��������߽�",
                                    explanationDetail = "Shell ��������������ǰ׺ `$matched`��ֱ�ӷ��С�"
                                ) else ApprovalDecisionExplanation(requiresApproval = true,
                                    explanationLabel = "δ��������߽�",
                                    explanationDetail = "Shell ����δ��������ǰ׺�����˹�������"
                                )
                            }
                        }
                        "file", "code_edit" -> {
                            val boundaries = getNormalizedPathPrefixes()
                            if (boundaries.isEmpty()) ApprovalDecisionExplanation(requiresApproval = false,
                                explanationLabel = "�����ļ�������",
                                explanationDetail = "�������ڰ�����������·���߽����ƣ�ֱ�ӷ��С�"
                            ) else {
                                val matched = findMatchedPathPrefix(pathBoundaryValue)
                                if (matched != null) ApprovalDecisionExplanation(requiresApproval = false,
                                    explanationLabel = "����·���߽�",
                                    explanationDetail = "·������������ǰ׺ `$matched`��ֱ�ӷ��С�"
                                ) else ApprovalDecisionExplanation(requiresApproval = true,
                                    explanationLabel = "δ����·���߽�",
                                    explanationDetail = "·��δ��������ǰ׺�����˹�������"
                                )
                            }
                        }
                        else -> ApprovalDecisionExplanation(requiresApproval = false,
                            explanationLabel = "���й��߰�����",
                            explanationDetail = "�������ڰ������ڣ�ֱ�ӷ��С�"
                        )
                    }
                }
            }
            ToolApprovalMode.ALL_AUTO -> ApprovalDecisionExplanation(
                requiresApproval = false, explanationLabel = "ȫ������",
                explanationDetail = "����ģʽΪȫ�����У��������󲻽����˹�������"
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

val DEFAULT_ENABLED_BUILTIN_TOOLS = listOf("shell", "file", "code_edit", "web_fetch", "web_search", "subagent_launch")
val DEFAULT_ENABLED_FILE_TOOL_OPERATIONS = listOf("read", "list", "exists", "write", "delete", "chmod")
val DEFAULT_SUBAGENT_FILE_TOOL_OPERATIONS = listOf("read", "list", "exists", "write", "delete")
const val GITHUB_OAUTH_REDIRECT_URI = "reasonix://github/callback"
const val REASONIX_BACKEND_AUTH_API_URL = "https://murongdiaodu.rl1.cc/api/reasonix_auth.php"
const val REASONIX_APP_GITHUB_REDIRECT_URI = "reasonix://auth/github"
