package com.murong.agent.lan

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Singleton
class LanWebDesktopAgentBridge internal constructor(
    private val mirrorStore: LanWebDesktopAgentMirrorStore?,
    private val syncKeyStore: LanWebSyncKeyStore?
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(
        LanWebDesktopAgentMirrorStore(context),
        AndroidLanWebSyncKeyStore(context)
    )

    internal constructor(mirrorStore: LanWebDesktopAgentMirrorStore?) : this(mirrorStore, null)

    internal constructor() : this(null, null)

    private data class Registration(
        val clientId: String,
        val nodeSessionId: String,
        val sourcePlatform: String,
        val sourceArchitecture: String,
        val protocolVersion: Int,
        val controlAllowed: Boolean,
        val lastSeenAt: Long
    )

    private data class PendingCommand(
        val clientId: String,
        val nodeSessionId: String,
        val operation: String,
        val sessionId: String?,
        val response: CompletableDeferred<LanWebDesktopAgentCommandResultRequest>
    )

    private val lock = Any()
    private val pending = ConcurrentHashMap<String, PendingCommand>()
    private val json = Json { encodeDefaults = true; explicitNulls = false }
    private val mutableCommands = MutableSharedFlow<LanWebDesktopAgentCommandDispatch>(extraBufferCapacity = 32)
    private val initialMirror = mirrorStore?.loadLatest()?.takeIf { mirror ->
        runCatching { validateSnapshot(mirror.snapshot) }.isSuccess
    }
    private val mutableState = MutableStateFlow(
        initialMirror?.toOfflineStatus() ?: LanWebDesktopAgentStatusResponse()
    )
    private var registration: Registration? = null
    private var displayedClientId: String? = initialMirror?.clientId

    internal var nowProvider: () -> Long = System::currentTimeMillis

    val commands: SharedFlow<LanWebDesktopAgentCommandDispatch> = mutableCommands.asSharedFlow()
    val state: StateFlow<LanWebDesktopAgentStatusResponse> = mutableState.asStateFlow()

    fun register(clientId: String, request: LanWebDesktopAgentRegisterRequest): Result<LanWebDesktopAgentStatusResponse> =
        runCatching {
            require(LanWebContract.requestIdPattern.matches(request.nodeSessionId)) { "桌面 Agent 会话 ID 无效" }
            require(LanWebContract.requestIdPattern.matches(request.requestId)) { "request_id 无效" }
            require(request.protocolVersion in 1..DESKTOP_AGENT_PROTOCOL_VERSION) { "桌面任务协议版本不受支持" }
            val sourcePlatform = normalizeDesktopPlatform(request.sourcePlatform)
            val sourceArchitecture = normalizeDesktopArchitecture(request.sourceArchitecture)
            synchronized(lock) {
                expireLocked()
                val current = registration
                if (current != null && current.clientId != clientId) {
                    error("另一个已配对客户端正在共享桌面任务")
                }
                if (current != null && current.nodeSessionId != request.nodeSessionId) {
                    failPendingLocked("desktop_agent_replaced", "桌面 Agent 已重新连接")
                }
                val now = nowProvider()
                registration = Registration(
                    clientId = clientId,
                    nodeSessionId = request.nodeSessionId,
                    sourcePlatform = sourcePlatform,
                    sourceArchitecture = sourceArchitecture,
                    protocolVersion = request.protocolVersion,
                    controlAllowed = request.controlAllowed,
                    lastSeenAt = now
                )
                displayedClientId = clientId
                val cachedSnapshot = mirrorStore?.loadLatest()
                    ?.takeIf { it.clientId == clientId }
                    ?.takeIf { runCatching { validateSnapshot(it.snapshot) }.isSuccess }
                    ?.takeIf {
                        it.snapshot.sourcePlatform == sourcePlatform &&
                            it.snapshot.sourceArchitecture == sourceArchitecture
                    }
                    ?.snapshot
                    ?.copy(
                        nodeSessionId = request.nodeSessionId,
                        sequence = 0L,
                        generatedAt = now,
                        sessionUpdates = emptyList()
                    )
                mutableState.value = LanWebDesktopAgentStatusResponse(
                    connected = true,
                    nodeSessionId = request.nodeSessionId,
                    sourcePlatform = sourcePlatform,
                    sourceArchitecture = sourceArchitecture,
                    controlAllowed = request.controlAllowed,
                    lastSeenAt = now,
                    snapshot = cachedSnapshot
                )
                mutableState.value
            }
        }

    fun publishSnapshot(
        clientId: String,
        snapshot: LanWebDesktopAgentSnapshotRequest
    ): Result<LanWebDesktopAgentStatusResponse> = runCatching {
        validateSnapshot(snapshot)
        synchronized(lock) {
            expireLocked()
            val current = registration ?: error("桌面 Agent 尚未注册")
            require(current.clientId == clientId && current.nodeSessionId == snapshot.nodeSessionId) {
                "桌面 Agent 会话已失效"
            }
            require(
                current.sourcePlatform == snapshot.sourcePlatform &&
                    current.sourceArchitecture == snapshot.sourceArchitecture
            ) { "桌面 Agent 系统或架构与注册信息不一致" }
            require(
                current.protocolVersion >= 3 ||
                    (snapshot.activeSession?.workflowPlan == null && snapshot.sessionUpdates.none { it.workflowPlan != null })
            ) { "当前桌面任务协议版本不允许发送规范计划" }
            val previous = mutableState.value.snapshot
            require(previous == null || snapshot.sequence >= previous.sequence) { "桌面任务快照序号已过期" }
            val now = nowProvider()
            val normalizedSnapshot = mirrorStore?.saveSnapshot(clientId, snapshot)?.snapshot
                ?: snapshot.copy(sessionUpdates = emptyList())
            registration = current.copy(lastSeenAt = now)
            displayedClientId = clientId
            mutableState.value = LanWebDesktopAgentStatusResponse(
                connected = true,
                nodeSessionId = current.nodeSessionId,
                sourcePlatform = current.sourcePlatform,
                sourceArchitecture = current.sourceArchitecture,
                controlAllowed = current.controlAllowed,
                lastSeenAt = now,
                snapshot = normalizedSnapshot
            )
            mutableState.value
        }
    }

    fun status(clientId: String? = null): LanWebDesktopAgentStatusResponse = synchronized(lock) {
        expireLocked()
        val current = registration
        if (clientId != null && current?.clientId != clientId && displayedClientId != clientId) {
            LanWebDesktopAgentStatusResponse()
        } else {
            mutableState.value
        }
    }

    /** Selects a durable phone-side mirror immediately; a connected caller may refresh it afterwards. */
    fun selectCachedSession(sessionId: String): Boolean = synchronized(lock) {
        if (!LanWebContract.sessionIdPattern.matches(sessionId)) return@synchronized false
        val clientId = registration?.clientId ?: displayedClientId ?: return@synchronized false
        val mirror = mirrorStore?.selectSession(clientId, sessionId) ?: return@synchronized false
        val active = mirror.snapshot.activeSession ?: return@synchronized false
        runCatching { validateSession(active) }.getOrElse { return@synchronized false }
        val current = registration
        val snapshot = if (current == null) {
            mirror.snapshot
        } else {
            mirror.snapshot.copy(nodeSessionId = current.nodeSessionId, sessionUpdates = emptyList())
        }
        displayedClientId = clientId
        mutableState.value = LanWebDesktopAgentStatusResponse(
            connected = current != null,
            nodeSessionId = current?.nodeSessionId,
            sourcePlatform = current?.sourcePlatform ?: mirror.snapshot.sourcePlatform,
            sourceArchitecture = current?.sourceArchitecture ?: mirror.snapshot.sourceArchitecture,
            controlAllowed = current?.controlAllowed == true,
            lastSeenAt = current?.lastSeenAt ?: mirror.savedAt,
            snapshot = snapshot
        )
        true
    }

    suspend fun command(
        operation: String,
        sessionId: String? = null,
        content: String? = null,
        approvalId: String? = null,
        approve: Boolean? = null,
        askId: String? = null,
        askAnswers: List<LanWebDesktopAgentAskAnswer> = emptyList(),
        dismissAsk: Boolean = false,
        handoffToken: String? = null,
        portableSession: String? = null
    ): Result<LanWebDesktopAgentCommandResultRequest> = runCatching {
        val normalizedOperation = operation.trim().lowercase()
        require(normalizedOperation in SUPPORTED_OPERATIONS) { "不支持的桌面任务操作" }
        val current = synchronized(lock) {
            expireLocked()
            registration ?: error("电脑端 Murong Desktop 未连接")
        }
        if (normalizedOperation in CONTROL_OPERATIONS) {
            require(current.controlAllowed) { "电脑端未开启手机控制桌面任务" }
        }
        if (normalizedOperation != "refresh") {
            require(!sessionId.isNullOrBlank() && LanWebContract.sessionIdPattern.matches(sessionId)) { "桌面任务 ID 无效" }
        }
        if (normalizedOperation == "send_message") {
            require(!content.isNullOrBlank() && content.length <= LanWebContract.MAX_MESSAGE_CHARS) { "消息为空或过长" }
        }
        if (normalizedOperation == "approval") {
            require(!approvalId.isNullOrBlank() && LanWebContract.requestIdPattern.matches(approvalId)) { "审批 ID 无效" }
            require(approve != null) { "审批决定缺失" }
        }
        if (normalizedOperation == "ask") {
            require(!askId.isNullOrBlank() && LanWebContract.requestIdPattern.matches(askId)) { "问题 ID 无效" }
            require(dismissAsk xor askAnswers.isNotEmpty()) { "应提交完整答案或跳过问题" }
            require(askAnswers.size <= MAX_ASK_QUESTIONS) { "问题答案过多" }
            askAnswers.forEach { answer ->
                require(answer.questionId.length in 1..MAX_ASK_QUESTION_ID_CHARS) { "问题答案 ID 无效" }
                require(answer.selectedOptions.size in 1..MAX_ASK_OPTIONS) { "问题选择数量无效" }
                require(answer.selectedOptions.all { it.isNotBlank() && it.length <= MAX_ASK_CUSTOM_CHARS }) { "问题答案为空或过长" }
            }
        }
        if (normalizedOperation == "return_handoff") {
            requireValidHandoffToken(handoffToken)
            requireValidPortableHandoff(portableSession)
        }
        if (normalizedOperation == "abort_handoff") {
            requireValidHandoffToken(handoffToken)
        }
        val requestId = randomRequestId("desktop-command")
        val handoffEnvelope = if (normalizedOperation in HANDOFF_OPERATIONS) {
            val key = syncKeyStore?.read(current.clientId)
                ?: error("当前电脑配对没有安全同步密钥，请清除配对后重新连接")
            try {
                if (normalizedOperation == "return_handoff" || normalizedOperation == "abort_handoff") {
                    val payload = DesktopHandoffPayload(
                        handoffToken = requireNotNull(handoffToken),
                        portableSession = portableSession
                    )
                    LanWebDeviceSyncCrypto.encrypt(
                        key = key,
                        requestId = requestId,
                        issuedAt = nowProvider(),
                        direction = LanWebDeviceSyncCrypto.DESKTOP_HANDOFF_TO_DESKTOP,
                        plaintext = json.encodeToString(payload)
                    )
                } else {
                    null
                }
            } finally {
                key.fill(0)
            }
        } else {
            null
        }
        val deferred = CompletableDeferred<LanWebDesktopAgentCommandResultRequest>()
        pending[requestId] = PendingCommand(
            current.clientId,
            current.nodeSessionId,
            normalizedOperation,
            sessionId,
            deferred
        )
        val emitted = mutableCommands.tryEmit(
            LanWebDesktopAgentCommandDispatch(
                targetClientId = current.clientId,
                event = LanWebDesktopAgentCommandEvent(
                    nodeSessionId = current.nodeSessionId,
                    requestId = requestId,
                    operation = normalizedOperation,
                    sessionId = sessionId,
                    content = content,
                    approvalId = approvalId,
                    approve = approve,
                    askId = askId,
                    askAnswers = askAnswers,
                    dismissAsk = dismissAsk,
                    handoffEnvelope = handoffEnvelope,
                    expiresAt = nowProvider() + COMMAND_TIMEOUT_MILLIS
                )
            )
        )
        if (!emitted) {
            pending.remove(requestId)
            error("桌面任务命令队列繁忙")
        }
        try {
            withTimeout(COMMAND_TIMEOUT_MILLIS) { deferred.await() }
        } finally {
            pending.remove(requestId)
        }
    }

    fun complete(clientId: String, result: LanWebDesktopAgentCommandResultRequest): Result<Unit> = runCatching {
        require(LanWebContract.requestIdPattern.matches(result.requestId)) { "request_id 无效" }
        val candidate = pending[result.requestId] ?: error("桌面任务命令已完成或过期")
        require(candidate.clientId == clientId && candidate.nodeSessionId == result.nodeSessionId) {
            "桌面任务命令不属于当前客户端"
        }
        result.snapshot?.let(::validateSnapshot)
        result.session?.let(::validateSession)
        require(result.handoffToken == null && result.portableSession == null) {
            "桌面接管能力不得通过明文响应传输"
        }
        val normalizedResult = if (candidate.operation == "begin_handoff" && result.success) {
            val envelope = result.handoffEnvelope ?: error("电脑端未返回加密接管内容，请更新并重新配对")
            require(envelope.requestId == result.requestId) { "跨端接管响应不属于本次请求" }
            require(envelope.direction == LanWebDeviceSyncCrypto.DESKTOP_HANDOFF_TO_ANDROID) {
                "跨端接管响应方向无效"
            }
            val now = nowProvider()
            require(envelope.issuedAt >= now - HANDOFF_CLOCK_WINDOW_MILLIS && envelope.issuedAt <= now + 30_000L) {
                "跨端接管响应已过期"
            }
            val key = syncKeyStore?.read(candidate.clientId)
                ?: error("当前电脑配对没有安全同步密钥，请清除配对后重新连接")
            val payload = try {
                json.decodeFromString<DesktopHandoffPayload>(LanWebDeviceSyncCrypto.decrypt(key, envelope))
            } finally {
                key.fill(0)
            }
            requireValidHandoffToken(payload.handoffToken)
            requireValidPortableHandoff(payload.portableSession)
            markLocalExecutionOwner(candidate.sessionId, "android")
            result.copy(
                handoffToken = payload.handoffToken,
                portableSession = payload.portableSession,
                handoffEnvelope = null
            )
        } else {
            require(result.handoffEnvelope == null) { "桌面任务返回了未请求的加密接管内容" }
            result
        }
        if (result.snapshot != null) publishSnapshot(clientId, result.snapshot).getOrThrow()
        if (result.session != null) {
            synchronized(lock) {
                val current = mutableState.value
                val snapshot = current.snapshot
                if (snapshot != null) {
                    require(snapshot.sessions.any { it.id == result.session.id }) { "返回的桌面任务不在任务列表中" }
                    val clientId = registration?.clientId
                    val persisted = clientId?.let { id ->
                        mirrorStore?.saveSession(id, result.nodeSessionId, result.session)
                    }
                    mutableState.value = current.copy(
                        snapshot = persisted?.snapshot
                            ?.copy(nodeSessionId = result.nodeSessionId, sessionUpdates = emptyList())
                            ?: snapshot.copy(activeSession = result.session, sessionUpdates = emptyList())
                    )
                }
            }
        }
        check(candidate.response.complete(normalizedResult)) { "桌面任务命令已完成" }
    }

    fun disconnect(clientId: String, nodeSessionId: String? = null) = synchronized(lock) {
        val current = registration ?: return@synchronized
        if (current.clientId != clientId) return@synchronized
        if (nodeSessionId != null && current.nodeSessionId != nodeSessionId) return@synchronized
        failPendingLocked("desktop_agent_disconnected", "电脑端 Murong Desktop 已断开")
        registration = null
        mutableState.value = offlineStatusFor(clientId)
    }

    fun shutdown() = synchronized(lock) {
        failPendingLocked("desktop_agent_stopped", "电脑节点服务已停止")
        val clientId = registration?.clientId ?: displayedClientId
        registration = null
        mutableState.value = offlineStatusFor(clientId)
    }

    fun forgetClient(clientId: String) = synchronized(lock) {
        if (registration?.clientId == clientId) {
            failPendingLocked("desktop_agent_revoked", "电脑端 Murong Desktop 配对已撤销")
            registration = null
        }
        mirrorStore?.clear(clientId)
        if (displayedClientId == clientId) {
            displayedClientId = null
            mutableState.value = LanWebDesktopAgentStatusResponse()
        }
    }

    fun forgetAllClients() = synchronized(lock) {
        failPendingLocked("desktop_agent_revoked", "电脑端 Murong Desktop 配对已撤销")
        registration = null
        displayedClientId = null
        mirrorStore?.clear()
        mutableState.value = LanWebDesktopAgentStatusResponse()
    }

    private fun expireLocked() {
        val current = registration ?: return
        if (nowProvider() - current.lastSeenAt <= EXPIRES_AFTER_MILLIS) return
        failPendingLocked("desktop_agent_expired", "电脑端 Murong Desktop 心跳已过期")
        val clientId = current.clientId
        registration = null
        mutableState.value = offlineStatusFor(clientId)
    }

    private fun offlineStatusFor(clientId: String?): LanWebDesktopAgentStatusResponse {
        val mirror = mirrorStore?.loadLatest()?.takeIf { clientId == null || it.clientId == clientId }
        if (mirror == null || runCatching { validateSnapshot(mirror.snapshot) }.isFailure) {
            return LanWebDesktopAgentStatusResponse()
        }
        displayedClientId = mirror.clientId
        return mirror.toOfflineStatus()
    }

    private fun LanWebDesktopAgentMirror.toOfflineStatus() = LanWebDesktopAgentStatusResponse(
        connected = false,
        nodeSessionId = null,
        sourcePlatform = snapshot.sourcePlatform,
        sourceArchitecture = snapshot.sourceArchitecture,
        controlAllowed = false,
        lastSeenAt = savedAt,
        snapshot = snapshot.copy(sessionUpdates = emptyList())
    )

    private fun failPendingLocked(code: String, message: String) {
        pending.entries.removeIf { (requestId, command) ->
            command.response.complete(
                LanWebDesktopAgentCommandResultRequest(
                    nodeSessionId = command.nodeSessionId,
                    requestId = requestId,
                    success = false,
                    errorCode = code,
                    errorMessage = message
                )
            )
            true
        }
    }

    private fun validateSnapshot(snapshot: LanWebDesktopAgentSnapshotRequest) {
        require(LanWebContract.requestIdPattern.matches(snapshot.nodeSessionId)) { "桌面 Agent 会话 ID 无效" }
        require(snapshot.sourcePlatform == normalizeDesktopPlatform(snapshot.sourcePlatform)) { "桌面 Agent 系统无效" }
        require(snapshot.sourceArchitecture == normalizeDesktopArchitecture(snapshot.sourceArchitecture)) {
            "桌面 Agent 架构无效"
        }
        require(snapshot.sequence >= 0L) { "桌面任务快照序号无效" }
        require(snapshot.sessions.size <= MAX_SESSIONS) { "桌面任务数量过多" }
        val sessionIds = HashSet<String>(snapshot.sessions.size)
        snapshot.sessions.forEach { summary ->
            require(LanWebContract.sessionIdPattern.matches(summary.id)) { "桌面任务 ID 无效" }
            require(sessionIds.add(summary.id)) { "桌面任务 ID 重复" }
            require(summary.title.length <= MAX_TITLE_CHARS && summary.messageCount in 0..MAX_MESSAGE_COUNT) {
                "桌面任务摘要无效"
            }
            require(summary.executionOwner in EXECUTION_OWNERS) { "桌面任务执行权状态无效" }
            require(summary.handoffStartedAt == null || summary.handoffStartedAt > 0L) { "桌面任务接管时间无效" }
            require(summary.executionOwner == "android" || summary.handoffStartedAt == null) { "桌面任务接管状态不一致" }
        }
        snapshot.activeSession?.let { session ->
            require(sessionIds.contains(session.id)) { "打开的桌面任务不在任务列表中" }
            validateSession(session)
            val summary = snapshot.sessions.first { it.id == session.id }
            require(
                summary.executionOwner == session.executionOwner &&
                    summary.handoffStartedAt == session.handoffStartedAt &&
                    summary.pendingQuestion == (session.pendingAsk != null)
            ) { "打开的桌面任务执行权与摘要不一致" }
        }
        require(snapshot.sessionUpdates.size <= MAX_SESSION_UPDATES) { "桌面任务增量过多" }
        val updateIds = HashSet<String>(snapshot.sessionUpdates.size)
        snapshot.sessionUpdates.forEach { session ->
            require(sessionIds.contains(session.id)) { "增量桌面任务不在任务列表中" }
            require(updateIds.add(session.id)) { "增量桌面任务 ID 重复" }
            validateSession(session)
            val summary = snapshot.sessions.first { it.id == session.id }
            require(
                summary.executionOwner == session.executionOwner &&
                    summary.handoffStartedAt == session.handoffStartedAt &&
                    summary.pendingQuestion == (session.pendingAsk != null)
            ) { "增量桌面任务执行权与摘要不一致" }
        }
        snapshot.activeSession?.let { active ->
            require(active.id !in updateIds) { "打开的桌面任务不应重复出现在增量中" }
        }
    }

    private fun validateSession(session: LanWebDesktopAgentTaskDetail) {
        require(LanWebContract.sessionIdPattern.matches(session.id)) { "桌面任务 ID 无效" }
        require(session.title.length <= MAX_TITLE_CHARS) { "桌面任务标题过长" }
        require(session.messages.size <= MAX_MESSAGES) { "桌面任务消息过多" }
        require(session.messageCount in session.messages.size..MAX_MESSAGE_COUNT) { "桌面任务消息总数无效" }
        require(session.executionOwner in EXECUTION_OWNERS) { "桌面任务执行权状态无效" }
        require(session.handoffStartedAt == null || session.handoffStartedAt > 0L) { "桌面任务接管时间无效" }
        require(session.executionOwner == "android" || session.handoffStartedAt == null) { "桌面任务接管状态不一致" }
        val messageIds = HashSet<String>(session.messages.size)
        session.messages.forEach { message ->
            require(message.id.isNotBlank() && message.id.length <= MAX_MESSAGE_ID_CHARS && messageIds.add(message.id)) {
                "桌面任务消息 ID 无效或重复"
            }
            require(message.role in MESSAGE_ROLES) { "桌面任务消息角色无效" }
            require(message.content.length <= LanWebContract.MAX_MESSAGE_RESPONSE_CHARS) { "桌面任务消息过长" }
        }
        session.pendingApproval?.let { approval ->
            require(approval.sessionId == session.id) { "审批不属于当前桌面任务" }
            require(LanWebContract.requestIdPattern.matches(approval.id)) { "审批 ID 无效" }
            require(approval.detail.length <= MAX_APPROVAL_DETAIL_CHARS) { "审批详情过长" }
        }
        session.pendingAsk?.let { request ->
            require(request.sessionId == session.id) { "问题不属于当前桌面任务" }
            require(LanWebContract.requestIdPattern.matches(request.id)) { "问题 ID 无效" }
            require(request.createdAt > 0L) { "问题创建时间无效" }
            require(request.questions.size in 1..MAX_ASK_QUESTIONS) { "问题数量无效" }
            val questionIds = HashSet<String>(request.questions.size)
            request.questions.forEach { question ->
                require(question.id.length in 1..MAX_ASK_QUESTION_ID_CHARS && questionIds.add(question.id)) { "问题 ID 无效或重复" }
                require(question.header.length <= MAX_ASK_HEADER_CHARS) { "问题标题过长" }
                require(question.question.isNotBlank() && question.question.length <= MAX_ASK_QUESTION_CHARS) { "问题内容为空或过长" }
                require(question.options.size in 2..MAX_ASK_OPTIONS) { "问题选项数量无效" }
                val labels = HashSet<String>(question.options.size)
                question.options.forEach { option ->
                    require(option.label.isNotBlank() && option.label.length <= MAX_ASK_LABEL_CHARS) { "问题选项为空或过长" }
                    require(labels.add(option.label.lowercase())) { "问题选项重复" }
                    require(option.description.length <= MAX_ASK_DESCRIPTION_CHARS) { "问题选项说明过长" }
                }
            }
        }
        session.workflowPlan?.let(::validateWorkflowPlan)
    }

    private fun validateWorkflowPlan(plan: LanWebDesktopAgentWorkflowPlan) {
        require(plan.id.isNotBlank() && plan.id.length <= MAX_WORKFLOW_PLAN_ID_CHARS) { "规范计划 ID 无效" }
        require(plan.summary.isNotBlank() && plan.summary.unicodeLength() <= MAX_WORKFLOW_PLAN_SUMMARY_CHARS) { "规范计划摘要无效" }
        require(plan.steps.size in 1..MAX_WORKFLOW_PLAN_STEPS) { "规范计划步骤数量无效" }
        require(plan.steps.all { it.isNotBlank() && it.unicodeLength() <= MAX_WORKFLOW_PLAN_STEP_CHARS }) { "规范计划步骤无效" }
        require(plan.currentStepIndex in 0..plan.steps.size) { "规范计划进度无效" }
        require(plan.status in WORKFLOW_PLAN_STATUSES) { "规范计划状态无效" }
        require(plan.nextStepHint.unicodeLength() <= MAX_WORKFLOW_PLAN_HINT_CHARS) { "规范计划下一步提示过长" }
        require(plan.createdAt > 0L && plan.updatedAt >= plan.createdAt) { "规范计划时间无效" }
        when (plan.status) {
            "ready" -> require(
                plan.currentStepIndex == 0 && plan.stepSignOffs.isEmpty() && plan.executionStartedAt == null
            ) { "待执行规范计划包含执行进度" }
            "executing", "blocked" -> require(
                plan.currentStepIndex < plan.steps.size && plan.executionStartedAt != null && plan.executionStartedAt >= plan.createdAt
            ) { "未完成规范计划状态不一致" }
            "completed" -> require(
                plan.currentStepIndex == plan.steps.size && plan.executionStartedAt != null && plan.executionStartedAt >= plan.createdAt
            ) { "已完成规范计划状态不一致" }
        }
        require(plan.stepSignOffs.size == plan.currentStepIndex) { "规范计划签收数量与进度不一致" }
        plan.stepSignOffs.forEachIndexed { index, signOff ->
            require(signOff.stepIndex == index) { "规范计划签收顺序无效" }
            require(signOff.resultSummary.isNotBlank() && signOff.resultSummary.unicodeLength() <= MAX_WORKFLOW_PLAN_RESULT_CHARS) {
                "规范计划签收摘要无效"
            }
            require(signOff.matchedEvidence in 1..MAX_WORKFLOW_PLAN_EVIDENCE && signOff.totalEvidence == signOff.matchedEvidence) {
                "规范计划签收证据数量无效"
            }
            require(signOff.matchedToolNames.size <= MAX_WORKFLOW_PLAN_EVIDENCE) { "规范计划签收工具过多" }
            require(signOff.matchedToolNames.all { it.isNotBlank() && it.unicodeLength() <= MAX_WORKFLOW_PLAN_TOOL_CHARS }) {
                "规范计划签收工具无效"
            }
            require(
                signOff.signedOffAt >= requireNotNull(plan.executionStartedAt) && signOff.signedOffAt <= plan.updatedAt
            ) { "规范计划签收时间无效" }
        }
    }

    private fun String.unicodeLength(): Int = codePointCount(0, length)

    private fun requireValidHandoffToken(token: String?) {
        require(token != null && HANDOFF_TOKEN.matches(token)) { "跨端接管令牌无效" }
    }

    private fun requireValidPortableHandoff(document: String?) {
        require(!document.isNullOrBlank()) { "跨端接管内容为空" }
        require(document.toByteArray(Charsets.UTF_8).size <= LanWebContract.MAX_DESKTOP_HANDOFF_BYTES) {
            "跨端接管内容超过 1.5 MiB"
        }
    }

    private fun markLocalExecutionOwner(sessionId: String?, owner: String) = synchronized(lock) {
        if (sessionId == null) return@synchronized
        val current = mutableState.value
        val snapshot = current.snapshot ?: return@synchronized
        val startedAt = if (owner == "android") nowProvider() else null
        val patched = snapshot.copy(
            sessions = snapshot.sessions.map { summary ->
                if (summary.id == sessionId) {
                    summary.copy(executionOwner = owner, handoffStartedAt = startedAt)
                } else {
                    summary
                }
            },
            activeSession = snapshot.activeSession?.let { detail ->
                if (detail.id == sessionId) {
                    detail.copy(executionOwner = owner, handoffStartedAt = startedAt)
                } else {
                    detail
                }
            },
            sessionUpdates = snapshot.sessionUpdates.map { detail ->
                if (detail.id == sessionId) {
                    detail.copy(executionOwner = owner, handoffStartedAt = startedAt)
                } else {
                    detail
                }
            }
        )
        mutableState.value = current.copy(snapshot = patched)
    }

    private fun randomRequestId(prefix: String): String =
        "$prefix-${java.util.UUID.randomUUID().toString().replace("-", "")}"

    companion object {
        private const val EXPIRES_AFTER_MILLIS = 30_000L
        private const val COMMAND_TIMEOUT_MILLIS = 20_000L
        private const val HANDOFF_CLOCK_WINDOW_MILLIS = 2 * 60_000L
        private const val MAX_SESSIONS = 500
        private const val MAX_MESSAGES = 200
        private const val MAX_MESSAGE_COUNT = 10_000_000
        private const val MAX_SESSION_UPDATES = 8
        private const val MAX_TITLE_CHARS = 200
        private const val MAX_MESSAGE_ID_CHARS = 200
        private const val MAX_APPROVAL_DETAIL_CHARS = 2_000
        private const val DESKTOP_AGENT_PROTOCOL_VERSION = 3
        private const val MAX_ASK_QUESTIONS = 4
        private const val MAX_ASK_OPTIONS = 4
        private const val MAX_ASK_QUESTION_ID_CHARS = 32
        private const val MAX_ASK_HEADER_CHARS = 32
        private const val MAX_ASK_QUESTION_CHARS = 500
        private const val MAX_ASK_LABEL_CHARS = 120
        private const val MAX_ASK_DESCRIPTION_CHARS = 300
        private const val MAX_ASK_CUSTOM_CHARS = 500
        private const val MAX_WORKFLOW_PLAN_ID_CHARS = 128
        private const val MAX_WORKFLOW_PLAN_SUMMARY_CHARS = 1_000
        private const val MAX_WORKFLOW_PLAN_STEPS = 8
        private const val MAX_WORKFLOW_PLAN_STEP_CHARS = 500
        private const val MAX_WORKFLOW_PLAN_HINT_CHARS = 1_000
        private const val MAX_WORKFLOW_PLAN_RESULT_CHARS = 2_000
        private const val MAX_WORKFLOW_PLAN_EVIDENCE = 8
        private const val MAX_WORKFLOW_PLAN_TOOL_CHARS = 100
        private val WORKFLOW_PLAN_STATUSES = setOf("ready", "executing", "blocked", "completed")
        private val SUPPORTED_OPERATIONS = setOf(
            "refresh", "get_session", "send_message", "cancel", "approval", "ask",
            "begin_handoff", "return_handoff", "abort_handoff"
        )
        private val CONTROL_OPERATIONS = setOf(
            "send_message", "cancel", "approval", "ask", "begin_handoff", "return_handoff", "abort_handoff"
        )
        private val HANDOFF_OPERATIONS = setOf("begin_handoff", "return_handoff", "abort_handoff")
        private val MESSAGE_ROLES = setOf("user", "assistant", "tool")
        private val EXECUTION_OWNERS = setOf("desktop", "android")
        private val HANDOFF_TOKEN = Regex("^handoff-[0-9a-f]{64}$")
    }
}

@Serializable
private data class DesktopHandoffPayload(
    val handoffToken: String,
    val portableSession: String? = null
)
