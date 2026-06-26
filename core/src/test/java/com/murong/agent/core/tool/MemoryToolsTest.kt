package com.murong.agent.core.tool

import com.murong.agent.core.config.GlobalMemory
import com.murong.agent.core.memory.MemoryScope
import com.murong.agent.core.memory.PersistedMemoryStore
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MemoryToolsTest {

    @Test
    fun memoryList_listsProjectAndGlobalMemories() = runBlocking {
        val tempDir = Files.createTempDirectory("memory-tool-list-test")
        try {
            val store = PersistedMemoryStore(
                baseDir = tempDir.toFile(),
                globalMemoriesProvider = {
                    listOf(
                        GlobalMemory(
                            id = "global-release",
                            title = "发布约定",
                            content = "发布前要核对 changelog。"
                        )
                    )
                },
                projectMemoriesProvider = {
                    listOf(
                        GlobalMemory(
                            id = "project-build",
                            title = "构建约束",
                            content = "先跑 :app:compileDebugKotlin。"
                        )
                    )
                },
                currentProjectPathProvider = { "C:/workspace/app" }
            )

            val result = MemoryListTool(store).execute("""{"scope":"any","limit":10}""")

            assertTrue(result.contains("[project] 构建约束"))
            assertTrue(result.contains("[global] 发布约定"))
            assertTrue(result.contains("memory_read"))
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun memorySearch_and_memoryRead_returnDurableMemory() = runBlocking {
        val tempDir = Files.createTempDirectory("memory-tool-read-test")
        try {
            val store = PersistedMemoryStore(
                baseDir = tempDir.toFile(),
                globalMemoriesProvider = { emptyList() },
                projectMemoriesProvider = { emptyList() },
                currentProjectPathProvider = { "C:/workspace/app" }
            )
            val saved = store.createProjectMemory(
                com.murong.agent.core.memory.MemoryDraft(
                    title = "登录排查",
                    content = "登录失败时先看 token 刷新，再看设备时间。"
                )
            )

            val searchResult = MemorySearchTool(store).execute("""{"query":"token 刷新","scope":"project"}""")
            val readResult = MemoryReadTool(store).execute(
                """{"memory_id":"${saved.savedMemory.id}","scope":"project"}"""
            )

            assertTrue(searchResult.contains("记忆命中 1 条"))
            assertTrue(searchResult.contains("登录排查"))
            assertTrue(readResult.contains("标题: 登录排查"))
            assertTrue(readResult.contains("范围: ${MemoryScope.PROJECT.wireName()}"))
            assertTrue(readResult.contains("token 刷新"))
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun forgetMemory_deletesDurableMemoryById() = runBlocking {
        val tempDir = Files.createTempDirectory("memory-tool-forget-test")
        try {
            val store = PersistedMemoryStore(
                baseDir = tempDir.toFile(),
                globalMemoriesProvider = { emptyList() },
                projectMemoriesProvider = { emptyList() },
                currentProjectPathProvider = { "C:/workspace/app" }
            )
            val saved = store.createProjectMemory(
                com.murong.agent.core.memory.MemoryDraft(
                    title = "待删除记忆",
                    content = "发版后清理临时开关。"
                )
            )

            val result = ForgetMemoryTool(store).execute(
                """{"memory_id":"${saved.savedMemory.id}","scope":"project"}"""
            )

            assertTrue(result.contains("已删除记忆"))
            assertTrue(result.contains(saved.savedMemory.id))
            assertTrue(store.read(saved.savedMemory.id, scope = MemoryScope.PROJECT) == null)
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun rememberMemory_defaultsToProjectScopeWhenProjectIsActive() = runBlocking {
        val tempDir = Files.createTempDirectory("memory-tool-remember-project-test")
        try {
            val store = PersistedMemoryStore(
                baseDir = tempDir.toFile(),
                globalMemoriesProvider = { emptyList() },
                projectMemoriesProvider = { emptyList() },
                currentProjectPathProvider = { "C:/workspace/app" }
            )

            val result = RememberMemoryTool(
                memoryStore = store,
                currentProjectScopePathProvider = { "C:/workspace/app" }
            ).execute("""{"title":"项目事实","content":"默认写入当前项目作用域。"}""")

            assertTrue(result.contains("已记住一条事实"))
            assertTrue(result.contains("范围: project"))
            assertTrue(store.list(scope = MemoryScope.PROJECT, limit = 10).any { it.title == "项目事实" })
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun rememberMemory_promotesLegacyMemoryIntoDurableStore() = runBlocking {
        val tempDir = Files.createTempDirectory("memory-tool-remember-migrate-test")
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

            val result = RememberMemoryTool(
                memoryStore = store,
                currentProjectScopePathProvider = { null }
            ).execute("""{"source_memory_id":"legacy-global-legacy-release","scope":"global"}""")

            assertTrue(result.contains("已将记忆写入 durable store"))
            val records = store.list(scope = MemoryScope.GLOBAL, limit = 10)
            assertTrue(records.size == 1)
            assertTrue(records.single().origin.name == "DURABLE")
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun rememberMemory_acceptsPendingSuggestionBySuggestionId() = runBlocking {
        val tempDir = Files.createTempDirectory("memory-tool-remember-suggestion-test")
        try {
            val store = PersistedMemoryStore(
                baseDir = tempDir.toFile(),
                globalMemoriesProvider = { emptyList() },
                projectMemoriesProvider = { emptyList() },
                currentProjectPathProvider = { "C:/workspace/app" }
            )
            var appliedSuggestionId: String? = null
            var linkedMemoryId: String? = null
            val tool = RememberMemoryTool(
                memoryStore = store,
                currentProjectScopePathProvider = { "C:/workspace/app" },
                suggestionProvider = { suggestionId ->
                    if (suggestionId == "mem-suggest-1") {
                        RememberMemorySuggestion(
                            id = suggestionId,
                            title = "建议沉淀",
                            content = "本轮确认了项目构建顺序。",
                            scope = MemoryScope.PROJECT
                        )
                    } else {
                        null
                    }
                },
                onSuggestionApplied = { suggestionId, memoryId ->
                    appliedSuggestionId = suggestionId
                    linkedMemoryId = memoryId
                }
            )

            val result = tool.execute("""{"suggestion_id":"mem-suggest-1"}""")

            assertTrue(result.contains("已按建议写入 durable memory"))
            assertEquals("mem-suggest-1", appliedSuggestionId)
            assertTrue(!linkedMemoryId.isNullOrBlank())
            assertTrue(store.list(scope = MemoryScope.PROJECT, limit = 10).any { it.title == "建议沉淀" })
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun migrateLegacyMemories_promotesVisibleLegacyMemories() = runBlocking {
        val tempDir = Files.createTempDirectory("memory-tool-migrate-legacy-test")
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

            val result = MigrateLegacyMemoriesTool(
                memoryStore = store,
                currentProjectScopePathProvider = { null }
            ).execute("""{"scope":"global"}""")

            assertTrue(result.contains("已完成 legacy config 记忆迁移"))
            assertTrue(result.contains("迁移条数: 1"))
            assertTrue(store.list(scope = MemoryScope.GLOBAL, limit = 10).all { it.origin.name == "DURABLE" })
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun memoryRead_legacyMemoryShowsMigrationHint() = runBlocking {
        val tempDir = Files.createTempDirectory("memory-tool-read-legacy-hint-test")
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

            val result = MemoryReadTool(store).execute(
                """{"memory_id":"legacy-global-legacy-release","scope":"global"}"""
            )

            assertTrue(result.contains("来源: legacy_config"))
            assertTrue(result.contains("迁移建议"))
            assertTrue(result.contains("migrate_legacy_memories"))
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }
}
