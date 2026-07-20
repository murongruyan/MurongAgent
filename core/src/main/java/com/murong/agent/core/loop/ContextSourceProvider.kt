package com.murong.agent.core.loop

/**
 * Immutable context contract shared by provider backends.
 *
 * A source receives an explicit user/project selection and a budget. It must not crawl storage,
 * execute code, or retain Android objects. This keeps future context plugins explainable and
 * makes the exact selection auditable before it is sent to a model.
 */
internal data class ContextSourceRequest(
    val projectPath: String?,
    val query: String? = null,
    val selectedFiles: List<FileMentionUi> = emptyList(),
    /** Explicit, bounded project documentation candidates discovered by the caller. */
    val projectDocumentation: List<FileMentionUi> = emptyList(),
    val maxItems: Int = 4,
    val maxCharacters: Int = 7_000,
    /** Providers must stop as soon as the turn was cancelled; they never own a coroutine. */
    val isCancelled: () -> Boolean = { false }
)

internal data class ContextItem(
    val stableId: String,
    val path: String,
    val displayPath: String,
    /** Optional caller-supplied excerpt; never synthesized by a provider. */
    val inlineContent: String? = null,
    val kind: FileMentionKind,
    val byteSize: Long?,
    val modifiedAtMillis: Long?,
    val accessState: FileMentionAccessState,
    val inclusionMode: FileMentionInclusionMode,
    val source: FileMentionSource,
    val estimatedCharacters: Int
)

internal data class ContextSelectionSnapshot(
    val items: List<ContextItem>,
    val maxCharacters: Int,
    val estimatedCharacters: Int
) {
    fun toAuditText(): String {
        if (items.isEmpty()) return "本轮没有额外文件上下文。"
        return buildString {
            appendLine("本轮上下文快照：${items.size} 项，预计 $estimatedCharacters / $maxCharacters 字符")
            items.forEach { item ->
                append("- ${item.displayPath} · ${item.kind.label} · ${item.inclusionMode.label}")
                item.byteSize?.let { append(" · $it B") }
                append(" · ${item.source.label} · ${item.accessState.label}")
                appendLine()
            }
        }.trim()
    }
}

/** Extension point for manual selection, project rules, snapshots, and future local retrieval. */
internal interface ContextSourceProvider {
    val id: String
    fun collect(request: ContextSourceRequest): List<ContextItem>
}

/** The first provider: it exposes only files the user has already selected in the UI. */
internal object ManualMentionContextSourceProvider : ContextSourceProvider {
    override val id: String = "manual_mentions"

    override fun collect(request: ContextSourceRequest): List<ContextItem> {
        return request.selectedFiles
            .distinctBy { it.stableId }
            .take(request.maxItems.coerceAtLeast(0))
            .map { mention ->
                ContextItem(
                    stableId = mention.stableId,
                    path = mention.path,
                    displayPath = mention.displayPath,
                    inlineContent = mention.inlineContent,
                    kind = mention.kind,
                    byteSize = mention.byteSize,
                    modifiedAtMillis = mention.modifiedAtMillis,
                    accessState = mention.accessState,
                    inclusionMode = mention.inclusionMode,
                    source = mention.source,
                    estimatedCharacters = mention.inlineContent?.length
                        ?: if (mention.inclusionMode == FileMentionInclusionMode.TEXT_EXCERPT) 2_500 else 180
                )
            }
    }
}

/**
 * Supplies only a small caller-vetted set of README/architecture documents.  The provider itself
 * deliberately does not crawl storage: path discovery and permission checks stay in the Android
 * session layer, while this contract remains deterministic and testable.
 */
internal object ProjectDocumentationContextSourceProvider : ContextSourceProvider {
    override val id: String = "project_documentation"

    override fun collect(request: ContextSourceRequest): List<ContextItem> {
        if (request.isCancelled()) return emptyList()
        return request.projectDocumentation
            .asSequence()
            .filter { it.source == FileMentionSource.PROJECT_KNOWLEDGE }
            .distinctBy { it.stableId }
            .take(request.maxItems.coerceAtLeast(0))
            .map { mention ->
                ContextItem(
                    stableId = mention.stableId,
                    path = mention.path,
                    displayPath = mention.displayPath,
                    inlineContent = mention.inlineContent,
                    kind = mention.kind,
                    byteSize = mention.byteSize,
                    modifiedAtMillis = mention.modifiedAtMillis,
                    accessState = mention.accessState,
                    inclusionMode = mention.inclusionMode,
                    source = mention.source,
                    estimatedCharacters = mention.inlineContent?.length ?: 2_000
                )
            }
            .toList()
    }
}

/**
 * Combines providers without allowing later providers to silently exceed the per-turn context
 * budget. Providers can be registered by future project-knowledge or retrieval implementations.
 */
internal class ContextSourceRegistry(
    private val providers: List<ContextSourceProvider> = listOf(
        ManualMentionContextSourceProvider,
        ProjectDocumentationContextSourceProvider
    )
) {
    fun snapshot(request: ContextSourceRequest): ContextSelectionSnapshot {
        val maxItems = request.maxItems.coerceAtLeast(0)
        val maxCharacters = request.maxCharacters.coerceAtLeast(0)
        val selected = mutableListOf<ContextItem>()
        var usedCharacters = 0
        providers.forEach providerLoop@ { provider ->
            if (request.isCancelled() || selected.size >= maxItems || usedCharacters >= maxCharacters) {
                return@providerLoop
            }
            provider.collect(request.copy(maxItems = maxItems - selected.size))
                .forEach itemLoop@ { item ->
                    if (
                        request.isCancelled() || selected.size >= maxItems ||
                        selected.any { it.stableId == item.stableId }
                    ) return@itemLoop
                    val remaining = maxCharacters - usedCharacters
                    if (remaining <= 0) return@itemLoop
                    selected += item.copy(estimatedCharacters = item.estimatedCharacters.coerceAtMost(remaining))
                    usedCharacters += item.estimatedCharacters.coerceAtMost(remaining)
                }
        }
        return ContextSelectionSnapshot(
            items = selected,
            maxCharacters = maxCharacters,
            estimatedCharacters = usedCharacters
        )
    }
}

internal val DefaultContextSourceRegistry = ContextSourceRegistry()

/** Recreates the immutable, user-visible file descriptor consumed by the existing prompt builder. */
internal fun ContextSelectionSnapshot.toFileMentions(): List<FileMentionUi> = items.map { item ->
    FileMentionUi(
        path = item.path,
        displayPath = item.displayPath,
        inlineContent = item.inlineContent,
        byteSize = item.byteSize,
        modifiedAtMillis = item.modifiedAtMillis,
        kind = item.kind,
        accessState = item.accessState,
        inclusionMode = item.inclusionMode,
        source = item.source
    )
}
