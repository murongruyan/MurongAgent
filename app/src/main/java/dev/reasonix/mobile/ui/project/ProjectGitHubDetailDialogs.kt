package dev.reasonix.mobile.ui.project

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.reasonix.mobile.ui.ReasonixAlertDialog
import dev.reasonix.mobile.ui.rememberReasonixMutedTextColor
import dev.reasonix.mobile.ui.rememberReasonixSurfaceColor

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
    val mutedTextColor = rememberReasonixMutedTextColor()
    val surfaceColor = rememberReasonixSurfaceColor()

    ReasonixAlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        },
        dismissButton = {
            issue.htmlUrl?.takeIf { it.isNotBlank() }?.let {
                TextButton(onClick = { onOpenIssuePage(issue.htmlUrl) }) {
                    Text("网页")
                }
            }
        },
        title = { Text("Issue #${issue.number}") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(issue.title, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "${issue.stateLabel} · ${issue.authorLabel} · 更新于 ${issue.updatedAt}",
                    style = MaterialTheme.typography.bodySmall,
                    color = mutedTextColor
                )
                if (issue.labels.isNotEmpty()) {
                    ProjectInsetCard(
                        shape = RoundedCornerShape(12.dp),
                        surfaceColorOverride = surfaceColor.copy(alpha = 0.56f)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("标签", style = MaterialTheme.typography.labelMedium)
                            Text(
                                text = issue.labels.joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = mutedTextColor
                            )
                        }
                    }
                }
                Text(
                    text = if (issue.body.isNotBlank()) issue.body else "这个 Issue 还没有正文说明。",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (issue.body.isNotBlank()) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        mutedTextColor
                    }
                )
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onToggleIssueState,
                        enabled = !isActionRunning && canToggleIssueState
                    ) {
                        Text(if (issue.isOpen) "关闭 Issue" else "重新打开")
                    }
                }
            }
        }
    )
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
    val mutedTextColor = rememberReasonixMutedTextColor()
    val surfaceColor = rememberReasonixSurfaceColor()

    ReasonixAlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        },
        dismissButton = {
            pullRequest.htmlUrl?.takeIf { it.isNotBlank() }?.let {
                TextButton(onClick = { onOpenPullRequestPage(pullRequest.htmlUrl) }) {
                    Text("网页")
                }
            }
        },
        title = { Text("PR #${pullRequest.number}") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(pullRequest.title, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "${pullRequest.stateLabel} · ${pullRequest.authorLabel} · ${pullRequest.headBranch} -> ${pullRequest.baseBranch}",
                    style = MaterialTheme.typography.bodySmall,
                    color = mutedTextColor
                )
                Text(
                    text = "更新于 ${pullRequest.updatedAt}",
                    style = MaterialTheme.typography.bodySmall,
                    color = mutedTextColor
                )
                if (pullRequest.labels.isNotEmpty()) {
                    ProjectInsetCard(
                        shape = RoundedCornerShape(12.dp),
                        surfaceColorOverride = surfaceColor.copy(alpha = 0.56f)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("标签", style = MaterialTheme.typography.labelMedium)
                            Text(
                                text = pullRequest.labels.joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = mutedTextColor
                            )
                        }
                    }
                }
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
            }
        }
    )
}
