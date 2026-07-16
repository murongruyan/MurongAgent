package com.murong.agent.core.loop

import com.murong.agent.core.codex.CodexAdditionalContext
import com.murong.agent.core.config.GlobalMemory
import com.murong.agent.core.config.GlobalRule
import com.murong.agent.core.config.GlobalSkill
import com.murong.agent.core.config.ProviderConfig

/**
 * Extensible application-context boundary for agent backends.
 *
 * A provider may translate the same context into native request fields (as the
 * Codex backend does through `turn/start.additionalContext`) rather than
 * pretending that application instructions came from the user.
 */
interface AgentContextProvider {
    fun buildContext(
        config: ProviderConfig,
        state: SessionState,
    ): Map<String, CodexAdditionalContext>
}

/** Default bounded projection of the existing Murong configuration model. */
object MurongAgentContextProvider : AgentContextProvider {
    private const val MAX_FRAGMENT_CHARS = 12_000
    private const val MAX_TOTAL_CHARS = 28_000

    override fun buildContext(
        config: ProviderConfig,
        state: SessionState,
    ): Map<String, CodexAdditionalContext> {
        val fragments = linkedMapOf<String, String>()
        // The primary system prompt is supplied through the native
        // `thread/start.baseInstructions` field. This map carries the
        // extensible application/project context only, avoiding duplication.
        buildRules("全局规则", config.globalRules)?.let {
            fragments["murong.global_rules"] = it
        }
        buildMemories("全局记忆", config.globalMemories)?.let {
            fragments["murong.global_memories"] = it
        }
        buildSkills(config.globalSkills)?.let {
            fragments["murong.global_skills"] = it
        }
        buildRules("项目规则", state.projectRules)?.let {
            fragments["murong.project_rules"] = it
        }
        buildMemories("项目记忆", state.projectMemories)?.let {
            fragments["murong.project_memories"] = it
        }
        buildSkills(state.projectSkills)?.let {
            fragments["murong.project_skills"] = it
        }
        state.sessionGoal?.trim()?.takeIf(String::isNotBlank)?.let { goal ->
            fragments["murong.session_goal"] = "当前会话目标：$goal"
        }

        var remaining = MAX_TOTAL_CHARS
        return fragments.mapNotNull { (sourceId, content) ->
            if (remaining <= 0) return@mapNotNull null
            val bounded = content.trim().take(MAX_FRAGMENT_CHARS).take(remaining)
            if (bounded.isBlank()) return@mapNotNull null
            remaining -= bounded.length
            sourceId to CodexAdditionalContext(value = bounded)
        }.toMap(linkedMapOf())
    }

    private fun buildRules(label: String, entries: List<GlobalRule>): String? =
        entries.filter { it.enabled && it.content.isNotBlank() }
            .joinToString("\n\n") { entry -> "[$label：${entry.title}]\n${entry.content.trim()}" }
            .takeIf(String::isNotBlank)

    private fun buildMemories(label: String, entries: List<GlobalMemory>): String? =
        entries.filter { it.enabled && it.content.isNotBlank() }
            .joinToString("\n\n") { entry -> "[$label：${entry.title}]\n${entry.content.trim()}" }
            .takeIf(String::isNotBlank)

    private fun buildSkills(entries: List<GlobalSkill>): String? =
        entries.filter { it.enabled && it.content.isNotBlank() }
            .joinToString("\n\n") { skill ->
                buildString {
                    append("[技能：")
                    append(skill.title)
                    append("]")
                    skill.description.trim().takeIf(String::isNotBlank)?.let {
                        append("\n")
                        append(it)
                    }
                    append("\n")
                    append(skill.content.trim())
                }
            }
            .takeIf(String::isNotBlank)
}
