package com.murong.agent.ui.project

internal enum class ProjectGitHubForegroundCloseLayer {
    NONE,
    PENDING_REMOTE_FILE_SAVE_CONFIRMATION,
    VIEWER_DESCRIPTION_EDITOR,
    VIEWER_REPOSITORY_MENU,
    SUSPECTED_REPO_MENU,
    GLOBAL_SEARCH,
    GLOBAL_TASK_CENTER,
    WORKFLOW_DISPATCH,
    WORKFLOW_RUN_DETAIL,
    ARTIFACT_DIALOG,
    RELEASE_ASSET_DIALOG,
    REMOTE_FILE_DIALOG,
    PULL_REQUEST_DETAIL,
    ISSUE_DETAIL,
    DIFF_PREVIEW,
    CREATE_PULL_REQUEST,
    CREATE_ISSUE,
    CREATE_OR_EDIT_RELEASE,
    CREATE_GITHUB_REPOSITORY,
    INIT_GIT,
    BRANCH_DIALOG,
    COMMIT_DIALOG
}

internal data class ProjectGitHubForegroundCloseState(
    val hasPendingRemoteFileSaveConfirmation: Boolean = false,
    val hasViewerDescriptionEditor: Boolean = false,
    val hasViewerRepositoryMenu: Boolean = false,
    val hasSuspectedRepoMenu: Boolean = false,
    val isGlobalSearchVisible: Boolean = false,
    val isGlobalTaskCenterVisible: Boolean = false,
    val hasWorkflowDispatch: Boolean = false,
    val hasWorkflowRunDetail: Boolean = false,
    val hasArtifactDialog: Boolean = false,
    val hasReleaseAssetDialog: Boolean = false,
    val hasRemoteFileDialog: Boolean = false,
    val hasPullRequestDetail: Boolean = false,
    val hasIssueDetail: Boolean = false,
    val hasDiffPreview: Boolean = false,
    val hasCreatePullRequestDialog: Boolean = false,
    val hasCreateIssueDialog: Boolean = false,
    val hasCreateOrEditReleaseDialog: Boolean = false,
    val hasCreateGitHubRepositoryDialog: Boolean = false,
    val hasInitGitDialog: Boolean = false,
    val hasBranchDialog: Boolean = false,
    val hasCommitDialog: Boolean = false
)

internal fun resolveProjectGitHubForegroundCloseLayer(
    currentState: ProjectGitHubForegroundCloseState
): ProjectGitHubForegroundCloseLayer {
    return when {
        currentState.hasPendingRemoteFileSaveConfirmation -> {
            ProjectGitHubForegroundCloseLayer.PENDING_REMOTE_FILE_SAVE_CONFIRMATION
        }

        currentState.hasViewerDescriptionEditor -> {
            ProjectGitHubForegroundCloseLayer.VIEWER_DESCRIPTION_EDITOR
        }

        currentState.hasViewerRepositoryMenu -> {
            ProjectGitHubForegroundCloseLayer.VIEWER_REPOSITORY_MENU
        }

        currentState.hasSuspectedRepoMenu -> {
            ProjectGitHubForegroundCloseLayer.SUSPECTED_REPO_MENU
        }

        currentState.isGlobalSearchVisible -> {
            ProjectGitHubForegroundCloseLayer.GLOBAL_SEARCH
        }

        currentState.isGlobalTaskCenterVisible -> {
            ProjectGitHubForegroundCloseLayer.GLOBAL_TASK_CENTER
        }

        currentState.hasWorkflowDispatch -> {
            ProjectGitHubForegroundCloseLayer.WORKFLOW_DISPATCH
        }

        currentState.hasWorkflowRunDetail -> {
            ProjectGitHubForegroundCloseLayer.WORKFLOW_RUN_DETAIL
        }

        currentState.hasArtifactDialog -> {
            ProjectGitHubForegroundCloseLayer.ARTIFACT_DIALOG
        }

        currentState.hasReleaseAssetDialog -> {
            ProjectGitHubForegroundCloseLayer.RELEASE_ASSET_DIALOG
        }

        currentState.hasRemoteFileDialog -> {
            ProjectGitHubForegroundCloseLayer.REMOTE_FILE_DIALOG
        }

        currentState.hasPullRequestDetail -> {
            ProjectGitHubForegroundCloseLayer.PULL_REQUEST_DETAIL
        }

        currentState.hasIssueDetail -> {
            ProjectGitHubForegroundCloseLayer.ISSUE_DETAIL
        }

        currentState.hasDiffPreview -> {
            ProjectGitHubForegroundCloseLayer.DIFF_PREVIEW
        }

        currentState.hasCreatePullRequestDialog -> {
            ProjectGitHubForegroundCloseLayer.CREATE_PULL_REQUEST
        }

        currentState.hasCreateIssueDialog -> {
            ProjectGitHubForegroundCloseLayer.CREATE_ISSUE
        }

        currentState.hasCreateOrEditReleaseDialog -> {
            ProjectGitHubForegroundCloseLayer.CREATE_OR_EDIT_RELEASE
        }

        currentState.hasCreateGitHubRepositoryDialog -> {
            ProjectGitHubForegroundCloseLayer.CREATE_GITHUB_REPOSITORY
        }

        currentState.hasInitGitDialog -> {
            ProjectGitHubForegroundCloseLayer.INIT_GIT
        }

        currentState.hasBranchDialog -> {
            ProjectGitHubForegroundCloseLayer.BRANCH_DIALOG
        }

        currentState.hasCommitDialog -> {
            ProjectGitHubForegroundCloseLayer.COMMIT_DIALOG
        }

        else -> ProjectGitHubForegroundCloseLayer.NONE
    }
}
