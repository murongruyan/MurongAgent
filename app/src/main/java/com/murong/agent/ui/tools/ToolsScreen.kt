@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.murong.agent.ui.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import com.murong.agent.ui.sanitizeForUiDisplay
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.murong.agent.core.config.DEFAULT_ENABLED_FILE_TOOL_OPERATIONS
import com.murong.agent.core.config.GuiInferenceMode
import com.murong.agent.core.config.ProjectToolPreferences
import com.murong.agent.core.config.ProviderConfig
import com.murong.agent.core.config.ToolApprovalMode
import com.murong.agent.core.config.WorkflowFailureFallbackMode
import com.murong.agent.core.config.WorkflowFailureType
import com.murong.agent.core.config.WorkflowExecutionMode
import com.murong.agent.core.config.approvalModeLabel
import com.murong.agent.core.config.toApprovalModePresentation
import com.murong.agent.core.automation.SavedWorkflowDefinition
import com.murong.agent.core.automation.SavedWorkflowTemplate
import com.murong.agent.core.automation.backgroundEligibility
import com.murong.agent.core.automation.SavedWorkflowBackgroundEligibility
import com.murong.agent.core.automation.defaultNodes
import com.murong.agent.core.automation.validate
import com.murong.agent.core.doctor.SensitiveDataSanitizer
import com.murong.agent.core.loop.ConversationCheckpointScope
import com.murong.agent.core.loop.CheckpointRecoveryRecordUi
import com.murong.agent.core.loop.FinalReadinessAuditOverview
import com.murong.agent.core.loop.FinalReadinessAuditRecord
import com.murong.agent.core.loop.ErrorRecordKind
import com.murong.agent.core.loop.ErrorRecordUi
import com.murong.agent.core.loop.ToolCallRecordUi
import com.murong.agent.core.loop.buildFinalReadinessAuditOverview
import com.murong.agent.core.provider.BuiltinLocalProvider
import com.murong.agent.core.provider.ProviderRegistry
import com.murong.agent.core.mcp.McpConfigSource
import com.murong.agent.core.mcp.McpServerConfig
import com.murong.agent.core.mcp.McpServerStatus
import com.murong.agent.core.mcp.McpTransportType
import com.murong.agent.core.mcp.canonicalMcpToolName
import com.murong.agent.core.tool.AndroidGuiAccessibilityAccess
import com.murong.agent.core.tool.AndroidGuiAccessibilityService
import com.murong.agent.core.tool.BuiltinLocalComputeBackend
import com.murong.agent.core.tool.BuiltinLocalCpuCorePolicy
import com.murong.agent.core.tool.BuiltinVisionEngine
import com.murong.agent.core.tool.BuiltinVisionModelManager
import com.murong.agent.core.tool.BuiltinVisionModels
import com.murong.agent.core.tool.BuiltinVisionRuntime
import com.murong.agent.core.tool.BuiltinVisionTier
import com.murong.agent.core.tool.RootAccessibilityEnableResult
import com.murong.agent.automation.ExternalWorkflowContract
import com.murong.agent.ui.settings.ExternalWorkflowAutomationUiState
import com.murong.agent.ui.PendingApprovalSummaryCard
import com.murong.agent.ui.MurongDialog
import com.murong.agent.ui.MurongGlassSurface
import com.murong.agent.ui.MurongInfoCard
import com.murong.agent.ui.MurongInteractionPerformanceHint
import com.murong.agent.ui.MurongLargeDialogCardShape
import com.murong.agent.ui.MurongLargeDialogScaffold
import com.murong.agent.ui.MurongPopupSurface
import com.murong.agent.ui.MurongPopupCardShape
import com.murong.agent.ui.MurongPrimaryPageSurface
import com.murong.agent.ui.buildApprovalModeOptionPresentations
import com.murong.agent.ui.buildApprovalPostureCopyPresentation
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

private data class ToolEntry(
    val name: String,
    val title: String,
    val description: String,
    val status: String
)

internal enum class ToolsSection(val label: String) {
    BUILTIN("内置工具"),
    PHONE("手机操作"),
    AUTOMATION("自动化与 MCP"),
    APPROVALS("审批策略"),
    ACTIVITY("执行记录")
}

internal data class PhoneAgentModelOption(
    val providerId: String?,
    val relayId: String,
    val title: String,
    val subtitle: String,
    val enabled: Boolean = true
) {
    val key: String
        get() = providerId?.let { "$it:$relayId" } ?: "follow-chat"
}

internal fun buildPhoneAgentModelOptions(
    config: ProviderConfig,
    installedTiers: Set<BuiltinVisionTier>
): List<PhoneAgentModelOption> {
    val currentProfile = if (config.usesCodexChatGptBackend()) {
        "ChatGPT / Codex · ${config.codexModel.trim().ifBlank { "账号默认模型" }}"
    } else {
        val provider = ProviderRegistry.getActiveProvider(config.activeProviderId)
        "${provider.name} · ${provider.formatModelDisplayName(config.getActiveModel())}"
    }
    val currentChatSupportsVision = config.usesCodexChatGptBackend() ||
        config.activeProviderId != BuiltinLocalProvider.ID ||
        BuiltinVisionRuntime.model(config.getActiveModel())?.supportsVision == true
    return buildList {
        add(
            PhoneAgentModelOption(
                providerId = null,
                relayId = "",
                title = "跟随当前聊天模型",
                subtitle = if (currentChatSupportsVision) {
                    currentProfile
                } else {
                    "$currentProfile · 当前是纯文本模型，不能读取手机截图"
                },
                enabled = currentChatSupportsVision,
            )
        )
        installedTiers
            .map(BuiltinVisionModels::descriptor)
            .sortedByDescending { it.supportsVision }
            .forEach { descriptor ->
                add(
                    PhoneAgentModelOption(
                        providerId = BuiltinLocalProvider.ID,
                        relayId = descriptor.id,
                        title = "内置本地模型 · ${descriptor.displayName}",
                        subtitle = if (descriptor.supportsVision) {
                            "已安装 · 截图只在本机处理；不会更改聊天当前使用的模型"
                        } else {
                            "已安装，但它是纯文本模型，不能读取手机截图"
                        },
                        enabled = descriptor.supportsVision
                    )
                )
            }
        ProviderRegistry.getAllProviders().forEach { provider ->
            config.getRelayConfigs(provider.id)
                .filter { relay -> config.isRelayConfigured(provider.id, relay) }
                .forEach { relay ->
                    val model = relay.model.trim().ifBlank {
                        provider.defaultModel
                    }
                    add(
                        PhoneAgentModelOption(
                            providerId = provider.id,
                            relayId = relay.id,
                            title = "${provider.name} · ${provider.formatModelDisplayName(model)}",
                            subtitle = "${config.configuredConnectionLabel(provider.id, relay)} · 需支持图片输入"
                        )
                    )
                }
        }
    }
}

internal fun buildAssistantCodeModelOptions(
    config: ProviderConfig,
    installedTiers: Set<BuiltinVisionTier>,
): List<PhoneAgentModelOption> {
    val currentProfile = if (config.usesCodexChatGptBackend()) {
        "ChatGPT / Codex · ${config.codexModel.trim().ifBlank { "账号默认模型" }}"
    } else {
        val provider = ProviderRegistry.getActiveProvider(config.activeProviderId)
        "${provider.name} · ${provider.formatModelDisplayName(config.getActiveModel())}"
    }
    return buildList {
        add(
            PhoneAgentModelOption(
                providerId = null,
                relayId = "",
                title = "跟随当前聊天模型",
                subtitle = currentProfile,
            ),
        )
        installedTiers
            .map(BuiltinVisionModels::descriptor)
            .sortedWith(
                compareByDescending<com.murong.agent.core.tool.BuiltinVisionModelDescriptor> {
                    !it.supportsVision
                }.thenByDescending { it.estimatedDownloadBytes },
            )
            .forEach { descriptor ->
                add(
                    PhoneAgentModelOption(
                        providerId = BuiltinLocalProvider.ID,
                        relayId = descriptor.id,
                        title = "内置本地模型 · ${descriptor.displayName}",
                        subtitle = if (descriptor.supportsVision) {
                            "已安装 · 支持文本、图片和代码"
                        } else {
                            "已安装 · 纯文本/代码"
                        },
                    ),
                )
            }
        ProviderRegistry.getAllProviders().forEach { provider ->
            config.getRelayConfigs(provider.id)
                .filter { relay -> config.isRelayConfigured(provider.id, relay) }
                .forEach { relay ->
                    val model = relay.model.trim().ifBlank { provider.defaultModel }
                    add(
                        PhoneAgentModelOption(
                            providerId = provider.id,
                            relayId = relay.id,
                            title = "${provider.name} · ${provider.formatModelDisplayName(model)}",
                            subtitle = config.configuredConnectionLabel(provider.id, relay),
                        ),
                    )
                }
        }
    }
}

private data class SkillUsageAuditSummary(
    val totalCount: Int,
    val recentSkillTitles: List<String>,
    val recentTasks: List<String>
)

@Composable
internal fun ToolsScreen(
    config: ProviderConfig,
    requestedSection: ToolsSection = ToolsSection.BUILTIN,
    sectionRequestSignal: Int = 0,
    currentProjectPath: String?,
    projectRuleCount: Int,
    projectMemoryCount: Int,
    projectSkillCount: Int,
    rootStatus: Boolean?,
    isCheckingRoot: Boolean,
    onCheckRoot: () -> Unit,
    approvalPresentation: ApprovalToolsPresentation,
    checkpointPresentation: CheckpointToolsPresentation,
    recentFinalReadinessAudits: List<FinalReadinessAuditRecord>,
    recentErrors: List<ErrorRecordUi>,
    recentToolCalls: List<ToolCallRecordUi>,
    onOpenChat: () -> Unit,
    onOpenApprovalDetail: () -> Unit,
    onApprovePendingTool: () -> Unit,
    onRejectPendingTool: () -> Unit,
    onRollbackCheckpoint: (String, ConversationCheckpointScope) -> Unit,
    onForkCheckpointSession: (String) -> Unit,
    mcpServers: List<McpServerConfig>,
    mcpStatuses: List<McpServerStatus>,
    mcpConnectError: String?,
    onConnectMcpServers: () -> Unit,
    onRefreshMcpStatus: () -> Unit,
    savedWorkflows: List<SavedWorkflowDefinition>,
    externalWorkflowAutomationState: ExternalWorkflowAutomationUiState,
    onSaveSavedWorkflow: (SavedWorkflowDefinition) -> Unit,
    onDeleteSavedWorkflow: (String) -> Unit,
    onRunSavedWorkflowNow: (String, Boolean) -> Unit,
    onRefreshSavedWorkflows: () -> Unit,
    onEnableExternalWorkflowAutomation: () -> Unit,
    onDisableExternalWorkflowAutomation: () -> Unit,
    onRotateExternalWorkflowToken: () -> Unit,
    onClearOneTimeExternalWorkflowToken: () -> Unit,
    onUpdateConfig: (ProviderConfig) -> Unit
) {
    val bottomBarScrollPadding = 24.dp
    val toolsListState = rememberLazyListState()
    MurongInteractionPerformanceHint(active = toolsListState.isScrollInProgress)
    val pendingApprovalPresentation = approvalPresentation.pendingApproval
    var showApprovalPolicyEditor by remember { mutableStateOf(false) }
    var showWorkflowExecutionEditor by remember { mutableStateOf(false) }
    var showInlineToolAccess by remember { mutableStateOf(false) }
    var showSubagentGroup by remember { mutableStateOf(false) }
    var selectedCheckpointId by remember { mutableStateOf<String?>(null) }
    var selectedRecordId by remember { mutableStateOf<String?>(null) }
    var selectedRecoveryId by remember { mutableStateOf<String?>(null) }
    var showRecoveryTimeline by remember { mutableStateOf(false) }
    var selectedToolCall by remember { mutableStateOf<ToolCallRecordUi?>(null) }
    var selectedError by remember { mutableStateOf<ErrorRecordUi?>(null) }
    var selectedMcpServerName by remember { mutableStateOf<String?>(null) }
    var editingSavedWorkflow by remember { mutableStateOf<SavedWorkflowDefinition?>(null) }
    var showSavedWorkflowEditor by remember { mutableStateOf(false) }
    var pendingForegroundWorkflow by remember { mutableStateOf<SavedWorkflowDefinition?>(null) }
    var guiAccessibilityConnected by remember {
        mutableStateOf(AndroidGuiAccessibilityService.isConnected())
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    val subagentPresetNames = remember { listOf("explore", "research", "review", "security_review") }
    val mcpConfigsByName = remember(mcpServers) { mcpServers.associateBy { it.name } }

    LaunchedEffect(Unit) { onRefreshSavedWorkflows() }
    LaunchedEffect(sectionRequestSignal, requestedSection) {
        toolsListState.scrollToItem(0)
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                guiAccessibilityConnected = AndroidGuiAccessibilityService.isConnected()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun updateBuiltinToolEnabled(toolName: String, enabled: Boolean) {
        val updated = config.enabledBuiltinTools.toMutableSet()
        when (toolName) {
            "subagent" -> {
                if (enabled) {
                    updated.add("subagent_launch")
                    updated.addAll(subagentPresetNames)
                } else {
                    updated.remove("subagent")
                    updated.remove("subagent_launch")
                    updated.removeAll(subagentPresetNames.toSet())
                }
            }
            in subagentPresetNames -> {
                if (enabled) {
                    updated.add("subagent_launch")
                    updated.add(toolName)
                } else {
                    updated.remove(toolName)
                }
            }
            else -> {
                if (enabled) updated.add(toolName) else updated.remove(toolName)
            }
        }
        onUpdateConfig(config.copy(enabledBuiltinTools = updated.sorted()))
    }

    fun updateFileOperationEnabled(operation: String, enabled: Boolean) {
        val updated = config.enabledFileToolOperations.toMutableSet()
        if (enabled) updated.add(operation) else updated.remove(operation)
        onUpdateConfig(config.copy(enabledFileToolOperations = updated.sorted()))
    }

    val mcpToolNames = remember(mcpStatuses) {
        mcpStatuses.flatMap { status ->
            status.toolNames.map { canonicalMcpToolName(status.name, it) }
        }.distinct().sorted()
    }
    val builtInTools = remember(
        config,
        pendingApprovalPresentation,
        checkpointPresentation.fileChanges,
        guiAccessibilityConnected,
        rootStatus
    ) {
        listOf(
            ToolEntry(
                name = "file",
                title = "文件工具",
                description = "当前开放 ${config.getEnabledFileToolOperations().size}/${DEFAULT_ENABLED_FILE_TOOL_OPERATIONS.size} 个文件操作。",
                status = when {
                    !config.isBuiltinToolEnabled("file") -> "已禁用"
                    checkpointPresentation.fileChanges.isNotEmpty() ->
                        "最近改动 ${checkpointPresentation.fileChanges.size}"
                    else -> "已启用"
                }
            ),
            ToolEntry(
                name = "code_edit",
                title = "代码编辑",
                description = "按行查看和替换文件内容。",
                status = if (config.isBuiltinToolEnabled("code_edit")) "已启用" else "已禁用"
            ),
            ToolEntry(
                name = "shell",
                title = "命令工具",
                description = "执行 shell 指令，支持审批拦截。",
                status = when {
                    !config.isBuiltinToolEnabled("shell") -> "已禁用"
                    pendingApprovalPresentation?.toolName == "shell" -> "等待审批"
                    else -> "已启用"
                }
            ),
            ToolEntry(
                name = "gui",
                title = "界面操作",
                description = "优先读取 Android Accessibility 语义树，Root/UIAutomator 与视觉识别作为后备。",
                status = when {
                    !config.isBuiltinToolEnabled("gui") -> "已禁用"
                    guiAccessibilityConnected -> "无障碍已连接"
                    rootStatus == true -> "使用 Root 后备"
                    else -> "待开启无障碍"
                }
            ),
            ToolEntry(
                name = "web_search",
                title = "联网搜索",
                description = "联网搜索与网页抓取链路。",
                status = if (config.isBuiltinToolEnabled("web_search")) "已启用" else "已禁用"
            ),
            ToolEntry(
                name = "web_fetch",
                title = "网页抓取",
                description = "抓取单个网页并提取标题、摘要和正文。",
                status = if (config.isBuiltinToolEnabled("web_fetch")) "已启用" else "已禁用"
            ),
            ToolEntry(
                name = "subagent",
                title = "子代理",
                description = "子任务分发与摘要回传。",
                status = if (config.isBuiltinToolEnabled("subagent")) "已启用" else "已禁用"
            )
        )
    }
    val subagentPresetTools = remember(config) {
        listOf(
            ToolEntry(
                name = "explore",
                title = "探索代理",
                description = "子代理预设：快速摸清代码结构、关键文件和调用链。",
                status = if (config.isBuiltinToolEnabled("explore")) "已启用" else "已禁用"
            ),
            ToolEntry(
                name = "research",
                title = "研究代理",
                description = "子代理预设：偏文档、网页和方案调研。",
                status = if (config.isBuiltinToolEnabled("research")) "已启用" else "已禁用"
            ),
            ToolEntry(
                name = "review",
                title = "审查代理",
                description = "子代理预设：偏 bug、回归和实现风险审查。",
                status = if (config.isBuiltinToolEnabled("review")) "已启用" else "已禁用"
            ),
            ToolEntry(
                name = "security_review",
                title = "安全审查代理",
                description = "子代理预设：偏权限边界、漏洞面和安全风险检查。",
                status = if (config.isBuiltinToolEnabled("security_review")) "已启用" else "已禁用"
            )
        )
    }

    MurongPrimaryPageSurface(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 2.dp)
    ) {
        LazyColumn(
            state = toolsListState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 6.dp,
                top = 8.dp,
                end = 6.dp,
                bottom = bottomBarScrollPadding
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
                when (requestedSection) {
                    ToolsSection.BUILTIN -> {
                        item {
                            SectionTitleWithAction(
                                title = "内置工具",
                                subtitle = "开关与权限",
                                action = {
                                    TextButton(
                                        onClick = { showInlineToolAccess = !showInlineToolAccess }
                                    ) {
                                        Text(if (showInlineToolAccess) "收起权限" else "权限")
                                    }
                                }
                            )
                        }
                        item("builtin-tools") {
                            CompactBuiltInToolsPanel(
                                tools = builtInTools,
                                subagentPresets = subagentPresetTools,
                                isEnabled = config::isBuiltinToolEnabled,
                                subagentExpanded = showSubagentGroup,
                                onSubagentExpandedChange = { showSubagentGroup = it },
                                onToggle = ::updateBuiltinToolEnabled
                            )
                        }
                        if (showInlineToolAccess) {
                            item("inline-tool-access") {
                                ToolAccessInlineCard(
                                    config = config,
                                    mcpToolNames = mcpToolNames,
                                    onFileOperationToggle = ::updateFileOperationEnabled,
                                    onAllowAllMcpToggle = { enabled ->
                                        onUpdateConfig(config.copy(allowAllMcpTools = enabled))
                                    },
                                    onMcpToolToggle = { toolName, enabled ->
                                        val updated = config.allowedMcpTools.toMutableSet()
                                        if (enabled) updated.add(toolName) else updated.remove(toolName)
                                        onUpdateConfig(config.copy(allowedMcpTools = updated.sorted()))
                                    }
                                )
                            }
                        }
                    }

                    ToolsSection.PHONE -> {
                        item { SectionTitle("手机操作", "无障碍、Root 与视觉识别设置") }
                        item("gui-automation-settings") {
                            GuiAutomationSettingsCard(
                                config = config,
                                accessibilityConnected = guiAccessibilityConnected,
                                rootAvailable = rootStatus == true,
                                onRefreshAccessibility = {
                                    guiAccessibilityConnected = AndroidGuiAccessibilityService.isConnected()
                                },
                                onUpdateConfig = onUpdateConfig
                            )
                        }
                    }

                    ToolsSection.AUTOMATION -> {
                        item { SectionTitle("自动化", "工作流与 MCP 连接") }
                        item {
                            WorkflowCard(
                                config = config,
                                onManageWorkflow = { showWorkflowExecutionEditor = true },
                                onManageApproval = { showApprovalPolicyEditor = true }
                            )
                        }
                        item {
                            SavedWorkflowCard(
                                workflows = savedWorkflows,
                                externalAutomationState = externalWorkflowAutomationState,
                                onCreate = {
                                    editingSavedWorkflow = null
                                    showSavedWorkflowEditor = true
                                },
                                onEdit = {
                                    editingSavedWorkflow = it
                                    showSavedWorkflowEditor = true
                                },
                                onRunNow = { workflow ->
                                    if (
                                        workflow.backgroundEligibility() ==
                                        SavedWorkflowBackgroundEligibility.ALLOWED_READ_ONLY
                                    ) {
                                        onRunSavedWorkflowNow(workflow.id, false)
                                    } else {
                                        pendingForegroundWorkflow = workflow
                                    }
                                },
                                onDelete = onDeleteSavedWorkflow,
                                onEnableExternalAutomation = onEnableExternalWorkflowAutomation,
                                onDisableExternalAutomation = onDisableExternalWorkflowAutomation,
                                onRotateExternalToken = onRotateExternalWorkflowToken
                            )
                        }
                        item {
                            McpCard(
                                mcpServers = mcpServers,
                                mcpStatuses = mcpStatuses,
                                mcpConnectError = mcpConnectError,
                                onConnectMcpServers = onConnectMcpServers,
                                onRefreshMcpStatus = onRefreshMcpStatus,
                                onOpenStatus = { selectedMcpServerName = it }
                            )
                        }
                    }

                    ToolsSection.APPROVALS -> {
                        item { SectionTitle("审批", "策略、待处理请求与项目偏好") }
                        item {
                            ApprovalPostureCard(overview = approvalPresentation.postureOverview)
                        }
                        item {
                            ApprovalCard(
                                cardPresentation = approvalPresentation.approvalCard,
                                onOpenChat = onOpenChat,
                                onOpenDetail = onOpenApprovalDetail,
                                onApprove = onApprovePendingTool,
                                onReject = onRejectPendingTool
                            )
                        }
                        item {
                            ProjectApprovalCard(
                                cardPresentation = approvalPresentation.projectApprovalCard
                            )
                        }
                        item {
                            ProjectPreferenceCard(
                                currentProjectPath = currentProjectPath,
                                projectRuleCount = projectRuleCount,
                                projectMemoryCount = projectMemoryCount,
                                projectSkillCount = projectSkillCount
                            )
                        }
                    }

                    ToolsSection.ACTIVITY -> {
                        item { SectionTitle("执行记录", "审计、错误、文件改动与检查点") }
                        item {
                            AuditCard(
                                recentFinalReadinessAudits = recentFinalReadinessAudits,
                                recentToolCalls = recentToolCalls,
                                recentErrors = recentErrors,
                                onOpenToolCall = { selectedToolCall = it },
                                onOpenError = { selectedError = it }
                            )
                        }
                        item {
                            FileChangeCard(
                                presentation = checkpointPresentation,
                                onOpenCheckpoint = { selectedCheckpointId = it },
                                onOpenRecord = { selectedRecordId = it },
                                onOpenRecovery = { selectedRecoveryId = it },
                                onOpenRecoveryTimeline = { showRecoveryTimeline = true },
                                onRollbackCheckpoint = onRollbackCheckpoint
                            )
                        }
                    }
                }
                item {
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }
    }

    if (showApprovalPolicyEditor) {
        ApprovalPolicyEditorDialog(
            config = config,
            onDismiss = { showApprovalPolicyEditor = false },
            onSave = { updatedConfig ->
                showApprovalPolicyEditor = false
                onUpdateConfig(updatedConfig)
            }
        )
    }
    if (showWorkflowExecutionEditor) {
        WorkflowExecutionPreferenceDialog(
            currentMode = config.workflowExecutionMode,
            initialAutoRouteEnabled = config.autoRouteBeforeExecution,
            initialFallbackMode = config.getFailureFallbackMode(),
            onDismiss = { showWorkflowExecutionEditor = false },
            onSave = { mode, autoRouteEnabled, fallbackMode ->
                showWorkflowExecutionEditor = false
                onUpdateConfig(
                    config.copy(
                        workflowExecutionMode = mode,
                        autoRouteBeforeExecution = autoRouteEnabled,
                        projectToolPreferences = (
                            config.projectToolPreferences ?: ProjectToolPreferences()
                        ).copy(failureFallbackMode = fallbackMode)
                    )
                )
            }
        )
    }
    selectedCheckpointId
        ?.let { checkpointId -> findCheckpointToolPresentation(checkpointPresentation, checkpointId) }
        ?.let { checkpoint ->
            CheckpointDetailSheet(
                checkpoint = checkpoint,
                records = resolveCheckpointRecordPresentations(checkpointPresentation, checkpoint),
                onDismiss = { selectedCheckpointId = null },
                onRollbackCheckpoint = {
                    selectedCheckpointId = null
                    onRollbackCheckpoint(checkpoint.id, checkpoint.rollbackScope)
                },
                onForkCheckpoint = {
                    selectedCheckpointId = null
                    onForkCheckpointSession(checkpoint.id)
                },
                onOpenRecord = { recordId -> selectedRecordId = recordId }
            )
        }
    selectedRecordId
        ?.let { recordId -> findFileChangeToolPresentation(checkpointPresentation, recordId) }
        ?.let { record ->
            FileChangeDetailSheet(record = record, onDismiss = { selectedRecordId = null })
        }
    selectedRecoveryId
        ?.let { recordId -> findCheckpointRecoveryToolPresentation(checkpointPresentation, recordId) }
        ?.let { record ->
            RecoveryDetailSheet(
                record = record,
                onDismiss = { selectedRecoveryId = null },
                onOpenCheckpoint = record.checkpointId?.let { checkpointId ->
                    {
                        selectedRecoveryId = null
                        selectedCheckpointId = checkpointId
                    }
                }
            )
        }
    if (showRecoveryTimeline && checkpointPresentation.recoveries.isNotEmpty()) {
        RecoveryTimelineSheet(
            records = checkpointPresentation.recoveries,
            onDismiss = { showRecoveryTimeline = false },
            onOpenRecovery = { recoveryId ->
                showRecoveryTimeline = false
                selectedRecoveryId = recoveryId
            }
        )
    }
    if (showSavedWorkflowEditor) {
        SavedWorkflowEditorDialog(
            initial = editingSavedWorkflow,
            currentProjectPath = currentProjectPath,
            onDismiss = {
                editingSavedWorkflow = null
                showSavedWorkflowEditor = false
            },
            onSave = { workflow ->
                onSaveSavedWorkflow(workflow)
                editingSavedWorkflow = null
                showSavedWorkflowEditor = false
            }
        )
    }
    pendingForegroundWorkflow?.let { workflow ->
        ForegroundSavedWorkflowConfirmationDialog(
            workflow = workflow,
            onDismiss = {
                pendingForegroundWorkflow = null
            },
            onConfirm = {
                pendingForegroundWorkflow = null
                onRunSavedWorkflowNow(workflow.id, true)
            }
        )
    }
    externalWorkflowAutomationState.oneTimeToken?.let { token ->
        ExternalWorkflowTokenDialog(
            token = token,
            onDismiss = onClearOneTimeExternalWorkflowToken
        )
    }
    selectedToolCall?.let { record ->
        ToolCallDetailSheet(record = record, onDismiss = { selectedToolCall = null })
    }
    selectedError?.let { record ->
        ErrorDetailSheet(record = record, onDismiss = { selectedError = null })
    }
    selectedMcpServerName?.let { serverName ->
        McpStatusDetailSheet(
            serverName = serverName,
            status = mcpStatuses.firstOrNull { it.name == serverName },
            config = mcpConfigsByName[serverName],
            onDismiss = { selectedMcpServerName = null }
        )
    }
}

@Composable
private fun PermissionToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                }
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                    alpha = if (enabled) 1f else 0.45f
                )
            )
        }
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun GuiAutomationSettingsCard(
    config: ProviderConfig,
    accessibilityConnected: Boolean,
    rootAvailable: Boolean,
    onRefreshAccessibility: () -> Unit,
    onUpdateConfig: (ProviderConfig) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val visionModelManager = remember(context) {
        BuiltinVisionModelManager.shared(context.applicationContext)
    }
    val visionModelState by visionModelManager.state.collectAsState()
    val activeLocalModel = BuiltinVisionModels.all.firstOrNull {
        it.tier == visionModelState.activeTier
    }
    var isEnablingWithRoot by remember { mutableStateOf(false) }
    var enableMessage by remember { mutableStateOf<String?>(null) }
    var showAdvancedLocalVision by remember { mutableStateOf(false) }
    var showLocalRuntimeSettings by remember { mutableStateOf(false) }
    var showPhoneAgentSettings by remember { mutableStateOf(false) }
    var showPhoneAgentModelPicker by remember { mutableStateOf(false) }
    var localRuntimeSettings by remember(context) {
        mutableStateOf(BuiltinVisionRuntime.runtimeSettings(context))
    }
    val localCpuTopology = remember { BuiltinVisionRuntime.cpuTopology() }
    val availableCpuThreads = when (localRuntimeSettings.cpuCorePolicy) {
        BuiltinLocalCpuCorePolicy.PERFORMANCE_CLUSTER ->
            localCpuTopology.recommendedThreadCount
        BuiltinLocalCpuCorePolicy.ALL_CORES ->
            localCpuTopology.logicalCoreCount
    }
    val phoneAgentModelConfig = config.getPhoneAgentResolvedConfig()
    val phoneAgentFollowsChat = config.phoneAgentProviderId.isBlank()
    val phoneAgentModelOptions = buildPhoneAgentModelOptions(
        config,
        visionModelState.installedTiers
    )
    val phoneAgentModelSummary = if (phoneAgentModelConfig.usesCodexChatGptBackend()) {
        "ChatGPT / Codex · " +
            phoneAgentModelConfig.codexModel.trim().ifBlank { "账号默认模型" }
    } else {
        val provider = ProviderRegistry.getActiveProvider(phoneAgentModelConfig.activeProviderId)
        "${
            provider.name
        } · ${provider.formatModelDisplayName(phoneAgentModelConfig.getActiveModel())}"
    }
    val phoneAgentAuthenticationSummary = when {
        phoneAgentModelConfig.usesCodexChatGptBackend() ->
            "使用设置中的 ChatGPT/Codex 账号登录"
        phoneAgentModelConfig.isActiveProviderLocal() -> "本地运行，无需 API"
        phoneAgentModelConfig.getActiveApiKey().isNotBlank() -> "使用设置中已保存的 API"
        else -> "尚未在设置中填写 API"
    }
    fun applyLocalRuntimeSettings(
        updated: com.murong.agent.core.tool.BuiltinLocalRuntimeSettings
    ) {
        localRuntimeSettings = BuiltinVisionRuntime.updateRuntimeSettings(context, updated)
        coroutineScope.launch { BuiltinVisionRuntime.release() }
    }
    fun enableAccessibilityWithRoot() {
        if (isEnablingWithRoot) return
        isEnablingWithRoot = true
        enableMessage = null
        coroutineScope.launch {
            val result = try {
                AndroidGuiAccessibilityAccess.enableWithRoot(context.applicationContext)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                RootAccessibilityEnableResult(
                    success = false,
                    serviceConnected = false,
                    message = "Root 启用失败：${error.message ?: error.javaClass.simpleName}"
                )
            } finally {
                isEnablingWithRoot = false
            }
            enableMessage = result.message
            onRefreshAccessibility()
        }
    }
    DisposableEffect(visionModelManager) {
        visionModelManager.refresh()
        onDispose { }
    }
    ToolsPanelCard {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("手机界面操作", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Android 13+ 优先使用无障碍语义树；截图仅在内存中生成，密码字段始终脱敏。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                StatusBadge(
                    text = if (accessibilityConnected) "无障碍已连接" else "无障碍未连接",
                    color = if (accessibilityConnected) Color(0xFF2E7D32) else MaterialTheme.colorScheme.primary
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    enabled = !isEnablingWithRoot,
                    onClick = {
                        if (accessibilityConnected) {
                            openGuiAccessibilitySettings(context)
                        } else if (rootAvailable) {
                            enableAccessibilityWithRoot()
                        } else {
                            openGuiAccessibilitySettings(context)
                        }
                    }
                ) {
                    if (isEnablingWithRoot) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("正在启用…")
                    } else {
                        Text(if (accessibilityConnected) "管理无障碍" else "启用界面操作")
                    }
                }
                TextButton(onClick = onRefreshAccessibility) { Text("刷新状态") }
            }
            enableMessage?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (accessibilityConnected) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    }
                )
            }

            Text("内置本地模型", style = MaterialTheme.typography.labelLarge)
            Text(
                visionModelState.recommendation?.message
                    ?: "安装后可直接用于离线聊天、看图、写代码和 GUI 操作；可选 Qwen、Gemma、智谱与 DeepSeek。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.36f)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "本地推理设置",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                "${
                                    when (localRuntimeSettings.cpuCorePolicy) {
                                        BuiltinLocalCpuCorePolicy.PERFORMANCE_CLUSTER -> "大核簇（默认）"
                                        BuiltinLocalCpuCorePolicy.ALL_CORES -> "全部核心"
                                    }
                                } · ${localRuntimeSettings.cpuThreads} 线程 · ${
                                    if (activeLocalModel?.engine == BuiltinVisionEngine.LLAMA_CPP) {
                                        "CPU（当前 GGUF 有效后端）"
                                    } else {
                                        when (localRuntimeSettings.backend) {
                                            BuiltinLocalComputeBackend.AUTO -> "自动后端"
                                            BuiltinLocalComputeBackend.GPU -> "仅 GPU"
                                            BuiltinLocalComputeBackend.CPU -> "仅 CPU"
                                        }
                                    }
                                }",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(
                            onClick = {
                                showLocalRuntimeSettings = !showLocalRuntimeSettings
                            }
                        ) {
                            Text(if (showLocalRuntimeSettings) "收起" else "展开")
                        }
                    }
                    if (showLocalRuntimeSettings) {
                        listOf(
                        Triple(
                            BuiltinLocalComputeBackend.AUTO,
                            "自动",
                            "按模型选择稳定后端：LiteRT 优先 GPU，MNN 按兼容性选择，GGUF 使用 CPU"
                        ),
                        Triple(
                            BuiltinLocalComputeBackend.GPU,
                            "仅 GPU",
                            "MNN / LiteRT 强制 GPU；当前 llama.cpp GGUF 包仅含 CPU，遇到这类模型会自动安全回退"
                        ),
                        Triple(
                            BuiltinLocalComputeBackend.CPU,
                            "仅 CPU",
                            "兼容性最好，但大模型生成速度通常较慢"
                        )
                    ).forEach { (backend, title, subtitle) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    applyLocalRuntimeSettings(
                                        localRuntimeSettings.copy(backend = backend)
                                    )
                                },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = localRuntimeSettings.backend == backend,
                                onClick = {
                                    applyLocalRuntimeSettings(
                                        localRuntimeSettings.copy(backend = backend)
                                    )
                                }
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(title, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    Text("CPU 核心范围", style = MaterialTheme.typography.bodyMedium)
                    listOf(
                        Triple(
                            BuiltinLocalCpuCorePolicy.PERFORMANCE_CLUSTER,
                            "大核簇（默认）",
                            "动态识别最高性能的一半核心：" +
                                localCpuTopology.performanceCoreIds.joinToString(
                                    prefix = " CPU ",
                                    separator = "、"
                                )
                        ),
                        Triple(
                            BuiltinLocalCpuCorePolicy.ALL_CORES,
                            "全部核心",
                            "允许使用设备全部 ${localCpuTopology.logicalCoreCount} 个逻辑核心"
                        )
                    ).forEach { (policy, title, subtitle) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val threads = when (policy) {
                                        BuiltinLocalCpuCorePolicy.PERFORMANCE_CLUSTER ->
                                            localCpuTopology.recommendedThreadCount
                                        BuiltinLocalCpuCorePolicy.ALL_CORES ->
                                            localCpuTopology.logicalCoreCount
                                    }
                                    applyLocalRuntimeSettings(
                                        localRuntimeSettings.copy(
                                            cpuCorePolicy = policy,
                                            cpuThreads = threads
                                        )
                                    )
                                },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = localRuntimeSettings.cpuCorePolicy == policy,
                                onClick = {
                                    val threads = when (policy) {
                                        BuiltinLocalCpuCorePolicy.PERFORMANCE_CLUSTER ->
                                            localCpuTopology.recommendedThreadCount
                                        BuiltinLocalCpuCorePolicy.ALL_CORES ->
                                            localCpuTopology.logicalCoreCount
                                    }
                                    applyLocalRuntimeSettings(
                                        localRuntimeSettings.copy(
                                            cpuCorePolicy = policy,
                                            cpuThreads = threads
                                        )
                                    )
                                }
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(title, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("CPU 线程数", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "当前核心范围最多 $availableCpuThreads 线程；CPU 后端和 GPU 回退时生效",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(
                            enabled = localRuntimeSettings.cpuThreads > 1,
                            onClick = {
                                applyLocalRuntimeSettings(
                                    localRuntimeSettings.copy(
                                        cpuThreads = localRuntimeSettings.cpuThreads - 1
                                    )
                                )
                            }
                        ) {
                            Text("－")
                        }
                        Text(
                            "${localRuntimeSettings.cpuThreads}",
                            style = MaterialTheme.typography.titleMedium
                        )
                        TextButton(
                            enabled = localRuntimeSettings.cpuThreads < availableCpuThreads,
                            onClick = {
                                applyLocalRuntimeSettings(
                                    localRuntimeSettings.copy(
                                        cpuThreads = localRuntimeSettings.cpuThreads + 1
                                    )
                                )
                            }
                        ) {
                            Text("＋")
                        }
                    }
                    PermissionToggleRow(
                        title = "推理时临时拉满 CPU / GPU 频率",
                        subtitle = if (rootAvailable) {
                            "仅推理期间通过 Root 提升最低频率，结束或失败后恢复；慕容调度可通过包名排除列表跳过本应用。会显著增加发热和耗电。"
                        } else {
                            "需要 Root；当前未检测到 Root，设置不会生效。"
                        },
                        checked = localRuntimeSettings.forceMaxPerformance,
                        enabled = rootAvailable || localRuntimeSettings.forceMaxPerformance,
                        onCheckedChange = { enabled ->
                            applyLocalRuntimeSettings(
                                localRuntimeSettings.copy(forceMaxPerformance = enabled)
                            )
                        }
                    )
                    }
                }
            }
            BuiltinVisionModels.all.forEach { descriptor ->
                val installed = descriptor.tier in visionModelState.installedTiers
                val active = visionModelState.activeTier == descriptor.tier
                val installing = visionModelState.installingTier == descriptor.tier
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.46f)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    descriptor.displayName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    "约 ${formatVisionModelSize(descriptor.totalBytes)} · " +
                                        descriptor.recommendation,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    buildString {
                                        append(if (descriptor.supportsVision) "文本 / 图片 / GUI" else "纯文本 / 代码")
                                        if (descriptor.reasoningModes.isNotEmpty()) append(" · 可开关思考")
                                        if (!descriptor.androidSupported) {
                                            append(" · ")
                                            append(descriptor.unavailableReason)
                                        }
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            when {
                                active -> StatusBadge(
                                    text = "当前使用",
                                    color = Color(0xFF2E7D32)
                                )
                                installed -> TextButton(
                                    onClick = { visionModelManager.select(descriptor.tier) }
                                ) {
                                    Text("使用")
                                }
                                else -> FilledTonalButton(
                                    enabled = !visionModelState.isInstalling &&
                                        descriptor.androidSupported,
                                    onClick = {
                                        BuiltinVisionModelDownloadService.enqueue(
                                            context,
                                            descriptor.tier,
                                        )
                                    }
                                ) {
                                    Text(
                                        when {
                                            installing -> "下载中"
                                            !descriptor.androidSupported -> "电脑端可用"
                                            else -> "安装"
                                        }
                                    )
                                }
                            }
                        }
                        if (installed) {
                            TextButton(
                                enabled = !visionModelState.isInstalling,
                                onClick = { visionModelManager.delete(descriptor.tier) }
                            ) {
                                Text("删除模型")
                            }
                        }
                    }
                }
            }
            if (visionModelState.isInstalling) {
                LinearProgressIndicator(
                    progress = { visionModelState.progress },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${visionModelState.message} · " +
                            "${(visionModelState.progress * 100).toInt()}%",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(onClick = { visionModelManager.cancelInstall() }) {
                        Text("暂停")
                    }
                }
            } else {
                visionModelState.message.takeIf { it.isNotBlank() }?.let { message ->
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            visionModelState.error?.let { error ->
                Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Text(
                "模型文件下载后保存在应用私有目录，逐文件校验 SHA-256；聊天文本和图片直接交给本地运行时，截图只在内存中处理。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text("视觉推理策略", style = MaterialTheme.typography.labelLarge)
            listOf(
                Triple(
                    GuiInferenceMode.LOCAL_FIRST,
                    "内置优先",
                    "所选内置模型失败后，仅在下方隐私权限允许时回退用户 API。"
                ),
                Triple(
                    GuiInferenceMode.LOCAL_ONLY,
                    "仅内置",
                    "任何情况下都不把截图交给用户 API。"
                ),
                Triple(
                    GuiInferenceMode.USER_API,
                    "用户 API",
                    "直接使用当前模型连接；远程地址仍受截图权限限制。"
                )
            ).forEach { (mode, title, subtitle) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onUpdateConfig(config.copy(guiInferenceMode = mode))
                        },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = config.guiInferenceMode == mode,
                        onClick = { onUpdateConfig(config.copy(guiInferenceMode = mode)) }
                    )
                    Column {
                        Text(title, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            TextButton(
                onClick = { showAdvancedLocalVision = !showAdvancedLocalVision }
            ) {
                Text(if (showAdvancedLocalVision) "收起高级本地服务" else "高级：连接外部本地服务")
            }
            if (showAdvancedLocalVision) {
                OutlinedTextField(
                    value = config.guiLocalBaseUrl,
                    onValueChange = {
                        onUpdateConfig(config.copy(guiLocalBaseUrl = it.take(500)))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("外部 OpenAI-compatible Base URL") },
                    supportingText = {
                        Text("仅用于高级兼容；内置模型不需要填写地址、模型名或 API Key。")
                    },
                    singleLine = true
                )
                OutlinedTextField(
                    value = config.guiLocalModel,
                    onValueChange = {
                        onUpdateConfig(config.copy(guiLocalModel = it.take(200)))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("外部本地视觉模型名") },
                    singleLine = true
                )
            }

            PermissionToggleRow(
                title = "允许远程主模型读取精简语义树",
                subtitle = "只含可访问控件、坐标和非密码文本；关闭后远程模型只能看到去文本结构。",
                checked = config.guiAllowRemoteSemanticTree,
                onCheckedChange = {
                    onUpdateConfig(config.copy(guiAllowRemoteSemanticTree = it))
                }
            )
            PermissionToggleRow(
                title = "允许用户 API 接收截图",
                subtitle = "只在 vision_query 且选择用户 API 或本地识别失败时生效。",
                checked = config.guiAllowRemoteScreenshots,
                onCheckedChange = {
                    onUpdateConfig(
                        config.copy(
                            guiAllowRemoteScreenshots = it,
                            guiAllowRemoteFullScreen = if (it) {
                                config.guiAllowRemoteFullScreen
                            } else {
                                false
                            }
                        )
                    )
                }
            )
            PermissionToggleRow(
                title = "允许上传完整屏幕",
                subtitle = "高隐私风险；关闭时远程视觉只能处理明确裁剪的区域。",
                checked = config.guiAllowRemoteFullScreen,
                enabled = config.guiAllowRemoteScreenshots,
                onCheckedChange = {
                    onUpdateConfig(config.copy(guiAllowRemoteFullScreen = it))
                }
            )

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.36f)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "手机操作 Agent",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                "可从已配置模型中独立选择，不限定 AutoGLM；支付和验证码自动停下接管。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = config.phoneAgentEnabled,
                            onCheckedChange = {
                                onUpdateConfig(config.copy(phoneAgentEnabled = it))
                            }
                        )
                    }
                    TextButton(onClick = { showPhoneAgentSettings = !showPhoneAgentSettings }) {
                        Text(if (showPhoneAgentSettings) "收起操作设置" else "手机操作设置")
                    }
                    if (showPhoneAgentSettings) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.68f)
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    "${
                                        if (phoneAgentFollowsChat) "跟随聊天" else "独立选择"
                                    }：$phoneAgentModelSummary",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    phoneAgentAuthenticationSummary,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    if (phoneAgentModelConfig.usesCodexChatGptBackend()) {
                                        "账号登录模型由当前对话直接连续调用 GUI，不会再要求登录第二次。"
                                    } else {
                                        "API、地址和模型只在统一设置中配置一次。所选模型需支持图片输入；动作支持函数调用、JSON 和 AutoGLM do/finish 协议。"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                TextButton(onClick = { showPhoneAgentModelPicker = true }) {
                                    Text("选择手机操作模型")
                                }
                            }
                        }
                        OutlinedTextField(
                            value = config.phoneAgentMaxSteps.toString(),
                            onValueChange = { raw ->
                                raw.filter(Char::isDigit).toIntOrNull()?.let { value ->
                                    onUpdateConfig(
                                        config.copy(phoneAgentMaxSteps = value.coerceIn(1, 100))
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("单任务最大步骤（1–100）") },
                            singleLine = true
                        )
                        PermissionToggleRow(
                            title = "允许当前远程模型接收完整截图",
                            subtitle = "手机操作必须看见屏幕；本地模型不上传，远程模型关闭此项时不会启动连续任务。",
                            checked = config.phoneAgentAllowRemoteScreenshots,
                            enabled = config.phoneAgentEnabled,
                            onCheckedChange = {
                                onUpdateConfig(
                                    config.copy(phoneAgentAllowRemoteScreenshots = it)
                                )
                            }
                        )
                        PermissionToggleRow(
                            title = "敏感操作保护",
                            subtitle = "支付、订单、验证码等核心风险始终拦截；开启后还会尊重模型附带的人工确认提示。",
                            checked = config.phoneAgentSafeMode,
                            enabled = config.phoneAgentEnabled,
                            onCheckedChange = {
                                onUpdateConfig(config.copy(phoneAgentSafeMode = it))
                            }
                        )
                        Text("外卖比价平台", style = MaterialTheme.typography.labelLarge)
                        listOf("美团", "饿了么", "京东秒送", "淘宝闪购").forEach { platform ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val updated = config.phoneAgentFoodPlatforms.toMutableSet()
                                        if (platform in updated) updated.remove(platform) else updated.add(platform)
                                        onUpdateConfig(
                                            config.copy(phoneAgentFoodPlatforms = updated.toList())
                                        )
                                    },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = platform in config.phoneAgentFoodPlatforms,
                                    onCheckedChange = { checked ->
                                        val updated = config.phoneAgentFoodPlatforms.toMutableSet()
                                        if (checked) updated.add(platform) else updated.remove(platform)
                                        onUpdateConfig(
                                            config.copy(phoneAgentFoodPlatforms = updated.toList())
                                        )
                                    }
                                )
                                Text(platform, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        Text(
                            "聊天中说“比较某商品外卖最低到手价”即可触发；只比较到结算前，不会自动下单或付款。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    if (showPhoneAgentModelPicker) {
        ModalBottomSheet(onDismissRequest = { showPhoneAgentModelPicker = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "选择手机操作模型",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "显示全部已安装本地模型和已配置连接；纯文本模型会保留在列表中并说明不能读取手机截图。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 560.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(phoneAgentModelOptions, key = { it.key }) { option ->
                        val selected = if (option.providerId == null) {
                            config.phoneAgentProviderId.isBlank()
                        } else {
                            config.phoneAgentProviderId == option.providerId &&
                                (
                                    config.phoneAgentRelayId == option.relayId ||
                                        (
                                            option.providerId == BuiltinLocalProvider.ID &&
                                                config.phoneAgentRelayId.isBlank() &&
                                                BuiltinVisionRuntime.activeModel()?.id ==
                                                option.relayId
                                            )
                                    )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = option.enabled) {
                                    onUpdateConfig(
                                        config.copy(
                                            phoneAgentProviderId = option.providerId.orEmpty(),
                                            phoneAgentRelayId = option.relayId
                                        )
                                    )
                                    showPhoneAgentModelPicker = false
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selected,
                                enabled = option.enabled,
                                onClick = null
                            )
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    option.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (selected) {
                                        FontWeight.SemiBold
                                    } else {
                                        FontWeight.Normal
                                    }
                                )
                                Text(
                                    option.subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (option.enabled) {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    } else {
                                        MaterialTheme.colorScheme.error
                                    }
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

private fun openGuiAccessibilitySettings(context: Context) {
    val appContext = context.applicationContext
    val detailsIntent = Intent(AndroidGuiAccessibilityAccess.DETAILS_SETTINGS_ACTION)
        .putExtra(
            Intent.EXTRA_COMPONENT_NAME,
            AndroidGuiAccessibilityAccess.serviceComponentName(appContext)
        )
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (runCatching { appContext.startActivity(detailsIntent) }.isSuccess) return

    appContext.startActivity(
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

private fun formatVisionModelSize(bytes: Long): String {
    val gib = bytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
    return String.format(Locale.US, "%.2f GB", gib)
}

@Composable
private fun RootStatusCard(
    rootStatus: Boolean?,
    isCheckingRoot: Boolean,
    onCheckRoot: () -> Unit
) {
    val statusText = when (rootStatus) {
        true -> "已获取 Root"
        false -> "Root 不可用"
        null -> "尚未检测"
    }
    val subtitle = when {
        isCheckingRoot -> "正在检查设备 Root 状态..."
        rootStatus == true -> "文件工具和命令工具可以直接走 root 能力。"
        else -> "应用会自动检测 Root，可继续直接使用文件和 shell 能力。"
    }
    ToolsPanelCard {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Root 状态", style = MaterialTheme.typography.titleMedium)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                StatusBadge(
                    text = statusText,
                    color = when (rootStatus) {
                        true -> Color(0xFF2E7D32)
                        false -> Color(0xFFC62828)
                        null -> MaterialTheme.colorScheme.primary
                    }
                )
            }
            if (isCheckingRoot) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp
                )
            }
        }
    }
}

@Composable
private fun WorkflowCard(
    config: ProviderConfig,
    onManageWorkflow: () -> Unit,
    onManageApproval: () -> Unit
) {
    ToolsPanelCard {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("执行策略", style = MaterialTheme.typography.titleMedium)
            KeyValueRow("审批模式", config.approvalMode.approvalModeLabel())
            KeyValueRow("工作流模式", workflowExecutionModeLabel(config.workflowExecutionMode))
            KeyValueRow("发送前自动分流", if (config.autoRouteBeforeExecution) "开启" else "关闭")
            KeyValueRow("失败回退", workflowFailureFallbackModeLabel(config.getFailureFallbackMode()))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onManageWorkflow) { Text("工作流") }
                FilledTonalButton(onClick = onManageApproval) { Text("审批") }
            }
        }
    }
}

@Composable
private fun SavedWorkflowCard(
    workflows: List<SavedWorkflowDefinition>,
    externalAutomationState: ExternalWorkflowAutomationUiState,
    onCreate: () -> Unit,
    onEdit: (SavedWorkflowDefinition) -> Unit,
    onRunNow: (SavedWorkflowDefinition) -> Unit,
    onDelete: (String) -> Unit,
    onEnableExternalAutomation: () -> Unit,
    onDisableExternalAutomation: () -> Unit,
    onRotateExternalToken: () -> Unit
) {
    val context = LocalContext.current
    ToolsPanelCard {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("保存的自动化", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "后台只运行固定的项目只读或 GitHub Actions GET 查询；导出和任何写入必须回到前台确认。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                FilledTonalButton(onClick = onCreate) { Text("新建") }
            }
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    Text("Tasker / 外部 Intent", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (externalAutomationState.status.enabled) {
                            "已启用 · 令牌 ${externalAutomationState.status.tokenHint ?: "已生成"}。仅能运行保存且重新校验后的固定只读模板。"
                        } else {
                            "默认关闭。启用后生成一次性显示的令牌；关闭会立即作废旧令牌。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    externalAutomationState.status.lastRequestStatus?.let { status ->
                        Text(
                            "最近外部请求：$status${externalAutomationState.status.lastRequestMessage?.let { " · $it" }.orEmpty()}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (externalAutomationState.status.enabled) {
                            TextButton(onClick = onRotateExternalToken) { Text("轮换令牌") }
                            TextButton(onClick = onDisableExternalAutomation) { Text("停用并作废") }
                        } else {
                            FilledTonalButton(onClick = onEnableExternalAutomation) { Text("启用并生成令牌") }
                        }
                        TextButton(
                            onClick = {
                                copyPlainText(
                                    context,
                                    "Murong 外部工作流 Intent 模板",
                                    externalWorkflowIntentTemplate()
                                )
                            }
                        ) { Text("复制调用模板") }
                    }
                    Text(
                        "task_text 仅作审计备注，不会变成 Agent 指令；project_path 只能收窄保存时的目录范围。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "ColorOS 等系统可能延迟冻结应用的广播；若要稳定后台触发，请在系统电池设置中允许 Murong 后台运行。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (workflows.isEmpty()) {
                Text(
                    "还没有保存的工作流。可创建项目诊断或目录摘要，并设定最短 15 分钟的周期。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                workflows.forEach { workflow ->
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Text(workflow.name, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "${workflow.template.label} · ${if (workflow.enabled) "启用" else "停用"} · 每 ${workflow.intervalMinutes} 分钟",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                workflowBackgroundLabel(workflow),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (workflow.backgroundEligibility() == SavedWorkflowBackgroundEligibility.ALLOWED_READ_ONLY) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.error
                                }
                            )
                            workflow.lastRun?.let { record ->
                                Text(
                                    "最近：${workflowRunStatusLabel(record.status)}${record.summary.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                record.failureReason?.takeIf { it.isNotBlank() }?.let { reason ->
                                    Text(
                                        "原因：$reason",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(onClick = { onEdit(workflow) }) { Text("编辑") }
                                TextButton(onClick = { onRunNow(workflow) }) { Text("立即运行") }
                                TextButton(
                                    onClick = { copyPlainText(context, "Murong 工作流 ID", workflow.id) }
                                ) { Text("复制 ID") }
                                workflow.lastRun?.let { record ->
                                    TextButton(
                                        onClick = {
                                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                                as? ClipboardManager
                                            clipboard?.setPrimaryClip(
                                                ClipData.newPlainText(
                                                    "Murong 去敏工作流记录",
                                                    savedWorkflowRunCopyText(workflow, record)
                                                )
                                            )
                                        }
                                    ) { Text("复制去敏记录") }
                                }
                                TextButton(onClick = { onDelete(workflow.id) }) { Text("删除") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExternalWorkflowTokenDialog(
    token: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    ToolsPopupDialog(
        title = "外部自动化令牌",
        subtitle = "令牌只显示这一次；Murong 只保存哈希。丢失后请轮换，不要把它放进聊天或日志。",
        onDismissRequest = onDismiss,
        actions = {
            TextButton(onClick = onDismiss) { Text("完成") }
        }
    ) {
        SelectionContainer {
            Text(
                token,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace
            )
        }
        FilledTonalButton(
            onClick = {
                copyPlainText(context, "Murong 外部自动化令牌", token)
            }
        ) { Text("复制令牌") }
        Text(
            "Tasker 使用“发送 Intent”，目标包 com.murong.agent，目标类 .automation.ExternalSavedWorkflowReceiver，动作 ${ExternalWorkflowContract.RUN_ACTION}。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun copyPlainText(context: Context, label: String, value: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText(label, value))
}

private fun externalWorkflowIntentTemplate(): String =
    "adb shell am broadcast --receiver-foreground " +
        "-a ${ExternalWorkflowContract.RUN_ACTION} " +
        "-n com.murong.agent/.automation.ExternalSavedWorkflowReceiver " +
        "--es ${ExternalWorkflowContract.EXTRA_WORKFLOW_ID} <WORKFLOW_ID> " +
        "--es ${ExternalWorkflowContract.EXTRA_ACCESS_TOKEN} <TOKEN> " +
        "--es ${ExternalWorkflowContract.EXTRA_REQUEST_ID} <UNIQUE_REQUEST_ID> " +
        "--es ${ExternalWorkflowContract.EXTRA_TASK_TEXT} <AUDIT_NOTE>"

private fun savedWorkflowRunCopyText(
    workflow: SavedWorkflowDefinition,
    record: com.murong.agent.core.automation.SavedWorkflowRunRecord
): String = SensitiveDataSanitizer.sanitizeText(
    buildString {
        appendLine("工作流：${workflow.name}")
        appendLine("模板：${workflow.template.label}")
        appendLine("状态：${workflowRunStatusLabel(record.status)}")
        record.startedAt?.let { appendLine("开始：${formatTime(it)}") }
        record.finishedAt?.let { appendLine("结束：${formatTime(it)}") }
        record.summary.takeIf { it.isNotBlank() }?.let { appendLine("摘要：$it") }
        record.failureReason?.takeIf { it.isNotBlank() }?.let { appendLine("原因：$it") }
    },
    redactPaths = true
)

@Composable
private fun SavedWorkflowEditorDialog(
    initial: SavedWorkflowDefinition?,
    currentProjectPath: String?,
    onDismiss: () -> Unit,
    onSave: (SavedWorkflowDefinition) -> Unit
) {
    var name by remember(initial) {
        mutableStateOf(initial?.name ?: "项目只读诊断")
    }
    var projectPath by remember(initial, currentProjectPath) {
        mutableStateOf(initial?.projectPath ?: currentProjectPath.orEmpty())
    }
    var template by remember(initial) {
        mutableStateOf(initial?.template ?: SavedWorkflowTemplate.PROJECT_READ_DIAGNOSTIC)
    }
    var githubRepository by remember(initial) { mutableStateOf(initial?.githubRepository.orEmpty()) }
    var enabled by remember(initial) { mutableStateOf(initial?.enabled ?: false) }
    var intervalMinutes by remember(initial) {
        mutableStateOf((initial?.intervalMinutes ?: 60L).toString())
    }
    val workflowId = remember(initial?.id) { initial?.id ?: java.util.UUID.randomUUID().toString() }
    val createdAt = remember(initial?.id) { initial?.createdAt ?: System.currentTimeMillis() }
    val resolvedInterval = intervalMinutes.toLongOrNull() ?: 0L
    val candidate = SavedWorkflowDefinition(
        id = workflowId,
        name = name,
        template = template,
        projectPath = projectPath.trim().takeIf { it.isNotBlank() },
        githubRepository = githubRepository.trim().takeIf { it.isNotBlank() },
        nodes = initial?.takeIf { it.template == template }?.nodes ?: template.defaultNodes(),
        intervalMinutes = resolvedInterval,
        enabled = enabled,
        createdAt = createdAt,
        lastRun = initial?.lastRun
    )
    val validation = candidate.validate()
    ToolsLargeDialog(
        title = if (initial == null) "新建保存的自动化" else "编辑保存的自动化",
        onDismissRequest = onDismiss,
        actions = {
            TextButton(onClick = onDismiss) { Text("取消") }
            FilledTonalButton(onClick = { onSave(candidate) }, enabled = validation.isValid) { Text("保存") }
        }
    ) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item("workflow-name") {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
            item("workflow-project") {
                OutlinedTextField(
                    value = projectPath,
                    onValueChange = { projectPath = it },
                    label = { Text("项目范围（只读模板必填）") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
            item("workflow-template-title") { Text("模板", style = MaterialTheme.typography.labelLarge) }
            SavedWorkflowTemplate.entries.forEach { option ->
                item("workflow-template-${option.name}") {
                    SelectableRow(
                        title = option.label,
                        subtitle = workflowTemplateDescription(option),
                        selected = template == option,
                        onClick = { template = option }
                    )
                }
            }
            if (template == SavedWorkflowTemplate.GITHUB_ACTIONS_STATUS) {
                item("workflow-github-repository") {
                    OutlinedTextField(
                        value = githubRepository,
                        onValueChange = { githubRepository = it },
                        label = { Text("GitHub 仓库（owner/repository）") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            }
            item("workflow-interval") {
                OutlinedTextField(
                    value = intervalMinutes,
                    onValueChange = { intervalMinutes = it.filter(Char::isDigit) },
                    label = { Text("周期（分钟，最短 15）") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
            item("workflow-enabled") {
                ToggleRow(
                    title = "启用周期调度",
                    subtitle = "启用后仅符合后台只读限制的模板会真正被安排。",
                    checked = enabled,
                    onCheckedChange = { enabled = it }
                )
            }
            item("workflow-safety") {
                Text(
                    workflowBackgroundLabel(candidate),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (candidate.backgroundEligibility() == SavedWorkflowBackgroundEligibility.ALLOWED_READ_ONLY) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    }
                )
            }
            if (!validation.isValid) {
                item("workflow-validation") {
                    Text(
                        validation.errors.joinToString("\n"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

private fun workflowTemplateDescription(template: SavedWorkflowTemplate): String = when (template) {
    SavedWorkflowTemplate.PROJECT_READ_DIAGNOSTIC -> "读取目录、可访问性和受限规模摘要，不读取文件正文。"
    SavedWorkflowTemplate.DIRECTORY_CHANGE_SUMMARY -> "收集受限目录快照，便于后续人工比较。"
    SavedWorkflowTemplate.GITHUB_ACTIONS_STATUS -> "固定 GET 请求读取最近 5 条 Actions 状态，可安全后台运行。"
    SavedWorkflowTemplate.SESSION_SUMMARY_EXPORT -> "需要写入导出文件；后台不会绕过前台确认。"
}

@Composable
private fun ForegroundSavedWorkflowConfirmationDialog(
    workflow: SavedWorkflowDefinition,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    ToolsLargeDialog(
        title = "确认前台执行",
        onDismissRequest = onDismiss,
        actions = {
            TextButton(onClick = onDismiss) { Text("取消") }
            FilledTonalButton(onClick = onConfirm) { Text("确认执行一次") }
        }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(workflow.name, style = MaterialTheme.typography.titleSmall)
            Text(
                text = workflowForegroundConfirmationText(workflow),
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "此确认只对本次运行有效；后台调度不会取得这项权限，且“禁止”权限规则仍会拦截执行。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun workflowForegroundConfirmationText(workflow: SavedWorkflowDefinition): String = when (workflow.template) {
    SavedWorkflowTemplate.GITHUB_ACTIONS_STATUS ->
        "将通过当前 GitHub 登录读取 ${workflow.githubRepository?.trim().orEmpty()} 最近 5 条 Actions 运行状态，不会写入仓库。"
    SavedWorkflowTemplate.SESSION_SUMMARY_EXPORT ->
        "将把当前聊天会话导出为 Markdown 到应用文档目录。内容会写入文件，但不会上传到网络。"
    SavedWorkflowTemplate.PROJECT_READ_DIAGNOSTIC,
    SavedWorkflowTemplate.DIRECTORY_CHANGE_SUMMARY ->
        "此模板是只读模板，不需要前台确认。"
}

private fun workflowBackgroundLabel(workflow: SavedWorkflowDefinition): String = when (workflow.backgroundEligibility()) {
    SavedWorkflowBackgroundEligibility.ALLOWED_READ_ONLY -> "后台权限：允许（固定只读执行器）"
    SavedWorkflowBackgroundEligibility.NEEDS_FOREGROUND_CONFIRMATION -> "后台权限：需要在前台确认"
    SavedWorkflowBackgroundEligibility.INVALID -> "后台权限：定义无效，不能调度"
}

private fun workflowRunStatusLabel(status: com.murong.agent.core.automation.SavedWorkflowRunStatus): String = when (status) {
    com.murong.agent.core.automation.SavedWorkflowRunStatus.NEVER -> "从未运行"
    com.murong.agent.core.automation.SavedWorkflowRunStatus.QUEUED -> "已排队"
    com.murong.agent.core.automation.SavedWorkflowRunStatus.RUNNING -> "运行中"
    com.murong.agent.core.automation.SavedWorkflowRunStatus.SUCCEEDED -> "成功"
    com.murong.agent.core.automation.SavedWorkflowRunStatus.FAILED -> "失败"
    com.murong.agent.core.automation.SavedWorkflowRunStatus.BLOCKED -> "已拦截"
    com.murong.agent.core.automation.SavedWorkflowRunStatus.CANCELLED -> "已取消"
}

@Composable
private fun ApprovalPostureCard(
    overview: ApprovalPostureOverviewPresentation,
) {
    ToolsPanelCard {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(overview.sectionTitle, style = MaterialTheme.typography.titleMedium)
            Text(
                text = overview.headline,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = overview.supportText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            overview.detailRows.forEach { row ->
                KeyValueRow(row.label, row.value)
            }
            overview.secondaryNotes.forEach { note ->
                Text(
                    text = note.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (note.emphasized) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
    }
}

@Composable
private fun ApprovalCard(
    cardPresentation: ApprovalCardPresentation,
    onOpenChat: () -> Unit,
    onOpenDetail: () -> Unit,
    onApprove: () -> Unit,
    onReject: () -> Unit
) {
    ToolsPanelCard {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(cardPresentation.sectionTitle, style = MaterialTheme.typography.titleMedium)
            cardPresentation.pendingTitle?.let { pendingTitle ->
                Text(pendingTitle, style = MaterialTheme.typography.bodyLarge)
                Text(
                    cardPresentation.pendingSupportText.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = onOpenDetail) {
                        Text(cardPresentation.detailActionLabel.orEmpty())
                    }
                    FilledTonalButton(
                        onClick = onApprove,
                        enabled = cardPresentation.approveEnabled
                    ) {
                        Text(cardPresentation.approveActionLabel.orEmpty())
                    }
                    TextButton(onClick = onReject) {
                        Text(cardPresentation.rejectActionLabel.orEmpty())
                    }
                }
            } ?: run {
                Text(
                    cardPresentation.emptyStateText.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FilledTonalButton(onClick = onOpenChat) { Text("回到对话") }
            }
            cardPresentation.recentApprovalsTitle?.let { recentApprovalsTitle ->
                Text(recentApprovalsTitle, style = MaterialTheme.typography.labelLarge)
                cardPresentation.recentApprovalItems.forEach { item ->
                    CompactListRow(
                        title = item.title,
                        subtitle = item.subtitle
                    )
                }
            }
        }
    }
}

@Composable
private fun ProjectPreferenceCard(
    currentProjectPath: String?,
    projectRuleCount: Int,
    projectMemoryCount: Int,
    projectSkillCount: Int
) {
    ToolsPanelCard {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("项目上下文", style = MaterialTheme.typography.titleMedium)
            KeyValueRow("当前项目", currentProjectPath ?: "未绑定")
            KeyValueRow("项目规则", "$projectRuleCount")
            KeyValueRow("项目记忆", "$projectMemoryCount")
            KeyValueRow("项目技能", "$projectSkillCount")
        }
    }
}

@Composable
private fun ProjectApprovalCard(
    cardPresentation: ProjectApprovalCardPresentation
) {
    ToolsPanelCard {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(cardPresentation.sectionTitle, style = MaterialTheme.typography.titleMedium)
            Text(
                cardPresentation.summaryText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (cardPresentation.emptyStateText != null) {
                Text(
                    cardPresentation.emptyStateText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                cardPresentation.items.forEach { item ->
                    CompactListRow(
                        title = item.title,
                        subtitle = item.subtitle
                    )
                }
            }
        }
    }
}

@Composable
private fun AuditCard(
    recentFinalReadinessAudits: List<FinalReadinessAuditRecord>,
    recentToolCalls: List<ToolCallRecordUi>,
    recentErrors: List<ErrorRecordUi>,
    onOpenToolCall: (ToolCallRecordUi) -> Unit,
    onOpenError: (ErrorRecordUi) -> Unit
) {
    val finalReadinessOverview = remember(recentFinalReadinessAudits) {
        buildFinalReadinessAuditOverview(recentFinalReadinessAudits)
    }
    val skillUsageSummary = remember(recentToolCalls) {
        buildSkillUsageAuditSummary(recentToolCalls)
    }
    ToolsPanelCard {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("工具审计", style = MaterialTheme.typography.titleMedium)
            KeyValueRow("任务完成检查", "${finalReadinessOverview?.totalCount ?: 0}")
            KeyValueRow("最近工具调用", "${recentToolCalls.size}")
            KeyValueRow("最近 Skill 调用", "${skillUsageSummary.totalCount}")
            KeyValueRow("最近错误", "${recentErrors.size}")
            if (skillUsageSummary.recentSkillTitles.isNotEmpty()) {
                Text(
                    text = "最近 Skill: ${skillUsageSummary.recentSkillTitles.joinToString("、")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (skillUsageSummary.recentTasks.isNotEmpty()) {
                Text(
                    text = "最近 Skill 任务: ${skillUsageSummary.recentTasks.joinToString("；")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            finalReadinessOverview?.let { overview ->
                Text(
                    text = "最近状态: ${summarizeFinalReadinessOverviewStatus(overview)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (overview.currentlyBlocked) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    }
                )
                Text(
                    text = buildFinalReadinessAuditOverviewHeadline(overview),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                buildFinalReadinessAuditOverviewBreakdown(overview)?.let { breakdown ->
                    Text(
                        text = breakdown,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            val finalReadinessErrorCount = recentErrors.count { it.kind == ErrorRecordKind.FINAL_READINESS }
            if (finalReadinessErrorCount > 0) {
                Text(
                    text = "其中 $finalReadinessErrorCount 条是收尾提醒，可点开查看详情。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            recentToolCalls.take(3).forEach { record ->
                ClickableListRow(
                    title = "${record.toolName} · ${if (record.isSuccess) "成功" else "失败"}",
                    subtitle = record.args,
                    onClick = { onOpenToolCall(record) }
                )
            }
            recentErrors.take(2).forEach { record ->
                ClickableListRow(
                    title = "${errorRecordTypeLabel(record)} · ${formatTime(record.timestamp)}",
                    subtitle = record.message,
                    onClick = { onOpenError(record) }
                )
            }
        }
    }
}

@Composable
private fun FileChangeCard(
    presentation: CheckpointToolsPresentation,
    onOpenCheckpoint: (String) -> Unit,
    onOpenRecord: (String) -> Unit,
    onOpenRecovery: (String) -> Unit,
    onOpenRecoveryTimeline: () -> Unit,
    onRollbackCheckpoint: (String, ConversationCheckpointScope) -> Unit
) {
    ToolsPanelCard {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("文件修改", style = MaterialTheme.typography.titleMedium)
            KeyValueRow("检查点", presentation.checkpointCountLabel)
            KeyValueRow("文件改动", presentation.fileChangeCountLabel)
            KeyValueRow("最近恢复", presentation.recoveryCountLabel)
            presentation.recoveryOverviewLabel?.let { overview ->
                Text(
                    text = overview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            presentation.recoveries.firstOrNull()?.let { latestRecovery ->
                Text(
                    text = "最近一次: ${latestRecovery.summaryPreview}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (presentation.recoveries.size > 3) {
                TextButton(onClick = onOpenRecoveryTimeline) {
                    Text("查看时间线")
                }
            }
            presentation.recoveries.take(3).forEach { record ->
                ClickableListRow(
                    title = record.title,
                    subtitle = record.subtitle,
                    onClick = { onOpenRecovery(record.id) }
                )
            }
            presentation.checkpoints.take(3).forEach { checkpoint ->
                ClickableListRow(
                    title = checkpoint.title,
                    subtitle = checkpoint.subtitle,
                    trailing = checkpoint.rollbackLabel,
                    onTrailingClick = { onRollbackCheckpoint(checkpoint.id, checkpoint.rollbackScope) },
                    onClick = { onOpenCheckpoint(checkpoint.id) }
                )
            }
            presentation.fileChanges.take(3).forEach { record ->
                ClickableListRow(
                    title = record.title,
                    subtitle = record.subtitle,
                    onClick = { onOpenRecord(record.id) }
                )
            }
        }
    }
}

@Composable
private fun McpCard(
    mcpServers: List<McpServerConfig>,
    mcpStatuses: List<McpServerStatus>,
    mcpConnectError: String?,
    onConnectMcpServers: () -> Unit,
    onRefreshMcpStatus: () -> Unit,
    onOpenStatus: (String) -> Unit
) {
    val configsByName = remember(mcpServers) { mcpServers.associateBy { it.name } }
    val statusesByName = remember(mcpStatuses) { mcpStatuses.associateBy { it.name } }
    val serverNames = remember(mcpServers, mcpStatuses) {
        (mcpServers.map { it.name } + mcpStatuses.map { it.name }).distinct().sorted()
    }
    ToolsPanelCard {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("MCP 服务器", style = MaterialTheme.typography.titleMedium)
            if (!mcpConnectError.isNullOrBlank()) {
                Text(mcpConnectError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onConnectMcpServers) { Text("连接") }
                FilledTonalButton(onClick = onRefreshMcpStatus) { Text("刷新") }
            }
            if (serverNames.isEmpty()) {
                Text("暂未保存或连接 MCP 服务器。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                serverNames.forEach { serverName ->
                    val status = statusesByName[serverName]
                    val config = configsByName[serverName]
                    ClickableListRow(
                        title = "$serverName · ${buildMcpToolsConnectionLabel(status, config)}",
                        subtitle = buildMcpToolsOverview(status, config),
                        onClick = { onOpenStatus(serverName) }
                    )
                }
            }
        }
    }
}

@Composable
private fun CompactBuiltInToolsPanel(
    tools: List<ToolEntry>,
    subagentPresets: List<ToolEntry>,
    isEnabled: (String) -> Boolean,
    subagentExpanded: Boolean,
    onSubagentExpandedChange: (Boolean) -> Unit,
    onToggle: (String, Boolean) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column {
            tools.forEachIndexed { index, tool ->
                CompactToolToggleRow(
                    tool = tool,
                    checked = isEnabled(tool.name),
                    onCheckedChange = { onToggle(tool.name, it) },
                    action = if (tool.name == "subagent") {
                        {
                            TextButton(
                                onClick = {
                                    onSubagentExpandedChange(!subagentExpanded)
                                }
                            ) {
                                Text(if (subagentExpanded) "收起" else "详情")
                            }
                        }
                    } else {
                        null
                    }
                )
                if (tool.name == "subagent" && subagentExpanded) {
                    Column(
                        modifier = Modifier.padding(start = 14.dp),
                    ) {
                        subagentPresets.forEach { preset ->
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                            )
                            CompactToolToggleRow(
                                tool = preset,
                                checked = isEnabled(preset.name),
                                enabled = isEnabled("subagent"),
                                onCheckedChange = { onToggle(preset.name, it) }
                            )
                        }
                    }
                }
                if (index != tools.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 14.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
                    )
                }
            }
        }
    }
}

@Composable
private fun CompactToolToggleRow(
    tool: ToolEntry,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
    action: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(tool.title, style = MaterialTheme.typography.bodyMedium)
            Text(
                tool.description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        action?.invoke()
        Spacer(modifier = Modifier.width(6.dp))
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun ToolToggleCard(
    tool: ToolEntry,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    ToolsPanelCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(tool.title, style = MaterialTheme.typography.titleSmall)
                Text(tool.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange
            )
        }
    }
}

@Composable
private fun CollapsibleToolGroupCard(
    tool: ToolEntry,
    checked: Boolean,
    expanded: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onExpandedChange: (Boolean) -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    ToolsPanelCard {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(tool.title, style = MaterialTheme.typography.titleSmall)
                    Text(tool.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(onClick = { onExpandedChange(!expanded) }) {
                    Text(if (expanded) "收起" else "展开")
                }
                Switch(
                    checked = checked,
                    onCheckedChange = onCheckedChange
                )
            }
            if (expanded) {
                content()
            }
        }
    }
}

@Composable
private fun ToolToggleRow(
    tool: ToolEntry,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.26f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(tool.title, style = MaterialTheme.typography.bodyMedium)
                Text(
                    tool.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Switch(
                checked = checked,
                enabled = enabled,
                onCheckedChange = onCheckedChange
            )
        }
    }
}

@Composable
private fun PlanningCard(title: String, body: String) {
    ToolsPanelCard {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ToolsPanelCard(content: @Composable ColumnScope.() -> Unit) {
    MurongGlassSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        contentPadding = PaddingValues(16.dp),
        content = content
    )
}

@Composable
private fun ToolsPopupDialog(
    title: String,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit
) {
    MurongDialog(onDismissRequest = onDismissRequest) {
        MurongPopupSurface(
            shape = MurongPopupCardShape,
            modifier = modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium
                        )
                        subtitle?.takeIf { it.isNotBlank() }?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        content = actions
                    )
                }
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    content = content
                )
            }
        }
    }
}

@Composable
private fun ToolsLargeDialog(
    title: String,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit
) {
    MurongLargeDialogScaffold(
        onDismissRequest = onDismissRequest,
        modifier = modifier
    ) {
        MurongGlassSurface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = MurongLargeDialogCardShape,
            contentPadding = PaddingValues(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium
                    )
                    subtitle?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    content = actions
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content
        )
    }
}

@Composable
private fun ApprovalPolicyEditorDialog(
    config: ProviderConfig,
    onDismiss: () -> Unit,
    onSave: (ProviderConfig) -> Unit
) {
    var selectedMode by remember(config.approvalMode) { mutableStateOf(config.approvalMode) }
    ToolsLargeDialog(
        title = "审批模式",
        onDismissRequest = onDismiss,
        actions = {
            TextButton(onClick = onDismiss) { Text("取消") }
            FilledTonalButton(
                onClick = {
                    onSave(
                        config.copy(
                            approvalMode = selectedMode
                        )
                    )
                }
            ) { Text("保存") }
        }
    ) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item("approval-mode-title") {
                Text("会话审批模式", style = MaterialTheme.typography.labelLarge)
            }
            buildApprovalModeOptionPresentations().forEach { optionPresentation ->
                item("approval-mode-${optionPresentation.mode}") {
                    SelectableRow(
                        title = optionPresentation.title,
                        subtitle = optionPresentation.subtitle,
                        selected = selectedMode == optionPresentation.mode,
                        onClick = { selectedMode = optionPresentation.mode ?: config.approvalMode }
                    )
                }
            }
        }
    }
}

@Composable
private fun WorkflowExecutionPreferenceDialog(
    currentMode: WorkflowExecutionMode,
    initialAutoRouteEnabled: Boolean,
    initialFallbackMode: WorkflowFailureFallbackMode,
    onDismiss: () -> Unit,
    onSave: (WorkflowExecutionMode, Boolean, WorkflowFailureFallbackMode) -> Unit
) {
    var selectedMode by remember(currentMode) { mutableStateOf(currentMode) }
    var autoRouteEnabled by remember(initialAutoRouteEnabled) { mutableStateOf(initialAutoRouteEnabled) }
    var fallbackMode by remember(initialFallbackMode) { mutableStateOf(initialFallbackMode) }
    ToolsLargeDialog(
        title = "工作流执行偏好",
        onDismissRequest = onDismiss,
        actions = {
            TextButton(onClick = onDismiss) { Text("取消") }
            FilledTonalButton(onClick = { onSave(selectedMode, autoRouteEnabled, fallbackMode) }) {
                Text("保存")
            }
        }
    ) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item("mode-title") {
                Text("执行模式", style = MaterialTheme.typography.labelLarge)
            }
            WorkflowExecutionMode.entries.forEach { mode ->
                item(mode.name) {
                    SelectableRow(
                        title = workflowExecutionModeLabel(mode),
                        subtitle = workflowExecutionModeDescription(mode),
                        selected = selectedMode == mode,
                        onClick = { selectedMode = mode }
                    )
                }
            }
            item("auto-route") {
                ToggleRow(
                    title = "发送前自动分流",
                    subtitle = "在发起任务前先判断是否更适合直接执行、先计划或先澄清。",
                    checked = autoRouteEnabled,
                    onCheckedChange = { autoRouteEnabled = it }
                )
            }
            item("fallback-title") {
                Text("失败回退", style = MaterialTheme.typography.labelLarge)
            }
            WorkflowFailureFallbackMode.entries.forEach { mode ->
                item("fallback-${mode.name}") {
                    SelectableRow(
                        title = workflowFailureFallbackModeLabel(mode),
                        subtitle = workflowFailureFallbackModeDescription(mode),
                        selected = fallbackMode == mode,
                        onClick = { fallbackMode = mode }
                    )
                }
            }
            item("summary-title") {
                Text("当前生效摘要", style = MaterialTheme.typography.labelLarge)
            }
            items(buildWorkflowFailureSummary(autoRouteEnabled, fallbackMode)) { item ->
                Text(
                    text = "• $item",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ToolAccessEditorDialog(
    config: ProviderConfig,
    mcpToolNames: List<String>,
    onDismiss: () -> Unit,
    onSave: (ProviderConfig) -> Unit
) {
    var enabledBuiltinTools by remember(config.enabledBuiltinTools) {
        mutableStateOf(config.enabledBuiltinTools.toMutableSet())
    }
    var enabledFileOperations by remember(config.enabledFileToolOperations) {
        mutableStateOf(config.enabledFileToolOperations.toMutableSet())
    }
    var allowAllMcpTools by remember(config.allowAllMcpTools) {
        mutableStateOf(config.allowAllMcpTools)
    }
    var allowedMcpTools by remember(config.allowedMcpTools) {
        mutableStateOf(config.allowedMcpTools.toMutableSet())
    }

    ToolsLargeDialog(
        title = "工具权限",
        onDismissRequest = onDismiss,
        actions = {
            TextButton(onClick = onDismiss) { Text("取消") }
            FilledTonalButton(
                onClick = {
                    onSave(
                        config.copy(
                            enabledBuiltinTools = enabledBuiltinTools.sorted(),
                            enabledFileToolOperations = enabledFileOperations.sorted(),
                            allowAllMcpTools = allowAllMcpTools,
                            allowedMcpTools = allowedMcpTools.sorted()
                        )
                    )
                }
            ) { Text("保存") }
        }
    ) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item("builtin-title") {
                Text("内置工具", style = MaterialTheme.typography.labelLarge)
            }
            builtInToolCatalog().forEach { tool ->
                item("builtin-${tool.name}") {
                    ToggleRow(
                        title = tool.title,
                        subtitle = tool.description,
                        checked = tool.name in enabledBuiltinTools,
                        onCheckedChange = { checked ->
                            enabledBuiltinTools = enabledBuiltinTools.toMutableSet().also { set ->
                                if (checked) set.add(tool.name) else set.remove(tool.name)
                            }
                        }
                    )
                }
            }
            item("file-title") {
                Text("文件操作", style = MaterialTheme.typography.labelLarge)
            }
            DEFAULT_ENABLED_FILE_TOOL_OPERATIONS.forEach { operation ->
                item("file-op-$operation") {
                    ToggleRow(
                        title = fileOperationLabel(operation),
                        subtitle = fileOperationDescription(operation),
                        checked = operation in enabledFileOperations,
                        onCheckedChange = { checked ->
                            enabledFileOperations = enabledFileOperations.toMutableSet().also { set ->
                                if (checked) set.add(operation) else set.remove(operation)
                            }
                        }
                    )
                }
            }
            item("mcp-toggle") {
                ToggleRow(
                    title = "允许全部 MCP 工具",
                    subtitle = "开启后不再单独维护 MCP 工具白名单。",
                    checked = allowAllMcpTools,
                    onCheckedChange = { allowAllMcpTools = it }
                )
            }
            if (!allowAllMcpTools && mcpToolNames.isNotEmpty()) {
                item("mcp-title") {
                    Text("MCP 工具白名单", style = MaterialTheme.typography.labelLarge)
                }
                mcpToolNames.forEach { toolName ->
                    item("mcp-$toolName") {
                        ToggleRow(
                            title = toolName,
                            subtitle = "仅允许当前工具自动进入调用列表。",
                            checked = toolName in allowedMcpTools,
                            onCheckedChange = { checked ->
                                allowedMcpTools = allowedMcpTools.toMutableSet().also { set ->
                                    if (checked) set.add(toolName) else set.remove(toolName)
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CheckpointDetailSheet(
    checkpoint: CheckpointToolPresentation,
    records: List<FileChangeToolPresentation>,
    onDismiss: () -> Unit,
    onRollbackCheckpoint: () -> Unit,
    onForkCheckpoint: () -> Unit,
    onOpenRecord: (String) -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(checkpoint.detailTitle, style = MaterialTheme.typography.titleMedium)
            Text(checkpoint.detailSubtitle, style = MaterialTheme.typography.bodySmall)
            CodeBlock(checkpoint.detailContent)
            if (checkpoint.changedFiles.isNotEmpty()) {
                Text(
                    text = "关联文件",
                    style = MaterialTheme.typography.labelLarge
                )
            }
            checkpoint.changedFiles.forEach { path ->
                Text("• $path", style = MaterialTheme.typography.bodySmall)
            }
            Text(
                text = checkpoint.rollbackDescription,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = onForkCheckpoint) {
                Text("分叉会话")
            }
            FilledTonalButton(onClick = onRollbackCheckpoint) { Text(checkpoint.rollbackLabel) }
            if (records.isNotEmpty()) {
                Text(
                    text = "关联记录",
                    style = MaterialTheme.typography.labelLarge
                )
            }
            records.forEach { record ->
                ClickableListRow(
                    title = record.title,
                    subtitle = record.subtitle,
                    onClick = { onOpenRecord(record.id) }
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun FileChangeDetailSheet(record: FileChangeToolPresentation, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        DetailSheetContent(
            title = record.detailTitle,
            subtitle = record.detailSubtitle,
            content = record.detailContent
        )
    }
}

@Composable
private fun RecoveryDetailSheet(
    record: CheckpointRecoveryToolPresentation,
    onDismiss: () -> Unit,
    onOpenCheckpoint: (() -> Unit)? = null
) {
    ToolsPopupDialog(
        title = record.detailTitle,
        subtitle = record.detailSubtitle,
        onDismissRequest = onDismiss,
        actions = {
            if (onOpenCheckpoint != null) {
                TextButton(onClick = onOpenCheckpoint) { Text("查看来源检查点") }
            }
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    ) {
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CodeBlock(record.detailContent)
            }
        }
    }
}

@Composable
private fun RecoveryTimelineSheet(
    records: List<CheckpointRecoveryToolPresentation>,
    onDismiss: () -> Unit,
    onOpenRecovery: (String) -> Unit
) {
    val timelineGroups = remember(records) { buildCheckpointRecoveryTimelineGroups(records) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("恢复时间线", style = MaterialTheme.typography.titleMedium)
            timelineGroups.forEach { group ->
                Text(
                    text = group.dayLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = group.summaryLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                group.records.forEach { record ->
                    ClickableListRow(
                        title = "${record.title} · ${formatTime(record.timestamp)}",
                        subtitle = record.summaryPreview,
                        onClick = { onOpenRecovery(record.id) }
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun ToolCallDetailSheet(record: ToolCallRecordUi, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        DetailSheetContent(
            title = "${record.toolName} · ${if (record.isSuccess) "成功" else "失败"}",
            subtitle = "时间 ${formatTime(record.timestamp)}",
            content = buildString {
                appendLine("Args:")
                appendLine(record.args)
                appendLine()
                appendLine("Result:")
                append(record.result ?: "无结果")
            }
        )
    }
}

@Composable
private fun ErrorDetailSheet(record: ErrorRecordUi, onDismiss: () -> Unit) {
    ToolsPopupDialog(
        title = "错误详情",
        subtitle = "${errorRecordTypeLabel(record)} · ${formatTime(record.timestamp)}",
        onDismissRequest = onDismiss,
        actions = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    ) {
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CodeBlock(record.message)
            }
        }
    }
}

@Composable
private fun McpStatusDetailSheet(
    serverName: String,
    status: McpServerStatus?,
    config: McpServerConfig?,
    onDismiss: () -> Unit
) {
    ToolsPopupDialog(
        title = "MCP 状态",
        subtitle = serverName,
        onDismissRequest = onDismiss,
        actions = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            buildMcpDetailFacts(status, config).forEach { fact ->
                val isErrorLine = fact.startsWith("失败信息:") || fact.startsWith("最近错误:")
                Text(
                    text = fact,
                    color = if (isErrorLine) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                )
            }
            if (status?.toolNames?.isNotEmpty() == true) {
                Text("工具列表", style = MaterialTheme.typography.labelLarge)
                status.toolNames.forEach { toolName ->
                    Text("• ${canonicalMcpToolName(serverName, toolName)}")
                }
            }
        }
    }
}

internal fun buildMcpToolsConnectionLabel(
    status: McpServerStatus?,
    config: McpServerConfig?
): String {
    return when {
        status?.connected == true -> "已连接"
        config != null -> "未连接"
        else -> "未知"
    }
}

internal fun buildMcpToolsOverview(
    status: McpServerStatus?,
    config: McpServerConfig?
): String {
    return buildList {
        add("工具 ${status?.toolCount ?: 0}")
        config?.let {
            add("来源 ${formatMcpSourceLabel(it.source)}")
            if (it.trustedReadOnlyTools.isNotEmpty()) add("ro ${it.trustedReadOnlyTools.size}")
            if (!it.autoStart) add("手动连接")
        }
        status?.failureRecord?.let { add("${it.stage.name.lowercase(Locale.ROOT)} 失败") }
            ?: status?.error?.takeIf { it.isNotBlank() }?.let { add(it) }
    }.joinToString(" · ")
}

internal fun buildMcpDetailFacts(
    status: McpServerStatus?,
    config: McpServerConfig?
): List<String> {
    return buildList {
        add("连接状态: ${buildMcpToolsConnectionLabel(status, config)}")
        add("工具数量: ${status?.toolCount ?: 0}")
        if (config != null) {
            add("配置来源: ${formatMcpSourceLabel(config.source)}")
            if (config.sourcePath.isNotBlank()) add("来源路径: ${config.sourcePath}")
            add("自动连接: ${if (config.autoStart) "是" else "否"}")
            add("可信只读: ${config.trustedReadOnlyTools.size} 个")
            if (config.authHeaderSecretReferences.isNotEmpty()) {
                add("安全凭据引用: ${config.authHeaderSecretReferences.size} 个")
            }
            add("传输类型: ${formatMcpTransportLabel(config.transport)}")
        } else {
            add("配置来源: 未保存")
        }
        status?.failureRecord?.let { failure ->
            add("失败阶段: ${failure.stage.name.lowercase(Locale.ROOT)}")
            failure.transport?.let { add("失败传输: ${formatMcpTransportLabel(it)}") }
            add("可重试: ${if (failure.retryable) "是" else "否"}")
            add("失败信息: ${failure.message}")
        } ?: status?.error?.takeIf { it.isNotBlank() }?.let { add("最近错误: $it") }
        status?.toolCacheUpdatedAt?.let { add("工具缓存更新时间: $it") }
        status?.configurationGeneration?.takeIf { it > 0 }?.let { add("配置版本: $it") }
    }
}

private fun formatMcpSourceLabel(source: McpConfigSource): String {
    return when (source) {
        McpConfigSource.MANUAL -> "manual"
        McpConfigSource.IMPORTED_DRAFT -> "draft"
        McpConfigSource.MCP_JSON -> ".mcp.json"
    }
}

private fun formatMcpTransportLabel(transport: McpTransportType): String {
    return when (transport) {
        McpTransportType.STDIO -> "stdio"
        McpTransportType.SSE -> "SSE"
        McpTransportType.STREAMABLE_HTTP -> "streamable-http"
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SectionTitleWithAction(
    title: String,
    subtitle: String,
    action: @Composable RowScope.() -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(modifier = Modifier.width(12.dp))
        Row(content = action)
    }
}

@Composable
private fun ToolAccessInlineCard(
    config: ProviderConfig,
    mcpToolNames: List<String>,
    onFileOperationToggle: (String, Boolean) -> Unit,
    onAllowAllMcpToggle: (Boolean) -> Unit,
    onMcpToolToggle: (String, Boolean) -> Unit
) {
    ToolsPanelCard {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("工具权限", style = MaterialTheme.typography.titleMedium)
            Text(
                "直接在这里改文件操作和 MCP 白名单，不再额外弹一层勾选框。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text("文件操作", style = MaterialTheme.typography.labelLarge)
            DEFAULT_ENABLED_FILE_TOOL_OPERATIONS.forEach { operation ->
                ToggleRow(
                    title = fileOperationLabel(operation),
                    subtitle = fileOperationDescription(operation),
                    checked = config.isFileToolOperationEnabled(operation),
                    onCheckedChange = { onFileOperationToggle(operation, it) }
                )
            }
            ToggleRow(
                title = "允许全部 MCP 工具",
                subtitle = "关闭后，只允许下面打开的 MCP 工具进入调用列表。",
                checked = config.allowAllMcpTools,
                onCheckedChange = onAllowAllMcpToggle
            )
            if (!config.allowAllMcpTools && mcpToolNames.isNotEmpty()) {
                Text("MCP 白名单", style = MaterialTheme.typography.labelLarge)
                mcpToolNames.forEach { toolName ->
                    ToggleRow(
                        title = toolName,
                        subtitle = "仅允许当前工具自动进入调用列表。",
                        checked = toolName in config.allowedMcpTools,
                        onCheckedChange = { onMcpToolToggle(toolName, it) }
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(text: String, color: Color) {
    Surface(shape = CircleShape, color = color.copy(alpha = 0.14f)) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            color = color
        )
    }
}

@Composable
private fun KeyValueRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun CompactListRow(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.bodyMedium)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ClickableListRow(
    title: String,
    subtitle: String,
    trailing: String? = null,
    onTrailingClick: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(title, style = MaterialTheme.typography.bodyMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (trailing != null && onTrailingClick != null) {
                TextButton(onClick = onTrailingClick) { Text(trailing) }
            }
        }
    }
}

private fun buildSkillUsageAuditSummary(
    recentToolCalls: List<ToolCallRecordUi>
): SkillUsageAuditSummary {
    val skillPayloads = recentToolCalls.asSequence()
        .filter { it.isSuccess }
        .mapNotNull { it.structuredPayload?.skill }
        .toList()
    return SkillUsageAuditSummary(
        totalCount = skillPayloads.size,
        recentSkillTitles = skillPayloads.mapNotNull { payload ->
            payload.skillTitle?.trim()?.takeIf { it.isNotBlank() }
        }.distinct().take(3),
        recentTasks = skillPayloads.mapNotNull { payload ->
            payload.task?.trim()?.takeIf { it.isNotBlank() }
        }.distinct().take(2)
    )
}

@Composable
private fun SelectableRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(selected = selected, onClick = onClick)
            Spacer(modifier = Modifier.width(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.bodyMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = checked, onCheckedChange = onCheckedChange)
            Spacer(modifier = Modifier.width(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.bodyMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun DetailSheetContent(title: String, subtitle: String, content: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        CodeBlock(content)
        Spacer(modifier = Modifier.height(12.dp))
    }
}

@Composable
private fun CodeBlock(content: String) {
    SelectionContainer {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f), MaterialTheme.shapes.medium)
                .padding(12.dp)
        ) {
            Text(
                text = sanitizeForUiDisplay(content),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

private fun builtInToolCatalog(): List<ToolEntry> {
    return listOf(
        ToolEntry("shell", "命令工具", "可选择 Root 系统环境或终端扩展环境执行命令。", ""),
        ToolEntry("file", "文件工具", "读写、列目录、删除和 chmod。", ""),
        ToolEntry("gui", "界面操作", "使用 Android Accessibility 语义树、手势与本地优先视觉识别。", ""),
        ToolEntry("code_edit", "代码编辑", "查看文件并执行 SEARCH/REPLACE。", ""),
        ToolEntry("web_search", "联网搜索", "联网检索文档与网页内容。", ""),
        ToolEntry("web_fetch", "网页抓取", "抓取单个网页并提取标题、摘要和正文。", ""),
        ToolEntry("subagent", "子代理", "派发受限的子代理执行只读任务。", ""),
        ToolEntry("explore", "探索代理", "快速探索代码结构、关键文件和调用链。", ""),
        ToolEntry("research", "研究代理", "聚焦文档、网页和方案调研。", ""),
        ToolEntry("review", "审查代理", "聚焦 bug、回归和实现风险。", ""),
        ToolEntry("security_review", "安全审查代理", "聚焦权限边界、漏洞面和安全问题。", "")
    )
}

private fun workflowExecutionModeLabel(mode: WorkflowExecutionMode): String {
    return when (mode) {
        WorkflowExecutionMode.SINGLE_PASS -> "单次工作流优先"
    }
}

private fun workflowExecutionModeDescription(mode: WorkflowExecutionMode): String {
    return when (mode) {
        WorkflowExecutionMode.SINGLE_PASS -> "尽量把规划、分析和执行放在同一连续链路里完成。"
    }
}

private fun workflowFailureFallbackModeLabel(mode: WorkflowFailureFallbackMode): String {
    return when (mode) {
        WorkflowFailureFallbackMode.FOLLOW_SCENARIO_DEFAULT -> "跟随场景默认"
        WorkflowFailureFallbackMode.DIRECT_EXECUTION -> "统一直接执行"
        WorkflowFailureFallbackMode.LOCAL_CLARIFICATION -> "统一本地澄清"
    }
}

private fun workflowFailureFallbackModeDescription(mode: WorkflowFailureFallbackMode): String {
    return when (mode) {
        WorkflowFailureFallbackMode.FOLLOW_SCENARIO_DEFAULT -> "让每种失败类型继续沿用场景默认回退。"
        WorkflowFailureFallbackMode.DIRECT_EXECUTION -> "优先继续执行，尽量少打断当前任务。"
        WorkflowFailureFallbackMode.LOCAL_CLARIFICATION -> "改为本地统一澄清，先补齐缺失信息。"
    }
}

private fun workflowFailureTypeTitle(type: WorkflowFailureType): String {
    return when (type) {
        WorkflowFailureType.AUTO_ROUTE_FAILURE -> "自动分流失败"
        WorkflowFailureType.CLARIFICATION_GENERATION_FAILURE -> "澄清问题生成失败"
        WorkflowFailureType.CLARIFICATION_FOLLOW_UP_FAILURE -> "澄清续问判断失败"
        WorkflowFailureType.EXECUTION_INTERRUPT_FORMAT_FAILURE -> "执行中自动打断格式异常"
    }
}

private fun buildWorkflowFailureSummary(
    autoRouteEnabled: Boolean,
    fallbackMode: WorkflowFailureFallbackMode
): List<String> {
    val intro = if (autoRouteEnabled) {
        "发送前会自动判断更适合直接执行、先出计划还是先澄清。"
    } else {
        "发送后直接进入执行链路，仍可手动切换到计划或澄清。"
    }
    val details = WorkflowFailureType.entries.map { type ->
        "${workflowFailureTypeTitle(type)}: ${
            workflowFailureFallbackModeLabel(
                fallbackMode.takeIf { it != WorkflowFailureFallbackMode.FOLLOW_SCENARIO_DEFAULT }
                    ?: scenarioDefaultWorkflowFailureFallbackMode(type)
            )
        }"
    }
    return buildList {
        add(intro)
        add("统一失败回退: ${workflowFailureFallbackModeLabel(fallbackMode)}")
        addAll(details)
    }
}

private fun scenarioDefaultWorkflowFailureFallbackMode(
    type: WorkflowFailureType
): WorkflowFailureFallbackMode {
    return when (type) {
        WorkflowFailureType.AUTO_ROUTE_FAILURE,
        WorkflowFailureType.CLARIFICATION_FOLLOW_UP_FAILURE ->
            WorkflowFailureFallbackMode.DIRECT_EXECUTION
        WorkflowFailureType.CLARIFICATION_GENERATION_FAILURE,
        WorkflowFailureType.EXECUTION_INTERRUPT_FORMAT_FAILURE ->
            WorkflowFailureFallbackMode.LOCAL_CLARIFICATION
    }
}

private fun fileOperationLabel(operation: String): String {
    return when (operation) {
        "read" -> "读取文件"
        "write" -> "写入文件"
        "list" -> "列目录"
        "delete" -> "删除文件"
        "exists" -> "检查存在"
        "chmod" -> "修改权限"
        else -> operation
    }
}

private fun fileOperationDescription(operation: String): String {
    return when (operation) {
        "read" -> "允许模型按需读取文件内容。"
        "write" -> "允许模型覆盖或创建文件。"
        "list" -> "允许模型查看目录下的文件列表。"
        "delete" -> "允许模型删除文件或目录。"
        "exists" -> "允许模型检查路径是否存在。"
        "chmod" -> "允许模型修改文件权限。"
        else -> "控制该文件能力的可用性。"
    }
}

private fun formatTime(timestamp: Long): String {
    return SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
}

internal fun errorRecordTypeLabel(record: ErrorRecordUi): String {
    return when (record.kind) {
        ErrorRecordKind.FINAL_READINESS -> "最终收口阻塞"
        ErrorRecordKind.GENERAL -> "错误"
    }
}

internal fun buildFinalReadinessAuditOverviewHeadline(
    overview: FinalReadinessAuditOverview
): String {
    return "阻塞 ${overview.blockedCount} · 恢复 ${overview.recoveredCount} · 允许 ${overview.allowedCount}"
}

internal fun buildFinalReadinessAuditOverviewBreakdown(
    overview: FinalReadinessAuditOverview
): String? {
    val parts = buildList {
        if (overview.writeSignOffBlockCount > 0) {
            add("写后待签收 ${overview.writeSignOffBlockCount}")
        }
        if (overview.canonicalWorkflowBlockCount > 0) {
            add("计划未收口 ${overview.canonicalWorkflowBlockCount}")
        }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

private fun summarizeFinalReadinessOverviewStatus(
    overview: FinalReadinessAuditOverview
): String {
    return when {
        overview.currentlyBlocked -> "还有收尾动作待继续"
        overview.recoveredCount > 0 -> "最近一次异常已恢复"
        overview.allowedCount > 0 -> "最近处理正常"
        else -> "最近有收尾记录"
    }
}
