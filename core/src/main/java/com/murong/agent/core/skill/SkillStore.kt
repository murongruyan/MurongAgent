package com.murong.agent.core.skill

import com.murong.agent.core.config.GlobalSkill
import com.murong.agent.core.config.SkillRunAs
import java.util.UUID

internal enum class SkillSource {
    ANY,
    PROJECT,
    GLOBAL;

    fun wireName(): String = name.lowercase()

    companion object {
        fun fromRaw(raw: String?): SkillSource {
            return when (raw?.trim()?.lowercase()) {
                "project" -> PROJECT
                "global" -> GLOBAL
                else -> ANY
            }
        }
    }
}

internal data class SkillCatalogEntry(
    val skill: GlobalSkill,
    val source: SkillSource
)

internal interface SkillStore {
    fun list(source: SkillSource = SkillSource.ANY): List<SkillCatalogEntry>

    fun match(query: String, source: SkillSource = SkillSource.ANY): List<SkillCatalogEntry>
}

internal data class SkillDraft(
    val title: String,
    val description: String = "",
    val content: String,
    val runAs: SkillRunAs,
    val allowedTools: List<String> = emptyList(),
    val preferredModel: String = "",
    val enabled: Boolean = true
)

internal data class PersistedSkillSaveResult(
    val savedSkill: GlobalSkill,
    val totalCount: Int
)

internal interface MutableSkillStore : SkillStore {
    suspend fun createGlobalSkill(draft: SkillDraft): PersistedSkillSaveResult

    suspend fun createProjectSkill(draft: SkillDraft): PersistedSkillSaveResult
}

internal class CompositeSkillStore(
    private val globalSkillsProvider: () -> List<GlobalSkill>,
    private val projectSkillsProvider: () -> List<GlobalSkill>
) : SkillStore {
    override fun list(source: SkillSource): List<SkillCatalogEntry> {
        val entries = buildList {
            projectSkillsProvider()
                .filter(::isActiveSkill)
                .forEach { add(SkillCatalogEntry(skill = it, source = SkillSource.PROJECT)) }
            globalSkillsProvider()
                .filter(::isActiveSkill)
                .forEach { add(SkillCatalogEntry(skill = it, source = SkillSource.GLOBAL)) }
        }
        return when (source) {
            SkillSource.ANY -> entries
            else -> entries.filter { it.source == source }
        }
    }

    override fun match(query: String, source: SkillSource): List<SkillCatalogEntry> {
        return SkillCatalogIndex(list(source)).match(query)
    }
}

internal class PersistedSkillStore(
    private val globalSkillsProvider: () -> List<GlobalSkill>,
    private val projectSkillsProvider: () -> List<GlobalSkill>,
    private val saveGlobalSkills: suspend (List<GlobalSkill>) -> Unit,
    private val saveProjectSkills: suspend (List<GlobalSkill>) -> Unit
) : MutableSkillStore {
    private val reader = CompositeSkillStore(
        globalSkillsProvider = globalSkillsProvider,
        projectSkillsProvider = projectSkillsProvider
    )

    override fun list(source: SkillSource): List<SkillCatalogEntry> = reader.list(source)

    override fun match(query: String, source: SkillSource): List<SkillCatalogEntry> {
        return reader.match(query, source)
    }

    override suspend fun createGlobalSkill(draft: SkillDraft): PersistedSkillSaveResult {
        val saved = buildGlobalSkill(draft)
        val updated = globalSkillsProvider() + saved
        saveGlobalSkills(updated)
        return PersistedSkillSaveResult(
            savedSkill = saved,
            totalCount = updated.size
        )
    }

    override suspend fun createProjectSkill(draft: SkillDraft): PersistedSkillSaveResult {
        val saved = buildGlobalSkill(draft)
        val updated = projectSkillsProvider() + saved
        saveProjectSkills(updated)
        return PersistedSkillSaveResult(
            savedSkill = saved,
            totalCount = updated.size
        )
    }

    private fun buildGlobalSkill(draft: SkillDraft): GlobalSkill {
        return GlobalSkill(
            id = UUID.randomUUID().toString().take(8),
            title = draft.title,
            description = draft.description,
            content = draft.content,
            runAs = draft.runAs,
            allowedTools = draft.allowedTools,
            preferredModel = draft.preferredModel,
            enabled = draft.enabled
        )
    }
}

internal class SkillCatalogIndex(
    private val entries: List<SkillCatalogEntry>
) {
    fun match(query: String): List<SkillCatalogEntry> {
        val normalizedQuery = query.trim().lowercase()
        if (normalizedQuery.isBlank()) return emptyList()
        val exactId = entries.filter { it.skill.id.lowercase() == normalizedQuery }
        if (exactId.isNotEmpty()) return exactId
        val exactTitle = entries.filter { it.skill.title.trim().lowercase() == normalizedQuery }
        if (exactTitle.isNotEmpty()) {
            return exactTitle.sortedBy { it.source != SkillSource.PROJECT }
        }
        return entries.filter { entry ->
            entry.skill.title.lowercase().contains(normalizedQuery) ||
                entry.skill.description.lowercase().contains(normalizedQuery) ||
                entry.skill.id.lowercase().contains(normalizedQuery)
        }.sortedBy { it.source != SkillSource.PROJECT }
    }
}

private fun isActiveSkill(skill: GlobalSkill): Boolean {
    return skill.enabled && skill.content.trim().isNotBlank()
}
