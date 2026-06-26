package com.murong.agent.core.tool

import com.murong.agent.core.config.GlobalSkill
import com.murong.agent.core.memory.MemoryScope
import com.murong.agent.core.memory.PersistedMemoryStore
import com.murong.agent.core.config.SkillRunAs
import com.murong.agent.core.skill.PersistedSkillStore
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GlobalConfigToolsTest {

    @Test
    fun createGlobalMemory_persistsIntoDurableStore() = runBlocking {
        val tempDir = Files.createTempDirectory("global-memory-tool-test")
        try {
            val store = PersistedMemoryStore(
                baseDir = tempDir.toFile(),
                globalMemoriesProvider = { emptyList() },
                projectMemoriesProvider = { emptyList() },
                currentProjectPathProvider = { null }
            )
            val tool = CreateGlobalMemoryTool(
                memoryStore = store
            )

            val result = tool.execute("""{"title":"全局约定","content":"先看发布说明。"}""")

            val saved = store.list(scope = MemoryScope.GLOBAL, limit = 10).singleOrNull()
            assertNotNull(saved)
            assertEquals("全局约定", saved.title)
            assertTrue(result.contains("已创建全局记忆"))
            assertTrue(result.contains("memory_id="))
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun createGlobalSkill_persistsSkillIntoGlobalStore() = runBlocking {
        var updatedGlobalSkills = emptyList<GlobalSkill>()
        val store = PersistedSkillStore(
            globalSkillsProvider = { updatedGlobalSkills },
            projectSkillsProvider = { emptyList() },
            saveGlobalSkills = { updatedGlobalSkills = it },
            saveProjectSkills = { error("should not save project skills") }
        )
        val tool = CreateGlobalSkillTool(
            skillStore = store
        )

        val result = tool.execute(
            """
            {
              "title": "全局发布检查",
              "description": "用于通用发布前核查",
              "content": "先核对版本与变更，再执行回归检查。",
              "runAs": "subagent",
              "allowedTools": ["read", "web_search", "exists"],
              "preferredModel": "gpt-5.5-pro"
            }
            """.trimIndent()
        )

        assertEquals(1, updatedGlobalSkills.size)
        val saved = updatedGlobalSkills.single()
        assertEquals("全局发布检查", saved.title)
        assertEquals("用于通用发布前核查", saved.description)
        assertEquals("先核对版本与变更，再执行回归检查。", saved.content)
        assertEquals(SkillRunAs.SUBAGENT, saved.runAs)
        assertEquals(listOf("read", "web_search", "exists"), saved.allowedTools)
        assertEquals("gpt-5.5-pro", saved.preferredModel)
        assertTrue(saved.enabled)
        assertTrue(result.contains("已导入全局 Skill"))
        assertTrue(result.contains("当前共 1 条"))
    }
}
