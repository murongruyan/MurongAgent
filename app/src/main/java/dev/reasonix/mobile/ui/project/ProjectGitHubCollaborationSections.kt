package dev.reasonix.mobile.ui.project

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.reasonix.mobile.ui.rememberReasonixMutedTextColor
import dev.reasonix.mobile.ui.rememberReasonixSurfaceColor

@Composable
internal fun ProjectGitHubIssueSection(
    issues: List<ProjectGitHubIssueUi>,
    isActionRunning: Boolean,
    onCreateIssue: () -> Unit,
    onOpenDetail: (ProjectGitHubIssueUi) -> Unit,
    onToggleIssueState: (ProjectGitHubIssueUi, Boolean) -> Unit,
    onOpenIssuePage: (ProjectGitHubIssueUi) -> Unit
) {
    val surfaceColor = rememberReasonixSurfaceColor()
    val mutedTextColor = rememberReasonixMutedTextColor()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Issues", style = MaterialTheme.typography.titleSmall)
            TextButton(onClick = onCreateIssue, enabled = !isActionRunning) {
                Text("新建")
            }
        }
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
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "${issue.stateLabel} · ${issue.authorLabel} · ${issue.updatedAt}",
                            style = MaterialTheme.typography.bodySmall,
                            color = mutedTextColor
                        )
                        if (issue.labels.isNotEmpty()) {
                            Text(
                                text = issue.labels.joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = mutedTextColor
                            )
                        }
                        issue.body.takeIf { it.isNotBlank() }?.let { body ->
                            Text(
                                text = body.take(140),
                                style = MaterialTheme.typography.bodySmall,
                                color = mutedTextColor
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                onClick = { onOpenDetail(issue) },
                                enabled = !isActionRunning
                            ) {
                                Text("详情")
                            }
                            OutlinedButton(
                                onClick = { onToggleIssueState(issue, issue.isOpen) },
                                enabled = !isActionRunning
                            ) {
                                Text(if (issue.isOpen) "关闭" else "重开")
                            }
                            issue.htmlUrl?.takeIf { it.isNotBlank() }?.let {
                                TextButton(
                                    onClick = { onOpenIssuePage(issue) },
                                    enabled = !isActionRunning
                                ) {
                                    Text("网页")
                                }
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
    val surfaceColor = rememberReasonixSurfaceColor()
    val mutedTextColor = rememberReasonixMutedTextColor()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Pull Requests", style = MaterialTheme.typography.titleSmall)
            TextButton(onClick = onCreatePullRequest, enabled = !isActionRunning) {
                Text("新建")
            }
        }
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
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "${pullRequest.stateLabel} · ${pullRequest.authorLabel} · ${pullRequest.headBranch} -> ${pullRequest.baseBranch}",
                            style = MaterialTheme.typography.bodySmall,
                            color = mutedTextColor
                        )
                        if (pullRequest.labels.isNotEmpty()) {
                            Text(
                                text = pullRequest.labels.joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = mutedTextColor
                            )
                        }
                        pullRequest.body.takeIf { it.isNotBlank() }?.let { body ->
                            Text(
                                text = body.take(140),
                                style = MaterialTheme.typography.bodySmall,
                                color = mutedTextColor
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                onClick = { onOpenDetail(pullRequest) },
                                enabled = !isActionRunning
                            ) {
                                Text("详情")
                            }
                            if (pullRequest.canMerge) {
                                Button(
                                    onClick = { onMergePullRequest(pullRequest) },
                                    enabled = !isActionRunning
                                ) {
                                    Text("合并")
                                }
                            }
                            if (!pullRequest.isMerged) {
                                OutlinedButton(
                                    onClick = { onTogglePullRequestState(pullRequest, pullRequest.isOpen) },
                                    enabled = !isActionRunning
                                ) {
                                    Text(if (pullRequest.isOpen) "关闭" else "重开")
                                }
                            }
                            pullRequest.htmlUrl?.takeIf { it.isNotBlank() }?.let {
                                TextButton(
                                    onClick = { onOpenPullRequestPage(pullRequest) },
                                    enabled = !isActionRunning
                                ) {
                                    Text("网页")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ProjectGitHubReleaseSection(
    releases: List<ProjectGitHubReleaseUi>,
    isActionRunning: Boolean,
    onCreateRelease: () -> Unit,
    onEditRelease: (ProjectGitHubReleaseUi) -> Unit,
    onToggleReleaseMode: (ProjectGitHubReleaseUi, Boolean) -> Unit,
    onTogglePrerelease: (ProjectGitHubReleaseUi, Boolean) -> Unit,
    onDeleteRelease: (ProjectGitHubReleaseUi) -> Unit,
    onOpenReleasePage: (ProjectGitHubReleaseUi) -> Unit,
    onOpenAssets: (ProjectGitHubReleaseUi) -> Unit
) {
    val surfaceColor = rememberReasonixSurfaceColor()
    val mutedTextColor = rememberReasonixMutedTextColor()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Releases", style = MaterialTheme.typography.titleSmall)
            OutlinedButton(
                onClick = onCreateRelease,
                enabled = !isActionRunning
            ) {
                Text("新建")
            }
        }
        if (releases.isEmpty()) {
            Text(
                text = "当前仓库还没有读取到 Release。",
                style = MaterialTheme.typography.bodySmall,
                color = mutedTextColor
            )
        } else {
            releases.forEach { release ->
                ProjectInsetCard(
                    shape = RoundedCornerShape(12.dp),
                    surfaceColorOverride = surfaceColor.copy(alpha = 0.58f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = release.name.ifBlank { release.tagName },
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = buildString {
                                append(release.tagName)
                                append(" · ")
                                append(
                                    when {
                                        release.isDraft -> "草稿"
                                        release.isPrerelease -> "预发布"
                                        else -> "正式版"
                                    }
                                )
                                release.publishedAt.takeIf { it.isNotBlank() }?.let {
                                    append(" · ")
                                    append(it)
                                }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = mutedTextColor
                        )
                        if (release.body.isNotBlank()) {
                            Text(
                                text = release.body.take(140),
                                style = MaterialTheme.typography.bodySmall,
                                color = mutedTextColor
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            release.htmlUrl?.takeIf { it.isNotBlank() }?.let {
                                TextButton(
                                    onClick = { onOpenReleasePage(release) },
                                    enabled = !isActionRunning
                                ) {
                                    Text("网页")
                                }
                            }
                            OutlinedButton(
                                onClick = { onEditRelease(release) },
                                enabled = !isActionRunning
                            ) {
                                Text("编辑")
                            }
                            OutlinedButton(
                                onClick = { onToggleReleaseMode(release, !release.isDraft) },
                                enabled = !isActionRunning
                            ) {
                                Text(if (release.isDraft) "发布" else "草稿")
                            }
                            OutlinedButton(
                                onClick = { onTogglePrerelease(release, !release.isPrerelease) },
                                enabled = !isActionRunning && !release.isDraft
                            ) {
                                Text(if (release.isPrerelease) "转正式" else "预发布")
                            }
                            OutlinedButton(
                                onClick = { onOpenAssets(release) },
                                enabled = !isActionRunning
                            ) {
                                Text("资产 ${release.assets.size}")
                            }
                            TextButton(
                                onClick = { onDeleteRelease(release) },
                                enabled = !isActionRunning
                            ) {
                                Text("删除")
                            }
                        }
                    }
                }
            }
        }
    }
}
