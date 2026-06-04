package dev.reasonix.mobile.ui.project

import android.net.Uri
import android.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

private val PROJECT_GITHUB_JSON = Json { ignoreUnknownKeys = true; isLenient = true }
private val PROJECT_GITHUB_HTTP = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .writeTimeout(30, TimeUnit.SECONDS)
    .build()

internal fun loadProjectGitHubActions(
    repo: ProjectGitHubRepoRef,
    token: String,
    apiBaseUrl: String
): ProjectGitHubActionsState {
    var state = ProjectGitHubActionsState.empty(repo)
    val viewerResult = runProjectGitHubApiRequest(
        apiBaseUrl = apiBaseUrl,
        token = token,
        path = "/user"
    )
    if (!viewerResult.success) {
        return state.copy(errorMessage = viewerResult.error ?: "GitHub 登录状态校验失败")
    }
    parseProjectGitHubJsonObject(viewerResult.body)?.let { viewer ->
        state = state.copy(
            viewerLogin = viewer.string("login"),
            viewerName = viewer.string("name")
        )
    }
    val repoResult = runProjectGitHubApiRequest(
        apiBaseUrl = apiBaseUrl,
        token = token,
        path = "/repos/${repo.owner}/${repo.repo}"
    )
    if (!repoResult.success) {
        return state.copy(errorMessage = repoResult.error ?: "读取 GitHub 仓库信息失败")
    }
    parseProjectGitHubJsonObject(repoResult.body)?.let { repoObject ->
        state = state.copy(
            defaultBranch = repoObject.string("default_branch"),
            repoHtmlUrl = repoObject.string("html_url")
        )
    }
    val workflowsResult = runProjectGitHubApiRequest(
        apiBaseUrl = apiBaseUrl,
        token = token,
        path = "/repos/${repo.owner}/${repo.repo}/actions/workflows?per_page=50"
    )
    if (!workflowsResult.success) {
        return state.copy(errorMessage = workflowsResult.error ?: "读取工作流列表失败")
    }
    val workflows = parseProjectGitHubJsonObject(workflowsResult.body)
        ?.jsonArrayOrEmpty("workflows")
        ?.mapNotNull { item ->
            val obj = item.jsonObjectOrNull() ?: return@mapNotNull null
            ProjectGitHubWorkflowUi(
                id = obj.long("id") ?: return@mapNotNull null,
                name = obj.string("name").orEmpty(),
                path = obj.string("path").orEmpty(),
                state = obj.string("state").orEmpty(),
                htmlUrl = obj.string("html_url")
            )
        }
        .orEmpty()
    val runsResult = runProjectGitHubApiRequest(
        apiBaseUrl = apiBaseUrl,
        token = token,
        path = "/repos/${repo.owner}/${repo.repo}/actions/runs?per_page=12"
    )
    if (!runsResult.success) {
        return state.copy(
            workflows = workflows,
            errorMessage = runsResult.error ?: "读取工作流运行记录失败"
        )
    }
    val runs = parseProjectGitHubJsonObject(runsResult.body)
        ?.jsonArrayOrEmpty("workflow_runs")
        ?.mapNotNull { item ->
            val obj = item.jsonObjectOrNull() ?: return@mapNotNull null
            ProjectGitHubWorkflowRunUi(
                id = obj.long("id") ?: return@mapNotNull null,
                name = obj.string("name").orEmpty(),
                displayTitle = obj.string("display_title").orEmpty(),
                headBranch = obj.string("head_branch").orEmpty(),
                status = obj.string("status").orEmpty(),
                conclusion = obj.string("conclusion"),
                event = obj.string("event").orEmpty(),
                runNumber = obj.long("run_number") ?: 0L,
                updatedAt = obj.string("updated_at").orEmpty(),
                htmlUrl = obj.string("html_url")
            )
        }
        .orEmpty()
    val releasesResult = runProjectGitHubApiRequest(
        apiBaseUrl = apiBaseUrl,
        token = token,
        path = "/repos/${repo.owner}/${repo.repo}/releases?per_page=10"
    )
    if (!releasesResult.success) {
        return state.copy(
            workflows = workflows,
            recentRuns = runs,
            errorMessage = releasesResult.error ?: "读取 Release 列表失败"
        )
    }
    val releases = parseProjectGitHubJsonArray(releasesResult.body)
        ?.mapNotNull { item ->
            val obj = item.jsonObjectOrNull() ?: return@mapNotNull null
            ProjectGitHubReleaseUi(
                id = obj.long("id") ?: return@mapNotNull null,
                tagName = obj.string("tag_name").orEmpty(),
                name = obj.string("name").orEmpty(),
                body = obj.string("body").orEmpty(),
                isDraft = obj.boolean("draft") ?: false,
                isPrerelease = obj.boolean("prerelease") ?: false,
                publishedAt = obj.string("published_at").orEmpty(),
                htmlUrl = obj.string("html_url"),
                assets = obj.jsonArrayOrEmpty("assets").mapNotNull { assetItem ->
                    val assetObj = assetItem.jsonObjectOrNull() ?: return@mapNotNull null
                    ProjectGitHubReleaseAssetUi(
                        id = assetObj.long("id") ?: return@mapNotNull null,
                        name = assetObj.string("name").orEmpty(),
                        sizeInBytes = assetObj.long("size") ?: 0L,
                        apiUrl = assetObj.string("url").orEmpty(),
                        browserDownloadUrl = assetObj.string("browser_download_url"),
                        updatedAt = assetObj.string("updated_at").orEmpty()
                    )
                }
            )
        }
        .orEmpty()
    val issuesResult = runProjectGitHubApiRequest(
        apiBaseUrl = apiBaseUrl,
        token = token,
        path = "/repos/${repo.owner}/${repo.repo}/issues?state=all&per_page=20&sort=updated&direction=desc"
    )
    if (!issuesResult.success) {
        return state.copy(
            workflows = workflows,
            recentRuns = runs,
            releases = releases,
            errorMessage = issuesResult.error ?: "读取 Issue 列表失败"
        )
    }
    val issues = parseProjectGitHubJsonArray(issuesResult.body)
        ?.mapNotNull { item ->
            val obj = item.jsonObjectOrNull() ?: return@mapNotNull null
            if (obj.jsonObjectOrNull("pull_request") != null) return@mapNotNull null
            parseProjectGitHubIssue(obj)
        }
        .orEmpty()
    val pullRequestsResult = runProjectGitHubApiRequest(
        apiBaseUrl = apiBaseUrl,
        token = token,
        path = "/repos/${repo.owner}/${repo.repo}/pulls?state=all&per_page=20&sort=updated&direction=desc"
    )
    if (!pullRequestsResult.success) {
        return state.copy(
            workflows = workflows,
            recentRuns = runs,
            releases = releases,
            issues = issues,
            errorMessage = pullRequestsResult.error ?: "读取 Pull Request 列表失败"
        )
    }
    val pullRequests = parseProjectGitHubJsonArray(pullRequestsResult.body)
        ?.mapNotNull { item ->
            val obj = item.jsonObjectOrNull() ?: return@mapNotNull null
            parseProjectGitHubPullRequest(obj)
        }
        .orEmpty()
    return state.copy(
        workflows = workflows,
        recentRuns = runs,
        releases = releases,
        issues = issues,
        pullRequests = pullRequests,
        errorMessage = null
    )
}

internal fun dispatchProjectGitHubWorkflow(
    repo: ProjectGitHubRepoRef,
    workflowId: Long,
    ref: String,
    token: String,
    apiBaseUrl: String
): ProjectGitHubCommandResult {
    val body = buildJsonObject {
        put("ref", ref)
    }.toString()
    val result = runProjectGitHubApiRequest(
        apiBaseUrl = apiBaseUrl,
        token = token,
        path = "/repos/${repo.owner}/${repo.repo}/actions/workflows/$workflowId/dispatches",
        method = "POST",
        jsonBody = body,
        allowedCodes = setOf(204)
    )
    return if (result.success) {
        ProjectGitHubCommandResult(
            success = true,
            message = "已触发工作流，GitHub 会开始排队执行。"
        )
    } else {
        ProjectGitHubCommandResult(
            success = false,
            message = "",
            error = result.error ?: "触发工作流失败"
        )
    }
}

internal fun loadProjectGitHubArtifacts(
    repo: ProjectGitHubRepoRef,
    runId: Long,
    token: String,
    apiBaseUrl: String
): ProjectGitHubArtifactLoadResult {
    val result = runProjectGitHubApiRequest(
        apiBaseUrl = apiBaseUrl,
        token = token,
        path = "/repos/${repo.owner}/${repo.repo}/actions/runs/$runId/artifacts?per_page=30"
    )
    if (!result.success) {
        return ProjectGitHubArtifactLoadResult(
            success = false,
            artifacts = emptyList(),
            error = result.error ?: "读取工作流产物失败"
        )
    }
    val artifacts = parseProjectGitHubJsonObject(result.body)
        ?.jsonArrayOrEmpty("artifacts")
        ?.mapNotNull { item ->
            val obj = item.jsonObjectOrNull() ?: return@mapNotNull null
            ProjectGitHubArtifactUi(
                id = obj.long("id") ?: return@mapNotNull null,
                name = obj.string("name").orEmpty(),
                sizeInBytes = obj.long("size_in_bytes") ?: 0L,
                archiveDownloadUrl = obj.string("archive_download_url").orEmpty(),
                expired = obj.boolean("expired") ?: false,
                updatedAt = obj.string("updated_at").orEmpty()
            )
        }
        .orEmpty()
    return ProjectGitHubArtifactLoadResult(
        success = true,
        artifacts = artifacts,
        error = null
    )
}

internal fun loadProjectGitHubWorkflowRunDetail(
    repo: ProjectGitHubRepoRef,
    runId: Long,
    token: String,
    apiBaseUrl: String
): ProjectGitHubWorkflowRunDetailLoadResult {
    val runResult = runProjectGitHubApiRequest(
        apiBaseUrl = apiBaseUrl,
        token = token,
        path = "/repos/${repo.owner}/${repo.repo}/actions/runs/$runId"
    )
    if (!runResult.success) {
        return ProjectGitHubWorkflowRunDetailLoadResult(
            success = false,
            detail = null,
            error = runResult.error ?: "读取工作流运行详情失败"
        )
    }
    val jobsResult = runProjectGitHubApiRequest(
        apiBaseUrl = apiBaseUrl,
        token = token,
        path = "/repos/${repo.owner}/${repo.repo}/actions/runs/$runId/jobs?per_page=100"
    )
    if (!jobsResult.success) {
        return ProjectGitHubWorkflowRunDetailLoadResult(
            success = false,
            detail = null,
            error = jobsResult.error ?: "读取工作流 job 详情失败"
        )
    }
    val artifactsResult = loadProjectGitHubArtifacts(
        repo = repo,
        runId = runId,
        token = token,
        apiBaseUrl = apiBaseUrl
    )
    val logsResult = loadProjectGitHubWorkflowRunLogsPreview(
        repo = repo,
        runId = runId,
        token = token,
        apiBaseUrl = apiBaseUrl
    )
    val runObject = parseProjectGitHubJsonObject(runResult.body)
        ?: return ProjectGitHubWorkflowRunDetailLoadResult(
            success = false,
            detail = null,
            error = "GitHub 返回的运行详情无法解析"
        )
    val jobs = parseProjectGitHubJsonObject(jobsResult.body)
        ?.jsonArrayOrEmpty("jobs")
        ?.mapNotNull { item ->
            val obj = item.jsonObjectOrNull() ?: return@mapNotNull null
            ProjectGitHubWorkflowJobUi(
                id = obj.long("id") ?: return@mapNotNull null,
                name = obj.string("name").orEmpty().ifBlank { "未命名 Job" },
                status = obj.string("status").orEmpty(),
                conclusion = obj.string("conclusion"),
                startedAt = obj.string("started_at").orEmpty(),
                completedAt = obj.string("completed_at").orEmpty(),
                steps = obj.jsonArrayOrEmpty("steps").mapNotNull { stepItem ->
                    val stepObj = stepItem.jsonObjectOrNull() ?: return@mapNotNull null
                    ProjectGitHubWorkflowStepUi(
                        number = (stepObj.long("number") ?: 0L).toInt(),
                        name = stepObj.string("name").orEmpty().ifBlank { "未命名步骤" },
                        status = stepObj.string("status").orEmpty(),
                        conclusion = stepObj.string("conclusion"),
                        startedAt = stepObj.string("started_at").orEmpty(),
                        completedAt = stepObj.string("completed_at").orEmpty()
                    )
                }
            )
        }
        .orEmpty()
    val detail = ProjectGitHubWorkflowRunDetailUi(
        id = runId,
        title = runObject.string("display_title")
            .orEmpty()
            .ifBlank { runObject.string("name").orEmpty().ifBlank { "运行 #${runObject.long("run_number") ?: runId}" } },
        workflowName = runObject.string("name").orEmpty(),
        headBranch = runObject.string("head_branch").orEmpty(),
        status = runObject.string("status").orEmpty(),
        conclusion = runObject.string("conclusion"),
        event = runObject.string("event").orEmpty(),
        runNumber = runObject.long("run_number") ?: 0L,
        createdAt = runObject.string("created_at").orEmpty(),
        updatedAt = runObject.string("updated_at").orEmpty(),
        htmlUrl = runObject.string("html_url"),
        jobs = jobs,
        artifacts = artifactsResult.artifacts,
        artifactsError = artifactsResult.error,
        logEntries = logsResult.entries,
        logsError = logsResult.error
    )
    return ProjectGitHubWorkflowRunDetailLoadResult(
        success = true,
        detail = detail,
        error = null
    )
}

private fun loadProjectGitHubWorkflowRunLogsPreview(
    repo: ProjectGitHubRepoRef,
    runId: Long,
    token: String,
    apiBaseUrl: String
): ProjectGitHubWorkflowLogLoadResult {
    return runCatching {
        val request = Request.Builder()
            .url(buildProjectGitHubApiUrl(apiBaseUrl, "/repos/${repo.owner}/${repo.repo}/actions/runs/$runId/logs"))
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Accept", "application/vnd.github+json")
            .addHeader("X-GitHub-Api-Version", "2022-11-28")
            .addHeader("User-Agent", "Reasonix-Mobile/1.0")
            .get()
            .build()
        PROJECT_GITHUB_HTTP.newCall(request).execute().use { response ->
            if (response.code !in setOf(200)) {
                val errorBody = response.body?.string().orEmpty()
                return@use ProjectGitHubWorkflowLogLoadResult(
                    success = false,
                    entries = emptyList(),
                    error = parseProjectGitHubApiError(errorBody, response.code)
                )
            }
            val entries = mutableListOf<ProjectGitHubWorkflowLogEntryUi>()
            val bodyStream = response.body?.byteStream()
                ?: return@use ProjectGitHubWorkflowLogLoadResult(
                    success = false,
                    entries = emptyList(),
                    error = "工作流日志内容为空"
                )
            ZipInputStream(bodyStream).use { zip ->
                var entry = zip.nextEntry
                while (entry != null && entries.size < 12) {
                    if (!entry.isDirectory) {
                        val raw = readProjectGitHubZipEntryBytes(zip, maxBytes = 96 * 1024)
                        val normalized = raw.first.toString(Charsets.UTF_8)
                            .replace("\r\n", "\n")
                            .trimEnd()
                        val lines = normalized.lines().filterNot { it.isEmpty() }
                        val previewLines = when {
                            lines.size <= 120 -> lines
                            else -> {
                                val hiddenCount = (lines.size - 84).coerceAtLeast(0)
                                lines.take(36) + listOf("... 已折叠 $hiddenCount 行 ...") + lines.takeLast(48)
                            }
                        }
                        entries += ProjectGitHubWorkflowLogEntryUi(
                            entryName = entry.name,
                            displayName = entry.name.substringAfterLast('/').ifBlank { entry.name },
                            preview = previewLines.joinToString("\n").ifBlank { "(当前日志文件为空)" },
                            totalLineCount = lines.size,
                            truncated = raw.second || lines.size > previewLines.size
                        )
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            ProjectGitHubWorkflowLogLoadResult(
                success = true,
                entries = entries,
                error = null
            )
        }
    }.getOrElse { error ->
        ProjectGitHubWorkflowLogLoadResult(
            success = false,
            entries = emptyList(),
            error = error.message ?: "读取工作流日志失败"
        )
    }
}

private fun readProjectGitHubZipEntryBytes(
    zip: ZipInputStream,
    maxBytes: Int
): Pair<ByteArray, Boolean> {
    val buffer = ByteArray(4096)
    val output = ByteArrayOutputStream()
    var truncated = false
    while (true) {
        val read = zip.read(buffer)
        if (read <= 0) break
        val remaining = maxBytes - output.size()
        if (remaining <= 0) {
            truncated = true
            continue
        }
        val toWrite = read.coerceAtMost(remaining)
        output.write(buffer, 0, toWrite)
        if (toWrite < read) {
            truncated = true
        }
    }
    return output.toByteArray() to truncated
}

internal fun updateProjectGitHubRelease(
    repo: ProjectGitHubRepoRef,
    releaseId: Long,
    tagName: String,
    releaseName: String,
    body: String,
    isDraft: Boolean,
    isPrerelease: Boolean,
    token: String,
    apiBaseUrl: String
): ProjectGitHubCommandResult {
    val requestBody = buildJsonObject {
        put("tag_name", tagName)
        put("name", releaseName)
        put("body", body)
        put("draft", isDraft)
        put("prerelease", isPrerelease)
    }.toString()
    val result = runProjectGitHubApiRequest(
        apiBaseUrl = apiBaseUrl,
        token = token,
        path = "/repos/${repo.owner}/${repo.repo}/releases/$releaseId",
        method = "PATCH",
        jsonBody = requestBody,
        allowedCodes = setOf(200)
    )
    return if (result.success) {
        ProjectGitHubCommandResult(success = true, message = "Release 已更新。")
    } else {
        ProjectGitHubCommandResult(
            success = false,
            message = "",
            error = result.error ?: "更新 Release 失败"
        )
    }
}

internal fun createProjectGitHubRelease(
    repo: ProjectGitHubRepoRef,
    tagName: String,
    releaseName: String,
    body: String,
    isDraft: Boolean,
    isPrerelease: Boolean,
    token: String,
    apiBaseUrl: String
): ProjectGitHubCommandResult {
    val requestBody = buildJsonObject {
        put("tag_name", tagName)
        put("name", releaseName)
        put("body", body)
        put("draft", isDraft)
        put("prerelease", isPrerelease)
    }.toString()
    val result = runProjectGitHubApiRequest(
        apiBaseUrl = apiBaseUrl,
        token = token,
        path = "/repos/${repo.owner}/${repo.repo}/releases",
        method = "POST",
        jsonBody = requestBody,
        allowedCodes = setOf(201)
    )
    return if (result.success) {
        ProjectGitHubCommandResult(success = true, message = "Release 已创建。")
    } else {
        ProjectGitHubCommandResult(
            success = false,
            message = "",
            error = result.error ?: "创建 Release 失败"
        )
    }
}

internal fun deleteProjectGitHubRelease(
    repo: ProjectGitHubRepoRef,
    releaseId: Long,
    token: String,
    apiBaseUrl: String
): ProjectGitHubCommandResult {
    val result = runProjectGitHubApiRequest(
        apiBaseUrl = apiBaseUrl,
        token = token,
        path = "/repos/${repo.owner}/${repo.repo}/releases/$releaseId",
        method = "DELETE",
        jsonBody = "",
        allowedCodes = setOf(204)
    )
    return if (result.success) {
        ProjectGitHubCommandResult(success = true, message = "Release 已删除。")
    } else {
        ProjectGitHubCommandResult(
            success = false,
            message = "",
            error = result.error ?: "删除 Release 失败"
        )
    }
}

internal fun updateProjectGitHubIssueState(
    repo: ProjectGitHubRepoRef,
    issueNumber: Long,
    close: Boolean,
    token: String,
    apiBaseUrl: String
): ProjectGitHubCommandResult {
    val requestBody = buildJsonObject {
        put("state", if (close) "closed" else "open")
    }.toString()
    val result = runProjectGitHubApiRequest(
        apiBaseUrl = apiBaseUrl,
        token = token,
        path = "/repos/${repo.owner}/${repo.repo}/issues/$issueNumber",
        method = "PATCH",
        jsonBody = requestBody,
        allowedCodes = setOf(200)
    )
    return if (result.success) {
        ProjectGitHubCommandResult(
            success = true,
            message = if (close) "Issue #$issueNumber 已关闭。" else "Issue #$issueNumber 已重新打开。"
        )
    } else {
        ProjectGitHubCommandResult(
            success = false,
            message = "",
            error = result.error ?: if (close) "关闭 Issue 失败" else "重新打开 Issue 失败"
        )
    }
}

internal fun updateProjectGitHubPullRequestState(
    repo: ProjectGitHubRepoRef,
    pullNumber: Long,
    close: Boolean,
    token: String,
    apiBaseUrl: String
): ProjectGitHubCommandResult {
    val requestBody = buildJsonObject {
        put("state", if (close) "closed" else "open")
    }.toString()
    val result = runProjectGitHubApiRequest(
        apiBaseUrl = apiBaseUrl,
        token = token,
        path = "/repos/${repo.owner}/${repo.repo}/pulls/$pullNumber",
        method = "PATCH",
        jsonBody = requestBody,
        allowedCodes = setOf(200)
    )
    return if (result.success) {
        ProjectGitHubCommandResult(
            success = true,
            message = if (close) "PR #$pullNumber 已关闭。" else "PR #$pullNumber 已重新打开。"
        )
    } else {
        ProjectGitHubCommandResult(
            success = false,
            message = "",
            error = result.error ?: if (close) "关闭 PR 失败" else "重新打开 PR 失败"
        )
    }
}

internal fun mergeProjectGitHubPullRequest(
    repo: ProjectGitHubRepoRef,
    pullNumber: Long,
    title: String,
    token: String,
    apiBaseUrl: String
): ProjectGitHubCommandResult {
    val requestBody = buildJsonObject {
        put("commit_title", "合并 PR #$pullNumber: ${title.ifBlank { "更新" }}")
        put("merge_method", "merge")
    }.toString()
    val result = runProjectGitHubApiRequest(
        apiBaseUrl = apiBaseUrl,
        token = token,
        path = "/repos/${repo.owner}/${repo.repo}/pulls/$pullNumber/merge",
        method = "PUT",
        jsonBody = requestBody,
        allowedCodes = setOf(200)
    )
    return if (result.success) {
        ProjectGitHubCommandResult(
            success = true,
            message = "PR #$pullNumber 已合并。"
        )
    } else {
        ProjectGitHubCommandResult(
            success = false,
            message = "",
            error = result.error ?: "合并 PR 失败"
        )
    }
}

internal fun createProjectGitHubIssue(
    repo: ProjectGitHubRepoRef,
    title: String,
    body: String,
    token: String,
    apiBaseUrl: String
): ProjectGitHubCommandResult {
    val requestBody = buildJsonObject {
        put("title", title)
        if (body.isNotBlank()) {
            put("body", body)
        }
    }.toString()
    val result = runProjectGitHubApiRequest(
        apiBaseUrl = apiBaseUrl,
        token = token,
        path = "/repos/${repo.owner}/${repo.repo}/issues",
        method = "POST",
        jsonBody = requestBody,
        allowedCodes = setOf(201)
    )
    return if (result.success) {
        ProjectGitHubCommandResult(success = true, message = "Issue 已创建。")
    } else {
        ProjectGitHubCommandResult(
            success = false,
            message = "",
            error = result.error ?: "创建 Issue 失败"
        )
    }
}

internal fun createProjectGitHubPullRequest(
    repo: ProjectGitHubRepoRef,
    title: String,
    body: String,
    head: String,
    base: String,
    isDraft: Boolean,
    token: String,
    apiBaseUrl: String
): ProjectGitHubCommandResult {
    val requestBody = buildJsonObject {
        put("title", title)
        put("head", head)
        put("base", base)
        put("draft", isDraft)
        if (body.isNotBlank()) {
            put("body", body)
        }
    }.toString()
    val result = runProjectGitHubApiRequest(
        apiBaseUrl = apiBaseUrl,
        token = token,
        path = "/repos/${repo.owner}/${repo.repo}/pulls",
        method = "POST",
        jsonBody = requestBody,
        allowedCodes = setOf(201)
    )
    return if (result.success) {
        ProjectGitHubCommandResult(success = true, message = "Pull Request 已创建。")
    } else {
        ProjectGitHubCommandResult(
            success = false,
            message = "",
            error = result.error ?: "创建 Pull Request 失败"
        )
    }
}

internal fun loadProjectGitHubIssueComments(
    repo: ProjectGitHubRepoRef,
    issueNumber: Long,
    token: String,
    apiBaseUrl: String
): ProjectGitHubCommentLoadResult {
    val result = runProjectGitHubApiRequest(
        apiBaseUrl = apiBaseUrl,
        token = token,
        path = "/repos/${repo.owner}/${repo.repo}/issues/$issueNumber/comments?per_page=50"
    )
    if (!result.success) {
        return ProjectGitHubCommentLoadResult(
            success = false,
            comments = emptyList(),
            error = result.error ?: "读取评论失败"
        )
    }
    val comments = parseProjectGitHubJsonArray(result.body)
        ?.mapNotNull { item ->
            val obj = item.jsonObjectOrNull() ?: return@mapNotNull null
            parseProjectGitHubComment(obj)
        }
        .orEmpty()
    return ProjectGitHubCommentLoadResult(
        success = true,
        comments = comments,
        error = null
    )
}

internal fun createProjectGitHubIssueComment(
    repo: ProjectGitHubRepoRef,
    issueNumber: Long,
    body: String,
    token: String,
    apiBaseUrl: String
): ProjectGitHubCommandResult {
    val requestBody = buildJsonObject {
        put("body", body)
    }.toString()
    val result = runProjectGitHubApiRequest(
        apiBaseUrl = apiBaseUrl,
        token = token,
        path = "/repos/${repo.owner}/${repo.repo}/issues/$issueNumber/comments",
        method = "POST",
        jsonBody = requestBody,
        allowedCodes = setOf(201)
    )
    return if (result.success) {
        ProjectGitHubCommandResult(success = true, message = "评论已发送。")
    } else {
        ProjectGitHubCommandResult(
            success = false,
            message = "",
            error = result.error ?: "发送评论失败"
        )
    }
}

internal fun loadProjectGitHubPullRequestReviews(
    repo: ProjectGitHubRepoRef,
    pullNumber: Long,
    token: String,
    apiBaseUrl: String
): ProjectGitHubPullRequestReviewLoadResult {
    val result = runProjectGitHubApiRequest(
        apiBaseUrl = apiBaseUrl,
        token = token,
        path = "/repos/${repo.owner}/${repo.repo}/pulls/$pullNumber/reviews?per_page=50"
    )
    if (!result.success) {
        return ProjectGitHubPullRequestReviewLoadResult(
            success = false,
            reviews = emptyList(),
            error = result.error ?: "读取 PR 评审失败"
        )
    }
    val reviews = parseProjectGitHubJsonArray(result.body)
        ?.mapNotNull { item ->
            val obj = item.jsonObjectOrNull() ?: return@mapNotNull null
            parseProjectGitHubPullRequestReview(obj)
        }
        .orEmpty()
    return ProjectGitHubPullRequestReviewLoadResult(
        success = true,
        reviews = reviews,
        error = null
    )
}

internal fun submitProjectGitHubPullRequestReview(
    repo: ProjectGitHubRepoRef,
    pullNumber: Long,
    body: String,
    event: String,
    token: String,
    apiBaseUrl: String
): ProjectGitHubCommandResult {
    val requestBody = buildJsonObject {
        if (body.isNotBlank()) {
            put("body", body)
        }
        put("event", event)
    }.toString()
    val result = runProjectGitHubApiRequest(
        apiBaseUrl = apiBaseUrl,
        token = token,
        path = "/repos/${repo.owner}/${repo.repo}/pulls/$pullNumber/reviews",
        method = "POST",
        jsonBody = requestBody,
        allowedCodes = setOf(200, 201)
    )
    return if (result.success) {
        ProjectGitHubCommandResult(
            success = true,
            message = when (event) {
                "APPROVE" -> "PR 评审已批准。"
                "REQUEST_CHANGES" -> "已提交请求修改。"
                else -> "PR 评审评论已提交。"
            }
        )
    } else {
        ProjectGitHubCommandResult(
            success = false,
            message = "",
            error = result.error ?: "提交 PR 评审失败"
        )
    }
}

internal fun loadProjectGitHubPullRequestFiles(
    repo: ProjectGitHubRepoRef,
    pullNumber: Long,
    token: String,
    apiBaseUrl: String
): ProjectGitHubPullRequestFileLoadResult {
    val result = runProjectGitHubApiRequest(
        apiBaseUrl = apiBaseUrl,
        token = token,
        path = "/repos/${repo.owner}/${repo.repo}/pulls/$pullNumber/files?per_page=100"
    )
    if (!result.success) {
        return ProjectGitHubPullRequestFileLoadResult(
            success = false,
            files = emptyList(),
            error = result.error ?: "读取 PR 变更文件失败"
        )
    }
    val files = parseProjectGitHubJsonArray(result.body)
        ?.mapNotNull { item ->
            val obj = item.jsonObjectOrNull() ?: return@mapNotNull null
            parseProjectGitHubPullRequestFile(obj)
        }
        .orEmpty()
    return ProjectGitHubPullRequestFileLoadResult(
        success = true,
        files = files,
        error = null
    )
}

internal fun loadProjectGitHubPullRequestReviewComments(
    repo: ProjectGitHubRepoRef,
    pullNumber: Long,
    token: String,
    apiBaseUrl: String
): ProjectGitHubPullRequestReviewCommentLoadResult {
    val result = runProjectGitHubApiRequest(
        apiBaseUrl = apiBaseUrl,
        token = token,
        path = "/repos/${repo.owner}/${repo.repo}/pulls/$pullNumber/comments?per_page=100"
    )
    if (!result.success) {
        return ProjectGitHubPullRequestReviewCommentLoadResult(
            success = false,
            comments = emptyList(),
            error = result.error ?: "读取代码评审评论失败"
        )
    }
    val comments = parseProjectGitHubJsonArray(result.body)
        ?.mapNotNull { item ->
            val obj = item.jsonObjectOrNull() ?: return@mapNotNull null
            parseProjectGitHubPullRequestReviewComment(obj)
        }
        .orEmpty()
    return ProjectGitHubPullRequestReviewCommentLoadResult(
        success = true,
        comments = comments,
        error = null
    )
}

internal fun createProjectGitHubPullRequestReviewComment(
    repo: ProjectGitHubRepoRef,
    pullNumber: Long,
    body: String,
    commitId: String,
    path: String,
    line: Int?,
    token: String,
    apiBaseUrl: String
): ProjectGitHubCommandResult {
    val requestBody = buildJsonObject {
        put("body", body)
        put("commit_id", commitId)
        put("path", path)
        if (line != null) {
            put("line", line)
            put("side", "RIGHT")
        } else {
            put("subject_type", "file")
        }
    }.toString()
    val result = runProjectGitHubApiRequest(
        apiBaseUrl = apiBaseUrl,
        token = token,
        path = "/repos/${repo.owner}/${repo.repo}/pulls/$pullNumber/comments",
        method = "POST",
        jsonBody = requestBody,
        allowedCodes = setOf(200, 201)
    )
    return if (result.success) {
        ProjectGitHubCommandResult(
            success = true,
            message = if (line != null) "代码行评论已提交。" else "文件级评论已提交。"
        )
    } else {
        ProjectGitHubCommandResult(
            success = false,
            message = "",
            error = result.error ?: "提交代码评审评论失败"
        )
    }
}

internal fun replyProjectGitHubPullRequestReviewComment(
    repo: ProjectGitHubRepoRef,
    pullNumber: Long,
    commentId: Long,
    body: String,
    token: String,
    apiBaseUrl: String
): ProjectGitHubCommandResult {
    val requestBody = buildJsonObject {
        put("body", body)
    }.toString()
    val result = runProjectGitHubApiRequest(
        apiBaseUrl = apiBaseUrl,
        token = token,
        path = "/repos/${repo.owner}/${repo.repo}/pulls/$pullNumber/comments/$commentId/replies",
        method = "POST",
        jsonBody = requestBody,
        allowedCodes = setOf(200, 201)
    )
    return if (result.success) {
        ProjectGitHubCommandResult(
            success = true,
            message = "已回复代码评审评论。"
        )
    } else {
        ProjectGitHubCommandResult(
            success = false,
            message = "",
            error = result.error ?: "回复代码评审评论失败"
        )
    }
}

internal fun loadProjectGitHubRemoteDirectory(
    repo: ProjectGitHubRepoRef,
    path: String,
    ref: String,
    token: String,
    apiBaseUrl: String
): ProjectGitHubRemoteDirectoryLoadResult {
    val result = runProjectGitHubApiRequest(
        token = token,
        apiBaseUrl = apiBaseUrl,
        path = buildProjectGitHubContentsApiPath(repo, path, ref)
    )
    if (!result.success) {
        return ProjectGitHubRemoteDirectoryLoadResult(
            success = false,
            state = null,
            error = result.error
        )
    }
    val entries = parseProjectGitHubJsonArray(result.body)?.mapNotNull { item ->
        val obj = item.jsonObjectOrNull() ?: return@mapNotNull null
        ProjectGitHubRemoteEntryUi(
            name = obj.string("name").orEmpty(),
            path = obj.string("path").orEmpty(),
            type = obj.string("type").orEmpty(),
            sha = obj.string("sha"),
            size = obj.long("size") ?: 0L,
            htmlUrl = obj.string("html_url"),
            downloadUrl = obj.string("download_url")
        )
    }?.sortedWith(
        compareBy<ProjectGitHubRemoteEntryUi> { !it.isDirectory }
            .thenBy { it.name.lowercase(Locale.getDefault()) }
    )
    if (entries == null) {
        return ProjectGitHubRemoteDirectoryLoadResult(
            success = false,
            state = null,
            error = "当前远端路径不是目录，或 GitHub API 返回了非目录内容。"
        )
    }
    return ProjectGitHubRemoteDirectoryLoadResult(
        success = true,
        state = ProjectGitHubRemoteBrowserState(
            repo = repo,
            currentRef = ref,
            currentPath = normalizeProjectGitHubRepoPath(path),
            entries = entries,
            repoHtmlUrl = "https://github.com/${repo.owner}/${repo.repo}",
            errorMessage = null
        ),
        error = null
    )
}

internal fun loadProjectGitHubRemoteFile(
    repo: ProjectGitHubRepoRef,
    path: String,
    ref: String,
    token: String,
    apiBaseUrl: String
): ProjectGitHubRemoteFileLoadResult {
    val result = runProjectGitHubApiRequest(
        token = token,
        apiBaseUrl = apiBaseUrl,
        path = buildProjectGitHubContentsApiPath(repo, path, ref)
    )
    if (!result.success) {
        return ProjectGitHubRemoteFileLoadResult(success = false, file = null, error = result.error)
    }
    val obj = parseProjectGitHubJsonObject(result.body)
        ?: return ProjectGitHubRemoteFileLoadResult(
            success = false,
            file = null,
            error = "GitHub 没有返回可读取的文件对象。"
        )
    if (obj.string("type") != "file") {
        return ProjectGitHubRemoteFileLoadResult(
            success = false,
            file = null,
            error = "当前远端条目不是普通文件。"
        )
    }
    val encoding = obj.string("encoding").orEmpty()
    val rawContent = obj.string("content").orEmpty()
    val decodedContent = when {
        encoding.equals("base64", ignoreCase = true) -> {
            val normalized = rawContent.replace("\n", "").replace("\r", "")
            runCatching {
                String(Base64.decode(normalized, Base64.DEFAULT), Charsets.UTF_8)
            }.getOrNull()
        }
        rawContent.isNotBlank() -> rawContent
        else -> null
    } ?: return ProjectGitHubRemoteFileLoadResult(
        success = false,
        file = null,
        error = "当前文件内容没有被 GitHub 直接返回，暂时无法在应用内编辑。"
    )
    return ProjectGitHubRemoteFileLoadResult(
        success = true,
        file = ProjectGitHubRemoteFileUi(
            path = obj.string("path").orEmpty(),
            name = obj.string("name").orEmpty(),
            sha = obj.string("sha"),
            ref = ref,
            content = decodedContent,
            size = obj.long("size") ?: decodedContent.toByteArray().size.toLong(),
            htmlUrl = obj.string("html_url"),
            downloadUrl = obj.string("download_url"),
            truncated = false
        ),
        error = null
    )
}

internal fun updateProjectGitHubRemoteFile(
    repo: ProjectGitHubRepoRef,
    path: String,
    branch: String,
    message: String,
    content: String,
    sha: String?,
    token: String,
    apiBaseUrl: String
): ProjectGitHubCommandResult {
    val payload = buildJsonObject {
        put("message", message)
        put("content", Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
        if (!sha.isNullOrBlank()) put("sha", sha)
        if (branch.isNotBlank()) put("branch", branch)
    }
    val result = runProjectGitHubApiRequest(
        token = token,
        apiBaseUrl = apiBaseUrl,
        method = "PUT",
        path = buildProjectGitHubContentsWriteApiPath(repo, path),
        jsonBody = payload.toString()
    )
    return if (result.success) {
        ProjectGitHubCommandResult(
            success = true,
            message = "已更新远端文件 $path"
        )
    } else {
        ProjectGitHubCommandResult(
            success = false,
            message = "",
            error = result.error ?: "更新远端文件失败"
        )
    }
}

internal fun createProjectGitHubRepository(
    repoName: String,
    description: String,
    isPrivate: Boolean,
    token: String,
    apiBaseUrl: String
): ProjectGitHubCreateRepoResult {
    val requestBody = buildJsonObject {
        put("name", repoName)
        if (description.isNotBlank()) {
            put("description", description)
        }
        put("private", isPrivate)
        put("auto_init", false)
    }.toString()
    val result = runProjectGitHubApiRequest(
        apiBaseUrl = apiBaseUrl,
        token = token,
        path = "/user/repos",
        method = "POST",
        jsonBody = requestBody,
        allowedCodes = setOf(201)
    )
    if (!result.success) {
        return ProjectGitHubCreateRepoResult(
            success = false,
            repo = null,
            cloneUrl = null,
            sshUrl = null,
            recommendedRemoteUrl = null,
            htmlUrl = null,
            error = result.error ?: "创建 GitHub 仓库失败"
        )
    }
    val obj = parseProjectGitHubJsonObject(result.body)
        ?: return ProjectGitHubCreateRepoResult(
            success = false,
            repo = null,
            cloneUrl = null,
            sshUrl = null,
            recommendedRemoteUrl = null,
            htmlUrl = null,
            error = "GitHub 返回的仓库信息无法解析"
        )
    val owner = obj.jsonObjectOrNull("owner")?.string("login")
    val repo = obj.string("name")
    val cloneUrl = obj.string("clone_url")
    val sshUrl = obj.string("ssh_url")
    val recommendedRemoteUrl = preferredProjectGitHubRemoteUrl(
        cloneUrl = cloneUrl,
        sshUrl = sshUrl
    )
    return if (owner.isNullOrBlank() || repo.isNullOrBlank() || recommendedRemoteUrl.isNullOrBlank()) {
        ProjectGitHubCreateRepoResult(
            success = false,
            repo = null,
            cloneUrl = null,
            sshUrl = null,
            recommendedRemoteUrl = null,
            htmlUrl = null,
            error = "GitHub 返回的仓库信息不完整"
        )
    } else {
        ProjectGitHubCreateRepoResult(
            success = true,
            repo = ProjectGitHubRepoRef(owner = owner, repo = repo),
            cloneUrl = cloneUrl,
            sshUrl = sshUrl,
            recommendedRemoteUrl = recommendedRemoteUrl,
            htmlUrl = obj.string("html_url"),
            error = null
        )
    }
}

internal fun parseProjectGitHubRepoRef(remoteUrl: String?): ProjectGitHubRepoRef? {
    val raw = remoteUrl?.trim()?.removeSuffix(".git").orEmpty()
    if (raw.isBlank()) return null
    val path = when {
        raw.startsWith("git@") -> raw.substringAfter(':', "")
        raw.startsWith("ssh://", ignoreCase = true) ||
            raw.startsWith("http://", ignoreCase = true) ||
            raw.startsWith("https://", ignoreCase = true) -> {
            Uri.parse(raw).path.orEmpty().trim('/')
        }
        else -> raw.substringAfter(':', raw).trim('/')
    }
    val segments = path.split('/').filter { it.isNotBlank() }
    if (segments.size < 2) return null
    return ProjectGitHubRepoRef(
        owner = segments[segments.lastIndex - 1],
        repo = segments.last()
    )
}

internal fun preferredProjectGitHubRemoteUrl(
    cloneUrl: String?,
    sshUrl: String?
): String? {
    val httpsRemote = cloneUrl?.trim()?.takeIf { it.isNotBlank() }
    if (httpsRemote != null) return httpsRemote
    return sshUrl?.trim()?.takeIf { it.isNotBlank() }
}

internal fun summarizeProjectGitRemoteTransport(
    remoteUrl: String?,
    tokenConfigured: Boolean
): String? {
    val raw = remoteUrl?.trim().orEmpty()
    if (raw.isBlank()) return null
    val isGitHubRemote = parseProjectGitHubRepoRef(raw) != null
    return when {
        raw.startsWith("git@github.com:", ignoreCase = true) ||
            (raw.startsWith("ssh://", ignoreCase = true) && Uri.parse(raw).host.equals("github.com", ignoreCase = true)) -> {
            if (tokenConfigured) {
                "当前是 GitHub SSH 远端，内置 Git 会自动改走 HTTPS + Token 传输。"
            } else {
                "当前是 GitHub SSH 远端；未配置 Token 时，拉取和推送会受限。"
            }
        }
        raw.startsWith("https://", ignoreCase = true) ||
            raw.startsWith("http://", ignoreCase = true) -> {
            if (isGitHubRemote && tokenConfigured) {
                "当前是 HTTPS 远端，内置 Git 会优先使用 GitHub Token 认证。"
            } else if (isGitHubRemote) {
                "当前是 HTTPS 远端；如需稳定推送，请在设置页配置 GitHub Token。"
            } else {
                "当前是 HTTPS 远端，认证方式取决于该服务端本身。"
            }
        }
        raw.startsWith("git@", ignoreCase = true) || raw.startsWith("ssh://", ignoreCase = true) -> {
            "当前是 SSH 远端；除 GitHub 外，其他 SSH 仓库仍需要设备侧凭据支持。"
        }
        else -> "当前远端类型较特殊，内置 Git 会按仓库原始配置尝试连接。"
    }
}

internal suspend fun searchProjectGitHubGlobal(
    query: String,
    repos: List<ProjectGitHubRepoRef>,
    token: String,
    apiBaseUrl: String,
    localRepoMap: Map<String, String> = emptyMap()
): List<ProjectGitHubGlobalSearchResultUi> {
    if (query.isBlank()) return emptyList()

    val results = mutableListOf<ProjectGitHubGlobalSearchResultUi>()
    // 限制仓库数量以防查询字符串过长 (GitHub 限制 256 字符)
    val targetRepos = repos.take(8)
    if (targetRepos.isEmpty()) return emptyList()
    
    val repoQualifiers = targetRepos.joinToString(" ") { "repo:${it.owner}/${it.repo}" }

    // 1. 搜索 Issue 和 PR
    val issueSearchPath = "/search/issues?q=${Uri.encode("$query $repoQualifiers")}&per_page=15"
    val issueResult = runProjectGitHubApiRequest(apiBaseUrl, token, issueSearchPath)
    if (issueResult.success) {
        parseProjectGitHubJsonObject(issueResult.body)?.jsonArrayOrEmpty("items")?.forEach { item ->
            val obj = item.jsonObjectOrNull() ?: return@forEach
            val htmlUrl = obj.string("html_url")
            val isPr = htmlUrl?.contains("/pull/") == true
            val repoUrl = obj.string("repository_url")
            val repoParts = repoUrl?.split("/repos/")?.lastOrNull()?.split("/")
            val owner = repoParts?.getOrNull(0).orEmpty()
            val repoName = repoParts?.getOrNull(1).orEmpty()

            results.add(ProjectGitHubGlobalSearchResultUi(
                type = if (isPr) ProjectGitHubGlobalSearchResultType.PULL_REQUEST else ProjectGitHubGlobalSearchResultType.ISSUE,
                title = obj.string("title").orEmpty(),
                subtitle = "#${obj.long("number")} · ${obj.string("state")} · $owner/$repoName",
                repoOwner = owner,
                repoName = repoName,
                rootPath = localRepoMap["$owner/$repoName"],
                url = htmlUrl,
                number = obj.long("number"),
                updatedAt = obj.string("updated_at")
            ))
        }
    }

    // 2. 搜索代码/文件
    val codeSearchPath = "/search/code?q=${Uri.encode("$query $repoQualifiers")}&per_page=15"
    val codeResult = runProjectGitHubApiRequest(apiBaseUrl, token, codeSearchPath)
    if (codeResult.success) {
        parseProjectGitHubJsonObject(codeResult.body)?.jsonArrayOrEmpty("items")?.forEach { item ->
            val obj = item.jsonObjectOrNull() ?: return@forEach
            val repoObj = obj.jsonObjectOrNull("repository")
            val owner = repoObj?.jsonObjectOrNull("owner")?.string("login").orEmpty()
            val repoName = repoObj?.string("name").orEmpty()

            results.add(ProjectGitHubGlobalSearchResultUi(
                type = ProjectGitHubGlobalSearchResultType.FILE,
                title = obj.string("name").orEmpty(),
                subtitle = "${obj.string("path")} · $owner/$repoName",
                repoOwner = owner,
                repoName = repoName,
                rootPath = localRepoMap["$owner/$repoName"],
                url = obj.string("html_url"),
                filePath = obj.string("path")
            ))
        }
    }

    return results.sortedByDescending { it.updatedAt ?: "" }
}

private fun runProjectGitHubApiRequest(
    apiBaseUrl: String,
    token: String,
    path: String,
    method: String = "GET",
    jsonBody: String? = null,
    allowedCodes: Set<Int> = setOf(200)
): ProjectGitHubHttpResult {
    return runCatching {
        val url = buildProjectGitHubApiUrl(apiBaseUrl, path)
        val requestBuilder = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Accept", "application/vnd.github+json")
            .addHeader("X-GitHub-Api-Version", "2022-11-28")
            .addHeader("User-Agent", "Reasonix-Mobile/1.0")
        if (method.equals("GET", ignoreCase = true)) {
            requestBuilder.get()
        } else {
            requestBuilder.method(
                method,
                (jsonBody ?: "{}").toRequestBody("application/json; charset=utf-8".toMediaType())
            )
        }
        PROJECT_GITHUB_HTTP.newCall(requestBuilder.build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (response.code in allowedCodes) {
                ProjectGitHubHttpResult(
                    success = true,
                    code = response.code,
                    body = body,
                    error = null
                )
            } else {
                ProjectGitHubHttpResult(
                    success = false,
                    code = response.code,
                    body = body,
                    error = parseProjectGitHubApiError(body, response.code)
                )
            }
        }
    }.getOrElse { error ->
        ProjectGitHubHttpResult(
            success = false,
            code = -1,
            body = "",
            error = error.message ?: "GitHub API 请求失败"
        )
    }
}

internal fun buildProjectGitHubApiUrl(apiBaseUrl: String, path: String): String {
    val base = normalizeProjectGitHubApiBaseUrl(apiBaseUrl)
    return base.trimEnd('/') + "/" + path.trimStart('/')
}

private fun normalizeProjectGitHubApiBaseUrl(raw: String?): String {
    val trimmed = raw?.trim().orEmpty().ifBlank { "https://api.github.com" }
    return when {
        trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true) -> trimmed
        else -> "https://$trimmed"
    }
}

private fun parseProjectGitHubApiError(body: String, code: Int): String {
    val obj = parseProjectGitHubJsonObject(body)
    val message = obj?.string("message")
    return message?.ifBlank { null } ?: "GitHub API 请求失败，HTTP $code"
}

private fun parseProjectGitHubJsonObject(body: String): JsonObject? {
    return runCatching {
        PROJECT_GITHUB_JSON.parseToJsonElement(body).jsonObject
    }.getOrNull()
}

private fun parseProjectGitHubJsonArray(body: String) = runCatching {
    PROJECT_GITHUB_JSON.parseToJsonElement(body).jsonArray
}.getOrNull()

private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

private fun JsonObject.long(key: String): Long? = this[key]?.jsonPrimitive?.longOrNull

private fun JsonObject.boolean(key: String): Boolean? = this[key]?.jsonPrimitive?.booleanOrNull

private fun JsonObject.jsonArrayOrEmpty(key: String) = this[key]?.jsonArray ?: emptyList()

private fun JsonObject.jsonObjectOrNull(key: String): JsonObject? = this[key]?.jsonObjectOrNull()

private fun kotlinx.serialization.json.JsonElement.jsonObjectOrNull(): JsonObject? = runCatching { jsonObject }.getOrNull()

private fun parseProjectGitHubIssue(obj: JsonObject): ProjectGitHubIssueUi? {
    return ProjectGitHubIssueUi(
        number = obj.long("number") ?: return null,
        title = obj.string("title").orEmpty().ifBlank { "未命名 Issue" },
        body = obj.string("body").orEmpty(),
        state = obj.string("state").orEmpty(),
        authorLogin = obj.jsonObjectOrNull("user")?.string("login"),
        updatedAt = obj.string("updated_at").orEmpty(),
        htmlUrl = obj.string("html_url"),
        labels = parseProjectGitHubLabels(obj)
    )
}

private fun parseProjectGitHubPullRequest(obj: JsonObject): ProjectGitHubPullRequestUi? {
    return ProjectGitHubPullRequestUi(
        number = obj.long("number") ?: return null,
        title = obj.string("title").orEmpty().ifBlank { "未命名 Pull Request" },
        body = obj.string("body").orEmpty(),
        state = obj.string("state").orEmpty(),
        isDraft = obj.boolean("draft") ?: false,
        isMerged = !obj.string("merged_at").isNullOrBlank(),
        authorLogin = obj.jsonObjectOrNull("user")?.string("login"),
        updatedAt = obj.string("updated_at").orEmpty(),
        htmlUrl = obj.string("html_url"),
        labels = parseProjectGitHubLabels(obj),
        headSha = obj.jsonObjectOrNull("head")?.string("sha").orEmpty(),
        headBranch = obj.jsonObjectOrNull("head")?.string("ref").orEmpty(),
        baseBranch = obj.jsonObjectOrNull("base")?.string("ref").orEmpty()
    )
}

private fun parseProjectGitHubComment(obj: JsonObject): ProjectGitHubCommentUi? {
    return ProjectGitHubCommentUi(
        id = obj.long("id") ?: return null,
        authorLogin = obj.jsonObjectOrNull("user")?.string("login"),
        body = obj.string("body").orEmpty(),
        createdAt = obj.string("created_at").orEmpty(),
        updatedAt = obj.string("updated_at").orEmpty(),
        htmlUrl = obj.string("html_url")
    )
}

private fun parseProjectGitHubPullRequestReview(obj: JsonObject): ProjectGitHubPullRequestReviewUi? {
    return ProjectGitHubPullRequestReviewUi(
        id = obj.long("id") ?: return null,
        authorLogin = obj.jsonObjectOrNull("user")?.string("login"),
        body = obj.string("body").orEmpty(),
        state = obj.string("state").orEmpty(),
        submittedAt = obj.string("submitted_at").orEmpty(),
        commitId = obj.string("commit_id"),
        htmlUrl = obj.string("html_url")
    )
}

private fun parseProjectGitHubPullRequestFile(obj: JsonObject): ProjectGitHubPullRequestFileUi? {
    return ProjectGitHubPullRequestFileUi(
        path = obj.string("filename") ?: return null,
        status = obj.string("status").orEmpty(),
        additions = obj.long("additions") ?: 0L,
        deletions = obj.long("deletions") ?: 0L,
        changes = obj.long("changes") ?: 0L,
        patch = obj.string("patch"),
        blobUrl = obj.string("blob_url")
    )
}

private fun parseProjectGitHubPullRequestReviewComment(obj: JsonObject): ProjectGitHubPullRequestReviewCommentUi? {
    return ProjectGitHubPullRequestReviewCommentUi(
        id = obj.long("id") ?: return null,
        authorLogin = obj.jsonObjectOrNull("user")?.string("login"),
        body = obj.string("body").orEmpty(),
        path = obj.string("path").orEmpty(),
        line = obj.long("line")?.toInt() ?: obj.long("original_line")?.toInt(),
        side = obj.string("side"),
        parentCommentId = obj.long("in_reply_to_id"),
        createdAt = obj.string("created_at").orEmpty(),
        updatedAt = obj.string("updated_at").orEmpty(),
        htmlUrl = obj.string("html_url")
    )
}

private fun parseProjectGitHubLabels(obj: JsonObject): List<String> {
    return obj.jsonArrayOrEmpty("labels").mapNotNull { labelItem ->
        labelItem.jsonObjectOrNull()?.string("name")?.takeIf { it.isNotBlank() }
    }
}

internal fun buildProjectGitHubContentsApiPath(
    repo: ProjectGitHubRepoRef,
    path: String,
    ref: String
): String {
    val encodedPath = normalizeProjectGitHubRepoPath(path)
        .split('/')
        .filter { it.isNotBlank() }
        .joinToString("/") { Uri.encode(it) }
    val suffix = if (encodedPath.isBlank()) "" else "/$encodedPath"
    val query = Uri.encode(ref).takeIf { it.isNotBlank() }?.let { "?ref=$it" }.orEmpty()
    return "/repos/${Uri.encode(repo.owner)}/${Uri.encode(repo.repo)}/contents$suffix$query"
}

internal fun buildProjectGitHubContentsWriteApiPath(
    repo: ProjectGitHubRepoRef,
    path: String
): String {
    val encodedPath = normalizeProjectGitHubRepoPath(path)
        .split('/')
        .filter { it.isNotBlank() }
        .joinToString("/") { Uri.encode(it) }
    return "/repos/${Uri.encode(repo.owner)}/${Uri.encode(repo.repo)}/contents/$encodedPath"
}

internal fun normalizeProjectGitHubRepoPath(path: String): String {
    return path.trim().removePrefix("/").removeSuffix("/")
}

internal fun parentProjectGitHubRepoPath(path: String): String? {
    val normalized = normalizeProjectGitHubRepoPath(path)
    if (normalized.isBlank()) return null
    return normalized.substringBeforeLast('/', "").ifBlank { "" }
}

internal fun displayProjectGitHubRepoPath(path: String): String {
    return normalizeProjectGitHubRepoPath(path).ifBlank { "/" }
}

internal fun loadProjectGitHubWorkspaceRemoteSummary(
    repo: ProjectGitHubRepoRef,
    token: String,
    apiBaseUrl: String,
    includeWorkItemPreview: Boolean = false
): ProjectGitHubWorkspaceRemoteSummaryUi {
    var defaultBranch: String? = null
    var repoHtmlUrl: String? = null
    var latestRun: ProjectGitHubWorkflowRunUi? = null
    var runningRunCount = 0
    var openIssueCount = 0
    var latestOpenIssue: ProjectGitHubIssueUi? = null
    var openPullRequestCount = 0
    var latestOpenPullRequest: ProjectGitHubPullRequestUi? = null
    val errors = mutableListOf<String>()

    val repoResult = runProjectGitHubApiRequest(
        apiBaseUrl = apiBaseUrl,
        token = token,
        path = "/repos/${repo.owner}/${repo.repo}"
    )
    if (repoResult.success) {
        parseProjectGitHubJsonObject(repoResult.body)?.let { repoObject ->
            defaultBranch = repoObject.string("default_branch")
            repoHtmlUrl = repoObject.string("html_url")
        }
    } else {
        errors += repoResult.error ?: "读取仓库信息失败"
    }

    val runsResult = runProjectGitHubApiRequest(
        apiBaseUrl = apiBaseUrl,
        token = token,
        path = "/repos/${repo.owner}/${repo.repo}/actions/runs?per_page=6"
    )
    if (runsResult.success) {
        val runs = parseProjectGitHubJsonObject(runsResult.body)
            ?.jsonArrayOrEmpty("workflow_runs")
            ?.mapNotNull { item ->
                val obj = item.jsonObjectOrNull() ?: return@mapNotNull null
                ProjectGitHubWorkflowRunUi(
                    id = obj.long("id") ?: return@mapNotNull null,
                    name = obj.string("name").orEmpty(),
                    displayTitle = obj.string("display_title").orEmpty(),
                    headBranch = obj.string("head_branch").orEmpty(),
                    status = obj.string("status").orEmpty(),
                    conclusion = obj.string("conclusion"),
                    event = obj.string("event").orEmpty(),
                    runNumber = obj.long("run_number") ?: 0L,
                    updatedAt = obj.string("updated_at").orEmpty(),
                    htmlUrl = obj.string("html_url")
                )
            }
            .orEmpty()
        latestRun = runs.firstOrNull()
        runningRunCount = runs.count { !it.status.equals("completed", ignoreCase = true) }
    } else {
        errors += runsResult.error ?: "读取工作流摘要失败"
    }

    val issuesQuery = Uri.encode("repo:${repo.owner}/${repo.repo} is:issue is:open")
    val issuesResult = runProjectGitHubApiRequest(
        apiBaseUrl = apiBaseUrl,
        token = token,
        path = "/search/issues?q=$issuesQuery&sort=updated&order=desc&per_page=1"
    )
    if (issuesResult.success) {
        val issuesObject = parseProjectGitHubJsonObject(issuesResult.body)
        openIssueCount = issuesObject?.long("total_count")?.toInt() ?: 0
        if (includeWorkItemPreview) {
            latestOpenIssue = issuesObject
                ?.jsonArrayOrEmpty("items")
                ?.mapNotNull { item ->
                    val obj = item.jsonObjectOrNull() ?: return@mapNotNull null
                    if (obj.jsonObjectOrNull("pull_request") != null) return@mapNotNull null
                    parseProjectGitHubIssue(obj)
                }
                ?.firstOrNull()
        }
    } else {
        errors += issuesResult.error ?: "读取开放 Issue 摘要失败"
    }

    val pullRequestsQuery = Uri.encode("repo:${repo.owner}/${repo.repo} is:pr is:open")
    val pullRequestsResult = runProjectGitHubApiRequest(
        apiBaseUrl = apiBaseUrl,
        token = token,
        path = "/search/issues?q=$pullRequestsQuery&sort=updated&order=desc&per_page=1"
    )
    if (pullRequestsResult.success) {
        val pullRequestsObject = parseProjectGitHubJsonObject(pullRequestsResult.body)
        openPullRequestCount = pullRequestsObject?.long("total_count")?.toInt() ?: 0
        if (includeWorkItemPreview) {
            latestOpenPullRequest = pullRequestsObject
                ?.jsonArrayOrEmpty("items")
                ?.mapNotNull { item ->
                    val obj = item.jsonObjectOrNull() ?: return@mapNotNull null
                    parseProjectGitHubPullRequest(obj)
                }
                ?.firstOrNull()
        }
    } else {
        errors += pullRequestsResult.error ?: "读取开放 PR 摘要失败"
    }

    return ProjectGitHubWorkspaceRemoteSummaryUi(
        repo = repo,
        defaultBranch = defaultBranch,
        repoHtmlUrl = repoHtmlUrl,
        latestRun = latestRun,
        runningRunCount = runningRunCount,
        openIssueCount = openIssueCount,
        latestOpenIssue = latestOpenIssue,
        openPullRequestCount = openPullRequestCount,
        latestOpenPullRequest = latestOpenPullRequest,
        hasWorkItemPreview = includeWorkItemPreview,
        errorMessage = errors.firstOrNull()
    )
}
