@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.murong.agent.ui.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.murong.agent.core.mcp.McpConfigSource
import com.murong.agent.core.mcp.McpServerConfig
import com.murong.agent.core.mcp.McpServerStatus
import com.murong.agent.core.mcp.McpTransportType
import com.murong.agent.core.mcp.canonicalMcpToolName
import com.murong.agent.automation.ExternalWorkflowContract
import com.murong.agent.ui.settings.ExternalWorkflowAutomationUiState
import com.murong.agent.ui.PendingApprovalSummaryCard
import com.murong.agent.ui.MurongDialog
import com.murong.agent.ui.MurongGlassSurface
import com.murong.agent.ui.MurongInfoCard
import com.murong.agent.ui.MurongInteractionPerformanceHint
import com.murong.agent.ui.MurongLargeDialogCardShape
import com.murong.agent.ui.MurongLargeDialogScaffold
import com.murong.agent.ui.rememberMurongBottomBarScrollPadding
import com.murong.agent.ui.MurongPopupSurface
import com.murong.agent.ui.MurongPopupCardShape
import com.murong.agent.ui.MurongPrimaryPageSurface
import com.murong.agent.ui.buildApprovalModeOptionPresentations
import com.murong.agent.ui.buildApprovalPostureCopyPresentation
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

private data class ToolEntry(
    val name: String,
    val title: String,
    val description: String,
    val status: String
)

private data class SkillUsageAuditSummary(
    val totalCount: Int,
    val recentSkillTitles: List<String>,
    val recentTasks: List<String>
)

@Composable
internal fun ToolsScreen(
    config: ProviderConfig,
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
    val bottomBarScrollPadding = rememberMurongBottomBarScrollPadding()
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
    val subagentPresetNames = remember { listOf("explore", "research", "review", "security_review") }
    val mcpConfigsByName = remember(mcpServers) { mcpServers.associateBy { it.name } }

    LaunchedEffect(Unit) { onRefreshSavedWorkflows() }

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
    val builtInTools = remember(config, pendingApprovalPresentation, checkpointPresentation.fileChanges) {
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
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
    ) {
        LazyColumn(
            state = toolsListState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 2.dp, bottom = bottomBarScrollPadding),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                SectionTitleWithAction(
                    title = "内置工具",
                    subtitle = "把常用开关直接收在这一页，少弹窗、少跳转。",
                    action = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text("权限编辑", style = MaterialTheme.typography.bodySmall)
                            Switch(
                                checked = showInlineToolAccess,
                                onCheckedChange = { showInlineToolAccess = it }
                            )
                        }
                    }
                )
            }
            items(builtInTools, key = { it.name }) { tool ->
                if (tool.name == "subagent") {
                    CollapsibleToolGroupCard(
                        tool = tool,
                        checked = config.isBuiltinToolEnabled(tool.name),
                        expanded = showSubagentGroup,
                        onCheckedChange = { updateBuiltinToolEnabled(tool.name, it) },
                        onExpandedChange = { showSubagentGroup = it }
                    ) {
                        subagentPresetTools.forEach { preset ->
                            ToolToggleRow(
                                tool = preset,
                                checked = config.isBuiltinToolEnabled(preset.name),
                                enabled = config.isBuiltinToolEnabled("subagent"),
                                onCheckedChange = { updateBuiltinToolEnabled(preset.name, it) }
                            )
                        }
                    }
                } else {
                    ToolToggleCard(
                        tool = tool,
                        checked = config.isBuiltinToolEnabled(tool.name),
                        onCheckedChange = { updateBuiltinToolEnabled(tool.name, it) }
                    )
                }
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
                        if (workflow.backgroundEligibility() == SavedWorkflowBackgroundEligibility.ALLOWED_READ_ONLY) {
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
                ApprovalPostureCard(
                    overview = approvalPresentation.postureOverview,
                )
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
            item {
                Spacer(modifier = Modifier.height(24.dp))
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
