package dev.reasonix.mobile.ui.project

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LibraryAddCheck
import androidx.compose.material.icons.filled.PlaylistAddCheck
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Task
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import dev.reasonix.mobile.ui.ReasonixSecondaryPageFrame
import dev.reasonix.mobile.ui.rememberReasonixChromeColor
import dev.reasonix.mobile.ui.rememberReasonixMutedTextColor
import dev.reasonix.mobile.ui.rememberReasonixSurfaceColor

@Composable
internal fun ProjectGitHubWorkspaceOverviewPage(
    overview: ProjectGitHubWorkspaceOverviewUi,
    repoCards: List<ProjectGitHubWorkspaceRepoCardUi>,
    tokenConfigured: Boolean,
    remoteSummaryStatusLabel: String,
    isRemoteSummaryLoading: Boolean,
    remoteSummaryErrorMessage: String?,
    onRefreshRemoteSummaries: () -> Unit,
    downloads: List<ProjectGitHubDownloadRecordUi>,
    onOpenDownloadCenter: () -> Unit,
    onOpenSystemDownloads: () -> Unit,
    onOpenRepoWorkbench: (String, ProjectGitHubWorkspaceRepoWorkbenchTab) -> Unit,
    onOpenWorkflowRunDetailTarget: (String, ProjectGitHubWorkflowRunUi) -> Unit,
    onOpenIssueDetailTarget: (String, ProjectGitHubIssueUi) -> Unit,
    onOpenPullRequestDetailTarget: (String, ProjectGitHubPullRequestUi) -> Unit,
    currentFilter: ProjectGitHubWorkspaceFilterType,
    onFilterChange: (ProjectGitHubWorkspaceFilterType) -> Unit,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onOpenGlobalSearch: () -> Unit,
    onOpenGlobalTaskCenter: () -> Unit,
    isSelectionMode: Boolean,
    onToggleSelectionMode: () -> Unit,
    selectedRepoPaths: Set<String>,
    onToggleRepoSelection: (String) -> Unit,
    onBatchAction: (ProjectGitHubWorkspaceBatchAction) -> Unit,
    backProgress: Float
) {
    val chromeColor = rememberReasonixChromeColor()
    val surfaceColor = rememberReasonixSurfaceColor()
    val mutedTextColor = rememberReasonixMutedTextColor()

    ReasonixSecondaryPageFrame(
        title = "GitHub 工作区",
        subtitle = "优先回答整个工作区哪里有问题、先处理什么，再进入仓库工作台。"
    ) {
        Column(
            modifier = Modifier.graphicsLayer {
                translationX = 180f * backProgress
                alpha = 1f - (backProgress * 0.08f)
            },
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ProjectSectionCard(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                surfaceColorOverride = chromeColor.copy(alpha = 0.38f)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("工作区健康概览", style = MaterialTheme.typography.titleSmall)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(
                                onClick = onOpenGlobalTaskCenter,
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Icon(Icons.Default.Task, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("任务中心", style = MaterialTheme.typography.labelMedium)
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                            TextButton(
                                onClick = onOpenGlobalSearch,
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Icon(Icons.Default.Language, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("全局搜索", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                    Text(
                        text = "仓库 ${overview.repoCount} · Git ${overview.gitRepoCount} · GitHub ${overview.githubRepoCount} · 远端摘要 ${overview.remoteSummaryCount} · 健康 ${overview.healthyRepoCount}",
                        style = MaterialTheme.typography.bodySmall,
                        color = mutedTextColor
                    )
                    Text(
                        text = "脏工作区 ${overview.dirtyRepoCount} · 落后远端 ${overview.behindRepoCount} · 冲突 ${overview.conflictRepoCount} · 工作流异常 ${overview.failingWorkflowRepoCount}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (overview.criticalTaskCount > 0) {
                            MaterialTheme.colorScheme.error
                        } else {
                            mutedTextColor
                        }
                    )
                    Text(
                        text = "待优先处理 ${overview.criticalTaskCount} · 待跟进 ${overview.attentionTaskCount} · 有开放事项 ${overview.openWorkItemRepoCount}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (overview.attentionTaskCount > 0) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            mutedTextColor
                        }
                    )
                    Text(
                        text = "数据更新于 ${overview.lastUpdatedLabel} · $remoteSummaryStatusLabel",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (tokenConfigured) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            mutedTextColor
                        }
                    )
                    if (tokenConfigured) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (isRemoteSummaryLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.height(16.dp),
                                    strokeWidth = 2.dp
                                )
                            }
                            OutlinedButton(
                                onClick = onRefreshRemoteSummaries,
                                enabled = !isRemoteSummaryLoading
                            ) {
                                Text(if (overview.remoteSummaryCount == 0) "拉取远端摘要" else "刷新远端摘要")
                            }
                        }
                        remoteSummaryErrorMessage?.takeIf { it.isNotBlank() }?.let { error ->
                            Text(
                                text = error,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
            ProjectGitHubDownloadCenterSummaryCard(
                downloads = downloads,
                onOpenDownloadCenter = onOpenDownloadCenter,
                onOpenSystemDownloads = onOpenSystemDownloads
            )

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("搜索仓库名称或路径...", style = MaterialTheme.typography.bodyMedium) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { onSearchQueryChange("") }) {
                                Icon(Icons.Default.Clear, contentDescription = "清除搜索")
                            }
                        }
                    },
                    singleLine = true,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = chromeColor.copy(alpha = 0.15f),
                        unfocusedContainerColor = chromeColor.copy(alpha = 0.1f),
                        focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                        unfocusedBorderColor = chromeColor.copy(alpha = 0.3f)
                    ),
                    textStyle = MaterialTheme.typography.bodyMedium
                )

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "筛选仓库",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(ProjectGitHubWorkspaceFilterType.values().toList()) { filter ->
                            FilterChip(
                                selected = currentFilter == filter,
                                onClick = { onFilterChange(filter) },
                                label = { Text(filter.label) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            )
                        }
                    }
                }

            ProjectSectionCard(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                surfaceColorOverride = chromeColor.copy(alpha = 0.32f)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("仓库摘要", style = MaterialTheme.typography.titleSmall)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (currentFilter != ProjectGitHubWorkspaceFilterType.ALL) {
                                Text(
                                    text = "已过滤 (${repoCards.size})",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(end = 8.dp)
                                )
                            }
                            TextButton(
                                onClick = onToggleSelectionMode,
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Icon(
                                    if (isSelectionMode) Icons.Default.PlaylistAddCheck else Icons.Default.LibraryAddCheck,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(if (isSelectionMode) "取消多选" else "批量操作", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                    if (isSelectionMode) {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp)
                        ) {
                            items(ProjectGitHubWorkspaceBatchAction.values().toList()) { action ->
                                OutlinedButton(
                                    onClick = { onBatchAction(action) },
                                    enabled = selectedRepoPaths.isNotEmpty(),
                                    modifier = Modifier.height(32.dp),
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                    shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                                ) {
                                    Text(action.label, style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                    repoCards.forEach { card ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (isSelectionMode) {
                                Checkbox(
                                    checked = selectedRepoPaths.contains(card.rootPath),
                                    onCheckedChange = { onToggleRepoSelection(card.rootPath) },
                                    modifier = Modifier.padding(end = 4.dp)
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                ProjectGitHubWorkspaceRepoCard(
                                    card = card,
                                    surfaceColor = surfaceColor,
                                    chromeColor = chromeColor,
                                    mutedTextColor = mutedTextColor,
                                    onClick = {
                                        if (isSelectionMode) {
                                            onToggleRepoSelection(card.rootPath)
                                        } else {
                                            onOpenRepoWorkbench(
                                                card.rootPath,
                                                ProjectGitHubWorkspaceRepoWorkbenchTab.OVERVIEW
                                            )
                                        }
                                    },
                                    onOpenQuickAction = { action ->
                                        when {
                                            action.targetWorkflowRun != null ->
                                                onOpenWorkflowRunDetailTarget(card.rootPath, action.targetWorkflowRun)
                                            action.targetPullRequest != null ->
                                                onOpenPullRequestDetailTarget(card.rootPath, action.targetPullRequest)
                                            action.targetIssue != null ->
                                                onOpenIssueDetailTarget(card.rootPath, action.targetIssue)
                                            else -> onOpenRepoWorkbench(card.rootPath, action.targetTab)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProjectGitHubWorkspaceRepoCard(
    card: ProjectGitHubWorkspaceRepoCardUi,
    surfaceColor: Color,
    chromeColor: Color,
    mutedTextColor: Color,
    onClick: () -> Unit,
    onOpenQuickAction: (ProjectGitHubWorkspaceQuickActionUi) -> Unit
) {
    ProjectInsetCard(
        modifier = Modifier.clickable(onClick = onClick),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        surfaceColorOverride = if (card.isSelected) {
            surfaceColor.copy(alpha = 0.80f)
        } else {
            chromeColor.copy(alpha = 0.28f)
        }
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = card.severityLabel,
                style = MaterialTheme.typography.labelSmall,
                color = if (card.severityScore >= 90) {
                    MaterialTheme.colorScheme.error
                } else if (card.severityScore > 0) {
                    MaterialTheme.colorScheme.primary
                } else {
                    mutedTextColor
                }
            )
            Text(
                text = card.title,
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = card.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = mutedTextColor
            )
            Text(
                text = card.changeSummary,
                style = MaterialTheme.typography.labelSmall,
                color = if (card.highlightChanges) {
                    MaterialTheme.colorScheme.primary
                } else {
                    mutedTextColor
                }
            )
            card.remoteSummary?.let { remoteSummary ->
                Text(
                    text = remoteSummary,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (card.highlightRemoteSummary) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        mutedTextColor
                    }
                )
            }
            val remoteErrorMessage = card.remoteErrorMessage?.takeIf { it.isNotBlank() }
            if (remoteErrorMessage != null) {
                Text(
                    text = remoteErrorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            } else if (card.latestWorkflowSummary != null) {
                Text(
                    text = card.latestWorkflowSummary,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (card.latestWorkflowHasIssue) {
                        MaterialTheme.colorScheme.error
                    } else {
                        mutedTextColor
                    }
                )
            }
            if (card.recommendedActions.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    card.recommendedActions.forEach { action ->
                        TextButton(onClick = { onOpenQuickAction(action) }) {
                            Text(action.label)
                        }
                    }
                }
            }
        }
    }
}
