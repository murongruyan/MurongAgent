package com.murong.agent.core.loop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContextSourceProviderTest {

    @Test
    fun manualProvider_onlyUsesExplicitlySelectedFilesWithinTheBudget() {
        val snapshot = DefaultContextSourceRegistry.snapshot(
            ContextSourceRequest(
                projectPath = "/storage/emulated/0/备份",
                selectedFiles = listOf(
                    FileMentionUi(
                        path = "/storage/emulated/0/备份/gt8pro/flash.sh",
                        displayPath = "gt8pro/flash.sh",
                        kind = FileMentionKind.SCRIPT
                    ),
                    FileMentionUi(
                        path = "/storage/emulated/0/备份/module.zip",
                        displayPath = "module.zip",
                        kind = FileMentionKind.ARCHIVE,
                        byteSize = 2_048
                    )
                ),
                maxItems = 1,
                maxCharacters = 300
            )
        )

        assertEquals(1, snapshot.items.size)
        assertEquals("gt8pro/flash.sh", snapshot.items.single().displayPath)
        assertEquals(300, snapshot.estimatedCharacters)
        assertTrue(snapshot.toAuditText().contains("文本摘录"))
    }

    @Test
    fun selectionSnapshot_keepsArchiveAsMetadataInsteadOfText() {
        val item = ManualMentionContextSourceProvider.collect(
            ContextSourceRequest(
                projectPath = null,
                selectedFiles = listOf(
                    FileMentionUi(
                        path = "/storage/emulated/0/备份/module.zip",
                        displayPath = "module.zip"
                    )
                )
            )
        ).single()

        assertEquals(FileMentionInclusionMode.METADATA, item.inclusionMode)
    }

    @Test
    fun selectionSnapshot_keepsDirectoryAsBoundedManifestInsteadOfARecursiveRead() {
        val item = ManualMentionContextSourceProvider.collect(
            ContextSourceRequest(
                projectPath = "/storage/emulated/0/备份",
                selectedFiles = listOf(
                    FileMentionUi(
                        path = "/storage/emulated/0/备份/gt8pro",
                        displayPath = "gt8pro",
                        kind = FileMentionKind.DIRECTORY
                    )
                )
            )
        ).single()

        assertEquals(FileMentionInclusionMode.DIRECTORY_MANIFEST, item.inclusionMode)
    }

    @Test
    fun manualSelection_hasPriorityOverBoundedProjectDocumentation() {
        val snapshot = DefaultContextSourceRegistry.snapshot(
            ContextSourceRequest(
                projectPath = "/storage/emulated/0/project",
                selectedFiles = listOf(
                    FileMentionUi(
                        path = "/storage/emulated/0/project/task.md",
                        displayPath = "task.md"
                    )
                ),
                projectDocumentation = listOf(
                    FileMentionUi(
                        path = "/storage/emulated/0/project/README.md",
                        displayPath = "README.md",
                        source = FileMentionSource.PROJECT_KNOWLEDGE
                    )
                ),
                maxItems = 1
            )
        )

        assertEquals(listOf("task.md"), snapshot.items.map(ContextItem::displayPath))
    }

    @Test
    fun projectDocumentation_isIncludedAndCanBeRebuiltAsPromptDescriptor() {
        val snapshot = DefaultContextSourceRegistry.snapshot(
            ContextSourceRequest(
                projectPath = "/storage/emulated/0/project",
                projectDocumentation = listOf(
                    FileMentionUi(
                        path = "/storage/emulated/0/project/README.md",
                        displayPath = "README.md",
                        source = FileMentionSource.PROJECT_KNOWLEDGE
                    )
                )
            )
        )

        assertEquals(FileMentionSource.PROJECT_KNOWLEDGE, snapshot.items.single().source)
        assertEquals("README.md", snapshot.toFileMentions().single().displayPath)
    }
}
