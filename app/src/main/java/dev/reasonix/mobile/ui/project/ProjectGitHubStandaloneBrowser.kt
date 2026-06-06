package dev.reasonix.mobile.ui.project

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import dev.reasonix.mobile.ui.ReasonixOutlinedActionButton
import dev.reasonix.mobile.ui.ReasonixSecondaryPageFrame
import dev.reasonix.mobile.ui.ReasonixTagButton
import dev.reasonix.mobile.ui.rememberReasonixChromeColor
import dev.reasonix.mobile.ui.rememberReasonixMutedTextColor
import dev.reasonix.mobile.ui.rememberReasonixSurfaceColor

internal enum class ProjectGitHubStandaloneSection(val label: String) {
    OVERVIEW("项目"),
    RELEASES("Release"),
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
    onLoadRunDetail: (ProjectGitHubWorkflowRunUi, (ProjectGitHubWorkflowRunDetailUi?) -> Unit) -> Unit,
    onRefreshRunDetail: (ProjectGitHubWorkflowRunDetailUi, (ProjectGitHubWorkflowRunDetailUi?) -> Unit) -> Unit,
    onDownloadWorkflowArtifact: (ProjectGitHubWorkflowRunDetailUi, ProjectGitHubArtifactUi) -> Unit,
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
    closeRequestSignal: Int,
    backProgress: Float
) {
    val chromeColor = rememberReasonixChromeColor()
    val surfaceColor = rememberReasonixSurfaceColor()
    val mutedTextColor = rememberReasonixMutedTextColor()

    LaunchedEffect(closeRequestSignal, selectedRepo?.fullName, activeSection) {
        if (closeRequestSignal == 0 || selectedRepo == null) return@LaunchedEffect
        when (activeSection) {
            ProjectGitHubStandaloneSection.OVERVIEW -> onBackToRepoList()
            ProjectGitHubStandaloneSection.WORKFLOWS -> Unit
            else -> onChangeSection(ProjectGitHubStandaloneSection.OVERVIEW)
        }
    }

    ProjectNestedPredictiveBackHost(
        detailVisible = selectedRepo != null,
        backProgress = backProgress,
        modifier = Modifier.fillMaxWidth(),
        detailContent = {
            val currentRepo = selectedRepo ?: return@ProjectNestedPredictiveBackHost
            ProjectNestedPredictiveBackHost(
                detailVisible = activeSection != ProjectGitHubStandaloneSection.OVERVIEW,
                backProgress = backProgress,
                detailContent = {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        when (activeSection) {
                            ProjectGitHubStandaloneSection.RELEASES -> {
                                ProjectGitHubStandaloneReleasePage(
                                    selectedRepo = currentRepo,
                                    releases = repoDetailState.releases,
                                    isLoading = isRepoDetailLoading,
                                    isActionRunning = isActionRunning,
                                    onRefresh = onRefreshRepoDetail,
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
                                    selectedRepo = currentRepo,
                                    state = repoDetailState,
                                    isLoading = isRepoDetailLoading,
                                    tokenConfigured = tokenConfigured,
                                    onRefreshRepoDetail = onRefreshRepoDetail,
                                    onRunWorkflow = onRunWorkflow,
                                    onLoadRunDetail = onLoadRunDetail,
                                    onRefreshRunDetail = onRefreshRunDetail,
                                    onDownloadWorkflowArtifact = onDownloadWorkflowArtifact,
                                    closeRequestSignal = closeRequestSignal,
                                    onExitWorkflowPage = {
                                        onChangeSection(ProjectGitHubStandaloneSection.OVERVIEW)
                                    },
                                    backProgress = backProgress
                                )
                            }

                            ProjectGitHubStandaloneSection.ISSUES -> {
                                ProjectGitHubStandaloneIssuePage(
                                    selectedRepo = currentRepo,
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
                                    selectedRepo = currentRepo,
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

                            ProjectGitHubStandaloneSection.OVERVIEW -> Unit
                        }
                    }
                },
                listContent = {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        ProjectGitHubStandaloneOverviewPage(
                            selectedRepo = currentRepo,
                            selectedTaskRepo = selectedTaskRepo,
                            onEditRepoDescription = { onEditRepoDescription(currentRepo) },
                            onRefreshRepoDetail = onRefreshRepoDetail,
                            onShowWorkflows = {
                                onChangeSection(ProjectGitHubStandaloneSection.WORKFLOWS)
                            },
                            onShowReleases = {
                                onChangeSection(ProjectGitHubStandaloneSection.RELEASES)
                            },
                            onShowIssues = {
                                onChangeSection(ProjectGitHubStandaloneSection.ISSUES)
                            },
                            onShowPullRequests = {
                                onChangeSection(ProjectGitHubStandaloneSection.PULL_REQUESTS)
                            },
                            readme = readme,
                            isReadmeLoading = isReadmeLoading,
                            readmeErrorMessage = readmeErrorMessage,
                            onEditReadme = onEditReadme,
                            backProgress = backProgress
                        )
                    }
                }
            )
        },
        listContent = {
            ProjectGitHubStandaloneRepoListContent(
                tokenConfigured = tokenConfigured,
                repoListState = repoListState,
                isRepoListLoading = isRepoListLoading,
                selectedTaskRepo = selectedTaskRepo,
                onRefreshRepoList = onRefreshRepoList,
                onSelectRepo = onSelectRepo,
                onShowRepoMenu = onShowRepoMenu,
                chromeColor = chromeColor,
                surfaceColor = surfaceColor,
                mutedTextColor = mutedTextColor
            )
        }
    )
}

@Composable
private fun ProjectGitHubStandaloneRepoListContent(
    tokenConfigured: Boolean,
    repoListState: ProjectGitHubViewerRepositoriesState,
    isRepoListLoading: Boolean,
    selectedTaskRepo: ProjectGitHubRepoRef?,
    onRefreshRepoList: () -> Unit,
    onSelectRepo: (ProjectGitHubAccountRepoUi) -> Unit,
    onShowRepoMenu: (ProjectGitHubAccountRepoUi) -> Unit,
    chromeColor: androidx.compose.ui.graphics.Color,
    surfaceColor: androidx.compose.ui.graphics.Color,
    mutedTextColor: androidx.compose.ui.graphics.Color
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ProjectSectionCard(
            shape = RoundedCornerShape(14.dp),
            surfaceColorOverride = chromeColor.copy(alpha = 0.38f)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                    text = "未选择本地项目时，直接显示当前账号下的仓库；点按进入仓库，长按可设为当前任务仓库。",
                    style = MaterialTheme.typography.bodySmall,
                    color = mutedTextColor
                )
                ReasonixOutlinedActionButton(
                    text = "刷新仓库",
                    onClick = onRefreshRepoList,
                    enabled = tokenConfigured && !isRepoListLoading
                )
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
    }
}

@Composable
private fun ProjectGitHubStandaloneOverviewPage(
    selectedRepo: ProjectGitHubAccountRepoUi,
    selectedTaskRepo: ProjectGitHubRepoRef?,
    onEditRepoDescription: () -> Unit,
    onRefreshRepoDetail: () -> Unit,
    onShowWorkflows: () -> Unit,
    onShowReleases: () -> Unit,
    onShowIssues: () -> Unit,
    onShowPullRequests: () -> Unit,
    readme: ProjectGitHubReadmeUi?,
    isReadmeLoading: Boolean,
    readmeErrorMessage: String?,
    onEditReadme: (ProjectGitHubReadmeUi) -> Unit,
    backProgress: Float
) {
    ReasonixSecondaryPageFrame(
        title = selectedRepo.fullName,
        subtitle = "仓库信息和 README 直接显示在这里。"
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            ProjectGitHubStandaloneHeaderCard(
                selectedRepo = selectedRepo,
                selectedTaskRepo = selectedTaskRepo,
                onEditRepoDescription = onEditRepoDescription
            )
            ProjectGitHubStandaloneTopActions(
                onShowReleases = onShowReleases,
                onShowWorkflows = onShowWorkflows,
                onShowIssues = onShowIssues,
                onShowPullRequests = onShowPullRequests
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
                    onOpenRepoPage = null,
                    onEditReadme = onEditReadme
                )
            }
        }
    }
}

@Composable
private fun ProjectGitHubStandaloneReleasePage(
    selectedRepo: ProjectGitHubAccountRepoUi,
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
    onOpenReleaseAssets: (ProjectGitHubReleaseUi) -> Unit,
    backProgress: Float
) {
    ReasonixSecondaryPageFrame(
        title = "${selectedRepo.name} · Release",
        subtitle = "当前仓库的 Release 列表。"
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            ProjectGitHubStandaloneReleaseSection(
                releases = releases,
                isLoading = isLoading,
                isActionRunning = isActionRunning,
                onRefresh = onRefresh,
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
    onRunWorkflow: (ProjectGitHubWorkflowUi) -> Unit,
    onLoadRunDetail: (ProjectGitHubWorkflowRunUi, (ProjectGitHubWorkflowRunDetailUi?) -> Unit) -> Unit,
    onRefreshRunDetail: (ProjectGitHubWorkflowRunDetailUi, (ProjectGitHubWorkflowRunDetailUi?) -> Unit) -> Unit,
    onDownloadWorkflowArtifact: (ProjectGitHubWorkflowRunDetailUi, ProjectGitHubArtifactUi) -> Unit,
    closeRequestSignal: Int,
    onExitWorkflowPage: () -> Unit,
    backProgress: Float
) {
    var selectedWorkflow by remember(selectedRepo.fullName) {
        mutableStateOf<ProjectGitHubWorkflowUi?>(null)
    }
    var selectedRun by remember(selectedRepo.fullName) {
        mutableStateOf<ProjectGitHubWorkflowRunUi?>(null)
    }
    var selectedRunDetail by remember(selectedRepo.fullName) {
        mutableStateOf<ProjectGitHubWorkflowRunDetailUi?>(null)
    }
    var selectedJob by remember(selectedRepo.fullName) {
        mutableStateOf<ProjectGitHubWorkflowJobUi?>(null)
    }
    var workflowRunDetailErrorMessage by remember(selectedRepo.fullName) {
        mutableStateOf<String?>(null)
    }
    var isWorkflowRunDetailLoading by remember(selectedRepo.fullName) {
        mutableStateOf(false)
    }
    val visibleRuns = remember(selectedWorkflow, state.recentRuns) {
        buildProjectGitHubStandaloneWorkflowRuns(
            workflow = selectedWorkflow,
            runs = state.recentRuns
        )
    }
    LaunchedEffect(closeRequestSignal) {
        if (closeRequestSignal == 0) return@LaunchedEffect
        when {
            selectedJob != null -> {
                selectedJob = null
            }

            selectedRun != null -> {
                selectedRun = null
                selectedRunDetail = null
                selectedJob = null
                workflowRunDetailErrorMessage = null
                isWorkflowRunDetailLoading = false
            }

            selectedWorkflow != null -> {
                selectedWorkflow = null
                selectedRun = null
                selectedRunDetail = null
                selectedJob = null
                workflowRunDetailErrorMessage = null
                isWorkflowRunDetailLoading = false
            }

            else -> onExitWorkflowPage()
        }
    }
    fun openWorkflowRun(run: ProjectGitHubWorkflowRunUi) {
        selectedRun = run
        selectedRunDetail = null
        selectedJob = null
        workflowRunDetailErrorMessage = null
        isWorkflowRunDetailLoading = true
        onLoadRunDetail(run) { detail ->
            selectedRunDetail = detail
            workflowRunDetailErrorMessage = if (detail == null) {
                "读取工作流运行详情失败，请刷新后重试。"
            } else {
                null
            }
            isWorkflowRunDetailLoading = false
        }
    }
    fun refreshWorkflowRunDetail() {
        val currentDetail = selectedRunDetail ?: return
        isWorkflowRunDetailLoading = true
        onRefreshRunDetail(currentDetail) { updatedDetail ->
            selectedRunDetail = updatedDetail ?: currentDetail
            workflowRunDetailErrorMessage = if (updatedDetail == null) {
                "刷新工作流运行详情失败，请稍后再试。"
            } else {
                null
            }
            if (updatedDetail != null && selectedJob != null) {
                selectedJob = updatedDetail.jobs.firstOrNull { it.id == selectedJob?.id }
            }
            isWorkflowRunDetailLoading = false
        }
    }
    ReasonixSecondaryPageFrame(
        title = when {
            selectedJob != null -> selectedJob!!.name
            selectedRun != null -> "运行 #${selectedRun!!.runNumber}"
            else -> when (val workflow = selectedWorkflow) {
                null -> "${selectedRepo.name} · 工作流"
                else -> workflow.name.ifBlank { "${selectedRepo.name} · 工作流运行" }
            }
        },
        subtitle = when {
            selectedJob != null -> "当前作业的步骤与日志。"
            selectedRun != null -> "当前运行的作业、产物与日志。"
            else -> when (selectedWorkflow) {
                null -> "这里显示当前可执行工作流。"
                else -> "点开运行记录继续查看作业和日志。"
            }
        }
    ) {
        ProjectNestedPredictiveBackHost(
            detailVisible = selectedWorkflow != null,
            backProgress = backProgress,
            detailContent = {
                ProjectNestedPredictiveBackHost(
                    detailVisible = selectedRun != null,
                    backProgress = backProgress,
                    detailContent = {
                        ProjectNestedPredictiveBackHost(
                            detailVisible = selectedJob != null && selectedRunDetail != null,
                            backProgress = backProgress,
                            detailContent = {
                                ProjectGitHubStandaloneWorkflowJobDetailSection(
                                    detail = selectedRunDetail ?: return@ProjectNestedPredictiveBackHost,
                                    job = selectedJob ?: return@ProjectNestedPredictiveBackHost,
                                    onBackToRun = { selectedJob = null }
                                )
                            },
                            listContent = {
                                ProjectGitHubStandaloneWorkflowRunDetailSection(
                                    run = selectedRun ?: return@ProjectNestedPredictiveBackHost,
                                    detail = selectedRunDetail,
                                    isLoading = isWorkflowRunDetailLoading,
                                    errorMessage = workflowRunDetailErrorMessage,
                                    onBackToRuns = {
                                        selectedRun = null
                                        selectedRunDetail = null
                                        selectedJob = null
                                        workflowRunDetailErrorMessage = null
                                    },
                                    onRefreshDetail = ::refreshWorkflowRunDetail,
                                    onDownloadArtifact = onDownloadWorkflowArtifact,
                                    onOpenJob = { job -> selectedJob = job }
                                )
                            }
                        )
                    },
                    listContent = {
                        Box(modifier = Modifier.fillMaxSize()) {
                            ProjectGitHubStandaloneWorkflowRunsSection(
                                workflow = selectedWorkflow ?: return@ProjectNestedPredictiveBackHost,
                                runs = visibleRuns,
                                isLoading = isLoading,
                                onBackToWorkflowList = {
                                    selectedWorkflow = null
                                    selectedRun = null
                                    selectedRunDetail = null
                                    selectedJob = null
                                    workflowRunDetailErrorMessage = null
                                },
                                onOpenRunDetail = ::openWorkflowRun
                            )
                            ProjectGitHubFloatingWorkflowActionButton(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(end = 4.dp, bottom = 4.dp),
                                onClick = {
                                    selectedWorkflow?.let(onRunWorkflow)
                                }
                            )
                        }
                    }
                )
            },
            listContent = {
                ProjectGitHubStandaloneWorkflowListSection(
                    state = state,
                    isLoading = isLoading,
                    tokenConfigured = tokenConfigured,
                    onRefresh = onRefreshRepoDetail,
                    onOpenWorkflow = { workflow -> selectedWorkflow = workflow }
                )
            }
        )
    }
}

@Composable
private fun ProjectGitHubFloatingWorkflowActionButton(
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    ExtendedFloatingActionButton(
        onClick = onClick,
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        elevation = FloatingActionButtonDefaults.elevation(
            defaultElevation = 6.dp,
            pressedElevation = 8.dp,
            focusedElevation = 6.dp,
            hoveredElevation = 7.dp
        )
    ) {
        Text("运行")
    }
}

@Composable
private fun ProjectGitHubStandaloneWorkflowRunDetailSection(
    run: ProjectGitHubWorkflowRunUi,
    detail: ProjectGitHubWorkflowRunDetailUi?,
    isLoading: Boolean,
    errorMessage: String?,
    onBackToRuns: () -> Unit,
    onRefreshDetail: () -> Unit,
    onDownloadArtifact: (ProjectGitHubWorkflowRunDetailUi, ProjectGitHubArtifactUi) -> Unit,
    onOpenJob: (ProjectGitHubWorkflowJobUi) -> Unit
) {
    val mutedTextColor = rememberReasonixMutedTextColor()
    val surfaceColor = rememberReasonixSurfaceColor()
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ProjectSectionCard(
            shape = RoundedCornerShape(14.dp),
            surfaceColorOverride = rememberReasonixChromeColor().copy(alpha = 0.28f)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("第 ${run.runNumber} 次运行", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "${buildProjectGitHubStandaloneWorkflowTriggerLabel(run.event)} · ${run.statusLabel}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (run.hasIssue) MaterialTheme.colorScheme.error else mutedTextColor
                )
                Text(
                    text = "分支 ${run.headBranch.ifBlank { "未知" }} · 更新时间 ${run.updatedAt}",
                    style = MaterialTheme.typography.bodySmall,
                    color = mutedTextColor
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReasonixOutlinedActionButton(
                        text = "返回运行列表",
                        onClick = onBackToRuns
                    )
                    ReasonixOutlinedActionButton(
                        text = "刷新详情",
                        onClick = onRefreshDetail,
                        enabled = detail != null && !isLoading
                    )
                }
            }
        }
        if (isLoading && detail == null) {
            ProjectGitHubStandaloneLoadingCard("正在读取运行详情与日志...")
            return
        }
        if (detail == null) {
            ProjectSectionCard(
                shape = RoundedCornerShape(14.dp),
                surfaceColorOverride = surfaceColor.copy(alpha = 0.58f)
            ) {
                Text(
                    text = errorMessage ?: "当前还没有读取到运行详情。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            return
        }
        ProjectGitHubStandaloneWorkflowJobSection(
            detail = detail,
            onOpenJob = onOpenJob
        )
        if (detail.artifacts.isNotEmpty()) {
            ProjectSectionCard(
                shape = RoundedCornerShape(14.dp),
                surfaceColorOverride = surfaceColor.copy(alpha = 0.58f)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("产物文件", style = MaterialTheme.typography.titleSmall)
                    detail.artifacts.forEach { artifact ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(artifact.name, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    text = "${artifact.sizeLabel} · ${artifact.updatedAt}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = mutedTextColor
                                )
                            }
                            ReasonixOutlinedActionButton(
                                text = "下载",
                                onClick = { onDownloadArtifact(detail, artifact) }
                            )
                        }
                    }
                }
            }
        }
        ProjectGitHubStandaloneWorkflowLogsSection(detail = detail)
    }
}

@Composable
private fun ProjectGitHubStandaloneWorkflowJobSection(
    detail: ProjectGitHubWorkflowRunDetailUi,
    onOpenJob: (ProjectGitHubWorkflowJobUi) -> Unit
) {
    val mutedTextColor = rememberReasonixMutedTextColor()
    ProjectSectionCard(
        shape = RoundedCornerShape(14.dp),
        surfaceColorOverride = rememberReasonixSurfaceColor().copy(alpha = 0.58f)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("作业", style = MaterialTheme.typography.titleSmall)
            detail.jobs.forEach { job ->
                ProjectInsetCard(
                    shape = RoundedCornerShape(12.dp),
                    surfaceColorOverride = rememberReasonixChromeColor().copy(
                        alpha = if (job.hasIssue) 0.34f else 0.22f
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenJob(job) }
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(job.name, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "${job.statusLabel} · ${job.durationLabel}",
                            style = MaterialTheme.typography.bodySmall,
                            color = when {
                                job.hasIssue -> MaterialTheme.colorScheme.error
                                job.status.equals("completed", ignoreCase = true) -> MaterialTheme.colorScheme.primary
                                else -> mutedTextColor
                            }
                        )
                        job.steps.forEach { step ->
                            Text(
                                text = "${step.number}. ${step.name} · ${step.statusLabel} · ${step.durationLabel}",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (step.hasIssue) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    mutedTextColor
                                }
                            )
                        }
                        Text(
                            text = "点开查看这个作业的日志",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProjectGitHubStandaloneWorkflowJobDetailSection(
    detail: ProjectGitHubWorkflowRunDetailUi,
    job: ProjectGitHubWorkflowJobUi,
    onBackToRun: () -> Unit
) {
    val mutedTextColor = rememberReasonixMutedTextColor()
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ProjectSectionCard(
            shape = RoundedCornerShape(14.dp),
            surfaceColorOverride = rememberReasonixChromeColor().copy(alpha = 0.28f)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(job.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "${job.statusLabel} · ${job.durationLabel}",
                    style = MaterialTheme.typography.bodySmall,
                    color = when {
                        job.hasIssue -> MaterialTheme.colorScheme.error
                        job.status.equals("completed", ignoreCase = true) -> MaterialTheme.colorScheme.primary
                        else -> mutedTextColor
                    }
                )
                ReasonixOutlinedActionButton(
                    text = "返回运行详情",
                    onClick = onBackToRun
                )
            }
        }
        ProjectSectionCard(
            shape = RoundedCornerShape(14.dp),
            surfaceColorOverride = rememberReasonixSurfaceColor().copy(alpha = 0.58f)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("步骤", style = MaterialTheme.typography.titleSmall)
                job.steps.forEach { step ->
                    ProjectInsetCard(
                        shape = RoundedCornerShape(12.dp),
                        surfaceColorOverride = rememberReasonixChromeColor().copy(
                            alpha = if (step.hasIssue) 0.30f else 0.18f
                        )
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "${step.number}. ${step.name}",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = "${step.statusLabel} · ${step.durationLabel}",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (step.hasIssue) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    mutedTextColor
                                }
                            )
                        }
                    }
                }
            }
        }
        ProjectGitHubStandaloneWorkflowLogsSection(
            detail = detail,
            initialJobName = job.name,
            showJobFilters = false
        )
    }
}

@Composable
private fun ProjectGitHubStandaloneWorkflowLogsSection(
    detail: ProjectGitHubWorkflowRunDetailUi,
    initialJobName: String? = null,
    showJobFilters: Boolean = true
) {
    val mutedTextColor = rememberReasonixMutedTextColor()
    var selectedJobName by remember(detail.id) {
        mutableStateOf(initialJobName ?: buildProjectGitHubStandalonePrimaryJobName(detail))
    }
    var expandedLogEntries by remember(detail.id) {
        mutableStateOf(
            buildProjectGitHubStandaloneDefaultExpandedLogEntries(
                detail = detail,
                selectedJobName = selectedJobName
            )
        )
    }
    val visibleEntries = remember(detail.id, detail.logEntries, selectedJobName) {
        detail.logEntries.filter { entry ->
            selectedJobName.isNullOrBlank() ||
                projectGitHubLogMatchesJob(entry, selectedJobName.orEmpty())
        }
    }
    ProjectSectionCard(
        shape = RoundedCornerShape(14.dp),
        surfaceColorOverride = rememberReasonixSurfaceColor().copy(alpha = 0.58f)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("日志", style = MaterialTheme.typography.titleSmall)
            Text(
                text = when {
                    detail.hasIssue -> "当前运行包含错误，已优先定位异常日志。"
                    detail.status.equals("completed", ignoreCase = true) -> "当前运行已结束，日志默认折叠。"
                    else -> "当前运行中，已自动展开正在执行的日志。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = mutedTextColor
            )
            if (showJobFilters) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ReasonixTagButton(
                        text = "全部日志",
                        onClick = {
                            selectedJobName = null
                            expandedLogEntries = buildProjectGitHubStandaloneDefaultExpandedLogEntries(
                                detail = detail,
                                selectedJobName = null
                            )
                        }
                    )
                    detail.jobs.forEach { job ->
                        ReasonixTagButton(
                            text = job.name,
                            onClick = {
                                selectedJobName = job.name
                                expandedLogEntries = buildProjectGitHubStandaloneDefaultExpandedLogEntries(
                                    detail = detail,
                                    selectedJobName = job.name
                                )
                            }
                        )
                    }
                }
            }
            if (visibleEntries.isEmpty()) {
                Text(
                    text = "当前作业范围内没有可显示的日志。",
                    style = MaterialTheme.typography.bodySmall,
                    color = mutedTextColor
                )
            }
            visibleEntries.forEach { entry ->
                val issueSearchHit = remember(entry.entryName, entry.preview) {
                    buildProjectGitHubWorkflowLogSearchHit(
                        entry = entry,
                        queries = PROJECT_GITHUB_WORKFLOW_LOG_SEARCH_PRESETS,
                        requireAllTerms = false
                    )
                }
                val expanded = expandedLogEntries.contains(entry.entryName)
                val activeLineNumber = issueSearchHit.matchedLineNumbers.firstOrNull()
                ProjectInsetCard(
                    shape = RoundedCornerShape(12.dp),
                    surfaceColorOverride = rememberReasonixChromeColor().copy(
                        alpha = if (issueSearchHit.hasMatch) 0.30f else 0.18f
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            expandedLogEntries = if (expanded) {
                                expandedLogEntries - entry.entryName
                            } else {
                                expandedLogEntries + entry.entryName
                            }
                        }
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(entry.displayName, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = buildString {
                                append("${entry.totalLineCount} 行")
                                if (issueSearchHit.hasMatch) {
                                    append(" · 已定位异常日志")
                                }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (issueSearchHit.hasMatch) {
                                MaterialTheme.colorScheme.error
                            } else {
                                mutedTextColor
                            }
                        )
                        ProjectGitHubWorkflowLogPreviewBody(
                            preview = if (expanded) {
                                entry.preview
                            } else {
                                projectGitHubCollapsedLogPreview(
                                    preview = entry.preview,
                                    maxLines = 12
                                )
                            },
                            queries = if (issueSearchHit.hasMatch) {
                                PROJECT_GITHUB_WORKFLOW_LOG_SEARCH_PRESETS
                            } else {
                                emptyList()
                            },
                            expanded = expanded,
                            activeLineNumber = if (issueSearchHit.hasMatch) activeLineNumber else null,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
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
        subtitle = "当前仓库的 Issue 列表。"
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
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
        subtitle = "当前仓库的 Pull Request 列表。"
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
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
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
private fun ProjectGitHubStandaloneTopActions(
    onShowReleases: () -> Unit,
    onShowWorkflows: () -> Unit,
    onShowIssues: () -> Unit,
    onShowPullRequests: () -> Unit
) {
    val buttonRows = listOf(
        listOf(ProjectGitHubStandaloneSection.RELEASES.label to onShowReleases, ProjectGitHubStandaloneSection.WORKFLOWS.label to onShowWorkflows),
        listOf(ProjectGitHubStandaloneSection.ISSUES.label to onShowIssues, ProjectGitHubStandaloneSection.PULL_REQUESTS.label to onShowPullRequests)
    )
    ProjectSectionCard(
        shape = RoundedCornerShape(14.dp),
        surfaceColorOverride = rememberReasonixSurfaceColor().copy(alpha = 0.58f)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = "仓库入口",
                style = MaterialTheme.typography.titleSmall
            )
            buttonRows.forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    row.forEach { (label, onClick) ->
                        ProjectGitHubStandaloneActionButton(
                            label = label,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 44.dp),
                            onClick = onClick
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProjectGitHubStandaloneActionButton(
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    ReasonixOutlinedActionButton(
        text = label,
        onClick = onClick,
        modifier = modifier
    )
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
private fun ProjectGitHubStandaloneWorkflowListSection(
    state: ProjectGitHubActionsState,
    isLoading: Boolean,
    tokenConfigured: Boolean,
    onRefresh: () -> Unit,
    onOpenWorkflow: (ProjectGitHubWorkflowUi) -> Unit
) {
    val mutedTextColor = rememberReasonixMutedTextColor()
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ProjectSectionCard(
            shape = RoundedCornerShape(14.dp),
            surfaceColorOverride = rememberReasonixChromeColor().copy(alpha = 0.28f)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("可执行工作流", style = MaterialTheme.typography.titleSmall)
                ReasonixOutlinedActionButton(
                    text = "刷新",
                    onClick = onRefresh,
                    enabled = tokenConfigured && !isLoading
                )
                when {
                    isLoading -> {
                        ProjectGitHubStandaloneLoadingCard("正在读取当前仓库的工作流...")
                    }
                    state.errorMessage?.isNotBlank() == true -> {
                        Text(
                            text = state.errorMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    !tokenConfigured -> {
                        Text(
                            text = "请先在设置页填写 GitHub Token。",
                            style = MaterialTheme.typography.bodySmall,
                            color = mutedTextColor
                        )
                    }
                    state.workflows.isEmpty() -> {
                        Text(
                            text = "当前仓库还没有可执行工作流。",
                            style = MaterialTheme.typography.bodySmall,
                            color = mutedTextColor
                        )
                    }
                }
            }
        }
        state.workflows.forEach { workflow ->
            ProjectInsetCard(
                shape = RoundedCornerShape(12.dp),
                surfaceColorOverride = rememberReasonixSurfaceColor().copy(alpha = 0.56f),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenWorkflow(workflow) }
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = workflow.name.ifBlank { workflow.path },
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "${workflow.path} · ${workflow.stateLabel}",
                        style = MaterialTheme.typography.bodySmall,
                        color = mutedTextColor
                    )
                    Text(
                        text = "点开查看这个工作流的运行记录",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
private fun ProjectGitHubStandaloneWorkflowRunsSection(
    workflow: ProjectGitHubWorkflowUi,
    runs: List<ProjectGitHubWorkflowRunUi>,
    isLoading: Boolean,
    onBackToWorkflowList: () -> Unit,
    onOpenRunDetail: (ProjectGitHubWorkflowRunUi) -> Unit
) {
    val mutedTextColor = rememberReasonixMutedTextColor()
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ProjectSectionCard(
            shape = RoundedCornerShape(14.dp),
            surfaceColorOverride = rememberReasonixChromeColor().copy(alpha = 0.28f)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(workflow.name.ifBlank { workflow.path }, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "这里显示这个工作流的运行记录，点开后继续看日志和作业。",
                    style = MaterialTheme.typography.bodySmall,
                    color = mutedTextColor
                )
                ReasonixOutlinedActionButton(
                    text = "返回工作流列表",
                    onClick = onBackToWorkflowList
                )
            }
        }
        if (isLoading && runs.isEmpty()) {
            ProjectGitHubStandaloneLoadingCard("正在读取该工作流的运行记录...")
        }
        if (!isLoading && runs.isEmpty()) {
            ProjectSectionCard(
                shape = RoundedCornerShape(14.dp),
                surfaceColorOverride = rememberReasonixSurfaceColor().copy(alpha = 0.58f)
            ) {
                Text(
                    text = "当前还没有读取到这个工作流的运行记录。",
                    style = MaterialTheme.typography.bodySmall,
                    color = mutedTextColor
                )
            }
        }
        runs.forEach { run ->
            ProjectInsetCard(
                shape = RoundedCornerShape(12.dp),
                surfaceColorOverride = rememberReasonixSurfaceColor().copy(alpha = 0.56f),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenRunDetail(run) }
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "第 ${run.runNumber} 次运行",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = run.displayTitle.ifBlank { run.name.ifBlank { workflow.name.ifBlank { "运行记录" } } },
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "${run.statusLabel} · ${buildProjectGitHubStandaloneWorkflowTriggerLabel(run.event)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (run.hasIssue) MaterialTheme.colorScheme.error else mutedTextColor
                    )
                    Text(
                        text = "分支 ${run.headBranch.ifBlank { "未知" }} · 更新时间 ${run.updatedAt}",
                        style = MaterialTheme.typography.labelSmall,
                        color = mutedTextColor
                    )
                }
            }
        }
    }
}

private fun buildProjectGitHubStandaloneWorkflowRuns(
    workflow: ProjectGitHubWorkflowUi?,
    runs: List<ProjectGitHubWorkflowRunUi>
): List<ProjectGitHubWorkflowRunUi> {
    if (workflow == null) return emptyList()
    val workflowName = workflow.name.trim()
    val workflowPathName = workflow.path.substringAfterLast('/').substringBeforeLast('.').trim()
    return runs.filter { run ->
        val runName = run.name.trim()
        runName.equals(workflowName, ignoreCase = true) ||
            (workflowPathName.isNotBlank() && runName.contains(workflowPathName, ignoreCase = true))
    }
}

private fun buildProjectGitHubStandalonePrimaryJobName(
    detail: ProjectGitHubWorkflowRunDetailUi
): String? {
    val issueJob = detail.jobs.firstOrNull { it.hasIssue }
    if (issueJob != null) return issueJob.name
    val activeJob = detail.jobs.firstOrNull { !it.status.equals("completed", ignoreCase = true) }
    if (activeJob != null) return activeJob.name
    return detail.jobs.firstOrNull()?.name
}

private fun buildProjectGitHubStandaloneDefaultExpandedLogEntries(
    detail: ProjectGitHubWorkflowRunDetailUi,
    selectedJobName: String?
): Set<String> {
    if (detail.hasIssue) {
        val issueEntries = detail.logEntries.filter { entry ->
            val matchesJob = selectedJobName.isNullOrBlank() ||
                projectGitHubLogMatchesJob(entry, selectedJobName.orEmpty())
            matchesJob && buildProjectGitHubWorkflowLogSearchHit(
                entry = entry,
                queries = PROJECT_GITHUB_WORKFLOW_LOG_SEARCH_PRESETS,
                requireAllTerms = false
            ).hasMatch
        }
        if (issueEntries.isNotEmpty()) {
            return issueEntries.map { it.entryName }.toSet()
        }
    }
    if (!detail.status.equals("completed", ignoreCase = true)) {
        val autoExpanded = buildProjectGitHubAutoExpandedLogEntries(detail)
        if (selectedJobName.isNullOrBlank()) return autoExpanded
        return autoExpanded.filter { entryName ->
            detail.logEntries.any { entry ->
                entry.entryName == entryName &&
                    projectGitHubLogMatchesJob(entry, selectedJobName)
            }
        }.toSet()
    }
    return emptySet()
}

private fun buildProjectGitHubStandaloneWorkflowTriggerLabel(event: String): String {
    return when (event.trim().lowercase()) {
        "workflow_dispatch" -> "手动执行"
        "push" -> "提交触发"
        "pull_request" -> "PR 触发"
        "schedule" -> "定时触发"
        else -> event.ifBlank { "未知触发" }
    }
}

@Composable
private fun ProjectGitHubStandaloneLoadingCard(message: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CircularProgressIndicator(strokeWidth = 2.dp)
        Text(message, style = MaterialTheme.typography.bodySmall)
    }
}
