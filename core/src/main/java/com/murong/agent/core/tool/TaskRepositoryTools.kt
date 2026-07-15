package com.murong.agent.core.tool

import android.net.Uri
import android.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

data class RemoteTaskRepositoryTarget(
    val owner: String,
    val repo: String,
    val label: String
)

internal class TaskRepoSearchCodeTool(
    private val repositoryProvider: () -> RemoteTaskRepositoryTarget?,
    private val githubTokenProvider: () -> String,
    private val githubApiBaseUrlProvider: () -> String
) : Tool {
    override val name: String = "task_repo_search_code"
    override val description: String =
        "在当前任务仓库里搜索文件名或代码片段。自动绑定当前远端任务仓库，不需要再手动填写 owner/repo。优先用它来定位远端文件路径。"
    override val parameters: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "query" to mapOf(
                "type" to "string",
                "description" to "搜索关键字，可填文件名、符号名或代码片段"
            ),
            "path" to mapOf(
                "type" to "string",
                "description" to "可选。限制搜索子目录，例如 app/src/main"
            ),
            "language" to mapOf(
                "type" to "string",
                "description" to "可选。GitHub 搜索语言过滤，例如 Kotlin、Java、Rust"
            ),
            "limit" to mapOf(
                "type" to "integer",
                "description" to "返回结果上限，默认 10，最大 20"
            )
        ),
        "required" to listOf("query")
    )

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(args: String): String {
        val repo = repositoryProvider()
            ?: return "Error: 当前会话没有绑定远端任务仓库。请先在项目页把 GitHub 仓库设为任务仓库。"
        val token = githubTokenProvider().trim()
        if (token.isBlank()) return "Error: 请先在设置页填写 GitHub Token。"
        return try {
            val obj = json.parseToJsonElement(args).jsonObject
            val query = obj["query"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (query.isBlank()) return "Error: 'query' parameter required"
            val path = obj["path"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty().ifBlank { null }
            val language = obj["language"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty().ifBlank { null }
            val limit = (obj["limit"]?.jsonPrimitive?.intOrNull ?: 10).coerceIn(1, 20)
            val normalizedSearchTerm = normalizeRemoteTaskRepositorySearchQuery(query)
            val githubQuery = buildString {
                append(normalizedSearchTerm)
                append(" repo:${repo.owner}/${repo.repo}")
                path?.let {
                    append(" path:")
                    append(it)
                }
                language?.let {
                    append(" language:")
                    append(it)
                }
            }
            val result = runRemoteTaskRepositoryApiRequest(
                apiBaseUrl = githubApiBaseUrlProvider(),
                token = token,
                path = "/search/code?q=${Uri.encode(githubQuery)}&per_page=$limit&page=1"
            )
            if (!result.success) {
                return result.error ?: "当前任务仓库代码搜索失败"
            }
            formatGitHubSearchResult(result.body, repo.label, githubQuery)
        } catch (e: Exception) {
            "Error: 搜索当前任务仓库失败: ${e.message}"
        }
    }
}

internal class TaskRepoListDirTool(
    private val repositoryProvider: () -> RemoteTaskRepositoryTarget?,
    private val githubTokenProvider: () -> String,
    private val githubApiBaseUrlProvider: () -> String
) : Tool {
    override val name: String = "task_repo_list_dir"
    override val description: String =
        "列出当前任务仓库某个目录下的文件和子目录。适合在还不知道准确路径时先浏览远端仓库结构。"
    override val parameters: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "path" to mapOf(
                "type" to "string",
                "description" to "可选。仓库内目录路径；不填、填写空字符串或 / 时表示仓库根目录"
            ),
            "branch" to mapOf(
                "type" to "string",
                "description" to "可选。指定分支；不填时使用仓库默认分支"
            ),
            "limit" to mapOf(
                "type" to "integer",
                "description" to "可选。输出条目上限，默认 50，最大 200"
            )
        )
    )

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(args: String): String {
        val repo = repositoryProvider()
            ?: return "Error: 当前会话没有绑定远端任务仓库。请先在项目页把 GitHub 仓库设为任务仓库。"
        val token = githubTokenProvider().trim()
        if (token.isBlank()) return "Error: 请先在设置页填写 GitHub Token。"
        return try {
            val obj = json.parseToJsonElement(args).jsonObject
            val path = obj["path"]?.jsonPrimitive?.contentOrNull
                ?.trim()
                ?.removePrefix("/")
                ?.removeSuffix("/")
                .orEmpty()
                .ifBlank { null }
            val branch = obj["branch"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty().ifBlank { null }
            val limit = (obj["limit"]?.jsonPrimitive?.intOrNull ?: 50).coerceIn(1, 200)
            val result = loadRemoteTaskRepositoryDirectory(
                repo = repo,
                path = path,
                branch = branch,
                token = token,
                apiBaseUrl = githubApiBaseUrlProvider()
            )
            when {
                result.entries != null -> formatRemoteTaskRepositoryDirectory(
                    repoLabel = repo.label,
                    requestedPath = path,
                    requestedBranch = branch,
                    entries = result.entries,
                    limit = limit
                )
                result.rawResult != null -> result.rawResult
                else -> "Error: 当前远端目录读取失败。"
            }
        } catch (e: Exception) {
            "Error: 列出当前任务仓库目录失败: ${e.message}"
        }
    }
}

internal class TaskRepoListBranchesTool(
    private val repositoryProvider: () -> RemoteTaskRepositoryTarget?,
    private val githubTokenProvider: () -> String,
    private val githubApiBaseUrlProvider: () -> String
) : Tool {
    override val name: String = "task_repo_list_branches"
    override val description: String =
        "列出当前任务仓库可用的远端分支，并标出默认分支。适合提交、创建分支或排查分支名时先确认仓库分支。"
    override val parameters: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "limit" to mapOf(
                "type" to "integer",
                "description" to "可选。返回分支上限，默认 50，最大 100"
            )
        )
    )

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(args: String): String {
        val repo = repositoryProvider()
            ?: return "Error: 当前会话没有绑定远端任务仓库。请先在项目页把 GitHub 仓库设为任务仓库。"
        val token = githubTokenProvider().trim()
        if (token.isBlank()) return "Error: 请先在设置页填写 GitHub Token。"
        return try {
            val obj = json.parseToJsonElement(args).jsonObject
            val limit = (obj["limit"]?.jsonPrimitive?.intOrNull ?: 50).coerceIn(1, 100)
            val result = loadRemoteTaskRepositoryBranches(
                repo = repo,
                token = token,
                apiBaseUrl = githubApiBaseUrlProvider(),
                limit = limit
            )
            when {
                result.branches != null -> formatRemoteTaskRepositoryBranches(
                    repoLabel = repo.label,
                    defaultBranch = result.defaultBranch,
                    branches = result.branches
                )
                result.rawResult != null -> result.rawResult
                else -> "Error: 当前远端分支列表读取失败。"
            }
        } catch (e: Exception) {
            "Error: 列出当前任务仓库分支失败: ${e.message}"
        }
    }
}

internal class TaskRepoCreateBranchTool(
    private val repositoryProvider: () -> RemoteTaskRepositoryTarget?,
    private val githubTokenProvider: () -> String,
    private val githubApiBaseUrlProvider: () -> String
) : Tool {
    override val name: String = "task_repo_create_branch"
    override val description: String =
        "在当前任务仓库中创建一个新的远端分支。可基于默认分支或指定源分支创建，适合后续在新分支上提交修改。"
    override val parameters: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "branch" to mapOf(
                "type" to "string",
                "description" to "要创建的新分支名，例如 feature/my-change"
            ),
            "source_branch" to mapOf(
                "type" to "string",
                "description" to "可选。作为基准的源分支；不填时使用仓库默认分支"
            )
        ),
        "required" to listOf("branch")
    )

    private val json = Json { ignoreUnknownKeys = true }

    override fun buildApprovalRequest(args: String): ToolApprovalRequest? {
        val repo = repositoryProvider() ?: return null
        return try {
            val obj = json.parseToJsonElement(args).jsonObject
            val branch = normalizeRemoteTaskRepositoryBranchName(
                obj["branch"]?.jsonPrimitive?.contentOrNull
            )
            if (branch.isNullOrBlank()) return null
            ToolApprovalRequest(
                toolName = name,
                summary = "创建远端任务仓库分支",
                detail = "${repo.label}: $branch",
                riskLevel = ApprovalRiskLevel.HIGH,
                rawArgs = args,
                approvalScopeTokens = setOf("mcp:github:write")
            )
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun execute(args: String): String {
        val repo = repositoryProvider()
            ?: return "Error: 当前会话没有绑定远端任务仓库。请先在项目页把 GitHub 仓库设为任务仓库。"
        val token = githubTokenProvider().trim()
        if (token.isBlank()) return "Error: 请先在设置页填写 GitHub Token。"
        val apiBaseUrl = githubApiBaseUrlProvider()
        return try {
            val obj = json.parseToJsonElement(args).jsonObject
            val targetBranch = normalizeRemoteTaskRepositoryBranchName(
                obj["branch"]?.jsonPrimitive?.contentOrNull
            )
            if (targetBranch.isNullOrBlank()) return "Error: 'branch' parameter required"
            val sourceBranch = normalizeRemoteTaskRepositoryBranchName(
                obj["source_branch"]?.jsonPrimitive?.contentOrNull
            ) ?: loadRemoteTaskRepositoryDefaultBranch(
                repo = repo,
                token = token,
                apiBaseUrl = apiBaseUrl
            ) ?: return "Error: 无法确定默认分支，请显式传入 'source_branch'"

            val sourceHeadSha = loadRemoteTaskRepositoryBranchHeadSha(
                repo = repo,
                branch = sourceBranch,
                token = token,
                apiBaseUrl = apiBaseUrl
            ) ?: return "Error: 无法读取源分支 `$sourceBranch` 的最新提交"

            val result = runRemoteTaskRepositoryApiRequest(
                apiBaseUrl = apiBaseUrl,
                token = token,
                path = buildRemoteTaskRepositoryGitRefsPath(repo),
                method = "POST",
                jsonBody = buildJsonObject {
                    put("ref", "refs/heads/$targetBranch")
                    put("sha", sourceHeadSha)
                }.toString(),
                allowedCodes = setOf(201)
            )
            if (!result.success) {
                return buildString {
                    append("Error: 创建远端任务仓库分支失败。")
                    append("\n目标分支: ")
                    append(targetBranch)
                    append("\n源分支: ")
                    append(sourceBranch)
                    result.error?.takeIf { it.isNotBlank() }?.let {
                        append("\n底层结果: ")
                        append(it)
                    }
                }
            }
            verifyRemoteTaskRepositoryBranchState(
                repo = repo,
                branch = targetBranch,
                shouldExist = true,
                token = token,
                apiBaseUrl = apiBaseUrl
            )?.let { verificationError ->
                return "Error: 创建远端任务仓库分支后校验失败。$verificationError"
            }
            buildString {
                append("已创建当前任务仓库分支：")
                append(repo.label)
                append("\n新分支: ")
                append(targetBranch)
                append("\n源分支: ")
                append(sourceBranch)
                append("\n基准提交: ")
                append(sourceHeadSha)
            }
        } catch (e: Exception) {
            "Error: 创建当前任务仓库分支失败: ${e.message}"
        }
    }
}

internal class TaskRepoCreatePrTool(
    private val repositoryProvider: () -> RemoteTaskRepositoryTarget?,
    private val githubTokenProvider: () -> String,
    private val githubApiBaseUrlProvider: () -> String
) : Tool {
    override val name: String = "task_repo_create_pr"
    override val description: String =
        "为当前任务仓库创建一个远端 Pull Request。适合在分支提交完成后发起 PR。"
    override val parameters: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "title" to mapOf(
                "type" to "string",
                "description" to "Pull Request 标题"
            ),
            "head" to mapOf(
                "type" to "string",
                "description" to "源分支名，例如 feature/my-change"
            ),
            "base" to mapOf(
                "type" to "string",
                "description" to "目标分支名；不填时使用仓库默认分支"
            ),
            "body" to mapOf(
                "type" to "string",
                "description" to "可选。Pull Request 描述正文"
            ),
            "draft" to mapOf(
                "type" to "boolean",
                "description" to "可选。是否创建为草稿 PR"
            )
        ),
        "required" to listOf("title", "head")
    )

    private val json = Json { ignoreUnknownKeys = true }

    override fun buildApprovalRequest(args: String): ToolApprovalRequest? {
        val repo = repositoryProvider() ?: return null
        return try {
            val obj = json.parseToJsonElement(args).jsonObject
            val title = obj["title"]?.jsonPrimitive?.contentOrNull?.trim()
            val head = normalizeRemoteTaskRepositoryBranchName(
                obj["head"]?.jsonPrimitive?.contentOrNull
            )
            if (title.isNullOrBlank() || head.isNullOrBlank()) return null
            ToolApprovalRequest(
                toolName = name,
                summary = "为远端任务仓库创建 Pull Request",
                detail = "${repo.label}: $head -> PR",
                riskLevel = ApprovalRiskLevel.HIGH,
                rawArgs = args,
                approvalScopeTokens = setOf("mcp:github:write")
            )
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun execute(args: String): String {
        val repo = repositoryProvider()
            ?: return "Error: 当前会话没有绑定远端任务仓库。请先在项目页把 GitHub 仓库设为任务仓库。"
        val token = githubTokenProvider().trim()
        if (token.isBlank()) return "Error: 请先在设置页填写 GitHub Token。"
        val apiBaseUrl = githubApiBaseUrlProvider()
        return try {
            val obj = json.parseToJsonElement(args).jsonObject
            val title = obj["title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (title.isBlank()) return "Error: 'title' parameter required"
            val head = normalizeRemoteTaskRepositoryBranchName(
                obj["head"]?.jsonPrimitive?.contentOrNull
            )
            if (head.isNullOrBlank()) return "Error: 'head' parameter required"
            val base = normalizeRemoteTaskRepositoryBranchName(
                obj["base"]?.jsonPrimitive?.contentOrNull
            ) ?: loadRemoteTaskRepositoryDefaultBranch(
                repo = repo,
                token = token,
                apiBaseUrl = apiBaseUrl
            ) ?: return "Error: 无法确定默认目标分支，请显式传入 'base'"
            val body = obj["body"]?.jsonPrimitive?.contentOrNull
            val draft = obj["draft"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()?.let {
                when (it) {
                    "true" -> true
                    "false" -> false
                    else -> null
                }
            } ?: false

            val result = runRemoteTaskRepositoryApiRequest(
                apiBaseUrl = apiBaseUrl,
                token = token,
                path = buildRemoteTaskRepositoryPullsPath(repo),
                method = "POST",
                jsonBody = buildJsonObject {
                    put("title", title)
                    put("head", head)
                    put("base", base)
                    body?.let { put("body", it) }
                    put("draft", draft)
                }.toString(),
                allowedCodes = setOf(201)
            )
            if (!result.success) {
                return buildString {
                    append("Error: 创建远端任务仓库 PR 失败。")
                    append("\n源分支: ")
                    append(head)
                    append("\n目标分支: ")
                    append(base)
                    result.error?.takeIf { it.isNotBlank() }?.let {
                        append("\n底层结果: ")
                        append(it)
                    }
                }
            }
            val createdPr = parseRemoteTaskRepositoryCreatedPr(result.body)
            if (createdPr == null) {
                return "已创建远端任务仓库 PR，但未能解析返回结果。\n原始响应:\n${result.body}"
            }
            if (!createdPr.state.equals("open", ignoreCase = true)) {
                return "Error: 创建远端任务仓库 PR 后校验失败。PR #${createdPr.number} 当前状态不是 open。"
            }
            buildString {
                append("已创建当前任务仓库 Pull Request：")
                append(repo.label)
                append("\n#")
                append(createdPr.number)
                append(" ")
                append(createdPr.title)
                append("\n源分支: ")
                append(createdPr.head)
                append("\n目标分支: ")
                append(createdPr.base)
                createdPr.url?.takeIf { it.isNotBlank() }?.let {
                    append("\n链接: ")
                    append(it)
                }
            }
        } catch (e: Exception) {
            "Error: 创建当前任务仓库 PR 失败: ${e.message}"
        }
    }
}

internal class TaskRepoClosePrTool(
    private val repositoryProvider: () -> RemoteTaskRepositoryTarget?,
    private val githubTokenProvider: () -> String,
    private val githubApiBaseUrlProvider: () -> String
) : Tool {
    override val name: String = "task_repo_close_pr"
    override val description: String =
        "关闭当前任务仓库里的一个 Pull Request。适合清理测试 PR 或结束不再需要的远端 PR。"
    override val parameters: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "number" to mapOf(
                "type" to "integer",
                "description" to "要关闭的 Pull Request 编号"
            )
        ),
        "required" to listOf("number")
    )

    private val json = Json { ignoreUnknownKeys = true }

    override fun buildApprovalRequest(args: String): ToolApprovalRequest? {
        val repo = repositoryProvider() ?: return null
        return try {
            val obj = json.parseToJsonElement(args).jsonObject
            val number = obj["number"]?.jsonPrimitive?.intOrNull ?: return null
            ToolApprovalRequest(
                toolName = name,
                summary = "关闭远端任务仓库 Pull Request",
                detail = "${repo.label}: PR #$number",
                riskLevel = ApprovalRiskLevel.HIGH,
                rawArgs = args,
                approvalScopeTokens = setOf("mcp:github:write")
            )
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun execute(args: String): String {
        val repo = repositoryProvider()
            ?: return "Error: 当前会话没有绑定远端任务仓库。请先在项目页把 GitHub 仓库设为任务仓库。"
        val token = githubTokenProvider().trim()
        if (token.isBlank()) return "Error: 请先在设置页填写 GitHub Token。"
        val apiBaseUrl = githubApiBaseUrlProvider()
        return try {
            val obj = json.parseToJsonElement(args).jsonObject
            val number = obj["number"]?.jsonPrimitive?.intOrNull
                ?: return "Error: 'number' parameter required"
            val result = runRemoteTaskRepositoryApiRequest(
                apiBaseUrl = apiBaseUrl,
                token = token,
                path = buildRemoteTaskRepositoryPullPath(repo, number),
                method = "PATCH",
                jsonBody = buildJsonObject {
                    put("state", "closed")
                }.toString(),
                allowedCodes = setOf(200)
            )
            if (!result.success) {
                return buildString {
                    append("Error: 关闭远端任务仓库 PR 失败。")
                    append("\nPR: #")
                    append(number)
                    result.error?.takeIf { it.isNotBlank() }?.let {
                        append("\n底层结果: ")
                        append(it)
                    }
                }
            }
            val updatedPr = parseRemoteTaskRepositoryCreatedPr(result.body)
            if (updatedPr != null && !updatedPr.state.equals("closed", ignoreCase = true)) {
                return "Error: 关闭远端任务仓库 PR 后校验失败。PR #${updatedPr.number} 当前状态不是 closed。"
            }
            return buildString {
                append("已关闭当前任务仓库 Pull Request：")
                append(repo.label)
                append("\nPR: #")
                append(updatedPr?.number ?: number)
                updatedPr?.title?.takeIf { it.isNotBlank() }?.let {
                    append(" ")
                    append(it)
                }
                updatedPr?.url?.takeIf { it.isNotBlank() }?.let {
                    append("\n链接: ")
                    append(it)
                }
            }
        } catch (e: Exception) {
            "Error: 关闭当前任务仓库 PR 失败: ${e.message}"
        }
    }
}

internal class TaskRepoDeleteBranchTool(
    private val repositoryProvider: () -> RemoteTaskRepositoryTarget?,
    private val githubTokenProvider: () -> String,
    private val githubApiBaseUrlProvider: () -> String
) : Tool {
    override val name: String = "task_repo_delete_branch"
    override val description: String =
        "删除当前任务仓库中的远端分支。适合清理测试分支；删除前建议先确认该分支不再需要。"
    override val parameters: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "branch" to mapOf(
                "type" to "string",
                "description" to "要删除的远端分支名，例如 feature/my-change"
            )
        ),
        "required" to listOf("branch")
    )

    private val json = Json { ignoreUnknownKeys = true }

    override fun buildApprovalRequest(args: String): ToolApprovalRequest? {
        val repo = repositoryProvider() ?: return null
        return try {
            val obj = json.parseToJsonElement(args).jsonObject
            val branch = normalizeRemoteTaskRepositoryBranchName(
                obj["branch"]?.jsonPrimitive?.contentOrNull
            )
            if (branch.isNullOrBlank()) return null
            ToolApprovalRequest(
                toolName = name,
                summary = "删除远端任务仓库分支",
                detail = "${repo.label}: $branch",
                riskLevel = ApprovalRiskLevel.HIGH,
                rawArgs = args,
                approvalScopeTokens = setOf("mcp:github:write")
            )
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun execute(args: String): String {
        val repo = repositoryProvider()
            ?: return "Error: 当前会话没有绑定远端任务仓库。请先在项目页把 GitHub 仓库设为任务仓库。"
        val token = githubTokenProvider().trim()
        if (token.isBlank()) return "Error: 请先在设置页填写 GitHub Token。"
        val apiBaseUrl = githubApiBaseUrlProvider()
        return try {
            val obj = json.parseToJsonElement(args).jsonObject
            val branch = normalizeRemoteTaskRepositoryBranchName(
                obj["branch"]?.jsonPrimitive?.contentOrNull
            ) ?: return "Error: 'branch' parameter required"
            val defaultBranch = loadRemoteTaskRepositoryDefaultBranch(
                repo = repo,
                token = token,
                apiBaseUrl = apiBaseUrl
            )
            if (!defaultBranch.isNullOrBlank() && branch == defaultBranch) {
                return "Error: 不能删除默认分支 `$branch`。"
            }
            val result = runRemoteTaskRepositoryApiRequest(
                apiBaseUrl = apiBaseUrl,
                token = token,
                path = buildRemoteTaskRepositoryGitBranchRefDeletePath(repo, branch),
                method = "DELETE",
                allowedCodes = setOf(204)
            )
            if (!result.success) {
                return buildString {
                    append("Error: 删除远端任务仓库分支失败。")
                    append("\n分支: ")
                    append(branch)
                    result.error?.takeIf { it.isNotBlank() }?.let {
                        append("\n底层结果: ")
                        append(it)
                    }
                }
            }
            verifyRemoteTaskRepositoryBranchState(
                repo = repo,
                branch = branch,
                shouldExist = false,
                token = token,
                apiBaseUrl = apiBaseUrl
            )?.let { verificationError ->
                return "Error: 删除远端任务仓库分支后校验失败。$verificationError"
            }
            "已删除当前任务仓库远端分支：${repo.label}\n分支: $branch"
        } catch (e: Exception) {
            "Error: 删除当前任务仓库分支失败: ${e.message}"
        }
    }
}

internal class TaskRepoReadFileTool(
    private val repositoryProvider: () -> RemoteTaskRepositoryTarget?,
    private val githubTokenProvider: () -> String,
    private val githubApiBaseUrlProvider: () -> String
) : Tool {
    override val name: String = "task_repo_read_file"
    override val description: String =
        "读取当前任务仓库里的单个文件内容。自动绑定当前远端任务仓库，不需要再手动填写 owner/repo。大文件若未指定 startLine/endLine，会默认返回首个安全窗口，避免整文件灌入上下文。"
    override val parameters: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "path" to mapOf(
                "type" to "string",
                "description" to "仓库内文件路径，例如 app/src/main/AndroidManifest.xml"
            ),
            "branch" to mapOf(
                "type" to "string",
                "description" to "可选。指定分支；不填时使用仓库默认分支"
            ),
            "startLine" to mapOf(
                "type" to "integer",
                "description" to "可选。按 1 开始的起始行号，适合大文件分段阅读"
            ),
            "endLine" to mapOf(
                "type" to "integer",
                "description" to "可选。结束行号，未填写时默认到文件末尾"
            )
        ),
        "required" to listOf("path")
    )

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(args: String): String {
        val repo = repositoryProvider()
            ?: return "Error: 当前会话没有绑定远端任务仓库。请先在项目页把 GitHub 仓库设为任务仓库。"
        val token = githubTokenProvider().trim()
        if (token.isBlank()) return "Error: 请先在设置页填写 GitHub Token。"
        return try {
            val obj = json.parseToJsonElement(args).jsonObject
            val path = obj["path"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (path.isBlank()) return "Error: 'path' parameter required"
            val branch = obj["branch"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty().ifBlank { null }
            val startLine = obj["startLine"]?.jsonPrimitive?.intOrNull
            val endLine = obj["endLine"]?.jsonPrimitive?.intOrNull
            val result = loadRemoteTaskRepositoryFile(
                repo = repo,
                path = path,
                branch = branch,
                token = token,
                apiBaseUrl = githubApiBaseUrlProvider()
            )
            when {
                result.file != null -> formatRemoteTaskRepositoryFile(
                    file = result.file,
                    repoLabel = repo.label,
                    startLine = startLine,
                    endLine = endLine
                )
                result.rawResult != null -> result.rawResult
                else -> "Error: 当前远端文件读取失败。"
            }
        } catch (e: Exception) {
            "Error: 读取当前任务仓库文件失败: ${e.message}"
        }
    }
}

internal class TaskRepoSearchReplaceTool(
    private val repositoryProvider: () -> RemoteTaskRepositoryTarget?,
    private val githubTokenProvider: () -> String,
    private val githubApiBaseUrlProvider: () -> String
) : Tool {
    override val name: String = "task_repo_search_replace"
    override val description: String =
        "在当前任务仓库的单个文件里做精确 SEARCH/REPLACE，并直接提交到远端分支。适合小范围修复，避免整文件重写。"
    override val parameters: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "path" to mapOf(
                "type" to "string",
                "description" to "仓库内文件路径"
            ),
            "search" to mapOf(
                "type" to "string",
                "description" to "要查找的精确文本，必须在目标文件中唯一匹配"
            ),
            "replace" to mapOf(
                "type" to "string",
                "description" to "替换后的文本"
            ),
            "message" to mapOf(
                "type" to "string",
                "description" to "提交说明"
            ),
            "branch" to mapOf(
                "type" to "string",
                "description" to "目标分支。建议显式填写，例如 main、master 或 feature/xxx"
            )
        ),
        "required" to listOf("path", "search", "replace", "message")
    )

    private val json = Json { ignoreUnknownKeys = true }

    override fun buildApprovalRequest(args: String): ToolApprovalRequest? {
        val repo = repositoryProvider() ?: return null
        return try {
            val obj = json.parseToJsonElement(args).jsonObject
            val path = obj["path"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (path.isBlank()) return null
            ToolApprovalRequest(
                toolName = name,
                summary = "精确替换远端任务仓库文件",
                detail = "${repo.label}/$path",
                riskLevel = ApprovalRiskLevel.HIGH,
                rawArgs = args,
                approvalScopeTokens = setOf("mcp:github:write")
            )
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun execute(args: String): String = executeWithResult(args).output

    override suspend fun executeWithResult(args: String): ToolExecutionResult {
        val repo = repositoryProvider()
            ?: return ToolExecutionResult("Error: 当前会话没有绑定远端任务仓库。请先在项目页把 GitHub 仓库设为任务仓库。")
        val token = githubTokenProvider().trim()
        if (token.isBlank()) return ToolExecutionResult("Error: 请先在设置页填写 GitHub Token。")
        val apiBaseUrl = githubApiBaseUrlProvider()
        return try {
            val obj = json.parseToJsonElement(args).jsonObject
            val path = obj["path"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val search = obj["search"]?.jsonPrimitive?.contentOrNull
                ?: return ToolExecutionResult("Error: 'search' parameter required")
            val replace = obj["replace"]?.jsonPrimitive?.contentOrNull
                ?: return ToolExecutionResult("Error: 'replace' parameter required")
            val message = obj["message"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (path.isBlank()) return ToolExecutionResult("Error: 'path' parameter required")
            if (search.isEmpty()) return ToolExecutionResult("Error: 'search' parameter cannot be empty")
            if (message.isBlank()) return ToolExecutionResult("Error: 'message' parameter required")
            val requestedBranch = obj["branch"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty().ifBlank { null }
            val existingLoadResult = loadRemoteTaskRepositoryFile(
                repo = repo,
                path = path,
                branch = requestedBranch,
                token = token,
                apiBaseUrl = apiBaseUrl
            )
            val existingFile = existingLoadResult.file ?: return ToolExecutionResult(
                buildString {
                    append("Error: 无法读取远端文件，不能执行精确替换：")
                    append(repo.label)
                    append("/")
                    append(path)
                    existingLoadResult.rawResult?.takeIf { it.isNotBlank() }?.let {
                        append("\n底层结果: ")
                        append(it)
                    }
                }
            )
            val matchCount = countExactOccurrences(existingFile.content, search)
            if (matchCount == 0) {
                return ToolExecutionResult("Error: SEARCH text not found in remote file `${repo.label}/$path`")
            }
            if (matchCount > 1) {
                return ToolExecutionResult(
                    "Error: SEARCH text found $matchCount times in remote file `${repo.label}/$path` — must be unique"
                )
            }
            val newContent = existingFile.content.replace(search, replace)
            if (newContent == existingFile.content) {
                return ToolExecutionResult("Error: SEARCH/REPLACE produced no content change for `${repo.label}/$path`")
            }
            val writeResult = writeRemoteTaskRepositoryFile(
                repo = repo,
                path = path,
                content = newContent,
                message = message,
                requestedBranch = requestedBranch,
                token = token,
                apiBaseUrl = apiBaseUrl
            )
            if (!writeResult.success || writeResult.branch == null || writeResult.fileChange == null) {
                return ToolExecutionResult(
                    buildString {
                        append("Error: 精确替换远端任务仓库文件失败。")
                        writeResult.branch?.takeIf { it.isNotBlank() }?.let {
                            append(" 分支 `")
                            append(it)
                            append("` 写入未通过校验。")
                        }
                        writeResult.error?.takeIf { it.isNotBlank() }?.let {
                            append("\n底层结果: ")
                            append(it)
                        }
                        writeResult.rawResult?.takeIf { it.isNotBlank() }?.let {
                            append("\n接口返回: ")
                            append(it)
                        }
                    }
                )
            }
            ToolExecutionResult(
                output = buildString {
                    append("已精确替换当前任务仓库文件：")
                    append(repo.label)
                    append("/")
                    append(path)
                    append("\n分支: ")
                    append(writeResult.branch)
                    append("\n提交说明: ")
                    append(message)
                    append("\n匹配次数: 1")
                    append("\n写后校验: 已通过")
                    writeResult.rawResult?.takeIf { it.isNotBlank() }?.let {
                        append("\n底层结果: ")
                        append(it)
                    }
                },
                fileChanges = listOf(writeResult.fileChange)
            )
        } catch (e: Exception) {
            ToolExecutionResult("Error: 精确替换当前任务仓库文件失败: ${e.message}")
        }
    }
}

internal class TaskRepoApplyPatchTool(
    private val repositoryProvider: () -> RemoteTaskRepositoryTarget?,
    private val githubTokenProvider: () -> String,
    private val githubApiBaseUrlProvider: () -> String
) : Tool {
    override val name: String = "task_repo_apply_patch"
    override val description: String =
        "对当前任务仓库里的一个或多个已有文件应用精确补丁，并合并为一次远端提交。适合大文件或多文件的小范围修改，避免让模型传整份文件内容。"
    override val parameters: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "message" to mapOf(
                "type" to "string",
                "description" to "提交说明"
            ),
            "branch" to mapOf(
                "type" to "string",
                "description" to "目标分支。建议显式填写，例如 main、master 或 feature/xxx"
            ),
            "patch_text" to mapOf(
                "type" to "string",
                "description" to "可选。接近本地 apply_patch 的文本补丁，只支持 *** Update File 场景。适合直接提交多文件 diff 风格的精确修改。"
            ),
            "file_patches" to mapOf(
                "type" to "array",
                "description" to "要应用补丁的文件列表。可与 patch_text 二选一；若同时提供，会合并后统一校验重复路径。",
                "items" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "path" to mapOf(
                            "type" to "string",
                            "description" to "仓库内已有文件路径"
                        ),
                        "hunks" to mapOf(
                            "type" to "array",
                            "description" to "按顺序应用的精确 SEARCH/REPLACE 片段；当 search 在文件中重复出现时，可额外提供 context_before/context_after 做上下文定位",
                            "items" to mapOf(
                                "type" to "object",
                                "properties" to mapOf(
                                    "search" to mapOf(
                                        "type" to "string",
                                        "description" to "要查找的精确文本"
                                    ),
                                    "replace" to mapOf(
                                        "type" to "string",
                                        "description" to "替换后的文本"
                                    ),
                                    "context_before" to mapOf(
                                        "type" to "string",
                                        "description" to "可选。必须紧邻 search 之前出现的上下文片段，用于锁定重复片段中的目标位置"
                                    ),
                                    "context_after" to mapOf(
                                        "type" to "string",
                                        "description" to "可选。必须紧邻 search 之后出现的上下文片段，用于锁定重复片段中的目标位置"
                                    ),
                                    "line_hint" to mapOf(
                                        "type" to "integer",
                                        "description" to "可选。1 开始的目标行号提示。当 search 重复出现时，优先匹配最接近该行号的片段，并在错误反馈里显示候选行。"
                                    ),
                                    "expected_matches" to mapOf(
                                        "type" to "integer",
                                        "description" to "可选。预期匹配次数，默认 1。若填写了上下文，则以带上下文约束后的匹配数为准。"
                                    )
                                ),
                                "required" to listOf("search", "replace")
                            )
                        )
                    ),
                    "required" to listOf("path", "hunks")
                )
            )
        ),
        "required" to listOf("message")
    )

    private val json = Json { ignoreUnknownKeys = true }

    override fun buildApprovalRequest(args: String): ToolApprovalRequest? {
        val repo = repositoryProvider() ?: return null
        return try {
            val obj = json.parseToJsonElement(args).jsonObject
            val filePatches = resolveRemoteTaskRepositoryApplyPatchFiles(obj)
            if (filePatches.isEmpty()) return null
            ToolApprovalRequest(
                toolName = name,
                summary = "对远端任务仓库应用精确补丁",
                detail = "${repo.label} (${filePatches.size} 个文件)",
                riskLevel = ApprovalRiskLevel.HIGH,
                rawArgs = args,
                approvalScopeTokens = setOf("mcp:github:write")
            )
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun execute(args: String): String = executeWithResult(args).output

    override suspend fun executeWithResult(args: String): ToolExecutionResult {
        val repo = repositoryProvider()
            ?: return ToolExecutionResult("Error: 当前会话没有绑定远端任务仓库。请先在项目页把 GitHub 仓库设为任务仓库。")
        val token = githubTokenProvider().trim()
        if (token.isBlank()) return ToolExecutionResult("Error: 请先在设置页填写 GitHub Token。")
        val apiBaseUrl = githubApiBaseUrlProvider()
        return try {
            val obj = json.parseToJsonElement(args).jsonObject
            val message = obj["message"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (message.isBlank()) return ToolExecutionResult("Error: 'message' parameter required")
            val requestedBranch = obj["branch"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty().ifBlank { null }
            val filePatches = resolveRemoteTaskRepositoryApplyPatchFiles(obj)
            if (filePatches.isEmpty()) {
                return ToolExecutionResult("Error: 请至少提供 `file_patches` 或 `patch_text` 其中一种补丁输入。")
            }
            val duplicatePaths = filePatches.groupBy { it.path }.filterValues { it.size > 1 }.keys.sorted()
            if (duplicatePaths.isNotEmpty()) {
                return ToolExecutionResult("Error: 同一次远端补丁提交里存在重复路径: ${duplicatePaths.joinToString(", ")}")
            }

            val batchChanges = mutableListOf<RemoteTaskRepositoryBatchChange>()
            val fileChanges = mutableListOf<ToolFileChange>()
            var totalHunks = 0

            for (filePatch in filePatches) {
                val existingForWrite = loadRemoteTaskRepositoryExistingFileForWrite(
                    repo = repo,
                    path = filePatch.path,
                    requestedBranch = requestedBranch,
                    token = token,
                    apiBaseUrl = apiBaseUrl
                ) ?: return ToolExecutionResult(
                    "Error: 远端补丁工具只能修改已有文件，未找到 `${repo.label}/${filePatch.path}`。"
                )
                val beforeContent = existingForWrite.file.content
                val patchedContent = applyRemoteTaskRepositoryPatchToContent(
                    path = filePatch.path,
                    originalContent = beforeContent,
                    hunks = filePatch.hunks
                ) ?: return ToolExecutionResult(
                    "Error: 远端补丁 `${repo.label}/${filePatch.path}` 未产生实际内容变化。"
                )
                totalHunks += filePatch.hunks.size
                batchChanges += RemoteTaskRepositoryBatchChange(
                    path = filePatch.path,
                    operation = "write",
                    content = patchedContent
                )
                fileChanges += ToolFileChange(
                    path = "github://${repo.label}/${filePatch.path}",
                    operation = "write",
                    beforeContent = beforeContent,
                    afterContent = patchedContent,
                    diffPreview = buildDiffPreview(beforeContent, patchedContent)
                )
            }

            val branchCandidates = if (requestedBranch != null) {
                listOf(requestedBranch)
            } else {
                val resolvedDefaultBranch = loadRemoteTaskRepositoryDefaultBranch(
                    repo = repo,
                    token = token,
                    apiBaseUrl = apiBaseUrl
                )
                listOfNotNull(
                    resolvedDefaultBranch,
                    "main",
                    "master"
                ).distinct()
            }

            var lastError: String? = null
            var batchResult: RemoteTaskRepositoryBatchCommitResult? = null
            for (branch in branchCandidates) {
                val result = runRemoteTaskRepositoryBatchCommit(
                    repo = repo,
                    branch = branch,
                    message = message,
                    changes = batchChanges,
                    token = token,
                    apiBaseUrl = apiBaseUrl
                )
                if (result.success) {
                    batchResult = result
                    break
                }
                lastError = result.error
            }

            val success = batchResult ?: return ToolExecutionResult(
                buildString {
                    append("Error: 对远端任务仓库应用精确补丁失败。")
                    if (!requestedBranch.isNullOrBlank()) {
                        append(" 分支 `")
                        append(requestedBranch)
                        append("` 提交未成功。")
                    } else {
                        append(" 已尝试默认分支和 main/master 回退，但都失败。建议显式传入 branch。")
                    }
                    lastError?.takeIf { it.isNotBlank() }?.let {
                        append("\n底层结果: ")
                        append(it)
                    }
                }
            )

            ToolExecutionResult(
                output = buildString {
                    append("已对当前任务仓库应用精确补丁：")
                    append(repo.label)
                    append("\n分支: ")
                    append(success.branch)
                    append("\n提交说明: ")
                    append(message)
                    append("\n文件数: ")
                    append(filePatches.size)
                    append("\nHunk 数: ")
                    append(totalHunks)
                    success.commitSha?.takeIf { it.isNotBlank() }?.let {
                        append("\n提交 SHA: ")
                        append(it)
                    }
                    success.commitUrl?.takeIf { it.isNotBlank() }?.let {
                        append("\n提交地址: ")
                        append(it)
                    }
                    append("\n写后校验: 已通过")
                },
                fileChanges = fileChanges
            )
        } catch (e: Exception) {
            ToolExecutionResult("Error: 对远端任务仓库应用精确补丁失败: ${e.message}")
        }
    }
}

internal class TaskRepoUpdateFileTool(
    private val repositoryProvider: () -> RemoteTaskRepositoryTarget?,
    private val githubTokenProvider: () -> String,
    private val githubApiBaseUrlProvider: () -> String
) : Tool {
    override val name: String = "task_repo_update_file"
    override val description: String =
        "更新当前任务仓库里的单个文件并直接提交到远端分支。自动绑定当前远端任务仓库，不需要再手动填写 owner/repo。超大已有文件的小改动应优先使用 task_repo_search_replace，避免整文件重写。"
    override val parameters: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "path" to mapOf(
                "type" to "string",
                "description" to "仓库内文件路径"
            ),
            "content" to mapOf(
                "type" to "string",
                "description" to "新的完整文件内容"
            ),
            "message" to mapOf(
                "type" to "string",
                "description" to "提交说明"
            ),
            "branch" to mapOf(
                "type" to "string",
                "description" to "目标分支。建议显式填写，例如 main、master 或 feature/xxx"
            )
        ),
        "required" to listOf("path", "content", "message")
    )

    private val json = Json { ignoreUnknownKeys = true }

    override fun buildApprovalRequest(args: String): ToolApprovalRequest? {
        val repo = repositoryProvider() ?: return null
        return try {
            val obj = json.parseToJsonElement(args).jsonObject
            val path = obj["path"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (path.isBlank()) return null
            ToolApprovalRequest(
                toolName = name,
                summary = "更新远端任务仓库文件",
                detail = "${repo.label}/$path",
                riskLevel = ApprovalRiskLevel.HIGH,
                rawArgs = args,
                approvalScopeTokens = setOf("mcp:github:write")
            )
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun execute(args: String): String = executeWithResult(args).output

    override suspend fun executeWithResult(args: String): ToolExecutionResult {
        val repo = repositoryProvider()
            ?: return ToolExecutionResult("Error: 当前会话没有绑定远端任务仓库。请先在项目页把 GitHub 仓库设为任务仓库。")
        val token = githubTokenProvider().trim()
        if (token.isBlank()) return ToolExecutionResult("Error: 请先在设置页填写 GitHub Token。")
        val apiBaseUrl = githubApiBaseUrlProvider()
        return try {
            val obj = json.parseToJsonElement(args).jsonObject
            val path = obj["path"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val content = obj["content"]?.jsonPrimitive?.contentOrNull
                ?: return ToolExecutionResult("Error: 'content' parameter required")
            val message = obj["message"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (path.isBlank()) return ToolExecutionResult("Error: 'path' parameter required")
            if (message.isBlank()) return ToolExecutionResult("Error: 'message' parameter required")
            val requestedBranch = obj["branch"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty().ifBlank { null }
            loadRemoteTaskRepositoryExistingFileForWrite(
                repo = repo,
                path = path,
                requestedBranch = requestedBranch,
                token = token,
                apiBaseUrl = apiBaseUrl
            )?.let { existingForGuard ->
                buildRemoteTaskRepositoryWholeFileOverwriteGuardError(
                    repo = repo,
                    path = path,
                    existingFile = existingForGuard.file,
                    candidateBranch = existingForGuard.branch,
                    newContent = content,
                    toolName = name
                )?.let { guardError ->
                    return ToolExecutionResult(guardError)
                }
            }
            val writeResult = writeRemoteTaskRepositoryFile(
                repo = repo,
                path = path,
                content = content,
                message = message,
                requestedBranch = requestedBranch,
                token = token,
                apiBaseUrl = apiBaseUrl
            )
            if (!writeResult.success || writeResult.branch == null || writeResult.fileChange == null) {
                return ToolExecutionResult(
                    output = buildString {
                        append("Error: 更新远端任务仓库文件失败。")
                        if (writeResult.branch != null) {
                            append(" 分支 `")
                            append(writeResult.branch)
                            append("` 写入未通过校验。")
                        } else if (!requestedBranch.isNullOrBlank()) {
                            append(" 分支 `")
                            append(requestedBranch)
                            append("` 未成功写入。")
                        } else {
                            append(" 已尝试默认分支 main/master，但都失败。建议显式传入 branch。")
                        }
                        writeResult.error?.takeIf { it.isNotBlank() }?.let {
                            append("\n底层结果: ")
                            append(it)
                        }
                        writeResult.rawResult?.takeIf { it.isNotBlank() }?.let {
                            append("\n接口返回: ")
                            append(it)
                        }
                    }
                )
            }
            ToolExecutionResult(
                output = buildString {
                    append("已更新当前任务仓库文件：")
                    append(repo.label)
                    append("/")
                    append(path)
                    append("\n分支: ")
                    append(writeResult.branch)
                    append("\n提交说明: ")
                    append(message)
                    append("\n写后校验: 已通过")
                    writeResult.rawResult?.takeIf { it.isNotBlank() }?.let {
                        append("\n底层结果: ")
                        append(it)
                    }
                },
                fileChanges = listOf(writeResult.fileChange)
            )
        } catch (e: Exception) {
            ToolExecutionResult("Error: 更新当前任务仓库文件失败: ${e.message}")
        }
    }
}

internal class TaskRepoDeleteFileTool(
    private val repositoryProvider: () -> RemoteTaskRepositoryTarget?,
    private val githubTokenProvider: () -> String,
    private val githubApiBaseUrlProvider: () -> String
) : Tool {
    override val name: String = "task_repo_delete_file"
    override val description: String =
        "真正删除当前任务仓库里的单个文件，并提交到远端分支。不要用写空内容来模拟删除。"
    override val parameters: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "path" to mapOf(
                "type" to "string",
                "description" to "要删除的仓库内文件路径"
            ),
            "message" to mapOf(
                "type" to "string",
                "description" to "删除提交说明"
            ),
            "branch" to mapOf(
                "type" to "string",
                "description" to "目标分支。建议显式填写，例如 main、master 或 feature/xxx"
            )
        ),
        "required" to listOf("path", "message")
    )

    private val json = Json { ignoreUnknownKeys = true }

    override fun buildApprovalRequest(args: String): ToolApprovalRequest? {
        val repo = repositoryProvider() ?: return null
        return try {
            val obj = json.parseToJsonElement(args).jsonObject
            val path = obj["path"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (path.isBlank()) return null
            ToolApprovalRequest(
                toolName = name,
                summary = "删除远端任务仓库文件",
                detail = "${repo.label}/$path",
                riskLevel = ApprovalRiskLevel.HIGH,
                rawArgs = args,
                approvalScopeTokens = setOf("mcp:github:write")
            )
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun execute(args: String): String = executeWithResult(args).output

    override suspend fun executeWithResult(args: String): ToolExecutionResult {
        val repo = repositoryProvider()
            ?: return ToolExecutionResult("Error: 当前会话没有绑定远端任务仓库。请先在项目页把 GitHub 仓库设为任务仓库。")
        val token = githubTokenProvider().trim()
        if (token.isBlank()) return ToolExecutionResult("Error: 请先在设置页填写 GitHub Token。")
        val apiBaseUrl = githubApiBaseUrlProvider()
        return try {
            val obj = json.parseToJsonElement(args).jsonObject
            val path = obj["path"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val message = obj["message"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (path.isBlank()) return ToolExecutionResult("Error: 'path' parameter required")
            if (message.isBlank()) return ToolExecutionResult("Error: 'message' parameter required")
            val requestedBranch = obj["branch"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty().ifBlank { null }
            val existingFile = loadRemoteTaskRepositoryFile(
                repo = repo,
                path = path,
                branch = requestedBranch,
                token = token,
                apiBaseUrl = apiBaseUrl
            ).file ?: return ToolExecutionResult("Error: 远端文件不存在，无法删除：${repo.label}/$path")
            val resolvedDefaultBranch = requestedBranch ?: loadRemoteTaskRepositoryDefaultBranch(
                repo = repo,
                token = token,
                apiBaseUrl = apiBaseUrl
            )
            val branchCandidates = listOfNotNull(
                requestedBranch,
                existingFile.branch?.takeIf { it.isNotBlank() },
                resolvedDefaultBranch,
                "main",
                "master"
            ).distinct()
            var lastResult: String? = null
            var chosenBranch: String? = null
            for (branch in branchCandidates) {
                val result = runRemoteTaskRepositoryApiRequest(
                    apiBaseUrl = apiBaseUrl,
                    token = token,
                    path = buildRemoteTaskRepositoryContentsPath(repo, path),
                    method = "DELETE",
                    jsonBody = buildJsonObject {
                        put("message", message)
                        put("sha", existingFile.sha.orEmpty())
                        put("branch", branch)
                    }.toString(),
                    allowedCodes = setOf(200)
                )
                if (result.success) {
                    chosenBranch = branch
                    lastResult = result.body.ifBlank { "OK" }
                    break
                }
                lastResult = result.error ?: result.body
            }
            if (chosenBranch == null) {
                return ToolExecutionResult(
                    output = buildString {
                        append("Error: 删除远端任务仓库文件失败。")
                        if (!requestedBranch.isNullOrBlank()) {
                            append(" 分支 `")
                            append(requestedBranch)
                            append("` 删除未成功。")
                        } else {
                            append(" 已尝试默认分支 main/master，但都失败。建议显式传入 branch。")
                        }
                        lastResult?.takeIf { it.isNotBlank() }?.let {
                            append("\n底层结果: ")
                            append(it)
                        }
                    }
                )
            }
            val verifiedBranch = chosenBranch
            verifyRemoteTaskRepositoryFileDeletion(
                repo = repo,
                path = path,
                branch = verifiedBranch,
                token = token,
                apiBaseUrl = apiBaseUrl
            )?.let { verificationError ->
                return ToolExecutionResult("Error: 删除远端任务仓库文件后校验失败。$verificationError")
            }
            ToolExecutionResult(
                output = buildString {
                    append("已删除当前任务仓库文件：")
                    append(repo.label)
                    append("/")
                    append(path)
                    append("\n分支: ")
                    append(verifiedBranch)
                    append("\n提交说明: ")
                    append(message)
                    lastResult?.takeIf { it.isNotBlank() }?.let {
                        append("\n底层结果: ")
                        append(it)
                    }
                },
                fileChanges = listOf(
                    ToolFileChange(
                        path = "github://${repo.label}/$path",
                        operation = "delete",
                        beforeContent = existingFile.content,
                        afterContent = null,
                        diffPreview = buildDiffPreview(existingFile.content, null)
                    )
                )
            )
        } catch (e: Exception) {
            ToolExecutionResult("Error: 删除远端任务仓库文件失败: ${e.message}")
        }
    }
}

internal class TaskRepoCommitFilesTool(
    private val repositoryProvider: () -> RemoteTaskRepositoryTarget?,
    private val githubTokenProvider: () -> String,
    private val githubApiBaseUrlProvider: () -> String
) : Tool {
    override val name: String = "task_repo_commit_files"
    override val description: String =
        "在当前任务仓库中一次性提交多个文件变更。适合需要在同一次提交里同时新增、修改、删除多个文件的场景；若其中包含超大已有文件，优先先用 task_repo_search_replace 做精确修改，避免整文件重写。"
    override val parameters: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "message" to mapOf(
                "type" to "string",
                "description" to "提交说明"
            ),
            "branch" to mapOf(
                "type" to "string",
                "description" to "目标分支。建议显式填写，例如 main、master 或 feature/xxx"
            ),
            "changes" to mapOf(
                "type" to "array",
                "description" to "本次提交包含的文件变更列表",
                "items" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "path" to mapOf(
                            "type" to "string",
                            "description" to "仓库内文件路径"
                        ),
                        "operation" to mapOf(
                            "type" to "string",
                            "description" to "写入或删除。可填 write、create、update、delete"
                        ),
                        "content" to mapOf(
                            "type" to "string",
                            "description" to "当 operation 为 write/create/update 时，填写新的完整文件内容"
                        )
                    ),
                    "required" to listOf("path", "operation")
                )
            )
        ),
        "required" to listOf("message", "changes")
    )

    private val json = Json { ignoreUnknownKeys = true }

    override fun buildApprovalRequest(args: String): ToolApprovalRequest? {
        val repo = repositoryProvider() ?: return null
        return try {
            val obj = json.parseToJsonElement(args).jsonObject
            val changes = obj["changes"]?.jsonArray.orEmpty()
            ToolApprovalRequest(
                toolName = name,
                summary = "批量提交远端任务仓库文件",
                detail = "${repo.label} (${changes.size} 项变更)",
                riskLevel = ApprovalRiskLevel.HIGH,
                rawArgs = args,
                approvalScopeTokens = setOf("mcp:github:write")
            )
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun execute(args: String): String = executeWithResult(args).output

    override suspend fun executeWithResult(args: String): ToolExecutionResult {
        val repo = repositoryProvider()
            ?: return ToolExecutionResult("Error: 当前会话没有绑定远端任务仓库。请先在项目页把 GitHub 仓库设为任务仓库。")
        val token = githubTokenProvider().trim()
        if (token.isBlank()) return ToolExecutionResult("Error: 请先在设置页填写 GitHub Token。")
        val apiBaseUrl = githubApiBaseUrlProvider()
        return try {
            val obj = json.parseToJsonElement(args).jsonObject
            val message = obj["message"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (message.isBlank()) return ToolExecutionResult("Error: 'message' parameter required")
            val requestedBranch = obj["branch"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty().ifBlank { null }
            val changes = parseRemoteTaskRepositoryBatchChanges(obj["changes"])
            if (changes.isEmpty()) return ToolExecutionResult("Error: 'changes' must contain at least one file change")
            val duplicatePaths = changes.groupBy { it.path }.filterValues { it.size > 1 }.keys.sorted()
            if (duplicatePaths.isNotEmpty()) {
                return ToolExecutionResult("Error: 同一次批量提交里存在重复路径: ${duplicatePaths.joinToString(", ")}")
            }
            for (change in changes) {
                if (change.operation != "write") continue
                val afterContent = change.content ?: continue
                val existingForGuard = loadRemoteTaskRepositoryExistingFileForWrite(
                    repo = repo,
                    path = change.path,
                    requestedBranch = requestedBranch,
                    token = token,
                    apiBaseUrl = apiBaseUrl
                )
                buildRemoteTaskRepositoryWholeFileOverwriteGuardError(
                    repo = repo,
                    path = change.path,
                    existingFile = existingForGuard?.file ?: continue,
                    candidateBranch = existingForGuard.branch,
                    newContent = afterContent,
                    toolName = name
                )?.let { guardError ->
                    return ToolExecutionResult(guardError)
                }
            }

            val branchCandidates = if (requestedBranch != null) {
                listOf(requestedBranch)
            } else {
                val resolvedDefaultBranch = loadRemoteTaskRepositoryDefaultBranch(
                    repo = repo,
                    token = token,
                    apiBaseUrl = apiBaseUrl
                )
                listOfNotNull(
                    resolvedDefaultBranch,
                    "main",
                    "master"
                ).distinct()
            }

            var lastError: String? = null
            var batchResult: RemoteTaskRepositoryBatchCommitResult? = null
            for (branch in branchCandidates) {
                val result = runRemoteTaskRepositoryBatchCommit(
                    repo = repo,
                    branch = branch,
                    message = message,
                    changes = changes,
                    token = token,
                    apiBaseUrl = apiBaseUrl
                )
                if (result.success) {
                    batchResult = result
                    break
                }
                lastError = result.error
            }

            val success = batchResult ?: return ToolExecutionResult(
                buildString {
                    append("Error: 批量提交远端任务仓库文件失败。")
                    if (!requestedBranch.isNullOrBlank()) {
                        append(" 分支 `")
                        append(requestedBranch)
                        append("` 提交未成功。")
                    } else {
                        append(" 已尝试默认分支和 main/master 回退，但都失败。建议显式传入 branch。")
                    }
                    lastError?.takeIf { it.isNotBlank() }?.let {
                        append("\n底层结果: ")
                        append(it)
                    }
                }
            )

            ToolExecutionResult(
                output = buildString {
                    append("已批量提交当前任务仓库文件：")
                    append(repo.label)
                    append("\n分支: ")
                    append(success.branch)
                    append("\n提交说明: ")
                    append(message)
                    success.commitSha?.takeIf { it.isNotBlank() }?.let {
                        append("\n提交 SHA: ")
                        append(it)
                    }
                    success.commitUrl?.takeIf { it.isNotBlank() }?.let {
                        append("\n提交地址: ")
                        append(it)
                    }
                    append("\n变更数: ")
                    append(success.fileChanges.size)
                },
                fileChanges = success.fileChanges
            )
        } catch (e: Exception) {
            ToolExecutionResult("Error: 批量提交远端任务仓库文件失败: ${e.message}")
        }
    }
}

private data class RemoteTaskRepositoryBatchChange(
    val path: String,
    val operation: String,
    val content: String?
)

private data class RemoteTaskRepositoryApplyPatchFile(
    val path: String,
    val hunks: List<RemoteTaskRepositoryPatchHunk>
)

private data class RemoteTaskRepositoryPatchHunk(
    val search: String,
    val replace: String,
    val expectedMatches: Int,
    val contextBefore: String?,
    val contextAfter: String?,
    val lineHint: Int?
)

private data class RemoteTaskRepositoryBatchCommitResult(
    val success: Boolean,
    val branch: String,
    val commitSha: String? = null,
    val commitUrl: String? = null,
    val fileChanges: List<ToolFileChange> = emptyList(),
    val error: String? = null
)

private data class RemoteTaskRepositoryWriteFileResult(
    val success: Boolean,
    val branch: String? = null,
    val rawResult: String? = null,
    val fileChange: ToolFileChange? = null,
    val error: String? = null
)

private data class RemoteTaskRepositoryExistingFileForWrite(
    val file: RemoteTaskRepositoryFile,
    val branch: String
)

private const val REMOTE_TASK_REPOSITORY_WHOLE_FILE_OVERWRITE_CHAR_LIMIT = 120_000
internal const val REMOTE_TASK_REPOSITORY_AUTO_READ_WINDOW_CHAR_LIMIT = 60_000
internal const val REMOTE_TASK_REPOSITORY_AUTO_READ_WINDOW_LINE_TRIGGER = 800
internal const val REMOTE_TASK_REPOSITORY_DEFAULT_READ_WINDOW_LINES = 220

private fun parseRemoteTaskRepositoryBatchChanges(element: JsonElement?): List<RemoteTaskRepositoryBatchChange> {
    val items = element?.jsonArray ?: return emptyList()
    return items.mapNotNull { item ->
        val obj = item.jsonObjectOrNull() ?: return@mapNotNull null
        val path = obj["path"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val operation = obj["operation"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty().lowercase()
        if (path.isBlank() || operation.isBlank()) return@mapNotNull null
        val normalizedOperation = when (operation) {
            "write", "create", "update" -> "write"
            "delete", "remove" -> "delete"
            else -> operation
        }
        RemoteTaskRepositoryBatchChange(
            path = path,
            operation = normalizedOperation,
            content = obj["content"]?.jsonPrimitive?.contentOrNull
        )
    }
}

private fun parseRemoteTaskRepositoryApplyPatchFiles(element: JsonElement?): List<RemoteTaskRepositoryApplyPatchFile> {
    val items = element?.jsonArray ?: return emptyList()
    return items.mapNotNull { item ->
        val obj = item.jsonObjectOrNull() ?: return@mapNotNull null
        val path = obj["path"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val hunks = parseRemoteTaskRepositoryPatchHunks(obj["hunks"])
        if (path.isBlank() || hunks.isEmpty()) return@mapNotNull null
        RemoteTaskRepositoryApplyPatchFile(
            path = path,
            hunks = hunks
        )
    }
}

private fun resolveRemoteTaskRepositoryApplyPatchFiles(obj: JsonObject): List<RemoteTaskRepositoryApplyPatchFile> {
    val structuredFiles = parseRemoteTaskRepositoryApplyPatchFiles(obj["file_patches"])
    val textPatch = obj["patch_text"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    val textPatchFiles = if (textPatch.isBlank()) {
        emptyList()
    } else {
        parseRemoteTaskRepositoryTextPatch(textPatch)
    }
    return structuredFiles + textPatchFiles
}

private fun parseRemoteTaskRepositoryTextPatch(patchText: String): List<RemoteTaskRepositoryApplyPatchFile> {
    val normalized = patchText.replace("\r\n", "\n").replace('\r', '\n')
    val lines = normalized.split('\n')
    val files = mutableListOf<RemoteTaskRepositoryApplyPatchFile>()
    var index = 0
    var currentPath: String? = null
    var currentHunks = mutableListOf<RemoteTaskRepositoryPatchHunk>()

    fun flushCurrentFile() {
        val path = currentPath ?: return
        if (currentHunks.isEmpty()) {
            throw IllegalArgumentException("文本补丁中的 `$path` 没有任何 hunk。")
        }
        files += RemoteTaskRepositoryApplyPatchFile(
            path = path,
            hunks = currentHunks.toList()
        )
        currentPath = null
        currentHunks = mutableListOf()
    }

    while (index < lines.size) {
        val rawLine = lines[index]
        when {
            rawLine.isBlank() && currentPath == null -> {
                index++
            }

            rawLine == "*** Begin Patch" || rawLine == "*** End Patch" -> {
                if (rawLine == "*** End Patch") {
                    flushCurrentFile()
                }
                index++
            }

            rawLine.startsWith("*** Update File: ") -> {
                flushCurrentFile()
                val path = rawLine.removePrefix("*** Update File: ").trim()
                if (path.isBlank()) {
                    throw IllegalArgumentException("文本补丁里存在空的 Update File 路径。")
                }
                currentPath = path
                index++
            }

            rawLine.startsWith("*** Add File: ") || rawLine.startsWith("*** Delete File: ") -> {
                throw IllegalArgumentException("当前 `task_repo_apply_patch` 的 `patch_text` 仅支持 `*** Update File`。请把新增/删除文件改用其他远端工具。")
            }

            rawLine.startsWith("@@") -> {
                val path = currentPath ?: throw IllegalArgumentException("文本补丁里的 hunk 缺少对应的 `*** Update File`。")
                val hunkLines = mutableListOf<String>()
                index++
                while (index < lines.size) {
                    val bodyLine = lines[index]
                    if (
                        bodyLine.startsWith("@@") ||
                        bodyLine.startsWith("*** Update File: ") ||
                        bodyLine == "*** End Patch"
                    ) {
                        break
                    }
                    if (bodyLine == "*** End of File") {
                        index++
                        break
                    }
                    if (bodyLine.isNotEmpty() && bodyLine[0] !in listOf(' ', '-', '+')) {
                        throw IllegalArgumentException("文本补丁 `$path` 的 hunk 中存在无法识别的行: `$bodyLine`")
                    }
                    hunkLines += bodyLine
                    index++
                }
                currentHunks += buildRemoteTaskRepositoryPatchHunkFromText(path, hunkLines)
            }

            else -> {
                throw IllegalArgumentException("无法解析文本补丁中的行: `$rawLine`")
            }
        }
    }

    flushCurrentFile()
    return files
}

private fun buildRemoteTaskRepositoryPatchHunkFromText(
    path: String,
    hunkLines: List<String>
): RemoteTaskRepositoryPatchHunk {
    if (hunkLines.isEmpty()) {
        throw IllegalArgumentException("文本补丁 `$path` 存在空 hunk。")
    }
    val search = StringBuilder()
    val replace = StringBuilder()
    var changed = false
    hunkLines.forEach { line ->
        if (line.isEmpty()) {
            throw IllegalArgumentException("文本补丁 `$path` 的 hunk 存在缺少前缀的空行，请用空格前缀表示上下文空行。")
        }
        val marker = line[0]
        val payload = line.substring(1)
        when (marker) {
            ' ' -> {
                search.append(payload).append('\n')
                replace.append(payload).append('\n')
            }
            '-' -> {
                search.append(payload).append('\n')
                changed = true
            }
            '+' -> {
                replace.append(payload).append('\n')
                changed = true
            }
            else -> throw IllegalArgumentException("文本补丁 `$path` 的 hunk 存在非法前缀 `$marker`。")
        }
    }
    if (!changed) {
        throw IllegalArgumentException("文本补丁 `$path` 的 hunk 没有任何实际改动。")
    }
    val searchText = search.toString()
    if (searchText.isEmpty()) {
        throw IllegalArgumentException("文本补丁 `$path` 的 hunk 没有可定位的原始内容，当前不支持纯追加型空搜索补丁。")
    }
    return RemoteTaskRepositoryPatchHunk(
        search = searchText,
        replace = replace.toString(),
        expectedMatches = 1,
        contextBefore = null,
        contextAfter = null,
        lineHint = null
    )
}

private fun parseRemoteTaskRepositoryPatchHunks(element: JsonElement?): List<RemoteTaskRepositoryPatchHunk> {
    val items = element?.jsonArray ?: return emptyList()
    return items.mapNotNull { item ->
        val obj = item.jsonObjectOrNull() ?: return@mapNotNull null
        val search = obj["search"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        val replace = obj["replace"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        val contextBefore = obj["context_before"]?.jsonPrimitive?.contentOrNull?.ifEmpty { null }
        val contextAfter = obj["context_after"]?.jsonPrimitive?.contentOrNull?.ifEmpty { null }
        val lineHint = obj["line_hint"]?.jsonPrimitive?.intOrNull?.takeIf { it > 0 }
        val expectedMatches = (obj["expected_matches"]?.jsonPrimitive?.intOrNull ?: 1).coerceAtLeast(1)
        if (search.isEmpty()) return@mapNotNull null
        RemoteTaskRepositoryPatchHunk(
            search = search,
            replace = replace,
            expectedMatches = expectedMatches,
            contextBefore = contextBefore,
            contextAfter = contextAfter,
            lineHint = lineHint
        )
    }
}

private fun applyRemoteTaskRepositoryPatchToContent(
    path: String,
    originalContent: String,
    hunks: List<RemoteTaskRepositoryPatchHunk>
): String? {
    var currentContent = originalContent
    hunks.forEachIndexed { index, hunk ->
        val rawMatchCount = countExactOccurrences(currentContent, hunk.search)
        val matches = findRemoteTaskRepositoryPatchMatches(currentContent, hunk)
        if (matches.size != hunk.expectedMatches) {
            throw IllegalArgumentException(
                buildString {
                    append("补丁第 ")
                    append(index + 1)
                    append(" 段在 `")
                    append(path)
                    append("` 中期望匹配 ")
                    append(hunk.expectedMatches)
                    append(" 次，实际匹配 ")
                    append(matches.size)
                    append(" 次。")
                    if (hunk.contextBefore != null || hunk.contextAfter != null) {
                        append(" 原始 search 命中 ")
                        append(rawMatchCount)
                        append(" 次，带上下文约束后命中 ")
                        append(matches.size)
                        append(" 次。")
                    }
                    hunk.lineHint?.let { lineHint ->
                        append(" 目标行提示为 ")
                        append(lineHint)
                        append("。")
                    }
                    val candidates = buildRemoteTaskRepositoryPatchCandidatePreview(currentContent, hunk)
                    if (candidates.isNotBlank()) {
                        append("\n候选位置: ")
                        append(candidates)
                    }
                }
            )
        }
        currentContent = replaceRemoteTaskRepositoryPatchMatches(
            content = currentContent,
            matches = matches,
            replacement = hunk.replace
        )
    }
    return currentContent.takeIf { it != originalContent }
}

private data class RemoteTaskRepositoryPatchMatch(
    val startIndex: Int,
    val endIndex: Int,
    val lineNumber: Int
)

private fun findRemoteTaskRepositoryPatchMatches(
    content: String,
    hunk: RemoteTaskRepositoryPatchHunk
): List<RemoteTaskRepositoryPatchMatch> {
    val candidateIndexes = findExactOccurrenceIndexes(content, hunk.search)
    val matches = candidateIndexes.mapNotNull { startIndex ->
        val endIndex = startIndex + hunk.search.length
        val lineNumber = computeLineNumberAtIndex(content, startIndex)
        val matchesBefore = hunk.contextBefore?.let { contextBefore ->
            val beforeStart = startIndex - contextBefore.length
            beforeStart >= 0 && content.regionMatches(beforeStart, contextBefore, 0, contextBefore.length)
        } ?: true
        val matchesAfter = hunk.contextAfter?.let { contextAfter ->
            val afterEnd = endIndex + contextAfter.length
            afterEnd <= content.length && content.regionMatches(endIndex, contextAfter, 0, contextAfter.length)
        } ?: true
        if (!matchesBefore || !matchesAfter) {
            null
        } else {
            RemoteTaskRepositoryPatchMatch(
                startIndex = startIndex,
                endIndex = endIndex,
                lineNumber = lineNumber
            )
        }
    }
    val lineHint = hunk.lineHint
    return if (lineHint == null || matches.size <= 1) {
        matches
    } else {
        matches.sortedWith(
            compareBy<RemoteTaskRepositoryPatchMatch> { kotlin.math.abs(it.lineNumber - lineHint) }
                .thenBy { it.lineNumber }
        )
    }
}

private fun replaceRemoteTaskRepositoryPatchMatches(
    content: String,
    matches: List<RemoteTaskRepositoryPatchMatch>,
    replacement: String
): String {
    var updated = content
    matches.asReversed().forEach { match ->
        updated = updated.replaceRange(match.startIndex, match.endIndex, replacement)
    }
    return updated
}

private fun buildRemoteTaskRepositoryPatchCandidatePreview(
    content: String,
    hunk: RemoteTaskRepositoryPatchHunk
): String {
    val rawIndexes = findExactOccurrenceIndexes(content, hunk.search)
    if (rawIndexes.isEmpty()) return "未找到任何 search 命中"
    val constrainedMatches = findRemoteTaskRepositoryPatchMatches(content, hunk)
    val sourceMatches = if (constrainedMatches.isNotEmpty()) constrainedMatches else rawIndexes.map { index ->
        RemoteTaskRepositoryPatchMatch(
            startIndex = index,
            endIndex = index + hunk.search.length,
            lineNumber = computeLineNumberAtIndex(content, index)
        )
    }
    return sourceMatches.take(3).joinToString(" | ") { match ->
        buildString {
            append("L")
            append(match.lineNumber)
            append(": ")
            append(extractRemoteTaskRepositoryPatchSnippet(content, match.startIndex, match.endIndex))
        }
    }
}

private fun extractRemoteTaskRepositoryPatchSnippet(
    content: String,
    startIndex: Int,
    endIndex: Int,
    radius: Int = 36
): String {
    val snippetStart = maxOf(0, startIndex - radius)
    val snippetEnd = minOf(content.length, endIndex + radius)
    val snippet = content.substring(snippetStart, snippetEnd)
        .replace('\n', ' ')
        .replace('\r', ' ')
        .trim()
    return when {
        snippet.isBlank() -> "<empty>"
        snippet.length <= 96 -> snippet
        else -> snippet.take(93) + "..."
    }
}

private fun computeLineNumberAtIndex(content: String, index: Int): Int {
    if (index <= 0) return 1
    var line = 1
    var cursor = 0
    while (cursor < index && cursor < content.length) {
        if (content[cursor] == '\n') line++
        cursor++
    }
    return line
}

private suspend fun loadRemoteTaskRepositoryExistingFileForWrite(
    repo: RemoteTaskRepositoryTarget,
    path: String,
    requestedBranch: String?,
    token: String,
    apiBaseUrl: String
): RemoteTaskRepositoryExistingFileForWrite? {
    val resolvedDefaultBranch = requestedBranch ?: loadRemoteTaskRepositoryDefaultBranch(
        repo = repo,
        token = token,
        apiBaseUrl = apiBaseUrl
    )
    val branchCandidates = listOfNotNull(
        requestedBranch,
        resolvedDefaultBranch,
        "main",
        "master"
    ).distinct()
    branchCandidates.forEach { branch ->
        val file = loadRemoteTaskRepositoryFile(
            repo = repo,
            path = path,
            branch = branch,
            token = token,
            apiBaseUrl = apiBaseUrl
        ).file
        if (file != null) {
            return RemoteTaskRepositoryExistingFileForWrite(
                file = file,
                branch = branch
            )
        }
    }
    return null
}

private fun buildRemoteTaskRepositoryWholeFileOverwriteGuardError(
    repo: RemoteTaskRepositoryTarget,
    path: String,
    existingFile: RemoteTaskRepositoryFile,
    candidateBranch: String,
    newContent: String,
    toolName: String
): String? {
    val largestCharCount = maxOf(existingFile.content.length, newContent.length)
    if (largestCharCount < REMOTE_TASK_REPOSITORY_WHOLE_FILE_OVERWRITE_CHAR_LIMIT) {
        return null
    }
    return buildString {
        append("Error: 已拦截远端大文件整文件改写。")
        append("\n文件: ")
        append(repo.label)
        append("/")
        append(path)
        append("\n检测分支: ")
        append(candidateBranch)
        append("\n当前长度: ")
        append(existingFile.content.length)
        append(" chars")
        append("\n新内容长度: ")
        append(newContent.length)
        append(" chars")
        append("\n触发工具: ")
        append(toolName)
        append("\n原因: 这类超大已有文件如果走整文件 content 提交，容易因为请求体过大、上下文截断或误传半截内容而把远端文件写坏。")
        append("\n建议: 先用 `task_repo_read_file` 读取相关片段，再用 `task_repo_search_replace` 做精确替换；若必须分多文件提交，请先把大文件改动拆成较小的精确修改。")
    }
}

private data class RemoteTaskRepositoryFile(
    val path: String,
    val content: String,
    val sha: String?,
    val branch: String?
)

private data class RemoteTaskRepositoryDirectoryEntry(
    val path: String,
    val name: String,
    val type: String,
    val sizeBytes: Long?,
    val sha: String?,
    val htmlUrl: String?
)

private data class RemoteTaskRepositoryFileLoadResult(
    val file: RemoteTaskRepositoryFile? = null,
    val rawResult: String? = null
)

private data class RemoteTaskRepositoryDirectoryLoadResult(
    val entries: List<RemoteTaskRepositoryDirectoryEntry>? = null,
    val rawResult: String? = null
)

private data class RemoteTaskRepositoryBranchesLoadResult(
    val defaultBranch: String? = null,
    val branches: List<String>? = null,
    val rawResult: String? = null
)

private data class RemoteTaskRepositoryCreatedPr(
    val number: Int,
    val title: String,
    val head: String,
    val base: String,
    val url: String?,
    val state: String?
)

private suspend fun loadRemoteTaskRepositoryFile(
    repo: RemoteTaskRepositoryTarget,
    path: String,
    branch: String?,
    token: String,
    apiBaseUrl: String
): RemoteTaskRepositoryFileLoadResult {
    val pathWithRef = buildString {
        append(buildRemoteTaskRepositoryContentsPath(repo, path))
        branch?.takeIf { it.isNotBlank() }?.let {
            append("?ref=")
            append(Uri.encode(it))
        }
    }
    val result = runRemoteTaskRepositoryApiRequest(
        apiBaseUrl = apiBaseUrl,
        token = token,
        path = pathWithRef
    )
    if (!result.success) {
        return RemoteTaskRepositoryFileLoadResult(rawResult = result.error ?: result.body)
    }
    val element = runCatching { Json.parseToJsonElement(result.body) }.getOrNull()
        ?: return RemoteTaskRepositoryFileLoadResult(rawResult = result.body)
    val file = parseRemoteTaskRepositoryFile(
        element = element,
        requestedPath = path,
        requestedBranch = branch
    )
    return if (file != null) {
        RemoteTaskRepositoryFileLoadResult(file = file)
    } else {
        RemoteTaskRepositoryFileLoadResult(rawResult = result.body)
    }
}

private suspend fun loadRemoteTaskRepositoryDirectory(
    repo: RemoteTaskRepositoryTarget,
    path: String?,
    branch: String?,
    token: String,
    apiBaseUrl: String
): RemoteTaskRepositoryDirectoryLoadResult {
    val pathWithRef = buildString {
        append(buildRemoteTaskRepositoryContentsPath(repo, path.orEmpty()))
        branch?.takeIf { it.isNotBlank() }?.let {
            append("?ref=")
            append(Uri.encode(it))
        }
    }
    val result = runRemoteTaskRepositoryApiRequest(
        apiBaseUrl = apiBaseUrl,
        token = token,
        path = pathWithRef
    )
    if (!result.success) {
        return RemoteTaskRepositoryDirectoryLoadResult(rawResult = result.error ?: result.body)
    }
    val element = runCatching { Json.parseToJsonElement(result.body) }.getOrNull()
        ?: return RemoteTaskRepositoryDirectoryLoadResult(rawResult = result.body)
    val entries = parseRemoteTaskRepositoryDirectoryEntries(element)
    if (entries != null) {
        return RemoteTaskRepositoryDirectoryLoadResult(entries = entries)
    }
    val file = parseRemoteTaskRepositoryFile(
        element = element,
        requestedPath = path.orEmpty().ifBlank { "/" },
        requestedBranch = branch
    )
    return if (file != null) {
        RemoteTaskRepositoryDirectoryLoadResult(
            rawResult = "Error: `${file.path}` 是文件，不是目录。请改用 task_repo_read_file 读取内容。"
        )
    } else {
        RemoteTaskRepositoryDirectoryLoadResult(rawResult = result.body)
    }
}

private suspend fun loadRemoteTaskRepositoryBranches(
    repo: RemoteTaskRepositoryTarget,
    token: String,
    apiBaseUrl: String,
    limit: Int
): RemoteTaskRepositoryBranchesLoadResult {
    val defaultBranch = loadRemoteTaskRepositoryDefaultBranch(
        repo = repo,
        token = token,
        apiBaseUrl = apiBaseUrl
    )
    val result = runRemoteTaskRepositoryApiRequest(
        apiBaseUrl = apiBaseUrl,
        token = token,
        path = buildRemoteTaskRepositoryBranchesPath(repo, limit)
    )
    if (!result.success) {
        return RemoteTaskRepositoryBranchesLoadResult(
            defaultBranch = defaultBranch,
            rawResult = result.error ?: result.body
        )
    }
    val element = runCatching { Json.parseToJsonElement(result.body) }.getOrNull()
        ?: return RemoteTaskRepositoryBranchesLoadResult(
            defaultBranch = defaultBranch,
            rawResult = result.body
        )
    val branches = parseRemoteTaskRepositoryBranches(element)
        ?: return RemoteTaskRepositoryBranchesLoadResult(
            defaultBranch = defaultBranch,
            rawResult = result.body
        )
    return RemoteTaskRepositoryBranchesLoadResult(
        defaultBranch = defaultBranch,
        branches = branches
    )
}

private fun parseRemoteTaskRepositoryFile(
    element: JsonElement,
    requestedPath: String,
    requestedBranch: String?
): RemoteTaskRepositoryFile? {
    val obj = element as? JsonObject ?: return null
    if (obj["type"]?.jsonPrimitive?.contentOrNull == "file" || obj.containsKey("content")) {
        return buildRemoteTaskRepositoryFile(obj, requestedPath, requestedBranch)
    }
    obj["content"]?.jsonObjectOrNull()?.let { nested ->
        buildRemoteTaskRepositoryFile(nested, requestedPath, requestedBranch)?.let { return it }
    }
    obj["data"]?.jsonObjectOrNull()?.let { nested ->
        buildRemoteTaskRepositoryFile(nested, requestedPath, requestedBranch)?.let { return it }
    }
    obj["entries"]?.jsonArray?.firstOrNull()?.jsonObjectOrNull()?.let { nested ->
        buildRemoteTaskRepositoryFile(nested, requestedPath, requestedBranch)?.let { return it }
    }
    return null
}

private fun parseRemoteTaskRepositoryDirectoryEntries(
    element: JsonElement
): List<RemoteTaskRepositoryDirectoryEntry>? {
    (element as? kotlinx.serialization.json.JsonArray)?.let { entries ->
        return entries.mapNotNull { parseRemoteTaskRepositoryDirectoryEntry(it.jsonObjectOrNull()) }
    }
    val obj = element as? JsonObject ?: return null
    obj["entries"]?.jsonArray?.let { entries ->
        return entries.mapNotNull { parseRemoteTaskRepositoryDirectoryEntry(it.jsonObjectOrNull()) }
    }
    obj["items"]?.jsonArray?.let { entries ->
        return entries.mapNotNull { parseRemoteTaskRepositoryDirectoryEntry(it.jsonObjectOrNull()) }
    }
    obj["data"]?.jsonArray?.let { entries ->
        return entries.mapNotNull { parseRemoteTaskRepositoryDirectoryEntry(it.jsonObjectOrNull()) }
    }
    return null
}

private fun parseRemoteTaskRepositoryDirectoryEntry(
    obj: JsonObject?
): RemoteTaskRepositoryDirectoryEntry? {
    if (obj == null) return null
    val type = obj["type"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    val path = obj["path"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    val name = obj["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    if (type.isBlank() || path.isBlank()) return null
    return RemoteTaskRepositoryDirectoryEntry(
        path = path,
        name = name.ifBlank { path.substringAfterLast('/') },
        type = type,
        sizeBytes = obj["size"]?.jsonPrimitive?.contentOrNull?.toLongOrNull(),
        sha = obj["sha"]?.jsonPrimitive?.contentOrNull,
        htmlUrl = obj["html_url"]?.jsonPrimitive?.contentOrNull
    )
}

private fun parseRemoteTaskRepositoryBranches(
    element: JsonElement
): List<String>? {
    val items = element as? kotlinx.serialization.json.JsonArray ?: return null
    return items.mapNotNull { item ->
        item.jsonObjectOrNull()
            ?.get("name")
            ?.jsonPrimitive
            ?.contentOrNull
            ?.trim()
            ?.ifBlank { null }
    }
}

private fun parseRemoteTaskRepositoryCreatedPr(body: String): RemoteTaskRepositoryCreatedPr? {
    val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
    val number = obj["number"]?.jsonPrimitive?.intOrNull ?: return null
    val title = obj["title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    val head = obj["head"]?.jsonObjectOrNull()
        ?.get("ref")
        ?.jsonPrimitive
        ?.contentOrNull
        ?.trim()
        .orEmpty()
    val base = obj["base"]?.jsonObjectOrNull()
        ?.get("ref")
        ?.jsonPrimitive
        ?.contentOrNull
        ?.trim()
        .orEmpty()
    if (title.isBlank() || head.isBlank() || base.isBlank()) return null
    return RemoteTaskRepositoryCreatedPr(
        number = number,
        title = title,
        head = head,
        base = base,
        url = obj["html_url"]?.jsonPrimitive?.contentOrNull?.trim(),
        state = obj["state"]?.jsonPrimitive?.contentOrNull?.trim()
    )
}

private fun buildRemoteTaskRepositoryFile(
    obj: JsonObject,
    requestedPath: String,
    requestedBranch: String?
): RemoteTaskRepositoryFile? {
    val hasContentField = obj.containsKey("content")
    val rawContent = obj["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
    val encoding = obj["encoding"]?.jsonPrimitive?.contentOrNull.orEmpty()
    val decodedContent = when {
        !hasContentField -> null
        rawContent.isEmpty() && encoding.equals("base64", ignoreCase = true) -> ""
        rawContent.isEmpty() -> ""
        encoding.equals("base64", ignoreCase = true) -> {
            runCatching {
                val normalized = rawContent.replace("\n", "").replace("\r", "")
                String(Base64.decode(normalized, Base64.DEFAULT), Charsets.UTF_8)
            }.getOrNull()
        }
        else -> rawContent
    } ?: return null
    return RemoteTaskRepositoryFile(
        path = obj["path"]?.jsonPrimitive?.contentOrNull?.ifBlank { requestedPath } ?: requestedPath,
        content = decodedContent,
        sha = obj["sha"]?.jsonPrimitive?.contentOrNull,
        branch = obj["branch"]?.jsonPrimitive?.contentOrNull ?: requestedBranch
    )
}

private fun formatRemoteTaskRepositoryDirectory(
    repoLabel: String,
    requestedPath: String?,
    requestedBranch: String?,
    entries: List<RemoteTaskRepositoryDirectoryEntry>,
    limit: Int
): String {
    val normalizedPath = requestedPath?.ifBlank { null } ?: "/"
    val sortedEntries = entries.sortedWith(
        compareBy<RemoteTaskRepositoryDirectoryEntry>(
            { it.type != "dir" },
            { it.path.lowercase() }
        )
    )
    val visibleEntries = sortedEntries.take(limit)
    return buildString {
        append("Remote task repository directory: ")
        append(repoLabel)
        append(normalizedPath)
        requestedBranch?.takeIf { it.isNotBlank() }?.let {
            append("\nBranch: ")
            append(it)
        }
        append("\nTotal: ")
        append(entries.size)
        if (entries.size > visibleEntries.size) {
            append(" (showing first ")
            append(visibleEntries.size)
            append(")")
        }
        append("\n\n")
        if (visibleEntries.isEmpty()) {
            append("(empty directory)")
        } else {
            append(
                visibleEntries.joinToString("\n") { entry ->
                    buildString {
                        append(
                            when (entry.type) {
                                "dir" -> "[DIR] "
                                "file" -> "[FILE] "
                                else -> "[${entry.type.uppercase()}] "
                            }
                        )
                        append(entry.path)
                        entry.sizeBytes?.takeIf { entry.type == "file" }?.let {
                            append(" (")
                            append(formatRemoteTaskRepositorySize(it))
                            append(")")
                        }
                        entry.sha?.takeIf { it.isNotBlank() }?.let {
                            append(" [sha=")
                            append(it.take(12))
                            append("]")
                        }
                    }
                }
            )
        }
    }
}

private fun formatRemoteTaskRepositoryBranches(
    repoLabel: String,
    defaultBranch: String?,
    branches: List<String>
): String {
    val sortedBranches = branches.sortedBy { it.lowercase() }
    return buildString {
        append("Remote task repository branches: ")
        append(repoLabel)
        defaultBranch?.takeIf { it.isNotBlank() }?.let {
            append("\nDefault branch: ")
            append(it)
        }
        append("\nTotal: ")
        append(sortedBranches.size)
        append("\n\n")
        if (sortedBranches.isEmpty()) {
            append("(no branches)")
        } else {
            append(
                sortedBranches.mapIndexed { index, branch ->
                    buildString {
                        append(index + 1)
                        append(". ")
                        append(branch)
                        if (!defaultBranch.isNullOrBlank() && branch == defaultBranch) {
                            append(" [default]")
                        }
                    }
                }.joinToString("\n")
            )
        }
    }
}

private fun formatRemoteTaskRepositorySize(sizeBytes: Long): String {
    return when {
        sizeBytes >= 1024L * 1024L -> String.format("%.1f MB", sizeBytes / (1024f * 1024f))
        sizeBytes >= 1024L -> String.format("%.1f KB", sizeBytes / 1024f)
        else -> "$sizeBytes B"
    }
}

private fun formatRemoteTaskRepositoryFile(
    file: RemoteTaskRepositoryFile,
    repoLabel: String,
    startLine: Int? = null,
    endLine: Int? = null
): String {
    val allLines = file.content.lines()
    val hasWindow = startLine != null || endLine != null
    val shouldAutoWindow = !hasWindow && shouldAutoWindowRemoteTaskRepositoryFileRead(
        content = file.content,
        totalLines = allLines.size
    )
    val effectiveStartLine = if (shouldAutoWindow) 1 else startLine
    val effectiveEndLine = if (shouldAutoWindow) {
        minOf(allLines.size, REMOTE_TASK_REPOSITORY_DEFAULT_READ_WINDOW_LINES)
    } else {
        endLine
    }
    val lineWindow = computeRemoteTaskRepositoryLineWindow(
        lines = allLines,
        startLine = effectiveStartLine,
        endLine = effectiveEndLine
    )
    val usesWindow = hasWindow || shouldAutoWindow
    val visibleText = if (usesWindow) {
        lineWindow.renderedText
    } else {
        file.content.ifBlank { "(empty file)" }
    }
    return buildString {
        append("Remote task repository file: ")
        append(repoLabel)
        append("/")
        append(file.path)
        file.branch?.takeIf { it.isNotBlank() }?.let {
            append("\nBranch: ")
            append(it)
        }
        file.sha?.takeIf { it.isNotBlank() }?.let {
            append("\nSHA: ")
            append(it)
        }
        append("\nTotal lines: ")
        append(allLines.size)
        if (usesWindow) {
            append("\nShowing lines: ")
            append(lineWindow.startLine)
            append("-")
            append(lineWindow.endLine)
        }
        if (shouldAutoWindow) {
            append("\nNote: 文件较大，未指定行号时默认只返回首个安全窗口。继续阅读请显式传入 startLine/endLine。")
        }
        append("\n\n")
        append(visibleText)
    }
}

internal fun shouldAutoWindowRemoteTaskRepositoryFileRead(
    content: String,
    totalLines: Int
): Boolean {
    return content.length >= REMOTE_TASK_REPOSITORY_AUTO_READ_WINDOW_CHAR_LIMIT ||
        totalLines >= REMOTE_TASK_REPOSITORY_AUTO_READ_WINDOW_LINE_TRIGGER
}

internal fun renderRemoteTaskRepositoryFileForTest(
    repoLabel: String,
    path: String,
    content: String,
    branch: String? = null,
    sha: String? = null,
    startLine: Int? = null,
    endLine: Int? = null
): String {
    return formatRemoteTaskRepositoryFile(
        file = RemoteTaskRepositoryFile(
            path = path,
            content = content,
            sha = sha,
            branch = branch
        ),
        repoLabel = repoLabel,
        startLine = startLine,
        endLine = endLine
    )
}

private data class RemoteTaskRepositoryLineWindow(
    val startLine: Int,
    val endLine: Int,
    val renderedText: String
)

private fun computeRemoteTaskRepositoryLineWindow(
    lines: List<String>,
    startLine: Int?,
    endLine: Int?
): RemoteTaskRepositoryLineWindow {
    if (lines.isEmpty()) {
        return RemoteTaskRepositoryLineWindow(
            startLine = 1,
            endLine = 1,
            renderedText = "(empty file)"
        )
    }
    val normalizedStart = (startLine ?: 1).coerceAtLeast(1)
    val normalizedEnd = (endLine ?: lines.size).coerceAtMost(lines.size)
    if (normalizedStart > normalizedEnd || normalizedStart > lines.size) {
        return RemoteTaskRepositoryLineWindow(
            startLine = normalizedStart,
            endLine = normalizedEnd.coerceAtLeast(normalizedStart),
            renderedText = "(invalid line range)"
        )
    }
    val rendered = buildString {
        for (lineNumber in normalizedStart..normalizedEnd) {
            append(lineNumber)
            append("→")
            append(lines[lineNumber - 1])
            if (lineNumber != normalizedEnd) append('\n')
        }
    }
    return RemoteTaskRepositoryLineWindow(
        startLine = normalizedStart,
        endLine = normalizedEnd,
        renderedText = rendered.ifBlank { "(empty selection)" }
    )
}

private fun formatGitHubSearchResult(
    rawResult: String,
    repoLabel: String,
    githubQuery: String
): String {
    val element = runCatching { Json.parseToJsonElement(rawResult) }.getOrNull()
        ?: return rawResult
    val obj = element as? JsonObject ?: return rawResult
    val items = obj["items"]?.jsonArray
    if (items.isNullOrEmpty()) {
        return buildString {
            append("Remote task repository search finished with no matches.")
            append("\nRepository: ")
            append(repoLabel)
            append("\nQuery: ")
            append(githubQuery)
        }
    }
    val lines = items.take(20).mapIndexed { index, item ->
        val itemObj = item.jsonObjectOrNull()
        val path = itemObj?.get("path")?.jsonPrimitive?.contentOrNull.orEmpty()
        val name = itemObj?.get("name")?.jsonPrimitive?.contentOrNull.orEmpty()
        val sha = itemObj?.get("sha")?.jsonPrimitive?.contentOrNull.orEmpty()
        val htmlUrl = itemObj?.get("html_url")?.jsonPrimitive?.contentOrNull.orEmpty()
        buildString {
            append(index + 1)
            append(". ")
            append(if (path.isNotBlank()) path else name.ifBlank { "(unknown path)" })
            if (sha.isNotBlank()) {
                append(" [sha=")
                append(sha.take(12))
                append("]")
            }
            if (htmlUrl.isNotBlank()) {
                append(" ")
                append(htmlUrl)
            }
        }
    }
    return buildString {
        append("Remote task repository search results")
        append("\nRepository: ")
        append(repoLabel)
        append("\nQuery: ")
        append(githubQuery)
        append("\nTotal: ")
        append(obj["total_count"]?.jsonPrimitive?.contentOrNull ?: items.size.toString())
        append("\n\n")
        append(lines.joinToString("\n"))
    }
}

private fun normalizeRemoteTaskRepositorySearchQuery(rawQuery: String): String {
    val trimmed = rawQuery.trim()
    if (trimmed.isBlank()) return trimmed
    val alreadyStructured = Regex("""\b(?:repo|org|user|path|language|symbol|content|is):""")
        .containsMatchIn(trimmed)
    if (alreadyStructured) return trimmed
    val needsLiteralQuoting = trimmed.any { ch ->
        ch.isWhitespace() || ch in setOf(
            '"', '\'', '(', ')', '[', ']', '{', '}', ':', ';', ',', '!', '?', '+', '*', '^', '|', '&'
        )
    }
    if (!needsLiteralQuoting) return trimmed
    val escaped = trimmed
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
    return "\"$escaped\""
}

private fun JsonElement.jsonObjectOrNull(): JsonObject? = this as? JsonObject

private data class RemoteTaskRepositoryHttpResult(
    val success: Boolean,
    val code: Int,
    val body: String,
    val error: String?
)

private val REMOTE_TASK_REPOSITORY_JSON = Json { ignoreUnknownKeys = true; isLenient = true }
private val REMOTE_TASK_REPOSITORY_HTTP = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .writeTimeout(30, TimeUnit.SECONDS)
    .build()

private fun runRemoteTaskRepositoryApiRequest(
    apiBaseUrl: String,
    token: String,
    path: String,
    method: String = "GET",
    jsonBody: String? = null,
    allowedCodes: Set<Int> = setOf(200)
): RemoteTaskRepositoryHttpResult {
    return runCatching {
        val requestBuilder = Request.Builder()
            .url(buildRemoteTaskRepositoryApiUrl(apiBaseUrl, path))
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
        REMOTE_TASK_REPOSITORY_HTTP.newCall(requestBuilder.build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (response.code in allowedCodes) {
                RemoteTaskRepositoryHttpResult(
                    success = true,
                    code = response.code,
                    body = body,
                    error = null
                )
            } else {
                RemoteTaskRepositoryHttpResult(
                    success = false,
                    code = response.code,
                    body = body,
                    error = parseRemoteTaskRepositoryApiError(body, response.code)
                )
            }
        }
    }.getOrElse { error ->
        RemoteTaskRepositoryHttpResult(
            success = false,
            code = -1,
            body = "",
            error = error.message ?: "GitHub API 请求失败"
        )
    }
}

private fun loadRemoteTaskRepositoryDefaultBranch(
    repo: RemoteTaskRepositoryTarget,
    token: String,
    apiBaseUrl: String
): String? {
    val result = runRemoteTaskRepositoryApiRequest(
        apiBaseUrl = apiBaseUrl,
        token = token,
        path = "/repos/${Uri.encode(repo.owner)}/${Uri.encode(repo.repo)}"
    )
    if (!result.success) return null
    val obj = runCatching { REMOTE_TASK_REPOSITORY_JSON.parseToJsonElement(result.body).jsonObject }.getOrNull()
        ?: return null
    return obj["default_branch"]?.jsonPrimitive?.contentOrNull?.trim()?.ifBlank { null }
}

private fun buildRemoteTaskRepositoryApiUrl(apiBaseUrl: String, path: String): String {
    val base = apiBaseUrl.trim().ifBlank { "https://api.github.com" }.trimEnd('/')
    return "$base/${path.trimStart('/')}"
}

private fun buildRemoteTaskRepositoryContentsPath(
    repo: RemoteTaskRepositoryTarget,
    path: String
): String {
    val encodedPath = path.trim().removePrefix("/").removeSuffix("/")
        .split('/')
        .filter { it.isNotBlank() }
        .joinToString("/") { Uri.encode(it) }
    val prefix = "/repos/${Uri.encode(repo.owner)}/${Uri.encode(repo.repo)}/contents"
    return if (encodedPath.isBlank()) prefix else "$prefix/$encodedPath"
}

private fun buildRemoteTaskRepositoryBranchesPath(
    repo: RemoteTaskRepositoryTarget,
    limit: Int
): String {
    return "/repos/${Uri.encode(repo.owner)}/${Uri.encode(repo.repo)}/branches?per_page=$limit&page=1"
}

private fun buildRemoteTaskRepositoryPullsPath(repo: RemoteTaskRepositoryTarget): String {
    return "/repos/${Uri.encode(repo.owner)}/${Uri.encode(repo.repo)}/pulls"
}

private fun buildRemoteTaskRepositoryPullPath(
    repo: RemoteTaskRepositoryTarget,
    number: Int
): String {
    return "/repos/${Uri.encode(repo.owner)}/${Uri.encode(repo.repo)}/pulls/$number"
}

private fun buildRemoteTaskRepositoryGitRefsPath(repo: RemoteTaskRepositoryTarget): String {
    return "/repos/${Uri.encode(repo.owner)}/${Uri.encode(repo.repo)}/git/refs"
}

private fun buildRemoteTaskRepositoryGitBranchRefGetPath(
    repo: RemoteTaskRepositoryTarget,
    branch: String
): String {
    return "/repos/${Uri.encode(repo.owner)}/${Uri.encode(repo.repo)}/git/ref/heads/${Uri.encode(branch)}"
}

private fun buildRemoteTaskRepositoryGitBranchRefUpdatePath(
    repo: RemoteTaskRepositoryTarget,
    branch: String
): String {
    return "/repos/${Uri.encode(repo.owner)}/${Uri.encode(repo.repo)}/git/refs/heads/${Uri.encode(branch)}"
}

private fun buildRemoteTaskRepositoryGitBranchRefDeletePath(
    repo: RemoteTaskRepositoryTarget,
    branch: String
): String {
    return "/repos/${Uri.encode(repo.owner)}/${Uri.encode(repo.repo)}/git/refs/heads/${Uri.encode(branch)}"
}

private fun buildRemoteTaskRepositoryGitCommitPath(
    repo: RemoteTaskRepositoryTarget,
    commitSha: String
): String {
    return "/repos/${Uri.encode(repo.owner)}/${Uri.encode(repo.repo)}/git/commits/${Uri.encode(commitSha)}"
}

private fun buildRemoteTaskRepositoryGitTreesPath(repo: RemoteTaskRepositoryTarget): String {
    return "/repos/${Uri.encode(repo.owner)}/${Uri.encode(repo.repo)}/git/trees"
}

private fun buildRemoteTaskRepositoryGitCommitsPath(repo: RemoteTaskRepositoryTarget): String {
    return "/repos/${Uri.encode(repo.owner)}/${Uri.encode(repo.repo)}/git/commits"
}

private suspend fun runRemoteTaskRepositoryBatchCommit(
    repo: RemoteTaskRepositoryTarget,
    branch: String,
    message: String,
    changes: List<RemoteTaskRepositoryBatchChange>,
    token: String,
    apiBaseUrl: String
): RemoteTaskRepositoryBatchCommitResult {
    if (changes.isEmpty()) {
        return RemoteTaskRepositoryBatchCommitResult(
            success = false,
            branch = branch,
            error = "没有可提交的文件变更"
        )
    }

    val headSha = loadRemoteTaskRepositoryBranchHeadSha(
        repo = repo,
        branch = branch,
        token = token,
        apiBaseUrl = apiBaseUrl
    ) ?: return RemoteTaskRepositoryBatchCommitResult(
        success = false,
        branch = branch,
        error = "无法读取分支 `$branch` 的最新提交"
    )

    val baseTreeSha = loadRemoteTaskRepositoryCommitTreeSha(
        repo = repo,
        commitSha = headSha,
        token = token,
        apiBaseUrl = apiBaseUrl
    ) ?: return RemoteTaskRepositoryBatchCommitResult(
        success = false,
        branch = branch,
        error = "无法读取分支 `$branch` 的基础 tree"
    )

    val fileChanges = mutableListOf<ToolFileChange>()
    val treeItems = mutableListOf<JsonObject>()

    for (change in changes) {
        when (change.operation) {
            "write" -> {
                val existing = loadRemoteTaskRepositoryFile(
                    repo = repo,
                    path = change.path,
                    branch = branch,
                    token = token,
                    apiBaseUrl = apiBaseUrl
                ).file
                val afterContent = change.content
                    ?: return RemoteTaskRepositoryBatchCommitResult(
                        success = false,
                        branch = branch,
                        error = "文件 `${change.path}` 缺少 content"
                    )
                treeItems += buildJsonObject {
                    put("path", change.path)
                    put("mode", "100644")
                    put("type", "blob")
                    put("content", afterContent)
                }
                fileChanges += ToolFileChange(
                    path = "github://${repo.label}/${change.path}",
                    operation = if (existing == null) "create" else "write",
                    beforeContent = existing?.content,
                    afterContent = afterContent,
                    diffPreview = buildDiffPreview(existing?.content, afterContent)
                )
            }

            "delete" -> {
                val existing = loadRemoteTaskRepositoryFile(
                    repo = repo,
                    path = change.path,
                    branch = branch,
                    token = token,
                    apiBaseUrl = apiBaseUrl
                ).file ?: return RemoteTaskRepositoryBatchCommitResult(
                    success = false,
                    branch = branch,
                    error = "远端文件不存在，无法删除：${repo.label}/${change.path}"
                )
                treeItems += buildJsonObject {
                    put("path", change.path)
                    put("mode", "100644")
                    put("type", "blob")
                    put("sha", JsonNull)
                }
                fileChanges += ToolFileChange(
                    path = "github://${repo.label}/${change.path}",
                    operation = "delete",
                    beforeContent = existing.content,
                    afterContent = null,
                    diffPreview = buildDiffPreview(existing.content, null)
                )
            }

            else -> {
                return RemoteTaskRepositoryBatchCommitResult(
                    success = false,
                    branch = branch,
                    error = "不支持的批量操作 `${change.operation}`，请使用 write 或 delete"
                )
            }
        }
    }

    val treeResult = runRemoteTaskRepositoryApiRequest(
        apiBaseUrl = apiBaseUrl,
        token = token,
        path = buildRemoteTaskRepositoryGitTreesPath(repo),
        method = "POST",
        jsonBody = buildJsonObject {
            put("base_tree", baseTreeSha)
            put("tree", buildJsonArray {
                treeItems.forEach { add(it) }
            })
        }.toString(),
        allowedCodes = setOf(201)
    )
    if (!treeResult.success) {
        return RemoteTaskRepositoryBatchCommitResult(
            success = false,
            branch = branch,
            error = treeResult.error ?: treeResult.body
        )
    }
    val newTreeSha = parseRemoteTaskRepositorySha(treeResult.body)
        ?: return RemoteTaskRepositoryBatchCommitResult(
            success = false,
            branch = branch,
            error = "创建批量提交 tree 失败"
        )

    val commitResult = runRemoteTaskRepositoryApiRequest(
        apiBaseUrl = apiBaseUrl,
        token = token,
        path = buildRemoteTaskRepositoryGitCommitsPath(repo),
        method = "POST",
        jsonBody = buildJsonObject {
            put("message", message)
            put("tree", newTreeSha)
            put("parents", buildJsonArray { add(JsonPrimitive(headSha)) })
        }.toString(),
        allowedCodes = setOf(201)
    )
    if (!commitResult.success) {
        return RemoteTaskRepositoryBatchCommitResult(
            success = false,
            branch = branch,
            error = commitResult.error ?: commitResult.body
        )
    }
    val newCommitSha = parseRemoteTaskRepositorySha(commitResult.body)
        ?: return RemoteTaskRepositoryBatchCommitResult(
            success = false,
            branch = branch,
            error = "创建批量提交 commit 失败"
        )

    val updateRefResult = runRemoteTaskRepositoryApiRequest(
        apiBaseUrl = apiBaseUrl,
        token = token,
        path = buildRemoteTaskRepositoryGitBranchRefUpdatePath(repo, branch),
        method = "PATCH",
        jsonBody = buildJsonObject {
            put("sha", newCommitSha)
            put("force", false)
        }.toString(),
        allowedCodes = setOf(200)
    )
    if (!updateRefResult.success) {
        return RemoteTaskRepositoryBatchCommitResult(
            success = false,
            branch = branch,
            error = updateRefResult.error ?: updateRefResult.body
        )
    }

    val verificationError = verifyRemoteTaskRepositoryBatchWriteChanges(
        repo = repo,
        branch = branch,
        changes = changes,
        token = token,
        apiBaseUrl = apiBaseUrl
    )
    if (verificationError != null) {
        return RemoteTaskRepositoryBatchCommitResult(
            success = false,
            branch = branch,
            error = verificationError
        )
    }

    return RemoteTaskRepositoryBatchCommitResult(
        success = true,
        branch = branch,
        commitSha = newCommitSha,
        commitUrl = "https://github.com/${repo.owner}/${repo.repo}/commit/$newCommitSha",
        fileChanges = fileChanges
    )
}

private fun loadRemoteTaskRepositoryBranchHeadSha(
    repo: RemoteTaskRepositoryTarget,
    branch: String,
    token: String,
    apiBaseUrl: String
): String? {
    val result = runRemoteTaskRepositoryApiRequest(
        apiBaseUrl = apiBaseUrl,
        token = token,
        path = buildRemoteTaskRepositoryGitBranchRefGetPath(repo, branch)
    )
    if (!result.success) return null
    val obj = runCatching { REMOTE_TASK_REPOSITORY_JSON.parseToJsonElement(result.body).jsonObject }.getOrNull()
        ?: return null
    return obj["object"]?.jsonObjectOrNull()?.get("sha")?.jsonPrimitive?.contentOrNull
}

private fun loadRemoteTaskRepositoryCommitTreeSha(
    repo: RemoteTaskRepositoryTarget,
    commitSha: String,
    token: String,
    apiBaseUrl: String
): String? {
    val result = runRemoteTaskRepositoryApiRequest(
        apiBaseUrl = apiBaseUrl,
        token = token,
        path = buildRemoteTaskRepositoryGitCommitPath(repo, commitSha)
    )
    if (!result.success) return null
    val obj = runCatching { REMOTE_TASK_REPOSITORY_JSON.parseToJsonElement(result.body).jsonObject }.getOrNull()
        ?: return null
    return obj["tree"]?.jsonObjectOrNull()?.get("sha")?.jsonPrimitive?.contentOrNull
}

private fun parseRemoteTaskRepositorySha(body: String): String? {
    val obj = runCatching { REMOTE_TASK_REPOSITORY_JSON.parseToJsonElement(body).jsonObject }.getOrNull()
        ?: return null
    return obj["sha"]?.jsonPrimitive?.contentOrNull
}

private fun parseRemoteTaskRepositoryApiError(body: String, code: Int): String {
    val parsed = runCatching { REMOTE_TASK_REPOSITORY_JSON.parseToJsonElement(body).jsonObject }.getOrNull()
    val message = parsed?.get("message")?.jsonPrimitive?.contentOrNull?.trim()
    return when {
        !message.isNullOrBlank() -> "GitHub API 错误($code): $message"
        body.isNotBlank() -> "GitHub API 错误($code): $body"
        else -> "GitHub API 请求失败($code)"
    }
}

private suspend fun writeRemoteTaskRepositoryFile(
    repo: RemoteTaskRepositoryTarget,
    path: String,
    content: String,
    message: String,
    requestedBranch: String?,
    token: String,
    apiBaseUrl: String
): RemoteTaskRepositoryWriteFileResult {
    val resolvedDefaultBranch = requestedBranch ?: loadRemoteTaskRepositoryDefaultBranch(
        repo = repo,
        token = token,
        apiBaseUrl = apiBaseUrl
    )
    val branchCandidates = listOfNotNull(
        requestedBranch,
        resolvedDefaultBranch,
        "main",
        "master"
    ).distinct()
    var lastResult: String? = null
    for (branch in branchCandidates) {
        val existingFile = loadRemoteTaskRepositoryFile(
            repo = repo,
            path = path,
            branch = branch,
            token = token,
            apiBaseUrl = apiBaseUrl
        ).file
        val result = runRemoteTaskRepositoryApiRequest(
            apiBaseUrl = apiBaseUrl,
            token = token,
            path = buildRemoteTaskRepositoryContentsPath(repo, path),
            method = "PUT",
            jsonBody = buildJsonObject {
                put("message", message)
                put("content", Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
                put("branch", branch)
                existingFile?.sha?.takeIf { it.isNotBlank() }?.let { put("sha", it) }
            }.toString(),
            allowedCodes = setOf(200, 201)
        )
        if (!result.success) {
            lastResult = result.error ?: result.body
            continue
        }
        val verificationError = verifyRemoteTaskRepositoryFileContent(
            repo = repo,
            path = path,
            branch = branch,
            expectedContent = content,
            token = token,
            apiBaseUrl = apiBaseUrl
        )
        if (verificationError != null) {
            return RemoteTaskRepositoryWriteFileResult(
                success = false,
                branch = branch,
                rawResult = result.body.ifBlank { null },
                error = verificationError
            )
        }
        return RemoteTaskRepositoryWriteFileResult(
            success = true,
            branch = branch,
            rawResult = result.body.ifBlank { "OK" },
            fileChange = ToolFileChange(
                path = "github://${repo.label}/$path",
                operation = if (existingFile == null) "create" else "write",
                beforeContent = existingFile?.content,
                afterContent = content,
                diffPreview = buildDiffPreview(existingFile?.content, content)
            )
        )
    }
    return RemoteTaskRepositoryWriteFileResult(
        success = false,
        error = lastResult
    )
}

private suspend fun verifyRemoteTaskRepositoryBranchState(
    repo: RemoteTaskRepositoryTarget,
    branch: String,
    shouldExist: Boolean,
    token: String,
    apiBaseUrl: String
): String? {
    val branchesResult = loadRemoteTaskRepositoryBranches(
        repo = repo,
        token = token,
        apiBaseUrl = apiBaseUrl,
        limit = 200
    )
    val branches = branchesResult.branches ?: return buildString {
        append("无法重新读取远端分支列表。")
        branchesResult.rawResult?.takeIf { it.isNotBlank() }?.let {
            append(" 底层结果: ")
            append(it)
        }
    }
    val exists = branch in branches
    return when {
        shouldExist && !exists -> "远端分支 `$branch` 未出现在最新分支列表中。"
        !shouldExist && exists -> "远端分支 `$branch` 仍然存在于最新分支列表中。"
        else -> null
    }
}

private suspend fun verifyRemoteTaskRepositoryFileDeletion(
    repo: RemoteTaskRepositoryTarget,
    path: String,
    branch: String,
    token: String,
    apiBaseUrl: String
): String? {
    val verificationResult = loadRemoteTaskRepositoryFile(
        repo = repo,
        path = path,
        branch = branch,
        token = token,
        apiBaseUrl = apiBaseUrl
    )
    if (verificationResult.file == null && isRemoteTaskRepositoryNotFoundResult(verificationResult.rawResult)) {
        return null
    }
    return when {
        verificationResult.file != null -> "远端文件 `${repo.label}/$path` 仍然可以被重新读取。"
        verificationResult.rawResult.isNullOrBlank() -> "无法确认远端文件 `${repo.label}/$path` 是否已删除。"
        else -> "无法确认远端文件 `${repo.label}/$path` 是否已删除。底层结果: ${verificationResult.rawResult}"
    }
}

private fun isRemoteTaskRepositoryNotFoundResult(rawResult: String?): Boolean {
    val normalized = rawResult?.lowercase().orEmpty()
    return "github api 错误(404)" in normalized || "not found" in normalized
}

private suspend fun verifyRemoteTaskRepositoryBatchWriteChanges(
    repo: RemoteTaskRepositoryTarget,
    branch: String,
    changes: List<RemoteTaskRepositoryBatchChange>,
    token: String,
    apiBaseUrl: String
): String? {
    changes.forEach { change ->
        when (change.operation) {
            "write" -> {
                val expectedContent = change.content ?: return@forEach
                val error = verifyRemoteTaskRepositoryFileContent(
                    repo = repo,
                    path = change.path,
                    branch = branch,
                    expectedContent = expectedContent,
                    token = token,
                    apiBaseUrl = apiBaseUrl
                )
                if (error != null) return error
            }

            "delete" -> {
                val error = verifyRemoteTaskRepositoryFileDeletion(
                    repo = repo,
                    path = change.path,
                    branch = branch,
                    token = token,
                    apiBaseUrl = apiBaseUrl
                )
                if (error != null) return error
            }
        }
    }
    return null
}

private suspend fun verifyRemoteTaskRepositoryFileContent(
    repo: RemoteTaskRepositoryTarget,
    path: String,
    branch: String,
    expectedContent: String,
    token: String,
    apiBaseUrl: String
): String? {
    val verificationResult = loadRemoteTaskRepositoryFile(
        repo = repo,
        path = path,
        branch = branch,
        token = token,
        apiBaseUrl = apiBaseUrl
    )
    val remoteFile = verificationResult.file ?: return buildString {
        append("写后校验失败：无法重新读取远端文件 `")
        append(repo.label)
        append("/")
        append(path)
        append("`。")
        verificationResult.rawResult?.takeIf { it.isNotBlank() }?.let {
            append(" 底层结果: ")
            append(it)
        }
    }
    if (remoteFile.content == expectedContent) return null
    return buildString {
        append("写后校验失败：远端文件 `")
        append(repo.label)
        append("/")
        append(path)
        append("` 内容与预期不一致。")
        append(" expectedLength=")
        append(expectedContent.length)
        append(", actualLength=")
        append(remoteFile.content.length)
        remoteFile.sha?.takeIf { it.isNotBlank() }?.let {
            append(", sha=")
            append(it)
        }
    }
}

private fun countExactOccurrences(content: String, search: String): Int {
    if (search.isEmpty()) return 0
    var fromIndex = 0
    var count = 0
    while (true) {
        val index = content.indexOf(search, startIndex = fromIndex)
        if (index < 0) return count
        count++
        fromIndex = index + search.length
    }
}

private fun findExactOccurrenceIndexes(content: String, search: String): List<Int> {
    if (search.isEmpty()) return emptyList()
    val indexes = mutableListOf<Int>()
    var fromIndex = 0
    while (true) {
        val index = content.indexOf(search, startIndex = fromIndex)
        if (index < 0) return indexes
        indexes += index
        fromIndex = index + search.length
    }
}

private fun normalizeRemoteTaskRepositoryBranchName(rawBranch: String?): String? {
    return rawBranch
        ?.trim()
        ?.removePrefix("refs/heads/")
        ?.removePrefix("heads/")
        ?.removePrefix("/")
        ?.ifBlank { null }
}
