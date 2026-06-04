package dev.reasonix.mobile.ui.project

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

internal data class ProjectGitHubIssueDetailStore(
    val issue: ProjectGitHubIssueUi? = null,
    val comments: List<ProjectGitHubCommentUi> = emptyList(),
    val isCommentsLoading: Boolean = false,
    val commentDraft: String = ""
)

internal data class ProjectGitHubPullRequestDetailStore(
    val pullRequest: ProjectGitHubPullRequestUi? = null,
    val comments: List<ProjectGitHubCommentUi> = emptyList(),
    val isCommentsLoading: Boolean = false,
    val commentDraft: String = "",
    val reviews: List<ProjectGitHubPullRequestReviewUi> = emptyList(),
    val isReviewsLoading: Boolean = false,
    val reviewDraft: String = "",
    val files: List<ProjectGitHubPullRequestFileUi> = emptyList(),
    val isFilesLoading: Boolean = false,
    val reviewComments: List<ProjectGitHubPullRequestReviewCommentUi> = emptyList(),
    val isReviewCommentsLoading: Boolean = false,
    val reviewCommentDraft: String = "",
    val reviewCommentPathDraft: String = "",
    val reviewCommentLineDraft: String = ""
)

internal data class ProjectGitHubCommentsLoadResult(
    val comments: List<ProjectGitHubCommentUi> = emptyList(),
    val feedbackMessage: String? = null
)

internal data class ProjectGitHubPullRequestReviewsLoadResult(
    val reviews: List<ProjectGitHubPullRequestReviewUi> = emptyList(),
    val feedbackMessage: String? = null
)

internal data class ProjectGitHubPullRequestFilesLoadResult(
    val files: List<ProjectGitHubPullRequestFileUi> = emptyList(),
    val suggestedPathDraft: String = "",
    val feedbackMessage: String? = null
)

internal data class ProjectGitHubPullRequestReviewCommentsLoadResult(
    val comments: List<ProjectGitHubPullRequestReviewCommentUi> = emptyList(),
    val feedbackMessage: String? = null
)

internal data class ProjectGitHubPullRequestDetailBootstrapLoadResult(
    val commentsResult: ProjectGitHubCommentsLoadResult = ProjectGitHubCommentsLoadResult(),
    val reviewsResult: ProjectGitHubPullRequestReviewsLoadResult =
        ProjectGitHubPullRequestReviewsLoadResult(),
    val filesResult: ProjectGitHubPullRequestFilesLoadResult =
        ProjectGitHubPullRequestFilesLoadResult(),
    val reviewCommentsResult: ProjectGitHubPullRequestReviewCommentsLoadResult =
        ProjectGitHubPullRequestReviewCommentsLoadResult()
) {
    val feedbackMessage: String?
        get() = listOfNotNull(
            commentsResult.feedbackMessage,
            reviewsResult.feedbackMessage,
            filesResult.feedbackMessage,
            reviewCommentsResult.feedbackMessage
        ).firstOrNull()
}

internal data class ProjectGitHubIssueDetailActionResult(
    val success: Boolean = false,
    val feedbackMessage: String? = null,
    val nextStore: ProjectGitHubIssueDetailStore? = null,
    val shouldRefreshComments: Boolean = false,
    val shouldRefreshGitHubActions: Boolean = false
)

internal data class ProjectGitHubPullRequestDetailActionResult(
    val success: Boolean = false,
    val feedbackMessage: String? = null,
    val nextStore: ProjectGitHubPullRequestDetailStore? = null,
    val shouldRefreshComments: Boolean = false,
    val shouldRefreshReviews: Boolean = false,
    val shouldRefreshFiles: Boolean = false,
    val shouldRefreshReviewComments: Boolean = false,
    val shouldRefreshGitHubActions: Boolean = false
)

internal fun clearProjectGitHubIssueDetailStore(): ProjectGitHubIssueDetailStore {
    return ProjectGitHubIssueDetailStore()
}

internal fun clearProjectGitHubPullRequestDetailStore(): ProjectGitHubPullRequestDetailStore {
    return ProjectGitHubPullRequestDetailStore()
}

internal fun openProjectGitHubIssueDetailStore(issue: ProjectGitHubIssueUi): ProjectGitHubIssueDetailStore {
    return ProjectGitHubIssueDetailStore(
        issue = issue,
        isCommentsLoading = true
    )
}

internal fun openProjectGitHubPullRequestDetailStore(
    pullRequest: ProjectGitHubPullRequestUi
): ProjectGitHubPullRequestDetailStore {
    return ProjectGitHubPullRequestDetailStore(
        pullRequest = pullRequest,
        isCommentsLoading = true,
        isReviewsLoading = true,
        isFilesLoading = true,
        isReviewCommentsLoading = true
    )
}

internal fun beginProjectGitHubIssueCommentsRefresh(
    currentStore: ProjectGitHubIssueDetailStore
): ProjectGitHubIssueDetailStore {
    return currentStore.copy(
        comments = emptyList(),
        isCommentsLoading = true
    )
}

internal fun applyProjectGitHubIssueCommentsRefreshResult(
    currentStore: ProjectGitHubIssueDetailStore,
    result: ProjectGitHubCommentsLoadResult
): ProjectGitHubIssueDetailStore {
    return currentStore.copy(
        comments = result.comments,
        isCommentsLoading = false
    )
}

internal fun beginProjectGitHubPullRequestCommentsRefresh(
    currentStore: ProjectGitHubPullRequestDetailStore
): ProjectGitHubPullRequestDetailStore {
    return currentStore.copy(
        comments = emptyList(),
        isCommentsLoading = true
    )
}

internal fun applyProjectGitHubPullRequestCommentsRefreshResult(
    currentStore: ProjectGitHubPullRequestDetailStore,
    result: ProjectGitHubCommentsLoadResult
): ProjectGitHubPullRequestDetailStore {
    return currentStore.copy(
        comments = result.comments,
        isCommentsLoading = false
    )
}

internal fun beginProjectGitHubPullRequestReviewsRefresh(
    currentStore: ProjectGitHubPullRequestDetailStore
): ProjectGitHubPullRequestDetailStore {
    return currentStore.copy(
        reviews = emptyList(),
        isReviewsLoading = true
    )
}

internal fun applyProjectGitHubPullRequestReviewsRefreshResult(
    currentStore: ProjectGitHubPullRequestDetailStore,
    result: ProjectGitHubPullRequestReviewsLoadResult
): ProjectGitHubPullRequestDetailStore {
    return currentStore.copy(
        reviews = result.reviews,
        isReviewsLoading = false
    )
}

internal fun beginProjectGitHubPullRequestFilesRefresh(
    currentStore: ProjectGitHubPullRequestDetailStore
): ProjectGitHubPullRequestDetailStore {
    return currentStore.copy(
        files = emptyList(),
        isFilesLoading = true
    )
}

internal fun applyProjectGitHubPullRequestFilesRefreshResult(
    currentStore: ProjectGitHubPullRequestDetailStore,
    result: ProjectGitHubPullRequestFilesLoadResult
): ProjectGitHubPullRequestDetailStore {
    return currentStore.copy(
        files = result.files,
        isFilesLoading = false,
        reviewCommentPathDraft = currentStore.reviewCommentPathDraft.ifBlank {
            result.suggestedPathDraft
        }
    )
}

internal fun beginProjectGitHubPullRequestReviewCommentsRefresh(
    currentStore: ProjectGitHubPullRequestDetailStore
): ProjectGitHubPullRequestDetailStore {
    return currentStore.copy(
        reviewComments = emptyList(),
        isReviewCommentsLoading = true
    )
}

internal fun applyProjectGitHubPullRequestReviewCommentsRefreshResult(
    currentStore: ProjectGitHubPullRequestDetailStore,
    result: ProjectGitHubPullRequestReviewCommentsLoadResult
): ProjectGitHubPullRequestDetailStore {
    return currentStore.copy(
        reviewComments = result.comments,
        isReviewCommentsLoading = false
    )
}

internal fun applyProjectGitHubPullRequestDetailBootstrapLoadResult(
    currentStore: ProjectGitHubPullRequestDetailStore,
    result: ProjectGitHubPullRequestDetailBootstrapLoadResult
): ProjectGitHubPullRequestDetailStore {
    return currentStore.copy(
        comments = result.commentsResult.comments,
        isCommentsLoading = false,
        reviews = result.reviewsResult.reviews,
        isReviewsLoading = false,
        files = result.filesResult.files,
        isFilesLoading = false,
        reviewComments = result.reviewCommentsResult.comments,
        isReviewCommentsLoading = false,
        reviewCommentPathDraft = currentStore.reviewCommentPathDraft.ifBlank {
            result.filesResult.suggestedPathDraft
        }
    )
}

internal suspend fun loadProjectGitHubCommentsForDetail(
    repo: ProjectGitHubRepoRef?,
    issueNumber: Long,
    token: String,
    apiBaseUrl: String,
    errorFallback: String
): ProjectGitHubCommentsLoadResult {
    if (repo == null || token.isBlank()) return ProjectGitHubCommentsLoadResult()
    val result = loadProjectGitHubIssueComments(
        repo = repo,
        issueNumber = issueNumber,
        token = token,
        apiBaseUrl = apiBaseUrl
    )
    return if (result.success) {
        ProjectGitHubCommentsLoadResult(comments = result.comments)
    } else {
        ProjectGitHubCommentsLoadResult(
            feedbackMessage = result.error ?: errorFallback
        )
    }
}

internal suspend fun loadProjectGitHubPullRequestReviewsForDetail(
    repo: ProjectGitHubRepoRef?,
    pullNumber: Long,
    token: String,
    apiBaseUrl: String
): ProjectGitHubPullRequestReviewsLoadResult {
    if (repo == null || token.isBlank()) return ProjectGitHubPullRequestReviewsLoadResult()
    val result = loadProjectGitHubPullRequestReviews(
        repo = repo,
        pullNumber = pullNumber,
        token = token,
        apiBaseUrl = apiBaseUrl
    )
    return if (result.success) {
        ProjectGitHubPullRequestReviewsLoadResult(reviews = result.reviews)
    } else {
        ProjectGitHubPullRequestReviewsLoadResult(
            feedbackMessage = result.error ?: "读取 PR 评审失败"
        )
    }
}

internal suspend fun loadProjectGitHubPullRequestFilesForDetail(
    repo: ProjectGitHubRepoRef?,
    pullNumber: Long,
    token: String,
    apiBaseUrl: String
): ProjectGitHubPullRequestFilesLoadResult {
    if (repo == null || token.isBlank()) return ProjectGitHubPullRequestFilesLoadResult()
    val result = loadProjectGitHubPullRequestFiles(
        repo = repo,
        pullNumber = pullNumber,
        token = token,
        apiBaseUrl = apiBaseUrl
    )
    return if (result.success) {
        ProjectGitHubPullRequestFilesLoadResult(
            files = result.files,
            suggestedPathDraft = result.files.firstOrNull()?.path.orEmpty()
        )
    } else {
        ProjectGitHubPullRequestFilesLoadResult(
            feedbackMessage = result.error ?: "读取 PR 变更文件失败"
        )
    }
}

internal suspend fun loadProjectGitHubPullRequestReviewCommentsForDetail(
    repo: ProjectGitHubRepoRef?,
    pullNumber: Long,
    token: String,
    apiBaseUrl: String
): ProjectGitHubPullRequestReviewCommentsLoadResult {
    if (repo == null || token.isBlank()) return ProjectGitHubPullRequestReviewCommentsLoadResult()
    val result = loadProjectGitHubPullRequestReviewComments(
        repo = repo,
        pullNumber = pullNumber,
        token = token,
        apiBaseUrl = apiBaseUrl
    )
    return if (result.success) {
        ProjectGitHubPullRequestReviewCommentsLoadResult(comments = result.comments)
    } else {
        ProjectGitHubPullRequestReviewCommentsLoadResult(
            feedbackMessage = result.error ?: "读取代码评审评论失败"
        )
    }
}

internal suspend fun loadProjectGitHubPullRequestDetailBootstrapData(
    repo: ProjectGitHubRepoRef?,
    pullNumber: Long,
    token: String,
    apiBaseUrl: String
): ProjectGitHubPullRequestDetailBootstrapLoadResult = coroutineScope {
    val commentsDeferred = async {
        loadProjectGitHubCommentsForDetail(
            repo = repo,
            issueNumber = pullNumber,
            token = token,
            apiBaseUrl = apiBaseUrl,
            errorFallback = "读取 Pull Request 评论失败"
        )
    }
    val reviewsDeferred = async {
        loadProjectGitHubPullRequestReviewsForDetail(
            repo = repo,
            pullNumber = pullNumber,
            token = token,
            apiBaseUrl = apiBaseUrl
        )
    }
    val filesDeferred = async {
        loadProjectGitHubPullRequestFilesForDetail(
            repo = repo,
            pullNumber = pullNumber,
            token = token,
            apiBaseUrl = apiBaseUrl
        )
    }
    val reviewCommentsDeferred = async {
        loadProjectGitHubPullRequestReviewCommentsForDetail(
            repo = repo,
            pullNumber = pullNumber,
            token = token,
            apiBaseUrl = apiBaseUrl
        )
    }
    ProjectGitHubPullRequestDetailBootstrapLoadResult(
        commentsResult = commentsDeferred.await(),
        reviewsResult = reviewsDeferred.await(),
        filesResult = filesDeferred.await(),
        reviewCommentsResult = reviewCommentsDeferred.await()
    )
}

internal suspend fun submitProjectGitHubIssueDetailComment(
    currentStore: ProjectGitHubIssueDetailStore,
    repo: ProjectGitHubRepoRef?,
    token: String,
    apiBaseUrl: String
): ProjectGitHubIssueDetailActionResult {
    val issue = currentStore.issue ?: return ProjectGitHubIssueDetailActionResult()
    val body = currentStore.commentDraft.trim()
    if (repo == null || token.isBlank() || body.isBlank()) {
        return ProjectGitHubIssueDetailActionResult()
    }
    val result = createProjectGitHubIssueComment(
        repo = repo,
        issueNumber = issue.number,
        body = body,
        token = token,
        apiBaseUrl = apiBaseUrl
    )
    return ProjectGitHubIssueDetailActionResult(
        success = result.success,
        feedbackMessage = when {
            result.success -> result.message.ifBlank { "已回复 Issue #${issue.number}" }
            else -> result.error ?: result.message.ifBlank { "回复 Issue 失败" }
        },
        nextStore = if (result.success) {
            currentStore.copy(commentDraft = "")
        } else {
            currentStore
        },
        shouldRefreshComments = result.success,
        shouldRefreshGitHubActions = result.success
    )
}

internal suspend fun toggleProjectGitHubIssueDetailState(
    currentStore: ProjectGitHubIssueDetailStore,
    repo: ProjectGitHubRepoRef?,
    token: String,
    apiBaseUrl: String
): ProjectGitHubIssueDetailActionResult {
    val issue = currentStore.issue ?: return ProjectGitHubIssueDetailActionResult()
    if (repo == null || token.isBlank()) return ProjectGitHubIssueDetailActionResult()
    val result = updateProjectGitHubIssueState(
        repo = repo,
        issueNumber = issue.number,
        close = issue.isOpen,
        token = token,
        apiBaseUrl = apiBaseUrl
    )
    return ProjectGitHubIssueDetailActionResult(
        success = result.success,
        feedbackMessage = when {
            result.success -> result.message.ifBlank {
                if (issue.isOpen) {
                    "已关闭 Issue #${issue.number}"
                } else {
                    "已重新打开 Issue #${issue.number}"
                }
            }
            else -> result.error ?: result.message.ifBlank { "Issue 状态更新失败" }
        },
        nextStore = if (result.success) clearProjectGitHubIssueDetailStore() else currentStore,
        shouldRefreshGitHubActions = result.success
    )
}

internal suspend fun submitProjectGitHubPullRequestDetailComment(
    currentStore: ProjectGitHubPullRequestDetailStore,
    repo: ProjectGitHubRepoRef?,
    token: String,
    apiBaseUrl: String
): ProjectGitHubPullRequestDetailActionResult {
    val pullRequest = currentStore.pullRequest ?: return ProjectGitHubPullRequestDetailActionResult()
    val body = currentStore.commentDraft.trim()
    if (repo == null || token.isBlank() || body.isBlank()) {
        return ProjectGitHubPullRequestDetailActionResult()
    }
    val result = createProjectGitHubIssueComment(
        repo = repo,
        issueNumber = pullRequest.number,
        body = body,
        token = token,
        apiBaseUrl = apiBaseUrl
    )
    return ProjectGitHubPullRequestDetailActionResult(
        success = result.success,
        feedbackMessage = when {
            result.success -> result.message.ifBlank { "已回复 PR #${pullRequest.number}" }
            else -> result.error ?: result.message.ifBlank { "回复 Pull Request 失败" }
        },
        nextStore = if (result.success) {
            currentStore.copy(commentDraft = "")
        } else {
            currentStore
        },
        shouldRefreshComments = result.success,
        shouldRefreshGitHubActions = result.success
    )
}

internal suspend fun submitProjectGitHubPullRequestDetailReview(
    currentStore: ProjectGitHubPullRequestDetailStore,
    repo: ProjectGitHubRepoRef?,
    token: String,
    apiBaseUrl: String,
    event: String
): ProjectGitHubPullRequestDetailActionResult {
    val pullRequest = currentStore.pullRequest ?: return ProjectGitHubPullRequestDetailActionResult()
    val body = currentStore.reviewDraft.trim()
    if (repo == null || token.isBlank()) return ProjectGitHubPullRequestDetailActionResult()
    if ((event == "COMMENT" || event == "REQUEST_CHANGES") && body.isBlank()) {
        return ProjectGitHubPullRequestDetailActionResult(
            feedbackMessage = "评论和请求修改时请先填写评审意见。",
            nextStore = currentStore
        )
    }
    val result = submitProjectGitHubPullRequestReview(
        repo = repo,
        pullNumber = pullRequest.number,
        body = body,
        event = event,
        token = token,
        apiBaseUrl = apiBaseUrl
    )
    return ProjectGitHubPullRequestDetailActionResult(
        success = result.success,
        feedbackMessage = when {
            result.success -> result.message.ifBlank { "已提交 PR 评审" }
            else -> result.error ?: result.message.ifBlank { "提交 PR 评审失败" }
        },
        nextStore = if (result.success) {
            currentStore.copy(reviewDraft = "")
        } else {
            currentStore
        },
        shouldRefreshReviews = result.success,
        shouldRefreshGitHubActions = result.success
    )
}

internal suspend fun submitProjectGitHubPullRequestDetailReviewComment(
    currentStore: ProjectGitHubPullRequestDetailStore,
    repo: ProjectGitHubRepoRef?,
    token: String,
    apiBaseUrl: String
): ProjectGitHubPullRequestDetailActionResult {
    val pullRequest = currentStore.pullRequest ?: return ProjectGitHubPullRequestDetailActionResult()
    val body = currentStore.reviewCommentDraft.trim()
    val path = currentStore.reviewCommentPathDraft.trim()
    val lineText = currentStore.reviewCommentLineDraft.trim()
    val line = lineText.toIntOrNull()
    if (repo == null || token.isBlank()) return ProjectGitHubPullRequestDetailActionResult()
    if (body.isBlank()) {
        return ProjectGitHubPullRequestDetailActionResult(
            feedbackMessage = "请先填写代码评审评论内容。",
            nextStore = currentStore
        )
    }
    if (path.isBlank()) {
        return ProjectGitHubPullRequestDetailActionResult(
            feedbackMessage = "请先选择或填写变更文件路径。",
            nextStore = currentStore
        )
    }
    if (lineText.isNotBlank() && line == null) {
        return ProjectGitHubPullRequestDetailActionResult(
            feedbackMessage = "行号只能填写数字。",
            nextStore = currentStore
        )
    }
    if (pullRequest.headSha.isBlank()) {
        return ProjectGitHubPullRequestDetailActionResult(
            feedbackMessage = "当前 PR 还没有可用的 head commit，暂时无法提交代码评审评论。",
            nextStore = currentStore
        )
    }
    val result = createProjectGitHubPullRequestReviewComment(
        repo = repo,
        pullNumber = pullRequest.number,
        body = body,
        commitId = pullRequest.headSha,
        path = path,
        line = line,
        token = token,
        apiBaseUrl = apiBaseUrl
    )
    return ProjectGitHubPullRequestDetailActionResult(
        success = result.success,
        feedbackMessage = when {
            result.success -> result.message.ifBlank { "已提交代码评审评论" }
            else -> result.error ?: result.message.ifBlank { "提交代码评审评论失败" }
        },
        nextStore = if (result.success) {
            currentStore.copy(
                reviewCommentDraft = "",
                reviewCommentLineDraft = ""
            )
        } else {
            currentStore
        },
        shouldRefreshReviewComments = result.success
    )
}

internal suspend fun replyProjectGitHubPullRequestDetailReviewComment(
    currentStore: ProjectGitHubPullRequestDetailStore,
    repo: ProjectGitHubRepoRef?,
    token: String,
    apiBaseUrl: String,
    commentId: Long,
    body: String
): ProjectGitHubPullRequestDetailActionResult {
    val pullRequest = currentStore.pullRequest ?: return ProjectGitHubPullRequestDetailActionResult()
    if (repo == null || token.isBlank()) return ProjectGitHubPullRequestDetailActionResult()
    val result = replyProjectGitHubPullRequestReviewComment(
        repo = repo,
        pullNumber = pullRequest.number,
        commentId = commentId,
        body = body,
        token = token,
        apiBaseUrl = apiBaseUrl
    )
    return ProjectGitHubPullRequestDetailActionResult(
        success = result.success,
        feedbackMessage = when {
            result.success -> result.message.ifBlank { "已回复代码评审评论" }
            else -> result.error ?: result.message.ifBlank { "回复代码评审评论失败" }
        },
        nextStore = currentStore,
        shouldRefreshReviewComments = result.success
    )
}

internal suspend fun mergeProjectGitHubPullRequestDetail(
    currentStore: ProjectGitHubPullRequestDetailStore,
    repo: ProjectGitHubRepoRef?,
    token: String,
    apiBaseUrl: String
): ProjectGitHubPullRequestDetailActionResult {
    val pullRequest = currentStore.pullRequest ?: return ProjectGitHubPullRequestDetailActionResult()
    if (repo == null || token.isBlank()) return ProjectGitHubPullRequestDetailActionResult()
    val result = mergeProjectGitHubPullRequest(
        repo = repo,
        pullNumber = pullRequest.number,
        title = pullRequest.title,
        token = token,
        apiBaseUrl = apiBaseUrl
    )
    return ProjectGitHubPullRequestDetailActionResult(
        success = result.success,
        feedbackMessage = when {
            result.success -> result.message.ifBlank { "已合并 PR #${pullRequest.number}" }
            else -> result.error ?: result.message.ifBlank { "合并 PR 失败" }
        },
        nextStore = if (result.success) clearProjectGitHubPullRequestDetailStore() else currentStore,
        shouldRefreshGitHubActions = result.success
    )
}

internal suspend fun toggleProjectGitHubPullRequestDetailState(
    currentStore: ProjectGitHubPullRequestDetailStore,
    repo: ProjectGitHubRepoRef?,
    token: String,
    apiBaseUrl: String
): ProjectGitHubPullRequestDetailActionResult {
    val pullRequest = currentStore.pullRequest ?: return ProjectGitHubPullRequestDetailActionResult()
    if (repo == null || token.isBlank()) return ProjectGitHubPullRequestDetailActionResult()
    val result = updateProjectGitHubPullRequestState(
        repo = repo,
        pullNumber = pullRequest.number,
        close = pullRequest.isOpen,
        token = token,
        apiBaseUrl = apiBaseUrl
    )
    return ProjectGitHubPullRequestDetailActionResult(
        success = result.success,
        feedbackMessage = when {
            result.success -> result.message.ifBlank {
                if (pullRequest.isOpen) {
                    "已关闭 PR #${pullRequest.number}"
                } else {
                    "已重新打开 PR #${pullRequest.number}"
                }
            }
            else -> result.error ?: result.message.ifBlank { "Pull Request 状态更新失败" }
        },
        nextStore = if (result.success) clearProjectGitHubPullRequestDetailStore() else currentStore,
        shouldRefreshGitHubActions = result.success
    )
}
