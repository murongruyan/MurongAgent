package dev.reasonix.mobile.ui.project

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment

internal data class ProjectGitHubWorkflowActionResult(
    val feedbackMessage: String? = null,
    val updatedDetail: ProjectGitHubWorkflowRunDetailUi? = null,
    val downloadRecord: ProjectGitHubWorkflowDownloadRecord? = null
)

internal data class ProjectGitHubWorkflowDownloadRecord(
    val typeLabel: String,
    val title: String,
    val fileName: String,
    val downloadId: Long,
    val sourceUrl: String?,
    val repo: ProjectGitHubRepoRef
)

internal suspend fun refreshProjectGitHubWorkflowRunDetailAction(
    currentDetail: ProjectGitHubWorkflowRunDetailUi,
    repo: ProjectGitHubRepoRef?,
    token: String,
    apiBaseUrl: String
): ProjectGitHubWorkflowActionResult {
    if (repo == null) return ProjectGitHubWorkflowActionResult()
    if (token.isBlank()) {
        return ProjectGitHubWorkflowActionResult(
            feedbackMessage = "请先在设置页填写 GitHub Token。"
        )
    }
    val result = loadProjectGitHubWorkflowRunDetail(
        repo = repo,
        runId = currentDetail.id,
        token = token,
        apiBaseUrl = apiBaseUrl
    )
    return if (result.success) {
        ProjectGitHubWorkflowActionResult(updatedDetail = result.detail)
    } else {
        ProjectGitHubWorkflowActionResult(
            feedbackMessage = result.error ?: "刷新工作流运行详情失败"
        )
    }
}

internal fun enqueueProjectGitHubWorkflowLogsDownloadAction(
    context: Context,
    repo: ProjectGitHubRepoRef?,
    runId: Long,
    runDisplayTitle: String,
    sourceUrl: String?,
    token: String,
    apiBaseUrl: String
): ProjectGitHubWorkflowActionResult {
    if (repo == null) return ProjectGitHubWorkflowActionResult()
    if (token.isBlank()) {
        return ProjectGitHubWorkflowActionResult(
            feedbackMessage = "请先在设置页填写 GitHub Token。"
        )
    }
    val result = enqueueProjectGitHubWorkflowLogsDownload(
        context = context,
        repo = repo,
        runId = runId,
        runDisplayTitle = runDisplayTitle,
        token = token,
        apiBaseUrl = apiBaseUrl
    )
    return ProjectGitHubWorkflowActionResult(
        feedbackMessage = "已开始下载 ${result.fileName}",
        downloadRecord = ProjectGitHubWorkflowDownloadRecord(
            typeLabel = "工作流日志",
            title = runDisplayTitle,
            fileName = result.fileName,
            downloadId = result.downloadId,
            sourceUrl = sourceUrl,
            repo = repo
        )
    )
}

internal fun enqueueProjectGitHubWorkflowArtifactDownloadAction(
    context: Context,
    repo: ProjectGitHubRepoRef?,
    artifact: ProjectGitHubArtifactUi,
    sourceUrl: String?,
    token: String
): ProjectGitHubWorkflowActionResult {
    if (repo == null) return ProjectGitHubWorkflowActionResult()
    if (token.isBlank()) {
        return ProjectGitHubWorkflowActionResult(
            feedbackMessage = "请先在设置页填写 GitHub Token。"
        )
    }
    val result = enqueueProjectGitHubArtifactDownload(
        context = context,
        repo = repo,
        artifact = artifact,
        token = token
    )
    return ProjectGitHubWorkflowActionResult(
        feedbackMessage = "已开始下载 ${result.fileName}",
        downloadRecord = ProjectGitHubWorkflowDownloadRecord(
            typeLabel = "工作流产物",
            title = artifact.name,
            fileName = result.fileName,
            downloadId = result.downloadId,
            sourceUrl = sourceUrl,
            repo = repo
        )
    )
}

internal fun enqueueProjectGitHubArtifactDownload(
    context: Context,
    repo: ProjectGitHubRepoRef,
    artifact: ProjectGitHubArtifactUi,
    token: String
): ProjectDownloadEnqueueResult {
    val fileName = sanitizeProjectDownloadFileName("${repo.repo}-${artifact.name}.zip")
    val request = DownloadManager.Request(Uri.parse(artifact.archiveDownloadUrl))
        .setTitle(artifact.name)
        .setDescription("${repo.owner}/${repo.repo}")
        .addRequestHeader("Authorization", "Bearer $token")
        .addRequestHeader("Accept", "application/vnd.github+json")
        .setMimeType("application/zip")
        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        .setAllowedOverMetered(true)
        .setAllowedOverRoaming(true)
    runCatching {
        request.setDestinationInExternalPublicDir(
            Environment.DIRECTORY_DOWNLOADS,
            "MurongAgent/$fileName"
        )
    }.getOrElse {
        request.setDestinationInExternalFilesDir(
            context,
            Environment.DIRECTORY_DOWNLOADS,
            fileName
        )
    }
    val manager = context.getSystemService(DownloadManager::class.java)
        ?: error("系统下载服务不可用")
    val downloadId = manager.enqueue(request)
    return ProjectDownloadEnqueueResult(downloadId = downloadId, fileName = fileName)
}

internal fun enqueueProjectGitHubWorkflowLogsDownload(
    context: Context,
    repo: ProjectGitHubRepoRef,
    runId: Long,
    runDisplayTitle: String,
    token: String,
    apiBaseUrl: String
): ProjectDownloadEnqueueResult {
    val fileName = sanitizeProjectDownloadFileName("${repo.repo}-run-$runId-logs.zip")
    val request = DownloadManager.Request(
        Uri.parse(
            buildProjectGitHubApiUrl(
                apiBaseUrl,
                "/repos/${repo.owner}/${repo.repo}/actions/runs/$runId/logs"
            )
        )
    )
        .setTitle("$runDisplayTitle 日志")
        .setDescription("${repo.owner}/${repo.repo}")
        .addRequestHeader("Authorization", "Bearer $token")
        .addRequestHeader("Accept", "application/vnd.github+json")
        .setMimeType("application/zip")
        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        .setAllowedOverMetered(true)
        .setAllowedOverRoaming(true)
    runCatching {
        request.setDestinationInExternalPublicDir(
            Environment.DIRECTORY_DOWNLOADS,
            "MurongAgent/$fileName"
        )
    }.getOrElse {
        request.setDestinationInExternalFilesDir(
            context,
            Environment.DIRECTORY_DOWNLOADS,
            fileName
        )
    }
    val manager = context.getSystemService(DownloadManager::class.java)
        ?: error("系统下载服务不可用")
    val downloadId = manager.enqueue(request)
    return ProjectDownloadEnqueueResult(downloadId = downloadId, fileName = fileName)
}
