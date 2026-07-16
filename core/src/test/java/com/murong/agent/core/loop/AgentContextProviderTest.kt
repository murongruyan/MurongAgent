package com.murong.agent.core.loop

import com.murong.agent.core.codex.CodexAdditionalContextKind
import com.murong.agent.core.config.GlobalMemory
import com.murong.agent.core.config.GlobalRule
import com.murong.agent.core.config.GlobalSkill
import com.murong.agent.core.config.ProviderConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentContextProviderTest {
    @Test
    fun buildsOnlyEnabledApplicationAndProjectContext() {
        val config = ProviderConfig(
            systemPrompt = "native base instruction",
            globalRules = listOf(
                GlobalRule("rule-1", "安全", "先检查改动", enabled = true),
                GlobalRule("rule-2", "关闭", "不应出现", enabled = false),
            ),
            globalMemories = listOf(GlobalMemory("memory-1", "用户", "偏好简洁回答")),
            globalSkills = listOf(GlobalSkill("skill-1", "测试", content = "运行相关测试")),
        )
        val state = SessionState(
            sessionGoal = "修复登录",
            projectRules = listOf(GlobalRule("project-rule", "项目", "不要改公开 API")),
        )

        val context = MurongAgentContextProvider.buildContext(config, state)

        assertEquals("先检查改动", context["murong.global_rules"]?.value?.substringAfter("\n"))
        assertTrue(context["murong.global_memories"]?.value.orEmpty().contains("偏好简洁回答"))
        assertTrue(context["murong.project_rules"]?.value.orEmpty().contains("不要改公开 API"))
        assertTrue(context["murong.session_goal"]?.value.orEmpty().contains("修复登录"))
        assertFalse(context.values.any { it.value.contains("native base instruction") })
        assertTrue(context.values.all { it.kind == CodexAdditionalContextKind.APPLICATION })
    }
}
