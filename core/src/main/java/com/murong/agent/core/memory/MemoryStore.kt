package com.murong.agent.core.memory

import com.murong.agent.core.config.GlobalMemory
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal enum class MemoryScope {
    ANY,
    GLOBAL,
    PROJECT;

    fun wireName(): String = name.lowercase()

    companion object {
        fun fromRaw(raw: String?): MemoryScope {
            return when (raw?.trim()?.lowercase()) {
                "global" -> GLOBAL
                "project" -> PROJECT
                else -> ANY
            }
        }
    }
}

internal enum class MemoryOrigin {
    DURABLE,
    LEGACY_CONFIG
}

internal data class MemoryRecord(
    val id: String,
    val title: String,
    val content: String,
    val enabled: Boolean = true,
    val scope: MemoryScope,
    val origin: MemoryOrigin,
    val updatedAt: Long,
    val scopePath: String? = null
)

internal data class MemorySearchHit(
    val memory: MemoryRecord,
    val score: Double,
    val snippet: String
)

internal data class MemoryDraft(
    val title: String,
    val content: String,
    val enabled: Boolean = true
)

internal data class PersistedMemorySaveResult(
    val savedMemory: MemoryRecord,
    val totalCount: Int
)

internal data class MemoryMigrationResult(
    val migratedMemories: List<MemoryRecord>,
    val skippedDuplicateCount: Int = 0
)

internal sealed interface MemoryDeleteResult {
    data class Deleted(val deletedMemory: MemoryRecord, val remainingCount: Int) : MemoryDeleteResult

    data class LegacyConfigMemory(val memory: MemoryRecord) : MemoryDeleteResult

    object NotFound : MemoryDeleteResult
}

internal interface MemoryStore {
    fun list(scope: MemoryScope = MemoryScope.ANY, limit: Int = 20): List<MemoryRecord>

    fun search(
        query: String,
        scope: MemoryScope = MemoryScope.ANY,
        limit: Int = 5
    ): List<MemorySearchHit>

    fun read(memoryId: String, scope: MemoryScope = MemoryScope.ANY): MemoryRecord?
}

internal interface MutableMemoryStore : MemoryStore {
    suspend fun createGlobalMemory(draft: MemoryDraft): PersistedMemorySaveResult

    suspend fun createProjectMemory(draft: MemoryDraft): PersistedMemorySaveResult

    suspend fun migrateLegacy(scope: MemoryScope = MemoryScope.ANY): MemoryMigrationResult

    suspend fun delete(memoryId: String, scope: MemoryScope = MemoryScope.ANY): MemoryDeleteResult
}

@Serializable
private data class PersistedMemoryEntry(
    val id: String,
    val title: String,
    val content: String,
    val enabled: Boolean = true,
    val updatedAt: Long = System.currentTimeMillis()
)

internal class PersistedMemoryStore(
    private val baseDir: File,
    private val globalMemoriesProvider: () -> List<GlobalMemory>,
    private val projectMemoriesProvider: () -> List<GlobalMemory>,
    private val currentProjectPathProvider: () -> String?
) : MutableMemoryStore {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    override fun list(scope: MemoryScope, limit: Int): List<MemoryRecord> {
        val normalizedLimit = limit.coerceIn(1, 50)
        return collectRecords(scope)
            .sortedWith(
                compareByDescending<MemoryRecord> { scopePriority(it.scope) }
                    .thenByDescending { it.updatedAt }
                    .thenBy { it.title.lowercase() }
            )
            .take(normalizedLimit)
    }

    override fun search(query: String, scope: MemoryScope, limit: Int): List<MemorySearchHit> {
        val normalizedQuery = normalizeSearchText(query)
        if (normalizedQuery.isBlank()) return emptyList()
        val queryTokens = tokenizeForBm25(normalizedQuery)
        if (queryTokens.isEmpty()) return emptyList()
        val records = collectRecords(scope)
        return bm25Score(records, queryTokens).mapNotNull { (index, score) ->
            val record = records[index]
            MemorySearchHit(
                memory = record,
                score = score,
                snippet = buildSnippet(record.content, normalizedQuery, queryTokens.distinct())
            )
        }.sortedWith(
            compareByDescending<MemorySearchHit> { it.score }
                .thenByDescending { scopePriority(it.memory.scope) }
                .thenByDescending { it.memory.updatedAt }
                .thenBy { it.memory.title.lowercase() }
        ).take(limit.coerceIn(1, 20))
    }

    override fun read(memoryId: String, scope: MemoryScope): MemoryRecord? {
        val normalizedId = memoryId.trim()
        if (normalizedId.isBlank()) return null
        return collectRecords(scope).firstOrNull { record ->
            record.id.equals(normalizedId, ignoreCase = true)
        }
    }

    override suspend fun createGlobalMemory(draft: MemoryDraft): PersistedMemorySaveResult {
        val entry = PersistedMemoryEntry(
            id = UUID.randomUUID().toString().take(8),
            title = draft.title.trim(),
            content = draft.content.trim(),
            enabled = draft.enabled,
            updatedAt = System.currentTimeMillis()
        )
        require(entry.title.isNotBlank()) { "'title' parameter required" }
        require(entry.content.isNotBlank()) { "'content' parameter required" }
        val updated = loadPersistedEntries(globalMemoryFile()) + entry
        savePersistedEntries(globalMemoryFile(), updated)
        return PersistedMemorySaveResult(
            savedMemory = entry.toRecord(
                scope = MemoryScope.GLOBAL,
                origin = MemoryOrigin.DURABLE,
                scopePath = null
            ),
            totalCount = updated.size
        )
    }

    override suspend fun createProjectMemory(draft: MemoryDraft): PersistedMemorySaveResult {
        val scopePath = currentProjectPathProvider()?.trim()?.takeIf { it.isNotBlank() }
            ?: error("当前没有激活的本地项目作用域")
        val file = projectMemoryFile(scopePath)
        val entry = PersistedMemoryEntry(
            id = UUID.randomUUID().toString().take(8),
            title = draft.title.trim(),
            content = draft.content.trim(),
            enabled = draft.enabled,
            updatedAt = System.currentTimeMillis()
        )
        require(entry.title.isNotBlank()) { "'title' parameter required" }
        require(entry.content.isNotBlank()) { "'content' parameter required" }
        val updated = loadPersistedEntries(file) + entry
        savePersistedEntries(file, updated)
        return PersistedMemorySaveResult(
            savedMemory = entry.toRecord(
                scope = MemoryScope.PROJECT,
                origin = MemoryOrigin.DURABLE,
                scopePath = scopePath
            ),
            totalCount = updated.size
        )
    }

    override suspend fun migrateLegacy(scope: MemoryScope): MemoryMigrationResult {
        val visibleLegacy = collectRecords(scope)
            .asSequence()
            .filter { it.origin == MemoryOrigin.LEGACY_CONFIG }
            .toList()
        if (visibleLegacy.isEmpty()) {
            return MemoryMigrationResult(migratedMemories = emptyList())
        }
        val uniqueLegacy = visibleLegacy.distinctBy { it.deduplicationKey() }
        val migrated = uniqueLegacy.map { legacy ->
            when (legacy.scope) {
                MemoryScope.PROJECT -> createProjectMemory(
                    MemoryDraft(
                        title = legacy.title,
                        content = legacy.content,
                        enabled = legacy.enabled
                    )
                ).savedMemory

                MemoryScope.GLOBAL -> createGlobalMemory(
                    MemoryDraft(
                        title = legacy.title,
                        content = legacy.content,
                        enabled = legacy.enabled
                    )
                ).savedMemory

                MemoryScope.ANY -> error("unexpected memory scope ANY during legacy migration")
            }
        }
        return MemoryMigrationResult(
            migratedMemories = migrated,
            skippedDuplicateCount = visibleLegacy.size - uniqueLegacy.size
        )
    }

    override suspend fun delete(memoryId: String, scope: MemoryScope): MemoryDeleteResult {
        val normalizedId = memoryId.trim()
        if (normalizedId.isBlank()) return MemoryDeleteResult.NotFound
        val record = collectRecords(scope).firstOrNull { candidate ->
            candidate.id.equals(normalizedId, ignoreCase = true)
        } ?: return MemoryDeleteResult.NotFound
        if (record.origin == MemoryOrigin.LEGACY_CONFIG) {
            return MemoryDeleteResult.LegacyConfigMemory(record)
        }
        val targetFile = when (record.scope) {
            MemoryScope.GLOBAL -> globalMemoryFile()
            MemoryScope.PROJECT -> {
                val scopePath = record.scopePath?.trim()?.takeIf { it.isNotBlank() }
                    ?: return MemoryDeleteResult.NotFound
                projectMemoryFile(scopePath)
            }
            MemoryScope.ANY -> return MemoryDeleteResult.NotFound
        }
        val existing = loadPersistedEntries(targetFile)
        val updated = existing.filterNot { entry ->
            entry.id.equals(normalizedId, ignoreCase = true)
        }
        if (existing.size == updated.size) return MemoryDeleteResult.NotFound
        savePersistedEntries(targetFile, updated)
        return MemoryDeleteResult.Deleted(
            deletedMemory = record,
            remainingCount = updated.count { it.enabled }
        )
    }

    private fun collectRecords(scope: MemoryScope): List<MemoryRecord> {
        val projectScopePath = currentProjectPathProvider()?.trim()?.takeIf { it.isNotBlank() }
        val durableGlobal = if (scope != MemoryScope.PROJECT) {
            loadPersistedEntries(globalMemoryFile()).map { entry ->
                entry.toRecord(
                    scope = MemoryScope.GLOBAL,
                    origin = MemoryOrigin.DURABLE,
                    scopePath = null
                )
            }
        } else {
            emptyList()
        }
        val durableProject = if (scope != MemoryScope.GLOBAL && projectScopePath != null) {
            loadPersistedEntries(projectMemoryFile(projectScopePath)).map { entry ->
                entry.toRecord(
                    scope = MemoryScope.PROJECT,
                    origin = MemoryOrigin.DURABLE,
                    scopePath = projectScopePath
                )
            }
        } else {
            emptyList()
        }
        val legacyGlobal = if (scope != MemoryScope.PROJECT) {
            globalMemoriesProvider()
                .asSequence()
                .filter { it.enabled }
                .mapNotNull { memory ->
                    memory.toLegacyRecord(scope = MemoryScope.GLOBAL, scopePath = null)
                }.toList()
        } else {
            emptyList()
        }
        val legacyProject = if (scope != MemoryScope.GLOBAL && projectScopePath != null) {
            projectMemoriesProvider()
                .asSequence()
                .filter { it.enabled }
                .mapNotNull { memory ->
                    memory.toLegacyRecord(
                        scope = MemoryScope.PROJECT,
                        scopePath = projectScopePath
                    )
                }.toList()
        } else {
            emptyList()
        }
        val durableRecords = durableProject + durableGlobal
        val durableKeys = durableRecords.mapTo(linkedSetOf()) { record ->
            record.deduplicationKey()
        }
        val filteredLegacy = (legacyProject + legacyGlobal).filterNot { record ->
            record.deduplicationKey() in durableKeys
        }
        return (durableRecords + filteredLegacy)
            .distinctBy { it.id }
    }

    private fun loadPersistedEntries(file: File): List<PersistedMemoryEntry> {
        if (!file.exists()) return emptyList()
        return runCatching {
            json.decodeFromString<List<PersistedMemoryEntry>>(file.readText())
        }.getOrDefault(emptyList()).filter { entry ->
            entry.title.isNotBlank() && entry.content.isNotBlank() && entry.enabled
        }
    }

    private fun savePersistedEntries(file: File, entries: List<PersistedMemoryEntry>) {
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(entries))
    }

    private fun globalMemoryFile(): File = File(baseDir, "global_memories.json")

    private fun projectMemoryFile(scopePath: String): File {
        return File(
            File(baseDir, "projects"),
            "${scopeKey(scopePath)}.json"
        )
    }

    private fun scopeKey(scopePath: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(scopePath.lowercase().toByteArray())
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }.take(16)
    }
}

private fun PersistedMemoryEntry.toRecord(
    scope: MemoryScope,
    origin: MemoryOrigin,
    scopePath: String?
): MemoryRecord {
    return MemoryRecord(
        id = id,
        title = title,
        content = content,
        enabled = enabled,
        scope = scope,
        origin = origin,
        updatedAt = updatedAt,
        scopePath = scopePath
    )
}

private fun GlobalMemory.toLegacyRecord(
    scope: MemoryScope,
    scopePath: String?
): MemoryRecord? {
    val normalizedTitle = title.trim()
    val normalizedContent = content.trim()
    if (normalizedTitle.isBlank() || normalizedContent.isBlank()) return null
    return MemoryRecord(
        id = buildString {
            append("legacy-")
            append(scope.wireName())
            append('-')
            append(id.ifBlank { UUID.randomUUID().toString().take(8) })
        },
        title = normalizedTitle,
        content = normalizedContent,
        enabled = enabled,
        scope = scope,
        origin = MemoryOrigin.LEGACY_CONFIG,
        updatedAt = 0L,
        scopePath = scopePath
    )
}

private fun scopePriority(scope: MemoryScope): Int {
    return when (scope) {
        MemoryScope.PROJECT -> 2
        MemoryScope.GLOBAL -> 1
        MemoryScope.ANY -> 0
    }
}

private fun MemoryRecord.deduplicationKey(): String {
    return listOf(
        scope.wireName(),
        scopePath.orEmpty().trim().lowercase(),
        title.trim().lowercase(),
        content.trim().lowercase()
    ).joinToString(separator = "\u0000")
}

/**
 * BM25 Okapi scoring over the full record collection.
 * Standard parameters: k1=1.2, b=0.75
 */
private fun bm25Score(
    records: List<MemoryRecord>,
    queryTokens: List<String>,
    k1: Double = 1.2,
    b: Double = 0.75
): List<Pair<Int, Double>> {
    if (queryTokens.isEmpty() || records.isEmpty()) return emptyList()

    // Tokenize each record (title + content combined)
    data class DocIndex(val index: Int, val tokens: List<String>, val termFreq: Map<String, Int>)
    val docs = records.mapIndexed { index, record ->
        val text = normalizeSearchText("${record.title} ${record.title} ${record.title} ${record.title} ${record.content}")
        val tokens = tokenizeForBm25(text)
        val termFreq = tokens.groupingBy { it }.eachCount()
        DocIndex(index, tokens, termFreq)
    }

    val numDocs = docs.size.toDouble()
    val avgDocLen = docs.map { it.tokens.size }.average().coerceAtLeast(1.0)
    val uniqueQueryTerms = queryTokens.toSet()

    // Document frequency per unique query term
    val docFreqs = mutableMapOf<String, Int>()
    for (term in uniqueQueryTerms) {
        docFreqs[term] = docs.count { doc -> doc.termFreq.containsKey(term) }
    }

    return docs.mapNotNull { doc ->
        val docLen = doc.tokens.size.coerceAtLeast(1).toDouble()
        var score = 0.0
        for (term in queryTokens) {
            val tf = doc.termFreq[term]?.toDouble() ?: continue
            val df = docFreqs[term] ?: continue
            if (df <= 0) continue
            // BM25 IDF: log(1 + (N - df + 0.5) / (df + 0.5))
            val idf = Math.log(1.0 + (numDocs - df + 0.5) / (df + 0.5))
            // BM25 term score
            score += idf * (tf * (k1 + 1.0)) / (tf + k1 * (1.0 - b + b * docLen / avgDocLen))
        }
        if (score <= 0.0) null else doc.index to score
    }.sortedByDescending { it.second }
}

/**
 * Tokenizer for BM25 — keeps duplicates (unlike [tokenize]) so term frequency is preserved.
 */
private fun tokenizeForBm25(text: String): List<String> {
    val result = mutableListOf<String>()
    val latinBuf = StringBuilder()
    for (ch in text.lowercase()) {
        when {
            ch in '\u4E00'..'\u9FFF' || ch in '\uAC00'..'\uD7AF' ||
                ch in '\u3040'..'\u309F' || ch in '\u30A0'..'\u30FF' -> {
                if (latinBuf.isNotEmpty()) { result.add(latinBuf.toString()); latinBuf.clear() }
                result.add(ch.toString())
            }
            ch.isLetterOrDigit() || ch == '_' || ch == '-' -> latinBuf.append(ch)
            else -> {
                if (latinBuf.isNotEmpty()) { result.add(latinBuf.toString()); latinBuf.clear() }
            }
        }
    }
    if (latinBuf.isNotEmpty()) result.add(latinBuf.toString())
    return result
}

private fun tokenize(text: String): List<String> {
    return Regex("[\\p{L}\\p{N}_-]+")
        .findAll(text.lowercase())
        .map { it.value }
        .filter { it.isNotBlank() }
        .distinct()
        .toList()
}

private fun normalizeSearchText(text: String): String {
    return text.lowercase()
        .replace(Regex("\\s+"), " ")
        .trim()
}

private fun buildSnippet(
    content: String,
    rawQuery: String,
    tokens: List<String>
): String {
    val normalized = content.trim()
    if (normalized.isBlank()) return ""
    val normalizedQuery = normalizeSearchText(rawQuery)
    val preferredLine = normalized.lines()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .maxByOrNull { line -> scoreSnippetLine(line, normalizedQuery, tokens) }
        ?.takeIf { line -> scoreSnippetLine(line, normalizedQuery, tokens) > 0 }
        ?: normalized.replace(Regex("\\s+"), " ").trim()
    return preferredLine.trim().let { line ->
        if (line.length <= 96) line else "${line.take(93)}..."
    }
}

private fun scoreSnippetLine(
    line: String,
    normalizedQuery: String,
    tokens: List<String>
): Int {
    val normalizedLine = normalizeSearchText(line)
    var score = 0
    when {
        normalizedLine == normalizedQuery -> score += 80
        normalizedLine.startsWith(normalizedQuery) -> score += 50
        normalizedLine.contains(normalizedQuery) -> score += 24
    }
    tokens.forEach { token ->
        when {
            normalizedLine.startsWith(token) -> score += 10
            normalizedLine.contains(token) -> score += 4
        }
    }
    return score
}
