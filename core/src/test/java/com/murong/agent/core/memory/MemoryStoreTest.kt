package com.murong.agent.core.memory

import com.murong.agent.core.config.GlobalMemory
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MemoryStoreTest {

    @Test
    fun list_includesLegacyGlobalAndProjectMemories() {
        val tempDir = Files.createTempDirectory("memory-store-test")
        try {
            val store = PersistedMemoryStore(
                baseDir = tempDir.toFile(),
                globalMemoriesProvider = {
                    listOf(
                        GlobalMemory(
                            id = "global-release",
                            title = "发布约定",
                            content = "发布前需要核对版本号和 changelog。"
                        )
                    )
                },
                projectMemoriesProvider = {
                    listOf(
                        GlobalMemory(
                            id = "project-build",
                            title = "构建约束",
                            content = "当前项目必须先跑 :app:compileDebugKotlin。"
                        )
                    )
                },
                currentProjectPathProvider = { "C:/workspace/app" }
            )

            val records = store.list()

            assertEquals(2, records.size)
            assertEquals("构建约束", records.first().title)
            assertEquals(MemoryScope.PROJECT, records.first().scope)
            assertEquals(MemoryOrigin.LEGACY_CONFIG, records.first().origin)
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun createGlobalMemory_persistsDurableMemoryAndSearchesIt() = runBlocking {
        val tempDir = Files.createTempDirectory("memory-store-global-test")
        try {
            val store = PersistedMemoryStore(
                baseDir = tempDir.toFile(),
                globalMemoriesProvider = { emptyList() },
                projectMemoriesProvider = { emptyList() },
                currentProjectPathProvider = { null }
            )

            val saved = store.createGlobalMemory(
                MemoryDraft(
                    title = "登录事实",
                    content = "登录异常时优先检查 token 刷新和设备时间漂移。"
                )
            )

            assertEquals("登录事实", saved.savedMemory.title)
            assertEquals(1, saved.totalCount)
            val hit = store.search("token 刷新").firstOrNull()
            assertNotNull(hit)
            assertEquals("登录事实", hit.memory.title)
            assertTrue(hit.snippet.contains("token"))
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun createProjectMemory_persistsDurableMemoryIntoProjectScope() = runBlocking {
        val tempDir = Files.createTempDirectory("memory-store-project-test")
        try {
            val store = PersistedMemoryStore(
                baseDir = tempDir.toFile(),
                globalMemoriesProvider = { emptyList() },
                projectMemoriesProvider = { emptyList() },
                currentProjectPathProvider = { "C:/workspace/app" }
            )

            val saved = store.createProjectMemory(
                MemoryDraft(
                    title = "项目发布检查",
                    content = "发版前还要检查埋点开关是否关闭。"
                )
            )

            assertEquals(MemoryScope.PROJECT, saved.savedMemory.scope)
            val record = store.read(saved.savedMemory.id, scope = MemoryScope.PROJECT)
            assertNotNull(record)
            assertEquals("项目发布检查", record.title)
            assertEquals("C:/workspace/app", record.scopePath)
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun delete_removesDurableMemoryButKeepsLegacyConfigReadonly() = runBlocking {
        val tempDir = Files.createTempDirectory("memory-store-delete-test")
        try {
            val store = PersistedMemoryStore(
                baseDir = tempDir.toFile(),
                globalMemoriesProvider = {
                    listOf(
                        GlobalMemory(
                            id = "legacy-release",
                            title = "旧发布约定",
                            content = "legacy memory"
                        )
                    )
                },
                projectMemoriesProvider = { emptyList() },
                currentProjectPathProvider = { null }
            )
            val saved = store.createGlobalMemory(
                MemoryDraft(
                    title = "新发布事实",
                    content = "durable memory"
                )
            )

            val deleted = store.delete(saved.savedMemory.id, scope = MemoryScope.GLOBAL)
            assertTrue(deleted is MemoryDeleteResult.Deleted)
            assertEquals(null, store.read(saved.savedMemory.id, scope = MemoryScope.GLOBAL))

            val legacyDelete = store.delete("legacy-global-legacy-release", scope = MemoryScope.GLOBAL)
            assertTrue(legacyDelete is MemoryDeleteResult.LegacyConfigMemory)
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun list_prefersDurableMemoryWhenLegacyAndDurableContentMatch() = runBlocking {
        val tempDir = Files.createTempDirectory("memory-store-dedup-test")
        try {
            val store = PersistedMemoryStore(
                baseDir = tempDir.toFile(),
                globalMemoriesProvider = {
                    listOf(
                        GlobalMemory(
                            id = "legacy-release",
                            title = "发布约定",
                            content = "先核对版本号，再检查 changelog。"
                        )
                    )
                },
                projectMemoriesProvider = { emptyList() },
                currentProjectPathProvider = { null }
            )
            store.createGlobalMemory(
                MemoryDraft(
                    title = "发布约定",
                    content = "先核对版本号，再检查 changelog。"
                )
            )

            val records = store.list(scope = MemoryScope.GLOBAL, limit = 10)

            assertEquals(1, records.size)
            assertEquals(MemoryOrigin.DURABLE, records.single().origin)
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun search_prefersTitlePhraseMatchOverContentOnlyMatch() = runBlocking {
        val tempDir = Files.createTempDirectory("memory-store-search-rank-title-test")
        try {
            val store = PersistedMemoryStore(
                baseDir = tempDir.toFile(),
                globalMemoriesProvider = { emptyList() },
                projectMemoriesProvider = { emptyList() },
                currentProjectPathProvider = { null }
            )
            store.createGlobalMemory(
                MemoryDraft(
                    title = "token 刷新排查",
                    content = "先看登录链路。"
                )
            )
            store.createGlobalMemory(
                MemoryDraft(
                    title = "登录排查",
                    content = "登录失败时先检查 token 刷新，再看设备时间。"
                )
            )

            val hits = store.search("token 刷新", scope = MemoryScope.GLOBAL, limit = 5)

            assertEquals(2, hits.size)
            assertEquals("token 刷新排查", hits.first().memory.title)
            assertTrue(hits.first().score > hits.last().score)
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun search_prefersHigherTokenCoverageWhenPhraseIsNotExact() = runBlocking {
        val tempDir = Files.createTempDirectory("memory-store-search-rank-coverage-test")
        try {
            val store = PersistedMemoryStore(
                baseDir = tempDir.toFile(),
                globalMemoriesProvider = { emptyList() },
                projectMemoriesProvider = { emptyList() },
                currentProjectPathProvider = { null }
            )
            store.createGlobalMemory(
                MemoryDraft(
                    title = "登录排查",
                    content = "排查时先看 token 刷新，再确认设备时间是否漂移。"
                )
            )
            store.createGlobalMemory(
                MemoryDraft(
                    title = "登录排查",
                    content = "排查时只先看 token。"
                )
            )

            val hits = store.search("token 刷新 设备时间", scope = MemoryScope.GLOBAL, limit = 5)

            assertEquals(2, hits.size)
            assertTrue(hits.first().memory.content.contains("设备时间"))
            assertTrue(hits.first().score > hits.last().score)
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun migrateLegacy_promotesVisibleLegacyMemoriesIntoDurableStore() = runBlocking {
        val tempDir = Files.createTempDirectory("memory-store-migrate-legacy-test")
        try {
            val store = PersistedMemoryStore(
                baseDir = tempDir.toFile(),
                globalMemoriesProvider = {
                    listOf(
                        GlobalMemory(
                            id = "legacy-release",
                            title = "发布约定",
                            content = "先核对版本号，再检查 changelog。"
                        )
                    )
                },
                projectMemoriesProvider = {
                    listOf(
                        GlobalMemory(
                            id = "legacy-build",
                            title = "构建约束",
                            content = "先跑 :app:compileDebugKotlin。"
                        )
                    )
                },
                currentProjectPathProvider = { "C:/workspace/app" }
            )

            val migrated = store.migrateLegacy(scope = MemoryScope.ANY)
            val records = store.list(scope = MemoryScope.ANY, limit = 10)

            assertEquals(2, migrated.migratedMemories.size)
            assertTrue(migrated.migratedMemories.all { it.origin == MemoryOrigin.DURABLE })
            assertEquals(2, records.size)
            assertTrue(records.all { it.origin == MemoryOrigin.DURABLE })
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }
}
