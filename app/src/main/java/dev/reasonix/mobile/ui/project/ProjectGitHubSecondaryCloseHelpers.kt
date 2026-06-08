package dev.reasonix.mobile.ui.project

internal enum class ProjectGitHubSecondaryCloseLayer {
    NONE,
    WORKSPACE_WORKBENCH_TAB,
    STANDALONE_NAVIGATION,
    WORKSPACE_NAVIGATION
}

internal fun resolveProjectGitHubSecondaryCloseLayer(
    activeProjectPath: String?,
    workspaceNavigationState: ProjectGitHubWorkspaceNavigationState,
    standaloneNavigationState: ProjectGitHubStandaloneNavigationState
): ProjectGitHubSecondaryCloseLayer {
    return when {
        workspaceNavigationState.workbenchRepoRoot != null &&
            workspaceNavigationState.workbenchSelectedTab != ProjectGitHubWorkspaceRepoWorkbenchTab.OVERVIEW -> {
            ProjectGitHubSecondaryCloseLayer.WORKSPACE_WORKBENCH_TAB
        }

        isProjectGitHubStandaloneSecondaryPage(
            activeProjectPath = activeProjectPath,
            currentState = standaloneNavigationState
        ) -> {
            ProjectGitHubSecondaryCloseLayer.STANDALONE_NAVIGATION
        }

        isProjectGitHubWorkspaceSecondaryPage(workspaceNavigationState) -> {
            ProjectGitHubSecondaryCloseLayer.WORKSPACE_NAVIGATION
        }

        else -> ProjectGitHubSecondaryCloseLayer.NONE
    }
}
