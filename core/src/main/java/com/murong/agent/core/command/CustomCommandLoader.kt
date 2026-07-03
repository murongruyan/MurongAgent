package com.murong.agent.core.command

import java.io.File

/**
 * Loads custom slash commands from Markdown files in convention directories.
 *
 * Search order:
 * 1. Project-level: {projectRoot}/.murong/commands/ (primary)
 * 2. Also scans: .reasonix/commands/, .claude/commands/, .agent/commands/, .agents/commands/
 *
 * Each *.md file should have optional frontmatter:
 * ---
 * description: Review a file for bugs
 * argument-hint: [path]
 * ---
 * Body content with $ARGUMENTS, $1..$N, $$ placeholders.
 *
 * File path determines command name:
 *   git/commit.md → name = "git:commit"
 *   review.md     → name = "review"
 */
object CustomCommandLoader {

    private const val MURONG_DIR = ".murong/commands"
    private val conventionDirs = listOf(
        MURONG_DIR,
        ".reasonix/commands",
        ".claude/commands",
        ".agent/commands",
        ".agents/commands"
    )

    private const val FRONTMATTER_SEPARATOR = "---"

    /**
     * Loads all custom commands from a project root.
     */
    fun load(projectRoot: String?): List<CustomCommand> {
        if (projectRoot.isNullOrBlank()) return emptyList()
        val root = File(projectRoot)
        if (!root.isDirectory) return emptyList()

        return conventionDirs.flatMap { relativeDir ->
            val dir = File(root, relativeDir)
            if (!dir.isDirectory) return@flatMap emptyList()
            dir.walkTopDown()
                .filter { it.isFile && it.extension == "md" }
                .mapNotNull { file -> parseCommandFile(file, relativeDir) }
                .toList()
        }
    }

    private fun parseCommandFile(file: File, relativeDir: String): CustomCommand? {
        val content = runCatching { file.readText() }.getOrNull() ?: return null
        if (content.isBlank()) return null

        // Derive command name from relative path: "git/commit" from ".reasonix/commands/git/commit.md"
        val parentDir = file.parentFile?.name?.takeIf { it != File(relativeDir).name }
        val fileName = file.nameWithoutExtension
        val name = if (parentDir != null && parentDir != fileName) "$parentDir:$fileName" else fileName

        // Parse frontmatter
        val lines = content.lines()
        var description = ""
        var argumentHint = ""
        var bodyStart = 0

        if (lines.size >= 2 && lines[0].trim() == FRONTMATTER_SEPARATOR) {
            var fmEnd = -1
            for (i in 1 until lines.size) {
                if (lines[i].trim() == FRONTMATTER_SEPARATOR) {
                    fmEnd = i
                    break
                }
                val line = lines[i]
                val colonIndex = line.indexOf(':')
                if (colonIndex > 0) {
                    val key = line.substring(0, colonIndex).trim().lowercase()
                    val value = line.substring(colonIndex + 1).trim()
                    when (key) {
                        "description" -> description = value
                        "argument-hint" -> argumentHint = value
                    }
                }
            }
            bodyStart = if (fmEnd > 0) fmEnd + 1 else 0
        }

        val body = lines.drop(bodyStart)
            .joinToString("\n")
            .trim()
        if (body.isBlank()) return null

        return CustomCommand(
            name = name,
            description = description,
            argumentHint = argumentHint,
            body = body
        )
    }

    /**
     * Resolves a slash command from input text.
     * Returns null if the input doesn't start with / or no matching command found.
     */
    fun resolve(
        input: String,
        commands: List<CustomCommand>
    ): ResolvedCommand? {
        val trimmed = input.trim()
        if (!trimmed.startsWith("/")) return null

        // Split into /name and args
        val spaceIndex = trimmed.indexOf(' ')
        val cmdName = if (spaceIndex > 0) trimmed.substring(1, spaceIndex).trim()
            else trimmed.substring(1).trim()
        val args = if (spaceIndex > 0) trimmed.substring(spaceIndex + 1).trim()
            else ""

        if (cmdName.isBlank()) return null

        // Try exact match first, then prefix match (e.g. "git:c" matches "git:commit")
        val match = commands.firstOrNull { it.name == cmdName }
            ?: commands.firstOrNull { it.name.startsWith(cmdName) && it.name.contains(':') }
        return match?.let { ResolvedCommand(it, args) }
    }

    data class ResolvedCommand(
        val command: CustomCommand,
        val args: String
    ) {
        /** The text that should replace the user's input when this command is invoked */
        val expandedText: String get() = command.expand(args)
    }
}
