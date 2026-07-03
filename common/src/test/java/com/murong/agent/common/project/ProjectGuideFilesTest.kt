package com.murong.agent.common.project

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectGuideFilesTest {

    @Test
    fun resolveProjectKnowledgeFiles_mergesAutoGuidesBeforeConfiguredPaths() {
        val projectRoot = Files.createTempDirectory("project-guides").toFile()
        projectRoot.resolve("AGENTS.md").writeText("# agents")
        projectRoot.resolve("README.md").writeText("# readme")
        val manualDoc = projectRoot.resolve("docs/custom.md").apply {
            parentFile?.mkdirs()
            writeText("custom")
        }

        val resolution = resolveProjectKnowledgeFiles(
            projectPath = projectRoot.absolutePath,
            configuredPaths = listOf(manualDoc.absolutePath, manualDoc.absolutePath)
        )

        assertEquals(
            listOf(
                projectRoot.resolve("AGENTS.md").canonicalPath,
                projectRoot.resolve("README.md").canonicalPath
            ),
            resolution.autoGuidePaths
        )
        assertEquals(listOf(manualDoc.canonicalPath), resolution.configuredPaths)
        assertEquals(
            listOf(
                projectRoot.resolve("AGENTS.md").canonicalPath,
                projectRoot.resolve("README.md").canonicalPath,
                manualDoc.canonicalPath
            ),
            resolution.mergedPaths
        )
    }

    @Test
    fun resolveProjectKnowledgeFiles_skipsMissingProjectRoot() {
        val manualPath = File("C:/tmp/not-used.md").canonicalPath
        val resolution = resolveProjectKnowledgeFiles(
            projectPath = "",
            configuredPaths = listOf(manualPath)
        )

        assertTrue(resolution.autoGuidePaths.isEmpty())
        assertEquals(listOf(manualPath), resolution.configuredPaths)
        assertEquals(listOf(manualPath), resolution.mergedPaths)
    }
}
