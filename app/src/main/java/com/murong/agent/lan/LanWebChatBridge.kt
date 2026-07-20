package com.murong.agent.lan

import android.content.Context
import com.murong.agent.core.doctor.SensitiveDataSanitizer
import com.murong.agent.core.loop.ChatMessageUi
import com.murong.agent.core.loop.ChatSessionManager
import com.murong.agent.core.loop.ConversationStore
import com.murong.agent.core.loop.PendingApprovalUi
import com.murong.agent.core.loop.PersistedMessage
import com.murong.agent.core.loop.SessionState
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

internal object LanWebApprovalBinding {
    fun fingerprint(sessionId: String, pending: PendingApprovalUi): String {
        val canonical = listOf(
            sessionId,
            pending.toolName,
            pending.summary,
            pending.detail,
            pending.rawArgs,
            pending.riskLevel.name,
            pending.isReplayOnly.toString()
        ).joinToString("\u0000")
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    fun matches(
        currentSessionId: String,
        currentPending: PendingApprovalUi?,
        requestedSessionId: String,
        requestedApprovalId: String
    ): Boolean {
        if (currentSessionId != requestedSessionId || currentPending == null) return false
        return MessageDigest.isEqual(
            fingerprint(currentSessionId, currentPending).toByteArray(Charsets.UTF_8),
            requestedApprovalId.toByteArray(Charsets.UTF_8)
        )
    }
}

interface LanWebChatGateway {
    fun listSessions(): LanWebSessionsResponse
    fun sessionDetail(sessionId: String, liveLimit: Int = LanWebContract.MAX_HISTORY_MESSAGES): LanWebSessionDetail?
    fun liveState(): LanWebLiveState
    fun enqueueMessage(sessionId: String, rawMessage: String): Result<Unit>
    fun decideApproval(sessionId: String, approvalId: String, approve: Boolean): Result<Unit>
}

@Singleton
class LanWebChatBridge @Inject constructor(
    @param:ApplicationContext context: Context,
    private val sessionManager: ChatSessionManager
) : LanWebChatGateway {
    private val conversationStore = ConversationStore(context)
    private val executionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val submissionLock = Any()
    private val approvalLock = Any()
    private var messageSubmissionReserved = false
    private var decidingApprovalId: String? = null

    val state: StateFlow<SessionState> = sessionManager.state

    init {
        executionScope.launch {
            state.map { current ->
                current.pendingApproval?.let { LanWebApprovalBinding.fingerprint(current.sessionId, it) }
            }.distinctUntilChanged().collect { currentApprovalId ->
                synchronized(approvalLock) {
                    if (currentApprovalId == null || currentApprovalId != decidingApprovalId) {
                        decidingApprovalId = null
                    }
                }
            }
        }
    }

    override fun listSessions(): LanWebSessionsResponse {
        val current = state.value
        val persisted = sessionManager.listSessions().map { summary ->
            LanWebSessionSummary(
                id = summary.id,
                title = summary.title.ifBlank { "新对话" },
                updatedAt = summary.updatedAt,
                messageCount = summary.messageCount,
                providerId = summary.providerId,
                modelName = summary.modelName,
                projectLabel = summary.projectPath?.let(::projectLabel),
                active = summary.id == current.sessionId,
                processing = summary.id == current.sessionId && current.isProcessing,
                pendingApproval = summary.id == current.sessionId && current.pendingApproval != null
            )
        }.toMutableList()
        if (persisted.none { it.id == current.sessionId }) {
            persisted.add(
                0,
                LanWebSessionSummary(
                    id = current.sessionId,
                    title = current.sessionTitle.ifBlank { "新对话" },
                    updatedAt = current.messages.lastOrNull()?.timestamp ?: System.currentTimeMillis(),
                    messageCount = current.messages.size,
                    providerId = "active",
                    modelName = "当前配置",
                    projectLabel = current.projectPath?.let(::projectLabel),
                    active = true,
                    processing = current.isProcessing,
                    pendingApproval = current.pendingApproval != null
                )
            )
        }
        return LanWebSessionsResponse(
            activeSessionId = current.sessionId,
            sessions = persisted.sortedWith(
                compareByDescending<LanWebSessionSummary> { it.active }.thenByDescending { it.updatedAt }
            )
        )
    }

    override fun sessionDetail(sessionId: String, liveLimit: Int): LanWebSessionDetail? {
        if (!LanWebContract.sessionIdPattern.matches(sessionId)) return null
        val current = state.value
        if (current.sessionId == sessionId) {
            return current.toDetail(liveLimit)
        }
        val persisted = conversationStore.loadSession(sessionId) ?: return null
        return LanWebSessionDetail(
            id = persisted.id,
            title = persisted.title.ifBlank { "新对话" },
            active = false,
            processing = false,
            messages = persisted.messages.takeLast(liveLimit).map(::toWebMessage),
            pendingApproval = null
        )
    }

    override fun liveState(): LanWebLiveState = LanWebLiveState(
        session = state.value.toLiveState(),
        updatedAt = System.currentTimeMillis()
    )

    override fun enqueueMessage(sessionId: String, rawMessage: String): Result<Unit> = synchronized(submissionLock) {
        runCatching {
            require(LanWebContract.sessionIdPattern.matches(sessionId)) { "会话 ID 无效" }
            val message = LanWebSecurity.normalizeMessage(rawMessage) ?: error("消息为空、过长或包含非法控制字符")
            require(!messageSubmissionReserved) { "已有 Web 消息正在提交" }
            var current = state.value
            require(!current.isProcessing) { "当前会话正在运行，请等待本轮结束" }
            if (current.sessionId != sessionId) {
                require(current.pendingApproval == null && current.pendingAskRequest == null) {
                    "当前会话有待处理交互，不能切换会话"
                }
                require(sessionManager.loadSession(sessionId)) { "会话不存在，或当前状态不允许切换" }
                current = state.value
                require(current.sessionId == sessionId && !current.isProcessing) { "会话切换失败" }
            }
            messageSubmissionReserved = true
            val accepted = CompletableDeferred<Unit>()
            val executionJob = executionScope.launch {
                try {
                    sessionManager.sendMessage(
                        text = message,
                        onUserMessageAccepted = {
                            accepted.complete(Unit)
                        }
                    )
                    if (!accepted.isCompleted) {
                        accepted.completeExceptionally(
                            IllegalStateException("Murong 核心未接收消息，请检查模型配置或登录状态")
                        )
                    }
                } catch (error: Throwable) {
                    if (!accepted.isCompleted) accepted.completeExceptionally(error)
                } finally {
                    synchronized(submissionLock) {
                        messageSubmissionReserved = false
                    }
                }
            }
            try {
                runBlocking {
                    withTimeout(CORE_MESSAGE_ACCEPT_TIMEOUT_MILLIS) {
                        accepted.await()
                    }
                }
            } catch (error: Throwable) {
                // Do not join while holding submissionLock: the coroutine releases its
                // reservation from finally under the same lock.
                executionJob.cancel()
                throw error
            }
            Unit
        }
    }

    override fun decideApproval(
        sessionId: String,
        approvalId: String,
        approve: Boolean
    ): Result<Unit> = synchronized(approvalLock) {
        runCatching {
            val current = state.value
            require(current.sessionId == sessionId) { "审批所属会话已不是当前会话" }
            val pending = current.pendingApproval ?: error("当前没有待审批请求")
            val expectedId = LanWebApprovalBinding.fingerprint(sessionId, pending)
            require(
                LanWebApprovalBinding.matches(
                    currentSessionId = current.sessionId,
                    currentPending = pending,
                    requestedSessionId = sessionId,
                    requestedApprovalId = approvalId
                )
            ) { "审批请求已变化或已过期" }
            require(decidingApprovalId != expectedId) { "该审批决定正在处理" }
            if (approve) require(!pending.isReplayOnly) { "恢复的历史审批仅供查看，不能批准" }
            decidingApprovalId = expectedId
            val accepted = if (approve) {
                sessionManager.approvePendingTool()
            } else {
                sessionManager.rejectPendingTool()
            }
            if (!accepted) decidingApprovalId = null
            require(accepted) { "核心审批状态已变化，请刷新后重试" }
        }
    }

    private fun SessionState.toDetail(limit: Int): LanWebSessionDetail = LanWebSessionDetail(
        id = sessionId,
        title = sessionTitle.ifBlank { "新对话" },
        active = true,
        processing = isProcessing,
        error = error?.let(::sanitize).orEmpty().takeIf { it.isNotBlank() },
        messages = messages.takeLast(limit).map(::toWebMessage),
        pendingApproval = pendingApproval?.toWebApproval(sessionId)
    )

    private fun SessionState.toLiveState(): LanWebLiveSessionState = LanWebLiveSessionState(
        id = sessionId,
        title = sessionTitle.ifBlank { "新对话" },
        processing = isProcessing,
        error = error?.let(::sanitize)?.take(2_000),
        lastMessage = messages.lastOrNull()?.let { toWebMessage(it, LIVE_EVENT_MESSAGE_CHAR_LIMIT) },
        pendingApproval = pendingApproval?.toWebApproval(sessionId)
    )

    private fun PendingApprovalUi.toWebApproval(sessionId: String): LanWebApproval = LanWebApproval(
        approvalId = LanWebApprovalBinding.fingerprint(sessionId, this),
        sessionId = sessionId,
        toolName = sanitize(toolName).take(160),
        summary = sanitize(summary).take(2_000),
        detail = sanitize(detail).take(8_000),
        arguments = sanitize(rawArgs).take(12_000),
        riskLevel = riskLevel.name,
        explanationLabel = explanationLabel?.let(::sanitize)?.take(500),
        explanationDetail = explanationDetail?.let(::sanitize)?.take(4_000),
        approveEnabled = !isReplayOnly
    )

    private fun toWebMessage(
        message: ChatMessageUi,
        contentLimit: Int = LanWebContract.MAX_MESSAGE_RESPONSE_CHARS
    ) = LanWebMessage(
        id = message.id,
        role = message.role,
        content = sanitize(message.content).take(contentLimit),
        streaming = message.isStreaming,
        timestamp = message.timestamp,
        attachmentNames = message.imageAttachments.map { sanitize(it.fileName).take(255) }
    )

    private fun toWebMessage(message: PersistedMessage) = LanWebMessage(
        id = message.id,
        role = message.role,
        content = sanitize(message.content).take(LanWebContract.MAX_MESSAGE_RESPONSE_CHARS),
        streaming = false,
        timestamp = message.timestamp,
        attachmentNames = message.imageAttachments.map { sanitize(it.fileName).take(255) }
    )

    private fun sanitize(value: String): String = SensitiveDataSanitizer.sanitizeText(
        value = value,
        redactPaths = false
    )

    private fun projectLabel(path: String): String = File(path).name.takeIf { it.isNotBlank() } ?: "项目"

    private companion object {
        const val CORE_MESSAGE_ACCEPT_TIMEOUT_MILLIS = 30_000L
        const val LIVE_EVENT_MESSAGE_CHAR_LIMIT = 20_000
    }
}
