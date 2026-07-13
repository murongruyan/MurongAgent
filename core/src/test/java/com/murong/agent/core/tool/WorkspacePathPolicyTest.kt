package com.murong.agent.core.tool

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WorkspacePathPolicyTest {
    @Test
    fun resolve_allowsRelativeAndAbsolutePathsInsideWorkspace() {
        val root = Files.createTempDirectory("workspace-policy")
        try {
            val policy = WorkspacePathPolicy { root.toString() }
            val relative = assertIs<WorkspacePathPolicy.Result.Allowed>(policy.resolve("src/Main.kt"))
            val absolute = assertIs<WorkspacePathPolicy.Result.Allowed>(policy.resolve(root.resolve("src/Main.kt").toString()))

            assertEquals("src/Main.kt", relative.relativePath)
            assertEquals(relative.canonicalPath, absolute.canonicalPath)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun resolve_rejectsEscapingPathAndMissingWorkspace() {
        val root = Files.createTempDirectory("workspace-policy")
        try {
            val policy = WorkspacePathPolicy { root.toString() }
            val escaped = assertIs<WorkspacePathPolicy.Result.Rejected>(policy.resolve("../outside.txt"))
            assertTrue(escaped.reason.contains("outside"))

            val missing = assertIs<WorkspacePathPolicy.Result.Rejected>(WorkspacePathPolicy { null }.resolve("file.txt"))
            assertTrue(missing.reason.contains("No active project"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
