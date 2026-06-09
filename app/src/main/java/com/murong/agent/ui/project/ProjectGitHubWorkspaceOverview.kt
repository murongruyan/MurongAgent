package com.murong.agent.ui.project

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.ui.unit.dp
import com.murong.agent.ui.MurongSecondaryPageFrame
import com.murong.agent.ui.rememberMurongChromeColor
import com.murong.agent.ui.rememberMurongMutedTextColor
import com.murong.agent.ui.rememberMurongSurfaceColor

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
    val chromeColor = rememberMurongChromeColor()
    val surfaceColor = rememberMurongSurfaceColor()
    val mutedTextColor = rememberMurongMutedTextColor()
    val priorityCards = repoCards
        .filter { it.severityScore > 0 || it.conflictCount > 0 || it.latestRunHasIssue || it.behindCount > 0 }
        .sortedByDescending { it.severityScore }
        .take(3)

    MurongSecondaryPageFrame(
        title = "GitHub 工作区",
        subtitle = "优先回答整个工作区哪里有问题、先处理什么，再进入仓库工作台。"
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                                Text("T", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("任务中心", style = MaterialTheme.typography.labelMedium)
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                            TextButton(
                                onClick = onOpenGlobalSearch,
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Text("G", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
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
                        color = if (overview.criticalTaskCount > 0) MaterialTheme.colorScheme.error else mutedTextColor
                    )
                    Text(
                        text = "待优先处理 ${overview.criticalTaskCount} · 待跟进 ${overview.attentionTaskCount} · 有开放事项 ${overview.openWorkItemRepoCount}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (overview.attentionTaskCount > 0) MaterialTheme.colorScheme.primary else mutedTextColor
                    )
                    Text(
                        text = "数据更新于 ${overview.lastUpdatedLabel} · $remoteSummaryStatusLabel",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (tokenConfigured) MaterialTheme.colorScheme.primary else mutedTextColor
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
                    }
                    if (!remoteSummaryErrorMessage.isNullOrBlank()) {
                        Text(
                            text = remoteSummaryErrorMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            if (priorityCards.isNotEmpty()) {
                ProjectSectionCard(
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                    surfaceColorOverride = chromeColor.copy(alpha = 0.26f)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("异常优先", style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = "先看最需要处理的仓库，优先把冲突、工作流异常、远端落后和开放协作事项收掉。",
                            style = MaterialTheme.typography.bodySmall,
                            color = mutedTextColor
                        )
                        priorityCards.forEach { card ->
                            ProjectGitHubWorkspacePriorityRepoCard(
                                card = card,
                                chromeColor = chromeColor,
                                mutedTextColor = mutedTextColor,
                                onOpenRepoWorkbench = { tab ->
                                    onOpenRepoWorkbench(card.rootPath, tab)
                                },
                                onOpenQuickAction = { action ->
                                    when {
                                        action.targetWorkflowRun != null -> {
                                            onOpenWorkflowRunDetailTarget(card.rootPath, action.targetWorkflowRun)
                                        }
                                        action.targetPullRequest != null -> {
                                            onOpenPullRequestDetailTarget(card.rootPath, action.targetPullRequest)
                                        }
                                        action.targetIssue != null -> {
                                            onOpenIssueDetailTarget(card.rootPath, action.targetIssue)
                                        }
                                        else -> {
                                            onOpenRepoWorkbench(card.rootPath, action.targetTab)
                                        }
                                    }
                                }
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

            ProjectSectionCard(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                surfaceColorOverride = chromeColor.copy(alpha = 0.24f)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = onSearchQueryChange,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = {
                            Text("搜索仓库名称或路径...", style = MaterialTheme.typography.bodyMedium)
                        },
                        leadingIcon = {
                            Icon(Icons.Default.Search, contentDescription = null)
                        },
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
                            unfocusedContainerColor = chromeColor.copy(alpha = 0.10f),
                            focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.50f),
                            unfocusedBorderColor = chromeColor.copy(alpha = 0.30f)
                        ),
                        textStyle = MaterialTheme.typography.bodyMedium
                    )

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
                                Text(
                                    text = if (isSelectionMode) "M" else "B",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (isSelectionMode) "取消多选" else "批量操作",
                                    style = MaterialTheme.typography.labelMedium
                                )
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
                            Column(modifier = Modifier.fillMaxWidth()) {
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
                                            action.targetWorkflowRun != null -> {
                                                onOpenWorkflowRunDetailTarget(card.rootPath, action.targetWorkflowRun)
                                            }
                                            action.targetPullRequest != null -> {
                                                onOpenPullRequestDetailTarget(card.rootPath, action.targetPullRequest)
                                            }
                                            action.targetIssue != null -> {
                                                onOpenIssueDetailTarget(card.rootPath, action.targetIssue)
                                            }
                                            else -> {
                                                onOpenRepoWorkbench(card.rootPath, action.targetTab)
                                            }
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
            Text(text = card.title, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = card.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = mutedTextColor
            )
            Text(
                text = card.changeSummary,
                style = MaterialTheme.typography.labelSmall,
                color = if (card.highlightChanges) MaterialTheme.colorScheme.primary else mutedTextColor
            )
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (card.conflictCount > 0) {
                    FilterChip(
                        selected = true,
                        onClick = {},
                        label = { Text("冲突 ${card.conflictCount}") }
                    )
                }
                if (card.behindCount > 0) {
                    FilterChip(
                        selected = true,
                        onClick = {},
                        label = { Text("落后 ${card.behindCount}") }
                    )
                }
                if (card.openIssueCount > 0) {
                    FilterChip(
                        selected = true,
                        onClick = {},
                        label = { Text("Issue ${card.openIssueCount}") }
                    )
                }
                if (card.openPullRequestCount > 0) {
                    FilterChip(
                        selected = true,
                        onClick = {},
                        label = { Text("PR ${card.openPullRequestCount}") }
                    )
                }
                if (card.hasWorkingTreeChanges) {
                    FilterChip(
                        selected = true,
                        onClick = {},
                        label = { Text("本地改动") }
                    )
                }
            }

            if (!card.remoteSummary.isNullOrBlank()) {
                Text(
                    text = card.remoteSummary,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (card.highlightRemoteSummary) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        mutedTextColor
                    }
                )
            }

            if (!card.remoteErrorMessage.isNullOrBlank()) {
                Text(
                    text = card.remoteErrorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            } else if (!card.latestWorkflowSummary.isNullOrBlank()) {
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

@Composable
private fun ProjectGitHubWorkspacePriorityRepoCard(
    card: ProjectGitHubWorkspaceRepoCardUi,
    chromeColor: Color,
    mutedTextColor: Color,
    onOpenRepoWorkbench: (ProjectGitHubWorkspaceRepoWorkbenchTab) -> Unit,
    onOpenQuickAction: (ProjectGitHubWorkspaceQuickActionUi) -> Unit
) {
    ProjectInsetCard(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        surfaceColorOverride = chromeColor.copy(alpha = 0.24f)
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "${card.severityLabel} · ${card.title}",
                style = MaterialTheme.typography.bodyMedium,
                color = if (card.severityScore >= 90) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                }
            )
            Text(
                text = card.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = mutedTextColor
            )
            Text(
                text = card.remoteErrorMessage
                    ?: card.latestWorkflowSummary
                    ?: card.remoteSummary
                    ?: card.changeSummary,
                style = MaterialTheme.typography.bodySmall,
                color = if (!card.remoteErrorMessage.isNullOrBlank() || card.latestRunHasIssue) {
                    MaterialTheme.colorScheme.error
                } else {
                    mutedTextColor
                }
            )
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (card.conflictCount > 0) {
                    FilterChip(
                        selected = true,
                        onClick = { onOpenRepoWorkbench(ProjectGitHubWorkspaceRepoWorkbenchTab.OVERVIEW) },
                        label = { Text("冲突 ${card.conflictCount}") }
                    )
                }
                if (card.behindCount > 0) {
                    FilterChip(
                        selected = true,
                        onClick = { onOpenRepoWorkbench(ProjectGitHubWorkspaceRepoWorkbenchTab.OVERVIEW) },
                        label = { Text("落后 ${card.behindCount}") }
                    )
                }
                if (card.openIssueCount > 0) {
                    FilterChip(
                        selected = true,
                        onClick = { onOpenRepoWorkbench(ProjectGitHubWorkspaceRepoWorkbenchTab.ISSUES) },
                        label = { Text("Issue ${card.openIssueCount}") }
                    )
                }
                if (card.openPullRequestCount > 0) {
                    FilterChip(
                        selected = true,
                        onClick = { onOpenRepoWorkbench(ProjectGitHubWorkspaceRepoWorkbenchTab.PULL_REQUESTS) },
                        label = { Text("PR ${card.openPullRequestCount}") }
                    )
                }
                if (card.latestRunHasIssue) {
                    FilterChip(
                        selected = true,
                        onClick = { onOpenRepoWorkbench(ProjectGitHubWorkspaceRepoWorkbenchTab.WORKFLOW) },
                        label = { Text("工作流异常") }
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onOpenRepoWorkbench(ProjectGitHubWorkspaceRepoWorkbenchTab.OVERVIEW) }) {
                    Text("打开仓库")
                }
                card.recommendedActions.take(2).forEach { action ->
                    TextButton(onClick = { onOpenQuickAction(action) }) {
                        Text(action.label)
                    }
                }
            }
        }
    }
}
