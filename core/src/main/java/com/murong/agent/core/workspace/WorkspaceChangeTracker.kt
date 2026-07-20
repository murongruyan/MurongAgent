package com.murong.agent.core.workspace

import android.util.Log
import com.murong.agent.core.tool.ToolFileChange
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

internal data class WorkspaceChangeBatch(
    val bindingGeneration: Long,
    val snapshot: WorkspaceChangeSnapshot,
    val attachment: String
)

/** Owns the observer for the currently active conversation/project pair. */
internal class WorkspaceChangeTracker(
    private val observerFactory: WorkspaceObserverFactory = AndroidWorkspaceObserverFactory,
    private val observerScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val accumulator: WorkspaceChangeAccumulator = WorkspaceChangeAccumulator()
) {
    private data class Binding(
        val sessionId: String,
        val projectRoot: File
    ) {
        val projectPath: String = projectRoot.absolutePath
    }

    private val lock = Any()
    private var binding: Binding? = null
    private var bindingGeneration = 0L
    private var observer: WorkspaceObserver? = null

    fun bind(sessionId: String, projectPath: String?) {
        val requestedBinding = resolveBinding(sessionId, projectPath)
        val previousObserver: WorkspaceObserver?
        val generation: Long
        synchronized(lock) {
            if (sameBinding(binding, requestedBinding)) return
            previousObserver = observer
            observer = null
            binding = requestedBinding
            bindingGeneration += 1L
            generation = bindingGeneration
            accumulator.reset()
        }
        previousObserver?.let { runCatching(it::stop) }
        val activeBinding = requestedBinding ?: return
        observerScope.launch {
            val candidate = runCatching {
                observerFactory.create(activeBinding.projectRoot) { change ->
                    recordObservedChange(generation, change)
                }
            }.onFailure { error ->
                runCatching {
                    Log.w(TAG, "Unable to create workspace observer for ${activeBinding.projectPath}", error)
                }
            }.getOrNull() ?: return@launch

            val shouldStart = synchronized(lock) {
                if (bindingGeneration == generation && sameBinding(binding, activeBinding)) {
                    observer = candidate
                    true
                } else {
                    false
                }
            }
            if (!shouldStart) {
                runCatching(candidate::stop)
                return@launch
            }
            runCatching(candidate::start)
                .onSuccess {
                    runCatching { Log.i(TAG, "Watching workspace for session ${activeBinding.sessionId}") }
                }
                .onFailure { error ->
                    runCatching {
                        Log.w(TAG, "Unable to start workspace observer for ${activeBinding.projectPath}", error)
                    }
                }
            val becameStale = synchronized(lock) {
                bindingGeneration != generation || !sameBinding(binding, activeBinding)
            }
            if (becameStale) runCatching(candidate::stop)
        }
    }

    fun prepareAttachment(sessionId: String, projectPath: String?): WorkspaceChangeBatch? {
        bind(sessionId, projectPath)
        val expectedBinding = resolveBinding(sessionId, projectPath) ?: return null
        val generation: Long
        val snapshot: WorkspaceChangeSnapshot
        synchronized(lock) {
            if (!sameBinding(binding, expectedBinding)) return null
            generation = bindingGeneration
            snapshot = accumulator.snapshot() ?: return null
        }
        val attachment = buildWorkspaceChangeAttachment(
            snapshot = snapshot,
            projectPath = expectedBinding.projectPath
        )
        runCatching {
            Log.i(
                TAG,
                "Prepared ${snapshot.changes.size} external workspace changes" +
                    if (snapshot.omittedCount > 0) " (+${snapshot.omittedCount} omitted)" else ""
            )
        }
        return WorkspaceChangeBatch(
            bindingGeneration = generation,
            snapshot = snapshot,
            attachment = attachment
        )
    }

    fun acknowledge(batch: WorkspaceChangeBatch) {
        synchronized(lock) {
            if (batch.bindingGeneration != bindingGeneration) return
            accumulator.acknowledge(batch.snapshot)
        }
        runCatching { Log.i(TAG, "Acknowledged external workspace change batch") }
    }

    fun suppressInternalChanges(
        sessionId: String,
        projectPath: String?,
        fileChanges: List<ToolFileChange>
    ) {
        if (fileChanges.isEmpty()) return
        val expectedBinding = resolveBinding(sessionId, projectPath) ?: return
        val relativePaths: List<String>
        synchronized(lock) {
            if (!sameBinding(binding, expectedBinding)) return
            relativePaths = fileChanges.mapNotNull { change ->
                resolveRelativePath(expectedBinding.projectRoot, change.path)
            }
            accumulator.suppressInternalChanges(relativePaths)
        }
        if (relativePaths.isNotEmpty()) {
            runCatching { Log.d(TAG, "Suppressed ${relativePaths.size} internal workspace changes") }
        }
    }

    fun stop() {
        val previousObserver = synchronized(lock) {
            binding = null
            bindingGeneration += 1L
            accumulator.reset()
            observer.also { observer = null }
        }
        previousObserver?.let { runCatching(it::stop) }
    }

    private fun recordObservedChange(generation: Long, change: ObservedWorkspaceChange) {
        synchronized(lock) {
            if (generation != bindingGeneration || binding == null) return
            accumulator.record(
                relativePath = change.relativePath,
                kind = change.kind,
                isDirectory = change.isDirectory
            )
        }
    }

    private fun resolveBinding(sessionId: String, projectPath: String?): Binding? {
        val normalizedSessionId = sessionId.trim()
        val normalizedProjectPath = projectPath?.trim()?.takeIf(String::isNotBlank) ?: return null
        if (normalizedSessionId.isBlank()) return null
        val projectRoot = runCatching { File(normalizedProjectPath).canonicalFile }
            .getOrElse { File(normalizedProjectPath).absoluteFile }
        return Binding(normalizedSessionId, projectRoot)
    }

    private fun resolveRelativePath(projectRoot: File, rawPath: String): String? {
        val trimmedPath = rawPath.trim()
        if (trimmedPath.isBlank()) return null
        val candidate = File(trimmedPath).let { file ->
            if (file.isAbsolute) file else File(projectRoot, trimmedPath)
        }
        val candidatePath = runCatching { candidate.canonicalFile.absolutePath }
            .getOrElse { candidate.absoluteFile.absolutePath }
        val rootPath = projectRoot.absolutePath.trimEnd(File.separatorChar)
        if (candidatePath == rootPath) return null
        val rootPrefix = "$rootPath${File.separator}"
        if (!candidatePath.startsWith(rootPrefix)) return null
        return normalizeWorkspaceRelativePath(
            candidatePath.removePrefix(rootPrefix).replace(File.separatorChar, '/')
        )
    }

    private fun sameBinding(left: Binding?, right: Binding?): Boolean {
        if (left == null || right == null) return left == right
        return left.sessionId == right.sessionId && left.projectPath == right.projectPath
    }

    private companion object {
        const val TAG = "WorkspaceChangeTracker"
    }
}
