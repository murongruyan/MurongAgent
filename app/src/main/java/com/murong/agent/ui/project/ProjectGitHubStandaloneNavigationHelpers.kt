package com.murong.agent.ui.project

internal data class ProjectGitHubStandaloneNavigationState(
    val selectedRepo: ProjectGitHubAccountRepoUi? = null,
    val selectedSection: ProjectGitHubStandaloneSection =
        ProjectGitHubStandaloneSection.OVERVIEW
)

internal sealed interface ProjectGitHubStandaloneNavigationAction {
    data class SelectRepo(
        val repo: ProjectGitHubAccountRepoUi
    ) : ProjectGitHubStandaloneNavigationAction

    data class SyncSelectedRepo(
        val repo: ProjectGitHubAccountRepoUi?
    ) : ProjectGitHubStandaloneNavigationAction

    data class SelectSection(
        val section: ProjectGitHubStandaloneSection
    ) : ProjectGitHubStandaloneNavigationAction

    data object CloseSecondaryLayer : ProjectGitHubStandaloneNavigationAction

    data object ClearNavigation : ProjectGitHubStandaloneNavigationAction
}

internal fun clearProjectGitHubStandaloneNavigationState():
    ProjectGitHubStandaloneNavigationState {
    return ProjectGitHubStandaloneNavigationState()
}

internal fun reduceProjectGitHubStandaloneNavigationState(
    currentState: ProjectGitHubStandaloneNavigationState,
    action: ProjectGitHubStandaloneNavigationAction
): ProjectGitHubStandaloneNavigationState {
    return when (action) {
        is ProjectGitHubStandaloneNavigationAction.SelectRepo -> {
            currentState.copy(
                selectedRepo = action.repo,
                selectedSection = ProjectGitHubStandaloneSection.OVERVIEW
            )
        }

        is ProjectGitHubStandaloneNavigationAction.SyncSelectedRepo -> {
            action.repo?.let { repo ->
                currentState.copy(selectedRepo = repo)
            } ?: clearProjectGitHubStandaloneNavigationState()
        }

        is ProjectGitHubStandaloneNavigationAction.SelectSection -> {
            currentState.copy(selectedSection = action.section)
        }

        ProjectGitHubStandaloneNavigationAction.CloseSecondaryLayer -> {
            when {
                currentState.selectedSection != ProjectGitHubStandaloneSection.OVERVIEW -> {
                    currentState.copy(
                        selectedSection = ProjectGitHubStandaloneSection.OVERVIEW
                    )
                }

                else -> {
                    clearProjectGitHubStandaloneNavigationState()
                }
            }
        }

        ProjectGitHubStandaloneNavigationAction.ClearNavigation -> {
            clearProjectGitHubStandaloneNavigationState()
        }
    }
}

internal fun isProjectGitHubStandaloneSecondaryPage(
    activeProjectPath: String?,
    currentState: ProjectGitHubStandaloneNavigationState
): Boolean {
    return activeProjectPath.isNullOrBlank() && currentState.selectedRepo != null
}

internal fun resolveProjectGitHubStandaloneCloseRequest(
    activeProjectPath: String?,
    currentState: ProjectGitHubStandaloneNavigationState,
    nestedCloseRequest: (() -> Unit)?,
    fallbackCloseRequest: () -> Unit
): (() -> Unit)? {
    if (!isProjectGitHubStandaloneSecondaryPage(activeProjectPath, currentState)) {
        return null
    }
    return nestedCloseRequest ?: fallbackCloseRequest
}
