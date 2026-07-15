package com.murong.agent.ui.project

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.murong.agent.ui.MurongOutlinedActionButton
import com.murong.agent.ui.MurongProjectInsetCardShape
import com.murong.agent.ui.MurongProjectSectionCardShape
import com.murong.agent.ui.MurongSecondaryPageFrame
import com.murong.agent.ui.rememberMurongBottomBarScrollPadding
import com.murong.agent.ui.rememberMurongChromeColor
import com.murong.agent.ui.rememberMurongMutedTextColor
import com.murong.agent.ui.rememberMurongSurfaceColor

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
    onRegisterNestedCloseRequest: (((() -> Unit)?) -> Unit),
    backProgress: Float,
    forceListInternalScroll: Boolean = false
) {
    val chromeColor = rememberMurongChromeColor()
    val surfaceColor = rememberMurongSurfaceColor()
    val mutedTextColor = rememberMurongMutedTextColor()
    var sectionCloseRequest by remember(selectedRepo?.fullName, activeSection) {
        mutableStateOf<(() -> Unit)?>(null)
    }
    var workflowNestedDetailVisible by remember(selectedRepo?.fullName, activeSection) {
        mutableStateOf(false)
    }
    var retainedRepoForDetail by remember {
        mutableStateOf<ProjectGitHubAccountRepoUi?>(null)
    }
    var retainedSectionForDetail by remember {
        mutableStateOf(ProjectGitHubStandaloneSection.OVERVIEW)
    }
    val outerBackProgress = if (
        selectedRepo != null && activeSection == ProjectGitHubStandaloneSection.OVERVIEW
    ) {
        backProgress
    } else {
        0f
    }
    val innerBackProgress = if (
        selectedRepo != null &&
            activeSection != ProjectGitHubStandaloneSection.OVERVIEW &&
            (activeSection != ProjectGitHubStandaloneSection.WORKFLOWS || !workflowNestedDetailVisible)
    ) {
        backProgress
    } else {
        0f
    }

    SideEffect {
        if (selectedRepo != null) {
            retainedRepoForDetail = selectedRepo
        }
        if (selectedRepo != null && activeSection != ProjectGitHubStandaloneSection.OVERVIEW) {
            retainedSectionForDetail = activeSection
        }
        onRegisterNestedCloseRequest(
            if (selectedRepo == null) {
                null
            } else {
                sectionCloseRequest ?: {
                    when (activeSection) {
                        ProjectGitHubStandaloneSection.OVERVIEW -> onBackToRepoList()
                        else -> onChangeSection(ProjectGitHubStandaloneSection.OVERVIEW)
                    }
                }
            }
        )
    }
    val repoForDetail = selectedRepo ?: retainedRepoForDetail
    val sectionForDetail = if (activeSection != ProjectGitHubStandaloneSection.OVERVIEW) {
        activeSection
    } else {
        retainedSectionForDetail
    }

    ProjectNestedPredictiveBackHost(
        detailVisible = selectedRepo != null,
        backProgress = outerBackProgress,
        modifier = Modifier.fillMaxSize(),
        detailContent = {
            val currentRepo = repoForDetail ?: return@ProjectNestedPredictiveBackHost
            ProjectNestedPredictiveBackHost(
                detailVisible = activeSection != ProjectGitHubStandaloneSection.OVERVIEW,
                backProgress = innerBackProgress,
                detailContent = {
                    Box(modifier = Modifier.fillMaxSize()) {
                        when (sectionForDetail) {
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
                                    onRegisterNestedCloseRequest = { sectionCloseRequest = it },
                                    onWorkflowDetailVisibilityChanged = {
                                        workflowNestedDetailVisible = it
                                    },
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
                    Box(modifier = Modifier.fillMaxSize()) {
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
            if (forceListInternalScroll) {
                Box(modifier = Modifier.fillMaxSize()) {
                    ProjectGitHubStandaloneScrollableContent {
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
                }
            } else {
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
            shape = RoundedCornerShape(20.dp),
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
                MurongOutlinedActionButton(
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
                shape = RoundedCornerShape(20.dp),
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
    MurongSecondaryPageFrame(
        title = selectedRepo.fullName,
        subtitle = "仓库信息和 README 直接显示在这里。"
        ,
        includeBottomBarPadding = false
    ) {
        ProjectGitHubStandalonePageBody {
            ProjectGitHubStandaloneScrollableContent {
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
                    shape = RoundedCornerShape(20.dp),
                    surfaceColorOverride = rememberMurongSurfaceColor().copy(alpha = 0.58f)
                ) {
                    ProjectGitHubReadmeSection(
                        readme = readme,
                        isLoading = isReadmeLoading,
                        errorMessage = readmeErrorMessage,
                        compactPreview = backProgress > 0f,
                        onRefresh = onRefreshRepoDetail,
                        onOpenRepoPage = null,
                        onEditReadme = onEditReadme
                    )
                }
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
    MurongSecondaryPageFrame(
        title = "${selectedRepo.name} · Release",
        subtitle = "当前仓库的 Release 列表。"
        ,
        includeBottomBarPadding = false
    ) {
        ProjectGitHubStandalonePageBody {
            ProjectGitHubStandaloneScrollableContent {
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
    onRegisterNestedCloseRequest: (((() -> Unit)?) -> Unit),
    onWorkflowDetailVisibilityChanged: (Boolean) -> Unit,
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
    var floatingRunActionVisible by remember(selectedRepo.fullName) {
        mutableStateOf(false)
    }
    var retainedWorkflow by remember(selectedRepo.fullName) {
        mutableStateOf<ProjectGitHubWorkflowUi?>(null)
    }
    var retainedRun by remember(selectedRepo.fullName) {
        mutableStateOf<ProjectGitHubWorkflowRunUi?>(null)
    }
    var retainedRunDetail by remember(selectedRepo.fullName) {
        mutableStateOf<ProjectGitHubWorkflowRunDetailUi?>(null)
    }
    var retainedJob by remember(selectedRepo.fullName) {
        mutableStateOf<ProjectGitHubWorkflowJobUi?>(null)
    }
    val visibleRuns = remember(selectedWorkflow, state.recentRuns) {
        buildProjectGitHubStandaloneWorkflowRuns(
            workflow = selectedWorkflow,
            runs = state.recentRuns
        )
    }
    val workflowForDetail = selectedWorkflow ?: retainedWorkflow
    val runForDetail = selectedRun ?: retainedRun
    val runDetailForDetail = selectedRunDetail ?: retainedRunDetail
    val jobForDetail = selectedJob ?: retainedJob
    val visibleRunsForDetail = remember(workflowForDetail, state.recentRuns) {
        buildProjectGitHubStandaloneWorkflowRuns(
            workflow = workflowForDetail,
            runs = state.recentRuns
        )
    }
    val hasWorkflowDetailStack = selectedWorkflow != null || selectedRun != null || selectedJob != null

    DisposableEffect(Unit) {
        onDispose {
            onRegisterNestedCloseRequest(null)
            onWorkflowDetailVisibilityChanged(false)
        }
    }

    SideEffect {
        if (selectedWorkflow != null) {
            retainedWorkflow = selectedWorkflow
        }
        if (selectedRun != null) {
            retainedRun = selectedRun
        }
        if (selectedRunDetail != null) {
            retainedRunDetail = selectedRunDetail
        }
        if (selectedJob != null) {
            retainedJob = selectedJob
        }
        onRegisterNestedCloseRequest {
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
    }
    LaunchedEffect(hasWorkflowDetailStack) {
        onWorkflowDetailVisibilityChanged(hasWorkflowDetailStack)
    }
    LaunchedEffect(selectedWorkflow, selectedRun, selectedJob) {
        floatingRunActionVisible = false
        if (selectedWorkflow != null && selectedRun == null && selectedJob == null) {
            withFrameNanos { }
            floatingRunActionVisible = true
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
    val nestedBackProgress = if (hasWorkflowDetailStack) backProgress else 0f
    val bottomBarScrollPadding = rememberMurongBottomBarScrollPadding()
    Box(modifier = Modifier.fillMaxSize()) {
        MurongSecondaryPageFrame(
            title = when {
                jobForDetail != null -> jobForDetail.name
                runForDetail != null -> "运行 #${runForDetail.runNumber}"
                else -> when (val workflow = workflowForDetail) {
                    null -> "${selectedRepo.name} · 工作流"
                    else -> workflow.name.ifBlank { "${selectedRepo.name} · 工作流运行" }
                }
            },
            subtitle = when {
                jobForDetail != null -> "当前作业的步骤与日志。"
                runForDetail != null -> "当前运行的作业、产物与日志。"
                else -> when (workflowForDetail) {
                    null -> "这里显示当前可执行工作流。"
                    else -> "点开运行记录继续查看作业和日志。"
                }
            },
            includeBottomBarPadding = false
        ) {
            ProjectGitHubStandalonePageBody {
                ProjectNestedPredictiveBackHost(
                    detailVisible = selectedWorkflow != null,
                    backProgress = nestedBackProgress,
                    modifier = Modifier.fillMaxSize(),
                    detailContent = {
                        ProjectNestedPredictiveBackHost(
                            detailVisible = selectedRun != null,
                            backProgress = nestedBackProgress,
                            modifier = Modifier.fillMaxSize(),
                            detailContent = {
                                ProjectNestedPredictiveBackHost(
                                    detailVisible = selectedJob != null && selectedRunDetail != null,
                                    backProgress = nestedBackProgress,
                                    modifier = Modifier.fillMaxSize(),
                                    detailContent = {
                                        ProjectGitHubStandaloneWorkflowJobDetailSection(
                                            detail = runDetailForDetail ?: return@ProjectNestedPredictiveBackHost,
                                            job = jobForDetail ?: return@ProjectNestedPredictiveBackHost,
                                            onBackToRun = { selectedJob = null }
                                        )
                                    },
                                    listContent = {
                                        ProjectGitHubStandaloneWorkflowRunDetailSection(
                                            run = runForDetail ?: return@ProjectNestedPredictiveBackHost,
                                            detail = runDetailForDetail,
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
                                ProjectGitHubStandaloneWorkflowRunsSection(
                                    workflow = workflowForDetail ?: return@ProjectNestedPredictiveBackHost,
                                    runs = visibleRunsForDetail,
                                    isLoading = isLoading,
                                    onOpenRunDetail = ::openWorkflowRun
                                )
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
        if (floatingRunActionVisible) {
            ProjectGitHubFloatingWorkflowActionButton(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(
                        end = 4.dp,
                        bottom = (bottomBarScrollPadding - 8.dp).coerceAtLeast(4.dp)
                    ),
                onClick = {
                    selectedWorkflow?.let(onRunWorkflow)
                }
            )
        }
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
        Text(
            text = "运行",
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun ProjectGitHubStandaloneScrollableContent(
    bottomPadding: Dp = 0.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = bottomPadding),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        content = content
    )
}

@Composable
private fun ColumnScope.ProjectGitHubStandalonePageBody(
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = Modifier
            .weight(1f, fill = true)
            .fillMaxWidth()
    ) {
        content()
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
    val mutedTextColor = rememberMurongMutedTextColor()
    val surfaceColor = rememberMurongSurfaceColor()
    ProjectGitHubStandaloneScrollableContent {
        ProjectSectionCard(
            shape = MurongProjectSectionCardShape,
            surfaceColorOverride = rememberMurongChromeColor().copy(alpha = 0.28f)
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
                    MurongOutlinedActionButton(
                        text = "返回运行列表",
                        onClick = onBackToRuns
                    )
                    MurongOutlinedActionButton(
                        text = "刷新详情",
                        onClick = onRefreshDetail,
                        enabled = detail != null && !isLoading
                    )
                }
            }
        }
        if (isLoading && detail == null) {
            ProjectGitHubStandaloneLoadingCard("正在读取运行详情与日志...")
        } else if (detail == null) {
            ProjectSectionCard(
                shape = MurongProjectSectionCardShape,
                surfaceColorOverride = surfaceColor.copy(alpha = 0.58f)
            ) {
                Text(
                    text = errorMessage ?: "当前还没有读取到运行详情。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        } else {
            if (detail.artifacts.isNotEmpty()) {
                ProjectSectionCard(
                    shape = MurongProjectSectionCardShape,
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
                                MurongOutlinedActionButton(
                                    text = "下载",
                                    onClick = { onDownloadArtifact(detail, artifact) }
                                )
                            }
                        }
                    }
                }
            }
            ProjectGitHubStandaloneWorkflowJobSection(
                detail = detail,
                onOpenJob = onOpenJob
            )
        }
    }
}

@Composable
private fun ProjectGitHubStandaloneWorkflowJobSection(
    detail: ProjectGitHubWorkflowRunDetailUi,
    onOpenJob: (ProjectGitHubWorkflowJobUi) -> Unit
) {
    val mutedTextColor = rememberMurongMutedTextColor()
    val chromeColor = rememberMurongChromeColor()
    ProjectSectionCard(
        shape = RoundedCornerShape(20.dp),
        surfaceColorOverride = rememberMurongSurfaceColor().copy(alpha = 0.58f)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("作业", style = MaterialTheme.typography.titleSmall)
            detail.jobs.forEach { job ->
                ProjectInsetCard(
                    shape = RoundedCornerShape(16.dp),
                    surfaceColorOverride = chromeColor.copy(
                        alpha = if (job.hasIssue) 0.34f else 0.22f
                    ),
                    modifier = Modifier.fillMaxWidth()
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
                        Text(
                            text = "这里不直接展开日志，点下面的作业详情进入查看。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            MurongOutlinedActionButton(
                                text = "作业详情",
                                onClick = { onOpenJob(job) }
                            )
                        }
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
    val mutedTextColor = rememberMurongMutedTextColor()
    var expandedStepKeys by remember(detail.id, job.name) {
        mutableStateOf(buildProjectGitHubStandaloneDefaultExpandedStepKeys(detail))
    }
    var expandedLogEntries by remember(detail.id, job.name) {
        mutableStateOf(buildProjectGitHubAutoExpandedLogEntries(detail))
    }
    ProjectGitHubStandaloneScrollableContent {
        ProjectSectionCard(
            shape = RoundedCornerShape(20.dp),
            surfaceColorOverride = rememberMurongChromeColor().copy(alpha = 0.28f)
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
                MurongOutlinedActionButton(
                    text = "返回运行详情",
                    onClick = onBackToRun
                )
            }
        }
        ProjectGitHubStandaloneWorkflowStepLogSection(
            detail = detail,
            job = job,
            expandedStepKeys = expandedStepKeys,
            onExpandedStepKeysChange = { expandedStepKeys = it },
            expandedLogEntries = expandedLogEntries,
            onExpandedLogEntriesChange = { expandedLogEntries = it }
        )
    }
}

@Composable
private fun ProjectGitHubStandaloneWorkflowStepLogSection(
    detail: ProjectGitHubWorkflowRunDetailUi,
    job: ProjectGitHubWorkflowJobUi,
    expandedStepKeys: Set<String>,
    onExpandedStepKeysChange: (Set<String>) -> Unit,
    expandedLogEntries: Set<String>,
    onExpandedLogEntriesChange: (Set<String>) -> Unit
) {
    val mutedTextColor = rememberMurongMutedTextColor()
    val chromeColor = rememberMurongChromeColor()
    var showTimestamps by remember(detail.id, job.name) {
        mutableStateOf(false)
    }
    val stepGroups = remember(detail.id, job.name, job.steps, detail.logEntries) {
        buildProjectGitHubStandaloneWorkflowStepLogGroups(detail, job)
    }
    ProjectSectionCard(
        shape = RoundedCornerShape(20.dp),
        surfaceColorOverride = rememberMurongSurfaceColor().copy(alpha = 0.58f)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("步骤日志", style = MaterialTheme.typography.titleSmall)
                MurongOutlinedActionButton(
                    text = if (showTimestamps) "隐藏时间戳" else "显示时间戳",
                    onClick = { showTimestamps = !showTimestamps }
                )
            }
            Text(
                text = when {
                    job.hasIssue -> "失败步骤会优先展开，点步骤卡片可查看对应日志。"
                    job.status.equals("completed", ignoreCase = true) -> "当前作业已结束，步骤日志默认折叠。"
                    else -> "运行中的步骤会自动展开。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = mutedTextColor
            )
            if (stepGroups.isEmpty()) {
                Text(
                    text = "当前作业没有可显示的步骤日志。",
                    style = MaterialTheme.typography.bodySmall,
                    color = mutedTextColor
                )
            }
            stepGroups.forEach { stepGroup ->
                val stepExpanded = expandedStepKeys.contains(stepGroup.key)
                ProjectInsetCard(
                    shape = RoundedCornerShape(16.dp),
                    surfaceColorOverride = chromeColor.copy(
                        alpha = if (stepGroup.hasIssue || stepGroup.isActive) 0.30f else 0.18f
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onExpandedStepKeysChange(
                                if (stepExpanded) {
                                    expandedStepKeys - stepGroup.key
                                } else {
                                    expandedStepKeys + stepGroup.key
                                }
                            )
                            if (!stepExpanded) {
                                val autoEntries = if (stepGroup.step == null) {
                                    stepGroup.entries.firstOrNull()?.entryName?.let(::setOf).orEmpty()
                                } else {
                                    detail.logEntries
                                        .filter { entry ->
                                            projectGitHubLogMatchesJob(entry, job.name) &&
                                                projectGitHubLogMatchesStep(
                                                    entry = entry,
                                                    stepName = stepGroup.step.name,
                                                    stepNumber = stepGroup.step.number
                                                )
                                        }
                                        .map { it.entryName }
                                        .toSet()
                                }
                                onExpandedLogEntriesChange(
                                    expandedLogEntries + if (autoEntries.isNotEmpty()) {
                                        autoEntries
                                    } else {
                                        stepGroup.entries.firstOrNull()?.entryName?.let(::setOf).orEmpty()
                                    }
                                )
                            }
                        }
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stepGroup.title, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = stepGroup.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (stepGroup.hasIssue) {
                                MaterialTheme.colorScheme.error
                            } else {
                                mutedTextColor
                            }
                        )
                        Text(
                            text = if (stepExpanded) "点击收起日志" else "点击展开日志",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (stepExpanded) {
                            if (stepGroup.entries.isEmpty()) {
                                val emptyLogHint = if (
                                    stepGroup.step?.conclusion.equals("skipped", ignoreCase = true)
                                ) {
                                    "这个步骤已跳过，没有单独日志。"
                                } else {
                                    "这个步骤当前没有匹配到单独日志。"
                                }
                                Text(
                                    text = emptyLogHint,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = mutedTextColor
                                )
                            } else {
                                stepGroup.entries.forEach { entry ->
                                    val issueSearchHit = remember(entry.entryName, entry.preview) {
                                        buildProjectGitHubWorkflowLogSearchHit(
                                            entry = entry,
                                            queries = PROJECT_GITHUB_WORKFLOW_LOG_SEARCH_PRESETS,
                                            requireAllTerms = false
                                        )
                                    }
                                    val entryExpanded = expandedLogEntries.contains(entry.entryName)
                                    val activeLineNumber = issueSearchHit.matchedLineNumbers.firstOrNull()
                                    ProjectInsetCard(
                                        shape = MurongProjectInsetCardShape,
                                        surfaceColorOverride = chromeColor.copy(
                                            alpha = if (issueSearchHit.hasMatch) 0.28f else 0.14f
                                        ),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                onExpandedLogEntriesChange(
                                                    if (entryExpanded) {
                                                        expandedLogEntries - entry.entryName
                                                    } else {
                                                        expandedLogEntries + entry.entryName
                                                    }
                                                )
                                            }
                                    ) {
                                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text(entry.displayName, style = MaterialTheme.typography.bodyMedium)
                                            Text(
                                                text = buildString {
                                                    append("${entry.totalLineCount} 行")
                                                    if (issueSearchHit.hasMatch) {
                                                        append(" · 已定位异常日志")
                                                    }
                                                },
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (issueSearchHit.hasMatch) {
                                                    MaterialTheme.colorScheme.error
                                                } else {
                                                    mutedTextColor
                                                }
                                            )
                                            ProjectGitHubWorkflowLogPreviewBody(
                                                preview = if (entryExpanded) {
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
                                                expanded = entryExpanded,
                                                activeLineNumber = if (issueSearchHit.hasMatch) activeLineNumber else null,
                                                showTimestamps = showTimestamps,
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                        }
                                    }
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
private fun ProjectGitHubStandaloneWorkflowLogsSection(
    detail: ProjectGitHubWorkflowRunDetailUi,
    initialJobName: String? = null,
    showJobFilters: Boolean = true
) {
    val mutedTextColor = rememberMurongMutedTextColor()
    val scopedJobName = if (showJobFilters) null else initialJobName
    val logGroups = remember(detail.id, detail.jobs, detail.logEntries, scopedJobName) {
        buildProjectGitHubStandaloneWorkflowLogGroups(
            detail = detail,
            scopedJobName = scopedJobName
        )
    }
    var expandedJobNames by remember(detail.id, scopedJobName) {
        mutableStateOf(
            buildProjectGitHubStandaloneDefaultExpandedLogGroups(
                detail = detail,
                scopedJobName = scopedJobName
            )
        )
    }
    var expandedLogEntries by remember(detail.id) {
        mutableStateOf(
            buildProjectGitHubStandaloneDefaultExpandedLogEntries(
                detail = detail,
                selectedJobName = scopedJobName
            )
        )
    }
    ProjectSectionCard(
        shape = MurongProjectSectionCardShape,
        surfaceColorOverride = rememberMurongSurfaceColor().copy(alpha = 0.58f)
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
            Text(
                text = when {
                    !scopedJobName.isNullOrBlank() -> "当前只显示 $scopedJobName 的日志分组。"
                    detail.artifacts.isNotEmpty() -> "运行产物已提前置顶，下面按 Job 折叠查看日志。"
                    else -> "下面按 Job 折叠查看日志，点状态块即可展开。"
                },
                style = MaterialTheme.typography.labelSmall,
                color = mutedTextColor
            )
            if (logGroups.isEmpty()) {
                Text(
                    text = "当前作业范围内没有可显示的日志。",
                    style = MaterialTheme.typography.bodySmall,
                    color = mutedTextColor
                )
            }
            logGroups.forEach { group ->
                val jobExpanded = expandedJobNames.contains(group.key)
                ProjectInsetCard(
                    shape = MurongProjectInsetCardShape,
                    surfaceColorOverride = rememberMurongChromeColor().copy(
                        alpha = if (group.hasIssue || group.isActive) 0.30f else 0.18f
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            expandedJobNames = if (jobExpanded) {
                                expandedJobNames - group.key
                            } else {
                                expandedJobNames + group.key
                            }
                            if (!jobExpanded) {
                                val defaultEntries = buildProjectGitHubStandaloneDefaultExpandedLogEntries(
                                    detail = detail,
                                    selectedJobName = group.jobName
                                )
                                expandedLogEntries = expandedLogEntries + (
                                    if (defaultEntries.isNotEmpty()) {
                                        defaultEntries
                                    } else {
                                        group.entries.firstOrNull()?.entryName?.let(::setOf).orEmpty()
                                    }
                                )
                            }
                        }
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = group.title,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = buildString {
                                append(group.statusLabel)
                                append(" · ${group.entries.size} 个日志文件")
                                group.stepSummary?.takeIf { it.isNotBlank() }?.let {
                                    append(" · $it")
                                }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (group.hasIssue) {
                                MaterialTheme.colorScheme.error
                            } else {
                                mutedTextColor
                            }
                        )
                        Text(
                            text = if (jobExpanded) "点击收起日志" else "点击展开日志",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (jobExpanded) {
                            if (group.entries.isEmpty()) {
                                Text(
                                    text = "这个分组当前没有可显示的日志文件。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = mutedTextColor
                                )
                            } else {
                                group.entries.forEach { entry ->
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
                                        shape = MurongProjectInsetCardShape,
                                        surfaceColorOverride = rememberMurongChromeColor().copy(
                                            alpha = if (issueSearchHit.hasMatch) 0.28f else 0.14f
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
                                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text(entry.displayName, style = MaterialTheme.typography.bodyMedium)
                                            Text(
                                                text = buildString {
                                                    append("${entry.totalLineCount} 行")
                                                    if (issueSearchHit.hasMatch) {
                                                        append(" · 已定位异常日志")
                                                    }
                                                },
                                                style = MaterialTheme.typography.labelSmall,
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
    MurongSecondaryPageFrame(
        title = "${selectedRepo.name} · Issue",
        subtitle = "当前仓库的 Issue 列表。"
        ,
        includeBottomBarPadding = false
    ) {
        ProjectGitHubStandalonePageBody {
            ProjectGitHubStandaloneScrollableContent {
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
    MurongSecondaryPageFrame(
        title = "${selectedRepo.name} · PR",
        subtitle = "当前仓库的 Pull Request 列表。"
        ,
        includeBottomBarPadding = false
    ) {
        ProjectGitHubStandalonePageBody {
            ProjectGitHubStandaloneScrollableContent {
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
}

@Composable
private fun ProjectGitHubStandaloneHeaderCard(
    selectedRepo: ProjectGitHubAccountRepoUi,
    selectedTaskRepo: ProjectGitHubRepoRef?,
    onEditRepoDescription: () -> Unit
) {
    val surfaceColor = rememberMurongSurfaceColor()
    val mutedTextColor = rememberMurongMutedTextColor()
    ProjectSectionCard(
        shape = RoundedCornerShape(20.dp),
        surfaceColorOverride = surfaceColor.copy(alpha = 0.58f)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(selectedRepo.fullName, style = MaterialTheme.typography.titleSmall)
            ProjectInsetCard(
                shape = RoundedCornerShape(16.dp),
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
        shape = RoundedCornerShape(20.dp),
        surfaceColorOverride = rememberMurongSurfaceColor().copy(alpha = 0.58f)
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
    MurongOutlinedActionButton(
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
    val surfaceColor = rememberMurongSurfaceColor()
    val mutedTextColor = rememberMurongMutedTextColor()
    ProjectInsetCard(
        shape = RoundedCornerShape(16.dp),
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
        shape = MurongProjectSectionCardShape,
        surfaceColorOverride = rememberMurongSurfaceColor().copy(alpha = 0.58f)
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
    val mutedTextColor = rememberMurongMutedTextColor()
    ProjectGitHubStandaloneScrollableContent {
        ProjectSectionCard(
            shape = MurongProjectSectionCardShape,
            surfaceColorOverride = rememberMurongChromeColor().copy(alpha = 0.28f)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("可执行工作流", style = MaterialTheme.typography.titleSmall)
                MurongOutlinedActionButton(
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
                shape = MurongProjectInsetCardShape,
                surfaceColorOverride = rememberMurongSurfaceColor().copy(alpha = 0.56f),
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
    onOpenRunDetail: (ProjectGitHubWorkflowRunUi) -> Unit
) {
    val mutedTextColor = rememberMurongMutedTextColor()
    ProjectGitHubStandaloneScrollableContent(bottomPadding = 88.dp) {
        ProjectSectionCard(
            shape = MurongProjectSectionCardShape,
            surfaceColorOverride = rememberMurongChromeColor().copy(alpha = 0.28f)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(workflow.name.ifBlank { workflow.path }, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "这里显示这个工作流的运行记录，点开后继续看日志和作业。",
                    style = MaterialTheme.typography.bodySmall,
                    color = mutedTextColor
                )
            }
        }
        if (isLoading && runs.isEmpty()) {
            ProjectGitHubStandaloneLoadingCard("正在读取该工作流的运行记录...")
        }
        if (!isLoading && runs.isEmpty()) {
            ProjectSectionCard(
                shape = MurongProjectSectionCardShape,
                surfaceColorOverride = rememberMurongSurfaceColor().copy(alpha = 0.58f)
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
                shape = MurongProjectInsetCardShape,
                surfaceColorOverride = rememberMurongSurfaceColor().copy(alpha = 0.56f),
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

private data class ProjectGitHubStandaloneWorkflowLogGroupUi(
    val key: String,
    val jobName: String?,
    val title: String,
    val statusLabel: String,
    val stepSummary: String?,
    val hasIssue: Boolean,
    val isActive: Boolean,
    val entries: List<ProjectGitHubWorkflowLogEntryUi>
)

private data class ProjectGitHubStandaloneWorkflowStepLogGroupUi(
    val key: String,
    val step: ProjectGitHubWorkflowStepUi?,
    val title: String,
    val subtitle: String,
    val hasIssue: Boolean,
    val isActive: Boolean,
    val entries: List<ProjectGitHubWorkflowLogEntryUi>
)

private fun buildProjectGitHubStandaloneWorkflowStepLogGroups(
    detail: ProjectGitHubWorkflowRunDetailUi,
    job: ProjectGitHubWorkflowJobUi
): List<ProjectGitHubStandaloneWorkflowStepLogGroupUi> {
    val jobEntries = detail.logEntries.filter { entry ->
        projectGitHubLogMatchesJob(entry, job.name)
    }
    val matchedEntryNames = mutableSetOf<String>()
    val activeStepName = buildProjectGitHubStandaloneActiveStepName(job)
    val groups = job.steps.map { step ->
        val stepEntries = jobEntries.filter { entry ->
            projectGitHubLogMatchesStep(
                entry = entry,
                stepName = step.name,
                stepNumber = step.number
            )
        }
        matchedEntryNames += stepEntries.map { it.entryName }
        ProjectGitHubStandaloneWorkflowStepLogGroupUi(
            key = "job:${job.name}:step:${step.number}",
            step = step,
            title = "${step.number}. ${step.name}",
            subtitle = "${step.statusLabel} · ${step.durationLabel}",
            hasIssue = step.hasIssue,
            isActive = activeStepName == step.name,
            entries = stepEntries
        )
    }
    val otherEntries = jobEntries.filterNot { entry -> matchedEntryNames.contains(entry.entryName) }
    return if (otherEntries.isEmpty()) {
        groups
    } else {
        groups + ProjectGitHubStandaloneWorkflowStepLogGroupUi(
            key = "job:${job.name}:other",
            step = null,
            title = "其他日志",
            subtitle = buildString {
                append("未匹配到具体步骤")
                otherEntries.firstOrNull()?.displayName?.takeIf { it.isNotBlank() }?.let { firstName ->
                    append(" · 来源 $firstName")
                }
            },
            hasIssue = false,
            isActive = false,
            entries = otherEntries
        )
    }
}

private fun buildProjectGitHubStandaloneDefaultExpandedStepKeys(
    detail: ProjectGitHubWorkflowRunDetailUi
): Set<String> {
    val keys = linkedSetOf<String>()
    detail.jobs.forEach { job ->
        if (job.hasIssue) {
            job.steps
                .filter { it.hasIssue }
                .forEach { step ->
                    keys += "job:${job.name}:step:${step.number}"
                }
        } else if (!job.status.equals("completed", ignoreCase = true)) {
            buildProjectGitHubStandaloneActiveStepName(job)?.let { activeStepName ->
                job.steps.firstOrNull { it.name == activeStepName }?.let { step ->
                    keys += "job:${job.name}:step:${step.number}"
                }
            }
        }
    }
    return keys
}

private fun buildProjectGitHubStandaloneActiveStepName(
    job: ProjectGitHubWorkflowJobUi
): String? {
    return job.steps.firstOrNull { !it.status.equals("completed", ignoreCase = true) }?.name
}

private fun buildProjectGitHubStandaloneWorkflowLogGroups(
    detail: ProjectGitHubWorkflowRunDetailUi,
    scopedJobName: String?
): List<ProjectGitHubStandaloneWorkflowLogGroupUi> {
    val activeJobName = buildProjectGitHubStandaloneActiveJobName(detail)
    val jobGroups = detail.jobs
        .filter { scopedJobName.isNullOrBlank() || it.name == scopedJobName }
        .map { job ->
            ProjectGitHubStandaloneWorkflowLogGroupUi(
                key = "job:${job.name}",
                jobName = job.name,
                title = job.name,
                statusLabel = job.statusLabel,
                stepSummary = job.failedSteps
                    .take(2)
                    .joinToString("、") { step -> step.name }
                    .takeIf { it.isNotBlank() },
                hasIssue = job.hasIssue,
                isActive = activeJobName == job.name,
                entries = detail.logEntries.filter { entry ->
                    projectGitHubLogMatchesJob(entry, job.name)
                }
            )
        }
    val unmatchedEntries = detail.logEntries.filter { entry ->
        detail.jobs.none { job -> projectGitHubLogMatchesJob(entry, job.name) }
    }
    val otherGroup = if (unmatchedEntries.isNotEmpty() && scopedJobName.isNullOrBlank()) {
        listOf(
            ProjectGitHubStandaloneWorkflowLogGroupUi(
                key = "other",
                jobName = null,
                title = "其他日志",
                statusLabel = buildString {
                    append("未匹配到具体 Job")
                    unmatchedEntries.firstOrNull()?.displayName?.takeIf { it.isNotBlank() }?.let { firstName ->
                        append(" · 来源 $firstName")
                    }
                },
                stepSummary = null,
                hasIssue = false,
                isActive = false,
                entries = unmatchedEntries
            )
        )
    } else {
        emptyList()
    }
    return (jobGroups + otherGroup).sortedWith(
        compareByDescending<ProjectGitHubStandaloneWorkflowLogGroupUi> { it.hasIssue }
            .thenByDescending { it.isActive }
            .thenBy { it.title }
    )
}

private fun buildProjectGitHubStandaloneActiveJobName(
    detail: ProjectGitHubWorkflowRunDetailUi
): String? {
    return detail.jobs.firstOrNull { !it.status.equals("completed", ignoreCase = true) }?.name
}

private fun buildProjectGitHubStandaloneDefaultExpandedLogGroups(
    detail: ProjectGitHubWorkflowRunDetailUi,
    scopedJobName: String?
): Set<String> {
    if (!scopedJobName.isNullOrBlank()) {
        return setOf("job:$scopedJobName")
    }
    if (detail.hasIssue) {
        val issueGroups = detail.jobs
            .filter { it.hasIssue }
            .map { "job:${it.name}" }
            .toSet()
        if (issueGroups.isNotEmpty()) return issueGroups
    }
    if (!detail.status.equals("completed", ignoreCase = true)) {
        buildProjectGitHubStandaloneActiveJobName(detail)?.let { activeJobName ->
            return setOf("job:$activeJobName")
        }
    }
    return emptySet()
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
