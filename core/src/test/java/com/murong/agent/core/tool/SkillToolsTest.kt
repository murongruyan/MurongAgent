package com.murong.agent.core.tool

import com.murong.agent.core.config.GlobalSkill
import com.murong.agent.core.config.SkillRunAs
import com.murong.agent.core.skill.CompositeSkillStore
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SkillToolsTest {

    @Test
    fun readSkill_listsEnabledProjectAndGlobalSkills() = runBlocking {
        val tool = ReadSkillTool(
            skillStore = CompositeSkillStore(
            globalSkillsProvider = {
                listOf(
                    GlobalSkill(
                        id = "global-review",
                        title = "全局审查",
                        description = "通用代码审查流程",
                        content = "先读实现，再列风险。"
                    )
                )
            },
            projectSkillsProvider = {
                listOf(
                    GlobalSkill(
                        id = "project-release",
                        title = "发布检查",
                        description = "项目发布前核查",
                        content = "核对版本、变更和回归点。"
                    )
                )
            }
            )
        )

        val result = tool.executeWithResult("{}")

        assertTrue(result.output.contains("[project] 发布检查"))
        assertTrue(result.output.contains("[global] 全局审查"))
        assertTrue(result.output.contains("可继续用 `read_skill`"))
        assertEquals("catalog", result.structuredPayload?.skill?.kind)
        assertEquals(listOf("project-release", "global-review"), result.structuredPayload?.skill?.matchedSkillIds)
    }

    @Test
    fun runSkill_inlineReturnsSkillInstructionForCurrentTurn() = runBlocking {
        val tool = RunSkillTool(
            skillStore = CompositeSkillStore(
            globalSkillsProvider = {
                listOf(
                    GlobalSkill(
                        id = "inline-fix",
                        title = "修复助手",
                        description = "修 bug 时的执行要求",
                        content = "先定位根因，再给最小修复。",
                        runAs = SkillRunAs.INLINE,
                        allowedTools = listOf("code_edit", "file")
                    )
                )
            },
            projectSkillsProvider = { emptyList() }
            )
        )

        val result = tool.executeWithResult(
            """
            {
              "skill": "修复助手",
              "task": "修复登录页的空指针"
            }
            """.trimIndent()
        )

        assertTrue(result.output.contains("已激活 Skill：修复助手"))
        assertTrue(result.output.contains("Execution Mode: inline"))
        assertTrue(result.output.contains("Allowed Tools: code_edit, file"))
        assertTrue(result.output.contains("修复登录页的空指针"))
        assertTrue(result.output.contains("先定位根因，再给最小修复。"))
        assertEquals("execution", result.structuredPayload?.skill?.kind)
        assertEquals("inline", result.structuredPayload?.skill?.runAs)
        assertEquals("修复登录页的空指针", result.structuredPayload?.skill?.task)
        assertEquals(listOf("code_edit", "file"), result.structuredPayload?.skill?.allowedTools)
    }

    @Test
    fun runSkill_subagentDelegatesToSubagentExecutor() = runBlocking {
        var forwardedArgs = ""
        val tool = RunSkillTool(
            skillStore = CompositeSkillStore(
                globalSkillsProvider = { emptyList() },
                projectSkillsProvider = {
                    listOf(
                        GlobalSkill(
                            id = "project-review",
                            title = "项目审查",
                            description = "把当前任务交给子代理审查",
                            content = "聚焦 bug 和回归风险。",
                            runAs = SkillRunAs.SUBAGENT,
                            allowedTools = listOf("web", "shell"),
                            preferredModel = "gpt-5.5-pro"
                        )
                    )
                }
            ),
            subagentExecutor = { args ->
                forwardedArgs = args
                ToolExecutionResult(output = "subagent-ok")
            }
        )

        val result = tool.executeWithResult(
            """
            {
              "skill": "项目审查",
              "task": "审查配置合并逻辑是否有回归",
              "background": true
            }
            """.trimIndent()
        )

        assertEquals("subagent-ok", result.output.substringAfterLast('\n').trim())
        assertTrue(result.output.contains("已按 Skill `项目审查` 派发子代理。"))
        assertTrue(forwardedArgs.contains("\"goal\":\"审查配置合并逻辑是否有回归\""))
        assertTrue(forwardedArgs.contains("\"model\":\"gpt-5.5-pro\""))
        assertTrue(forwardedArgs.contains("\"background\":true"))
        assertTrue(forwardedArgs.contains("web_search"))
        assertTrue(forwardedArgs.contains("shell"))
        assertEquals("execution", result.structuredPayload?.skill?.kind)
        assertEquals("subagent", result.structuredPayload?.skill?.runAs)
        assertEquals("审查配置合并逻辑是否有回归", result.structuredPayload?.skill?.task)
        assertEquals(true, result.structuredPayload?.skill?.delegatedToSubagent)
        assertEquals(true, result.structuredPayload?.skill?.background)
    }
}
