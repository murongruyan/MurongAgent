package com.murong.agent.core.tool

internal data class LocalUpdatePatchResult(
    val content: String,
    val hunkCount: Int
)

private data class LocalUpdatePatchHunk(
    val oldLines: List<String>,
    val newLines: List<String>,
    val oldLineHint: Int?
)

/** Applies the `*** Update File` subset of the apply_patch format to one existing file. */
internal fun applyLocalUpdatePatch(
    expectedPath: String,
    currentContent: String,
    patchText: String
): LocalUpdatePatchResult {
    require(patchText.isNotBlank()) { "'patch_text' cannot be blank" }
    val normalizedPatch = patchText.replace("\r\n", "\n").replace('\r', '\n')
    val lines = normalizedPatch.split('\n')
    val meaningfulLines = lines.filterNot(String::isBlank)
    require(meaningfulLines.firstOrNull() == "*** Begin Patch") {
        "Patch must start with '*** Begin Patch'"
    }
    require(meaningfulLines.lastOrNull() == "*** End Patch") {
        "Patch must end with '*** End Patch'"
    }
    val updateSections = mutableListOf<Pair<String, List<String>>>()
    var index = 0

    while (index < lines.size) {
        val line = lines[index]
        when {
            line == "*** Begin Patch" || line.isBlank() -> index += 1
            line == "*** End Patch" -> break
            line.startsWith("*** Add File:") || line.startsWith("*** Delete File:") ->
                throw IllegalArgumentException("code_edit apply_patch only supports existing files via '*** Update File'")
            line.startsWith("*** Update File:") -> {
                val path = line.substringAfter(':').trim()
                require(path.isNotBlank()) { "Update File path cannot be blank" }
                index += 1
                val body = mutableListOf<String>()
                while (index < lines.size && !lines[index].startsWith("*** ")) {
                    body += lines[index]
                    index += 1
                }
                updateSections += path to body
            }
            else -> throw IllegalArgumentException("Unexpected patch line: $line")
        }
    }

    require(updateSections.size == 1) {
        "code_edit apply_patch accepts exactly one '*** Update File' section"
    }
    val (patchPath, body) = updateSections.single()
    require(pathsReferToSameFile(expectedPath, patchPath)) {
        "Patch path '$patchPath' does not match requested path '$expectedPath'"
    }

    val hunks = parseLocalUpdatePatchHunks(body)
    require(hunks.isNotEmpty()) { "Patch does not contain any @@ hunks" }

    val originalLineEnding = if (currentContent.contains("\r\n")) "\r\n" else "\n"
    val hadTrailingLineEnding = currentContent.endsWith("\n") || currentContent.endsWith("\r")
    var currentLines = currentContent
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .removeSuffix("\n")
        .let { if (it.isEmpty()) emptyList() else it.split('\n') }

    hunks.forEachIndexed { hunkIndex, hunk ->
        val start = findHunkStart(currentLines, hunk)
            ?: throw IllegalArgumentException(
                "Hunk ${hunkIndex + 1} did not match the current contents of '$expectedPath'"
            )
        currentLines = buildList {
            addAll(currentLines.take(start))
            addAll(hunk.newLines)
            addAll(currentLines.drop(start + hunk.oldLines.size))
        }
    }

    val normalizedResult = currentLines.joinToString("\n") + if (hadTrailingLineEnding) "\n" else ""
    val result = if (originalLineEnding == "\r\n") {
        normalizedResult.replace("\n", "\r\n")
    } else {
        normalizedResult
    }
    require(result != currentContent) { "Patch did not change '$expectedPath'" }
    return LocalUpdatePatchResult(content = result, hunkCount = hunks.size)
}

private fun parseLocalUpdatePatchHunks(body: List<String>): List<LocalUpdatePatchHunk> {
    val hunks = mutableListOf<LocalUpdatePatchHunk>()
    var index = 0
    while (index < body.size) {
        while (index < body.size && body[index].isBlank()) index += 1
        if (index >= body.size) break
        val header = body[index]
        require(header.startsWith("@@")) { "Expected @@ hunk header, found: $header" }
        val oldLineHint = Regex("""^@@\s+-(\d+)""")
            .find(header)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
        index += 1

        val oldLines = mutableListOf<String>()
        val newLines = mutableListOf<String>()
        while (index < body.size && !body[index].startsWith("@@")) {
            val line = body[index]
            when {
                line == "\\ No newline at end of file" -> Unit
                line.startsWith(" ") -> {
                    oldLines += line.drop(1)
                    newLines += line.drop(1)
                }
                line.startsWith("-") -> oldLines += line.drop(1)
                line.startsWith("+") -> newLines += line.drop(1)
                line.isEmpty() -> {
                    oldLines += ""
                    newLines += ""
                }
                else -> throw IllegalArgumentException("Invalid hunk line: $line")
            }
            index += 1
        }
        require(oldLines.isNotEmpty()) {
            "Insertion-only hunks are ambiguous; include at least one unchanged context line"
        }
        hunks += LocalUpdatePatchHunk(oldLines, newLines, oldLineHint)
    }
    return hunks
}

private fun findHunkStart(lines: List<String>, hunk: LocalUpdatePatchHunk): Int? {
    if (hunk.oldLines.size > lines.size) return null
    val matches = (0..lines.size - hunk.oldLines.size).filter { start ->
        lines.subList(start, start + hunk.oldLines.size) == hunk.oldLines
    }
    if (matches.isEmpty()) return null
    if (matches.size == 1) return matches.single()
    val hint = hunk.oldLineHint?.minus(1)
        ?: throw IllegalArgumentException("Hunk context matches ${matches.size} locations; add more context")
    val ordered = matches.sortedBy { kotlin.math.abs(it - hint) }
    if (ordered.size > 1 && kotlin.math.abs(ordered[0] - hint) == kotlin.math.abs(ordered[1] - hint)) {
        throw IllegalArgumentException("Hunk context is ambiguous near line ${hunk.oldLineHint}")
    }
    return ordered.first()
}

private fun pathsReferToSameFile(expectedPath: String, patchPath: String): Boolean {
    fun normalize(path: String): String = path
        .replace('\\', '/')
        .trim()
        .removePrefix("./")
        .trimEnd('/')
        .lowercase()

    val expected = normalize(expectedPath)
    val patched = normalize(patchPath)
    return expected == patched || expected.endsWith("/$patched")
}
