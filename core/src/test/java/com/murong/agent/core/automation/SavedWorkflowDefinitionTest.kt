package com.murong.agent.core.automation

import com.murong.agent.core.config.ToolPermissionCategory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SavedWorkflowDefinitionTest {
    @Test
    fun `cycle cannot be saved`() {
        val workflow = SavedWorkflowDefinition(
            name = "cycle",
            template = SavedWorkflowTemplate.PROJECT_READ_DIAGNOSTIC,
            projectPath = "/storage/emulated/0/项目",
            nodes = listOf(
                SavedWorkflowNode("a", "A", dependsOn = listOf("b")),
                SavedWorkflowNode("b", "B", dependsOn = listOf("a"))
            )
        )
        assertFalse(workflow.validate().isValid)
    }

    @Test
    fun `read only directory summary may run in background`() {
        val workflow = SavedWorkflowDefinition(
            name = "summary",
            template = SavedWorkflowTemplate.DIRECTORY_CHANGE_SUMMARY,
            projectPath = "/storage/emulated/0/项目"
        )
        assertTrue(workflow.validate().isValid)
        assertEquals(SavedWorkflowBackgroundEligibility.ALLOWED_READ_ONLY, workflow.backgroundEligibility())
    }

    @Test
    fun `write workflow stays foreground only`() {
        val workflow = SavedWorkflowDefinition(
            name = "export",
            template = SavedWorkflowTemplate.SESSION_SUMMARY_EXPORT,
            nodes = listOf(
                SavedWorkflowNode("export", "导出", requiredPermission = ToolPermissionCategory.FILE_WRITE)
            )
        )
        assertEquals(
            SavedWorkflowBackgroundEligibility.NEEDS_FOREGROUND_CONFIRMATION,
            workflow.backgroundEligibility()
        )
    }

    @Test
    fun `fixed GitHub Actions status query is background read only`() {
        assertEquals(
            SavedWorkflowBackgroundEligibility.ALLOWED_READ_ONLY,
            SavedWorkflowDefinition(
                name = "actions",
                template = SavedWorkflowTemplate.GITHUB_ACTIONS_STATUS,
                githubRepository = "murong/example"
            ).backgroundEligibility()
        )
    }

    @Test
    fun `github status workflow requires an explicit repository target`() {
        assertFalse(
            SavedWorkflowDefinition(
                name = "actions",
                template = SavedWorkflowTemplate.GITHUB_ACTIONS_STATUS
            ).validate().isValid
        )
    }

    @Test
    fun `network template is blocked when persisted node permissions are tampered`() {
        val workflow = SavedWorkflowDefinition(
            name = "actions",
            template = SavedWorkflowTemplate.GITHUB_ACTIONS_STATUS,
            githubRepository = "murong/example",
            nodes = listOf(
                SavedWorkflowNode(
                    id = "query_github_actions",
                    label = "被篡改为只读的网络节点",
                    requiredPermission = ToolPermissionCategory.PROJECT_READ
                )
            )
        )

        assertEquals(
            SavedWorkflowBackgroundEligibility.NEEDS_FOREGROUND_CONFIRMATION,
            workflow.backgroundEligibility()
        )
    }
}
