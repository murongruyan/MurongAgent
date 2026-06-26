package com.murong.agent.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import com.murong.agent.core.config.ConfigRepository
import com.murong.agent.core.config.GlobalSkill
import com.murong.agent.core.config.ProviderConfig
import com.murong.agent.core.config.GlobalMemory
import com.murong.agent.core.config.GlobalRule
import com.murong.agent.core.config.ProjectToolPreferences
import com.murong.agent.core.loop.*
import com.murong.agent.ui.project.ProjectGitHubRepoRef
import com.murong.agent.ui.project.loadProjectGitHubRemoteFile
import com.murong.agent.ui.project.searchProjectGitHubFileNames
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
    private companion object {
        private const val REMOTE_MENTION_LIMIT = 8
        private const val REMOTE_MENTION_CONTENT_LIMIT = 6
    }

    val state: StateFlow<SessionState> = sessionManager.state

    val config: StateFlow<ProviderConfig> = configRepository.configFlow
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), ProviderConfig())

    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending.asStateFlow()
    private var activeOperationJob: Job? = null

    private val _sessions = MutableStateFlow<List<SessionSummary>>(emptyList())
    val sessions: StateFlow<List<SessionSummary>> = _sessions.asStateFlow()
    private val _archivedMemoryCandidates = MutableStateFlow<List<ArchivedMemoryCandidate>>(emptyList())
    val archivedMemoryCandidates: StateFlow<List<ArchivedMemoryCandidate>> =
        _archivedMemoryCandidates.asStateFlow()
    private val _toastMessages = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val toastMessages = _toastMessages.asSharedFlow()
    private var currentRemoteTaskRepository: ProjectGitHubRepoRef? = null
    private val replayTriggerGate = ReplayTriggerGate()

    init {
        refreshSessions()
        viewModelScope.launch {
            sessionManager.pendingPromptReplayNotices.collect { notice ->
                _toastMessages.emit(notice)
            }
        }
        viewModelScope.launch {
            sessionManager.sessionLifecycleNotices.collect { notice ->
                _toastMessages.emit(notice)
            }
        }
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
        pendingImages: List<PendingImageAttachmentUi> = emptyList(),
        selectedSkills: List<GlobalSkill> = emptyList()
    ) {
        launchSendingOperation {
            sessionManager.clearLastAutoRouteDecision()
            val toastMessage = withContext(Dispatchers.IO) {
                sessionManager.sendMessage(text, mentionedFiles, pendingImages, selectedSkills)
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

    suspend fun acceptArchivedMemoryCandidate(
        sessionId: String,
        scope: ArchivedMemoryCandidateScope
    ): ArchivedMemoryCandidateMutationResult {
        val result = withContext(Dispatchers.IO) {
            sessionManager.acceptArchivedMemoryCandidate(sessionId, scope)
        }
        refreshSessions()
        return result
    }

    suspend fun dismissArchivedMemoryCandidate(sessionId: String): ArchivedMemoryCandidateMutationResult {
        val result = withContext(Dispatchers.IO) {
            sessionManager.dismissArchivedMemoryCandidate(sessionId)
        }
        refreshSessions()
        return result
    }

    suspend fun consumeArchivedMemoryCandidate(sessionId: String): ArchivedMemoryCandidateMutationResult {
        val result = withContext(Dispatchers.IO) {
            sessionManager.consumeArchivedMemoryCandidate(sessionId)
        }
        refreshSessions()
        return result
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

    suspend fun searchMentionFiles(query: String, limit: Int = 20): List<FileMentionUi> {
        val localResults = withContext(Dispatchers.IO) {
            sessionManager.searchProjectFiles(query, limit)
        }
        val remoteRepo = currentRemoteTaskRepository
        if (remoteRepo == null) {
            return localResults
        }
        val remoteResults = withContext(Dispatchers.IO) {
            searchRemoteTaskRepositoryMentions(
                query = query,
                repo = remoteRepo,
                limit = limit
            )
        }
        return (localResults + remoteResults)
            .distinctBy { it.path }
            .take(limit)
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

    fun updateCurrentTask(projectPath: String) {
        if (projectPath.isBlank()) return
        sessionManager.updateCurrentTask(projectPath)
        refreshSessions()
    }

    internal fun updateRemoteTaskRepositorySelection(repo: ProjectGitHubRepoRef?) {
        currentRemoteTaskRepository = repo
        sessionManager.updateRemoteTaskRepositoryContext(
            repositoryOwner = repo?.owner,
            repositoryName = repo?.repo,
            repositoryLabel = repo?.let { "${it.owner}/${it.repo}" },
            editable = repo != null
        )
        refreshSessions()
    }

    private suspend fun searchRemoteTaskRepositoryMentions(
        query: String,
        repo: ProjectGitHubRepoRef,
        limit: Int
    ): List<FileMentionUi> {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isBlank()) return emptyList()
        val activeConfig = config.value
        val token = activeConfig.githubToken.trim()
        if (token.isBlank()) return emptyList()
        val apiBaseUrl = activeConfig.githubApiBaseUrl
        val fileMatches = searchProjectGitHubFileNames(
            query = normalizedQuery,
            repo = repo,
            token = token,
            apiBaseUrl = apiBaseUrl
        )
        return fileMatches
            .asSequence()
            .mapNotNull { it.filePath?.takeIf(String::isNotBlank) }
            .distinct()
            .take(limit.coerceAtMost(REMOTE_MENTION_LIMIT))
            .mapIndexed { index, filePath ->
                val inlineContent = if (index < REMOTE_MENTION_CONTENT_LIMIT) {
                    loadProjectGitHubRemoteFile(
                        repo = repo,
                        path = filePath,
                        ref = "",
                        token = token,
                        apiBaseUrl = apiBaseUrl
                    ).file?.content
                } else {
                    null
                }
                FileMentionUi(
                    path = "github://${repo.owner}/${repo.repo}/$filePath",
                    displayPath = "${repo.owner}/${repo.repo}/$filePath",
                    inlineContent = inlineContent
                )
            }
            .toList()
    }

    fun setCurrentSessionGoal(goal: String) {
        if (goal.isBlank()) return
        sessionManager.setCurrentSessionGoal(goal)
        refreshSessions()
    }

    fun clearCurrentSessionGoal() {
        sessionManager.clearCurrentSessionGoal()
        refreshSessions()
    }

    fun switchProjectScope(scopePath: String?) {
        sessionManager.switchProjectScope(scopePath)
        refreshSessions()
    }

    fun loadSession(sessionId: String) {
        if (sessionManager.loadSession(sessionId)) {
            val currentSessionId = state.value.sessionId
            sessionManager.replayPendingPrompts()
            replayTriggerGate.markReplayHandled(currentSessionId)
            refreshSessions()
        }
    }

    fun replayPendingPrompts(): Boolean = sessionManager.replayPendingPrompts()

    fun onChatScreenAttached() {
        replayTriggerGate.onScreenAttached(state.value.sessionId)
    }

    fun onChatScreenActiveStateChanged(isScreenActive: Boolean) {
        val sessionId = state.value.sessionId
        if (!replayTriggerGate.shouldReplayOnScreenState(sessionId, isScreenActive)) return
        sessionManager.replayPendingPrompts()
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

    fun rollbackCheckpoint(
        checkpointId: String,
        scope: ConversationCheckpointScope = ConversationCheckpointScope.CODE
    ): Result<Int> {
        val result = sessionManager.rollbackCheckpoint(
            checkpointId = checkpointId,
            scope = scope
        )
        if (result.isSuccess) {
            refreshSessions()
        }
        return result
    }

    fun rollbackFileCheckpoint(checkpointId: String): Result<Int> {
        return rollbackCheckpoint(
            checkpointId = checkpointId,
            scope = ConversationCheckpointScope.CODE
        )
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

    fun submitPendingAskAnswers(answers: List<AskAnswerUi>): Boolean {
        val submitted = sessionManager.submitPendingAskAnswers(answers)
        if (submitted) {
            refreshSessions()
        }
        return submitted
    }

    fun dismissPendingAsk(): Boolean {
        val dismissed = sessionManager.dismissPendingAsk()
        if (dismissed) {
            refreshSessions()
        }
        return dismissed
    }

    fun deleteSession(sessionId: String) {
        if (sessionManager.deleteSession(sessionId)) {
            refreshSessions()
        }
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
        _archivedMemoryCandidates.value = sessionManager.listArchivedMemoryCandidates()
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
