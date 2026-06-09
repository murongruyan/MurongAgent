package com.murong.agent.ui.project

import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale

internal data class ProjectDetectedRepoUi(
    val rootPath: String,
    val displayName: String,
    val relativePath: String,
    val isWorkspaceRoot: Boolean,
    val hasGitMetadata: Boolean,
    val hasReadme: Boolean,
    val hasGradleBuild: Boolean,
    val hasPackageJson: Boolean,
    val hasGitHubWorkflows: Boolean
)

@Serializable
internal data class ProjectGitHubRepoRef(
    val owner: String,
    val repo: String
)

internal data class ProjectGitHubAccountRepoUi(
    val id: Long,
    val owner: String,
    val name: String,
    val description: String,
    val isPrivate: Boolean,
    val stargazerCount: Long,
    val forkCount: Long,
    val htmlUrl: String?,
    val defaultBranch: String,
    val updatedAt: String
) {
    val repoRef: ProjectGitHubRepoRef
        get() = ProjectGitHubRepoRef(owner = owner, repo = name)
    val fullName: String
        get() = "$owner/$name"
    val visibilityLabel: String
        get() = if (isPrivate) "私人" else "公开"
}

internal data class ProjectGitHubViewerRepositoriesState(
    val viewerLogin: String?,
    val viewerName: String?,
    val repositories: List<ProjectGitHubAccountRepoUi>,
    val errorMessage: String?
) {
    companion object {
        fun empty() = ProjectGitHubViewerRepositoriesState(
            viewerLogin = null,
            viewerName = null,
            repositories = emptyList(),
            errorMessage = null
        )
    }
}

internal data class ProjectGitHubReadmeUi(
    val name: String,
    val path: String,
    val htmlUrl: String?,
    val content: String
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

internal data class ProjectGitHubWorkflowDispatchInputUi(
    val key: String = "",
    val value: String = "",
    val description: String? = null,
    val required: Boolean = false,
    val defaultValue: String? = null,
    val type: String = "string",
    val options: List<String> = emptyList(),
    val autoDetected: Boolean = false
)

internal data class ProjectGitHubWorkflowDispatchSchemaLoadResult(
    val inputs: List<ProjectGitHubWorkflowDispatchInputUi>,
    val error: String? = null
)

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
    val hasIssue: Boolean
        get() = projectGitHubRunHasIssue(status, conclusion)
}

internal data class ProjectGitHubWorkflowStepUi(
    val number: Int,
    val name: String,
    val status: String,
    val conclusion: String?,
    val startedAt: String,
    val completedAt: String
) {
    val statusLabel: String
        get() = buildProjectGitHubStatusLabel(status, conclusion)
    val hasIssue: Boolean
        get() = projectGitHubRunHasIssue(status, conclusion)
    val durationLabel: String
        get() = buildProjectGitHubDurationLabel(
            startedAt = startedAt,
            completedAt = completedAt,
            status = status
        )
}

internal data class ProjectGitHubWorkflowJobUi(
    val id: Long,
    val name: String,
    val status: String,
    val conclusion: String?,
    val startedAt: String,
    val completedAt: String,
    val steps: List<ProjectGitHubWorkflowStepUi>
) {
    val statusLabel: String
        get() = buildProjectGitHubStatusLabel(status, conclusion)
    val failedSteps: List<ProjectGitHubWorkflowStepUi>
        get() = steps.filter { it.hasIssue }
    val hasIssue: Boolean
        get() = projectGitHubRunHasIssue(status, conclusion) || failedSteps.isNotEmpty()
    val durationLabel: String
        get() = buildProjectGitHubDurationLabel(
            startedAt = startedAt,
            completedAt = completedAt,
            status = status
        )
}

internal data class ProjectGitHubWorkflowLogEntryUi(
    val entryName: String,
    val displayName: String,
    val preview: String,
    val totalLineCount: Int,
    val truncated: Boolean
)

internal data class ProjectGitHubWorkflowLogSearchHitUi(
    val hasMatch: Boolean,
    val matchedLineCount: Int,
    val snippet: String,
    val matchedLineIndices: List<Int>
) {
    val matchedLineNumbers: List<Int>
        get() = matchedLineIndices.map { it + 1 }
}

internal data class ProjectGitHubWorkflowRunDetailUi(
    val id: Long,
    val repo: ProjectGitHubRepoRef,
    val title: String,
    val workflowName: String,
    val headBranch: String,
    val status: String,
    val conclusion: String?,
    val event: String,
    val runNumber: Long,
    val createdAt: String,
    val updatedAt: String,
    val htmlUrl: String?,
    val jobs: List<ProjectGitHubWorkflowJobUi>,
    val artifacts: List<ProjectGitHubArtifactUi>,
    val artifactsError: String?,
    val logEntries: List<ProjectGitHubWorkflowLogEntryUi>,
    val logsError: String?
) {
    val statusLabel: String
        get() = buildProjectGitHubStatusLabel(status, conclusion)
    val eventLabel: String
        get() = event.ifBlank { "未知触发方式" }
    val durationLabel: String
        get() = buildProjectGitHubDurationLabel(
            startedAt = createdAt,
            completedAt = updatedAt,
            status = status
        )
    val createdAtLabel: String
        get() = formatProjectGitHubIsoDateTime(createdAt)
    val updatedAtLabel: String
        get() = formatProjectGitHubIsoDateTime(updatedAt)
    val hasIssue: Boolean
        get() = projectGitHubRunHasIssue(status, conclusion) || issueSummaries.isNotEmpty()
    val issueSummaries: List<String>
        get() = buildList {
            jobs.filter { it.hasIssue }.take(4).forEach { job ->
                val failedStepSummary = job.failedSteps
                    .take(2)
                    .joinToString("、") { it.name }
                    .takeIf { it.isNotBlank() }
                add(
                    buildString {
                        append(job.name)
                        append("：")
                        append(job.statusLabel)
                        failedStepSummary?.let {
                            append("，步骤 ")
                            append(it)
                        }
                    }
                )
            }
            logsError?.takeIf { it.isNotBlank() }?.let { add("日志：$it") }
            artifactsError?.takeIf { it.isNotBlank() }?.let { add("产物：$it") }
        }
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
    val htmlUrl: String?,
    val downloadUrl: String? = null
) {
    val isDirectory: Boolean
        get() = type.equals("dir", ignoreCase = true)
    val typeLabel: String
        get() = when {
            isDirectory -> "目录"
            type.equals("file", ignoreCase = true) -> "文件"
            type.equals("symlink", ignoreCase = true) -> "符号链接"
            type.equals("submodule", ignoreCase = true) -> "子模块"
            type.isBlank() -> "未知类型"
            else -> type
        }
}

internal data class ProjectGitHubRemoteBrowserState(
    val repo: ProjectGitHubRepoRef?,
    val currentPath: String,
    val currentRef: String,
    val entries: List<ProjectGitHubRemoteEntryUi>,
    val repoHtmlUrl: String? = null,
    val isLoading: Boolean = false,
    val errorMessage: String?
) {
    companion object {
        fun empty(repo: ProjectGitHubRepoRef? = null) = ProjectGitHubRemoteBrowserState(
            repo = repo,
            currentPath = "",
            currentRef = "",
            entries = emptyList(),
            repoHtmlUrl = repo?.let { "https://github.com/${it.owner}/${it.repo}" },
            isLoading = false,
            errorMessage = null
        )
    }
}

internal data class ProjectGitHubRemoteFileUi(
    val name: String,
    val path: String,
    val sha: String?,
    val ref: String,
    val size: Long,
    val content: String,
    val htmlUrl: String?,
    val downloadUrl: String? = null,
    val truncated: Boolean = false
)

internal data class ProjectGitHubCommentUi(
    val id: Long,
    val body: String,
    val authorLogin: String?,
    val createdAt: String,
    val updatedAt: String,
    val htmlUrl: String?
) {
    val authorLabel: String
        get() = authorLogin?.takeIf { it.isNotBlank() } ?: "未知作者"
    val timeLabel: String
        get() = updatedAt.ifBlank { createdAt }.ifBlank { "时间未知" }
}

internal data class ProjectGitHubPullRequestReviewUi(
    val id: Long,
    val body: String,
    val state: String,
    val authorLogin: String?,
    val submittedAt: String,
    val commitId: String?,
    val htmlUrl: String?
) {
    val authorLabel: String
        get() = authorLogin?.takeIf { it.isNotBlank() } ?: "未知作者"
    val stateLabel: String
        get() = when {
            state.equals("APPROVED", ignoreCase = true) -> "已批准"
            state.equals("CHANGES_REQUESTED", ignoreCase = true) -> "请求修改"
            state.equals("COMMENTED", ignoreCase = true) -> "已评论"
            state.isBlank() -> "状态未知"
            else -> state
        }
    val timeLabel: String
        get() = submittedAt.ifBlank { "时间未知" }
}

internal data class ProjectGitHubPullRequestFileUi(
    val path: String,
    val status: String,
    val additions: Long,
    val deletions: Long,
    val changes: Long,
    val patch: String?,
    val sha: String? = null,
    val blobUrl: String? = null,
    val rawUrl: String? = null,
    val contentsUrl: String? = null,
    val fileName: String = path.substringAfterLast('/')
) {
    val displayPath: String
        get() = path
    val statusLabel: String
        get() = when {
            status.equals("added", ignoreCase = true) -> "新增"
            status.equals("modified", ignoreCase = true) -> "修改"
            status.equals("removed", ignoreCase = true) -> "删除"
            status.equals("renamed", ignoreCase = true) -> "重命名"
            status.equals("copied", ignoreCase = true) -> "复制"
            status.equals("changed", ignoreCase = true) -> "变更"
            status.isBlank() -> "未知状态"
            else -> status
        }
    val summaryLabel: String
        get() = buildString {
            append(statusLabel)
            append(" · ")
            append(path)
            if (changes > 0) {
                append(" · +")
                append(additions)
                append(" / -")
                append(deletions)
            }
        }
}

internal data class ProjectGitHubPullRequestReviewCommentUi(
    val id: Long,
    val body: String,
    val authorLogin: String?,
    val path: String,
    val line: Int?,
    val side: String? = null,
    val parentCommentId: Long? = null,
    val createdAt: String,
    val updatedAt: String,
    val htmlUrl: String?
) {
    val authorLabel: String
        get() = authorLogin?.takeIf { it.isNotBlank() } ?: "未知作者"
    val timeLabel: String
        get() = updatedAt.ifBlank { createdAt }.ifBlank { "时间未知" }
    val pathLabel: String
        get() = path.ifBlank { "文件未知" }
    val positionLabel: String
        get() = line?.let { "L$it" } ?: "文件级评论"
}

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

internal enum class ProjectGitHubWorkspaceTaskKind(val label: String) {
    GENERAL("概览"),
    WORKFLOW("工作流"),
    LOCAL_CONFLICT("冲突"),
    SYNC("同步"),
    COLLABORATION("协作"),
    LOCAL_CHANGES("本地改动"),
    REMOTE_BINDING("远端绑定"),
    REMOTE_ERROR("远端摘要")
}

internal data class ProjectGitHubWorkspaceTaskUi(
    val title: String,
    val subtitle: String,
    val repoRoot: String,
    val repoTitle: String = "",
    val isCritical: Boolean = false,
    val kind: ProjectGitHubWorkspaceTaskKind = ProjectGitHubWorkspaceTaskKind.GENERAL,
    val destinationLabel: String? = null,
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

internal data class ProjectGitHubMutationResult<T>(
    val success: Boolean,
    val value: T?,
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

private fun projectGitHubRunHasIssue(status: String, conclusion: String?): Boolean {
    if (!status.equals("completed", ignoreCase = true)) return true
    if (conclusion.isNullOrBlank()) return false
    return !conclusion.equals("success", ignoreCase = true) &&
        !conclusion.equals("neutral", ignoreCase = true) &&
        !conclusion.equals("skipped", ignoreCase = true)
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

internal fun formatProjectGitHubIsoDateTime(raw: String): String {
    val millis = parseProjectGitHubIsoMillis(raw) ?: return raw.ifBlank { "时间未知" }
    return formatProjectDateTime(millis)
}

internal fun buildProjectGitHubDurationLabel(
    startedAt: String,
    completedAt: String,
    status: String
): String {
    val startMillis = parseProjectGitHubIsoMillis(startedAt) ?: return "耗时未知"
    val completedMillis = parseProjectGitHubIsoMillis(completedAt)
    val endMillis = when {
        completedMillis != null -> completedMillis
        status.equals("completed", ignoreCase = true) -> return "耗时未知"
        else -> System.currentTimeMillis()
    }
    val durationMillis = (endMillis - startMillis).coerceAtLeast(0L)
    val minutes = durationMillis / 60_000L
    val seconds = (durationMillis % 60_000L) / 1_000L
    return if (minutes > 0) {
        "${minutes}分${seconds}秒"
    } else {
        "${seconds}秒"
    }
}

private fun parseProjectGitHubIsoMillis(raw: String): Long? {
    if (raw.isBlank()) return null
    return runCatching { Instant.parse(raw).toEpochMilli() }.getOrNull()
}
