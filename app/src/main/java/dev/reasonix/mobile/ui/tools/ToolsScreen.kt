@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.reasonix.mobile.ui.tools

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.reasonix.mobile.core.config.DEFAULT_ENABLED_FILE_TOOL_OPERATIONS
import dev.reasonix.mobile.core.config.ProjectToolPreferences
import dev.reasonix.mobile.core.config.ProviderConfig
import dev.reasonix.mobile.core.config.ToolApprovalMode
import dev.reasonix.mobile.core.config.WorkflowFailureFallbackMode
import dev.reasonix.mobile.core.config.WorkflowFailureType
import dev.reasonix.mobile.core.config.WorkflowExecutionMode
import dev.reasonix.mobile.core.loop.ApprovalRecordUi
import dev.reasonix.mobile.core.loop.ConversationCheckpointUi
import dev.reasonix.mobile.core.loop.ErrorRecordUi
import dev.reasonix.mobile.core.loop.FileChangeRecordUi
import dev.reasonix.mobile.core.loop.PendingApprovalUi
import dev.reasonix.mobile.core.loop.ToolCallRecordUi
import dev.reasonix.mobile.core.mcp.McpServerStatus
import dev.reasonix.mobile.ui.PendingApprovalSummaryCard
import dev.reasonix.mobile.ui.ReasonixDialog
import dev.reasonix.mobile.ui.ReasonixGlassSurface
import dev.reasonix.mobile.ui.ReasonixInfoCard
import dev.reasonix.mobile.ui.ReasonixLargeDialogScaffold
import dev.reasonix.mobile.ui.rememberReasonixBottomBarScrollPadding
import dev.reasonix.mobile.ui.ReasonixPopupSurface
import dev.reasonix.mobile.ui.ReasonixPrimaryPageSurface
import dev.reasonix.mobile.ui.toPendingApprovalPresentation
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class ToolEntry(
    val name: String,
    val title: String,
    val description: String,
    val status: String
)

@Composable
fun ToolsScreen(
    config: ProviderConfig,
    currentProjectPath: String?,
    projectRuleCount: Int,
    projectMemoryCount: Int,
    projectSkillCount: Int,
    rootStatus: Boolean?,
    isCheckingRoot: Boolean,
    onCheckRoot: () -> Unit,
    pendingApproval: PendingApprovalUi?,
    recentApprovals: List<ApprovalRecordUi>,
    recentErrors: List<ErrorRecordUi>,
    recentToolCalls: List<ToolCallRecordUi>,
    checkpoints: List<ConversationCheckpointUi>,
    fileChanges: List<FileChangeRecordUi>,
    onOpenChat: () -> Unit,
    onApprovePendingTool: () -> Unit,
    onRejectPendingTool: () -> Unit,
    onRollbackFileCheckpoint: (String) -> Unit,
    mcpStatuses: List<McpServerStatus>,
    mcpConnectError: String?,
    onConnectMcpServers: () -> Unit,
    onRefreshMcpStatus: () -> Unit,
    onUpdateConfig: (ProviderConfig) -> Unit
) {
    val bottomBarScrollPadding = rememberReasonixBottomBarScrollPadding()
    var showApprovalDetail by remember(pendingApproval?.toolName, pendingApproval?.rawArgs) {
        mutableStateOf(false)
    }
    var showApprovalPolicyEditor by remember { mutableStateOf(false) }
    var showWorkflowExecutionEditor by remember { mutableStateOf(false) }
    var showToolAccessEditor by remember { mutableStateOf(false) }
    var selectedCheckpoint by remember { mutableStateOf<ConversationCheckpointUi?>(null) }
    var selectedRecord by remember { mutableStateOf<FileChangeRecordUi?>(null) }
    var selectedToolCall by remember { mutableStateOf<ToolCallRecordUi?>(null) }
    var selectedError by remember { mutableStateOf<ErrorRecordUi?>(null) }
    var selectedMcpStatus by remember { mutableStateOf<McpServerStatus?>(null) }

    val mcpToolNames = remember(mcpStatuses) {
        mcpStatuses.flatMap { status -> status.toolNames.map { "mcp_$it" } }.distinct().sorted()
    }
    val builtInTools = remember(config, pendingApproval, fileChanges) {
        listOf(
            ToolEntry(
                name = "file",
                title = "文件工具",
                description = "当前开放 ${config.getEnabledFileToolOperations().size}/${DEFAULT_ENABLED_FILE_TOOL_OPERATIONS.size} 个文件操作。",
                status = when {
                    !config.isBuiltinToolEnabled("file") -> "已禁用"
                    fileChanges.isNotEmpty() -> "最近改动 ${fileChanges.size}"
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
                    pendingApproval?.toolName == "shell" -> "等待审批"
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
                name = "subagent",
                title = "子代理",
                description = "子任务分发与摘要回传。",
                status = if (config.isBuiltinToolEnabled("subagent")) "已启用" else "已禁用"
            )
        )
    }

    ReasonixPrimaryPageSurface(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 2.dp, bottom = bottomBarScrollPadding),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                SectionTitle(
                    title = "内置工具",
                    subtitle = "集中查看当前工具开关、审批、工作流和 MCP 状态。"
                )
            }
            items(builtInTools, key = { it.name }) { tool ->
                ToolEntryCard(tool = tool)
            }
            item {
                WorkflowCard(
                    config = config,
                    onManageWorkflow = { showWorkflowExecutionEditor = true },
                    onManageApproval = { showApprovalPolicyEditor = true },
                    onManageTools = { showToolAccessEditor = true }
                )
            }
            item {
                ApprovalCard(
                    pendingApproval = pendingApproval,
                    recentApprovals = recentApprovals,
                    onOpenChat = onOpenChat,
                    onOpenDetail = { showApprovalDetail = true },
                    onApprove = onApprovePendingTool,
                    onReject = onRejectPendingTool
                )
            }
            item {
                ProjectPreferenceCard(
                    currentProjectPath = currentProjectPath,
                    projectRuleCount = projectRuleCount,
                    projectMemoryCount = projectMemoryCount,
                    projectSkillCount = projectSkillCount,
                    mcpServerCount = mcpStatuses.size,
                    availableMcpToolCount = mcpToolNames.size
                )
            }
            item {
                AuditCard(
                    recentToolCalls = recentToolCalls,
                    recentErrors = recentErrors,
                    onOpenToolCall = { selectedToolCall = it },
                    onOpenError = { selectedError = it }
                )
            }
            item {
                FileChangeCard(
                    checkpoints = checkpoints,
                    fileChanges = fileChanges,
                    onOpenCheckpoint = { selectedCheckpoint = it },
                    onOpenRecord = { selectedRecord = it },
                    onRollbackCheckpoint = onRollbackFileCheckpoint
                )
            }
            item {
                McpCard(
                    mcpStatuses = mcpStatuses,
                    mcpConnectError = mcpConnectError,
                    onConnectMcpServers = onConnectMcpServers,
                    onRefreshMcpStatus = onRefreshMcpStatus,
                    onOpenStatus = { selectedMcpStatus = it }
                )
            }
            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    if (showApprovalDetail && pendingApproval != null) {
        PendingApprovalSheet(
            approval = pendingApproval,
            onDismiss = { showApprovalDetail = false },
            onApprove = {
                showApprovalDetail = false
                onApprovePendingTool()
            },
            onReject = {
                showApprovalDetail = false
                onRejectPendingTool()
            }
        )
    }
    if (showApprovalPolicyEditor) {
        ApprovalPolicyEditorDialog(
            currentMode = config.approvalMode,
            onDismiss = { showApprovalPolicyEditor = false },
            onSave = { mode ->
                showApprovalPolicyEditor = false
                onUpdateConfig(config.copy(approvalMode = mode))
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
    if (showToolAccessEditor) {
        ToolAccessEditorDialog(
            config = config,
            mcpToolNames = mcpToolNames,
            onDismiss = { showToolAccessEditor = false },
            onSave = { updatedConfig ->
                showToolAccessEditor = false
                onUpdateConfig(updatedConfig)
            }
        )
    }
    selectedCheckpoint?.let { checkpoint ->
        CheckpointDetailSheet(
            checkpoint = checkpoint,
            records = fileChanges.filter { it.checkpointId == checkpoint.id },
            onDismiss = { selectedCheckpoint = null },
            onRollbackCheckpoint = {
                selectedCheckpoint = null
                onRollbackFileCheckpoint(checkpoint.id)
            },
            onOpenRecord = { record -> selectedRecord = record }
        )
    }
    selectedRecord?.let { record ->
        FileChangeDetailSheet(record = record, onDismiss = { selectedRecord = null })
    }
    selectedToolCall?.let { record ->
        ToolCallDetailSheet(record = record, onDismiss = { selectedToolCall = null })
    }
    selectedError?.let { record ->
        ErrorDetailSheet(record = record, onDismiss = { selectedError = null })
    }
    selectedMcpStatus?.let { status ->
        McpStatusDetailSheet(status = status, onDismiss = { selectedMcpStatus = null })
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
    onManageApproval: () -> Unit,
    onManageTools: () -> Unit
) {
    ToolsPanelCard {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("执行策略", style = MaterialTheme.typography.titleMedium)
            KeyValueRow("审批模式", approvalModeLabel(config.approvalMode))
            KeyValueRow("工作流模式", workflowExecutionModeLabel(config.workflowExecutionMode))
            KeyValueRow("发送前自动分流", if (config.autoRouteBeforeExecution) "开启" else "关闭")
            KeyValueRow("失败回退", workflowFailureFallbackModeLabel(config.getFailureFallbackMode()))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onManageWorkflow) { Text("工作流") }
                FilledTonalButton(onClick = onManageApproval) { Text("审批") }
                FilledTonalButton(onClick = onManageTools) { Text("工具权限") }
            }
        }
    }
}

@Composable
private fun ApprovalCard(
    pendingApproval: PendingApprovalUi?,
    recentApprovals: List<ApprovalRecordUi>,
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
            Text("审批状态", style = MaterialTheme.typography.titleMedium)
            if (pendingApproval != null) {
                Text("等待审批: ${pendingApproval.toolName}", style = MaterialTheme.typography.bodyLarge)
                Text(pendingApproval.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = onOpenDetail) { Text("查看详情") }
                    FilledTonalButton(onClick = onApprove) { Text("批准") }
                    TextButton(onClick = onReject) { Text("拒绝") }
                }
            } else {
                Text("当前没有待审批工具调用。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FilledTonalButton(onClick = onOpenChat) { Text("回到对话") }
            }
            if (recentApprovals.isNotEmpty()) {
                Text("最近审批", style = MaterialTheme.typography.labelLarge)
                recentApprovals.take(3).forEach { record ->
                    CompactListRow(
                        title = "${record.toolName} · ${record.decision}",
                        subtitle = record.summary
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
    projectSkillCount: Int,
    mcpServerCount: Int,
    availableMcpToolCount: Int
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
            KeyValueRow("MCP 服务器", "$mcpServerCount")
            KeyValueRow("MCP 工具", "$availableMcpToolCount")
        }
    }
}

@Composable
private fun AuditCard(
    recentToolCalls: List<ToolCallRecordUi>,
    recentErrors: List<ErrorRecordUi>,
    onOpenToolCall: (ToolCallRecordUi) -> Unit,
    onOpenError: (ErrorRecordUi) -> Unit
) {
    ToolsPanelCard {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("工具审计", style = MaterialTheme.typography.titleMedium)
            KeyValueRow("最近工具调用", "${recentToolCalls.size}")
            KeyValueRow("最近错误", "${recentErrors.size}")
            recentToolCalls.take(3).forEach { record ->
                ClickableListRow(
                    title = "${record.toolName} · ${if (record.isSuccess) "成功" else "失败"}",
                    subtitle = record.args,
                    onClick = { onOpenToolCall(record) }
                )
            }
            recentErrors.take(2).forEach { record ->
                ClickableListRow(
                    title = "错误 · ${formatTime(record.timestamp)}",
                    subtitle = record.message,
                    onClick = { onOpenError(record) }
                )
            }
        }
    }
}

@Composable
private fun FileChangeCard(
    checkpoints: List<ConversationCheckpointUi>,
    fileChanges: List<FileChangeRecordUi>,
    onOpenCheckpoint: (ConversationCheckpointUi) -> Unit,
    onOpenRecord: (FileChangeRecordUi) -> Unit,
    onRollbackCheckpoint: (String) -> Unit
) {
    ToolsPanelCard {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("文件修改", style = MaterialTheme.typography.titleMedium)
            KeyValueRow("检查点", "${checkpoints.size}")
            KeyValueRow("文件改动", "${fileChanges.size}")
            checkpoints.take(3).forEach { checkpoint ->
                ClickableListRow(
                    title = checkpoint.summary,
                    subtitle = "变更 ${checkpoint.changedFiles.size} 个文件 · ${formatTime(checkpoint.createdAt)}",
                    trailing = "回滚",
                    onTrailingClick = { onRollbackCheckpoint(checkpoint.id) },
                    onClick = { onOpenCheckpoint(checkpoint) }
                )
            }
            fileChanges.take(3).forEach { record ->
                ClickableListRow(
                    title = record.operation,
                    subtitle = record.path,
                    onClick = { onOpenRecord(record) }
                )
            }
        }
    }
}

@Composable
private fun McpCard(
    mcpStatuses: List<McpServerStatus>,
    mcpConnectError: String?,
    onConnectMcpServers: () -> Unit,
    onRefreshMcpStatus: () -> Unit,
    onOpenStatus: (McpServerStatus) -> Unit
) {
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
            if (mcpStatuses.isEmpty()) {
                Text("暂未连接 MCP 服务器。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                mcpStatuses.forEach { status ->
                    ClickableListRow(
                        title = "${status.name} · ${if (status.connected) "已连接" else "未连接"}",
                        subtitle = "工具 ${status.toolCount}${status.error?.let { " · $it" } ?: ""}",
                        onClick = { onOpenStatus(status) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolEntryCard(tool: ToolEntry) {
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
            StatusBadge(text = tool.status, color = MaterialTheme.colorScheme.primary)
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
    ReasonixGlassSurface(
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
    ReasonixDialog(onDismissRequest = onDismissRequest) {
        ReasonixPopupSurface(
            shape = RoundedCornerShape(24.dp),
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
    ReasonixLargeDialogScaffold(
        onDismissRequest = onDismissRequest,
        modifier = modifier
    ) {
        ReasonixGlassSurface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(24.dp),
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
private fun PendingApprovalSheet(
    approval: PendingApprovalUi,
    onDismiss: () -> Unit,
    onApprove: () -> Unit,
    onReject: () -> Unit
) {
    val presentation = remember(approval.toolName, approval.summary, approval.detail, approval.rawArgs) {
        approval.toPendingApprovalPresentation()
    }
    ToolsLargeDialog(
        title = "待审批工具调用",
        subtitle = approval.toolName,
        onDismissRequest = onDismiss,
        actions = {
            TextButton(onClick = onReject) { Text(presentation.rejectLabel) }
            TextButton(onClick = onDismiss) { Text("关闭") }
            FilledTonalButton(onClick = onApprove) { Text(presentation.approveLabel) }
        }
    ) {
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("工具: ${approval.toolName}")
                Text("摘要: ${presentation.headline}")
                Text("风险: ${approval.riskLevel}")
                PendingApprovalSummaryCard(presentation = presentation)
                approval.explanationLabel?.let { Text("原因: $it") }
                approval.explanationDetail?.let { Text(it) }
                Text(presentation.rawArgsLabel, style = MaterialTheme.typography.labelLarge)
                CodeBlock(approval.rawArgs)
            }
        }
    }
}

@Composable
private fun ApprovalPolicyEditorDialog(
    currentMode: ToolApprovalMode,
    onDismiss: () -> Unit,
    onSave: (ToolApprovalMode) -> Unit
) {
    var selectedMode by remember(currentMode) { mutableStateOf(currentMode) }
    ToolsPopupDialog(
        title = "审批模式",
        onDismissRequest = onDismiss,
        actions = {
            TextButton(onClick = onDismiss) { Text("取消") }
            FilledTonalButton(onClick = { onSave(selectedMode) }) { Text("保存") }
        }
    ) {
        ToolApprovalMode.entries.forEach { mode ->
            SelectableRow(
                title = approvalModeLabel(mode),
                subtitle = approvalModeDescription(mode),
                selected = selectedMode == mode,
                onClick = { selectedMode = mode }
            )
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
    checkpoint: ConversationCheckpointUi,
    records: List<FileChangeRecordUi>,
    onDismiss: () -> Unit,
    onRollbackCheckpoint: () -> Unit,
    onOpenRecord: (FileChangeRecordUi) -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(checkpoint.summary, style = MaterialTheme.typography.titleMedium)
            Text("创建时间: ${formatTime(checkpoint.createdAt)}", style = MaterialTheme.typography.bodySmall)
            checkpoint.changedFiles.forEach { path ->
                Text("• $path", style = MaterialTheme.typography.bodySmall)
            }
            FilledTonalButton(onClick = onRollbackCheckpoint) { Text("回滚这个检查点") }
            records.forEach { record ->
                ClickableListRow(
                    title = record.operation,
                    subtitle = record.path,
                    onClick = { onOpenRecord(record) }
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun FileChangeDetailSheet(record: FileChangeRecordUi, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        DetailSheetContent(
            title = "${record.operation} · ${record.path}",
            subtitle = "时间 ${formatTime(record.changedAt)}",
            content = record.diffPreview.ifBlank {
                record.afterContent ?: record.beforeContent ?: "没有可展示内容"
            }
        )
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
        subtitle = formatTime(record.timestamp),
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
private fun McpStatusDetailSheet(status: McpServerStatus, onDismiss: () -> Unit) {
    ToolsPopupDialog(
        title = "MCP 状态",
        subtitle = status.name,
        onDismissRequest = onDismiss,
        actions = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("连接状态: ${if (status.connected) "已连接" else "未连接"}")
            Text("工具数量: ${status.toolCount}")
            status.error?.let { Text("错误: $it", color = MaterialTheme.colorScheme.error) }
            if (status.toolNames.isNotEmpty()) {
                Text("工具列表", style = MaterialTheme.typography.labelLarge)
                status.toolNames.forEach { Text("• $it") }
            }
        }
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
                text = content,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

private fun builtInToolCatalog(): List<ToolEntry> {
    return listOf(
        ToolEntry("shell", "命令工具", "执行 root shell 命令。", ""),
        ToolEntry("file", "文件工具", "读写、列目录、删除和 chmod。", ""),
        ToolEntry("code_edit", "代码编辑", "查看文件并执行 SEARCH/REPLACE。", ""),
        ToolEntry("web_search", "联网搜索", "联网检索文档与网页内容。", ""),
        ToolEntry("web_fetch", "网页抓取", "抓取单个网页并提取标题、摘要和正文。", ""),
        ToolEntry("subagent", "子代理", "派发受限的子代理执行只读任务。", "")
    )
}

private fun approvalModeLabel(mode: ToolApprovalMode): String {
    return when (mode) {
        ToolApprovalMode.READ_ONLY -> "只读模式"
        ToolApprovalMode.ALL_APPROVAL -> "全部审批"
        ToolApprovalMode.WHITELIST_AUTO -> "白名单自动通过"
        ToolApprovalMode.ALL_AUTO -> "全部自动通过"
    }
}

private fun approvalModeDescription(mode: ToolApprovalMode): String {
    return when (mode) {
        ToolApprovalMode.READ_ONLY -> "仅允许只读类能力自动运行，写入和执行类操作会被明显收紧。"
        ToolApprovalMode.ALL_APPROVAL -> "所有工具调用都需要你显式确认后才会继续。"
        ToolApprovalMode.WHITELIST_AUTO -> "命中白名单的操作可自动通过，其余请求仍然审批。"
        ToolApprovalMode.ALL_AUTO -> "默认不再弹审批，适合你完全信任当前任务时使用。"
    }
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
