package com.murong.agent.core.tool

import java.io.File

/**
 * Enforces the active project boundary for local file mutations. The policy is
 * deliberately independent from RootFile so callers can validate before both
 * approval and execution.
 */
class WorkspacePathPolicy(
    private val workspaceRootProvider: () -> String?
) {
    sealed interface Result {
        data class Allowed(val canonicalPath: String, val relativePath: String) : Result
        data class Rejected(val reason: String) : Result
    }

    fun resolve(path: String): Result {
        val rootValue = workspaceRootProvider()?.trim().orEmpty()
        if (rootValue.isBlank()) {
            return Result.Rejected("No active project is attached; local file mutations are limited to an active project.")
        }
        if (path.trim().isBlank()) return Result.Rejected("File path must not be blank.")

        return runCatching {
            val root = File(rootValue).canonicalFile
            val target = canonicalTarget(File(path.trim()), root)
            val relative = target.relativeTo(root).invariantSeparatorsPath
            Result.Allowed(target.path, relative)
        }.getOrElse { error ->
            Result.Rejected("Path is outside the active project or cannot be resolved: ${error.message}")
        }
    }

    private fun canonicalTarget(input: File, root: File): File {
        val absolute = if (input.isAbsolute) input else File(root, input.path)
        var current: File? = absolute
        val tail = ArrayDeque<String>()
        while (current != null && !current.exists()) {
            current.name.takeIf { it.isNotEmpty() }?.let(tail::addFirst)
            val parent = current.parentFile
            if (parent == current) break
            current = parent
        }
        val existing = current?.canonicalFile ?: absolute.canonicalFile
        val resolved = tail.fold(existing) { parent, segment -> File(parent, segment) }.canonicalFile
        if (resolved != root && !resolved.path.startsWith(root.path + File.separator)) {
            throw IllegalArgumentException("$resolved is not inside $root")
        }
        return resolved
    }
}

internal fun WorkspacePathPolicy.requireAllowed(path: String): String {
    return when (val result = resolve(path)) {
        is WorkspacePathPolicy.Result.Allowed -> result.canonicalPath
        is WorkspacePathPolicy.Result.Rejected -> throw IllegalArgumentException(result.reason)
    }
}
