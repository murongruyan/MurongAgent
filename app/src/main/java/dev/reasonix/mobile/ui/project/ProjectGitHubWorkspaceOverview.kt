package dev.reasonix.mobile.ui.project

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
    val githubColors = rememberGitHubColors()
    val priorityCards = repoCards
        .filter { it.severityScore > 0 || it.conflictCount > 0 || it.latestRunHasIssue || it.behindCount > 0 }
        .sortedByDescending { it.severityScore }
        .take(3)

    ReasonixSecondaryPageFrame(
        title = "GitHub 工作区",
        subtitle = "工作区概览与异常聚合"
    ) {
        Column(
            modifier = Modifier.graphicsLayer {
                translationX = 180f * backProgress
                alpha = 1f - (backProgress * 0.08f)
            },
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            GitHubCard {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("健康概览", style = MaterialTheme.typography.titleMedium)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = onOpenGlobalTaskCenter) {
                                Text("任务中心", color = githubColors.accent)
                            }
                            TextButton(onClick = onOpenGlobalSearch) {
                                Text("全局搜索", color = githubColors.accent)
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("仓库总数", style = MaterialTheme.typography.labelSmall, color = githubColors.mutedText)
                            Text("${overview.repoCount}", style = MaterialTheme.typography.titleMedium)
                        }
                        Column {
                            Text("Git 仓库", style = MaterialTheme.typography.labelSmall, color = githubColors.mutedText)
                            Text("${overview.gitRepoCount}", style = MaterialTheme.typography.titleMedium)
                        }
                        Column {
                            Text("健康", style = MaterialTheme.typography.labelSmall, color = githubColors.mutedText)
                            Text("${overview.healthyRepoCount}", style = MaterialTheme.typography.titleMedium, color = githubColors.primary)
                        }
                        Column {
                            Text("异常", style = MaterialTheme.typography.labelSmall, color = githubColors.mutedText)
                            Text("${overview.dirtyRepoCount}", style = MaterialTheme.typography.titleMedium, color = if(overview.dirtyRepoCount > 0) githubColors.danger else githubColors.text)
                        }
                    }
                    androidx.compose.material3.HorizontalDivider(color = githubColors.border)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "更新于 ${overview.lastUpdatedLabel}",
                            style = MaterialTheme.typography.bodySmall,
                            color = githubColors.mutedText
                        )
                        if (tokenConfigured) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (isRemoteSummaryLoading) {
                                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = githubColors.accent)
                                }
                                TextButton(onClick = onRefreshRemoteSummaries, enabled = !isRemoteSummaryLoading) {
                                    Text("刷新摘要", color = githubColors.accent)
                                }
                            }
                        }
                    }
                    if (!remoteSummaryErrorMessage.isNullOrBlank()) {
                        Text(
                            text = remoteSummaryErrorMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = githubColors.danger
                        )
                    }
                }
            }

            if (priorityCards.isNotEmpty()) {
                Text("异常优先", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                priorityCards.forEach { card ->
                    ProjectGitHubWorkspacePriorityRepoCard(
                        card = card,
                        githubColors = githubColors,
                        onOpenRepoWorkbench = { tab ->
                            onOpenRepoWorkbench(card.rootPath, tab)
                        },
                        onOpenQuickAction = { action ->
                            when {
                                action.targetWorkflowRun != null -> onOpenWorkflowRunDetailTarget(card.rootPath, action.targetWorkflowRun)
                                action.targetPullRequest != null -> onOpenPullRequestDetailTarget(card.rootPath, action.targetPullRequest)
                                action.targetIssue != null -> onOpenIssueDetailTarget(card.rootPath, action.targetIssue)
                                else -> onOpenRepoWorkbench(card.rootPath, action.targetTab)
                            }
                        }
                    )
                }
            }

            ProjectGitHubDownloadCenterSummaryCard(
                downloads = downloads,
                onOpenDownloadCenter = onOpenDownloadCenter,
                onOpenSystemDownloads = onOpenSystemDownloads
            )

            GitHubCard {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = onSearchQueryChange,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = {
                            Text("搜索仓库名称或路径...", style = MaterialTheme.typography.bodyMedium, color = githubColors.mutedText)
                        },
                        leadingIcon = {
                            Icon(Icons.Default.Search, contentDescription = null, tint = githubColors.mutedText)
                        },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { onSearchQueryChange("") }) {
                                    Icon(Icons.Default.Clear, contentDescription = "清除搜索", tint = githubColors.mutedText)
                                }
                            }
                        },
                        singleLine = true,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = githubColors.background,
                            unfocusedContainerColor = githubColors.background,
                            focusedBorderColor = githubColors.accent,
                            unfocusedBorderColor = githubColors.border,
                            focusedTextColor = githubColors.text,
                            unfocusedTextColor = githubColors.text
                        ),
                        textStyle = MaterialTheme.typography.bodyMedium
                    )

                    Text(
                        text = "筛选仓库",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = 4.dp),
                        color = githubColors.text
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
                                    selectedContainerColor = githubColors.accent.copy(alpha = 0.2f),
                                    selectedLabelColor = githubColors.accent,
                                    labelColor = githubColors.mutedText
                                )
                            )
                        }
                    }
                }
            }

            GitHubCard {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("仓库摘要", style = MaterialTheme.typography.titleMedium, color = githubColors.text)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (currentFilter != ProjectGitHubWorkspaceFilterType.ALL) {
                                Text(
                                    text = "已过滤 (${repoCards.size})",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = githubColors.primary,
                                    modifier = Modifier.padding(end = 8.dp)
                                )
                            }
                            TextButton(
                                onClick = onToggleSelectionMode,
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Text(
                                    text = if (isSelectionMode) "取消多选" else "批量操作",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = githubColors.accent
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
                                    Text(action.label, style = MaterialTheme.typography.labelMedium, color = githubColors.accent)
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
                                    githubColors = githubColors,
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
    githubColors: GitHubColorPalette,
    onClick: () -> Unit,
    onOpenQuickAction: (ProjectGitHubWorkspaceQuickActionUi) -> Unit
) {
    GitHubCard(
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = card.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = githubColors.accent
                )
                if (card.severityScore > 0) {
                    GitHubLabel(
                        text = card.severityLabel,
                        color = if (card.severityScore >= 90) githubColors.danger else githubColors.primary
                    )
                }
            }
            Text(
                text = card.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = githubColors.mutedText
            )
            Text(
                text = card.changeSummary,
                style = MaterialTheme.typography.bodySmall,
                color = if (card.highlightChanges) githubColors.primary else githubColors.mutedText
            )
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (card.conflictCount > 0) GitHubLabel("冲突 ${card.conflictCount}", githubColors.danger)
                if (card.behindCount > 0) GitHubLabel("落后 ${card.behindCount}", githubColors.danger)
                if (card.openIssueCount > 0) GitHubLabel("Issue ${card.openIssueCount}", githubColors.primary)
                if (card.openPullRequestCount > 0) GitHubLabel("PR ${card.openPullRequestCount}", githubColors.primary)
                if (card.hasWorkingTreeChanges) GitHubLabel("本地改动", githubColors.accent)
            }

            if (!card.remoteSummary.isNullOrBlank()) {
                Text(
                    text = card.remoteSummary,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (card.highlightRemoteSummary) githubColors.primary else githubColors.mutedText
                )
            }

            if (!card.remoteErrorMessage.isNullOrBlank()) {
                Text(
                    text = card.remoteErrorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = githubColors.danger
                )
            } else if (!card.latestWorkflowSummary.isNullOrBlank()) {
                Text(
                    text = card.latestWorkflowSummary,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (card.latestWorkflowHasIssue) githubColors.danger else githubColors.mutedText
                )
            }

            if (card.recommendedActions.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    card.recommendedActions.forEach { action ->
                        TextButton(onClick = { onOpenQuickAction(action) }) {
                            Text(action.label, color = githubColors.accent)
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
    githubColors: GitHubColorPalette,
    onOpenRepoWorkbench: (ProjectGitHubWorkspaceRepoWorkbenchTab) -> Unit,
    onOpenQuickAction: (ProjectGitHubWorkspaceQuickActionUi) -> Unit
) {
    GitHubCard {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = card.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = githubColors.accent
                )
                GitHubLabel(
                    text = card.severityLabel,
                    color = if (card.severityScore >= 90) githubColors.danger else githubColors.primary
                )
            }
            Text(
                text = card.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = githubColors.mutedText
            )
            Text(
                text = card.remoteErrorMessage
                    ?: card.latestWorkflowSummary
                    ?: card.remoteSummary
                    ?: card.changeSummary,
                style = MaterialTheme.typography.bodySmall,
                color = if (!card.remoteErrorMessage.isNullOrBlank() || card.latestRunHasIssue) {
                    githubColors.danger
                } else {
                    githubColors.mutedText
                }
            )
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (card.conflictCount > 0) GitHubLabel("冲突 ${card.conflictCount}", githubColors.danger)
                if (card.behindCount > 0) GitHubLabel("落后 ${card.behindCount}", githubColors.danger)
                if (card.openIssueCount > 0) GitHubLabel("Issue ${card.openIssueCount}", githubColors.primary)
                if (card.openPullRequestCount > 0) GitHubLabel("PR ${card.openPullRequestCount}", githubColors.primary)
                if (card.latestRunHasIssue) GitHubLabel("工作流异常", githubColors.danger)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onOpenRepoWorkbench(ProjectGitHubWorkspaceRepoWorkbenchTab.OVERVIEW) }) {
                    Text("打开仓库", color = githubColors.accent)
                }
                card.recommendedActions.take(2).forEach { action ->
                    TextButton(onClick = { onOpenQuickAction(action) }) {
                        Text(action.label, color = githubColors.accent)
                    }
                }
            }
        }
    }
}
