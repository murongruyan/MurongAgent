package com.murong.agent.core.workspace

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.util.Log
import java.io.File
import java.util.Collections
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.eclipse.jgit.api.Git

@RunWith(AndroidJUnit4::class)
class RecursiveWorkspaceObserverInstrumentedTest {
    @Test
    fun testExternalProcessAtomicReplaceAndIgnoredDirectories() {
        val root = File(
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "workspace-observer-${System.nanoTime()}"
        )
        File(root, "src").mkdirs()
        File(root, ".git").mkdirs()
        File(root, "build").mkdirs()
        val events = Collections.synchronizedList(mutableListOf<ObservedWorkspaceChange>())
        val observer = RecursiveWorkspaceObserver(rootDirectory = root, onChange = events::add)
        observer.start()

        try {
            val command = buildString {
                append("printf 'one\\n' > '")
                append(File(root, "src/shell.txt").absolutePath)
                append("'; printf 'two\\n' >> '")
                append(File(root, "src/shell.txt").absolutePath)
                append("'; printf 'atomic\\n' > '")
                append(File(root, "src/.atomic.tmp").absolutePath)
                append("'; mv '")
                append(File(root, "src/.atomic.tmp").absolutePath)
                append("' '")
                append(File(root, "src/atomic.txt").absolutePath)
                append("'; printf 'git metadata\\n' > '")
                append(File(root, ".git/index").absolutePath)
                append("'; printf 'build output\\n' > '")
                append(File(root, "build/output.bin").absolutePath)
                append("'")
            }
            val process = ProcessBuilder("/system/bin/sh", "-c", command)
                .redirectErrorStream(true)
                .start()
            assertTrue("external shell command timed out", process.waitFor(8, TimeUnit.SECONDS))
            assertEquals("external shell command failed", 0, process.exitValue())

            assertTrue("shell and atomic changes were not observed", waitUntil(8_000L) {
                val paths = synchronized(events) { events.map(ObservedWorkspaceChange::relativePath).toSet() }
                "src/shell.txt" in paths && "src/atomic.txt" in paths
            })
            val snapshot = synchronized(events) { events.toList() }
            assertTrue(snapshot.any {
                it.relativePath == "src/shell.txt" &&
                    it.kind in setOf(WorkspaceChangeKind.CREATED, WorkspaceChangeKind.MODIFIED)
            })
            assertTrue(snapshot.any {
                it.relativePath == "src/atomic.txt" && it.kind == WorkspaceChangeKind.CREATED
            })
            assertFalse(snapshot.any { it.relativePath.startsWith(".git/") })
            assertFalse(snapshot.any { it.relativePath.startsWith("build/") })
            assertFalse(snapshot.any { it.relativePath.endsWith(".tmp") })
        } finally {
            observer.stop()
            root.deleteRecursively()
        }
    }

    @Test
    fun testGitCheckoutReportsWorktreeButNotGitMetadata() {
        val root = File(
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "workspace-observer-git-${System.nanoTime()}"
        ).apply { mkdirs() }
        val trackedFile = File(root, "tracked.txt")
        val events = Collections.synchronizedList(mutableListOf<ObservedWorkspaceChange>())

        Git.init().setDirectory(root).call().use { git ->
            val mainBranch = git.repository.branch
            trackedFile.writeText("main branch\n")
            git.add().addFilepattern("tracked.txt").call()
            git.commit()
                .setMessage("main")
                .setAuthor("Murong Test", "test@example.invalid")
                .setCommitter("Murong Test", "test@example.invalid")
                .call()
            git.checkout().setCreateBranch(true).setName("workspace-observer-feature").call()
            trackedFile.writeText("feature branch\n")
            git.add().addFilepattern("tracked.txt").call()
            git.commit()
                .setMessage("feature")
                .setAuthor("Murong Test", "test@example.invalid")
                .setCommitter("Murong Test", "test@example.invalid")
                .call()

            val observer = RecursiveWorkspaceObserver(rootDirectory = root, onChange = events::add)
            observer.start()
            try {
                git.checkout().setName(mainBranch).call()
                assertTrue("git checkout worktree change was not observed", waitUntil(8_000L) {
                    synchronized(events) {
                        events.any { change -> change.relativePath == "tracked.txt" }
                    }
                })
                assertEquals("main branch\n", trackedFile.readText())
                assertFalse(synchronized(events) {
                    events.any { change ->
                        change.relativePath == ".git" || change.relativePath.startsWith(".git/")
                    }
                })
            } finally {
                observer.stop()
            }
        }
        root.deleteRecursively()
    }

    @Test
    fun testRootShellWriteProducesTheSameWorkspaceEvent() {
        assumeTrue(
            "Root probe is enabled only by the host-coordinated device test",
            InstrumentationRegistry.getArguments().getString("rootProbe") == "true"
        )
        val root = File(
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "workspace-observer-root-${System.nanoTime()}"
        )
        val sourceDirectory = File(root, "src").apply { mkdirs() }
        val events = Collections.synchronizedList(mutableListOf<ObservedWorkspaceChange>())
        val observer = RecursiveWorkspaceObserver(rootDirectory = root, onChange = events::add)
        observer.start()

        try {
            val target = File(sourceDirectory, "root-shell.txt")
            Log.i(ROOT_PROBE_TAG, "ROOT_PROBE_READY:${target.absolutePath}")
            assertTrue("root shell file event was not observed", waitUntil(20_000L) {
                synchronized(events) {
                    events.any { change -> change.relativePath == "src/root-shell.txt" }
                }
            })
        } finally {
            observer.stop()
            root.deleteRecursively()
        }
    }

    private fun waitUntil(timeoutMillis: Long, predicate: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return true
            Thread.sleep(50L)
        }
        return predicate()
    }

    private companion object {
        const val ROOT_PROBE_TAG = "WorkspaceRootProbe"
    }
}
