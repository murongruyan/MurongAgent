package com.murong.agent.core.codex

import android.content.Context
import com.murong.agent.core.doctor.SensitiveDataSanitizer
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

data class CodexApprovalRequest(
    val requestId: CodexRpcId,
    val approval: CodexServerRequestPayload.Approval,
)

enum class CodexDiagnosticKind {
    STDERR,
    PROTOCOL,
    IO,
    PROCESS_EXIT,
    RESTART,
}

/** Already-sanitized diagnostic text; raw stderr is never exposed by the client. */
data class CodexAppServerDiagnostic(
    val kind: CodexDiagnosticKind,
    val message: String,
)

class CodexAppServerRpcException(
    val rpcError: CodexRpcError,
) : IOException("Codex app-server request failed (${rpcError.code}): ${rpcError.message}")

class CodexAppServerClosedException : IOException("Codex app-server client is closed")

/**
 * Long-lived Codex app-server client with a transport-neutral public surface.
 *
 * The Android constructor locates the binary through [com.murong.agent.common.toolchain.ToolchainManager].
 * Tests can inject a fake [CodexAppServerTransportFactory] through the internal
 * primary constructor. Auth persistence stays inside Codex's private
 * `CODEX_HOME`; this client never reads `auth.json`.
 */
class CodexAppServerClient internal constructor(
    private val transportFactory: CodexAppServerTransportFactory,
    private val clientInfo: CodexClientInfo = DEFAULT_CLIENT_INFO,
    private val capabilities: CodexInitializeCapabilities? = DEFAULT_CAPABILITIES,
    private val requestTimeoutMillis: Long = DEFAULT_REQUEST_TIMEOUT_MILLIS,
    private val restartDelaysMillis: List<Long> = DEFAULT_RESTART_DELAYS_MILLIS,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    constructor(
        context: Context,
        workingDirectory: File? = null,
        clientVersion: String = "unknown",
        capabilities: CodexInitializeCapabilities? = DEFAULT_CAPABILITIES,
        requestTimeoutMillis: Long = DEFAULT_REQUEST_TIMEOUT_MILLIS,
    ) : this(
        transportFactory = AndroidCodexAppServerTransportFactory(
            context = context.applicationContext ?: context,
            workingDirectory = workingDirectory,
        ),
        clientInfo = CodexClientInfo(
            name = "murong_agent",
            title = "Murong Agent",
            version = clientVersion,
        ),
        capabilities = capabilities,
        requestTimeoutMillis = requestTimeoutMillis,
    )

    private data class ActiveTransport(
        val generation: Long,
        val transport: CodexAppServerTransport,
        val jobs: MutableList<Job> = mutableListOf(),
    )

    private val lifecycleMutex = Mutex()
    private val protocolStateMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val requestIds = AtomicLong(1L)
    private val desiredRunning = AtomicBoolean(false)
    private val mutableState = MutableStateFlow(CodexAppServerState())
    private val mutableNotifications = MutableSharedFlow<CodexNotificationEvent>(
        extraBufferCapacity = EVENT_BUFFER_CAPACITY,
    )
    private val mutableServerRequests =
        MutableSharedFlow<CodexServerMessage.ServerRequest>(
            extraBufferCapacity = EVENT_BUFFER_CAPACITY,
        )
    private val mutableApprovalRequests = MutableSharedFlow<CodexApprovalRequest>(
        extraBufferCapacity = EVENT_BUFFER_CAPACITY,
    )
    private val mutableDiagnostics = MutableSharedFlow<CodexAppServerDiagnostic>(
        extraBufferCapacity = DIAGNOSTIC_BUFFER_CAPACITY,
    )

    @Volatile
    private var activeTransport: ActiveTransport? = null

    @Volatile
    private var closed = false

    @Volatile
    private var restartJob: Job? = null

    private var generationCounter = 0L

    val state: StateFlow<CodexAppServerState> = mutableState.asStateFlow()
    val notifications: SharedFlow<CodexNotificationEvent> = mutableNotifications.asSharedFlow()
    val serverRequests: SharedFlow<CodexServerMessage.ServerRequest> =
        mutableServerRequests.asSharedFlow()
    val approvalRequests: SharedFlow<CodexApprovalRequest> = mutableApprovalRequests.asSharedFlow()
    val diagnostics: SharedFlow<CodexAppServerDiagnostic> = mutableDiagnostics.asSharedFlow()

    init {
        require(requestTimeoutMillis > 0L) { "requestTimeoutMillis must be positive" }
        require(restartDelaysMillis.all { it >= 0L }) {
            "restart delays must not be negative"
        }
    }

    /** Starts the process if needed and completes the initialize/initialized handshake. */
    suspend fun start(): CodexInitializeResult {
        checkOpen()
        desiredRunning.set(true)
        return lifecycleMutex.withLock {
            activeTransport
                ?.takeIf { mutableState.value.connectionPhase == CodexConnectionPhase.READY }
                ?.let {
                    return@withLock mutableState.value.initializeResult
                        ?: throw CodexProtocolException("Ready connection has no initialize result")
                }
            startLocked()
        }
    }

    /** Stops the current generation and performs a fresh handshake. */
    suspend fun restart(): CodexInitializeResult {
        checkOpen()
        desiredRunning.set(true)
        cancelScheduledRestart()
        return lifecycleMutex.withLock {
            stopActiveLocked()
            resetConnectionState(CodexConnectionPhase.NEW)
            startLocked()
        }
    }

    suspend fun accountRead(refreshToken: Boolean = false): CodexAccountReadResult {
        val result = request(
            CodexAppServerProtocol.accountRead(nextRequestId(), refreshToken),
        )
        return (result as? CodexResponseResult.AccountRead)?.value
            ?: unexpectedResult("account/read", result)
    }

    /** Reads only the official account's rate-limit projection, never auth tokens. */
    suspend fun accountRateLimitsRead(): CodexRateLimitsSnapshot {
        val result = request(CodexAppServerProtocol.accountRateLimitsRead(nextRequestId()))
        return (result as? CodexResponseResult.RateLimits)?.value
            ?: unexpectedResult("account/rateLimits/read", result)
    }

    /** Returns the authenticated account's server-advertised model capabilities. */
    suspend fun modelList(): CodexModelCatalog {
        val allModels = mutableListOf<CodexModelDescriptor>()
        var cursor: String? = null
        var lastRaw = JsonObject(emptyMap())
        repeat(MAX_MODEL_LIST_PAGES) {
            val result = request(
                CodexAppServerProtocol.modelList(nextRequestId(), cursor = cursor),
            )
            val page = (result as? CodexResponseResult.ModelList)?.value
                ?: unexpectedResult("model/list", result)
            allModels += page.models
            lastRaw = page.raw
            val nextCursor = page.nextCursor?.takeIf { it.isNotBlank() }
            if (nextCursor == null || nextCursor == cursor) {
                return CodexModelCatalog(
                    models = allModels.distinctBy { it.id },
                    raw = lastRaw,
                )
            }
            cursor = nextCursor
        }
        throw CodexProtocolException("官方模型目录分页过多，已停止读取")
    }

    suspend fun startDeviceCodeLogin(): CodexDeviceCode {
        val result = request(
            CodexAppServerProtocol.startChatGptDeviceCodeLogin(nextRequestId()),
        )
        return (result as? CodexResponseResult.DeviceCode)?.value
            ?: unexpectedResult("account/login/start", result)
    }

    suspend fun cancelLogin(loginId: String) {
        val result = request(CodexAppServerProtocol.cancelLogin(nextRequestId(), loginId))
        if (result !is CodexResponseResult.Empty) unexpectedResult("account/login/cancel", result)
    }

    suspend fun logout() {
        val result = request(CodexAppServerProtocol.logout(nextRequestId()))
        if (result !is CodexResponseResult.Empty) unexpectedResult("account/logout", result)
    }

    suspend fun threadStart(options: CodexThreadOptions = CodexThreadOptions()): CodexThread {
        val result = request(CodexAppServerProtocol.threadStart(nextRequestId(), options))
        return (result as? CodexResponseResult.Thread)?.value
            ?: unexpectedResult("thread/start", result)
    }

    suspend fun threadResume(
        threadId: String,
        options: CodexThreadOptions = CodexThreadOptions(),
    ): CodexThread {
        val result = request(
            CodexAppServerProtocol.threadResume(nextRequestId(), threadId, options),
        )
        return (result as? CodexResponseResult.Thread)?.value
            ?: unexpectedResult("thread/resume", result)
    }

    suspend fun turnStart(
        threadId: String,
        input: List<CodexUserInput>,
        options: CodexTurnOptions = CodexTurnOptions(),
    ): CodexTurn {
        val result = request(
            CodexAppServerProtocol.turnStart(nextRequestId(), threadId, input, options),
        )
        return (result as? CodexResponseResult.Turn)?.value
            ?: unexpectedResult("turn/start", result)
    }

    suspend fun turnInterrupt(threadId: String, turnId: String) {
        val result = request(
            CodexAppServerProtocol.turnInterrupt(nextRequestId(), threadId, turnId),
        )
        if (result !is CodexResponseResult.Empty) unexpectedResult("turn/interrupt", result)
    }

    suspend fun respondToApproval(
        requestId: CodexRpcId,
        decision: CodexApprovalDecision,
    ) {
        send(CodexAppServerProtocol.approvalResponse(requestId, decision))
    }

    suspend fun respondToApproval(requestId: CodexRpcId, decision: JsonElement) {
        send(CodexAppServerProtocol.approvalResponse(requestId, decision))
    }

    /** Final shutdown. Create a new client instance to connect again afterwards. */
    suspend fun close() {
        if (closed) return
        closed = true
        desiredRunning.set(false)
        cancelScheduledRestart()
        lifecycleMutex.withLock {
            stopActiveLocked()
            resetConnectionState(CodexConnectionPhase.NEW)
        }
        scope.cancel()
    }

    private suspend fun startLocked(): CodexInitializeResult {
        resetConnectionState(CodexConnectionPhase.NEW)
        val transport = try {
            transportFactory.create()
        } catch (error: Throwable) {
            resetConnectionState(CodexConnectionPhase.FAILED)
            throw error
        }
        val active = ActiveTransport(
            generation = ++generationCounter,
            transport = transport,
        )
        activeTransport = active
        attachCollectors(active)

        try {
            transport.start()
            val result = requestOn(
                active,
                CodexAppServerProtocol.initialize(
                    id = nextRequestId(),
                    clientInfo = clientInfo,
                    capabilities = capabilities,
                ),
            )
            val initialize = (result as? CodexResponseResult.Initialize)?.value
                ?: unexpectedResult("initialize", result)
            val initialized = CodexAppServerProtocol.initialized()
            transport.send(CodexAppServerCodec.encodeToJson(initialized))
            recordSent(initialized)
            return initialize
        } catch (error: Throwable) {
            if (activeTransport?.generation == active.generation) activeTransport = null
            active.jobs.forEach(Job::cancel)
            transport.kill()
            clearPendingAndSetPhase(CodexConnectionPhase.FAILED)
            throw error
        }
    }

    private suspend fun request(request: CodexClientRequest): CodexResponseResult {
        start()
        val active = activeTransport ?: throw IOException("Codex app-server is not running")
        return requestOn(active, request)
    }

    private suspend fun requestOn(
        active: ActiveTransport,
        request: CodexClientRequest,
    ): CodexResponseResult {
        recordSent(request)
        val responseElement = try {
            withTimeout(requestTimeoutMillis) {
                active.transport.request(CodexAppServerCodec.encodeToJson(request))
            }
        } catch (error: Throwable) {
            clearPending(request.id)
            throw error
        }
        val responseObject = responseElement as? JsonObject
            ?: throw CodexProtocolException("Codex response must be a JSON object")
        return when (val response = CodexAppServerCodec.decode(responseObject)) {
            is CodexServerMessage.Response -> {
                val update = recordReceived(response)
                update.responseResult
                    ?: throw CodexProtocolException("Response id was not pending")
            }

            is CodexServerMessage.ErrorResponse -> {
                recordReceived(response)
                throw CodexAppServerRpcException(response.error)
            }

            is CodexServerMessage.Notification,
            is CodexServerMessage.ServerRequest,
            -> throw CodexProtocolException("Request returned a non-response message")
        }
    }

    private suspend fun send(message: CodexClientResponse) {
        start()
        val active = activeTransport ?: throw IOException("Codex app-server is not running")
        active.transport.send(CodexAppServerCodec.encodeToJson(message))
        recordSent(message)
    }

    private fun attachCollectors(active: ActiveTransport) {
        active.jobs += scope.launch(start = CoroutineStart.UNDISPATCHED) {
            active.transport.messages.collect { handleIncoming(active, it) }
        }
        active.jobs += scope.launch(start = CoroutineStart.UNDISPATCHED) {
            active.transport.stderrLines.collect { line ->
                emitDiagnostic(CodexDiagnosticKind.STDERR, line)
            }
        }
        active.jobs += scope.launch(start = CoroutineStart.UNDISPATCHED) {
            active.transport.protocolViolations.collect { violation ->
                emitDiagnostic(
                    CodexDiagnosticKind.PROTOCOL,
                    "${violation.description}: ${violation.rawLine}",
                )
            }
        }
        active.jobs += scope.launch(start = CoroutineStart.UNDISPATCHED) {
            active.transport.ioFailures.collect { failure ->
                emitDiagnostic(
                    CodexDiagnosticKind.IO,
                    "${failure.stream}: ${failure.description}",
                )
            }
        }
        active.jobs += scope.launch(start = CoroutineStart.UNDISPATCHED) {
            active.transport.exits.collect { exit -> handleExit(active, exit) }
        }
    }

    private suspend fun handleIncoming(active: ActiveTransport, element: JsonElement) {
        if (activeTransport?.generation != active.generation) return
        val root = element as? JsonObject
        if (root == null) {
            emitDiagnostic(CodexDiagnosticKind.PROTOCOL, "Non-object stdout message ignored")
            return
        }
        val message = runCatching { CodexAppServerCodec.decode(root) }
            .getOrElse { error ->
                emitDiagnostic(
                    CodexDiagnosticKind.PROTOCOL,
                    error.message ?: "Malformed app-server message",
                )
                return
            }
        when (message) {
            is CodexServerMessage.Notification -> {
                recordReceived(message)
                mutableNotifications.emit(message.event)
            }

            is CodexServerMessage.ServerRequest -> {
                recordReceived(message)
                mutableServerRequests.emit(message)
                val approval = message.request as? CodexServerRequestPayload.Approval
                if (approval != null) {
                    mutableApprovalRequests.emit(
                        CodexApprovalRequest(requestId = message.id, approval = approval),
                    )
                }
            }

            // PersistentJsonLineProcess also publishes responses on stdout. The
            // request waiter owns them, preventing double state reduction here.
            is CodexServerMessage.Response,
            is CodexServerMessage.ErrorResponse,
            -> Unit
        }
    }

    private suspend fun handleExit(active: ActiveTransport, exit: CodexTransportExit) {
        val currentJob = currentCoroutineContext()[Job]
        val wasCurrent = lifecycleMutex.withLock {
            if (activeTransport?.generation != active.generation) {
                false
            } else {
                activeTransport = null
                true
            }
        }
        if (!wasCurrent) return
        active.jobs.filterNot { it === currentJob }.forEach(Job::cancel)
        clearPendingAndSetPhase(
            if (exit.expected) CodexConnectionPhase.NEW else CodexConnectionPhase.FAILED,
        )
        if (!exit.expected) {
            emitDiagnostic(
                CodexDiagnosticKind.PROCESS_EXIT,
                "Codex app-server exited unexpectedly" +
                    exit.exitCode?.let { " (code $it)" }.orEmpty(),
            )
            if (desiredRunning.get() && !closed) scheduleAutomaticRestart()
        }
    }

    @Synchronized
    private fun scheduleAutomaticRestart() {
        if (restartJob?.isActive == true || restartDelaysMillis.isEmpty()) return
        restartJob = scope.launch {
            try {
                for ((index, waitMillis) in restartDelaysMillis.withIndex()) {
                    delay(waitMillis)
                    if (!desiredRunning.get() || closed) return@launch
                    val result = runCatching { start() }
                    if (result.isSuccess) {
                        emitDiagnostic(
                            CodexDiagnosticKind.RESTART,
                            "Codex app-server restarted",
                        )
                        return@launch
                    }
                    emitDiagnostic(
                        CodexDiagnosticKind.RESTART,
                        "Codex app-server restart ${index + 1} failed: " +
                            result.exceptionOrNull()?.message.orEmpty(),
                    )
                }
            } finally {
                synchronized(this@CodexAppServerClient) {
                    restartJob = null
                }
            }
        }
    }

    @Synchronized
    private fun cancelScheduledRestart() {
        restartJob?.cancel()
        restartJob = null
    }

    private suspend fun stopActiveLocked() {
        val active = activeTransport ?: return
        activeTransport = null
        runCatching { active.transport.close() }
            .onFailure { active.transport.kill() }
        active.jobs.forEach(Job::cancel)
    }

    private suspend fun recordSent(message: CodexClientMessage) {
        protocolStateMutex.withLock {
            mutableState.value = CodexAppServerStateReducer.sent(mutableState.value, message)
        }
    }

    private suspend fun recordReceived(message: CodexServerMessage): CodexStateUpdate {
        return protocolStateMutex.withLock {
            CodexAppServerStateReducer.received(mutableState.value, message).also { update ->
                mutableState.value = update.state
            }
        }
    }

    private suspend fun clearPending(id: CodexRpcId) {
        protocolStateMutex.withLock {
            mutableState.value = mutableState.value.copy(
                pendingRequests = mutableState.value.pendingRequests - id,
            )
        }
    }

    private suspend fun clearPendingAndSetPhase(phase: CodexConnectionPhase) {
        protocolStateMutex.withLock {
            mutableState.value = mutableState.value.copy(
                connectionPhase = phase,
                pendingRequests = emptyMap(),
                pendingApprovals = emptyMap(),
            )
        }
    }

    private suspend fun resetConnectionState(phase: CodexConnectionPhase) {
        protocolStateMutex.withLock {
            mutableState.value = mutableState.value.copy(
                connectionPhase = phase,
                pendingRequests = emptyMap(),
                pendingApprovals = emptyMap(),
                initializeResult = null,
                lastRpcError = null,
            )
        }
    }

    private suspend fun emitDiagnostic(kind: CodexDiagnosticKind, rawMessage: String) {
        val sanitized = CodexAppServerDiagnosticSanitizer.sanitize(rawMessage)
        if (sanitized.isNotBlank()) {
            mutableDiagnostics.emit(CodexAppServerDiagnostic(kind, sanitized))
        }
    }

    private fun nextRequestId(): CodexRpcId = CodexRpcId.Number(requestIds.getAndIncrement())

    private fun checkOpen() {
        if (closed) throw CodexAppServerClosedException()
    }

    private fun unexpectedResult(method: String, result: CodexResponseResult): Nothing {
        throw CodexProtocolException(
            "Unexpected response type for $method: ${result::class.simpleName}",
        )
    }

    companion object {
        const val DEFAULT_REQUEST_TIMEOUT_MILLIS = 30_000L
        const val MAX_MODEL_LIST_PAGES = 10
        val DEFAULT_RESTART_DELAYS_MILLIS = listOf(250L, 1_000L, 3_000L)
        val DEFAULT_CLIENT_INFO = CodexClientInfo(
            name = "murong_agent",
            title = "Murong Agent",
            version = "unknown",
        )
        val DEFAULT_CAPABILITIES = CodexInitializeCapabilities(experimentalApi = true)
        private const val EVENT_BUFFER_CAPACITY = 64
        private const val DIAGNOSTIC_BUFFER_CAPACITY = 32
    }
}

internal object CodexAppServerDiagnosticSanitizer {
    private val standaloneOpenAiToken = Regex(
        """(?i)\b(?:sk|sess)-[A-Za-z0-9._~-]{8,}\b""",
    )
    private val deviceCode = Regex("""\b[A-Z0-9]{4}-[A-Z0-9]{4}\b""")
    private val rawAuthPayload = Regex(
        """(?i)[\"']?(?:access_token|refresh_token|id_token)[\"']?\s*:""",
    )

    fun sanitize(value: String): String {
        if (value.isBlank()) return value
        if (rawAuthPayload.containsMatchIn(value)) return "[REDACTED_AUTH_DIAGNOSTIC]"
        return SensitiveDataSanitizer.sanitizeText(value)
            .replace(standaloneOpenAiToken, "[REDACTED_OPENAI_TOKEN]")
            .replace(deviceCode, "[REDACTED_DEVICE_CODE]")
            .take(MAX_DIAGNOSTIC_CHARS)
    }

    private const val MAX_DIAGNOSTIC_CHARS = 4_096
}
