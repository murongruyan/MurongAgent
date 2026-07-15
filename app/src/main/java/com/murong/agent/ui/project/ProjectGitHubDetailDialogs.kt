package com.murong.agent.ui.project

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.murong.agent.ui.MurongGlassSurface
import com.murong.agent.ui.MurongLargeDialogScaffold
import com.murong.agent.ui.rememberMurongMutedTextColor
import com.murong.agent.ui.rememberMurongSurfaceColor

@Composable
private fun ProjectGitHubLargeDetailDialog(
    title: String,
    subtitle: String,
    pageUrl: String?,
    onOpenPage: (String?) -> Unit,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    MurongLargeDialogScaffold(onDismissRequest = onDismiss) {
        MurongGlassSurface(
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
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pageUrl?.takeIf { it.isNotBlank() }?.let {
                        TextButton(onClick = { onOpenPage(pageUrl) }) {
                            Text("网页")
                        }
                    }
                    TextButton(onClick = onDismiss) {
                        Text("关闭")
                    }
                }
            }
        }
        content()
    }
}

@Composable
internal fun ProjectGitHubIssueDetailDialog(
    issue: ProjectGitHubIssueUi,
    comments: List<ProjectGitHubCommentUi>,
    isCommentsLoading: Boolean,
    isActionRunning: Boolean,
    draft: String,
    onDraftChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onOpenIssuePage: (String?) -> Unit,
    onRefreshComments: () -> Unit,
    onSubmitComment: () -> Unit,
    onToggleIssueState: () -> Unit,
    canToggleIssueState: Boolean
) {
    val mutedTextColor = rememberMurongMutedTextColor()
    val surfaceColor = rememberMurongSurfaceColor()
    val issueSummary = remember(issue, comments, isCommentsLoading) {
        buildProjectGitHubIssueDetailSummary(
            issue = issue,
            commentCount = comments.size,
            isCommentsLoading = isCommentsLoading
        )
    }

    ProjectGitHubLargeDetailDialog(
        title = "Issue #${issue.number}",
        subtitle = "${issue.authorLabel} · 更新于 ${issue.updatedAt}",
        pageUrl = issue.htmlUrl,
        onOpenPage = onOpenIssuePage,
        onDismiss = onDismiss
    ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 16.dp)
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(issue.title, style = MaterialTheme.typography.titleSmall)
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
                        label = {
                            Text(
                                if (issueSummary.isCommentsLoading) {
                                    "评论读取中"
                                } else {
                                    "评论 ${issueSummary.commentCount}"
                                }
                            )
                        }
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
                    ProjectInsetCard(
                        shape = RoundedCornerShape(12.dp),
                        surfaceColorOverride = surfaceColor.copy(alpha = 0.56f)
                    ) {
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            issue.labels.forEach { label ->
                                FilterChip(
                                    selected = true,
                                    onClick = {},
                                    label = { Text(label) }
                                )
                            }
                        }
                    }
                }
                ProjectInsetCard(
                    shape = RoundedCornerShape(12.dp),
                    surfaceColorOverride = surfaceColor.copy(alpha = 0.56f)
                ) {
                    Text(
                        text = if (issue.body.isNotBlank()) issue.body else "这个 Issue 还没有正文说明。",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (issue.body.isNotBlank()) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            mutedTextColor
                        }
                    )
                }
                Text(
                    text = "详情概览: ${issueSummary.summaryText}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onRefreshComments,
                        enabled = !isCommentsLoading && !isActionRunning
                    ) {
                        Text("刷新讨论")
                    }
                    OutlinedButton(
                        onClick = onToggleIssueState,
                        enabled = !isActionRunning && canToggleIssueState
                    ) {
                        Text(if (issue.isOpen) "关闭 Issue" else "重新打开")
                    }
                }
                ProjectGitHubCommentThreadSection(
                    title = "讨论",
                    comments = comments,
                    isLoading = isCommentsLoading,
                    isActionRunning = isActionRunning,
                    draft = draft,
                    onDraftChange = onDraftChange,
                    onRefresh = onRefreshComments,
                    onSubmit = onSubmitComment
                )
            }
    }
}

@Composable
internal fun ProjectGitHubPullRequestDetailDialog(
    pullRequest: ProjectGitHubPullRequestUi,
    comments: List<ProjectGitHubCommentUi>,
    isCommentsLoading: Boolean,
    commentDraft: String,
    onCommentDraftChange: (String) -> Unit,
    onRefreshComments: () -> Unit,
    onSubmitComment: () -> Unit,
    reviews: List<ProjectGitHubPullRequestReviewUi>,
    isReviewsLoading: Boolean,
    reviewDraft: String,
    onReviewDraftChange: (String) -> Unit,
    onRefreshReviews: () -> Unit,
    onSubmitReview: (String) -> Unit,
    files: List<ProjectGitHubPullRequestFileUi>,
    reviewComments: List<ProjectGitHubPullRequestReviewCommentUi>,
    isFilesLoading: Boolean,
    isReviewCommentsLoading: Boolean,
    reviewCommentDraft: String,
    onReviewCommentDraftChange: (String) -> Unit,
    reviewCommentPathDraft: String,
    onReviewCommentPathDraftChange: (String) -> Unit,
    reviewCommentLineDraft: String,
    onReviewCommentLineDraftChange: (String) -> Unit,
    onPickReviewCommentPath: (String) -> Unit,
    onPickReviewCommentLine: (String) -> Unit,
    onRefreshReviewComments: () -> Unit,
    onSubmitReviewComment: () -> Unit,
    onReplyToReviewComment: (Long, String, () -> Unit) -> Unit,
    isActionRunning: Boolean,
    onDismiss: () -> Unit,
    onOpenPullRequestPage: (String?) -> Unit,
    onMerge: () -> Unit,
    canMerge: Boolean,
    onTogglePullRequestState: () -> Unit,
    canTogglePullRequestState: Boolean
) {
    val mutedTextColor = rememberMurongMutedTextColor()
    val surfaceColor = rememberMurongSurfaceColor()
    val pullRequestSummary = remember(
        pullRequest,
        comments,
        reviews,
        files,
        reviewComments,
        isCommentsLoading,
        isReviewsLoading,
        isFilesLoading,
        isReviewCommentsLoading
    ) {
        buildProjectGitHubPullRequestDetailSummary(
            pullRequest = pullRequest,
            commentCount = comments.size,
            reviewCount = reviews.size,
            fileCount = files.size,
            reviewCommentCount = reviewComments.size,
            isCommentsLoading = isCommentsLoading,
            isReviewsLoading = isReviewsLoading,
            isFilesLoading = isFilesLoading,
            isReviewCommentsLoading = isReviewCommentsLoading
        )
    }

    ProjectGitHubLargeDetailDialog(
        title = "PR #${pullRequest.number}",
        subtitle = "${pullRequest.authorLabel} · 更新于 ${pullRequest.updatedAt}",
        pageUrl = pullRequest.htmlUrl,
        onOpenPage = onOpenPullRequestPage,
        onDismiss = onDismiss
    ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 16.dp)
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(pullRequest.title, style = MaterialTheme.typography.titleSmall)
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
                    ProjectInsetCard(
                        shape = RoundedCornerShape(12.dp),
                        surfaceColorOverride = surfaceColor.copy(alpha = 0.56f)
                    ) {
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            pullRequest.labels.forEach { label ->
                                FilterChip(
                                    selected = true,
                                    onClick = {},
                                    label = { Text(label) }
                                )
                            }
                        }
                    }
                }
                ProjectInsetCard(
                    shape = RoundedCornerShape(12.dp),
                    surfaceColorOverride = surfaceColor.copy(alpha = 0.56f)
                ) {
                    Text(
                        text = if (pullRequest.body.isNotBlank()) {
                            pullRequest.body
                        } else {
                            "这个 Pull Request 还没有正文说明。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (pullRequest.body.isNotBlank()) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            mutedTextColor
                        }
                    )
                }
                Text(
                    text = pullRequestSummary.summaryText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (pullRequest.canMerge) {
                        Button(
                            onClick = onMerge,
                            enabled = !isActionRunning && canMerge
                        ) {
                            Text("合并")
                        }
                    }
                    OutlinedButton(
                        onClick = onTogglePullRequestState,
                        enabled = !isActionRunning && canTogglePullRequestState && !pullRequest.isMerged
                    ) {
                        Text(if (pullRequest.isOpen) "关闭 PR" else "重新打开")
                    }
                }
                ProjectGitHubCommentThreadSection(
                    title = "讨论",
                    comments = comments,
                    isLoading = isCommentsLoading,
                    isActionRunning = isActionRunning,
                    draft = commentDraft,
                    onDraftChange = onCommentDraftChange,
                    onRefresh = onRefreshComments,
                    onSubmit = onSubmitComment
                )
                ProjectGitHubPullRequestReviewSection(
                    reviews = reviews,
                    isLoading = isReviewsLoading,
                    isActionRunning = isActionRunning,
                    draft = reviewDraft,
                    onDraftChange = onReviewDraftChange,
                    onRefresh = onRefreshReviews,
                    onSubmitReview = onSubmitReview
                )
                ProjectGitHubPullRequestReviewCommentSection(
                    files = files,
                    comments = reviewComments,
                    isFilesLoading = isFilesLoading,
                    isCommentsLoading = isReviewCommentsLoading,
                    isActionRunning = isActionRunning,
                    bodyDraft = reviewCommentDraft,
                    onBodyDraftChange = onReviewCommentDraftChange,
                    pathDraft = reviewCommentPathDraft,
                    onPathDraftChange = onReviewCommentPathDraftChange,
                    lineDraft = reviewCommentLineDraft,
                    onLineDraftChange = onReviewCommentLineDraftChange,
                    onPickPath = onPickReviewCommentPath,
                    onPickLine = onPickReviewCommentLine,
                    onRefresh = onRefreshReviewComments,
                    onSubmit = onSubmitReviewComment,
                    onReplyToComment = onReplyToReviewComment
                )
            }
    }
}

private data class ProjectGitHubIssueDetailSummaryUi(
    val commentCount: Int,
    val isCommentsLoading: Boolean,
    val summaryText: String
)

private data class ProjectGitHubPullRequestDetailSummaryUi(
    val summaryText: String
)

private fun buildProjectGitHubIssueDetailSummary(
    issue: ProjectGitHubIssueUi,
    commentCount: Int,
    isCommentsLoading: Boolean
): ProjectGitHubIssueDetailSummaryUi {
    return ProjectGitHubIssueDetailSummaryUi(
        commentCount = commentCount,
        isCommentsLoading = isCommentsLoading,
        summaryText = buildString {
            append(issue.stateLabel)
            append(" · 作者 ${issue.authorLabel}")
            append(" · 标签 ${issue.labels.size}")
            append(" · ")
            append(
                if (isCommentsLoading) {
                    "评论读取中"
                } else {
                    "评论 $commentCount"
                }
            )
        }
    )
}

private fun buildProjectGitHubPullRequestDetailSummary(
    pullRequest: ProjectGitHubPullRequestUi,
    commentCount: Int,
    reviewCount: Int,
    fileCount: Int,
    reviewCommentCount: Int,
    isCommentsLoading: Boolean,
    isReviewsLoading: Boolean,
    isFilesLoading: Boolean,
    isReviewCommentsLoading: Boolean
): ProjectGitHubPullRequestDetailSummaryUi {
    return ProjectGitHubPullRequestDetailSummaryUi(
        summaryText = buildString {
            append(pullRequest.stateLabel)
            append(" · 作者 ${pullRequest.authorLabel}")
            append(" · 变更 ${pullRequest.headBranch} -> ${pullRequest.baseBranch}")
            append(" · ")
            append(if (isCommentsLoading) "讨论读取中" else "讨论 $commentCount")
            append(" · ")
            append(if (isReviewsLoading) "评审读取中" else "评审 $reviewCount")
            append(" · ")
            append(if (isFilesLoading) "文件读取中" else "文件 $fileCount")
            append(" · ")
            append(if (isReviewCommentsLoading) "代码评论读取中" else "代码评论 $reviewCommentCount")
        }
    )
}
