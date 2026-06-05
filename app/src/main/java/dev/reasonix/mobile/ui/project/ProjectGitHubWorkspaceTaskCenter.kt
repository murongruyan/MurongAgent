package dev.reasonix.mobile.ui.project

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import dev.reasonix.mobile.ui.ReasonixAlertDialog
import dev.reasonix.mobile.ui.rememberReasonixMutedTextColor
import dev.reasonix.mobile.ui.rememberReasonixSurfaceColor

@Composable
internal fun ProjectGitHubWorkspaceTaskCenterDialog(
    taskCenter: ProjectGitHubGlobalTaskCenterUi,
    onClose: () -> Unit,
    onTaskClick: (ProjectGitHubWorkspaceTaskUi) -> Unit,
    chromeColor: Color
) {
    val surfaceColor = rememberReasonixSurfaceColor()
    val mutedTextColor = rememberReasonixMutedTextColor()
    val allTasks = remember(taskCenter.criticalTasks, taskCenter.attentionTasks) {
        taskCenter.criticalTasks + taskCenter.attentionTasks
    }
    var query by remember(taskCenter.criticalTasks, taskCenter.attentionTasks) { mutableStateOf("") }
    var selectedKind by remember(taskCenter.criticalTasks, taskCenter.attentionTasks) {
        mutableStateOf<ProjectGitHubWorkspaceTaskKind?>(null)
    }
    val kindSummary = remember(allTasks) {
        allTasks.groupingBy { it.kind }.eachCount().entries.sortedByDescending { it.value }.take(5)
    }
    val filteredCriticalTasks = remember(taskCenter.criticalTasks, query, selectedKind) {
        filterProjectGitHubWorkspaceTasks(
            tasks = taskCenter.criticalTasks,
            query = query,
            selectedKind = selectedKind
        )
    }
    val filteredAttentionTasks = remember(taskCenter.attentionTasks, query, selectedKind) {
        filterProjectGitHubWorkspaceTasks(
            tasks = taskCenter.attentionTasks,
            query = query,
            selectedKind = selectedKind
        )
    }
    val filteredTasks = remember(filteredCriticalTasks, filteredAttentionTasks) {
        filteredCriticalTasks + filteredAttentionTasks
    }
    val shortcuts = remember(filteredTasks) {
        buildProjectGitHubWorkspaceTaskShortcuts(filteredTasks)
    }

    ReasonixAlertDialog(
        onDismissRequest = onClose,
        confirmButton = {
            TextButton(onClick = onClose) {
                Text("关闭")
            }
        },
        dismissButton = {},
        title = { Text("任务中心") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 500.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ProjectInsetCard(
                    shape = RoundedCornerShape(12.dp),
                    surfaceColorOverride = chromeColor.copy(alpha = 0.30f)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("待处理摘要", style = MaterialTheme.typography.labelMedium)
                        Text(
                            text = "优先处理 ${taskCenter.criticalTasks.size} · 待跟进 ${taskCenter.attentionTasks.size} · 总计 ${allTasks.size}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (taskCenter.criticalTasks.isNotEmpty()) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primary
                            }
                        )
                        if (kindSummary.isNotEmpty()) {
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                kindSummary.forEach { (kind, count) ->
                                    FilterChip(
                                        selected = true,
                                        onClick = {},
                                        label = { Text("${kind.label} $count") }
                                    )
                                }
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("搜索任务") },
                    placeholder = { Text("按仓库名、标题或说明筛选") },
                    leadingIcon = {
                        Icon(Icons.Default.Search, contentDescription = null)
                    },
                    trailingIcon = {
                        if (query.isNotBlank()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = "清空搜索")
                            }
                        }
                    },
                    singleLine = true
                )
                if (kindSummary.isNotEmpty()) {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = selectedKind == null,
                            onClick = { selectedKind = null },
                            label = { Text("全部") }
                        )
                        kindSummary.forEach { (kind, count) ->
                            FilterChip(
                                selected = selectedKind == kind,
                                onClick = {
                                    selectedKind = if (selectedKind == kind) null else kind
                                },
                                label = { Text("${kind.label} $count") }
                            )
                        }
                    }
                }
                if (shortcuts.isNotEmpty()) {
                    ProjectInsetCard(
                        shape = RoundedCornerShape(12.dp),
                        surfaceColorOverride = surfaceColor.copy(alpha = 0.54f)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("快速处理", style = MaterialTheme.typography.labelMedium)
                            Text(
                                text = "直接打开当前筛选条件下最靠前的一条任务，减少来回找入口。",
                                style = MaterialTheme.typography.bodySmall,
                                color = mutedTextColor
                            )
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                shortcuts.forEach { shortcut ->
                                    OutlinedButton(onClick = { onTaskClick(shortcut.task) }) {
                                        Text("${shortcut.label} ${shortcut.count}")
                                    }
                                }
                            }
                        }
                    }
                }

                ProjectGitHubWorkspaceTaskGroup(
                    title = "优先处理 ${filteredCriticalTasks.size}",
                    tasks = filteredCriticalTasks,
                    emptyText = if (query.isBlank() && selectedKind == null) {
                        "当前没有必须立刻处理的仓库任务。"
                    } else {
                        "没有符合当前筛选条件的优先任务。"
                    },
                    chromeColor = chromeColor,
                    surfaceColor = surfaceColor,
                    mutedTextColor = mutedTextColor,
                    onTaskClick = onTaskClick
                )
                ProjectGitHubWorkspaceTaskGroup(
                    title = "待跟进 ${filteredAttentionTasks.size}",
                    tasks = filteredAttentionTasks,
                    emptyText = if (query.isBlank() && selectedKind == null) {
                        "当前没有待跟进任务。"
                    } else {
                        "没有符合当前筛选条件的待跟进任务。"
                    },
                    chromeColor = chromeColor,
                    surfaceColor = surfaceColor,
                    mutedTextColor = mutedTextColor,
                    onTaskClick = onTaskClick
                )
            }
        }
    )
}

@Composable
private fun ProjectGitHubWorkspaceTaskGroup(
    title: String,
    tasks: List<ProjectGitHubWorkspaceTaskUi>,
    emptyText: String,
    chromeColor: Color,
    surfaceColor: Color,
    mutedTextColor: Color,
    onTaskClick: (ProjectGitHubWorkspaceTaskUi) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        if (tasks.isEmpty()) {
            Text(
                text = emptyText,
                style = MaterialTheme.typography.bodySmall,
                color = mutedTextColor
            )
        } else {
            tasks.forEach { task ->
                ProjectInsetCard(
                    shape = RoundedCornerShape(12.dp),
                    surfaceColorOverride = if (task.isCritical) {
                        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.42f)
                    } else {
                        surfaceColor.copy(alpha = 0.58f)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onTaskClick(task) }
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "${task.kind.label} · ${task.title}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (task.isCritical) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            }
                        )
                        Text(
                            text = task.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = mutedTextColor
                        )
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            task.repoTitle.takeIf { it.isNotBlank() }?.let { repoTitle ->
                                FilterChip(
                                    selected = true,
                                    onClick = {},
                                    label = { Text(repoTitle) }
                                )
                            }
                            task.destinationLabel?.takeIf { it.isNotBlank() }?.let { destination ->
                                FilterChip(
                                    selected = true,
                                    onClick = {},
                                    label = { Text(destination) }
                                )
                            }
                            FilterChip(
                                selected = true,
                                onClick = {},
                                label = { Text(if (task.isCritical) "优先处理" else "待跟进") }
                            )
                            FilterChip(
                                selected = true,
                                onClick = {},
                                label = { Text(task.actionLabel.ifBlank { "打开" }) }
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { onTaskClick(task) }) {
                                Text(task.actionLabel.ifBlank { "打开" })
                            }
                            Text(
                                text = "点按卡片也可直接进入",
                                style = MaterialTheme.typography.labelSmall,
                                color = chromeColor
                            )
                        }
                    }
                }
            }
        }
    }
}

private data class ProjectGitHubWorkspaceTaskShortcutUi(
    val label: String,
    val count: Int,
    val task: ProjectGitHubWorkspaceTaskUi
)

private fun buildProjectGitHubWorkspaceTaskShortcuts(
    tasks: List<ProjectGitHubWorkspaceTaskUi>
): List<ProjectGitHubWorkspaceTaskShortcutUi> {
    val preferredKinds = listOf(
        ProjectGitHubWorkspaceTaskKind.LOCAL_CONFLICT,
        ProjectGitHubWorkspaceTaskKind.WORKFLOW,
        ProjectGitHubWorkspaceTaskKind.COLLABORATION,
        ProjectGitHubWorkspaceTaskKind.SYNC,
        ProjectGitHubWorkspaceTaskKind.REMOTE_ERROR,
        ProjectGitHubWorkspaceTaskKind.REMOTE_BINDING,
        ProjectGitHubWorkspaceTaskKind.LOCAL_CHANGES
    )
    return preferredKinds.mapNotNull { kind ->
        val matched = tasks.filter { it.kind == kind }
        val firstTask = matched.firstOrNull() ?: return@mapNotNull null
        ProjectGitHubWorkspaceTaskShortcutUi(
            label = kind.label,
            count = matched.size,
            task = firstTask
        )
    }
}

private fun filterProjectGitHubWorkspaceTasks(
    tasks: List<ProjectGitHubWorkspaceTaskUi>,
    query: String,
    selectedKind: ProjectGitHubWorkspaceTaskKind?
): List<ProjectGitHubWorkspaceTaskUi> {
    val normalizedQuery = query.trim()
    return tasks.filter { task ->
        val matchesKind = selectedKind == null || task.kind == selectedKind
        val matchesQuery = normalizedQuery.isBlank() || buildString {
            append(task.title)
            append('\n')
            append(task.subtitle)
            append('\n')
            append(task.repoTitle)
            append('\n')
            append(task.destinationLabel.orEmpty())
        }.contains(normalizedQuery, ignoreCase = true)
        matchesKind && matchesQuery
    }
}
