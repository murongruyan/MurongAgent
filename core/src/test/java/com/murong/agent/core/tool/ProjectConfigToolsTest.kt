package com.murong.agent.core.tool

import com.murong.agent.core.config.GlobalSkill
import com.murong.agent.core.config.SkillRunAs
import com.murong.agent.core.memory.MemoryScope
import com.murong.agent.core.memory.PersistedMemoryStore
import com.murong.agent.core.skill.PersistedSkillStore
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProjectConfigToolsTest {

    @Test
    fun createProjectMemory_persistsIntoCurrentProjectDurableStore() = runBlocking {
        val tempDir = Files.createTempDirectory("project-memory-tool-test")
        try {
            val store = PersistedMemoryStore(
                baseDir = tempDir.toFile(),
                globalMemoriesProvider = { emptyList() },
                projectMemoriesProvider = { emptyList() },
                currentProjectPathProvider = { "C:/workspace/app" }
            )
            val tool = CreateProjectMemoryTool(
                scopePathProvider = { "C:/workspace/app" },
                scopeLabelProvider = { "当前项目" },
                memoryStore = store
            )

            val result = tool.execute("""{"title":"项目约束","content":"先跑 compileDebugKotlin。"}""")

            val saved = store.list(scope = MemoryScope.PROJECT, limit = 10).singleOrNull()
            assertNotNull(saved)
            assertEquals("项目约束", saved.title)
            assertTrue(result.contains("已创建项目记忆"))
            assertTrue(result.contains("memory_id:"))
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun createProjectSkill_persistsSkillIntoCurrentProjectScope() = runBlocking {
        var updatedSkills = emptyList<GlobalSkill>()
        val store = PersistedSkillStore(
            globalSkillsProvider = { emptyList() },
            projectSkillsProvider = { updatedSkills },
            saveGlobalSkills = { error("should not save global skills") },
            saveProjectSkills = { updatedSkills = it }
        )
        val tool = CreateProjectSkillTool(
            scopePathProvider = { "C:/workspace/app" },
            scopeLabelProvider = { "当前项目" },
            skillStore = store
        )

        val result = tool.execute(
            """
            {
              "title": "发布检查流",
              "description": "发布前核对与验证",
              "content": "先检查变更，再跑验证，再整理发布说明。",
              "runAs": "subagent",
              "allowedTools": ["read", "web_search", "read", "exists"],
              "preferredModel": "gpt-5.5-pro"
            }
            """.trimIndent()
        )

        assertEquals(1, updatedSkills.size)
        val saved = updatedSkills.single()
        assertEquals("发布检查流", saved.title)
        assertEquals("发布前核对与验证", saved.description)
        assertEquals("先检查变更，再跑验证，再整理发布说明。", saved.content)
        assertEquals(SkillRunAs.SUBAGENT, saved.runAs)
        assertEquals(listOf("file_read", "web_search", "file_exists"), saved.allowedTools)
        assertEquals("gpt-5.5-pro", saved.preferredModel)
        assertTrue(saved.enabled)
        assertTrue(result.contains("已创建项目 Skill"))
        assertTrue(result.contains("当前项目 Skill 数: 1"))
    }

    @Test
    fun createProjectSkill_requiresActiveProjectScope() = runBlocking {
        val store = PersistedSkillStore(
            globalSkillsProvider = { emptyList() },
            projectSkillsProvider = { emptyList() },
            saveGlobalSkills = { error("should not save global skills") },
            saveProjectSkills = { error("should not save project skills without scope") }
        )
        val tool = CreateProjectSkillTool(
            scopePathProvider = { null },
            scopeLabelProvider = { "当前项目" },
            skillStore = store
        )

        val result = tool.execute("""{"title":"发布检查流","content":"内容"}""")

        assertTrue(result.contains("当前没有激活的本地项目作用域"))
    }
}
