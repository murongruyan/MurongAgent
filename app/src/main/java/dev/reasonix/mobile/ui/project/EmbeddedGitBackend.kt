package dev.reasonix.mobile.ui.project

import dev.reasonix.mobile.common.utils.RootFile
import org.eclipse.jgit.api.CreateBranchCommand
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ListBranchCommand
import org.eclipse.jgit.api.MergeCommand
import org.eclipse.jgit.api.PullResult
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.dircache.DirCacheEditor
import org.eclipse.jgit.lib.BranchConfig
import org.eclipse.jgit.lib.BranchTrackingStatus
import org.eclipse.jgit.lib.ConfigConstants
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.PushResult
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.eclipse.jgit.treewalk.AbstractTreeIterator
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import org.eclipse.jgit.treewalk.EmptyTreeIterator
import org.eclipse.jgit.treewalk.filter.PathFilter
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

internal data class EmbeddedGitStatus(
    val repoRoot: String,
    val branchSummary: String,
    val currentBranch: String?,
    val remoteUrl: String?,
    val upstreamBranch: String?,
    val aheadCount: Int,
    val behindCount: Int,
    val lastCommitSummary: String?,
    val localBranches: List<EmbeddedGitBranch>,
    val remoteBranches: List<String>,
    val recentCommits: List<EmbeddedGitCommit>,
    val conflictedFiles: List<EmbeddedGitFileChange>,
    val stagedFiles: List<EmbeddedGitFileChange>,
    val modifiedFiles: List<EmbeddedGitFileChange>,
    val untrackedFiles: List<EmbeddedGitFileChange>
)

internal data class EmbeddedGitBranch(
    val name: String,
    val isCurrent: Boolean,
    val trackInfo: String?
)

internal data class EmbeddedGitCommit(
    val commitHash: String,
    val shortHash: String,
    val subject: String,
    val relativeTime: String,
    val author: String
)

internal data class EmbeddedGitFileChange(
    val displayPath: String,
    val actionPath: String,
    val statusLabel: String,
    val diffMode: EmbeddedGitDiffMode
)

internal enum class EmbeddedGitDiffMode {
    STAGED,
    WORKTREE,
    UNTRACKED
}

internal data class EmbeddedGitDiffPreview(
    val title: String,
    val content: String
)

private data class EmbeddedGitRepositoryLocation(
    val repoRoot: String,
    val gitDir: File,
    val workTree: File
)

internal fun findEmbeddedGitRoot(projectPath: String): String? {
    return resolveEmbeddedRepositoryLocation(projectPath)?.repoRoot
}

internal fun loadEmbeddedGitStatus(projectPath: String): EmbeddedGitStatus {
    val repoRoot = findEmbeddedGitRoot(projectPath)
        ?: error("当前目录下没有识别到 `.git` 仓库。")
    return openEmbeddedGit(repoRoot).use { git ->
        val repository = git.repository
        val status = git.status().call()
        val currentBranch = repository.safeCurrentBranch()
        val remoteUrl = repository.config.getString("remote", "origin", "url")?.trim()?.ifBlank { null }
        val upstreamBranch = currentBranch?.let { branchTrackingName(repository, it) }
        val trackingStatus = currentBranch?.let { BranchTrackingStatus.of(repository, it) }
        val recentCommits = loadEmbeddedGitHistory(git)
        EmbeddedGitStatus(
            repoRoot = repoRoot,
            branchSummary = buildEmbeddedBranchSummary(currentBranch, upstreamBranch),
            currentBranch = currentBranch,
            remoteUrl = remoteUrl,
            upstreamBranch = upstreamBranch,
            aheadCount = trackingStatus?.aheadCount ?: 0,
            behindCount = trackingStatus?.behindCount ?: 0,
            lastCommitSummary = recentCommits.firstOrNull()?.let { "${it.shortHash}\t${it.subject}\t${it.relativeTime}" },
            localBranches = loadEmbeddedGitBranches(git),
            remoteBranches = loadEmbeddedGitRemoteBranches(git),
            recentCommits = recentCommits,
            conflictedFiles = buildEmbeddedConflictChanges(status.conflictingStageState),
            stagedFiles = buildEmbeddedStagedChanges(status),
            modifiedFiles = buildEmbeddedModifiedChanges(status),
            untrackedFiles = status.untracked
                .sorted()
                .map { path ->
                    EmbeddedGitFileChange(
                        displayPath = path,
                        actionPath = path,
                        statusLabel = "未跟踪文件",
                        diffMode = EmbeddedGitDiffMode.UNTRACKED
                    )
                }
        )
    }
}

internal fun initializeEmbeddedGitRepository(projectPath: String, preferredBranch: String): String {
    val branchName = preferredBranch.trim().ifBlank { "main" }
    Git.init()
        .setDirectory(File(projectPath))
        .setInitialBranch(branchName)
        .call()
        .use { git ->
            ensureEmbeddedGitIdentity(git.repository)
        }
    return "Git 仓库初始化完成"
}

internal fun bindEmbeddedGitRemote(projectPath: String, remoteUrl: String, preferredBranch: String): String {
    val repoRoot = findEmbeddedGitRoot(projectPath) ?: run {
        initializeEmbeddedGitRepository(projectPath, preferredBranch)
        findEmbeddedGitRoot(projectPath)
    } ?: projectPath
    openEmbeddedGit(repoRoot).use { git ->
        val repository = git.repository
        val config = repository.config
        config.setString("remote", "origin", "url", remoteUrl)
        config.setString("remote", "origin", "fetch", "+refs/heads/*:refs/remotes/origin/*")
        val currentBranch = repository.safeCurrentBranch() ?: preferredBranch.trim().ifBlank { "main" }
        config.setString(ConfigConstants.CONFIG_BRANCH_SECTION, currentBranch, ConfigConstants.CONFIG_KEY_REMOTE, "origin")
        config.setString(ConfigConstants.CONFIG_BRANCH_SECTION, currentBranch, ConfigConstants.CONFIG_KEY_MERGE, "refs/heads/$currentBranch")
        config.save()
    }
    return "已绑定 GitHub origin"
}

internal fun embeddedGitStageAll(repoRoot: String): String {
    openEmbeddedGit(repoRoot).use { git ->
        git.add().addFilepattern(".").call()
        git.add().addFilepattern(".").setUpdate(true).call()
    }
    return "已全部暂存"
}

internal fun embeddedGitStagePath(repoRoot: String, relativePath: String): String {
    openEmbeddedGit(repoRoot).use { git ->
        git.add().addFilepattern(relativePath).call()
        git.add().addFilepattern(relativePath).setUpdate(true).call()
    }
    return "已暂存 $relativePath"
}

internal fun embeddedGitUnstagePath(repoRoot: String, relativePath: String): String {
    openEmbeddedGit(repoRoot).use { git ->
        val repository = git.repository
        val hasHead = repository.resolve(Constants.HEAD) != null
        if (hasHead) {
            git.reset().addPath(relativePath).call()
        } else {
            val dirCache = repository.lockDirCache()
            dirCache.editor().apply {
                add(DirCacheEditor.DeletePath(relativePath))
                commit()
            }
        }
    }
    return "已取消暂存 $relativePath"
}

internal fun embeddedGitCommit(repoRoot: String, message: String): String {
    openEmbeddedGit(repoRoot).use { git ->
        ensureEmbeddedGitIdentity(git.repository)
        val commit = git.commit()
            .setMessage(message.trim())
            .call()
        return "提交完成 ${commit.name.substring(0, 8)} ${commit.shortMessage}"
    }
}

internal fun embeddedGitCreateBranch(repoRoot: String, branchName: String): String {
    openEmbeddedGit(repoRoot).use { git ->
        git.checkout()
            .setCreateBranch(true)
            .setName(branchName.trim())
            .call()
    }
    return "已切换到新分支 ${branchName.trim()}"
}

internal fun embeddedGitCheckoutBranch(repoRoot: String, branchName: String): String {
    openEmbeddedGit(repoRoot).use { git ->
        git.checkout().setName(branchName.trim()).call()
    }
    return "已切换到 ${branchName.trim()}"
}

internal fun embeddedGitCheckoutRemoteBranch(repoRoot: String, remoteBranch: String): String {
    val trimmed = remoteBranch.trim()
    val localName = trimmed.substringAfter('/', trimmed)
    openEmbeddedGit(repoRoot).use { git ->
        val localExists = git.branchList().call().any { Repository.shortenRefName(it.name) == localName }
        if (localExists) {
            git.checkout().setName(localName).call()
        } else {
            git.checkout()
                .setCreateBranch(true)
                .setName(localName)
                .setStartPoint(trimmed)
                .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.TRACK)
                .call()
        }
    }
    return "已跟踪并切换到 $localName"
}

internal fun embeddedGitFetch(repoRoot: String, githubToken: String?): String {
    openEmbeddedGit(repoRoot).use { git ->
        val repository = git.repository
        val remotes = repository.config.getSubsections("remote").ifEmpty { setOf("origin") }
        remotes.forEach { remote ->
            val remoteTarget = resolveEmbeddedTransportRemote(repository, remote, githubToken)
            git.fetch()
                .setRemote(remoteTarget)
                .setRemoveDeletedRefs(true)
                .setCredentialsProvider(embeddedGitCredentialsProvider(remoteTarget, githubToken))
                .call()
        }
        return "已完成抓取"
    }
}

internal fun embeddedGitPull(repoRoot: String, githubToken: String?): String {
    openEmbeddedGit(repoRoot).use { git ->
        val repository = git.repository
        val currentBranch = repository.safeCurrentBranch() ?: error("当前没有可拉取的活动分支")
        val branchConfig = BranchConfig(repository.config, currentBranch)
        val remoteName = branchConfig.remote ?: "origin"
        val remoteBranch = branchConfig.merge?.let(Repository::shortenRefName) ?: currentBranch
        val remoteTarget = resolveEmbeddedTransportRemote(repository, remoteName, githubToken)
        val result = git.pull()
            .setRemote(remoteTarget)
            .setRemoteBranchName(remoteBranch)
            .setFastForward(MergeCommand.FastForwardMode.FF_ONLY)
            .setCredentialsProvider(embeddedGitCredentialsProvider(remoteTarget, githubToken))
            .call()
        ensureEmbeddedPullResult(result)
        return "已完成拉取"
    }
}

internal fun embeddedGitPush(repoRoot: String, githubToken: String?): String {
    openEmbeddedGit(repoRoot).use { git ->
        val repository = git.repository
        val currentBranch = repository.safeCurrentBranch() ?: error("当前没有可推送的活动分支")
        val branchConfig = BranchConfig(repository.config, currentBranch)
        val remoteName = branchConfig.remote ?: "origin"
        val remoteTarget = resolveEmbeddedTransportRemote(repository, remoteName, githubToken)
        val results = git.push()
            .setRemote(remoteTarget)
            .setCredentialsProvider(embeddedGitCredentialsProvider(remoteTarget, githubToken))
            .setRefSpecs(RefSpec("HEAD:refs/heads/$currentBranch"))
            .call()
        ensureEmbeddedPushResults(results)
        return "已完成推送"
    }
}

internal fun loadEmbeddedGitDiffPreview(repoRoot: String, change: EmbeddedGitFileChange): EmbeddedGitDiffPreview? {
    return openEmbeddedGit(repoRoot).use { git ->
        when (change.diffMode) {
            EmbeddedGitDiffMode.UNTRACKED -> buildEmbeddedUntrackedDiff(repoRoot, change.actionPath)
            EmbeddedGitDiffMode.STAGED,
            EmbeddedGitDiffMode.WORKTREE -> {
                val entries = git.diff()
                    .apply {
                        setPathFilter(PathFilter.create(change.actionPath))
                        if (change.diffMode == EmbeddedGitDiffMode.STAGED) {
                            setCached(true)
                        }
                    }
                    .call()
                val content = formatEmbeddedDiffEntries(git.repository, entries)
                    .ifBlank { "该文件当前没有可显示的差异。" }
                EmbeddedGitDiffPreview(
                    title = "${change.displayPath} · 差异",
                    content = content
                )
            }
        }
    }
}

internal fun loadEmbeddedGitCommitPreview(repoRoot: String, commitHash: String): EmbeddedGitDiffPreview? {
    return openEmbeddedGit(repoRoot).use { git ->
        val repository = git.repository
        RevWalk(repository).use { walk ->
            val commit = walk.parseCommit(repository.resolve(commitHash) ?: return null)
            val oldTree: AbstractTreeIterator = if (commit.parentCount > 0) {
                canonicalTreeParser(repository, walk.parseCommit(commit.getParent(0)).tree.id)
            } else {
                EmptyTreeIterator()
            }
            val newTree = canonicalTreeParser(repository, commit.tree.id)
            val diffContent = ByteArrayOutputStream().use { output ->
                DiffFormatter(output).use { formatter ->
                    formatter.setRepository(repository)
                    formatter.setDetectRenames(true)
                    formatter.scan(oldTree, newTree).forEach(formatter::format)
                }
                output.toString(Charsets.UTF_8.name()).trimEnd()
            }
            val metadata = buildString {
                appendLine("commit ${commit.name}")
                appendLine("Author: ${commit.authorIdent.name} <${commit.authorIdent.emailAddress}>")
                appendLine("Date: ${commit.authorIdent.whenAsInstant}")
                appendLine()
                appendLine(commit.fullMessage.trim())
                appendLine()
                if (diffContent.isNotBlank()) {
                    append(diffContent)
                } else {
                    append("该提交暂无可显示详情。")
                }
            }.trimEnd()
            EmbeddedGitDiffPreview(
                title = "提交 ${commitHash.take(8)}",
                content = metadata
            )
        }
    }
}

private fun loadEmbeddedGitBranches(git: Git): List<EmbeddedGitBranch> {
    val repository = git.repository
    val currentBranch = repository.safeCurrentBranch()
    return git.branchList().call()
        .map { ref ->
            val name = Repository.shortenRefName(ref.name)
            val tracking = BranchTrackingStatus.of(repository, name)
            EmbeddedGitBranch(
                name = name,
                isCurrent = name == currentBranch,
                trackInfo = buildEmbeddedTrackInfo(repository, tracking)
            )
        }
        .sortedBy { it.name.lowercase(Locale.getDefault()) }
}

private fun loadEmbeddedGitRemoteBranches(git: Git): List<String> {
    return git.branchList()
        .setListMode(ListBranchCommand.ListMode.REMOTE)
        .call()
        .map { Repository.shortenRefName(it.name) }
        .filterNot { it.endsWith("/HEAD") }
        .sortedBy { it.lowercase(Locale.getDefault()) }
}

private fun loadEmbeddedGitHistory(git: Git): List<EmbeddedGitCommit> {
    return git.log()
        .setMaxCount(12)
        .call()
        .map { commit ->
            EmbeddedGitCommit(
                commitHash = commit.name,
                shortHash = commit.name.substring(0, 8),
                subject = commit.shortMessage.ifBlank { "(空提交说明)" },
                relativeTime = formatEmbeddedRelativeTime(commit.commitTime.toLong() * 1000L),
                author = commit.authorIdent.name.orEmpty().ifBlank { "未知作者" }
            )
        }
        .toList()
}

private fun buildEmbeddedConflictChanges(
    states: Map<String, org.eclipse.jgit.lib.IndexDiff.StageState>
): List<EmbeddedGitFileChange> {
    return states.entries
        .sortedBy { it.key.lowercase(Locale.getDefault()) }
        .map { (path, state) ->
            EmbeddedGitFileChange(
                displayPath = path,
                actionPath = path,
                statusLabel = "冲突: ${embeddedConflictLabel(state)}",
                diffMode = EmbeddedGitDiffMode.WORKTREE
            )
        }
}

private fun buildEmbeddedStagedChanges(status: org.eclipse.jgit.api.Status): List<EmbeddedGitFileChange> {
    val entries = linkedMapOf<String, EmbeddedGitFileChange>()
    status.added.sorted().forEach { path ->
        entries[path] = EmbeddedGitFileChange(path, path, "暂存区: 新增", EmbeddedGitDiffMode.STAGED)
    }
    status.changed.sorted().forEach { path ->
        entries[path] = EmbeddedGitFileChange(path, path, "暂存区: 已修改", EmbeddedGitDiffMode.STAGED)
    }
    status.removed.sorted().forEach { path ->
        entries[path] = EmbeddedGitFileChange(path, path, "暂存区: 删除", EmbeddedGitDiffMode.STAGED)
    }
    return entries.values.toList()
}

private fun buildEmbeddedModifiedChanges(status: org.eclipse.jgit.api.Status): List<EmbeddedGitFileChange> {
    val entries = linkedMapOf<String, EmbeddedGitFileChange>()
    status.modified.sorted().forEach { path ->
        entries[path] = EmbeddedGitFileChange(path, path, "工作区: 已修改", EmbeddedGitDiffMode.WORKTREE)
    }
    status.missing.sorted().forEach { path ->
        entries[path] = EmbeddedGitFileChange(path, path, "工作区: 删除", EmbeddedGitDiffMode.WORKTREE)
    }
    return entries.values.toList()
}

private fun buildEmbeddedBranchSummary(currentBranch: String?, upstreamBranch: String?): String {
    val current = currentBranch.orEmpty()
    if (current.isBlank()) return ""
    return if (upstreamBranch.isNullOrBlank()) current else "$current -> $upstreamBranch"
}

private fun buildEmbeddedTrackInfo(
    repository: Repository,
    tracking: BranchTrackingStatus?
): String? {
    if (tracking == null) return null
    val trackingBranch = tracking.remoteTrackingBranch
        ?.let(Repository::shortenRefName)
        .orEmpty()
    if (trackingBranch.isBlank()) return null
    val parts = mutableListOf(trackingBranch)
    if (tracking.aheadCount > 0) parts += "ahead ${tracking.aheadCount}"
    if (tracking.behindCount > 0) parts += "behind ${tracking.behindCount}"
    return parts.joinToString(" · ")
}

private fun formatEmbeddedDiffEntries(
    repository: Repository,
    entries: List<DiffEntry>
): String {
    if (entries.isEmpty()) return ""
    return ByteArrayOutputStream().use { output ->
        DiffFormatter(output).use { formatter ->
            formatter.setRepository(repository)
            formatter.setDetectRenames(true)
            entries.forEach(formatter::format)
        }
        output.toString(Charsets.UTF_8.name()).trimEnd()
    }
}

private fun buildEmbeddedUntrackedDiff(repoRoot: String, relativePath: String): EmbeddedGitDiffPreview {
    val targetFile = File(repoRoot, relativePath)
    val content = runCatching { targetFile.readText() }.getOrElse { error ->
        "无法读取未跟踪文件内容：${error.message ?: targetFile.absolutePath}"
    }
    val lines = content.lines()
    val diff = buildString {
        appendLine("diff --git a/$relativePath b/$relativePath")
        appendLine("new file mode 100644")
        appendLine("--- /dev/null")
        appendLine("+++ b/$relativePath")
        appendLine("@@ -0,0 +1,${lines.size} @@")
        lines.forEach { line ->
            append('+')
            appendLine(line)
        }
    }.trimEnd()
    return EmbeddedGitDiffPreview(
        title = "$relativePath · 差异",
        content = diff
    )
}

private fun ensureEmbeddedPullResult(result: PullResult) {
    val mergeResult = result.mergeResult
    if (!result.isSuccessful && mergeResult?.mergeStatus != null) {
        error("拉取失败：${mergeResult.mergeStatus}")
    }
}

private fun ensureEmbeddedPushResults(results: Iterable<PushResult>) {
    results.forEach { result ->
        result.remoteUpdates.forEach { update ->
            when (update.status) {
                RemoteRefUpdate.Status.OK,
                RemoteRefUpdate.Status.UP_TO_DATE -> Unit
                else -> error("推送失败：${update.status}${update.message?.let { " - $it" }.orEmpty()}")
            }
        }
    }
}

private fun embeddedGitCredentialsProvider(
    remoteTarget: String,
    githubToken: String?
): CredentialsProvider? {
    val token = githubToken?.trim().orEmpty()
    if (token.isBlank()) return null
    val remoteUrl = remoteTarget.lowercase(Locale.getDefault())
    return if (
        remoteUrl.startsWith("https://") ||
        remoteUrl.startsWith("http://")
    ) {
        UsernamePasswordCredentialsProvider("x-access-token", token)
    } else {
        null
    }
}

private fun resolveEmbeddedTransportRemote(
    repository: Repository,
    remoteName: String,
    githubToken: String?
): String {
    val configuredRemote = repository.config.getString("remote", remoteName, "url").orEmpty().trim()
    if (configuredRemote.isBlank()) return remoteName
    val token = githubToken?.trim().orEmpty()
    if (token.isBlank()) return remoteName
    val githubHttpsRemote = convertGitHubRemoteToHttps(configuredRemote)
    return githubHttpsRemote ?: remoteName
}

private fun convertGitHubRemoteToHttps(remoteUrl: String): String? {
    val trimmed = remoteUrl.trim()
    if (trimmed.isBlank()) return null
    if (trimmed.startsWith("git@github.com:", ignoreCase = true)) {
        val path = trimmed.substringAfter(':').removePrefix("/").removeSuffix(".git")
        return path.takeIf { it.contains("/") }?.let { "https://github.com/$it.git" }
    }
    if (trimmed.startsWith("ssh://", ignoreCase = true)) {
        val uri = runCatching { java.net.URI(trimmed) }.getOrNull() ?: return null
        if (!uri.host.equals("github.com", ignoreCase = true)) return null
        val path = uri.path.orEmpty().trim('/').removeSuffix(".git")
        return path.takeIf { it.contains("/") }?.let { "https://github.com/$it.git" }
    }
    if (trimmed.startsWith("https://github.com/", ignoreCase = true) ||
        trimmed.startsWith("http://github.com/", ignoreCase = true)
    ) {
        val normalized = trimmed.removePrefix("http://").removePrefix("https://")
        return "https://$normalized"
    }
    return null
}

private fun branchTrackingName(repository: Repository, branchName: String): String? {
    return BranchConfig(repository.config, branchName)
        .trackingBranch
        ?.let(Repository::shortenRefName)
        ?.takeIf { it.isNotBlank() }
}

private fun canonicalTreeParser(repository: Repository, treeId: ObjectId): CanonicalTreeParser {
    return repository.newObjectReader().use { reader ->
        CanonicalTreeParser().apply {
            reset(reader, treeId)
        }
    }
}

private fun formatEmbeddedRelativeTime(timeMillis: Long): String {
    val diff = (System.currentTimeMillis() - timeMillis).coerceAtLeast(0L)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(diff)
    val hours = TimeUnit.MILLISECONDS.toHours(diff)
    val days = TimeUnit.MILLISECONDS.toDays(diff)
    return when {
        diff < TimeUnit.MINUTES.toMillis(1) -> "刚刚"
        minutes < 60 -> "${minutes} 分钟前"
        hours < 24 -> "${hours} 小时前"
        days < 30 -> "${days} 天前"
        days < 365 -> "${days / 30} 个月前"
        else -> "${days / 365} 年前"
    }
}

private fun embeddedConflictLabel(state: org.eclipse.jgit.lib.IndexDiff.StageState): String {
    return when (state) {
        org.eclipse.jgit.lib.IndexDiff.StageState.BOTH_MODIFIED -> "双方都修改"
        org.eclipse.jgit.lib.IndexDiff.StageState.BOTH_ADDED -> "双方都新增"
        org.eclipse.jgit.lib.IndexDiff.StageState.BOTH_DELETED -> "双方都删除"
        org.eclipse.jgit.lib.IndexDiff.StageState.DELETED_BY_THEM -> "对方删除"
        org.eclipse.jgit.lib.IndexDiff.StageState.DELETED_BY_US -> "当前分支删除"
        org.eclipse.jgit.lib.IndexDiff.StageState.ADDED_BY_THEM -> "对方新增"
        org.eclipse.jgit.lib.IndexDiff.StageState.ADDED_BY_US -> "当前分支新增"
    }
}

private fun ensureEmbeddedGitIdentity(repository: Repository) {
    val config = repository.config
    val hasName = !config.getString(ConfigConstants.CONFIG_USER_SECTION, null, ConfigConstants.CONFIG_KEY_NAME).isNullOrBlank()
    val hasEmail = !config.getString(ConfigConstants.CONFIG_USER_SECTION, null, ConfigConstants.CONFIG_KEY_EMAIL).isNullOrBlank()
    if (hasName && hasEmail) return
    if (!hasName) {
        config.setString(ConfigConstants.CONFIG_USER_SECTION, null, ConfigConstants.CONFIG_KEY_NAME, "Reasonix Mobile")
    }
    if (!hasEmail) {
        config.setString(ConfigConstants.CONFIG_USER_SECTION, null, ConfigConstants.CONFIG_KEY_EMAIL, "reasonix-mobile@local")
    }
    config.save()
}

private fun Repository.safeCurrentBranch(): String? {
    return runCatching { branch }
        .getOrNull()
        ?.takeIf { it.isNotBlank() && it != Constants.HEAD }
}

private fun openEmbeddedGit(repoRoot: String): Git {
    val location = resolveEmbeddedRepositoryLocation(repoRoot)
        ?: error("当前目录下没有识别到可用的 `.git` 仓库。")
    val repository = FileRepositoryBuilder()
        .setGitDir(location.gitDir)
        .setWorkTree(location.workTree)
        .setMustExist(true)
        .build()
    return Git(repository)
}

private fun resolveEmbeddedRepositoryLocation(projectPath: String): EmbeddedGitRepositoryLocation? {
    val normalizedPath = projectPath.trim().removeSuffix("/").removeSuffix("\\")
    if (normalizedPath.isBlank()) return null
    val direct = resolveEmbeddedRepositoryLocationFromMetadata(normalizedPath)
    if (direct != null) return direct
    return runCatching {
        FileRepositoryBuilder()
            .findGitDir(File(normalizedPath))
            .build()
            .use { repository ->
                val workTree = repository.workTree ?: File(normalizedPath)
                val gitDir = repository.directory ?: return@use null
                EmbeddedGitRepositoryLocation(
                    repoRoot = workTree.absolutePath,
                    gitDir = gitDir,
                    workTree = workTree
                )
            }
    }.getOrNull()
}

private fun resolveEmbeddedRepositoryLocationFromMetadata(projectPath: String): EmbeddedGitRepositoryLocation? {
    val gitPath = "$projectPath/.git"
    if (RootFile.dirExists(gitPath) || File(gitPath).isDirectory) {
        return EmbeddedGitRepositoryLocation(
            repoRoot = projectPath,
            gitDir = File(gitPath),
            workTree = File(projectPath)
        )
    }
    if (!(RootFile.fileExists(gitPath) || File(gitPath).isFile)) return null
    val gitPointer = readEmbeddedGitMetadata(gitPath) ?: return null
    val gitDirPath = parseEmbeddedGitDirPointer(gitPointer, projectPath) ?: return null
    return EmbeddedGitRepositoryLocation(
        repoRoot = projectPath,
        gitDir = File(gitDirPath),
        workTree = File(projectPath)
    )
}

private fun readEmbeddedGitMetadata(path: String): String? {
    val direct = runCatching {
        val file = File(path)
        if (file.exists() && file.isFile) file.readText() else null
    }.getOrNull()
    if (!direct.isNullOrBlank()) return direct
    val fallback = RootFile.readFile(path)
    return fallback.takeUnless { it.startsWith("error:") || it.isBlank() }
}

private fun parseEmbeddedGitDirPointer(content: String, repoRoot: String): String? {
    val trimmed = content.trim()
    if (!trimmed.startsWith("gitdir:", ignoreCase = true)) return null
    val rawPath = trimmed.substringAfter("gitdir:", "").trim()
    if (rawPath.isBlank()) return null
    return File(rawPath).takeIf(File::isAbsolute)?.absolutePath
        ?: File(repoRoot, rawPath).absolutePath
}
