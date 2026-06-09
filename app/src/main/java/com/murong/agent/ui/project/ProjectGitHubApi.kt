package com.murong.agent.ui.project

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

internal data class ProjectGitHubReadmeLoadResult(
    val readme: ProjectGitHubReadmeUi?,
    val error: String?
)

internal data class ProjectGitHubBranchListLoadResult(
    val branches: List<String>,
    val error: String? = null
)

internal fun loadProjectGitHubViewerRepositories(
    token: String,
    apiBaseUrl: String
): ProjectGitHubViewerRepositoriesState {
    if (token.isBlank()) {
        return ProjectGitHubViewerRepositoriesState.empty().copy(
            errorMessage = "请先在设置页填写 GitHub Token。"
        )
    }
    val viewerResult = runProjectGitHubApiRequest(
        apiBaseUrl = apiBaseUrl,
        token = token,
        path = "/user"
    )
    if (!viewerResult.success) {
        return ProjectGitHubViewerRepositoriesState.empty().copy(
            errorMessage = viewerResult.error ?: "GitHub 登录状态校验失败"
        )
    }
    val viewer = parseProjectGitHubJsonObject(viewerResult.body)
    val reposResult = runProjectGitHubApiRequest(
        apiBaseUrl = apiBaseUrl,
        token = token,
        path = "/user/repos?sort=updated&per_page=100"
    )
    if (!reposResult.success) {
        return ProjectGitHubViewerRepositoriesState(
            viewerLogin = viewer?.string("login"),
            viewerName = viewer?.string("name"),
            repositories = emptyList(),
            errorMessage = reposResult.error ?: "读取账号仓库列表失败"
        )
    }
    val repositories = parseProjectGitHubJsonArray(reposResult.body)
        ?.mapNotNull { item ->
            val obj = item.jsonObjectOrNull() ?: return@mapNotNull null
            parseProjectGitHubAccountRepo(item = obj)
        }
        .orEmpty()
    return ProjectGitHubViewerRepositoriesState(
        viewerLogin = viewer?.string("login"),
        viewerName = viewer?.string("name"),
        repositories = repositories,
        errorMessage = null
    )
}

internal fun updateProjectGitHubRepositoryDescription(
    repo: ProjectGitHubRepoRef,
    description: String,
    token: String,
    apiBaseUrl: String
): ProjectGitHubMutationResult<ProjectGitHubAccountRepoUi> {
    if (token.isBlank()) {
        return ProjectGitHubMutationResult(
            success = false,
            value = null,
            error = "请先在设置页填写 GitHub Token。"
        )
    }
    val body = buildJsonObject {
        put("description", description)
    }.toString()
    val result = runProjectGitHubApiRequest(
        apiBaseUrl = apiBaseUrl,
        token = token,
        path = "/repos/${repo.owner}/${repo.repo}",
        method = "PATCH",
        jsonBody = body
    )
    if (!result.success) {
        return ProjectGitHubMutationResult(
            success = false,
            value = null,
            error = result.error ?: "更新仓库简介失败"
        )
    }
    val updatedRepo = parseProjectGitHubJsonObject(result.body)?.let(::parseProjectGitHubAccountRepo)
        ?: return ProjectGitHubMutationResult(
            success = false,
            value = null,
            error = "GitHub 返回的仓库信息无法解析"
        )
    return ProjectGitHubMutationResult(
        success = true,
        value = updatedRepo,
        error = null
    )
}

internal fun loadProjectGitHubReadme(
    repo: ProjectGitHubRepoRef,
    token: String,
    apiBaseUrl: String
): ProjectGitHubReadmeLoadResult {
    if (token.isBlank()) {
        return ProjectGitHubReadmeLoadResult(
            readme = null,
            error = "请先在设置页填写 GitHub Token。"
        )
    }
    val result = runProjectGitHubApiRequest(
        apiBaseUrl = apiBaseUrl,
        token = token,
        path = "/repos/${repo.owner}/${repo.repo}/readme"
    )
    if (!result.success) {
        return ProjectGitHubReadmeLoadResult(
            readme = null,
            error = result.error ?: "读取 README 失败"
        )
    }
    val obj = parseProjectGitHubJsonObject(result.body)
        ?: return ProjectGitHubReadmeLoadResult(readme = null, error = "README 数据格式无效")
    val encodedContent = obj.string("content").orEmpty()
    val decodedContent = runCatching {
        String(
            Base64.decode(
                encodedContent.replace("\n", ""),
                Base64.DEFAULT
            ),
            Charsets.UTF_8
        )
    }.getOrDefault("")
    return ProjectGitHubReadmeLoadResult(
        readme = ProjectGitHubReadmeUi(
            name = obj.string("name").orEmpty().ifBlank { "README.md" },
            path = obj.string("path").orEmpty().ifBlank { "README.md" },
            htmlUrl = obj.string("html_url"),
            content = decodedContent
        ),
        error = null
    )
}

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

internal fun loadProjectGitHubBranches(
    repo: ProjectGitHubRepoRef,
    token: String,
    apiBaseUrl: String
): ProjectGitHubBranchListLoadResult {
    val result = runProjectGitHubApiRequest(
        apiBaseUrl = apiBaseUrl,
        token = token,
        path = "/repos/${repo.owner}/${repo.repo}/branches?per_page=100"
    )
    if (!result.success) {
        return ProjectGitHubBranchListLoadResult(
            branches = emptyList(),
            error = result.error ?: "读取仓库分支失败"
        )
    }
    val branches = parseProjectGitHubJsonArray(result.body)
        ?.mapNotNull { item ->
            item.jsonObjectOrNull()?.string("name")?.takeIf { it.isNotBlank() }
        }
        .orEmpty()
    return ProjectGitHubBranchListLoadResult(
        branches = branches,
        error = null
    )
}

internal fun dispatchProjectGitHubWorkflow(
    repo: ProjectGitHubRepoRef,
    workflowId: Long,
    ref: String,
    inputs: Map<String, String> = emptyMap(),
    token: String,
    apiBaseUrl: String
): ProjectGitHubCommandResult {
    val body = buildJsonObject {
        put("ref", ref)
        if (inputs.isNotEmpty()) {
            put(
                "inputs",
                buildJsonObject {
                    inputs.forEach { (key, value) ->
                        put(key, value)
                    }
                }
            )
        }
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
        repo = repo,
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
            .addHeader("User-Agent", "MurongAgent/1.0")
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

internal fun deleteProjectGitHubReleaseAsset(
    repo: ProjectGitHubRepoRef,
    assetId: Long,
    token: String,
    apiBaseUrl: String
): ProjectGitHubCommandResult {
    val result = runProjectGitHubApiRequest(
        apiBaseUrl = apiBaseUrl,
        token = token,
        path = "/repos/${repo.owner}/${repo.repo}/releases/assets/$assetId",
        method = "DELETE",
        jsonBody = "",
        allowedCodes = setOf(204)
    )
    return if (result.success) {
        ProjectGitHubCommandResult(success = true, message = "Release 资产已删除。")
    } else {
        ProjectGitHubCommandResult(
            success = false,
            message = "",
            error = result.error ?: "删除 Release 资产失败"
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

internal fun loadProjectGitHubWorkflowDispatchSchema(
    repo: ProjectGitHubRepoRef,
    workflowPath: String,
    ref: String,
    token: String,
    apiBaseUrl: String
): ProjectGitHubWorkflowDispatchSchemaLoadResult {
    if (workflowPath.isBlank()) {
        return ProjectGitHubWorkflowDispatchSchemaLoadResult(
            inputs = emptyList(),
            error = "当前工作流路径为空，无法解析参数。"
        )
    }
    val fileResult = loadProjectGitHubRemoteFile(
        repo = repo,
        path = workflowPath,
        ref = ref,
        token = token,
        apiBaseUrl = apiBaseUrl
    )
    if (!fileResult.success || fileResult.file == null) {
        return ProjectGitHubWorkflowDispatchSchemaLoadResult(
            inputs = emptyList(),
            error = fileResult.error ?: "读取工作流配置失败"
        )
    }
    val inputs = parseProjectGitHubWorkflowDispatchInputs(fileResult.file.content)
    return ProjectGitHubWorkflowDispatchSchemaLoadResult(
        inputs = inputs,
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

private fun parseProjectGitHubWorkflowDispatchInputs(
    workflowContent: String
): List<ProjectGitHubWorkflowDispatchInputUi> {
    val normalizedContent = workflowContent.replace("\r\n", "\n")
    val documents = splitProjectGitHubYamlDocuments(normalizedContent)
    documents.forEach { documentLines ->
        val parsed = parseProjectGitHubWorkflowDispatchInputsFromDocument(documentLines)
        if (parsed != null) {
            return parsed
        }
    }
    return emptyList()
}

private fun parseProjectGitHubWorkflowDispatchInputsFromDocument(
    lines: List<String>
): List<ProjectGitHubWorkflowDispatchInputUi>? {
    val jobsIndex = lines.indexOfFirst { it.trimStart().startsWith("jobs:") }.let { if (it < 0) lines.size else it }
    val onBlockIndex = lines.indexOfFirst { line ->
        val trimmed = stripProjectGitHubYamlInlineComment(line).trim()
        Regex("^(\"on\"|'on'|on)\\s*:").containsMatchIn(trimmed)
    }
    if (onBlockIndex < 0) return null
    val onIndent = lines[onBlockIndex].leadingWhitespaceCount()
    // Inline `on: [workflow_dispatch]` / `on: {workflow_dispatch: {}}` / `on: workflow_dispatch`
    run {
        val inline = stripProjectGitHubYamlInlineComment(lines[onBlockIndex]).trim()
        val afterColon = inline.substringAfter(':', "").trim()
        if (afterColon.isNotBlank()) {
            if (afterColon.contains("workflow_dispatch")) {
                return parseProjectGitHubInlineWorkflowDispatchInputs(afterColon) ?: emptyList()
            }
        }
    }
    val onBlockLines = collectProjectGitHubYamlChildLines(
        lines = lines,
        startIndex = onBlockIndex + 1,
        parentIndent = onIndent
    ).first
    if (onBlockLines.any { line ->
            val trimmed = stripProjectGitHubYamlInlineComment(line).trim()
            trimmed == "- workflow_dispatch" ||
                trimmed == "- \"workflow_dispatch\"" ||
                trimmed == "- 'workflow_dispatch'"
        }) {
        return emptyList()
    }
    val workflowDispatchIndex = findProjectGitHubYamlChildIndex(
        lines = lines,
        parentIndex = onBlockIndex,
        parentIndent = onIndent,
        targetKey = "workflow_dispatch"
    ) ?: return null
    val workflowDispatchIndent = lines[workflowDispatchIndex].leadingWhitespaceCount()
    run {
        val workflowDispatchLine = stripProjectGitHubYamlInlineComment(lines[workflowDispatchIndex]).trim()
        val afterColon = workflowDispatchLine.substringAfter(':', "").trim()
        if (afterColon.isNotBlank()) {
            return parseProjectGitHubInlineInputsFromWorkflowDispatchValue(afterColon) ?: emptyList()
        }
    }
    val inputsIndex = findProjectGitHubYamlChildIndex(
        lines = lines,
        parentIndex = workflowDispatchIndex,
        parentIndent = workflowDispatchIndent,
        targetKey = "inputs"
    ) ?: return emptyList()
    if (inputsIndex >= jobsIndex) return null
    run {
        val inputsLine = stripProjectGitHubYamlInlineComment(lines[inputsIndex]).trim()
        val afterColon = inputsLine.substringAfter(':', "").trim()
        if (afterColon.isNotBlank()) {
            return parseProjectGitHubInlineInputsMap(afterColon)
        }
    }
    val inputsIndent = lines[inputsIndex].leadingWhitespaceCount()
    val results = mutableListOf<ProjectGitHubWorkflowDispatchInputUi>()
    var index = inputsIndex + 1
    while (index < lines.size) {
        val line = lines[index]
        if (line.isBlank() || line.trimStart().startsWith("#")) {
            index++
            continue
        }
        val indent = line.leadingWhitespaceCount()
        if (indent <= inputsIndent) break
        val trimmed = line.trim()
        if (!trimmed.endsWith(":") || trimmed.startsWith("- ")) {
            index++
            continue
        }
        val inputKey = trimmed.removeSuffix(":").trim().trimYamlQuotes()
        val propertyStart = index + 1
        var nextInputIndex = propertyStart
        while (nextInputIndex < lines.size) {
            val nextLine = lines[nextInputIndex]
            if (nextLine.isBlank() || nextLine.trimStart().startsWith("#")) {
                nextInputIndex++
                continue
            }
            val nextIndent = nextLine.leadingWhitespaceCount()
            if (nextIndent <= inputsIndent) break
            if (nextIndent == indent && nextLine.trim().endsWith(":")) break
            nextInputIndex++
        }
        val propertyLines = lines.subList(propertyStart, nextInputIndex)
        results += parseProjectGitHubWorkflowDispatchInput(
            key = inputKey,
            propertyLines = propertyLines
        )
        index = nextInputIndex
    }
    return results
}

private fun splitProjectGitHubYamlDocuments(content: String): List<List<String>> {
    val lines = content.lines()
    val documents = mutableListOf<MutableList<String>>()
    var current = mutableListOf<String>()
    lines.forEach { line ->
        val trimmed = line.trim()
        if (trimmed == "---" || trimmed == "...") {
            if (current.isNotEmpty()) {
                documents += current
                current = mutableListOf()
            }
        } else {
            current += line
        }
    }
    if (current.isNotEmpty()) {
        documents += current
    }
    return documents.ifEmpty { listOf(lines) }
}

private fun parseProjectGitHubInlineWorkflowDispatchInputs(
    inlineText: String
): List<ProjectGitHubWorkflowDispatchInputUi>? {
    val normalized = stripProjectGitHubYamlInlineComment(inlineText).trim()
    if (normalized.equals("workflow_dispatch", ignoreCase = true)) {
        return emptyList()
    }
    if (normalized.startsWith("[") && normalized.endsWith("]")) {
        val items = splitProjectGitHubInlineTopLevel(
            normalized.removePrefix("[").removeSuffix("]")
        ).map { it.trim().trimYamlQuotes() }
        return if (items.any { it == "workflow_dispatch" }) emptyList() else null
    }
    val entries = parseProjectGitHubInlineMapEntries(
        if (normalized.startsWith("{")) normalized else "{$normalized}"
    )
    val workflowDispatchValue = entries["workflow_dispatch"] ?: return null
    return parseProjectGitHubInlineInputsFromWorkflowDispatchValue(workflowDispatchValue) ?: emptyList()
}

private fun parseProjectGitHubInlineInputsFromWorkflowDispatchValue(
    workflowDispatchValue: String
): List<ProjectGitHubWorkflowDispatchInputUi>? {
    val normalized = workflowDispatchValue.trim()
    if (normalized.isBlank() || normalized == "{}") return emptyList()
    val entries = parseProjectGitHubInlineMapEntries(
        if (normalized.startsWith("{")) normalized else "{$normalized}"
    )
    val inputsValue = entries["inputs"] ?: return emptyList()
    return parseProjectGitHubInlineInputsMap(inputsValue)
}

private fun parseProjectGitHubInlineInputsMap(
    inputsValue: String
): List<ProjectGitHubWorkflowDispatchInputUi> {
    val entries = parseProjectGitHubInlineMapEntries(
        if (inputsValue.trim().startsWith("{")) inputsValue.trim() else "{$inputsValue}"
    )
    return entries.map { (key, rawValue) ->
        val normalized = rawValue.trim()
        val properties = if (normalized.startsWith("{") && normalized.endsWith("}")) {
            parseProjectGitHubInlineMapEntries(normalized)
        } else {
            emptyMap()
        }
        val optionsValue = properties["options"]
        val options = if (optionsValue != null && optionsValue.trim().startsWith("[") && optionsValue.trim().endsWith("]")) {
            splitProjectGitHubInlineTopLevel(
                optionsValue.trim().removePrefix("[").removeSuffix("]")
            ).mapNotNull { option ->
                option.trim().trimYamlQuotes().takeIf { it.isNotBlank() }
            }
        } else {
            emptyList()
        }
        ProjectGitHubWorkflowDispatchInputUi(
            key = key,
            value = properties["default"]?.trimYamlQuotes().orEmpty(),
            description = properties["description"]?.trimYamlQuotes()?.takeIf { it.isNotBlank() },
            required = properties["required"]?.equals("true", ignoreCase = true) == true,
            defaultValue = properties["default"]?.trimYamlQuotes()?.takeIf { it.isNotBlank() },
            type = properties["type"]?.trimYamlQuotes()?.ifBlank { "string" } ?: "string",
            options = options,
            autoDetected = true
        )
    }
}

private fun parseProjectGitHubInlineMapEntries(
    raw: String
): Map<String, String> {
    val normalized = raw.trim().removeSurrounding("{", "}").trim()
    if (normalized.isBlank()) return emptyMap()
    return splitProjectGitHubInlineTopLevel(normalized)
        .mapNotNull { entry ->
            val separatorIndex = findProjectGitHubInlineTopLevelSeparator(entry, ':')
            if (separatorIndex <= 0) {
                null
            } else {
                val key = entry.substring(0, separatorIndex).trim().trimYamlQuotes()
                val value = entry.substring(separatorIndex + 1).trim()
                key.takeIf { it.isNotBlank() }?.let { it to value }
            }
        }
        .toMap()
}

private fun splitProjectGitHubInlineTopLevel(
    content: String
): List<String> {
    val parts = mutableListOf<String>()
    val builder = StringBuilder()
    var braceDepth = 0
    var bracketDepth = 0
    var inSingleQuote = false
    var inDoubleQuote = false
    content.forEach { char ->
        when (char) {
            '\'' -> if (!inDoubleQuote) inSingleQuote = !inSingleQuote
            '"' -> if (!inSingleQuote) inDoubleQuote = !inDoubleQuote
            '{' -> if (!inSingleQuote && !inDoubleQuote) braceDepth++
            '}' -> if (!inSingleQuote && !inDoubleQuote) braceDepth--
            '[' -> if (!inSingleQuote && !inDoubleQuote) bracketDepth++
            ']' -> if (!inSingleQuote && !inDoubleQuote) bracketDepth--
            ',' -> if (!inSingleQuote && !inDoubleQuote && braceDepth == 0 && bracketDepth == 0) {
                parts += builder.toString().trim()
                builder.clear()
                return@forEach
            }
        }
        builder.append(char)
    }
    builder.toString().trim().takeIf { it.isNotBlank() }?.let(parts::add)
    return parts
}

private fun findProjectGitHubInlineTopLevelSeparator(
    content: String,
    separator: Char
): Int {
    var braceDepth = 0
    var bracketDepth = 0
    var inSingleQuote = false
    var inDoubleQuote = false
    content.forEachIndexed { index, char ->
        when (char) {
            '\'' -> if (!inDoubleQuote) inSingleQuote = !inSingleQuote
            '"' -> if (!inSingleQuote) inDoubleQuote = !inDoubleQuote
            '{' -> if (!inSingleQuote && !inDoubleQuote) braceDepth++
            '}' -> if (!inSingleQuote && !inDoubleQuote) braceDepth--
            '[' -> if (!inSingleQuote && !inDoubleQuote) bracketDepth++
            ']' -> if (!inSingleQuote && !inDoubleQuote) bracketDepth--
            separator -> if (!inSingleQuote && !inDoubleQuote && braceDepth == 0 && bracketDepth == 0) {
                return index
            }
        }
    }
    return -1
}

private fun parseProjectGitHubWorkflowDispatchInput(
    key: String,
    propertyLines: List<String>
): ProjectGitHubWorkflowDispatchInputUi {
    var description: String? = null
    var required = false
    var defaultValue: String? = null
    var type = "string"
    val options = mutableListOf<String>()
    var index = 0
    while (index < propertyLines.size) {
        val line = propertyLines[index]
        val trimmed = stripProjectGitHubYamlInlineComment(line).trim()
        if (trimmed.isBlank() || trimmed.startsWith("#")) {
            index++
            continue
        }
        val colonIndex = trimmed.indexOf(':')
        if (colonIndex <= 0) {
            index++
            continue
        }
        val lineIndent = line.leadingWhitespaceCount()
        val propertyKey = trimmed.substring(0, colonIndex).trim().trimYamlQuotes()
        val propertyValue = trimmed.substring(colonIndex + 1).trim()
        when (propertyKey) {
            "description" -> {
                if (propertyValue == "|" || propertyValue == ">") {
                    val (blockLines, nextIndex) = collectProjectGitHubYamlChildLines(
                        lines = propertyLines,
                        startIndex = index + 1,
                        parentIndent = lineIndent
                    )
                    description = formatProjectGitHubYamlBlockScalar(
                        lines = blockLines,
                        folded = propertyValue == ">"
                    ).takeIf { it.isNotBlank() }
                    index = nextIndex
                    continue
                }
                description = propertyValue.trimYamlQuotes().takeIf { it.isNotBlank() }
            }
            "required" -> required = propertyValue.equals("true", ignoreCase = true)
            "default" -> {
                if (propertyValue == "|" || propertyValue == ">") {
                    val (blockLines, nextIndex) = collectProjectGitHubYamlChildLines(
                        lines = propertyLines,
                        startIndex = index + 1,
                        parentIndent = lineIndent
                    )
                    defaultValue = formatProjectGitHubYamlBlockScalar(
                        lines = blockLines,
                        folded = propertyValue == ">"
                    ).takeIf { it.isNotBlank() }
                    index = nextIndex
                    continue
                }
                defaultValue = propertyValue.trimYamlQuotes().takeIf { it.isNotBlank() }
            }
            "type" -> type = propertyValue.trimYamlQuotes().ifBlank { "string" }
            "options" -> {
                if (propertyValue.startsWith("[") && propertyValue.endsWith("]")) {
                    options += propertyValue
                        .removePrefix("[")
                        .removeSuffix("]")
                        .split(',')
                        .map { it.trim().trimYamlQuotes() }
                        .filter { it.isNotBlank() }
                } else {
                    val (blockLines, nextIndex) = collectProjectGitHubYamlChildLines(
                        lines = propertyLines,
                        startIndex = index + 1,
                        parentIndent = lineIndent
                    )
                    options += blockLines.mapNotNull { optionLine ->
                        stripProjectGitHubYamlInlineComment(optionLine).trim()
                            .takeIf { it.startsWith("- ") }
                            ?.removePrefix("- ")
                            ?.trim()
                            ?.trimYamlQuotes()
                            ?.takeIf { it.isNotBlank() }
                    }
                    index = nextIndex
                    continue
                }
            }
        }
        index++
    }
    return ProjectGitHubWorkflowDispatchInputUi(
        key = key,
        value = defaultValue.orEmpty(),
        description = description,
        required = required,
        defaultValue = defaultValue,
        type = type,
        options = options,
        autoDetected = true
    )
}

private fun collectProjectGitHubYamlChildLines(
    lines: List<String>,
    startIndex: Int,
    parentIndent: Int
): Pair<List<String>, Int> {
    val collected = mutableListOf<String>()
    var index = startIndex
    while (index < lines.size) {
        val line = lines[index]
        if (line.isBlank()) {
            collected += line
            index++
            continue
        }
        val indent = line.leadingWhitespaceCount()
        if (indent <= parentIndent) break
        collected += line
        index++
    }
    return collected to index
}

private fun formatProjectGitHubYamlBlockScalar(
    lines: List<String>,
    folded: Boolean
): String {
    val normalized = lines
        .filterNot { it.trimStart().startsWith("#") }
        .map { it.trimEnd() }
        .dropWhile { it.isBlank() }
        .dropLastWhile { it.isBlank() }
        .map { it.trimStart() }
    return if (folded) {
        normalized.joinToString(" ").replace(Regex("\\s+"), " ").trim()
    } else {
        normalized.joinToString("\n").trim()
    }
}

private fun findProjectGitHubYamlChildIndex(
    lines: List<String>,
    parentIndex: Int,
    parentIndent: Int,
    targetKey: String
): Int? {
    var index = parentIndex + 1
    while (index < lines.size) {
        val line = lines[index]
        if (line.isBlank() || line.trimStart().startsWith("#")) {
            index++
            continue
        }
        val indent = line.leadingWhitespaceCount()
        if (indent <= parentIndent) break
        val trimmed = stripProjectGitHubYamlInlineComment(line).trim()
        val key = trimmed.substringBefore(':').trim().trimYamlQuotes()
        if (key == targetKey) return index
        index++
    }
    return null
}

private fun String.leadingWhitespaceCount(): Int = takeWhile { it == ' ' || it == '\t' }.length

private fun String.trimYamlQuotes(): String = trim().removeSurrounding("\"").removeSurrounding("'")

private fun stripProjectGitHubYamlInlineComment(line: String): String {
    var inSingleQuote = false
    var inDoubleQuote = false
    line.forEachIndexed { index, char ->
        when (char) {
            '\'' -> if (!inDoubleQuote) inSingleQuote = !inSingleQuote
            '"' -> if (!inSingleQuote) inDoubleQuote = !inDoubleQuote
            '#' -> if (!inSingleQuote && !inDoubleQuote) {
                return line.substring(0, index).trimEnd()
            }
        }
    }
    return line
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
            .addHeader("User-Agent", "MurongAgent/1.0")
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

private fun parseProjectGitHubAccountRepo(
    item: JsonObject
): ProjectGitHubAccountRepoUi? {
    val ownerLogin = item.jsonObjectOrNull("owner")?.string("login").orEmpty()
    val name = item.string("name").orEmpty()
    if (ownerLogin.isBlank() || name.isBlank()) return null
    return ProjectGitHubAccountRepoUi(
        id = item.long("id") ?: return null,
        owner = ownerLogin,
        name = name,
        description = item.string("description").orEmpty(),
        isPrivate = item.boolean("private") ?: false,
        stargazerCount = item.long("stargazers_count") ?: 0L,
        forkCount = item.long("forks_count") ?: 0L,
        htmlUrl = item.string("html_url"),
        defaultBranch = item.string("default_branch").orEmpty(),
        updatedAt = item.string("updated_at").orEmpty()
    )
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
