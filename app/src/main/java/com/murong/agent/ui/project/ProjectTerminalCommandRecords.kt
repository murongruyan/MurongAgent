package com.murong.agent.ui.project

/**
 * A completed interactive shell command recorded by the shell rc file.
 *
 * The command log deliberately contains only data that is already visible to the user in the
 * terminal: the submitted command, its exit result and the directory in which it ran.  It is not
 * a second execution channel and never causes a command to be run.
 */
internal data class ProjectTerminalCommandRecord(
    val commandId: String,
    val command: String,
    val workingDirectory: String,
    val exitCode: Int?,
    val startedAtMillis: Long,
    val finishedAtMillis: Long?,
    val status: ProjectTerminalCommandStatus,
    val environmentMode: ProjectTerminalEnvironmentMode? = null,
    /** A stable reference to the interactive terminal session containing the complete transcript. */
    val transcriptReference: String? = null
) {
    /** Retained for existing log sorting and legacy persisted records. */
    val timestampMillis: Long
        get() = finishedAtMillis ?: startedAtMillis
}

internal enum class ProjectTerminalCommandStatus {
    COMPLETED,
    INTERRUPTED
}

internal enum class ProjectTerminalCommandRecordEventKind {
    STARTED,
    FINISHED
}

/** One append-only shell event.  STARTED and FINISHED are paired by [commandId]. */
internal data class ProjectTerminalCommandRecordEvent(
    val kind: ProjectTerminalCommandRecordEventKind,
    val commandId: String,
    val command: String? = null,
    val workingDirectory: String? = null,
    val exitCode: Int? = null,
    val timestampMillis: Long
)

private const val PROJECT_TERMINAL_COMMAND_RECORD_SEPARATOR = '\u001F'
private const val PROJECT_TERMINAL_COMMAND_RECORD_STARTED = "S"
private const val PROJECT_TERMINAL_COMMAND_RECORD_FINISHED = "E"

/**
 * Parses the structured START/FINISH events produced by the interactive Bash hooks.  The old
 * four-column completion line remains readable so already-created sessions are not discarded.
 */
internal fun parseProjectTerminalCommandRecordEvent(
    line: String,
    fallbackTimestampMillis: Long = System.currentTimeMillis()
): ProjectTerminalCommandRecordEvent? {
    val fields = line.split(PROJECT_TERMINAL_COMMAND_RECORD_SEPARATOR)
    fun normalizeTimestamp(raw: String): Long {
        val value = raw.trim().toLongOrNull()
        return when {
            value == null -> fallbackTimestampMillis
            value in 1..9_999_999_999L -> value * 1_000L
            else -> value
        }
    }
    return when {
        fields.size == 5 && fields[0] == PROJECT_TERMINAL_COMMAND_RECORD_STARTED -> {
            val commandId = fields[1].trim()
            val command = fields[2].trim()
            val directory = fields[3].trim()
            if (commandId.isBlank() || command.isBlank() || directory.isBlank()) null else {
                ProjectTerminalCommandRecordEvent(
                    kind = ProjectTerminalCommandRecordEventKind.STARTED,
                    commandId = commandId,
                    command = command,
                    workingDirectory = directory,
                    timestampMillis = normalizeTimestamp(fields[4])
                )
            }
        }
        fields.size == 5 && fields[0] == PROJECT_TERMINAL_COMMAND_RECORD_FINISHED -> {
            val commandId = fields[1].trim()
            if (commandId.isBlank()) null else {
                ProjectTerminalCommandRecordEvent(
                    kind = ProjectTerminalCommandRecordEventKind.FINISHED,
                    commandId = commandId,
                    exitCode = fields[2].trim().toIntOrNull(),
                    workingDirectory = fields[3].trim().ifBlank { null },
                    timestampMillis = normalizeTimestamp(fields[4])
                )
            }
        }
        else -> parseProjectTerminalCommandRecord(line, fallbackTimestampMillis)?.let { legacy ->
            ProjectTerminalCommandRecordEvent(
                kind = ProjectTerminalCommandRecordEventKind.FINISHED,
                commandId = "legacy-${legacy.timestampMillis}-${legacy.command.hashCode()}",
                command = legacy.command,
                workingDirectory = legacy.workingDirectory,
                exitCode = legacy.exitCode,
                timestampMillis = legacy.timestampMillis
            )
        }
    }
}

/** Parses one line emitted by `murong_record_command` in the interactive shell rc file. */
internal fun parseProjectTerminalCommandRecord(
    line: String,
    fallbackTimestampMillis: Long = System.currentTimeMillis()
): ProjectTerminalCommandRecord? {
    val fields = line.split(PROJECT_TERMINAL_COMMAND_RECORD_SEPARATOR)
    if (fields.size != 4) return null
    val command = fields[0].trim()
    val workingDirectory = fields[1].trim()
    if (command.isBlank() || workingDirectory.isBlank()) return null
    val rawTimestamp = fields[3].trim().toLongOrNull()
    val timestampMillis = when {
        rawTimestamp == null -> fallbackTimestampMillis
        rawTimestamp in 1..9_999_999_999L -> rawTimestamp * 1_000L
        else -> rawTimestamp
    }
    return ProjectTerminalCommandRecord(
        commandId = "legacy-$timestampMillis-${command.hashCode()}",
        command = command,
        workingDirectory = workingDirectory,
        exitCode = fields[2].trim().toIntOrNull(),
        startedAtMillis = timestampMillis,
        finishedAtMillis = timestampMillis,
        status = ProjectTerminalCommandStatus.COMPLETED
    )
}

internal fun projectTerminalCommandRecordFromEvents(
    started: ProjectTerminalCommandRecordEvent,
    finished: ProjectTerminalCommandRecordEvent,
    transcriptReference: String
): ProjectTerminalCommandRecord? {
    if (
        started.kind != ProjectTerminalCommandRecordEventKind.STARTED ||
        finished.kind != ProjectTerminalCommandRecordEventKind.FINISHED ||
        started.commandId != finished.commandId ||
        started.command.isNullOrBlank() ||
        started.workingDirectory.isNullOrBlank()
    ) return null
    return ProjectTerminalCommandRecord(
        commandId = started.commandId,
        command = started.command,
        workingDirectory = finished.workingDirectory ?: started.workingDirectory,
        exitCode = finished.exitCode,
        startedAtMillis = started.timestampMillis,
        finishedAtMillis = finished.timestampMillis,
        status = if (finished.exitCode == 130) {
            ProjectTerminalCommandStatus.INTERRUPTED
        } else {
            ProjectTerminalCommandStatus.COMPLETED
        },
        transcriptReference = transcriptReference
    )
}

/**
 * Returns the visible output following the most recent echo of [command].  This is intentionally
 * best-effort: full transcript export remains the lossless way to retain very long output.
 */
internal fun projectTerminalOutputPreviewForCommand(
    transcript: String,
    command: String,
    limit: Int = 1_200
): String {
    val normalizedCommand = command.trim()
    if (normalizedCommand.isBlank()) return ""
    val commandIndex = transcript.lastIndexOf(normalizedCommand)
    if (commandIndex < 0) return ""
    val afterCommand = transcript.substring(commandIndex + normalizedCommand.length)
        .removePrefix("\r")
        .removePrefix("\n")
    val nextPromptIndex = sequenceOf("\n$ ", "\n# ")
        .map { marker -> afterCommand.indexOf(marker) }
        .filter { it >= 0 }
        .minOrNull()
    return afterCommand
        .let { content -> if (nextPromptIndex == null) content else content.substring(0, nextPromptIndex) }
        .trim()
        .take(limit.coerceAtLeast(1))
}

internal data class ProjectTerminalOutputPreview(
    val content: String,
    val nextSearchOffset: Int
)

/** Resolves records in execution order so repeated commands cannot receive one another's output. */
internal fun projectTerminalOutputPreviewAfterOffset(
    transcript: String,
    command: String,
    searchOffset: Int,
    limit: Int = 1_200
): ProjectTerminalOutputPreview {
    val normalizedCommand = command.trim()
    val safeOffset = searchOffset.coerceAtLeast(0)
    if (normalizedCommand.isBlank()) return ProjectTerminalOutputPreview("", safeOffset)
    val commandIndex = transcript.indexOf(normalizedCommand, safeOffset)
    if (commandIndex < 0) return ProjectTerminalOutputPreview("", safeOffset)
    val afterCommand = transcript.substring(commandIndex + normalizedCommand.length)
        .removePrefix("\r")
        .removePrefix("\n")
    val nextPromptIndex = sequenceOf("\n$ ", "\n# ")
        .map { marker -> afterCommand.indexOf(marker) }
        .filter { it >= 0 }
        .minOrNull()
    val untrimmed = if (nextPromptIndex == null) afterCommand else afterCommand.substring(0, nextPromptIndex)
    return ProjectTerminalOutputPreview(
        content = untrimmed.trim().take(limit.coerceAtLeast(1)),
        nextSearchOffset = commandIndex + normalizedCommand.length + (nextPromptIndex ?: untrimmed.length)
    )
}
