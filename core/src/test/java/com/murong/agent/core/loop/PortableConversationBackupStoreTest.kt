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
    fun mergeIntoCurrentStore_atomicallyAddsSessionsAndSkipsIdenticalRepeat() {
        val sourceDir = Files.createTempDirectory("portable-live-source").toFile()
        val targetDir = Files.createTempDirectory("portable-live-target").toFile()
        try {
            assertTrue(ConversationStore(sourceDir).saveSession(session("desktop-1", "电脑任务", "来自电脑", 20)))
            val records = PortableConversationBackupStore.forDirectory(sourceDir).exportAll()
            assertTrue(ConversationStore(targetDir).saveSession(session("local", "手机任务", "本地保留", 30)))
            val bridge = PortableConversationBackupStore.forDirectory(targetDir)

            val first = bridge.mergeIntoCurrentStore("android", records)
            assertEquals(1, first.importedSessions)
            assertEquals(0, first.conflictCopies)
            assertEquals(0, first.skippedSessions)
            assertEquals(2, ConversationStore(targetDir).listSessions().size)
            assertTrue(ConversationStore(targetDir).loadSession("local") != null)

            val repeated = PortableConversationBackupStore.forDirectory(targetDir)
                .mergeIntoCurrentStore("android", records)
            assertEquals(0, repeated.importedSessions)
            assertEquals(0, repeated.conflictCopies)
            assertEquals(1, repeated.skippedSessions)
            assertEquals(2, ConversationStore(targetDir).listSessions().size)
        } finally {
            sourceDir.deleteRecursively()
            targetDir.deleteRecursively()
        }
    }

    @Test
    fun mergeIntoCurrentStore_invalidInputLeavesLiveStoreUntouched() {
        val sourceDir = Files.createTempDirectory("portable-live-invalid-source").toFile()
        val targetDir = Files.createTempDirectory("portable-live-invalid-target").toFile()
        try {
            assertTrue(ConversationStore(sourceDir).saveSession(session("desktop-1", "电脑任务", "来自电脑", 20)))
            val records = PortableConversationBackupStore.forDirectory(sourceDir).exportAll()
            assertTrue(ConversationStore(targetDir).saveSession(session("local", "手机任务", "本地保留", 30)))

            assertFailsWith<IllegalArgumentException> {
                PortableConversationBackupStore.forDirectory(targetDir)
                    .mergeIntoCurrentStore("windows", records)
            }
            val sessions = ConversationStore(targetDir).listSessions()
            assertEquals(1, sessions.size)
            assertEquals("local", sessions.single().id)
        } finally {
            sourceDir.deleteRecursively()
            targetDir.deleteRecursively()
        }
    }

    @Test
    fun mergeIntoCurrentStore_doesNotEchoOriginalAndroidSessionBackAsDuplicate() {
        val sourceDir = Files.createTempDirectory("portable-echo-source").toFile()
        val targetDir = Files.createTempDirectory("portable-echo-target").toFile()
        try {
            val original = session("android-origin", "原始手机任务", "相同内容", 20)
            assertTrue(ConversationStore(sourceDir).saveSession(original))
            assertTrue(ConversationStore(targetDir).saveSession(original))
            val androidRecord = PortableConversationBackupStore.forDirectory(sourceDir).exportAll().single()
            val windowsDocument = androidRecord.portableJson.replace(
                "\"sourcePlatform\":\"android\"",
                "\"sourcePlatform\":\"windows\""
            )
            assertTrue(windowsDocument.contains("\"sourcePlatform\":\"windows\""))
            val echoed = androidRecord.copy(
                sourceSessionId = "portable-windows-copy",
                portableJson = windowsDocument,
                originPlatform = "android",
                originSessionId = original.id,
            )

            val result = PortableConversationBackupStore.forDirectory(targetDir)
                .mergeIntoCurrentStore("windows", listOf(echoed))
            assertEquals(0, result.importedSessions)
            assertEquals(0, result.conflictCopies)
            assertEquals(1, result.skippedSessions)
            assertEquals(listOf(original.id), ConversationStore(targetDir).listSessions().map { it.id })
        } finally {
            sourceDir.deleteRecursively()
            targetDir.deleteRecursively()
        }
    }

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
            val reExported = PortableConversationBackupStore.forDirectory(mergedDir)
                .exportAll()
                .first { it.sourceSessionId == imported.id }
            assertEquals("android", reExported.originPlatform)
            assertEquals("source-1", reExported.originSessionId)
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
