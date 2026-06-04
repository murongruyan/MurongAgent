package dev.reasonix.mobile.ui.project

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import dev.reasonix.mobile.ui.ReasonixSecondaryPageFrame
import dev.reasonix.mobile.ui.rememberReasonixChromeColor
import dev.reasonix.mobile.ui.rememberReasonixMutedTextColor
import dev.reasonix.mobile.ui.rememberReasonixSurfaceColor

internal enum class ProjectGitHubWorkspaceRepoWorkbenchTab(val label: String) {
    OVERVIEW("概览"),
    WORKFLOW("工作流"),
    REMOTE("远端"),
    ISSUES("Issue"),
    PULL_REQUESTS("PR"),
    RELEASES("Release")
}

internal data class ProjectGitHubWorkspaceWorkbenchHeaderUi(
    val title: String,
    val subtitle: String,
    val changeSummary: String,
    val highlightChanges: Boolean
)

internal data class ProjectGitHubWorkspaceWorkbenchOverviewUi(
    val remoteSummaryText: String,
    val remoteErrorMessage: String?,
    val latestWorkflow: ProjectGitHubWorkspaceWorkbenchLatestWorkflowUi?,
    val remoteUrl: String?,
    val upstreamBranch: String?,
    val aheadCount: Int,
    val behindCount: Int,
    val remoteBranchCount: Int,
    val tokenConfigured: Boolean
)

internal data class ProjectGitHubWorkspaceWorkbenchLatestWorkflowUi(
    val title: String,
    val detail: String,
    val hasIssue: Boolean
)

@Composable
internal fun ProjectGitHubWorkspaceRepoWorkbenchTabs(
    selectedTab: ProjectGitHubWorkspaceRepoWorkbenchTab,
    onSelectTab: (ProjectGitHubWorkspaceRepoWorkbenchTab) -> Unit
) {
    val chromeColor = rememberReasonixChromeColor()

    PrimaryScrollableTabRow(
        selectedTabIndex = selectedTab.ordinal,
        containerColor = chromeColor.copy(alpha = 0.28f)
    ) {
        ProjectGitHubWorkspaceRepoWorkbenchTab.entries.forEach { tab ->
            Tab(
                selected = selectedTab == tab,
                onClick = { onSelectTab(tab) },
                text = { Text(tab.label) }
            )
        }
    }
}

@Composable
internal fun ProjectGitHubWorkspaceRepoWorkbenchHeader(
    header: ProjectGitHubWorkspaceWorkbenchHeaderUi,
    isGitLoading: Boolean,
    isGitHubLoading: Boolean,
    isGitHubActionRunning: Boolean,
    tokenConfigured: Boolean,
    onExitWorkbench: () -> Unit,
    onRefreshGitState: () -> Unit,
    onRefreshGitHubActions: () -> Unit
) {
    val chromeColor = rememberReasonixChromeColor()
    val mutedTextColor = rememberReasonixMutedTextColor()

    ProjectSectionCard(
        shape = RoundedCornerShape(14.dp),
        surfaceColorOverride = chromeColor.copy(alpha = 0.38f)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(header.title, style = MaterialTheme.typography.titleSmall)
            Text(
                text = header.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = mutedTextColor
            )
            Text(
                text = header.changeSummary,
                style = MaterialTheme.typography.bodySmall,
                color = if (header.highlightChanges) {
                    MaterialTheme.colorScheme.primary
                } else {
                    mutedTextColor
                }
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onExitWorkbench) {
                    Text("返回工作区")
                }
                OutlinedButton(
                    onClick = onRefreshGitState,
                    enabled = !isGitLoading
                ) {
                    Text("刷新本地")
                }
                if (tokenConfigured) {
                    OutlinedButton(
                        onClick = onRefreshGitHubActions,
                        enabled = !isGitHubLoading && !isGitHubActionRunning
                    ) {
                        Text("刷新 GitHub")
                    }
                }
            }
        }
    }
}

@Composable
internal fun ProjectGitHubWorkspaceRepoWorkbenchOverviewTab(
    overview: ProjectGitHubWorkspaceWorkbenchOverviewUi,
    downloads: List<ProjectGitHubDownloadRecordUi>,
    onShowWorkflowTab: () -> Unit,
    onShowIssuesTab: () -> Unit,
    onOpenLatestRunDetail: (() -> Unit)?,
    onOpenSystemDownloads: () -> Unit,
    onOpenDownloadSource: (ProjectGitHubDownloadRecordUi) -> Unit
) {
    val chromeColor = rememberReasonixChromeColor()
    val surfaceColor = rememberReasonixSurfaceColor()
    val mutedTextColor = rememberReasonixMutedTextColor()

    ProjectSectionCard(
        shape = RoundedCornerShape(14.dp),
        surfaceColorOverride = surfaceColor.copy(alpha = 0.58f)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("仓库概览", style = MaterialTheme.typography.titleSmall)
            Text(
                text = overview.remoteSummaryText,
                style = MaterialTheme.typography.bodySmall,
                color = mutedTextColor
            )
            overview.remoteErrorMessage?.takeIf { it.isNotBlank() }?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            overview.latestWorkflow?.let { run ->
                ProjectGitHubWorkspaceLatestWorkflowCard(
                    run = run,
                    chromeColor = chromeColor,
                    mutedTextColor = mutedTextColor,
                    onShowWorkflowTab = onShowWorkflowTab,
                    onShowIssuesTab = onShowIssuesTab,
                    onOpenLatestRunDetail = onOpenLatestRunDetail
                )
            }
            ProjectGitRemoteSummaryCard(
                remoteUrl = overview.remoteUrl,
                upstreamBranch = overview.upstreamBranch,
                aheadCount = overview.aheadCount,
                behindCount = overview.behindCount,
                remoteBranchCount = overview.remoteBranchCount,
                tokenConfigured = overview.tokenConfigured
            )
            ProjectGitHubDownloadHistorySection(
                downloads = downloads,
                onOpenSystemDownloads = onOpenSystemDownloads,
                onOpenSource = onOpenDownloadSource
            )
        }
    }
}

@Composable
private fun ProjectGitHubWorkspaceLatestWorkflowCard(
    run: ProjectGitHubWorkspaceWorkbenchLatestWorkflowUi,
    chromeColor: Color,
    mutedTextColor: Color,
    onShowWorkflowTab: () -> Unit,
    onShowIssuesTab: () -> Unit,
    onOpenLatestRunDetail: (() -> Unit)?
) {
    ProjectInsetCard(
        shape = RoundedCornerShape(12.dp),
        surfaceColorOverride = chromeColor.copy(alpha = 0.30f)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("最近工作流", style = MaterialTheme.typography.labelMedium)
            Text(
                text = run.title,
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = run.detail,
                style = MaterialTheme.typography.bodySmall,
                color = if (run.hasIssue) {
                    MaterialTheme.colorScheme.error
                } else {
                    mutedTextColor
                }
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onShowWorkflowTab) {
                    Text("切到工作流")
                }
                OutlinedButton(onClick = onShowIssuesTab) {
                    Text("切到事项")
                }
                onOpenLatestRunDetail?.let { openDetail ->
                    TextButton(onClick = openDetail) {
                        Text("运行详情")
                    }
                }
            }
        }
    }
}

@Composable
internal fun ProjectGitHubWorkspaceRepoWorkbenchPage(
    summary: ProjectGitHubWorkspaceRepoSummaryUi,
    remoteSummary: ProjectGitHubWorkspaceRemoteSummaryUi?,
    gitState: ProjectGitStatusUi,
    githubActionsState: ProjectGitHubActionsState,
    remoteRepoState: ProjectGitHubRemoteBrowserState,
    isGitLoading: Boolean,
    isGitHubLoading: Boolean,
    isGitHubActionRunning: Boolean,
    isRemoteRepoLoading: Boolean,
    tokenConfigured: Boolean,
    remoteRepoRefDraft: String,
    onRemoteRepoRefDraftChange: (String) -> Unit,
    onRefreshGitState: () -> Unit,
    onRefreshGitHubActions: () -> Unit,
    onRefreshRemoteRepository: () -> Unit,
    onApplyRemoteRef: () -> Unit,
    onOpenRepoPage: () -> Unit,
    onOpenWorkflowPage: (ProjectGitHubWorkflowUi) -> Unit,
    onRunWorkflow: (ProjectGitHubWorkflowUi) -> Unit,
    onOpenArtifacts: (ProjectGitHubWorkflowRunUi) -> Unit,
    onOpenRunPage: (ProjectGitHubWorkflowRunUi) -> Unit,
    onDownloadRunLogs: (ProjectGitHubWorkflowRunUi) -> Unit,
    onOpenRunDetail: (ProjectGitHubWorkflowRunUi) -> Unit,
    onOpenRemoteParent: () -> Unit,
    onOpenRemoteRoot: () -> Unit,
    onOpenRemoteEntry: (ProjectGitHubRemoteEntryUi) -> Unit,
    onOpenRemoteEntryPage: (ProjectGitHubRemoteEntryUi) -> Unit,
    onCreateIssue: () -> Unit,
    onOpenIssueDetail: (ProjectGitHubIssueUi) -> Unit,
    onToggleIssueState: (ProjectGitHubIssueUi, Boolean) -> Unit,
    onOpenIssuePage: (ProjectGitHubIssueUi) -> Unit,
    onCreatePullRequest: () -> Unit,
    onOpenPullRequestDetail: (ProjectGitHubPullRequestUi) -> Unit,
    onTogglePullRequestState: (ProjectGitHubPullRequestUi, Boolean) -> Unit,
    onMergePullRequest: (ProjectGitHubPullRequestUi) -> Unit,
    onOpenPullRequestPage: (ProjectGitHubPullRequestUi) -> Unit,
    onCreateRelease: () -> Unit,
    onEditRelease: (ProjectGitHubReleaseUi) -> Unit,
    onToggleReleaseMode: (ProjectGitHubReleaseUi, Boolean) -> Unit,
    onTogglePrerelease: (ProjectGitHubReleaseUi, Boolean) -> Unit,
    onDeleteRelease: (ProjectGitHubReleaseUi) -> Unit,
    onOpenReleasePage: (ProjectGitHubReleaseUi) -> Unit,
    onOpenReleaseAssets: (ProjectGitHubReleaseUi) -> Unit,
    downloads: List<ProjectGitHubDownloadRecordUi>,
    onOpenSystemDownloads: () -> Unit,
    onOpenDownloadSource: (ProjectGitHubDownloadRecordUi) -> Unit,
    selectedTab: ProjectGitHubWorkspaceRepoWorkbenchTab,
    onSelectTab: (ProjectGitHubWorkspaceRepoWorkbenchTab) -> Unit,
    onExitWorkbench: () -> Unit,
    backProgress: Float
) {
    val chromeColor = rememberReasonixChromeColor()
    val surfaceColor = rememberReasonixSurfaceColor()
    val mutedTextColor = rememberReasonixMutedTextColor()

    ReasonixSecondaryPageFrame(
        title = summary.repo.displayName.ifBlank { "仓库工作台" },
        subtitle = "先承接仓库级概览、工作流与远端仓库，后续继续迁移 Issue / PR / Release。"
    ) {
        Column(
            modifier = Modifier.graphicsLayer {
                translationX = 180f * backProgress
                alpha = 1f - (backProgress * 0.08f)
            },
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ProjectGitHubWorkspaceRepoWorkbenchHeader(
                header = buildProjectGitHubWorkspaceWorkbenchHeader(
                    summary = summary,
                    remoteSummary = remoteSummary,
                    gitState = gitState
                ),
                isGitLoading = isGitLoading,
                isGitHubLoading = isGitHubLoading,
                isGitHubActionRunning = isGitHubActionRunning,
                tokenConfigured = tokenConfigured,
                onExitWorkbench = onExitWorkbench,
                onRefreshGitState = onRefreshGitState,
                onRefreshGitHubActions = onRefreshGitHubActions
            )
            ProjectGitHubWorkspaceRepoWorkbenchTabs(
                selectedTab = selectedTab,
                onSelectTab = onSelectTab
            )
            when (selectedTab) {
                ProjectGitHubWorkspaceRepoWorkbenchTab.OVERVIEW -> {
                    ProjectGitHubWorkspaceRepoWorkbenchOverviewTab(
                        overview = buildProjectGitHubWorkspaceWorkbenchOverview(
                            remoteSummary = remoteSummary,
                            gitState = gitState,
                            tokenConfigured = tokenConfigured
                        ),
                        downloads = downloads,
                        onShowWorkflowTab = {
                            onSelectTab(ProjectGitHubWorkspaceRepoWorkbenchTab.WORKFLOW)
                        },
                        onShowIssuesTab = {
                            onSelectTab(ProjectGitHubWorkspaceRepoWorkbenchTab.ISSUES)
                        },
                        onOpenLatestRunDetail = remoteSummary?.latestRun?.let { run ->
                            { onOpenRunDetail(run) }
                        },
                        onOpenSystemDownloads = onOpenSystemDownloads,
                        onOpenDownloadSource = onOpenDownloadSource
                    )
                }
                ProjectGitHubWorkspaceRepoWorkbenchTab.WORKFLOW -> {
                    ProjectGitHubActionsSection(
                        state = githubActionsState,
                        isLoading = isGitHubLoading,
                        isActionRunning = isGitHubActionRunning,
                        tokenConfigured = tokenConfigured,
                        onRefresh = onRefreshGitHubActions,
                        onOpenRepo = onOpenRepoPage,
                        onRunWorkflow = onRunWorkflow,
                        onOpenWorkflowPage = onOpenWorkflowPage,
                        onOpenArtifacts = onOpenArtifacts,
                        onOpenRunPage = onOpenRunPage,
                        onDownloadRunLogs = onDownloadRunLogs,
                        onOpenRunDetail = onOpenRunDetail
                    )
                }
                ProjectGitHubWorkspaceRepoWorkbenchTab.REMOTE -> {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ProjectGitRemoteSummaryCard(
                            remoteUrl = gitState.remoteUrl,
                            upstreamBranch = gitState.upstreamBranch,
                            aheadCount = gitState.aheadCount,
                            behindCount = gitState.behindCount,
                            remoteBranchCount = gitState.remoteBranches.size,
                            tokenConfigured = tokenConfigured
                        )
                        ProjectGitHubRemoteRepositorySection(
                            state = remoteRepoState,
                            isLoading = isRemoteRepoLoading,
                            isActionRunning = isGitHubActionRunning,
                            tokenConfigured = tokenConfigured,
                            refDraft = remoteRepoRefDraft,
                            onRefDraftChange = onRemoteRepoRefDraftChange,
                            onRefresh = onRefreshRemoteRepository,
                            onApplyRef = onApplyRemoteRef,
                            onOpenRepo = onOpenRepoPage,
                            onOpenParent = onOpenRemoteParent,
                            onOpenRoot = onOpenRemoteRoot,
                            onOpenEntry = onOpenRemoteEntry,
                            onOpenEntryPage = onOpenRemoteEntryPage
                        )
                    }
                }
                ProjectGitHubWorkspaceRepoWorkbenchTab.ISSUES -> {
                    ProjectGitHubIssueSection(
                        issues = githubActionsState.issues,
                        isActionRunning = isGitHubActionRunning,
                        onCreateIssue = onCreateIssue,
                        onOpenDetail = onOpenIssueDetail,
                        onToggleIssueState = onToggleIssueState,
                        onOpenIssuePage = onOpenIssuePage
                    )
                }
                ProjectGitHubWorkspaceRepoWorkbenchTab.PULL_REQUESTS -> {
                    ProjectGitHubPullRequestSection(
                        pullRequests = githubActionsState.pullRequests,
                        isActionRunning = isGitHubActionRunning,
                        onCreatePullRequest = onCreatePullRequest,
                        onOpenDetail = onOpenPullRequestDetail,
                        onTogglePullRequestState = onTogglePullRequestState,
                        onMergePullRequest = onMergePullRequest,
                        onOpenPullRequestPage = onOpenPullRequestPage
                    )
                }
                ProjectGitHubWorkspaceRepoWorkbenchTab.RELEASES -> {
                    ProjectGitHubReleaseSection(
                        releases = githubActionsState.releases,
                        isActionRunning = isGitHubActionRunning,
                        onCreateRelease = onCreateRelease,
                        onEditRelease = onEditRelease,
                        onToggleReleaseMode = onToggleReleaseMode,
                        onTogglePrerelease = onTogglePrerelease,
                        onDeleteRelease = onDeleteRelease,
                        onOpenReleasePage = onOpenReleasePage,
                        onOpenAssets = onOpenReleaseAssets
                    )
                }
            }
        }
    }
}

private fun buildProjectGitHubWorkspaceWorkbenchHeader(
    summary: ProjectGitHubWorkspaceRepoSummaryUi,
    remoteSummary: ProjectGitHubWorkspaceRemoteSummaryUi?,
    gitState: ProjectGitStatusUi
): ProjectGitHubWorkspaceWorkbenchHeaderUi {
    return ProjectGitHubWorkspaceWorkbenchHeaderUi(
        title = "仓库工作台",
        subtitle = buildString {
            append(
                if (summary.repo.isWorkspaceRoot) {
                    "工作区根目录"
                } else {
                    summary.repo.relativePath
                }
            )
            append(" · ")
            append(if (gitState.branchSummary.isNotBlank()) gitState.branchSummary else "分支未知")
            remoteSummary?.repo?.let { repo ->
                append(" · ")
                append(repo.owner)
                append("/")
                append(repo.repo)
            }
        },
        changeSummary = "暂存 ${gitState.stagedFiles.size} · 修改 ${gitState.modifiedFiles.size} · 未跟踪 ${gitState.untrackedFiles.size} · 冲突 ${gitState.conflictedFiles.size}",
        highlightChanges = gitState.hasWorkingTreeChanges || gitState.conflictedFiles.isNotEmpty()
    )
}

private fun buildProjectGitHubWorkspaceWorkbenchOverview(
    remoteSummary: ProjectGitHubWorkspaceRemoteSummaryUi?,
    gitState: ProjectGitStatusUi,
    tokenConfigured: Boolean
): ProjectGitHubWorkspaceWorkbenchOverviewUi {
    return ProjectGitHubWorkspaceWorkbenchOverviewUi(
        remoteSummaryText = remoteSummary?.let {
            buildString {
                append("默认分支 ${it.defaultBranch ?: "未知"}")
                append(" · 开放 Issue ${it.openIssueCount}")
                append(" · 开放 PR ${it.openPullRequestCount}")
                if (it.runningRunCount > 0) {
                    append(" · 运行中 ${it.runningRunCount}")
                }
            }
        } ?: "当前仓库还没有工作区级远端摘要，可先刷新 GitHub。",
        remoteErrorMessage = remoteSummary?.errorMessage,
        latestWorkflow = remoteSummary?.latestRun?.let { run ->
            ProjectGitHubWorkspaceWorkbenchLatestWorkflowUi(
                title = run.displayTitle.ifBlank { run.name.ifBlank { "运行 #${run.runNumber}" } },
                detail = "${run.statusLabel} · ${run.headBranch.ifBlank { "分支未知" }} · ${run.updatedAt}",
                hasIssue = remoteSummary.latestRunHasIssue
            )
        },
        remoteUrl = gitState.remoteUrl,
        upstreamBranch = gitState.upstreamBranch,
        aheadCount = gitState.aheadCount,
        behindCount = gitState.behindCount,
        remoteBranchCount = gitState.remoteBranches.size,
        tokenConfigured = tokenConfigured
    )
}
