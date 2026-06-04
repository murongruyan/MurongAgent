package dev.reasonix.mobile.ui.project

internal data class ProjectGitHubWorkspaceNavigationState(
    val isVisible: Boolean = false,
    val isDownloadCenterVisible: Boolean = false,
    val workbenchRepoRoot: String? = null,
    val workbenchSelectedTab: ProjectGitHubWorkspaceRepoWorkbenchTab =
        ProjectGitHubWorkspaceRepoWorkbenchTab.OVERVIEW
)

internal fun clearProjectGitHubWorkspaceNavigationState():
    ProjectGitHubWorkspaceNavigationState {
    return ProjectGitHubWorkspaceNavigationState()
}

internal fun openProjectGitHubWorkspaceRepoWorkbench(
    currentState: ProjectGitHubWorkspaceNavigationState,
    rootPath: String,
    selectedTab: ProjectGitHubWorkspaceRepoWorkbenchTab =
        ProjectGitHubWorkspaceRepoWorkbenchTab.OVERVIEW
): ProjectGitHubWorkspaceNavigationState {
    return currentState.copy(
        isVisible = true,
        isDownloadCenterVisible = false,
        workbenchRepoRoot = rootPath,
        workbenchSelectedTab = selectedTab
    )
}

internal fun openProjectGitHubWorkspaceDownloadCenter(
    currentState: ProjectGitHubWorkspaceNavigationState
): ProjectGitHubWorkspaceNavigationState {
    return currentState.copy(
        isVisible = true,
        isDownloadCenterVisible = true,
        workbenchRepoRoot = null,
        workbenchSelectedTab = ProjectGitHubWorkspaceRepoWorkbenchTab.OVERVIEW
    )
}

internal fun closeProjectGitHubWorkspaceRepoWorkbench(
    currentState: ProjectGitHubWorkspaceNavigationState
): ProjectGitHubWorkspaceNavigationState {
    return currentState.copy(
        workbenchRepoRoot = null,
        workbenchSelectedTab = ProjectGitHubWorkspaceRepoWorkbenchTab.OVERVIEW
    )
}

internal fun closeProjectGitHubWorkspaceNavigationLayer(
    currentState: ProjectGitHubWorkspaceNavigationState
): ProjectGitHubWorkspaceNavigationState {
    return when {
        currentState.workbenchRepoRoot != null -> {
            currentState.copy(
                workbenchRepoRoot = null,
                workbenchSelectedTab = ProjectGitHubWorkspaceRepoWorkbenchTab.OVERVIEW
            )
        }

        currentState.isDownloadCenterVisible -> {
            currentState.copy(isDownloadCenterVisible = false)
        }

        else -> {
            clearProjectGitHubWorkspaceNavigationState()
        }
    }
}

internal fun normalizeProjectGitHubWorkspaceNavigationState(
    currentState: ProjectGitHubWorkspaceNavigationState,
    detectedRepos: List<ProjectDetectedRepoUi>
): ProjectGitHubWorkspaceNavigationState {
    val workbenchRepoRoot = currentState.workbenchRepoRoot ?: return currentState
    if (detectedRepos.any { it.rootPath == workbenchRepoRoot }) return currentState
    return currentState.copy(
        workbenchRepoRoot = null,
        workbenchSelectedTab = ProjectGitHubWorkspaceRepoWorkbenchTab.OVERVIEW
    )
}
