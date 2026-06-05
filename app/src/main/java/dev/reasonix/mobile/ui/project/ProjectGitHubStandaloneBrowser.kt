package dev.reasonix.mobile.ui.project

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.reasonix.mobile.ui.ReasonixSecondaryPageFrame
import dev.reasonix.mobile.ui.rememberReasonixChromeColor
import dev.reasonix.mobile.ui.rememberReasonixMutedTextColor
import dev.reasonix.mobile.ui.rememberReasonixSurfaceColor

internal enum class ProjectGitHubStandaloneSection(val label: String) {
    OVERVIEW("项目"),
    WORKFLOWS("工作流"),
    ISSUES("Issue"),
    PULL_REQUESTS("PR")
}

@Composable
internal fun ProjectGitHubStandaloneBrowserSection(
    tokenConfigured: Boolean,
    repoListState: ProjectGitHubViewerRepositoriesState,
    isRepoListLoading: Boolean,
    selectedRepo: ProjectGitHubAccountRepoUi?,
    selectedTaskRepo: ProjectGitHubRepoRef?,
    activeSection: ProjectGitHubStandaloneSection,
    repoDetailState: ProjectGitHubActionsState,
    isRepoDetailLoading: Boolean,
    remoteRepoState: ProjectGitHubRemoteBrowserState,
    isRemoteRepoLoading: Boolean,
    isActionRunning: Boolean,
    remoteRepoRefDraft: String,
    readme: ProjectGitHubReadmeUi?,
    readmeErrorMessage: String?,
    isReadmeLoading: Boolean,
    onRefreshRepoList: () -> Unit,
    onSelectRepo: (ProjectGitHubAccountRepoUi) -> Unit,
    onShowRepoMenu: (ProjectGitHubAccountRepoUi) -> Unit,
    onEditRepoDescription: (ProjectGitHubAccountRepoUi) -> Unit,
    onBackToRepoList: () -> Unit,
    onChangeSection: (ProjectGitHubStandaloneSection) -> Unit,
    onRefreshRepoDetail: () -> Unit,
    onOpenRepoPage: () -> Unit,
    onRemoteRepoRefDraftChange: (String) -> Unit,
    onRefreshRemoteRepository: () -> Unit,
    onApplyRemoteRef: () -> Unit,
    onOpenRemoteParent: () -> Unit,
    onOpenRemoteRoot: () -> Unit,
    onOpenRemoteEntry: (ProjectGitHubRemoteEntryUi) -> Unit,
    onOpenRemoteEntryPage: (ProjectGitHubRemoteEntryUi) -> Unit,
    onRunWorkflow: (ProjectGitHubWorkflowUi) -> Unit,
    onOpenWorkflowPage: (ProjectGitHubWorkflowUi) -> Unit,
    onOpenArtifacts: (ProjectGitHubWorkflowRunUi) -> Unit,
    onOpenRunPage: (ProjectGitHubWorkflowRunUi) -> Unit,
    onDownloadRunLogs: (ProjectGitHubWorkflowRunUi) -> Unit,
    onOpenRunDetail: (ProjectGitHubWorkflowRunUi) -> Unit,
    onCreateRelease: () -> Unit,
    onEditRelease: (ProjectGitHubReleaseUi) -> Unit,
    onToggleReleaseMode: (ProjectGitHubReleaseUi, Boolean) -> Unit,
    onTogglePrerelease: (ProjectGitHubReleaseUi, Boolean) -> Unit,
    onDeleteRelease: (ProjectGitHubReleaseUi) -> Unit,
    onOpenReleasePage: (ProjectGitHubReleaseUi) -> Unit,
    onOpenReleaseAssets: (ProjectGitHubReleaseUi) -> Unit,
    onCreateIssue: () -> Unit,
    onOpenIssueDetail: (ProjectGitHubIssueUi) -> Unit,
    onToggleIssueState: (ProjectGitHubIssueUi, Boolean) -> Unit,
    onOpenIssuePage: (ProjectGitHubIssueUi) -> Unit,
    onCreatePullRequest: () -> Unit,
    onOpenPullRequestDetail: (ProjectGitHubPullRequestUi) -> Unit,
    onTogglePullRequestState: (ProjectGitHubPullRequestUi, Boolean) -> Unit,
    onMergePullRequest: (ProjectGitHubPullRequestUi) -> Unit,
    onOpenPullRequestPage: (ProjectGitHubPullRequestUi) -> Unit,
    onEditReadme: (ProjectGitHubReadmeUi) -> Unit,
    backProgress: Float
) {
    val chromeColor = rememberReasonixChromeColor()
    val surfaceColor = rememberReasonixSurfaceColor()
    val mutedTextColor = rememberReasonixMutedTextColor()

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ProjectSectionCard(
            shape = RoundedCornerShape(14.dp),
            surfaceColorOverride = chromeColor.copy(alpha = 0.38f)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("GitHub 账号仓库", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = buildString {
                        append(repoListState.viewerLogin?.let { "@$it" } ?: "未识别账号")
                        repoListState.viewerName?.takeIf { it.isNotBlank() }?.let {
                            append(" · ")
                            append(it)
                        }
                        if (repoListState.repositories.isNotEmpty()) {
                            append(" · 仓库 ${repoListState.repositories.size}")
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = mutedTextColor
                )
                Text(
                    text = if (selectedRepo == null) {
                        "未选择本地项目时，直接显示当前账号下的仓库；点击进入二级页，长按可设为当前任务仓库。"
                    } else {
                        "当前查看 ${selectedRepo.fullName}，未被设为任务仓库的项目只允许浏览，修改前需确认。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = mutedTextColor
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onRefreshRepoList,
                        enabled = tokenConfigured && !isRepoListLoading
                    ) {
                        Text("刷新仓库")
                    }
                    if (selectedRepo != null) {
                        TextButton(onClick = onBackToRepoList) {
                            Text("返回列表")
                        }
                    }
                }
                if (!tokenConfigured) {
                    Text(
                        text = "请先在设置页填写 GitHub Token，未选择本地项目时才能直接浏览账号仓库。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                repoListState.errorMessage?.takeIf { it.isNotBlank() }?.let { error ->
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        if (selectedRepo == null) {
            if (isRepoListLoading) {
                ProjectGitHubStandaloneLoadingCard("正在读取当前账号下的仓库列表...")
            }
            if (repoListState.repositories.isEmpty() && !isRepoListLoading && tokenConfigured) {
                ProjectSectionCard(
                    shape = RoundedCornerShape(14.dp),
                    surfaceColorOverride = surfaceColor.copy(alpha = 0.55f)
                ) {
                    Text(
                        text = "当前账号下还没有读取到仓库，或该 Token 没有仓库读取权限。",
                        style = MaterialTheme.typography.bodySmall,
                        color = mutedTextColor
                    )
                }
            }
            repoListState.repositories.forEach { repo ->
                ProjectGitHubStandaloneRepoRow(
                    repo = repo,
                    isTaskRepo = selectedTaskRepo == repo.repoRef,
                    onClick = { onSelectRepo(repo) },
                    onLongClick = { onShowRepoMenu(repo) }
                )
            }
            return
        }

        when (activeSection) {
            ProjectGitHubStandaloneSection.OVERVIEW -> {
                ProjectGitHubStandaloneOverviewPage(
                    selectedRepo = selectedRepo,
                    selectedTaskRepo = selectedTaskRepo,
                    tokenConfigured = tokenConfigured,
                    repoDetailState = repoDetailState,
                    isRepoDetailLoading = isRepoDetailLoading,
                    remoteRepoState = remoteRepoState,
                    isRemoteRepoLoading = isRemoteRepoLoading,
                    isActionRunning = isActionRunning,
                    remoteRepoRefDraft = remoteRepoRefDraft,
                    onEditRepoDescription = { onEditRepoDescription(selectedRepo) },
                    onBackToRepoList = onBackToRepoList,
                    onRefreshRepoDetail = onRefreshRepoDetail,
                    onOpenRepoPage = onOpenRepoPage,
                    onRemoteRepoRefDraftChange = onRemoteRepoRefDraftChange,
                    onRefreshRemoteRepository = onRefreshRemoteRepository,
                    onApplyRemoteRef = onApplyRemoteRef,
                    onOpenRemoteParent = onOpenRemoteParent,
                    onOpenRemoteRoot = onOpenRemoteRoot,
                    onOpenRemoteEntry = onOpenRemoteEntry,
                    onOpenRemoteEntryPage = onOpenRemoteEntryPage,
                    onShowWorkflows = {
                        onChangeSection(ProjectGitHubStandaloneSection.WORKFLOWS)
                    },
                    onShowIssues = {
                        onChangeSection(ProjectGitHubStandaloneSection.ISSUES)
                    },
                    onShowPullRequests = {
                        onChangeSection(ProjectGitHubStandaloneSection.PULL_REQUESTS)
                    },
                    onOpenLatestRunDetail = repoDetailState.recentRuns.firstOrNull()?.let { run ->
                        { onOpenRunDetail(run) }
                    },
                    releases = repoDetailState.releases,
                    readme = readme,
                    isReadmeLoading = isReadmeLoading,
                    readmeErrorMessage = readmeErrorMessage,
                    onEditReadme = onEditReadme,
                    onCreateRelease = onCreateRelease,
                    onEditRelease = onEditRelease,
                    onToggleReleaseMode = onToggleReleaseMode,
                    onTogglePrerelease = onTogglePrerelease,
                    onDeleteRelease = onDeleteRelease,
                    onOpenReleasePage = onOpenReleasePage,
                    onOpenReleaseAssets = onOpenReleaseAssets,
                    backProgress = backProgress
                )
            }

            ProjectGitHubStandaloneSection.WORKFLOWS -> {
                ProjectGitHubStandaloneWorkflowPage(
                    selectedRepo = selectedRepo,
                    state = repoDetailState,
                    isLoading = isRepoDetailLoading,
                    tokenConfigured = tokenConfigured,
                    onRefreshRepoDetail = onRefreshRepoDetail,
                    onOpenRepoPage = onOpenRepoPage,
                    onRunWorkflow = onRunWorkflow,
                    onOpenWorkflowPage = onOpenWorkflowPage,
                    onOpenArtifacts = onOpenArtifacts,
                    onOpenRunPage = onOpenRunPage,
                    onDownloadRunLogs = onDownloadRunLogs,
                    onOpenRunDetail = onOpenRunDetail,
                    backProgress = backProgress
                )
            }

            ProjectGitHubStandaloneSection.ISSUES -> {
                ProjectGitHubStandaloneIssuePage(
                    selectedRepo = selectedRepo,
                    issues = repoDetailState.issues,
                    isActionRunning = isActionRunning,
                    onCreateIssue = onCreateIssue,
                    onOpenIssueDetail = onOpenIssueDetail,
                    onToggleIssueState = onToggleIssueState,
                    onOpenIssuePage = onOpenIssuePage,
                    backProgress = backProgress
                )
            }

            ProjectGitHubStandaloneSection.PULL_REQUESTS -> {
                ProjectGitHubStandalonePullRequestPage(
                    selectedRepo = selectedRepo,
                    pullRequests = repoDetailState.pullRequests,
                    isActionRunning = isActionRunning,
                    onCreatePullRequest = onCreatePullRequest,
                    onOpenPullRequestDetail = onOpenPullRequestDetail,
                    onTogglePullRequestState = onTogglePullRequestState,
                    onMergePullRequest = onMergePullRequest,
                    onOpenPullRequestPage = onOpenPullRequestPage,
                    backProgress = backProgress
                )
            }

        }
    }
}

@Composable
private fun ProjectGitHubStandaloneOverviewPage(
    selectedRepo: ProjectGitHubAccountRepoUi,
    selectedTaskRepo: ProjectGitHubRepoRef?,
    tokenConfigured: Boolean,
    repoDetailState: ProjectGitHubActionsState,
    isRepoDetailLoading: Boolean,
    remoteRepoState: ProjectGitHubRemoteBrowserState,
    isRemoteRepoLoading: Boolean,
    isActionRunning: Boolean,
    remoteRepoRefDraft: String,
    onEditRepoDescription: () -> Unit,
    onBackToRepoList: () -> Unit,
    onRefreshRepoDetail: () -> Unit,
    onOpenRepoPage: () -> Unit,
    onRemoteRepoRefDraftChange: (String) -> Unit,
    onRefreshRemoteRepository: () -> Unit,
    onApplyRemoteRef: () -> Unit,
    onOpenRemoteParent: () -> Unit,
    onOpenRemoteRoot: () -> Unit,
    onOpenRemoteEntry: (ProjectGitHubRemoteEntryUi) -> Unit,
    onOpenRemoteEntryPage: (ProjectGitHubRemoteEntryUi) -> Unit,
    onShowWorkflows: () -> Unit,
    onShowIssues: () -> Unit,
    onShowPullRequests: () -> Unit,
    onOpenLatestRunDetail: (() -> Unit)?,
    releases: List<ProjectGitHubReleaseUi>,
    readme: ProjectGitHubReadmeUi?,
    isReadmeLoading: Boolean,
    readmeErrorMessage: String?,
    onEditReadme: (ProjectGitHubReadmeUi) -> Unit,
    onCreateRelease: () -> Unit,
    onEditRelease: (ProjectGitHubReleaseUi) -> Unit,
    onToggleReleaseMode: (ProjectGitHubReleaseUi, Boolean) -> Unit,
    onTogglePrerelease: (ProjectGitHubReleaseUi, Boolean) -> Unit,
    onDeleteRelease: (ProjectGitHubReleaseUi) -> Unit,
    onOpenReleasePage: (ProjectGitHubReleaseUi) -> Unit,
    onOpenReleaseAssets: (ProjectGitHubReleaseUi) -> Unit,
    backProgress: Float
) {
    ReasonixSecondaryPageFrame(
        title = selectedRepo.fullName,
        subtitle = "仓库详情页默认展示固定信息、Release 与远端仓库内容。"
    ) {
        Column(
            modifier = Modifier.graphicsLayer {
                translationX = 180f * backProgress
                alpha = 1f - (backProgress * 0.08f)
            },
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ProjectGitHubStandaloneHeaderCard(
                selectedRepo = selectedRepo,
                selectedTaskRepo = selectedTaskRepo,
                onEditRepoDescription = onEditRepoDescription
            )
            ProjectGitHubStandaloneTopActions(
                onShowWorkflows = onShowWorkflows,
                onShowIssues = onShowIssues,
                onShowPullRequests = onShowPullRequests
            )
            ProjectGitHubStandaloneOverviewSection(
                tokenConfigured = tokenConfigured,
                selectedRepo = selectedRepo,
                repoDetailState = repoDetailState,
                isRepoDetailLoading = isRepoDetailLoading,
                remoteRepoState = remoteRepoState,
                isRemoteRepoLoading = isRemoteRepoLoading,
                isActionRunning = isActionRunning,
                remoteRepoRefDraft = remoteRepoRefDraft,
                onRefreshRepoDetail = onRefreshRepoDetail,
                onOpenRepoPage = onOpenRepoPage,
                onRemoteRepoRefDraftChange = onRemoteRepoRefDraftChange,
                onRefreshRemoteRepository = onRefreshRemoteRepository,
                onApplyRemoteRef = onApplyRemoteRef,
                onOpenRemoteParent = onOpenRemoteParent,
                onOpenRemoteRoot = onOpenRemoteRoot,
                onOpenRemoteEntry = onOpenRemoteEntry,
                onOpenRemoteEntryPage = onOpenRemoteEntryPage,
                onShowWorkflows = onShowWorkflows,
                onShowIssues = onShowIssues,
                onShowPullRequests = onShowPullRequests,
                onOpenLatestRunDetail = onOpenLatestRunDetail,
                releases = releases,
                readme = readme,
                isReadmeLoading = isReadmeLoading,
                readmeErrorMessage = readmeErrorMessage,
                onEditReadme = onEditReadme,
                onCreateRelease = onCreateRelease,
                onEditRelease = onEditRelease,
                onToggleReleaseMode = onToggleReleaseMode,
                onTogglePrerelease = onTogglePrerelease,
                onDeleteRelease = onDeleteRelease,
                onOpenReleasePage = onOpenReleasePage,
                onOpenReleaseAssets = onOpenReleaseAssets
            )
        }
    }
}

@Composable
private fun ProjectGitHubStandaloneWorkflowPage(
    selectedRepo: ProjectGitHubAccountRepoUi,
    state: ProjectGitHubActionsState,
    isLoading: Boolean,
    tokenConfigured: Boolean,
    onRefreshRepoDetail: () -> Unit,
    onOpenRepoPage: () -> Unit,
    onRunWorkflow: (ProjectGitHubWorkflowUi) -> Unit,
    onOpenWorkflowPage: (ProjectGitHubWorkflowUi) -> Unit,
    onOpenArtifacts: (ProjectGitHubWorkflowRunUi) -> Unit,
    onOpenRunPage: (ProjectGitHubWorkflowRunUi) -> Unit,
    onDownloadRunLogs: (ProjectGitHubWorkflowRunUi) -> Unit,
    onOpenRunDetail: (ProjectGitHubWorkflowRunUi) -> Unit,
    backProgress: Float
) {
    ReasonixSecondaryPageFrame(
        title = "${selectedRepo.name} · 工作流",
        subtitle = "独立三级页，只显示工作流与运行记录。"
    ) {
        Column(
            modifier = Modifier.graphicsLayer {
                translationX = 180f * backProgress
                alpha = 1f - (backProgress * 0.08f)
            },
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ProjectGitHubActionsSection(
                state = state,
                isLoading = isLoading,
                isActionRunning = false,
                tokenConfigured = tokenConfigured,
                onRefresh = onRefreshRepoDetail,
                onOpenRepo = onOpenRepoPage,
                onRunWorkflow = onRunWorkflow,
                onOpenWorkflowPage = onOpenWorkflowPage,
                onOpenArtifacts = onOpenArtifacts,
                onOpenRunPage = onOpenRunPage,
                onDownloadRunLogs = onDownloadRunLogs,
                onOpenRunDetail = onOpenRunDetail
            )
        }
    }
}

@Composable
private fun ProjectGitHubStandaloneIssuePage(
    selectedRepo: ProjectGitHubAccountRepoUi,
    issues: List<ProjectGitHubIssueUi>,
    isActionRunning: Boolean,
    onCreateIssue: () -> Unit,
    onOpenIssueDetail: (ProjectGitHubIssueUi) -> Unit,
    onToggleIssueState: (ProjectGitHubIssueUi, Boolean) -> Unit,
    onOpenIssuePage: (ProjectGitHubIssueUi) -> Unit,
    backProgress: Float
) {
    ReasonixSecondaryPageFrame(
        title = "${selectedRepo.name} · Issue",
        subtitle = "独立三级页，只显示当前仓库的 Issue。"
    ) {
        Column(
            modifier = Modifier.graphicsLayer {
                translationX = 180f * backProgress
                alpha = 1f - (backProgress * 0.08f)
            },
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ProjectGitHubIssueSection(
                issues = issues,
                isActionRunning = isActionRunning,
                onCreateIssue = onCreateIssue,
                onOpenDetail = onOpenIssueDetail,
                onToggleIssueState = onToggleIssueState,
                onOpenIssuePage = onOpenIssuePage
            )
        }
    }
}

@Composable
private fun ProjectGitHubStandalonePullRequestPage(
    selectedRepo: ProjectGitHubAccountRepoUi,
    pullRequests: List<ProjectGitHubPullRequestUi>,
    isActionRunning: Boolean,
    onCreatePullRequest: () -> Unit,
    onOpenPullRequestDetail: (ProjectGitHubPullRequestUi) -> Unit,
    onTogglePullRequestState: (ProjectGitHubPullRequestUi, Boolean) -> Unit,
    onMergePullRequest: (ProjectGitHubPullRequestUi) -> Unit,
    onOpenPullRequestPage: (ProjectGitHubPullRequestUi) -> Unit,
    backProgress: Float
) {
    ReasonixSecondaryPageFrame(
        title = "${selectedRepo.name} · PR",
        subtitle = "独立三级页，只显示当前仓库的 Pull Request。"
    ) {
        Column(
            modifier = Modifier.graphicsLayer {
                translationX = 180f * backProgress
                alpha = 1f - (backProgress * 0.08f)
            },
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ProjectGitHubPullRequestSection(
                pullRequests = pullRequests,
                isActionRunning = isActionRunning,
                onCreatePullRequest = onCreatePullRequest,
                onOpenDetail = onOpenPullRequestDetail,
                onTogglePullRequestState = onTogglePullRequestState,
                onMergePullRequest = onMergePullRequest,
                onOpenPullRequestPage = onOpenPullRequestPage
            )
        }
    }
}

@Composable
private fun ProjectGitHubStandaloneHeaderCard(
    selectedRepo: ProjectGitHubAccountRepoUi,
    selectedTaskRepo: ProjectGitHubRepoRef?,
    onEditRepoDescription: () -> Unit
) {
    val surfaceColor = rememberReasonixSurfaceColor()
    val mutedTextColor = rememberReasonixMutedTextColor()
    ProjectSectionCard(
        shape = RoundedCornerShape(14.dp),
        surfaceColorOverride = surfaceColor.copy(alpha = 0.58f)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(selectedRepo.fullName, style = MaterialTheme.typography.titleSmall)
            ProjectInsetCard(
                shape = RoundedCornerShape(12.dp),
                surfaceColorOverride = surfaceColor.copy(alpha = 0.42f),
                modifier = Modifier.combinedClickable(
                    onClick = {},
                    onLongClick = onEditRepoDescription
                )
            ) {
                Text(
                    text = selectedRepo.description.ifBlank { "这个仓库暂时还没有简介。长按这里可编辑。" },
                    style = MaterialTheme.typography.bodySmall,
                    color = mutedTextColor
                )
            }
            Text(
                text = "${selectedRepo.visibilityLabel} · ${selectedRepo.stargazerCount} 星标 · ${selectedRepo.forkCount} 复刻",
                style = MaterialTheme.typography.bodySmall,
                color = mutedTextColor
            )
            Text(
                text = if (selectedTaskRepo == selectedRepo.repoRef) {
                    "当前任务仓库，可直接编辑源码。"
                } else {
                    "当前不是任务仓库，可浏览；若要修改该仓库，后续需要用户确认。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (selectedTaskRepo == selectedRepo.repoRef) {
                    MaterialTheme.colorScheme.primary
                } else {
                    mutedTextColor
                }
            )
        }
    }
}

@Composable
private fun ProjectGitHubStandaloneOverviewSection(
    tokenConfigured: Boolean,
    selectedRepo: ProjectGitHubAccountRepoUi,
    repoDetailState: ProjectGitHubActionsState,
    isRepoDetailLoading: Boolean,
    remoteRepoState: ProjectGitHubRemoteBrowserState,
    isRemoteRepoLoading: Boolean,
    isActionRunning: Boolean,
    remoteRepoRefDraft: String,
    onRefreshRepoDetail: () -> Unit,
    onOpenRepoPage: () -> Unit,
    onRemoteRepoRefDraftChange: (String) -> Unit,
    onRefreshRemoteRepository: () -> Unit,
    onApplyRemoteRef: () -> Unit,
    onOpenRemoteParent: () -> Unit,
    onOpenRemoteRoot: () -> Unit,
    onOpenRemoteEntry: (ProjectGitHubRemoteEntryUi) -> Unit,
    onOpenRemoteEntryPage: (ProjectGitHubRemoteEntryUi) -> Unit,
    onShowWorkflows: () -> Unit,
    onShowIssues: () -> Unit,
    onShowPullRequests: () -> Unit,
    onOpenLatestRunDetail: (() -> Unit)?,
    releases: List<ProjectGitHubReleaseUi>,
    readme: ProjectGitHubReadmeUi?,
    isReadmeLoading: Boolean,
    readmeErrorMessage: String?,
    onEditReadme: (ProjectGitHubReadmeUi) -> Unit,
    onCreateRelease: () -> Unit,
    onEditRelease: (ProjectGitHubReleaseUi) -> Unit,
    onToggleReleaseMode: (ProjectGitHubReleaseUi, Boolean) -> Unit,
    onTogglePrerelease: (ProjectGitHubReleaseUi, Boolean) -> Unit,
    onDeleteRelease: (ProjectGitHubReleaseUi) -> Unit,
    onOpenReleasePage: (ProjectGitHubReleaseUi) -> Unit,
    onOpenReleaseAssets: (ProjectGitHubReleaseUi) -> Unit
) {
    val chromeColor = rememberReasonixChromeColor()
    val mutedTextColor = rememberReasonixMutedTextColor()
    val latestRun = remember(repoDetailState.recentRuns) { repoDetailState.recentRuns.firstOrNull() }
    val latestIssue = remember(repoDetailState.issues) { repoDetailState.issues.firstOrNull() }
    val latestPullRequest = remember(repoDetailState.pullRequests) {
        repoDetailState.pullRequests.firstOrNull()
    }
    ProjectSectionCard(
        shape = RoundedCornerShape(14.dp),
        surfaceColorOverride = chromeColor.copy(alpha = 0.28f)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("项目概览", style = MaterialTheme.typography.titleSmall)
            Text(
                text = "这里默认展示仓库固定信息、Release、README 和远端仓库内容。当前任务仓库会保留“可直接编辑”标记。",
                style = MaterialTheme.typography.bodySmall,
                color = mutedTextColor
            )
            OutlinedButton(
                onClick = onRefreshRepoDetail,
                enabled = tokenConfigured && !isRepoDetailLoading
            ) {
                Text("刷新详情")
            }
            Text(
                text = "默认分支: ${
                    repoDetailState.defaultBranch
                        .takeIf { !it.isNullOrBlank() }
                        ?: selectedRepo.defaultBranch.ifBlank { "未知" }
                }",
                style = MaterialTheme.typography.bodySmall,
                color = mutedTextColor
            )
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = true,
                    onClick = onShowWorkflows,
                    label = { Text("工作流 ${repoDetailState.workflows.size}") }
                )
                FilterChip(
                    selected = true,
                    onClick = onShowWorkflows,
                    label = { Text("运行 ${repoDetailState.recentRuns.size}") }
                )
                FilterChip(
                    selected = true,
                    onClick = {},
                    label = { Text("Release ${repoDetailState.releases.size}") }
                )
                FilterChip(
                    selected = true,
                    onClick = onShowIssues,
                    label = { Text("Issue ${repoDetailState.issues.size}") }
                )
                FilterChip(
                    selected = true,
                    onClick = onShowPullRequests,
                    label = { Text("PR ${repoDetailState.pullRequests.size}") }
                )
            }
            latestRun?.let { run ->
                ProjectInsetCard(
                    shape = RoundedCornerShape(12.dp),
                    surfaceColorOverride = chromeColor.copy(alpha = 0.40f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "最近工作流活动",
                            style = MaterialTheme.typography.labelMedium
                        )
                        Text(
                            text = run.displayTitle.ifBlank { run.name.ifBlank { "最近一次运行" } },
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "${run.statusLabel} · 分支 ${run.headBranch.ifBlank { "未知" }} · 更新 ${run.updatedAt}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (run.hasIssue) {
                                MaterialTheme.colorScheme.error
                            } else {
                                mutedTextColor
                            }
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = onShowWorkflows) {
                                Text("切到工作流")
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
            latestIssue?.let { issue ->
                ProjectInsetCard(
                    shape = RoundedCornerShape(12.dp),
                    surfaceColorOverride = chromeColor.copy(alpha = 0.32f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "最近 Issue",
                            style = MaterialTheme.typography.labelMedium
                        )
                        Text(
                            text = "#${issue.number} · ${issue.title}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "${issue.stateLabel} · ${issue.authorLabel} · 更新 ${issue.updatedAt}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (issue.isOpen) {
                                mutedTextColor
                            } else {
                                MaterialTheme.colorScheme.primary
                            }
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = onShowIssues) {
                                Text("切到 Issue")
                            }
                        }
                    }
                }
            }
            latestPullRequest?.let { pullRequest ->
                ProjectInsetCard(
                    shape = RoundedCornerShape(12.dp),
                    surfaceColorOverride = chromeColor.copy(alpha = 0.30f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "最近 PR",
                            style = MaterialTheme.typography.labelMedium
                        )
                        Text(
                            text = "#${pullRequest.number} · ${pullRequest.title}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = buildString {
                                append(pullRequest.stateLabel)
                                append(" · ")
                                append(pullRequest.headBranch)
                                append(" -> ")
                                append(pullRequest.baseBranch)
                                append(" · 更新 ")
                                append(pullRequest.updatedAt)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (pullRequest.canMerge) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                mutedTextColor
                            }
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = onShowPullRequests) {
                                Text("切到 PR")
                            }
                        }
                    }
                }
            }
            repoDetailState.errorMessage?.takeIf { it.isNotBlank() }?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
    ProjectGitHubStandaloneReleaseSection(
        releases = releases,
        isLoading = isRepoDetailLoading,
        isActionRunning = isActionRunning,
        onRefresh = onRefreshRepoDetail,
        onCreateRelease = onCreateRelease,
        onEditRelease = onEditRelease,
        onToggleReleaseMode = onToggleReleaseMode,
        onTogglePrerelease = onTogglePrerelease,
        onDeleteRelease = onDeleteRelease,
        onOpenReleasePage = onOpenReleasePage,
        onOpenReleaseAssets = onOpenReleaseAssets
    )
    ProjectSectionCard(
        shape = RoundedCornerShape(14.dp),
        surfaceColorOverride = rememberReasonixSurfaceColor().copy(alpha = 0.58f)
    ) {
        ProjectGitHubReadmeSection(
            readme = readme,
            isLoading = isReadmeLoading,
            errorMessage = readmeErrorMessage,
            onRefresh = onRefreshRepoDetail,
            onOpenRepoPage = onOpenRepoPage,
            onEditReadme = onEditReadme
        )
    }
    ProjectGitHubRemoteRepositorySection(
        state = remoteRepoState,
        isLoading = isRemoteRepoLoading,
        isActionRunning = isActionRunning,
        tokenConfigured = tokenConfigured,
        refDraft = remoteRepoRefDraft,
        onRefDraftChange = onRemoteRepoRefDraftChange,
        onRefresh = onRefreshRemoteRepository,
        onApplyRef = onApplyRemoteRef,
        onOpenRepo = onOpenRepoPage,
        onOpenParent = onOpenRemoteParent,
        onOpenRoot = onOpenRemoteRoot,
        onOpenPath = composeProjectGitHubRemoteDirectoryPathOpener(onOpenRemoteEntry),
        onOpenEntry = onOpenRemoteEntry,
        onOpenEntryPage = onOpenRemoteEntryPage
    )
}

@Composable
private fun ProjectGitHubStandaloneTopActions(
    onShowWorkflows: () -> Unit,
    onShowIssues: () -> Unit,
    onShowPullRequests: () -> Unit
) {
    val buttonRows = listOf(
        listOf(ProjectGitHubStandaloneSection.WORKFLOWS.label to onShowWorkflows, ProjectGitHubStandaloneSection.ISSUES.label to onShowIssues),
        listOf(ProjectGitHubStandaloneSection.PULL_REQUESTS.label to onShowPullRequests)
    )
    buttonRows.forEach { row ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            row.forEach { (label, onClick) ->
                ProjectGitHubStandaloneActionButton(
                    label = label,
                    selected = false,
                    modifier = Modifier.weight(1f),
                    onClick = onClick
                )
            }
            if (row.size == 1) {
                Row(modifier = Modifier.weight(1f)) {}
            }
        }
    }
}

@Composable
private fun ProjectGitHubStandaloneActionButton(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val chromeColor = rememberReasonixChromeColor()
    val surfaceColor = rememberReasonixSurfaceColor()
    ProjectInsetCard(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        surfaceColorOverride = if (selected) {
            chromeColor.copy(alpha = 0.62f)
        } else {
            surfaceColor.copy(alpha = 0.54f)
        }
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProjectGitHubStandaloneRepoRow(
    repo: ProjectGitHubAccountRepoUi,
    isTaskRepo: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val surfaceColor = rememberReasonixSurfaceColor()
    val mutedTextColor = rememberReasonixMutedTextColor()
    ProjectInsetCard(
        shape = RoundedCornerShape(12.dp),
        surfaceColorOverride = surfaceColor.copy(alpha = 0.56f),
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(repo.fullName, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = repo.description.ifBlank { "暂无仓库简介" },
                style = MaterialTheme.typography.bodySmall,
                color = mutedTextColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = buildString {
                    append(repo.visibilityLabel)
                    append(" · ")
                    append(repo.updatedAt.ifBlank { "更新时间未知" })
                    if (isTaskRepo) {
                        append(" · 可直接编辑")
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (isTaskRepo) MaterialTheme.colorScheme.primary else mutedTextColor
            )
        }
    }
}

@Composable
private fun ProjectGitHubStandaloneReleaseSection(
    releases: List<ProjectGitHubReleaseUi>,
    isLoading: Boolean,
    isActionRunning: Boolean,
    onRefresh: () -> Unit,
    onCreateRelease: () -> Unit,
    onEditRelease: (ProjectGitHubReleaseUi) -> Unit,
    onToggleReleaseMode: (ProjectGitHubReleaseUi, Boolean) -> Unit,
    onTogglePrerelease: (ProjectGitHubReleaseUi, Boolean) -> Unit,
    onDeleteRelease: (ProjectGitHubReleaseUi) -> Unit,
    onOpenReleasePage: (ProjectGitHubReleaseUi) -> Unit,
    onOpenReleaseAssets: (ProjectGitHubReleaseUi) -> Unit
) {
    ProjectSectionCard(
        shape = RoundedCornerShape(14.dp),
        surfaceColorOverride = rememberReasonixSurfaceColor().copy(alpha = 0.58f)
    ) {
        ProjectGitHubReleaseSection(
            releases = releases,
            isLoading = isLoading,
            isActionRunning = isActionRunning,
            onCreateRelease = onCreateRelease,
            onRefresh = onRefresh,
            onEditRelease = onEditRelease,
            onToggleReleaseMode = onToggleReleaseMode,
            onTogglePrerelease = onTogglePrerelease,
            onDeleteRelease = onDeleteRelease,
            onOpenReleasePage = onOpenReleasePage,
            onOpenAssets = onOpenReleaseAssets
        )
    }
}

@Composable
private fun ProjectGitHubStandaloneLoadingCard(message: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CircularProgressIndicator(strokeWidth = 2.dp)
        Text(message, style = MaterialTheme.typography.bodySmall)
    }
}
