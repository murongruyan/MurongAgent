package com.murong.agent.ui.project

import com.murong.agent.common.shell.KeepShellPublic
import com.murong.agent.common.utils.RootFile
import org.eclipse.jgit.api.CreateBranchCommand
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ListBranchCommand
import org.eclipse.jgit.api.MergeCommand
import org.eclipse.jgit.api.PullResult
import org.eclipse.jgit.api.ResetCommand
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
import org.eclipse.jgit.treewalk.TreeWalk
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

internal data class EmbeddedGitWorkingTreeSnapshot(
    val conflictedFiles: List<EmbeddedGitFileChange>,
    val stagedFiles: List<EmbeddedGitFileChange>,
    val modifiedFiles: List<EmbeddedGitFileChange>,
    val untrackedFiles: List<EmbeddedGitFileChange>
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
        val workingTreeSnapshot = loadEmbeddedWorkingTreeSnapshot(repoRoot, status)
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
            conflictedFiles = workingTreeSnapshot.conflictedFiles,
            stagedFiles = workingTreeSnapshot.stagedFiles,
            modifiedFiles = workingTreeSnapshot.modifiedFiles,
            untrackedFiles = workingTreeSnapshot.untrackedFiles
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
    if (canUseRootEmbeddedGitCommands(repoRoot)) {
        return rootEmbeddedGitStageAll(repoRoot)
    }
    openEmbeddedGit(repoRoot).use { git ->
        git.add().addFilepattern(".").call()
        git.add().addFilepattern(".").setUpdate(true).call()
    }
    return "已全部暂存"
}

internal fun embeddedGitStagePath(repoRoot: String, relativePath: String): String {
    if (canUseRootEmbeddedGitCommands(repoRoot)) {
        return rootEmbeddedGitStagePath(repoRoot, relativePath)
    }
    openEmbeddedGit(repoRoot).use { git ->
        git.add().addFilepattern(relativePath).call()
        git.add().addFilepattern(relativePath).setUpdate(true).call()
    }
    return "已暂存 $relativePath"
}

internal fun embeddedGitUnstagePath(repoRoot: String, relativePath: String): String {
    if (canUseRootEmbeddedGitCommands(repoRoot)) {
        return rootEmbeddedGitUnstagePath(repoRoot, relativePath)
    }
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
    if (canUseRootEmbeddedGitCommands(repoRoot)) {
        return rootEmbeddedGitCommit(repoRoot, message)
    }
    openEmbeddedGit(repoRoot).use { git ->
        ensureEmbeddedGitIdentity(git.repository)
        val commit = git.commit()
            .setMessage(message.trim())
            .call()
        return "提交完成 ${commit.name.substring(0, 8)} ${commit.shortMessage}"
    }
}

internal fun embeddedGitCreateBranch(repoRoot: String, branchName: String): String {
    if (canUseRootEmbeddedGitCommands(repoRoot)) {
        return rootEmbeddedGitCreateBranch(repoRoot, branchName)
    }
    openEmbeddedGit(repoRoot).use { git ->
        git.checkout()
            .setCreateBranch(true)
            .setName(branchName.trim())
            .call()
    }
    return "已切换到新分支 ${branchName.trim()}"
}

internal fun embeddedGitCheckoutBranch(repoRoot: String, branchName: String): String {
    if (canUseRootEmbeddedGitCommands(repoRoot)) {
        return rootEmbeddedGitCheckoutBranch(repoRoot, branchName)
    }
    openEmbeddedGit(repoRoot).use { git ->
        git.checkout().setName(branchName.trim()).call()
    }
    return "已切换到 ${branchName.trim()}"
}

internal fun embeddedGitCheckoutRemoteBranch(repoRoot: String, remoteBranch: String): String {
    val trimmed = remoteBranch.trim()
    val localName = trimmed.substringAfter('/', trimmed)
    if (canUseRootEmbeddedGitCommands(repoRoot)) {
        return rootEmbeddedGitCheckoutRemoteBranch(repoRoot, trimmed, localName)
    }
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
    if (canUseRootEmbeddedGitCommands(repoRoot)) {
        return rootEmbeddedGitPush(repoRoot, githubToken)
    }
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

internal fun embeddedGitSyncDiscardAll(repoRoot: String, githubToken: String?): String {
    if (canUseRootEmbeddedGitCommands(repoRoot)) {
        return rootEmbeddedGitSyncDiscardAll(repoRoot)
    }
    openEmbeddedGit(repoRoot).use { git ->
        val repository = git.repository
        val currentBranch = repository.safeCurrentBranch()
        val branchConfig = currentBranch?.let { BranchConfig(repository.config, it) }
        val remoteName = branchConfig?.remote ?: "origin"
        val remoteBranch = branchConfig?.merge?.let(Repository::shortenRefName) ?: currentBranch
        val remoteUrl = repository.config.getString("remote", remoteName, "url")?.trim()
        val resetTarget = if (
            !remoteUrl.isNullOrBlank() &&
                !currentBranch.isNullOrBlank() &&
                !remoteBranch.isNullOrBlank()
        ) {
            val remoteTarget = resolveEmbeddedTransportRemote(repository, remoteName, githubToken)
            git.fetch()
                .setRemote(remoteTarget)
                .setRemoveDeletedRefs(true)
                .setCredentialsProvider(embeddedGitCredentialsProvider(remoteTarget, githubToken))
                .call()
            "refs/remotes/$remoteName/$remoteBranch"
        } else {
            Constants.HEAD
        }
        val resolvedResetTarget = repository.resolve(resetTarget) ?: repository.resolve(Constants.HEAD)
            ?: error("当前仓库没有可恢复的提交记录")
        git.reset()
            .setMode(ResetCommand.ResetType.HARD)
            .setRef(resolvedResetTarget.name)
            .call()
        git.clean()
            .setCleanDirectories(true)
            .setIgnore(false)
            .call()
        return if (resetTarget == Constants.HEAD) {
            "已撤回本地改动，恢复到最近提交状态"
        } else {
            "已撤回本地改动，并同步到 $remoteName/$remoteBranch"
        }
    }
}

internal fun embeddedGitDiscardPath(repoRoot: String, relativePath: String, githubToken: String?): String {
    if (canUseRootEmbeddedGitCommands(repoRoot)) {
        return rootEmbeddedGitDiscardPath(repoRoot, relativePath)
    }
    openEmbeddedGit(repoRoot).use { git ->
        val repository = git.repository
        val currentBranch = repository.safeCurrentBranch()
        val branchConfig = currentBranch?.let { BranchConfig(repository.config, it) }
        val remoteName = branchConfig?.remote ?: "origin"
        val remoteBranch = branchConfig?.merge?.let(Repository::shortenRefName) ?: currentBranch
        val remoteUrl = repository.config.getString("remote", remoteName, "url")?.trim()
        val resetTarget = if (
            !remoteUrl.isNullOrBlank() &&
                !currentBranch.isNullOrBlank() &&
                !remoteBranch.isNullOrBlank()
        ) {
            val remoteTarget = resolveEmbeddedTransportRemote(repository, remoteName, githubToken)
            git.fetch()
                .setRemote(remoteTarget)
                .setRemoveDeletedRefs(true)
                .setCredentialsProvider(embeddedGitCredentialsProvider(remoteTarget, githubToken))
                .call()
            "refs/remotes/$remoteName/$remoteBranch"
        } else {
            Constants.HEAD
        }
        val resolvedResetTarget = repository.resolve(resetTarget) ?: repository.resolve(Constants.HEAD)
        if (resolvedResetTarget != null) {
            git.reset().addPath(relativePath).call()
            val existsInTarget = RevWalk(repository).use { walk ->
                val commit = walk.parseCommit(resolvedResetTarget)
                TreeWalk.forPath(repository, relativePath, commit.tree) != null
            }
            if (existsInTarget) {
                git.checkout()
                    .setStartPoint(resolvedResetTarget.name)
                    .addPath(relativePath)
                    .call()
            } else {
                deleteEmbeddedWorkingTreePath(repoRoot, relativePath)
                removeEmbeddedIndexPath(repository, relativePath)
            }
        } else {
            deleteEmbeddedWorkingTreePath(repoRoot, relativePath)
            removeEmbeddedIndexPath(repository, relativePath)
        }
        return "已撤回 $relativePath 的本地改动"
    }
}

internal fun loadEmbeddedGitDiffPreview(repoRoot: String, change: EmbeddedGitFileChange): EmbeddedGitDiffPreview? {
    if (change.diffMode != EmbeddedGitDiffMode.UNTRACKED && canUseRootEmbeddedGitCommands(repoRoot)) {
        return rootEmbeddedGitDiffPreview(repoRoot, change)
    }
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
    if (canUseRootEmbeddedGitCommands(repoRoot)) {
        return rootEmbeddedGitCommitPreview(repoRoot, commitHash)
    }
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

private fun buildEmbeddedUntrackedChanges(paths: Collection<String>): List<EmbeddedGitFileChange> {
    return paths
        .asSequence()
        .filter { it.isNotBlank() }
        .distinct()
        .sorted()
        .map { path ->
            EmbeddedGitFileChange(
                displayPath = path,
                actionPath = path.substringAfter(" -> ", path),
                statusLabel = "未跟踪文件",
                diffMode = EmbeddedGitDiffMode.UNTRACKED
            )
        }
        .toList()
}

private fun loadEmbeddedWorkingTreeSnapshot(
    repoRoot: String,
    status: org.eclipse.jgit.api.Status
): EmbeddedGitWorkingTreeSnapshot {
    val jgitSnapshot = EmbeddedGitWorkingTreeSnapshot(
        conflictedFiles = buildEmbeddedConflictChanges(status.conflictingStageState),
        stagedFiles = buildEmbeddedStagedChanges(status),
        modifiedFiles = buildEmbeddedModifiedChanges(status),
        untrackedFiles = buildEmbeddedUntrackedChanges(status.untracked)
    )
    val shouldUseRoot = shouldUseRootEmbeddedGitStatus(repoRoot)
    val rootAvailable = shouldUseRoot && KeepShellPublic.checkRoot()
    if (!shouldUseRoot || !rootAvailable) {
        return jgitSnapshot
    }
    return loadRootEmbeddedWorkingTreeSnapshot(repoRoot) ?: jgitSnapshot
}

private fun shouldUseRootEmbeddedGitStatus(path: String): Boolean {
    val normalized = path.replace('\\', '/')
    return normalized.startsWith("/storage/") ||
        normalized.startsWith("/sdcard/") ||
        normalized.startsWith("/mnt/")
}

private fun loadRootEmbeddedWorkingTreeSnapshot(repoRoot: String): EmbeddedGitWorkingTreeSnapshot? {
    val marker = "__MURONG_GIT_STATUS_EXIT__"
    val command = buildString {
        append("(")
        append("git -c safe.directory=")
        append(shellQuoteEmbedded(repoRoot))
        append(" -c core.quotepath=false -C ")
        append(shellQuoteEmbedded(repoRoot))
        append(" status --porcelain=1 --untracked-files=all")
        append(") 2>&1; printf '\\n")
        append(marker)
        append("%s' $?")
    }
    val raw = KeepShellPublic.doCmdSync(command)
    val markerIndex = raw.lastIndexOf(marker)
    if (markerIndex < 0) {
        return null
    }
    val output = raw.substring(0, markerIndex).trimEnd()
    val exitCode = raw.substring(markerIndex + marker.length).trim().toIntOrNull()
    if (exitCode != 0) return null
    val parsed = parseRootEmbeddedGitStatus(output)
    val filtered = filterRootEmbeddedLineEndingOnlyChanges(repoRoot, parsed)
    return filtered
}

internal fun loadRootEmbeddedWorkingTreeSnapshotFallback(repoRoot: String): EmbeddedGitWorkingTreeSnapshot? {
    if (!shouldUseRootEmbeddedGitStatus(repoRoot) || !KeepShellPublic.checkRoot()) {
        return null
    }
    return loadRootEmbeddedWorkingTreeSnapshot(repoRoot)
}

private data class RootEmbeddedGitCommandResult(
    val output: String,
    val exitCode: Int?
)

private fun canUseRootEmbeddedGitCommands(repoRoot: String): Boolean {
    return shouldUseRootEmbeddedGitStatus(repoRoot) && KeepShellPublic.checkRoot()
}

private fun runRootEmbeddedGitScript(script: String): RootEmbeddedGitCommandResult {
    val marker = "__MURONG_ROOT_GIT_EXIT__"
    val raw = KeepShellPublic.doCmdSync(
        buildString {
            append("(")
            append(script)
            append(") 2>&1; printf '\\n")
            append(marker)
            append("%s' $?")
        }
    )
    val markerIndex = raw.lastIndexOf(marker)
    if (markerIndex < 0) {
        return RootEmbeddedGitCommandResult(output = raw.trimEnd(), exitCode = null)
    }
    return RootEmbeddedGitCommandResult(
        output = raw.substring(0, markerIndex).trimEnd(),
        exitCode = raw.substring(markerIndex + marker.length).trim().toIntOrNull()
    )
}

private fun rootEmbeddedGitBaseCommand(repoRoot: String): String {
    return buildString {
        append("git -c safe.directory=")
        append(shellQuoteEmbedded(repoRoot))
        append(" -c core.quotepath=false -C ")
        append(shellQuoteEmbedded(repoRoot))
    }
}

private fun rootEmbeddedGitRequireSuccess(
    result: RootEmbeddedGitCommandResult,
    fallbackMessage: String
): String {
    if (result.exitCode == 0) {
        return result.output
    }
    error(result.output.ifBlank { fallbackMessage })
}

private fun rootEmbeddedGitConfigValue(
    repoRoot: String,
    key: String
): String? {
    val git = rootEmbeddedGitBaseCommand(repoRoot)
    val result = runRootEmbeddedGitScript("$git config --get ${shellQuoteEmbedded(key)}")
    if (result.exitCode != 0) {
        return null
    }
    return result.output.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.isNotBlank() }
}

private fun rootEmbeddedGitCurrentBranch(repoRoot: String): String? {
    val git = rootEmbeddedGitBaseCommand(repoRoot)
    val result = runRootEmbeddedGitScript("$git symbolic-ref --quiet --short HEAD")
    if (result.exitCode != 0) {
        return null
    }
    return result.output.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.isNotBlank() }
}

private fun resolveRootEmbeddedTransportRemote(
    repoRoot: String,
    remoteName: String,
    githubToken: String?
): String {
    val configuredRemote = rootEmbeddedGitConfigValue(repoRoot, "remote.$remoteName.url").orEmpty()
    if (configuredRemote.isBlank()) return remoteName
    val token = githubToken?.trim().orEmpty()
    if (token.isBlank()) return remoteName
    val githubHttpsRemote = convertGitHubRemoteToHttps(configuredRemote) ?: return remoteName
    val withoutScheme = githubHttpsRemote.removePrefix("https://")
    return "https://x-access-token:$token@$withoutScheme"
}

private fun rootEmbeddedGitStageAll(repoRoot: String): String {
    val git = rootEmbeddedGitBaseCommand(repoRoot)
    rootEmbeddedGitRequireSuccess(
        runRootEmbeddedGitScript("$git add -A -- ."),
        "root stage all failed"
    )
    return "已全部暂存"
}

private fun rootEmbeddedGitStagePath(repoRoot: String, relativePath: String): String {
    val git = rootEmbeddedGitBaseCommand(repoRoot)
    rootEmbeddedGitRequireSuccess(
        runRootEmbeddedGitScript("$git add -A -- ${shellQuoteEmbedded(relativePath)}"),
        "root stage path failed"
    )
    return "已暂存 $relativePath"
}

private fun rootEmbeddedGitUnstagePath(repoRoot: String, relativePath: String): String {
    val git = rootEmbeddedGitBaseCommand(repoRoot)
    val hasHead = runRootEmbeddedGitScript("$git rev-parse --verify --quiet HEAD").exitCode == 0
    val command = if (hasHead) {
        "$git reset HEAD -- ${shellQuoteEmbedded(relativePath)}"
    } else {
        "$git rm --cached -r --ignore-unmatch -- ${shellQuoteEmbedded(relativePath)}"
    }
    rootEmbeddedGitRequireSuccess(
        runRootEmbeddedGitScript(command),
        "root unstage path failed"
    )
    return "已取消暂存 $relativePath"
}

private fun rootEmbeddedGitCommit(repoRoot: String, message: String): String {
    val git = rootEmbeddedGitBaseCommand(repoRoot)
    rootEmbeddedGitRequireSuccess(
        runRootEmbeddedGitScript(
            buildString {
                appendLine("if [ -z \"\$($git config --get user.name 2>/dev/null)\" ]; then")
                appendLine("  $git config user.name 'Murong Agent'")
                appendLine("fi")
                appendLine("if [ -z \"\$($git config --get user.email 2>/dev/null)\" ]; then")
                appendLine("  $git config user.email 'agent@murong.local'")
                appendLine("fi")
                append("$git commit -m ${shellQuoteEmbedded(message.trim())}")
            }
        ),
        "root commit failed"
    )
    val commitHash = rootEmbeddedGitRequireSuccess(
        runRootEmbeddedGitScript("$git rev-parse --short HEAD"),
        "root commit hash lookup failed"
    ).lineSequence().firstOrNull()?.trim().orEmpty()
    val subject = rootEmbeddedGitRequireSuccess(
        runRootEmbeddedGitScript("$git log -1 --pretty=%s"),
        "root commit subject lookup failed"
    ).lineSequence().firstOrNull()?.trim().orEmpty()
    return "提交完成 ${commitHash.ifBlank { "HEAD" }} ${subject.ifBlank { message.trim() }}"
}

private fun rootEmbeddedGitCreateBranch(repoRoot: String, branchName: String): String {
    val git = rootEmbeddedGitBaseCommand(repoRoot)
    rootEmbeddedGitRequireSuccess(
        runRootEmbeddedGitScript("$git checkout -b ${shellQuoteEmbedded(branchName.trim())}"),
        "root create branch failed"
    )
    return "已切换到新分支 ${branchName.trim()}"
}

private fun rootEmbeddedGitCheckoutBranch(repoRoot: String, branchName: String): String {
    val git = rootEmbeddedGitBaseCommand(repoRoot)
    rootEmbeddedGitRequireSuccess(
        runRootEmbeddedGitScript("$git checkout ${shellQuoteEmbedded(branchName.trim())}"),
        "root checkout branch failed"
    )
    return "已切换到 ${branchName.trim()}"
}

private fun rootEmbeddedGitCheckoutRemoteBranch(
    repoRoot: String,
    remoteBranch: String,
    localName: String
): String {
    val git = rootEmbeddedGitBaseCommand(repoRoot)
    rootEmbeddedGitRequireSuccess(
        runRootEmbeddedGitScript(
            buildString {
                appendLine(
                    "if $git rev-parse --verify --quiet refs/heads/${shellQuoteEmbedded(localName)} >/dev/null; then"
                )
                appendLine("  $git checkout ${shellQuoteEmbedded(localName)}")
                appendLine("else")
                appendLine(
                    "  $git checkout -b ${shellQuoteEmbedded(localName)} --track ${shellQuoteEmbedded(remoteBranch)}"
                )
                appendLine("fi")
            }
        ),
        "root checkout remote branch failed"
    )
    return "已跟踪并切换到 $localName"
}

private fun rootEmbeddedGitPush(repoRoot: String, githubToken: String?): String {
    val git = rootEmbeddedGitBaseCommand(repoRoot)
    val currentBranch = rootEmbeddedGitCurrentBranch(repoRoot) ?: error("当前没有可推送的活动分支")
    val remoteName = rootEmbeddedGitConfigValue(repoRoot, "branch.$currentBranch.remote") ?: "origin"
    val remoteTarget = resolveRootEmbeddedTransportRemote(repoRoot, remoteName, githubToken)
    rootEmbeddedGitRequireSuccess(
        runRootEmbeddedGitScript(
            "$git push ${shellQuoteEmbedded(remoteTarget)} ${shellQuoteEmbedded("HEAD:refs/heads/$currentBranch")}"
        ),
        "root push failed"
    )
    return "已完成推送"
}

private fun rootEmbeddedGitDiffPreview(
    repoRoot: String,
    change: EmbeddedGitFileChange
): EmbeddedGitDiffPreview? {
    val git = rootEmbeddedGitBaseCommand(repoRoot)
    val command = buildString {
        append(git)
        append(" diff --no-color --no-ext-diff ")
        if (change.diffMode == EmbeddedGitDiffMode.STAGED) {
            append("--cached ")
        }
        append("-- ")
        append(shellQuoteEmbedded(change.actionPath))
    }
    val output = rootEmbeddedGitRequireSuccess(
        runRootEmbeddedGitScript(command),
        "root diff preview failed"
    ).ifBlank { "该文件当前没有可显示的差异。" }
    return EmbeddedGitDiffPreview(
        title = "${change.displayPath} · 差异",
        content = output
    )
}

private fun rootEmbeddedGitCommitPreview(
    repoRoot: String,
    commitHash: String
): EmbeddedGitDiffPreview? {
    val git = rootEmbeddedGitBaseCommand(repoRoot)
    val output = rootEmbeddedGitRequireSuccess(
        runRootEmbeddedGitScript(
            "$git show --no-color --no-ext-diff --stat --patch --format=fuller ${shellQuoteEmbedded(commitHash)}"
        ),
        "root commit preview failed"
    )
    return EmbeddedGitDiffPreview(
        title = "提交 ${commitHash.take(8)}",
        content = output.ifBlank { "该提交暂无可显示详情。" }
    )
}

private fun resolveRootEmbeddedDiscardMode(output: String): String? {
    return output.lineSequence()
        .map { it.trim() }
        .lastOrNull { it.startsWith("__MURONG_DISCARD_MODE__") }
        ?.removePrefix("__MURONG_DISCARD_MODE__")
        ?.ifBlank { null }
}

private fun resolveRootEmbeddedDiscardTarget(output: String): String? {
    return output.lineSequence()
        .map { it.trim() }
        .lastOrNull { it.startsWith("__MURONG_DISCARD_TARGET__") }
        ?.removePrefix("__MURONG_DISCARD_TARGET__")
        ?.ifBlank { null }
}

private fun rootEmbeddedGitSyncDiscardAll(repoRoot: String): String {
    val git = rootEmbeddedGitBaseCommand(repoRoot)
    val result = runRootEmbeddedGitScript(
        buildString {
            appendLine("current_branch=\$($git symbolic-ref --quiet --short HEAD 2>/dev/null)")
            appendLine("remote_name=origin")
            appendLine("remote_branch=")
            appendLine("if [ -n \"\$current_branch\" ]; then")
            appendLine("  configured_remote=\$($git config --get branch.\"\$current_branch\".remote 2>/dev/null)")
            appendLine("  if [ -n \"\$configured_remote\" ]; then remote_name=\"\$configured_remote\"; fi")
            appendLine("  remote_branch=\$($git config --get branch.\"\$current_branch\".merge 2>/dev/null | sed 's#^refs/heads/##')")
            appendLine("fi")
            appendLine("reset_target=HEAD")
            appendLine("remote_url=\$($git config --get remote.\"\$remote_name\".url 2>/dev/null)")
            appendLine("if [ -n \"\$remote_url\" ] && [ -n \"\$current_branch\" ] && [ -n \"\$remote_branch\" ]; then")
            appendLine("  $git fetch \"\$remote_name\" --prune >/dev/null 2>&1 || true")
            appendLine("  if $git rev-parse --verify --quiet \"refs/remotes/\$remote_name/\$remote_branch\" >/dev/null; then")
            appendLine("    reset_target=\"refs/remotes/\$remote_name/\$remote_branch\"")
            appendLine("  fi")
            appendLine("fi")
            appendLine("$git reset --hard \"\$reset_target\"")
            appendLine("$git clean -fd")
            appendLine("printf '__MURONG_DISCARD_TARGET__%s\\n' \"\$reset_target\"")
        }
    )
    if (result.exitCode != 0) {
        error(result.output.ifBlank { "root discard all failed" })
    }
    return when (resolveRootEmbeddedDiscardTarget(result.output)) {
        null, Constants.HEAD -> "已撤回本地改动，恢复到最近提交状态"
        else -> "已撤回本地改动，并同步到远端状态"
    }
}

private fun rootEmbeddedGitDiscardPath(repoRoot: String, relativePath: String): String {
    val git = rootEmbeddedGitBaseCommand(repoRoot)
    val targetPath = File(repoRoot, relativePath).path.replace('\\', '/')
    val result = runRootEmbeddedGitScript(
        buildString {
            appendLine("current_branch=\$($git symbolic-ref --quiet --short HEAD 2>/dev/null)")
            appendLine("remote_name=origin")
            appendLine("remote_branch=")
            appendLine("if [ -n \"\$current_branch\" ]; then")
            appendLine("  configured_remote=\$($git config --get branch.\"\$current_branch\".remote 2>/dev/null)")
            appendLine("  if [ -n \"\$configured_remote\" ]; then remote_name=\"\$configured_remote\"; fi")
            appendLine("  remote_branch=\$($git config --get branch.\"\$current_branch\".merge 2>/dev/null | sed 's#^refs/heads/##')")
            appendLine("fi")
            appendLine("reset_target=HEAD")
            appendLine("remote_url=\$($git config --get remote.\"\$remote_name\".url 2>/dev/null)")
            appendLine("if [ -n \"\$remote_url\" ] && [ -n \"\$current_branch\" ] && [ -n \"\$remote_branch\" ]; then")
            appendLine("  $git fetch \"\$remote_name\" --prune >/dev/null 2>&1 || true")
            appendLine("  if $git rev-parse --verify --quiet \"refs/remotes/\$remote_name/\$remote_branch\" >/dev/null; then")
            appendLine("    reset_target=\"refs/remotes/\$remote_name/\$remote_branch\"")
            appendLine("  fi")
            appendLine("fi")
            appendLine("if $git cat-file -e \"\$reset_target:${relativePath.replace("\"", "\\\"")}\" 2>/dev/null; then")
            appendLine("  $git checkout \"\$reset_target\" -- ${shellQuoteEmbedded(relativePath)}")
            appendLine("  printf '__MURONG_DISCARD_MODE__checkout\\n'")
            appendLine("else")
            appendLine("  rm -rf ${shellQuoteEmbedded(targetPath)}")
            appendLine("  $git rm --cached -r --ignore-unmatch -- ${shellQuoteEmbedded(relativePath)} >/dev/null 2>&1 || true")
            appendLine("  printf '__MURONG_DISCARD_MODE__delete\\n'")
            appendLine("fi")
            appendLine("printf '__MURONG_DISCARD_TARGET__%s\\n' \"\$reset_target\"")
        }
    )
    if (result.exitCode != 0) {
        error(result.output.ifBlank { "root discard path failed" })
    }
    return "已撤回 $relativePath 的本地改动"
}

private fun parseRootEmbeddedGitStatus(raw: String): EmbeddedGitWorkingTreeSnapshot {
    val conflicted = linkedMapOf<String, EmbeddedGitFileChange>()
    val staged = linkedMapOf<String, EmbeddedGitFileChange>()
    val modified = linkedMapOf<String, EmbeddedGitFileChange>()
    val untracked = linkedMapOf<String, EmbeddedGitFileChange>()
    raw.lineSequence()
        .map { it.removeSuffix("\r") }
        .filter { it.length >= 3 }
        .forEach { line ->
            val x = line[0]
            val y = line[1]
            val rawPath = line.substring(3).trim()
            if (rawPath.isBlank()) return@forEach
            val actionPath = rawPath.substringAfter(" -> ", rawPath)
            when {
                x == '?' && y == '?' -> {
                    untracked[actionPath] = EmbeddedGitFileChange(
                        displayPath = rawPath,
                        actionPath = actionPath,
                        statusLabel = "未跟踪文件",
                        diffMode = EmbeddedGitDiffMode.UNTRACKED
                    )
                }
                isEmbeddedConflictStatus(x, y) -> {
                    conflicted[actionPath] = EmbeddedGitFileChange(
                        displayPath = rawPath,
                        actionPath = actionPath,
                        statusLabel = "冲突",
                        diffMode = EmbeddedGitDiffMode.WORKTREE
                    )
                }
                else -> {
                    if (x != ' ') {
                        staged[actionPath] = EmbeddedGitFileChange(
                            displayPath = rawPath,
                            actionPath = actionPath,
                            statusLabel = embeddedStagedStatusLabel(x),
                            diffMode = EmbeddedGitDiffMode.STAGED
                        )
                    }
                    if (y != ' ') {
                        modified[actionPath] = EmbeddedGitFileChange(
                            displayPath = rawPath,
                            actionPath = actionPath,
                            statusLabel = embeddedWorktreeStatusLabel(y),
                            diffMode = EmbeddedGitDiffMode.WORKTREE
                        )
                    }
                }
            }
        }
    return EmbeddedGitWorkingTreeSnapshot(
        conflictedFiles = conflicted.values.toList(),
        stagedFiles = staged.values.toList(),
        modifiedFiles = modified.values.toList(),
        untrackedFiles = untracked.values.toList()
    )
}

private fun filterRootEmbeddedLineEndingOnlyChanges(
    repoRoot: String,
    snapshot: EmbeddedGitWorkingTreeSnapshot
): EmbeddedGitWorkingTreeSnapshot {
    val filteredModified = snapshot.modifiedFiles.filterNot { change ->
        change.diffMode == EmbeddedGitDiffMode.WORKTREE &&
            isRootEmbeddedLineEndingOnlyDiff(
                repoRoot = repoRoot,
                relativePath = change.actionPath,
                cached = false
            )
    }
    val filteredStaged = snapshot.stagedFiles.filterNot { change ->
        change.diffMode == EmbeddedGitDiffMode.STAGED &&
            isRootEmbeddedLineEndingOnlyDiff(
                repoRoot = repoRoot,
                relativePath = change.actionPath,
                cached = true
            )
    }
    return snapshot.copy(
        stagedFiles = filteredStaged,
        modifiedFiles = filteredModified
    )
}

private fun isRootEmbeddedLineEndingOnlyDiff(
    repoRoot: String,
    relativePath: String,
    cached: Boolean
): Boolean {
    val marker = "__MURONG_GIT_EOL_ONLY_EXIT__"
    val command = buildString {
        append("(")
        append("git -c safe.directory=")
        append(shellQuoteEmbedded(repoRoot))
        append(" -c core.quotepath=false -C ")
        append(shellQuoteEmbedded(repoRoot))
        append(" diff ")
        if (cached) {
            append("--cached ")
        }
        append("--ignore-cr-at-eol --quiet -- ")
        append(shellQuoteEmbedded(relativePath))
        append(") 2>&1; printf '\\n")
        append(marker)
        append("%s' $?")
    }
    val raw = KeepShellPublic.doCmdSync(command)
    val markerIndex = raw.lastIndexOf(marker)
    if (markerIndex < 0) {
        return false
    }
    val exitCode = raw.substring(markerIndex + marker.length).trim().toIntOrNull()
    return exitCode == 0
}

private fun isEmbeddedConflictStatus(x: Char, y: Char): Boolean {
    return when ("$x$y") {
        "DD", "AU", "UD", "UA", "DU", "AA", "UU" -> true
        else -> false
    }
}

private fun embeddedStagedStatusLabel(code: Char): String {
    return when (code) {
        'M' -> "暂存区: 已修改"
        'A' -> "暂存区: 新增"
        'D' -> "暂存区: 删除"
        'R' -> "暂存区: 重命名"
        'C' -> "暂存区: 复制"
        'T' -> "暂存区: 类型变更"
        else -> "暂存区: 已变更"
    }
}

private fun embeddedWorktreeStatusLabel(code: Char): String {
    return when (code) {
        'M' -> "工作区: 已修改"
        'D' -> "工作区: 删除"
        'T' -> "工作区: 类型变更"
        else -> "工作区: 已变更"
    }
}

private fun shellQuoteEmbedded(value: String): String {
    return "'" + value.replace("'", "'\"'\"'") + "'"
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

private fun deleteEmbeddedWorkingTreePath(repoRoot: String, relativePath: String) {
    val target = File(repoRoot, relativePath)
    if (target.exists()) {
        target.deleteRecursively()
        target.parentFile?.let(::pruneEmbeddedEmptyParents)
    }
}

private fun pruneEmbeddedEmptyParents(dir: File) {
    var current: File? = dir
    while (current != null && current.exists()) {
        val children = current.listFiles()
        if (!children.isNullOrEmpty()) break
        if (!current.delete()) break
        current = current.parentFile
    }
}

private fun removeEmbeddedIndexPath(repository: Repository, relativePath: String) {
    val dirCache = repository.lockDirCache()
    dirCache.editor().apply {
        add(DirCacheEditor.DeletePath(relativePath))
        commit()
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
        config.setString(ConfigConstants.CONFIG_USER_SECTION, null, ConfigConstants.CONFIG_KEY_NAME, "Murong Agent")
    }
    if (!hasEmail) {
        config.setString(ConfigConstants.CONFIG_USER_SECTION, null, ConfigConstants.CONFIG_KEY_EMAIL, "agent@murong.local")
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
