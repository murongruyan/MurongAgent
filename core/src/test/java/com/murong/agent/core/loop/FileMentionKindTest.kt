package com.murong.agent.core.loop

import kotlin.test.Test
import kotlin.test.assertEquals

class FileMentionKindTest {

    @Test
    fun backupArtifacts_areClassifiedWithoutReadingBinaryContent() {
        assertEquals(FileMentionKind.ARCHIVE, detectFileMentionKind("/storage/emulated/0/备份/module.zip"))
        assertEquals(FileMentionKind.BINARY, detectFileMentionKind("/storage/emulated/0/备份/gt8pro/boot.img"))
        assertEquals(FileMentionKind.SCRIPT, detectFileMentionKind("/storage/emulated/0/备份/gt8pro/flash.sh"))
    }

    @Test
    fun persistedKind_usesStoredValueOrSafelyFallsBackToPath() {
        assertEquals(
            FileMentionKind.DIRECTORY,
            restoreFileMentionKind("DIRECTORY", "/storage/emulated/0/备份")
        )
        assertEquals(
            FileMentionKind.ARCHIVE,
            restoreFileMentionKind("obsolete-kind", "/storage/emulated/0/备份/module.zip")
        )
    }

    @Test
    fun inclusionPolicy_keepsBinaryArtifactsAsMetadataAndScriptsAsTextExcerpt() {
        assertEquals(
            FileMentionInclusionMode.METADATA,
            defaultFileMentionInclusionMode(FileMentionKind.ARCHIVE)
        )
        assertEquals(
            FileMentionInclusionMode.METADATA,
            defaultFileMentionInclusionMode(FileMentionKind.BINARY)
        )
        assertEquals(
            FileMentionInclusionMode.TEXT_EXCERPT,
            defaultFileMentionInclusionMode(FileMentionKind.SCRIPT)
        )
    }

    @Test
    fun stableId_changesWhenTheFileVersionChanges() {
        val path = "/storage/emulated/0/备份/gt8pro/flash.sh"

        assertEquals(
            stableFileMentionId(path, 1000L),
            stableFileMentionId(path, 1000L)
        )
        kotlin.test.assertNotEquals(
            stableFileMentionId(path, 1000L),
            stableFileMentionId(path, 2000L)
        )
    }
}
