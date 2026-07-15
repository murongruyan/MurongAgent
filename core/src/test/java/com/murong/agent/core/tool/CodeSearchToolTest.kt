package com.murong.agent.core.tool

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CodeSearchToolTest {

    private val tool = CodeSearchTool()

    @Test
    fun resolveEffectiveExcludeGlobs_excludesGeneratedArtifactsByDefault() {
        val excludes = tool.resolveEffectiveExcludeGlobs(
            excludeGlob = "*/custom-cache/*,*.min.js",
            includeGeneratedArtifacts = false
        )

        assertTrue(excludes.contains("*/build/*"))
        assertTrue(excludes.contains("*/intermediates/*"))
        assertTrue(excludes.contains("*/mapping/*"))
        assertTrue(excludes.contains("*/custom-cache/*"))
        assertTrue(excludes.contains("*.min.js"))
    }

    @Test
    fun resolveEffectiveExcludeGlobs_allowsGeneratedArtifactsWhenRequested() {
        val excludes = tool.resolveEffectiveExcludeGlobs(
            excludeGlob = "*/custom-cache/*",
            includeGeneratedArtifacts = true
        )

        assertEquals(listOf("*/custom-cache/*"), excludes)
        assertFalse(excludes.contains("*/build/*"))
        assertFalse(excludes.contains("*/mapping/*"))
    }

    @Test
    fun sortFilePathsForDisplay_prioritizesSourceFilesAheadOfGeneratedOutputs() {
        val sorted = tool.sortFilePathsForDisplay(
            listOf(
                "/repo/app/build/intermediates/mapping/release/mapping.txt",
                "/repo/core/src/main/java/com/example/ExecutionProfileDecider.kt",
                "/repo/core/src/test/java/com/example/ExecutionProfileDeciderTest.kt",
                "/repo/docs/notes.txt",
                "/repo/app/src/main/java/com/example/MainActivity.kt"
            )
        )

        assertEquals("/repo/app/src/main/java/com/example/MainActivity.kt", sorted[0])
        assertEquals("/repo/core/src/main/java/com/example/ExecutionProfileDecider.kt", sorted[1])
        assertEquals("/repo/core/src/test/java/com/example/ExecutionProfileDeciderTest.kt", sorted[2])
        assertEquals("/repo/docs/notes.txt", sorted[3])
        assertEquals("/repo/app/build/intermediates/mapping/release/mapping.txt", sorted[4])
    }
}
