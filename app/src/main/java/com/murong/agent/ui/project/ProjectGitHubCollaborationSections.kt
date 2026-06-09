package com.murong.agent.ui.project

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.murong.agent.ui.MurongOutlinedActionButton
import com.murong.agent.ui.rememberMurongMutedTextColor
import com.murong.agent.ui.rememberMurongSurfaceColor

@Composable
internal fun ProjectGitHubIssueSection(
    issues: List<ProjectGitHubIssueUi>,
    isActionRunning: Boolean,
    onCreateIssue: () -> Unit,
    onOpenDetail: (ProjectGitHubIssueUi) -> Unit,
    onToggleIssueState: (ProjectGitHubIssueUi, Boolean) -> Unit,
    onOpenIssuePage: (ProjectGitHubIssueUi) -> Unit
) {
    val surfaceColor = rememberMurongSurfaceColor()
    val mutedTextColor = rememberMurongMutedTextColor()
    val summary = remember(issues) {
        buildProjectGitHubIssueSummary(issues)
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Issues", style = MaterialTheme.typography.titleSmall)
            MurongOutlinedActionButton(
                text = "新建",
                onClick = onCreateIssue,
                enabled = !isActionRunning
            )
        }
        Text(
            text = "共 ${summary.totalCount} 个 Issue · 开放 ${summary.openCount} · 已关闭 ${summary.closedCount} · 标签 ${summary.labelCount}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary
        )
        if (issues.isEmpty()) {
            Text(
                text = "当前仓库还没有读取到 Issue。",
                style = MaterialTheme.typography.bodySmall,
                color = mutedTextColor
            )
        } else {
            issues.forEach { issue ->
                ProjectInsetCard(
                    shape = RoundedCornerShape(12.dp),
                    surfaceColorOverride = surfaceColor.copy(alpha = 0.58f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "#${issue.number} · ${issue.title}",
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${issue.authorLabel} · 更新 ${issue.updatedAt}",
                            style = MaterialTheme.typography.bodySmall,
                            color = mutedTextColor
                        )
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = true,
                                onClick = {},
                                label = { Text(issue.stateLabel) }
                            )
                            FilterChip(
                                selected = true,
                                onClick = {},
                                label = { Text("评论入口") }
                            )
                            if (issue.labels.isNotEmpty()) {
                                FilterChip(
                                    selected = true,
                                    onClick = {},
                                    label = { Text("标签 ${issue.labels.size}") }
                                )
                            }
                        }
                        if (issue.labels.isNotEmpty()) {
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                issue.labels.take(6).forEach { label ->
                                    FilterChip(
                                        selected = true,
                                        onClick = {},
                                        label = { Text(label) }
                                    )
                                }
                            }
                        }
                        issue.body.takeIf { it.isNotBlank() }?.let { body ->
                            Text(
                                text = body.take(220),
                                style = MaterialTheme.typography.bodySmall,
                                color = mutedTextColor,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            MurongOutlinedActionButton(
                                text = "详情",
                                onClick = { onOpenDetail(issue) },
                                enabled = !isActionRunning
                            )
                            MurongOutlinedActionButton(
                                text = if (issue.isOpen) "关闭" else "重开",
                                onClick = { onToggleIssueState(issue, issue.isOpen) },
                                enabled = !isActionRunning
                            )
                            issue.htmlUrl?.takeIf { it.isNotBlank() }?.let {
                                MurongOutlinedActionButton(
                                    text = "网页",
                                    onClick = { onOpenIssuePage(issue) },
                                    enabled = !isActionRunning
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
internal fun ProjectGitHubPullRequestSection(
    pullRequests: List<ProjectGitHubPullRequestUi>,
    isActionRunning: Boolean,
    onCreatePullRequest: () -> Unit,
    onOpenDetail: (ProjectGitHubPullRequestUi) -> Unit,
    onTogglePullRequestState: (ProjectGitHubPullRequestUi, Boolean) -> Unit,
    onMergePullRequest: (ProjectGitHubPullRequestUi) -> Unit,
    onOpenPullRequestPage: (ProjectGitHubPullRequestUi) -> Unit
) {
    val surfaceColor = rememberMurongSurfaceColor()
    val mutedTextColor = rememberMurongMutedTextColor()
    val summary = remember(pullRequests) {
        buildProjectGitHubPullRequestSummary(pullRequests)
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Pull Requests", style = MaterialTheme.typography.titleSmall)
            MurongOutlinedActionButton(
                text = "新建",
                onClick = onCreatePullRequest,
                enabled = !isActionRunning
            )
        }
        Text(
            text = buildString {
                append("共 ${summary.totalCount} 个 PR")
                append(" · 开放 ${summary.openCount}")
                append(" · 草稿 ${summary.draftCount}")
                append(" · 已合并 ${summary.mergedCount}")
                append(" · 可合并 ${summary.mergeableCount}")
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary
        )
        if (pullRequests.isEmpty()) {
            Text(
                text = "当前仓库还没有读取到 Pull Request。",
                style = MaterialTheme.typography.bodySmall,
                color = mutedTextColor
            )
        } else {
            pullRequests.forEach { pullRequest ->
                ProjectInsetCard(
                    shape = RoundedCornerShape(12.dp),
                    surfaceColorOverride = surfaceColor.copy(alpha = 0.58f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "#${pullRequest.number} · ${pullRequest.title}",
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${pullRequest.authorLabel} · 更新 ${pullRequest.updatedAt}",
                            style = MaterialTheme.typography.bodySmall,
                            color = mutedTextColor
                        )
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = true,
                                onClick = {},
                                label = { Text(pullRequest.stateLabel) }
                            )
                            FilterChip(
                                selected = true,
                                onClick = {},
                                label = { Text("${pullRequest.headBranch} -> ${pullRequest.baseBranch}") }
                            )
                            if (pullRequest.canMerge) {
                                FilterChip(
                                    selected = true,
                                    onClick = {},
                                    label = { Text("可直接合并") }
                                )
                            }
                            if (pullRequest.labels.isNotEmpty()) {
                                FilterChip(
                                    selected = true,
                                    onClick = {},
                                    label = { Text("标签 ${pullRequest.labels.size}") }
                                )
                            }
                        }
                        if (pullRequest.labels.isNotEmpty()) {
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                pullRequest.labels.take(6).forEach { label ->
                                    FilterChip(
                                        selected = true,
                                        onClick = {},
                                        label = { Text(label) }
                                    )
                                }
                            }
                        }
                        pullRequest.body.takeIf { it.isNotBlank() }?.let { body ->
                            Text(
                                text = body.take(220),
                                style = MaterialTheme.typography.bodySmall,
                                color = mutedTextColor,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            MurongOutlinedActionButton(
                                text = "详情",
                                onClick = { onOpenDetail(pullRequest) },
                                enabled = !isActionRunning
                            )
                            if (pullRequest.canMerge) {
                                MurongOutlinedActionButton(
                                    text = "合并",
                                    onClick = { onMergePullRequest(pullRequest) },
                                    enabled = !isActionRunning
                                )
                            }
                            if (!pullRequest.isMerged) {
                                MurongOutlinedActionButton(
                                    text = if (pullRequest.isOpen) "关闭" else "重开",
                                    onClick = { onTogglePullRequestState(pullRequest, pullRequest.isOpen) },
                                    enabled = !isActionRunning
                                )
                            }
                            pullRequest.htmlUrl?.takeIf { it.isNotBlank() }?.let {
                                MurongOutlinedActionButton(
                                    text = "网页",
                                    onClick = { onOpenPullRequestPage(pullRequest) },
                                    enabled = !isActionRunning
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class ProjectGitHubIssueSummaryUi(
    val totalCount: Int,
    val openCount: Int,
    val closedCount: Int,
    val labelCount: Int
)

private data class ProjectGitHubPullRequestSummaryUi(
    val totalCount: Int,
    val openCount: Int,
    val draftCount: Int,
    val mergedCount: Int,
    val mergeableCount: Int
)

private fun buildProjectGitHubIssueSummary(
    issues: List<ProjectGitHubIssueUi>
): ProjectGitHubIssueSummaryUi {
    return ProjectGitHubIssueSummaryUi(
        totalCount = issues.size,
        openCount = issues.count { it.isOpen },
        closedCount = issues.count { !it.isOpen },
        labelCount = issues.sumOf { it.labels.size }
    )
}

private fun buildProjectGitHubPullRequestSummary(
    pullRequests: List<ProjectGitHubPullRequestUi>
): ProjectGitHubPullRequestSummaryUi {
    return ProjectGitHubPullRequestSummaryUi(
        totalCount = pullRequests.size,
        openCount = pullRequests.count { it.isOpen },
        draftCount = pullRequests.count { it.isDraft && !it.isMerged },
        mergedCount = pullRequests.count { it.isMerged },
        mergeableCount = pullRequests.count { it.canMerge }
    )
}

@Composable
internal fun ProjectGitHubReleaseSection(
    releases: List<ProjectGitHubReleaseUi>,
    isLoading: Boolean = false,
    isActionRunning: Boolean,
    onCreateRelease: () -> Unit,
    onRefresh: (() -> Unit)? = null,
    onEditRelease: (ProjectGitHubReleaseUi) -> Unit,
    onToggleReleaseMode: (ProjectGitHubReleaseUi, Boolean) -> Unit,
    onTogglePrerelease: (ProjectGitHubReleaseUi, Boolean) -> Unit,
    onDeleteRelease: (ProjectGitHubReleaseUi) -> Unit,
    onOpenReleasePage: (ProjectGitHubReleaseUi) -> Unit,
    onOpenAssets: (ProjectGitHubReleaseUi) -> Unit
) {
    val surfaceColor = rememberMurongSurfaceColor()
    val mutedTextColor = rememberMurongMutedTextColor()
    val summary = remember(releases) {
        buildProjectGitHubReleaseSummary(releases)
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Releases", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MurongOutlinedActionButton(
                    text = "新建",
                    onClick = onCreateRelease,
                    enabled = !isLoading && !isActionRunning
                )
                onRefresh?.let { refresh ->
                    MurongOutlinedActionButton(
                        text = "刷新",
                        onClick = refresh,
                        enabled = !isLoading
                    )
                }
            }
        }
        Text(
            text = "共 ${summary.totalCount} 个 Release · 草稿 ${summary.draftCount} · 预发布 ${summary.prereleaseCount} · 资产 ${summary.assetCount}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = "先看标签、状态和发布时间，再按需要展开发布管理和资产预览。",
            style = MaterialTheme.typography.bodySmall,
            color = mutedTextColor
        )
        if (isLoading) {
            Text(
                text = "正在读取 Release...",
                style = MaterialTheme.typography.bodySmall,
                color = mutedTextColor
            )
        } else if (releases.isEmpty()) {
            Text(
                text = "当前仓库还没有读取到 Release。",
                style = MaterialTheme.typography.bodySmall,
                color = mutedTextColor
            )
        } else {
            val expandedReleaseState = remember(releases) { mutableStateMapOf<Long, Boolean>() }
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MurongOutlinedActionButton(
                    text = "展开全部",
                    onClick = {
                        releases.forEach { release ->
                            expandedReleaseState[release.id] = true
                        }
                    }
                )
                MurongOutlinedActionButton(
                    text = "收起全部",
                    onClick = {
                        releases.forEach { release ->
                            expandedReleaseState[release.id] = false
                        }
                    }
                )
            }
            releases.forEach { release ->
                val stateLabel = when {
                    release.isDraft -> "草稿"
                    release.isPrerelease -> "预发布"
                    else -> "正式版"
                }
                val expanded = expandedReleaseState[release.id] ?: (releases.size == 1)
                val publishedAtLabel = release.publishedAt
                    .takeIf { it.isNotBlank() }
                    ?.let(::formatProjectGitHubIsoDateTime)
                    ?: "发布时间未知"
                ProjectInsetCard(
                    shape = RoundedCornerShape(12.dp),
                    surfaceColorOverride = surfaceColor.copy(alpha = 0.58f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { expandedReleaseState[release.id] = !expanded }
                ) {
                    Column(
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
                                    text = release.name.ifBlank { release.tagName },
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = release.tagName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Text(
                                text = if (expanded) "收起" else "展开",
                                style = MaterialTheme.typography.labelSmall,
                                color = mutedTextColor
                            )
                        }
                        Text(
                            text = buildString {
                                append(stateLabel)
                                append(" · ")
                                append(publishedAtLabel)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = mutedTextColor
                        )
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = true,
                                onClick = {},
                                label = { Text(release.tagName) }
                            )
                            FilterChip(
                                selected = true,
                                onClick = {},
                                label = { Text(stateLabel) }
                            )
                            FilterChip(
                                selected = true,
                                onClick = {},
                                label = { Text("资产 ${release.assets.size}") }
                            )
                            if (release.body.isNotBlank()) {
                                FilterChip(
                                    selected = true,
                                    onClick = {},
                                    label = { Text("说明") }
                                )
                            }
                        }
                        if (release.body.isNotBlank()) {
                            Text(
                                text = release.body.take(140),
                                style = MaterialTheme.typography.bodySmall,
                                color = mutedTextColor,
                                maxLines = if (expanded) Int.MAX_VALUE else 4,
                                overflow = TextOverflow.Ellipsis
                            )
                        } else {
                            Text(
                                text = "当前 Release 还没有说明。",
                                style = MaterialTheme.typography.bodySmall,
                                color = mutedTextColor
                            )
                        }
                        if (release.assets.isNotEmpty()) {
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                release.assets.take(3).forEach { asset ->
                                    FilterChip(
                                        selected = true,
                                        onClick = { onOpenAssets(release) },
                                        label = { Text(asset.name.take(22)) }
                                    )
                                }
                                if (release.assets.size > 3) {
                                    FilterChip(
                                        selected = true,
                                        onClick = { onOpenAssets(release) },
                                        label = { Text("其余 ${release.assets.size - 3} 个") }
                                    )
                                }
                            }
                        }
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            release.htmlUrl?.takeIf { it.isNotBlank() }?.let {
                                MurongOutlinedActionButton(
                                    text = "网页",
                                    onClick = { onOpenReleasePage(release) },
                                    enabled = !isActionRunning
                                )
                            }
                            MurongOutlinedActionButton(
                                text = "编辑",
                                onClick = { onEditRelease(release) },
                                enabled = !isActionRunning
                            )
                            MurongOutlinedActionButton(
                                text = if (release.assets.isEmpty()) "产物列表" else "查看产物",
                                onClick = { onOpenAssets(release) },
                                enabled = !isActionRunning
                            )
                        }
                        if (expanded) {
                            Text(
                                text = "发布管理",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                MurongOutlinedActionButton(
                                    text = if (release.isDraft) "发布" else "转草稿",
                                    onClick = { onToggleReleaseMode(release, !release.isDraft) },
                                    enabled = !isActionRunning
                                )
                                MurongOutlinedActionButton(
                                    text = if (release.isPrerelease) "转正式" else "预发布",
                                    onClick = { onTogglePrerelease(release, !release.isPrerelease) },
                                    enabled = !isActionRunning && !release.isDraft
                                )
                                MurongOutlinedActionButton(
                                    text = "删除",
                                    onClick = { onDeleteRelease(release) },
                                    enabled = !isActionRunning
                                )
                            }
                            Text(
                                text = "Assets · ${release.assets.size}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            if (release.assets.isEmpty()) {
                                Text(
                                    text = "当前 Release 没有可下载产物。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = mutedTextColor
                                )
                            } else {
                                release.assets.forEach { asset ->
                                    ProjectGitHubReleaseAssetPreviewRow(
                                        asset = asset,
                                        onOpenAssets = { onOpenAssets(release) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class ProjectGitHubReleaseSummaryUi(
    val totalCount: Int,
    val draftCount: Int,
    val prereleaseCount: Int,
    val assetCount: Int
)

private fun buildProjectGitHubReleaseSummary(
    releases: List<ProjectGitHubReleaseUi>
): ProjectGitHubReleaseSummaryUi {
    return ProjectGitHubReleaseSummaryUi(
        totalCount = releases.size,
        draftCount = releases.count { it.isDraft },
        prereleaseCount = releases.count { it.isPrerelease },
        assetCount = releases.sumOf { it.assets.size }
    )
}

@Composable
private fun ProjectGitHubReleaseAssetPreviewRow(
    asset: ProjectGitHubReleaseAssetUi,
    onOpenAssets: () -> Unit
) {
    val surfaceColor = rememberMurongSurfaceColor()
    val mutedTextColor = rememberMurongMutedTextColor()
    ProjectInsetCard(
        shape = RoundedCornerShape(10.dp),
        surfaceColorOverride = surfaceColor.copy(alpha = 0.34f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = asset.name,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = buildString {
                        append(asset.sizeLabel)
                        append(" · ")
                        append(
                            asset.updatedAt
                                .takeIf { it.isNotBlank() }
                                ?.let(::formatProjectGitHubIsoDateTime)
                                ?: "时间未知"
                        )
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = mutedTextColor
                )
            }
            MurongOutlinedActionButton(
                text = "管理资产",
                onClick = onOpenAssets
            )
        }
    }
}
