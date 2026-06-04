package dev.reasonix.mobile.ui.project

import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal data class ProjectDetectedRepoUi(
    val rootPath: String,
    val displayName: String,
    val relativePath: String,
    val isWorkspaceRoot: Boolean,
    val hasGitMetadata: Boolean
)

@Serializable
internal data class ProjectGitHubRepoRef(
    val owner: String,
    val repo: String
)

internal data class ProjectGitHubWorkflowUi(
    val id: Long,
    val name: String,
    val path: String,
    val state: String,
    val htmlUrl: String?
) {
    val canDispatch: Boolean get() = state.equals("active", ignoreCase = true)
    val stateLabel: String get() = if (state.isBlank()) "状态未知" else "状态 ${state.lowercase(Locale.getDefault())}"
}

@Serializable
internal data class ProjectGitHubWorkflowRunUi(
    val id: Long,
    val name: String,
    val displayTitle: String,
    val headBranch: String,
    val status: String,
    val conclusion: String?,
    val event: String,
    val runNumber: Long,
    val updatedAt: String,
    val htmlUrl: String?
) {
    val statusLabel: String
        get() = buildProjectGitHubStatusLabel(status, conclusion)
}

internal data class ProjectGitHubArtifactUi(
    val id: Long,
    val name: String,
    val sizeInBytes: Long,
    val archiveDownloadUrl: String,
    val expired: Boolean,
    val updatedAt: String
) {
    val sizeLabel: String get() = formatProjectByteSize(sizeInBytes)
}

internal data class ProjectGitHubReleaseAssetUi(
    val id: Long,
    val name: String,
    val sizeInBytes: Long,
    val apiUrl: String,
    val browserDownloadUrl: String?,
    val updatedAt: String
) {
    val sizeLabel: String get() = formatProjectByteSize(sizeInBytes)
}

internal data class ProjectGitHubArtifactDialogUi(
    val runTitle: String,
    val artifacts: List<ProjectGitHubArtifactUi>,
    val runId: Long,
    val runHtmlUrl: String?
)

internal data class ProjectGitHubReleaseAssetDialogUi(
    val releaseTitle: String,
    val assets: List<ProjectGitHubReleaseAssetUi>,
    val releaseHtmlUrl: String?
)

internal data class ProjectGitHubReleaseUi(
    val id: Long,
    val tagName: String,
    val name: String,
    val body: String,
    val isDraft: Boolean,
    val isPrerelease: Boolean,
    val publishedAt: String,
    val htmlUrl: String?,
    val assets: List<ProjectGitHubReleaseAssetUi>
)

@Serializable
internal data class ProjectGitHubIssueUi(
    val number: Long,
    val title: String,
    val body: String,
    val state: String,
    val authorLogin: String?,
    val updatedAt: String,
    val htmlUrl: String?,
    val labels: List<String>
) {
    val isOpen: Boolean get() = state.equals("open", ignoreCase = true)
    val authorLabel: String get() = authorLogin?.takeIf { it.isNotBlank() } ?: "未知作者"
    val stateLabel: String
        get() = when {
            isOpen -> "开放"
            state.equals("closed", ignoreCase = true) -> "已关闭"
            state.isBlank() -> "状态未知"
            else -> "状态 ${state.lowercase(Locale.getDefault())}"
        }
}

@Serializable
internal data class ProjectGitHubPullRequestUi(
    val number: Long,
    val title: String,
    val body: String,
    val state: String,
    val isDraft: Boolean,
    val isMerged: Boolean,
    val authorLogin: String?,
    val updatedAt: String,
    val htmlUrl: String?,
    val labels: List<String>,
    val headSha: String,
    val headBranch: String,
    val baseBranch: String
) {
    val isOpen: Boolean get() = state.equals("open", ignoreCase = true)
    val canMerge: Boolean get() = isOpen && !isMerged && !isDraft
    val authorLabel: String get() = authorLogin?.takeIf { it.isNotBlank() } ?: "未知作者"
    val stateLabel: String
        get() = when {
            isMerged -> "已合并"
            isDraft && isOpen -> "草稿中"
            isOpen -> "开放"
            state.equals("closed", ignoreCase = true) -> "已关闭"
            state.isBlank() -> "状态未知"
            else -> "状态 ${state.lowercase(Locale.getDefault())}"
        }
}

internal data class ProjectGitHubActionsState(
    val repo: ProjectGitHubRepoRef?,
    val viewerLogin: String?,
    val viewerName: String?,
    val defaultBranch: String?,
    val repoHtmlUrl: String?,
    val workflows: List<ProjectGitHubWorkflowUi>,
    val recentRuns: List<ProjectGitHubWorkflowRunUi>,
    val releases: List<ProjectGitHubReleaseUi>,
    val issues: List<ProjectGitHubIssueUi>,
    val pullRequests: List<ProjectGitHubPullRequestUi>,
    val errorMessage: String?
) {
    companion object {
        fun empty(repo: ProjectGitHubRepoRef? = null) = ProjectGitHubActionsState(
            repo = repo,
            viewerLogin = null,
            viewerName = null,
            defaultBranch = null,
            repoHtmlUrl = null,
            workflows = emptyList(),
            recentRuns = emptyList(),
            releases = emptyList(),
            issues = emptyList(),
            pullRequests = emptyList(),
            errorMessage = null
        )
    }
}

internal data class ProjectGitHubRemoteEntryUi(
    val name: String,
    val path: String,
    val type: String,
    val sha: String?,
    val size: Long,
    val htmlUrl: String?
)

internal data class ProjectGitHubRemoteBrowserState(
    val repo: ProjectGitHubRepoRef?,
    val currentPath: String,
    val currentRef: String,
    val entries: List<ProjectGitHubRemoteEntryUi>,
    val isLoading: Boolean,
    val errorMessage: String?
) {
    companion object {
        fun empty() = ProjectGitHubRemoteBrowserState(
            repo = null,
            currentPath = "",
            currentRef = "",
            entries = emptyList(),
            isLoading = false,
            errorMessage = null
        )
    }
}

internal data class ProjectGitHubRemoteFileUi(
    val name: String,
    val path: String,
    val sha: String,
    val size: Long,
    val content: String?,
    val htmlUrl: String?
)

internal data class ProjectGitHubCommentUi(
    val id: Long,
    val body: String,
    val authorLogin: String,
    val updatedAt: String,
    val htmlUrl: String?
)

internal data class ProjectGitHubPullRequestReviewUi(
    val id: Long,
    val body: String,
    val state: String,
    val authorLogin: String,
    val updatedAt: String,
    val htmlUrl: String?
)

internal data class ProjectGitHubPullRequestFileUi(
    val sha: String,
    val fileName: String,
    val status: String,
    val additions: Int,
    val deletions: Int,
    val changes: Int,
    val blobUrl: String?,
    val rawUrl: String?,
    val contentsUrl: String?,
    val patch: String?
)

internal data class ProjectGitHubPullRequestReviewCommentUi(
    val id: Long,
    val body: String,
    val authorLogin: String,
    val path: String,
    val line: Int?,
    val updatedAt: String,
    val htmlUrl: String?
)

internal enum class ProjectGitHubGlobalSearchResultType {
    ISSUE,
    PULL_REQUEST,
    FILE
}

internal data class ProjectGitHubGlobalSearchResultUi(
    val type: ProjectGitHubGlobalSearchResultType,
    val title: String,
    val subtitle: String,
    val repoOwner: String,
    val repoName: String,
    val rootPath: String?,
    val url: String?,
    val number: Long? = null,
    val filePath: String? = null,
    val updatedAt: String? = null
)

internal data class ProjectGitHubGlobalSearchStore(
    val query: String = "",
    val results: List<ProjectGitHubGlobalSearchResultUi> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val isVisible: Boolean = false
)

internal data class ProjectGitHubGlobalTaskCenterUi(
    val criticalTasks: List<ProjectGitHubWorkspaceTaskUi> = emptyList(),
    val attentionTasks: List<ProjectGitHubWorkspaceTaskUi> = emptyList(),
    val isVisible: Boolean = false
)

internal enum class ProjectGitHubWorkspaceFilterType(val label: String) {
    ALL("全部"),
    ABNORMAL("异常优先"),
    LOCAL_CHANGES("本地有改动"),
    OPEN_ITEMS("有开放事项"),
    GITHUB_ONLY("仅 GitHub")
}

internal enum class ProjectGitHubWorkspaceBatchAction(val label: String) {
    FETCH("批量获取远端 (Fetch)"),
    PULL("批量拉取 (Pull)"),
    REFRESH_STATUS("批量刷新状态"),
    REFRESH_REMOTE("批量刷新 GitHub 摘要")
}

internal data class ProjectGitHubWorkspaceTaskUi(
    val title: String,
    val subtitle: String,
    val repoRoot: String,
    val isCritical: Boolean = false,
    val targetTab: ProjectGitHubWorkspaceRepoWorkbenchTab? = null,
    val targetWorkflowRun: ProjectGitHubWorkflowRunUi? = null,
    val targetIssue: ProjectGitHubIssueUi? = null,
    val targetPullRequest: ProjectGitHubPullRequestUi? = null,
    val actionLabel: String = "查看"
)

internal data class ProjectGitHubWorkspaceRepoCardUi(
    val rootPath: String,
    val title: String,
    val subtitle: String,
    val changeSummary: String,
    val highlightChanges: Boolean,
    val remoteSummary: String?,
    val highlightRemoteSummary: Boolean,
    val remoteErrorMessage: String?,
    val latestWorkflowSummary: String?,
    val latestWorkflowHasIssue: Boolean,
    val isSelected: Boolean,
    val hasGitMetadata: Boolean,
    val hasGitHubRepo: Boolean,
    val hasWorkingTreeChanges: Boolean,
    val behindCount: Int,
    val conflictCount: Int,
    val latestRunHasIssue: Boolean,
    val hasOpenWorkItems: Boolean,
    val openIssueCount: Int,
    val latestOpenIssue: ProjectGitHubIssueUi?,
    val openPullRequestCount: Int,
    val latestOpenPullRequest: ProjectGitHubPullRequestUi?,
    val latestWorkflowTitle: String?,
    val latestRun: ProjectGitHubWorkflowRunUi?,
    val severityScore: Int,
    val severityLabel: String,
    val recommendedActions: List<ProjectGitHubWorkspaceQuickActionUi>
)

internal data class ProjectGitHubWorkspaceQuickActionUi(
    val label: String,
    val targetTab: ProjectGitHubWorkspaceRepoWorkbenchTab,
    val targetWorkflowRun: ProjectGitHubWorkflowRunUi? = null,
    val targetIssue: ProjectGitHubIssueUi? = null,
    val targetPullRequest: ProjectGitHubPullRequestUi? = null
)

internal data class ProjectGitHubWorkspaceOverviewUi(
    val repoCount: Int,
    val gitRepoCount: Int,
    val githubRepoCount: Int,
    val remoteSummaryCount: Int,
    val healthyRepoCount: Int,
    val dirtyRepoCount: Int,
    val behindRepoCount: Int,
    val conflictRepoCount: Int,
    val failingWorkflowRepoCount: Int,
    val criticalTaskCount: Int,
    val attentionTaskCount: Int,
    val openWorkItemRepoCount: Int,
    val lastUpdatedLabel: String
)

@Serializable
internal data class ProjectGitHubWorkspaceRemoteSummaryCache(
    val summaries: Map<String, ProjectGitHubWorkspaceRemoteSummaryUi> = emptyMap(),
    val lastUpdatedMillis: Long = 0
) {
    val lastUpdatedLabel: String
        get() = if (lastUpdatedMillis == 0L) "从未更新" else formatProjectDateTime(lastUpdatedMillis)
}

@Serializable
internal data class ProjectGitHubWorkspaceRemoteSummaryUi(
    val repo: ProjectGitHubRepoRef,
    val defaultBranch: String?,
    val repoHtmlUrl: String?,
    val latestRun: ProjectGitHubWorkflowRunUi?,
    val runningRunCount: Int,
    val openIssueCount: Int,
    val latestOpenIssue: ProjectGitHubIssueUi?,
    val openPullRequestCount: Int,
    val latestOpenPullRequest: ProjectGitHubPullRequestUi?,
    val hasWorkItemPreview: Boolean,
    val errorMessage: String?
) {
    val hasOpenWorkItems: Boolean get() = openIssueCount > 0 || openPullRequestCount > 0
    val latestRunHasIssue: Boolean
        get() = latestRun?.let { run ->
            !run.status.equals("completed", ignoreCase = true) ||
                (run.conclusion != null && !run.conclusion.equals("success", ignoreCase = true) && !run.conclusion.equals("neutral", ignoreCase = true))
        } ?: false
}

internal data class ProjectDownloadEnqueueResult(
    val downloadId: Long,
    val fileName: String
)

@Serializable
internal data class ProjectGitHubDownloadRecordUi(
    val id: String,
    val typeLabel: String,
    val title: String,
    val fileName: String,
    val createdAtMillis: Long,
    val downloadId: Long,
    val repoOwner: String?,
    val repoName: String?,
    val repoLabel: String?,
    val sourceUrl: String?
) {
    val createdAtLabel: String
        get() = formatProjectDateTime(createdAtMillis)
}

internal data class ProjectGitHubHttpResult(
    val success: Boolean,
    val code: Int,
    val body: String,
    val error: String?
)

internal data class ProjectGitHubCommandResult(
    val success: Boolean,
    val message: String,
    val error: String? = null
)

internal data class ProjectGitHubCreateRepoResult(
    val success: Boolean,
    val repo: ProjectGitHubRepoRef?,
    val cloneUrl: String?,
    val sshUrl: String?,
    val recommendedRemoteUrl: String?,
    val htmlUrl: String?,
    val error: String?
)

internal data class ProjectGitHubArtifactLoadResult(
    val success: Boolean,
    val artifacts: List<ProjectGitHubArtifactUi>,
    val error: String?
)

internal data class ProjectGitHubWorkflowRunDetailLoadResult(
    val success: Boolean,
    val detail: ProjectGitHubWorkflowRunDetailUi?,
    val error: String?
)

internal data class ProjectGitHubWorkflowLogLoadResult(
    val success: Boolean,
    val entries: List<ProjectGitHubWorkflowLogEntryUi>,
    val error: String?
)

internal data class ProjectGitHubRemoteDirectoryLoadResult(
    val success: Boolean,
    val state: ProjectGitHubRemoteBrowserState?,
    val error: String?
)

internal data class ProjectGitHubRemoteFileLoadResult(
    val success: Boolean,
    val file: ProjectGitHubRemoteFileUi?,
    val error: String?
)

internal data class ProjectGitHubCommentLoadResult(
    val success: Boolean,
    val comments: List<ProjectGitHubCommentUi>,
    val error: String?
)

internal data class ProjectGitHubPullRequestReviewLoadResult(
    val success: Boolean,
    val reviews: List<ProjectGitHubPullRequestReviewUi>,
    val error: String?
)

internal data class ProjectGitHubPullRequestFileLoadResult(
    val success: Boolean,
    val files: List<ProjectGitHubPullRequestFileUi>,
    val error: String?
)

internal data class ProjectGitHubPullRequestReviewCommentLoadResult(
    val success: Boolean,
    val comments: List<ProjectGitHubPullRequestReviewCommentUi>,
    val error: String?
)

internal fun buildProjectGitHubStatusLabel(status: String, conclusion: String?): String = buildString {
    append(status.ifBlank { "未知状态" })
    conclusion?.takeIf { it.isNotBlank() }?.let {
        append(" (")
        append(it)
        append(")")
    }
}

internal fun formatProjectByteSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val exp = (Math.log(bytes.toDouble()) / Math.log(1024.0)).toInt()
    val pre = "KMGTPE"[exp - 1]
    return String.format(Locale.getDefault(), "%.1f %sB", bytes / Math.pow(1024.0, exp.toDouble()), pre)
}

internal fun formatProjectDateTime(millis: Long): String {
    return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
}
