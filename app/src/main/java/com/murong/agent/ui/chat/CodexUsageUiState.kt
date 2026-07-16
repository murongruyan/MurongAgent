package com.murong.agent.ui.chat

import com.murong.agent.core.codex.CodexRateLimitWindow
import com.murong.agent.core.codex.CodexRateLimitsSnapshot
import com.murong.agent.core.codex.CodexModelCatalog
import com.murong.agent.core.codex.CodexModelDescriptor

/** Display-safe projection of the official app-server rate-limit snapshot. */
data class CodexUsageUiState(
    val isLoading: Boolean = false,
    val primary: CodexRateLimitWindow? = null,
    /** The longer rolling window (normally the ChatGPT/Codex weekly allowance). */
    val secondary: CodexRateLimitWindow? = null,
    val rateLimitReachedType: String? = null,
    val error: String? = null,
) {
    val hasData: Boolean get() = primary != null || secondary != null
}

internal fun CodexRateLimitsSnapshot.toCodexUsageUiState(
    isLoading: Boolean = false,
): CodexUsageUiState = CodexUsageUiState(
    isLoading = isLoading,
    primary = rateLimits?.primary,
    secondary = rateLimits?.secondary,
    rateLimitReachedType = rateLimits?.rateLimitReachedType,
)

/** Model catalog sourced from `model/list`, not a locally guessed model list. */
data class CodexModelCatalogUiState(
    val isLoading: Boolean = false,
    val models: List<CodexModelDescriptor> = emptyList(),
    val error: String? = null,
) {
    val hasCatalog: Boolean get() = models.isNotEmpty()
}

internal fun CodexModelCatalog.toCodexModelCatalogUiState(): CodexModelCatalogUiState =
    CodexModelCatalogUiState(models = models.filter { it.id.isNotBlank() && it.hidden != true })
