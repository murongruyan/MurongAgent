package com.murong.agent.automation

import com.murong.agent.core.automation.SavedWorkflowDefinition
import com.murong.agent.core.automation.SavedWorkflowTemplate
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ExternalWorkflowSecurityTest {
    @Test
    fun `access token is stored only as hash and rotation invalidates old token`() {
        val stateFile = File(createTempDirectory("external-access").toFile(), "state.json")
        val store = ExternalWorkflowAccessStore(stateFile)

        val first = store.enableWithNewToken(nowMillis = 10)
        assertTrue(store.authenticate(first.token))
        assertFalse(stateFile.readText().contains(first.token))

        val second = store.enableWithNewToken(nowMillis = 20)
        assertFalse(store.authenticate(first.token))
        assertTrue(store.authenticate(second.token))
        assertFalse(stateFile.readText().contains(second.token))
        assertEquals(20, store.status().tokenCreatedAt)
    }

    @Test
    fun `disabled and corrupt access state fail closed`() {
        val stateFile = File(createTempDirectory("external-access").toFile(), "state.json")
        val store = ExternalWorkflowAccessStore(stateFile)
        val token = store.enableWithNewToken().token
        store.disable()
        assertFalse(store.authenticate(token))
        stateFile.writeText("not-json")
        assertFalse(store.status().enabled)
        assertFalse(store.authenticate(token))
    }

    @Test
    fun `request id can be consumed only once inside replay window`() {
        val stateFile = File(createTempDirectory("external-access").toFile(), "state.json")
        val store = ExternalWorkflowAccessStore(stateFile)
        store.enableWithNewToken(nowMillis = 0)

        assertTrue(store.claimRequest("request-001", nowMillis = 1_000))
        assertFalse(store.claimRequest("request-001", nowMillis = 2_000))
        assertTrue(store.claimRequest("request-001", nowMillis = 24L * 60 * 60 * 1_000 + 1_001))
    }

    @Test
    fun `parser rejects callback package spoofing and unsafe text`() {
        val token = "a".repeat(43)
        val spoofed = ExternalWorkflowRequestPolicy.parse(
            ExternalWorkflowRawRequest(
                workflowId = "workflow-1",
                accessToken = token,
                requestId = "request-001",
                projectPath = null,
                taskText = null,
                callbackPackage = "evil.example",
                sentFromPackage = "tasker.example"
            )
        )
        assertTrue(spoofed.isFailure)

        val controlCharacter = ExternalWorkflowRequestPolicy.parse(
            ExternalWorkflowRawRequest(
                workflowId = "workflow-1",
                accessToken = token,
                requestId = "request-002",
                projectPath = null,
                taskText = "hello\u0001world",
                callbackPackage = null,
                sentFromPackage = null
            )
        )
        assertTrue(controlCharacter.isFailure)
    }

    @Test
    fun `parser accepts callback only for real sender`() {
        val parsed = ExternalWorkflowRequestPolicy.parse(
            ExternalWorkflowRawRequest(
                workflowId = "workflow-1",
                accessToken = "b".repeat(43),
                requestId = "request-003",
                projectPath = null,
                taskText = "充电后例行检查",
                callbackPackage = "net.dinglisch.android.taskerm",
                sentFromPackage = "net.dinglisch.android.taskerm"
            )
        ).getOrThrow()
        assertEquals("net.dinglisch.android.taskerm", parsed.callbackPackage)
        assertEquals("充电后例行检查", parsed.taskText)
    }

    @Test
    fun `project override may narrow but cannot escape saved root`() {
        val parent = createTempDirectory("external-scope").toFile()
        val root = File(parent, "project").apply { mkdirs() }
        val child = File(root, "module").apply { mkdirs() }
        val sibling = File(parent, "project-copy").apply { mkdirs() }
        val workflow = SavedWorkflowDefinition(
            name = "diagnostic",
            template = SavedWorkflowTemplate.PROJECT_READ_DIAGNOSTIC,
            projectPath = root.path
        )

        val narrowed = ExternalWorkflowRequestPolicy.applyProjectOverride(workflow, child.path).getOrThrow()
        assertEquals(child.canonicalPath, narrowed.projectPath)
        assertTrue(ExternalWorkflowRequestPolicy.applyProjectOverride(workflow, sibling.path).isFailure)
        assertTrue(
            ExternalWorkflowRequestPolicy
                .applyProjectOverride(workflow, File(child, "../../project-copy").path)
                .isFailure
        )
    }

    @Test
    fun `write template cannot become background safe through external input`() {
        val export = SavedWorkflowDefinition(
            name = "export",
            template = SavedWorkflowTemplate.SESSION_SUMMARY_EXPORT
        )
        val policy = ExternalWorkflowRequestPolicy.requireBackgroundSafe(export)
        assertTrue(policy.isFailure)
        assertNotNull(policy.exceptionOrNull()?.message)
    }

    @Test
    fun `GitHub status accepts no project override`() {
        val workflow = SavedWorkflowDefinition(
            name = "actions",
            template = SavedWorkflowTemplate.GITHUB_ACTIONS_STATUS,
            githubRepository = "murong/example"
        )
        assertTrue(ExternalWorkflowRequestPolicy.applyProjectOverride(workflow, "/tmp/project").isFailure)
        assertTrue(ExternalWorkflowRequestPolicy.requireBackgroundSafe(workflow).isSuccess)
    }

    @Test
    fun `store instances do not lose concurrent workflow updates`() {
        val file = File(createTempDirectory("workflow-store").toFile(), "saved_workflows.json")
        val firstStore = SavedWorkflowStore(file)
        val secondStore = SavedWorkflowStore(file)
        val executor = Executors.newFixedThreadPool(8)
        try {
            repeat(40) { index ->
                executor.submit {
                    val store = if (index % 2 == 0) firstStore else secondStore
                    store.upsert(
                        SavedWorkflowDefinition(
                            id = "concurrent-$index",
                            name = "workflow $index",
                            template = SavedWorkflowTemplate.PROJECT_READ_DIAGNOSTIC,
                            projectPath = "/project/$index"
                        )
                    )
                }
            }
        } finally {
            executor.shutdown()
            assertTrue(executor.awaitTermination(20, TimeUnit.SECONDS))
        }
        assertEquals(40, firstStore.list().size)
        assertTrue(file.readText().startsWith("["))
    }
}
