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
    PULL_REQUESTS("PR"),
    README("README")
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

        ReasonixSecondaryPageFrame(
            title = when (activeSection) {
                ProjectGitHubStandaloneSection.OVERVIEW -> selectedRepo.fullName
                else -> "${selectedRepo.name} · ${activeSection.label}"
            },
            subtitle = when (activeSection) {
                ProjectGitHubStandaloneSection.OVERVIEW -> "仓库详情页默认展示固定信息与 Release，工作流 / 协作 / README 进入三级页。"
                ProjectGitHubStandaloneSection.WORKFLOWS -> "工作流三级页"
                ProjectGitHubStandaloneSection.ISSUES -> "Issue 三级页"
                ProjectGitHubStandaloneSection.PULL_REQUESTS -> "PR 三级页"
                ProjectGitHubStandaloneSection.README -> "README 三级页"
            }
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
                    onEditRepoDescription = { onEditRepoDescription(selectedRepo) }
                )
                ProjectGitHubStandaloneTopActions(
                    selectedRepo = selectedRepo,
                    activeSection = activeSection,
                    onBack = {
                        if (activeSection == ProjectGitHubStandaloneSection.OVERVIEW) {
                            onBackToRepoList()
                        } else {
                            onChangeSection(ProjectGitHubStandaloneSection.OVERVIEW)
                        }
                    },
                    onChangeSection = onChangeSection
                )
                when (activeSection) {
                    ProjectGitHubStandaloneSection.OVERVIEW -> {
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
                            onShowWorkflows = {
                                onChangeSection(ProjectGitHubStandaloneSection.WORKFLOWS)
                            },
                            onShowIssues = {
                                onChangeSection(ProjectGitHubStandaloneSection.ISSUES)
                            },
                            onShowPullRequests = {
                                onChangeSection(ProjectGitHubStandaloneSection.PULL_REQUESTS)
                            },
                            onShowReadme = {
                                onChangeSection(ProjectGitHubStandaloneSection.README)
                            },
                            onOpenLatestRunDetail = repoDetailState.recentRuns.firstOrNull()?.let { run ->
                                { onOpenRunDetail(run) }
                            },
                            releases = repoDetailState.releases,
                            onCreateRelease = onCreateRelease,
                            onEditRelease = onEditRelease,
                            onToggleReleaseMode = onToggleReleaseMode,
                            onTogglePrerelease = onTogglePrerelease,
                            onDeleteRelease = onDeleteRelease,
                            onOpenReleasePage = onOpenReleasePage,
                            onOpenReleaseAssets = onOpenReleaseAssets
                        )
                    }

                    ProjectGitHubStandaloneSection.WORKFLOWS -> {
                        ProjectGitHubActionsSection(
                            state = repoDetailState,
                            isLoading = isRepoDetailLoading,
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

                    ProjectGitHubStandaloneSection.ISSUES -> {
                        ProjectGitHubIssueSection(
                            issues = repoDetailState.issues,
                            isActionRunning = isActionRunning,
                            onCreateIssue = onCreateIssue,
                            onOpenDetail = onOpenIssueDetail,
                            onToggleIssueState = onToggleIssueState,
                            onOpenIssuePage = onOpenIssuePage
                        )
                    }

                    ProjectGitHubStandaloneSection.PULL_REQUESTS -> {
                        ProjectGitHubPullRequestSection(
                            pullRequests = repoDetailState.pullRequests,
                            isActionRunning = isActionRunning,
                            onCreatePullRequest = onCreatePullRequest,
                            onOpenDetail = onOpenPullRequestDetail,
                            onTogglePullRequestState = onTogglePullRequestState,
                            onMergePullRequest = onMergePullRequest,
                            onOpenPullRequestPage = onOpenPullRequestPage
                        )
                    }

                    ProjectGitHubStandaloneSection.README -> {
                        ProjectGitHubReadmeSection(
                            readme = readme,
                            isLoading = isReadmeLoading,
                            errorMessage = readmeErrorMessage,
                            onRefresh = onRefreshRepoDetail,
                            onOpenRepoPage = onOpenRepoPage,
                            onEditReadme = onEditReadme
                        )
                    }
                }
            }
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
    onShowReadme: () -> Unit,
    onOpenLatestRunDetail: (() -> Unit)?,
    releases: List<ProjectGitHubReleaseUi>,
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
                text = "这里先提供远端仓库树浏览、网页入口和直接编辑链路。当前任务仓库会保留“可直接编辑”标记。",
                style = MaterialTheme.typography.bodySmall,
                color = mutedTextColor
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onRefreshRepoDetail,
                    enabled = tokenConfigured && !isRepoDetailLoading
                ) {
                    Text("刷新详情")
                }
                TextButton(onClick = onOpenRepoPage) {
                    Text("打开网页")
                }
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
                    onClick = onShowReadme,
                    label = { Text("README") }
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
    selectedRepo: ProjectGitHubAccountRepoUi,
    activeSection: ProjectGitHubStandaloneSection,
    onBack: () -> Unit,
    onChangeSection: (ProjectGitHubStandaloneSection) -> Unit
) {
    val buttonRows = listOf(
        listOf("返回" to null, ProjectGitHubStandaloneSection.OVERVIEW.label to ProjectGitHubStandaloneSection.OVERVIEW),
        listOf(ProjectGitHubStandaloneSection.WORKFLOWS.label to ProjectGitHubStandaloneSection.WORKFLOWS, ProjectGitHubStandaloneSection.ISSUES.label to ProjectGitHubStandaloneSection.ISSUES),
        listOf(ProjectGitHubStandaloneSection.PULL_REQUESTS.label to ProjectGitHubStandaloneSection.PULL_REQUESTS, ProjectGitHubStandaloneSection.README.label to ProjectGitHubStandaloneSection.README)
    )
    buttonRows.forEach { row ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            row.forEach { (label, section) ->
                if (section == null) {
                    ProjectGitHubStandaloneActionButton(
                        label = "返回",
                        selected = false,
                        modifier = Modifier.weight(1f),
                        onClick = onBack
                    )
                } else {
                    ProjectGitHubStandaloneActionButton(
                        label = if (section == activeSection) "${selectedRepo.name} · $label" else label,
                        selected = section == activeSection,
                        modifier = Modifier.weight(1f),
                        onClick = { onChangeSection(section) }
                    )
                }
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
