package com.murong.agent.core.loop

import com.murong.agent.core.tool.SkillToolPayload
import com.murong.agent.core.tool.ToolStructuredPayload
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SkillUsageClueTest {

    @Test
    fun buildRecentSkillUsageClue_collectsRecentSuccessfulSkillPayloads() {
        val clue = buildRecentSkillUsageClue(
            recentToolCalls = listOf(
                ToolCallRecordUi(
                    toolName = "run_skill",
                    args = """{"skill":"发布检查"}""",
                    isSuccess = true,
                    structuredPayload = ToolStructuredPayload(
                        skill = SkillToolPayload(
                            kind = "execution",
                            query = "发布检查",
                            skillTitle = "发布检查",
                            task = "核对发布前 checklist",
                            runAs = "inline"
                        )
                    )
                ),
                ToolCallRecordUi(
                    toolName = "run_skill",
                    args = """{"skill":"项目审查"}""",
                    isSuccess = true,
                    structuredPayload = ToolStructuredPayload(
                        skill = SkillToolPayload(
                            kind = "execution",
                            query = "项目审查",
                            skillTitle = "项目审查",
                            task = "审查配置合并逻辑",
                            runAs = "subagent",
                            delegatedToSubagent = true
                        )
                    )
                ),
                ToolCallRecordUi(
                    toolName = "run_skill",
                    args = """{"skill":"失败案例"}""",
                    isSuccess = false,
                    structuredPayload = ToolStructuredPayload(
                        skill = SkillToolPayload(
                            kind = "execution",
                            skillTitle = "失败案例",
                            task = "不应进入聚合"
                        )
                    )
                )
            )
        )

        assertNotNull(clue)
        assertEquals(listOf("发布检查", "项目审查"), clue.skillTitles)
        assertEquals(listOf("发布检查", "项目审查"), clue.queries)
        assertEquals(listOf("核对发布前 checklist", "审查配置合并逻辑"), clue.tasks)
        assertEquals(listOf("inline", "subagent"), clue.runModes)
        assertEquals(listOf("项目审查"), clue.delegatedSkillTitles)
    }

    @Test
    fun buildSkillUsageContext_formatsReusableHint() {
        val context = buildSkillUsageContext(
            SkillUsageClueUi(
                skillTitles = listOf("发布检查", "项目审查"),
                tasks = listOf("核对发布前 checklist"),
                runModes = listOf("inline", "subagent"),
                delegatedSkillTitles = listOf("项目审查"),
                queries = listOf("发布检查")
            )
        )

        assertNotNull(context)
        assertTrue(context.contains("最近已调用过 Skill 能力"))
        assertTrue(context.contains("最近 Skill"))
        assertTrue(context.contains("执行模式"))
        assertTrue(context.contains("已委派子代理"))
        assertTrue(context.contains("优先复用这些 Skill"))
    }
}
