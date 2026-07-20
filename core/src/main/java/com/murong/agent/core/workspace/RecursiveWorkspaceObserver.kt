package com.murong.agent.core.workspace

import android.os.FileObserver
import android.util.Log
import java.io.File

internal data class ObservedWorkspaceChange(
    val relativePath: String,
    val kind: WorkspaceChangeKind,
    val isDirectory: Boolean
)

internal interface WorkspaceObserver {
    fun start()
    fun stop()
}

internal fun interface WorkspaceObserverFactory {
    fun create(rootDirectory: File, onChange: (ObservedWorkspaceChange) -> Unit): WorkspaceObserver
}

internal object AndroidWorkspaceObserverFactory : WorkspaceObserverFactory {
    override fun create(
        rootDirectory: File,
        onChange: (ObservedWorkspaceChange) -> Unit
    ): WorkspaceObserver = RecursiveWorkspaceObserver(
        rootDirectory = rootDirectory,
        onChange = onChange
    )
}

/**
 * FileObserver is not recursive, so one lightweight observer is installed per eligible directory.
 * Limits protect Android's inotify descriptors and keep opening a very large repository bounded.
 */
internal class RecursiveWorkspaceObserver(
    rootDirectory: File,
    private val maxDepth: Int = DEFAULT_MAX_DEPTH,
    private val maxObservedDirectories: Int = DEFAULT_MAX_OBSERVED_DIRECTORIES,
    private val shouldIgnore: (String, Boolean) -> Boolean = WorkspaceChangePathPolicy::shouldIgnore,
    private val onChange: (ObservedWorkspaceChange) -> Unit
) : WorkspaceObserver {
    private val root = runCatching { rootDirectory.canonicalFile }.getOrElse { rootDirectory.absoluteFile }
    private val rootPath = root.absolutePath.trimEnd(File.separatorChar)
    private val observers = LinkedHashMap<String, DirectoryObserver>()
    private var started = false

    override fun start() {
        synchronized(this) {
            if (started) return
            started = true
        }
        if (!root.exists() || !root.isDirectory) return
        runCatching { addExistingDirectoryTree(root) }
            .onFailure { error -> Log.w(TAG, "Unable to watch workspace $rootPath", error) }
    }

    override fun stop() {
        val toStop = synchronized(this) {
            started = false
            observers.values.toList().also { observers.clear() }
        }
        toStop.forEach { observer -> runCatching(observer::stopWatching) }
    }

    private fun addExistingDirectoryTree(startDirectory: File) {
        val queue = ArrayDeque<File>()
        val visited = HashSet<String>()
        queue.add(startDirectory)
        while (queue.isNotEmpty()) {
            val directory = queue.removeFirst()
            val canonical = safeCanonical(directory) ?: continue
            val relativePath = relativePathFor(canonical) ?: continue
            if (!visited.add(canonical.absolutePath)) continue
            if (depthOf(relativePath) > maxDepth || shouldIgnore(relativePath, true)) continue
            if (!watchDirectory(canonical)) continue
            if (depthOf(relativePath) == maxDepth) continue
            canonical.listFiles()
                ?.asSequence()
                ?.filter(File::isDirectory)
                ?.forEach(queue::addLast)
        }
    }

    private fun watchDirectory(directory: File): Boolean {
        val canonical = safeCanonical(directory) ?: return false
        val path = canonical.absolutePath
        synchronized(this) {
            if (!started) return false
            if (observers.containsKey(path)) return true
            if (observers.size >= maxObservedDirectories) return false
            val observer = DirectoryObserver(canonical)
            return runCatching {
                observer.startWatching()
                observers[path] = observer
                true
            }.getOrElse { error ->
                Log.w(TAG, "Unable to watch directory $path", error)
                false
            }
        }
    }

    private fun removeDirectoryTree(directory: File) {
        val canonicalPath = safeCanonical(directory)?.absolutePath ?: directory.absolutePath
        val removed = synchronized(this) {
            observers.keys
                .filter { path -> path == canonicalPath || path.startsWith("$canonicalPath${File.separator}") }
                .mapNotNull { path -> observers.remove(path) }
        }
        removed.forEach { observer -> runCatching(observer::stopWatching) }
    }

    /** Capture files that may already exist by the time a newly created directory is observed. */
    private fun reportNewDirectoryTree(directory: File) {
        val queue = ArrayDeque<File>()
        val visited = HashSet<String>()
        var reported = 0
        queue.add(directory)
        while (queue.isNotEmpty() && reported < MAX_NEW_DIRECTORY_TREE_PATHS) {
            val current = queue.removeFirst()
            val canonical = safeCanonical(current) ?: continue
            if (!visited.add(canonical.absolutePath)) continue
            val children = canonical.listFiles() ?: continue
            for (child in children) {
                if (reported >= MAX_NEW_DIRECTORY_TREE_PATHS) break
                val relativePath = relativePathFor(child) ?: continue
                val isDirectory = child.isDirectory
                if (shouldIgnore(relativePath, isDirectory)) continue
                report(relativePath, WorkspaceChangeKind.CREATED, isDirectory)
                reported += 1
                if (isDirectory && depthOf(relativePath) <= maxDepth) queue.addLast(child)
            }
        }
    }

    private fun report(
        relativePath: String,
        kind: WorkspaceChangeKind,
        isDirectory: Boolean
    ) {
        runCatching {
            onChange(
                ObservedWorkspaceChange(
                    relativePath = relativePath,
                    kind = kind,
                    isDirectory = isDirectory
                )
            )
        }.onFailure { error -> Log.w(TAG, "Workspace change callback failed", error) }
    }

    private fun relativePathFor(file: File): String? {
        val canonicalPath = safeCanonical(file)?.absolutePath ?: file.absolutePath
        if (canonicalPath == rootPath) return ""
        val prefix = "$rootPath${File.separator}"
        if (!canonicalPath.startsWith(prefix)) return null
        return canonicalPath.removePrefix(prefix).replace(File.separatorChar, '/')
    }

    private fun safeCanonical(file: File): File? = runCatching { file.canonicalFile }.getOrNull()

    private fun depthOf(relativePath: String): Int {
        val normalized = relativePath.trim('/').replace('\\', '/')
        return if (normalized.isBlank()) 0 else normalized.count { it == '/' } + 1
    }

    @Suppress("DEPRECATION")
    private inner class DirectoryObserver(
        val directory: File
    ) : FileObserver(directory.absolutePath, EVENT_MASK) {
        override fun onEvent(event: Int, path: String?) {
            val childName = path?.takeIf(String::isNotBlank) ?: return
            val child = File(directory, childName)
            val relativePath = relativePathFor(child) ?: return
            val normalizedEvent = event and FileObserver.ALL_EVENTS
            val observedDirectory = synchronized(this@RecursiveWorkspaceObserver) {
                val childPath = safeCanonical(child)?.absolutePath ?: child.absolutePath
                observers.containsKey(childPath)
            }
            val isDirectoryNow = child.exists() && child.isDirectory
            val isDirectory = isDirectoryNow || observedDirectory
            if (shouldIgnore(relativePath, isDirectory)) return

            when {
                normalizedEvent and (FileObserver.CREATE or FileObserver.MOVED_TO) != 0 -> {
                    report(relativePath, WorkspaceChangeKind.CREATED, isDirectoryNow)
                    if (isDirectoryNow && depthOf(relativePath) <= maxDepth) {
                        addExistingDirectoryTree(child)
                        reportNewDirectoryTree(child)
                    }
                }
                normalizedEvent and (FileObserver.DELETE or FileObserver.MOVED_FROM) != 0 -> {
                    report(relativePath, WorkspaceChangeKind.DELETED, isDirectory)
                    if (observedDirectory) removeDirectoryTree(child)
                }
                normalizedEvent and (FileObserver.CLOSE_WRITE or FileObserver.MODIFY or FileObserver.ATTRIB) != 0 -> {
                    if (!isDirectoryNow) {
                        report(relativePath, WorkspaceChangeKind.MODIFIED, false)
                    }
                }
            }
        }
    }

    private companion object {
        const val TAG = "WorkspaceObserver"
        const val DEFAULT_MAX_DEPTH = 12
        const val DEFAULT_MAX_OBSERVED_DIRECTORIES = 1_024
        const val MAX_NEW_DIRECTORY_TREE_PATHS = 240
        const val EVENT_MASK =
            FileObserver.CREATE or
                FileObserver.DELETE or
                FileObserver.MODIFY or
                FileObserver.ATTRIB or
                FileObserver.CLOSE_WRITE or
                FileObserver.MOVED_FROM or
                FileObserver.MOVED_TO or
                FileObserver.DELETE_SELF or
                FileObserver.MOVE_SELF
    }
}

internal object WorkspaceChangePathPolicy {
    private val ignoredDirectoryNames = setOf(
        ".git",
        ".gradle",
        ".idea",
        ".cxx",
        ".externalnativebuild",
        ".gitnexus",
        ".cache",
        ".next",
        ".nuxt",
        ".dart_tool",
        "build",
        "out",
        "dist",
        "target",
        "node_modules",
        "bower_components",
        "__pycache__",
        ".pytest_cache",
        ".mypy_cache",
        ".ruff_cache",
        ".tox",
        ".venv",
        "venv",
        "coverage",
        ".coverage",
        "pods",
        "deriveddata"
    )
    private val ignoredFileNames = setOf(
        ".ds_store",
        "thumbs.db",
        "desktop.ini"
    )
    private val ignoredFileSuffixes = setOf(
        "~",
        ".swp",
        ".swo",
        ".tmp",
        ".temp",
        ".part",
        ".crdownload"
    )

    fun shouldIgnore(relativePath: String, isDirectory: Boolean): Boolean {
        val normalized = normalizeWorkspaceRelativePath(relativePath) ?: return relativePath.isNotBlank()
        val segments = normalized.split('/')
        val lowerSegments = segments.map(String::lowercase)
        if (lowerSegments.dropLast(1).any(ignoredDirectoryNames::contains)) return true
        val leaf = lowerSegments.last()
        if (isDirectory && leaf in ignoredDirectoryNames) return true
        if (!isDirectory && leaf in ignoredFileNames) return true
        return !isDirectory && ignoredFileSuffixes.any(leaf::endsWith)
    }
}
