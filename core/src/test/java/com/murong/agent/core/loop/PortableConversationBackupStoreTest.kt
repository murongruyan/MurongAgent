package com.murong.agent.core.loop

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PortableConversationBackupStoreTest {
    @Test
    fun exportAndMerge_preservesExistingAndCreatesStableConflictCopy() {
        val sourceDir = Files.createTempDirectory("portable-backup-source").toFile()
        val targetDir = Files.createTempDirectory("portable-backup-target").toFile()
        val mergedDir = Files.createTempDirectory("portable-backup-merged").toFile()
        try {
            val sourceStore = ConversationStore(sourceDir)
            assertTrue(sourceStore.saveSession(session("source-1", "电脑任务", "来自电脑", 20)))
            val records = PortableConversationBackupStore.forDirectory(sourceDir).exportAll()
            assertEquals(1, records.size)

            val targetStore = ConversationStore(targetDir)
            assertTrue(targetStore.saveSession(session("local", "手机任务", "本地保留", 30)))
            val bridge = PortableConversationBackupStore.forDirectory(targetDir)
            val first = bridge.prepareMergedDirectory("android", records, mergedDir)
            assertEquals(1, first.importedSessions)
            assertEquals(0, first.conflictCopies)
            assertEquals(2, ConversationStore(mergedDir).listSessions().size)

            val importedStore = ConversationStore(mergedDir)
            val imported = importedStore.listSessions().first { it.id != "local" }
            assertTrue(
                importedStore.saveSession(
                    importedStore.loadSession(imported.id)!!.copy(
                        updatedAt = 40,
                        messages = listOf(PersistedMessage(1, "user", "手机继续编辑", timestamp = 40))
                    )
                )
            )
            val secondDir = File(targetDir.parentFile, "portable-backup-second-${System.nanoTime()}")
            val second = PortableConversationBackupStore.forDirectory(mergedDir)
                .prepareMergedDirectory("android", records, secondDir)
            assertEquals(1, second.importedSessions)
            assertEquals(1, second.conflictCopies)
            assertEquals(3, ConversationStore(secondDir).listSessions().size)

            val thirdDir = File(targetDir.parentFile, "portable-backup-third-${System.nanoTime()}")
            val third = PortableConversationBackupStore.forDirectory(secondDir)
                .prepareMergedDirectory("android", records, thirdDir)
            assertEquals(0, third.importedSessions)
            assertEquals(0, third.conflictCopies)
            assertEquals(1, third.skippedSessions)
            assertEquals(3, ConversationStore(thirdDir).listSessions().size)
            secondDir.deleteRecursively()
            thirdDir.deleteRecursively()
        } finally {
            sourceDir.deleteRecursively()
            targetDir.deleteRecursively()
            mergedDir.deleteRecursively()
        }
    }

    @Test
    fun merge_rejectsSourceMismatchAndDoesNotCarryAndroidOnlyFields() {
        val sourceDir = Files.createTempDirectory("portable-backup-private").toFile()
        val targetDir = Files.createTempDirectory("portable-backup-private-target").toFile()
        try {
            val source = session("private", "私有任务", "文本", 2).copy(
                projectPath = "/storage/emulated/0/secret-project",
                codexThreadId = "private-thread",
                pendingApproval = PersistedPendingApproval(
                    toolName = "shell",
                    summary = "secret",
                    detail = "secret command",
                    rawArgs = "TOKEN=plaintext",
                    riskLevel = "HIGH"
                )
            )
            assertTrue(ConversationStore(sourceDir).saveSession(source))
            val records = PortableConversationBackupStore.forDirectory(sourceDir).exportAll()
            val portable = records.single().portableJson
            assertFalse(portable.contains("secret-project"))
            assertFalse(portable.contains("private-thread"))
            assertFalse(portable.contains("TOKEN=plaintext"))
            assertFailsWith<IllegalArgumentException> {
                PortableConversationBackupStore.forDirectory(targetDir)
                    .prepareMergedDirectory("windows", records, File(targetDir, "merged"))
            }
        } finally {
            sourceDir.deleteRecursively()
            targetDir.deleteRecursively()
        }
    }

    private fun session(id: String, title: String, content: String, updatedAt: Long) = PersistedSession(
        id = id,
        title = title,
        createdAt = 1,
        updatedAt = updatedAt,
        providerId = "openai-compatible",
        modelName = "test-model",
        messages = listOf(PersistedMessage(1, "user", content, timestamp = updatedAt))
    )
}
