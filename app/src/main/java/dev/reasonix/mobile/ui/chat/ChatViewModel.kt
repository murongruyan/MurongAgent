package dev.reasonix.mobile.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.reasonix.mobile.core.config.ConfigRepository
import dev.reasonix.mobile.core.config.GlobalSkill
import dev.reasonix.mobile.core.config.ProviderConfig
import dev.reasonix.mobile.core.config.GlobalMemory
import dev.reasonix.mobile.core.config.GlobalRule
import dev.reasonix.mobile.core.config.ProjectToolPreferences
import dev.reasonix.mobile.core.loop.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val sessionManager: ChatSessionManager,
    private val configRepository: ConfigRepository
) : ViewModel() {

    val state: StateFlow<SessionState> = sessionManager.state

    val config: StateFlow<ProviderConfig> = configRepository.configFlow
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), ProviderConfig())

    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending.asStateFlow()
    private var activeOperationJob: Job? = null

    private val _sessions = MutableStateFlow<List<SessionSummary>>(emptyList())
    val sessions: StateFlow<List<SessionSummary>> = _sessions.asStateFlow()
    private val _toastMessages = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val toastMessages = _toastMessages.asSharedFlow()

    init {
        refreshSessions()
    }

    fun hasActiveApiKey(config: ProviderConfig): Boolean {
        return config.getActiveApiKey().isNotBlank()
    }

    fun sendMessage(text: String, mentionedFiles: List<FileMentionUi> = emptyList()) {
        sendMessage(text, mentionedFiles, emptyList())
    }

    fun sendMessage(
        text: String,
        mentionedFiles: List<FileMentionUi> = emptyList(),
        pendingImages: List<PendingImageAttachmentUi> = emptyList()
    ) {
        launchSendingOperation {
            sessionManager.clearLastAutoRouteDecision()
            val toastMessage = withContext(Dispatchers.IO) {
                sessionManager.sendMessage(text, mentionedFiles, pendingImages)
            }
            toastMessage?.let { _toastMessages.tryEmit(it) }
            refreshSessions()
        }
    }

    fun autoRouteMessage(text: String, mentionedFiles: List<FileMentionUi> = emptyList()) {
        launchSendingOperation {
            val toastMessage = withContext(Dispatchers.IO) {
                sessionManager.autoRouteMessage(text, mentionedFiles)
            }
            toastMessage?.let { _toastMessages.tryEmit(it) }
            refreshSessions()
        }
    }

    fun generateWorkflowPlan(text: String, mentionedFiles: List<FileMentionUi> = emptyList()) {
        launchSendingOperation {
            sessionManager.clearLastAutoRouteDecision()
            withContext(Dispatchers.IO) {
                sessionManager.generateWorkflowPlan(text, mentionedFiles)
            }
            refreshSessions()
        }
    }

    fun executePendingWorkflowPlan() {
        launchSendingOperation {
            withContext(Dispatchers.IO) {
                sessionManager.executePendingWorkflowPlan()
            }
            refreshSessions()
        }
    }

    fun dismissPendingWorkflowPlan() {
        sessionManager.dismissPendingWorkflowPlan()
        refreshSessions()
    }

    fun generateClarificationQuestion(text: String, mentionedFiles: List<FileMentionUi> = emptyList()) {
        launchSendingOperation {
            sessionManager.clearLastAutoRouteDecision()
            withContext(Dispatchers.IO) {
                sessionManager.generateClarificationQuestion(text, mentionedFiles)
            }
            refreshSessions()
        }
    }

    fun submitClarificationAnswer(answer: String) {
        launchSendingOperation {
            withContext(Dispatchers.IO) {
                sessionManager.submitClarificationAnswer(answer)
            }
            refreshSessions()
        }
    }

    fun dismissPendingClarification() {
        sessionManager.dismissPendingClarification()
        refreshSessions()
    }

    fun cancelSubagentRun(runId: String): Boolean {
        val cancelled = sessionManager.cancelSubagentRun(runId)
        if (cancelled) {
            refreshSessions()
        }
        return cancelled
    }

    fun retrySubagentRun(runId: String) {
        launchSendingOperation {
            withContext(Dispatchers.IO) {
                sessionManager.retrySubagentRun(runId)
            }
            refreshSessions()
        }
    }

    fun stopSending(): Boolean {
        val cancelled = sessionManager.cancelCurrentProcessing()
        activeOperationJob?.cancel()
        activeOperationJob = null
        _isSending.value = false
        if (cancelled) {
            refreshSessions()
        }
        return cancelled
    }

    fun searchProjectFiles(query: String, limit: Int = 20): List<FileMentionUi> {
        return sessionManager.searchProjectFiles(query, limit)
    }

    fun clear() {
        sessionManager.clear()
    }

    fun newSession() {
        sessionManager.newSession()
        refreshSessions()
    }

    fun startTask(projectPath: String) {
        if (projectPath.isBlank()) return
        sessionManager.startTask(projectPath)
        refreshSessions()
    }

    fun switchProjectScope(scopePath: String?) {
        sessionManager.switchProjectScope(scopePath)
        refreshSessions()
    }

    fun loadSession(sessionId: String) {
        if (sessionManager.loadSession(sessionId)) {
            refreshSessions()
        }
    }

    fun saveSession() {
        sessionManager.saveCurrentSession()
        refreshSessions()
    }

    fun rollbackLastTurn(): Boolean {
        val rolledBack = sessionManager.rollbackLastTurn()
        if (rolledBack) {
            refreshSessions()
        }
        return rolledBack
    }

    fun rollbackToUserMessage(messageId: Long): Boolean {
        val rolledBack = sessionManager.rollbackToUserMessage(messageId)
        if (rolledBack) {
            refreshSessions()
        }
        return rolledBack
    }

    fun rollbackFileCheckpoint(checkpointId: String): Result<Int> {
        val result = sessionManager.rollbackFileCheckpoint(checkpointId)
        if (result.isSuccess) {
            refreshSessions()
        }
        return result
    }

    fun importConversation(rawText: String, sourceName: String? = null): Result<Int> {
        return runCatching {
            val importedCount = sessionManager.importConversation(rawText, sourceName)
            refreshSessions()
            importedCount
        }
    }

    fun approvePendingTool(): Boolean = sessionManager.approvePendingTool()

    fun clearApprovedSubagentScopes(): Boolean {
        val cleared = sessionManager.clearApprovedApprovalScopesForCurrentSession()
        if (cleared) {
            refreshSessions()
        }
        return cleared
    }

    fun clearImportedSubagentScopes(): Boolean {
        val cleared = sessionManager.clearImportedApprovalScopesForCurrentSession()
        if (cleared) {
            refreshSessions()
        }
        return cleared
    }

    fun removeApprovedSubagentScope(scopeId: String): Boolean {
        val removed = sessionManager.removeApprovedApprovalScopeForCurrentSession(scopeId)
        if (removed) {
            refreshSessions()
        }
        return removed
    }

    fun importInheritedSubagentScope(scopeId: String): Boolean {
        val imported = sessionManager.importInheritedApprovalScopeForCurrentSession(scopeId)
        if (imported) {
            refreshSessions()
        }
        return imported
    }

    fun rejectPendingTool(): Boolean = sessionManager.rejectPendingTool()

    fun deleteSession(sessionId: String) {
        sessionManager.deleteSession(sessionId)
        refreshSessions()
    }

    fun renameSession(sessionId: String, newTitle: String): Boolean {
        val renamed = sessionManager.renameSession(sessionId, newTitle)
        if (renamed) {
            refreshSessions()
        }
        return renamed
    }

    fun exportCurrentConversation(format: ConversationExportFormat): Result<ConversationExportData> {
        return sessionManager.exportCurrentSession(format, config.value)
    }

    suspend fun compressCurrentContext(): Result<ContextCompressionUi> {
        val result = sessionManager.compressCurrentContext()
        if (result.isSuccess) {
            refreshSessions()
        }
        return result
    }

    fun disableContextCompression(): Boolean {
        val disabled = sessionManager.disableContextCompression()
        if (disabled) {
            refreshSessions()
        }
        return disabled
    }

    fun enableContextCompression(): Boolean {
        val enabled = sessionManager.enableContextCompression()
        if (enabled) {
            refreshSessions()
        }
        return enabled
    }

    fun updateProjectConfig(
        scopePath: String? = state.value.activeProjectScopePath ?: state.value.projectPath,
        rules: List<GlobalRule>? = null,
        memories: List<GlobalMemory>? = null,
        skills: List<GlobalSkill>? = null
    ) {
        sessionManager.updateProjectConfig(scopePath, rules, memories, skills)
        refreshSessions()
    }

    fun updateProjectToolPreferences(
        scopePath: String? = state.value.activeProjectScopePath ?: state.value.projectPath,
        preferences: ProjectToolPreferences?
    ) {
        sessionManager.updateProjectToolPreferences(scopePath, preferences)
        refreshSessions()
    }

    fun updateProjectKnowledgePaths(paths: List<String>) {
        sessionManager.updateProjectKnowledgePaths(paths)
        refreshSessions()
    }

    fun saveProjectKnowledgeSnapshot(name: String, paths: List<String>): ProjectKnowledgeSnapshotMutationResult {
        return sessionManager.saveProjectKnowledgeSnapshot(name, paths).also { result ->
            if (result.success) {
                refreshSessions()
            }
        }
    }

    fun renameProjectKnowledgeSnapshot(
        snapshotId: String,
        newName: String
    ): ProjectKnowledgeSnapshotMutationResult {
        return sessionManager.renameProjectKnowledgeSnapshot(snapshotId, newName).also { result ->
            if (result.success) {
                refreshSessions()
            }
        }
    }

    fun deleteProjectKnowledgeSnapshot(snapshotId: String): ProjectKnowledgeSnapshotMutationResult {
        return sessionManager.deleteProjectKnowledgeSnapshot(snapshotId).also { result ->
            if (result.success) {
                refreshSessions()
            }
        }
    }

    fun applyProjectKnowledgeSnapshot(snapshotId: String): ProjectKnowledgeSnapshotMutationResult {
        return sessionManager.applyProjectKnowledgeSnapshot(snapshotId).also { result ->
            if (result.success) {
                refreshSessions()
            }
        }
    }

    fun refreshSessions() {
        _sessions.value = sessionManager.listSessions()
    }

    private fun launchSendingOperation(block: suspend () -> Unit) {
        if (_isSending.value) return
        _isSending.value = true
        val job = viewModelScope.launch {
            try {
                block()
            } finally {
                if (activeOperationJob === coroutineContext[Job]) {
                    activeOperationJob = null
                }
                _isSending.value = false
            }
        }
        activeOperationJob = job
    }
}
