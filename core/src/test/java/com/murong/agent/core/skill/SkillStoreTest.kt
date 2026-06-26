package com.murong.agent.core.skill

import com.murong.agent.core.config.GlobalSkill
import com.murong.agent.core.config.SkillRunAs
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SkillStoreTest {

    @Test
    fun list_filtersDisabledAndBlankSkills() {
        val store = CompositeSkillStore(
            globalSkillsProvider = {
                listOf(
                    GlobalSkill(
                        id = "global-kept",
                        title = "全局保留",
                        content = "可用 skill"
                    ),
                    GlobalSkill(
                        id = "global-disabled",
                        title = "全局停用",
                        content = "不可用 skill",
                        enabled = false
                    )
                )
            },
            projectSkillsProvider = {
                listOf(
                    GlobalSkill(
                        id = "project-kept",
                        title = "项目保留",
                        content = "可用项目 skill"
                    ),
                    GlobalSkill(
                        id = "project-blank",
                        title = "空正文",
                        content = "   "
                    )
                )
            }
        )

        val result = store.list()

        assertEquals(listOf("project-kept", "global-kept"), result.map { it.skill.id })
        assertEquals(listOf(SkillSource.PROJECT, SkillSource.GLOBAL), result.map { it.source })
    }

    @Test
    fun match_prefersProjectExactTitleBeforeGlobalPartialMatch() {
        val store = CompositeSkillStore(
            globalSkillsProvider = {
                listOf(
                    GlobalSkill(
                        id = "global-release-helper",
                        title = "发布检查助手",
                        description = "全局发布检查套路",
                        content = "全局检查"
                    )
                )
            },
            projectSkillsProvider = {
                listOf(
                    GlobalSkill(
                        id = "project-release",
                        title = "发布检查",
                        description = "项目内精确匹配",
                        content = "项目检查"
                    )
                )
            }
        )

        val result = store.match("发布检查")

        assertEquals("project-release", result.first().skill.id)
        assertEquals(SkillSource.PROJECT, result.first().source)
        assertTrue(result.any { it.skill.id == "global-release-helper" }.not())
    }

    @Test
    fun createGlobalSkill_persistsAndShowsUpInCatalog() = runBlocking {
        var globalSkills = emptyList<GlobalSkill>()
        val store = PersistedSkillStore(
            globalSkillsProvider = { globalSkills },
            projectSkillsProvider = { emptyList() },
            saveGlobalSkills = { globalSkills = it },
            saveProjectSkills = { error("should not save project skills") }
        )

        val saved = store.createGlobalSkill(
            SkillDraft(
                title = "全局修复",
                description = "通用修复流程",
                content = "先定位，再最小修复。",
                runAs = SkillRunAs.INLINE,
                allowedTools = listOf("file_read")
            )
        )

        assertEquals(1, saved.totalCount)
        assertEquals("全局修复", saved.savedSkill.title)
        assertEquals(listOf("全局修复"), store.list(SkillSource.GLOBAL).map { it.skill.title })
    }

    @Test
    fun createProjectSkill_persistsAndShowsUpInProjectCatalog() = runBlocking {
        var projectSkills = emptyList<GlobalSkill>()
        val store = PersistedSkillStore(
            globalSkillsProvider = { emptyList() },
            projectSkillsProvider = { projectSkills },
            saveGlobalSkills = { error("should not save global skills") },
            saveProjectSkills = { projectSkills = it }
        )

        val saved = store.createProjectSkill(
            SkillDraft(
                title = "项目发布流",
                content = "检查版本与回归点。",
                runAs = SkillRunAs.SUBAGENT
            )
        )

        assertEquals(1, saved.totalCount)
        assertEquals("项目发布流", saved.savedSkill.title)
        assertEquals(listOf("项目发布流"), store.list(SkillSource.PROJECT).map { it.skill.title })
    }
}
