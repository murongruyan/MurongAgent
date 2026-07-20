package com.murong.agent.lan

import com.murong.agent.core.workspace.ComputerWorkspaceChangeBatch
import com.murong.agent.core.workspace.ComputerWorkspaceDescriptor
import com.murong.agent.core.workspace.ComputerWorkspaceEntry
import com.murong.agent.core.workspace.ComputerWorkspaceGateway
import com.murong.agent.core.workspace.ComputerWorkspaceOperation
import com.murong.agent.core.workspace.ComputerWorkspaceRequest
import com.murong.agent.core.workspace.ComputerWorkspaceResponse
import com.murong.agent.core.workspace.ComputerTerminalDescriptor
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withTimeout

@Singleton
class LanWebComputerWorkspaceBridge @Inject constructor() : ComputerWorkspaceGateway {
    private data class ActiveSession(
        val clientId: String,
        val descriptor: ComputerWorkspaceDescriptor,
        var lastHeartbeatAt: Long
    )

    private data class PendingRequest(
        val clientId: String,
        val workspaceSessionId: String,
        val request: ComputerWorkspaceRequest,
        val deferred: CompletableDeferred<ComputerWorkspaceResponse>
    )

    private data class ChangeRecord(
        val sequence: Long,
        val path: String,
        val kind: String,
        val directory: Boolean
    )

    private data class PreparedChangeBatch(
        val workspaceSessionId: String,
        val throughSequence: Long
    )

    private val lock = Any()
    private val pending = linkedMapOf<String, PendingRequest>()
    private val changesByPath = linkedMapOf<String, ChangeRecord>()
    private val processedChangeReportIds = ArrayDeque<String>()
    private val preparedChangeBatches = linkedMapOf<String, PreparedChangeBatch>()
    private val mutableRequests = MutableSharedFlow<LanWebWorkspaceRpcDispatch>(extraBufferCapacity = 64)
    private var active: ActiveSession? = null
    private var changeSequence = 0L

    val requests: SharedFlow<LanWebWorkspaceRpcDispatch> = mutableRequests.asSharedFlow()

    internal var nowProvider: () -> Long = System::currentTimeMillis
    internal var readTimeoutMillis: Long = READ_TIMEOUT_MILLIS
    internal var writeTimeoutMillis: Long = WRITE_TIMEOUT_MILLIS

    fun register(clientId: String, request: LanWebWorkspaceRegisterRequest): Result<ComputerWorkspaceDescriptor> =
        runCatching {
            require(LanWebContract.requestIdPattern.matches(request.workspaceSessionId)) {
                "工作区会话 ID 无效"
            }
            require(LanWebContract.requestIdPattern.matches(request.requestId)) { "request_id 无效" }
            val label = normalizeLabel(request.label) ?: error("工作区名称无效")
            val platform = normalizeDesktopPlatform(request.platform)
            val architecture = normalizeDesktopArchitecture(request.architecture)
            require(request.readable) { "工作区必须至少允许读取" }
            val terminals = normalizeTerminals(request.terminals)
            val now = nowProvider()
            synchronized(lock) {
                expireLocked(now)
                val current = active
                if (current != null && current.clientId != clientId) {
                    error("另一个电脑节点正在持有工作区，请先断开它")
                }
                if (current != null && current.descriptor.sessionId != request.workspaceSessionId) {
                    invalidatePendingLocked("workspace_replaced", "电脑节点重新配置了工作区")
                    resetChangesLocked()
                }
                val descriptor = ComputerWorkspaceDescriptor(
                    sessionId = request.workspaceSessionId,
                    label = label,
                    readable = request.readable,
                    writable = request.writable,
                    platform = platform,
                    architecture = architecture,
                    terminal = request.terminal || terminals.isNotEmpty(),
                    terminalBackends = terminals
                )
                active = ActiveSession(clientId, descriptor, now)
                descriptor
            }
        }

    fun heartbeat(clientId: String, workspaceSessionId: String): Result<Unit> = runCatching {
        synchronized(lock) {
            expireLocked(nowProvider())
            val current = active ?: error("电脑工作区未连接")
            require(current.clientId == clientId && current.descriptor.sessionId == workspaceSessionId) {
                "电脑工作区会话不匹配"
            }
            current.lastHeartbeatAt = nowProvider()
        }
    }

    fun status(clientId: String): LanWebWorkspaceStatusResponse = synchronized(lock) {
        expireLocked(nowProvider())
        val current = active?.takeIf { it.clientId == clientId }
        LanWebWorkspaceStatusResponse(
            connected = current != null,
            workspaceSessionId = current?.descriptor?.sessionId,
            label = current?.descriptor?.label,
            platform = current?.descriptor?.platform,
            architecture = current?.descriptor?.architecture,
            readable = current?.descriptor?.readable == true,
            writable = current?.descriptor?.writable == true,
            terminal = current?.descriptor?.terminal == true,
            terminals = current?.descriptor?.effectiveTerminalBackends.orEmpty().map {
                LanWebTerminalBackend(it.id, it.label, it.version)
            },
            heartbeatIntervalMillis = HEARTBEAT_INTERVAL_MILLIS,
            expiresAfterMillis = SESSION_EXPIRY_MILLIS
        )
    }

    fun disconnect(clientId: String, workspaceSessionId: String? = null) {
        synchronized(lock) {
            val current = active ?: return
            if (current.clientId != clientId) return
            if (workspaceSessionId != null && current.descriptor.sessionId != workspaceSessionId) return
            active = null
            invalidatePendingLocked("workspace_disconnected", "电脑工作区已断开")
            resetChangesLocked()
        }
    }

    fun shutdown() {
        synchronized(lock) {
            active = null
            invalidatePendingLocked("service_stopped", "局域网服务已停止")
            resetChangesLocked()
        }
    }

    override fun activeWorkspace(): ComputerWorkspaceDescriptor? = synchronized(lock) {
        expireLocked(nowProvider())
        active?.descriptor
    }

    override suspend fun execute(request: ComputerWorkspaceRequest): ComputerWorkspaceResponse {
        val requestId = "workspace-${UUID.randomUUID()}"
        val now = nowProvider()
        val pendingRequest: PendingRequest
        val dispatch: LanWebWorkspaceRpcDispatch
        synchronized(lock) {
            expireLocked(now)
            val current = active ?: return unavailable("workspace_unavailable", "电脑工作区未连接")
            if (!current.descriptor.readable) {
                return unavailable("read_permission_missing", "电脑端未授予读取权限")
            }
            if (request.operation in WRITE_OPERATIONS && !current.descriptor.writable) {
                return unavailable("write_permission_missing", "电脑端未授予写入权限")
            }
            if (request.operation == ComputerWorkspaceOperation.RUN && !current.descriptor.terminal) {
                return unavailable("terminal_permission_missing", "电脑端未启用终端能力")
            }
            if (
                request.operation == ComputerWorkspaceOperation.RUN && request.terminalId != null &&
                current.descriptor.effectiveTerminalBackends.none { it.id == request.terminalId }
            ) {
                return unavailable("terminal_unavailable", "电脑端未启用请求的终端")
            }
            val timeout = timeoutFor(request)
            val deferred = CompletableDeferred<ComputerWorkspaceResponse>()
            pendingRequest = PendingRequest(
                clientId = current.clientId,
                workspaceSessionId = current.descriptor.sessionId,
                request = request,
                deferred = deferred
            )
            pending[requestId] = pendingRequest
            dispatch = LanWebWorkspaceRpcDispatch(
                targetClientId = current.clientId,
                event = LanWebWorkspaceRpcEvent(
                    workspaceSessionId = current.descriptor.sessionId,
                    requestId = requestId,
                    operation = request.operation.name.lowercase(),
                    path = request.relativePath,
                    content = request.content,
                    expectedSha256 = request.expectedSha256,
                    command = request.command,
                    terminalId = request.terminalId,
                    timeoutMillis = request.timeoutMillis,
                    maxBytes = request.maxBytes,
                    maxEntries = request.maxEntries,
                    expiresAt = now + timeout
                )
            )
        }
        if (!mutableRequests.tryEmit(dispatch)) {
            synchronized(lock) { pending.remove(requestId) }
            return unavailable("workspace_busy", "电脑工作区请求队列已满")
        }
        val timeout = timeoutFor(request)
        return try {
            withTimeout(timeout) { pendingRequest.deferred.await() }
        } catch (_: TimeoutCancellationException) {
            unavailable("workspace_timeout", "电脑端未在时限内完成操作")
        } finally {
            synchronized(lock) {
                if (pending[requestId] === pendingRequest) pending.remove(requestId)
            }
        }
    }

    private fun normalizeTerminals(values: List<LanWebTerminalBackend>): List<ComputerTerminalDescriptor> {
        require(values.size <= 16) { "终端数量过多" }
        val seen = mutableSetOf<String>()
        return values.map { value ->
            val id = value.id.trim()
            require(id.length in 1..128 && id.none { it.isISOControl() }) { "终端 ID 无效" }
            require(seen.add(id)) { "终端 ID 重复" }
            val label = normalizeLabel(value.label) ?: error("终端名称无效")
            ComputerTerminalDescriptor(id, label, value.version.trim().take(80))
        }
    }

    fun complete(clientId: String, result: LanWebWorkspaceResultRequest): Result<Unit> = runCatching {
        val pendingRequest = synchronized(lock) {
            expireLocked(nowProvider())
            val current = active ?: error("电脑工作区未连接")
            require(current.clientId == clientId && current.descriptor.sessionId == result.workspaceSessionId) {
                "电脑工作区会话不匹配"
            }
            val candidate = pending.remove(result.requestId) ?: error("请求不存在、已完成或已过期")
            require(candidate.clientId == clientId && candidate.workspaceSessionId == result.workspaceSessionId) {
                "请求所有者不匹配"
            }
            candidate
        }
        val validated = runCatching { validateResult(pendingRequest.request, result) }
        validated.onSuccess(pendingRequest.deferred::complete)
        validated.onFailure { error ->
            pendingRequest.deferred.complete(
                unavailable(
                    "invalid_node_result",
                    error.message?.take(500) ?: "电脑端返回了无效结果"
                )
            )
        }
        validated.getOrThrow()
    }

    fun recordChanges(clientId: String, report: LanWebWorkspaceChangeReportRequest): Result<Unit> = runCatching {
        require(LanWebContract.requestIdPattern.matches(report.reportId)) { "report_id 无效" }
        require(report.changes.size <= MAX_CHANGES_PER_REPORT) { "单次变化数量过多" }
        synchronized(lock) {
            expireLocked(nowProvider())
            val current = active ?: error("电脑工作区未连接")
            require(current.clientId == clientId && current.descriptor.sessionId == report.workspaceSessionId) {
                "电脑工作区会话不匹配"
            }
            if (report.reportId in processedChangeReportIds) return@runCatching
            processedChangeReportIds.addLast(report.reportId)
            while (processedChangeReportIds.size > MAX_PROCESSED_REPORT_IDS) {
                processedChangeReportIds.removeFirst()
            }
            report.changes.forEach { change ->
                val path = validateRelativePath(change.path)
                val kind = change.kind.lowercase().also {
                    require(it in VALID_CHANGE_KINDS) { "变化类型无效" }
                }
                changeSequence += 1L
                changesByPath[path] = ChangeRecord(changeSequence, path, kind, change.directory)
            }
            if (report.partialScan) {
                changeSequence += 1L
                changesByPath[PARTIAL_SCAN_MARKER] = ChangeRecord(
                    changeSequence,
                    PARTIAL_SCAN_MARKER,
                    "partial_scan",
                    directory = true
                )
            }
        }
    }

    override fun prepareExternalChanges(): ComputerWorkspaceChangeBatch? = synchronized(lock) {
        expireLocked(nowProvider())
        val current = active ?: return@synchronized null
        val records = changesByPath.values.sortedBy { it.sequence }
        if (records.isEmpty()) return@synchronized null
        val visible = records.filter { it.path != PARTIAL_SCAN_MARKER }.take(MAX_CHANGES_IN_ATTACHMENT)
        val omitted = (records.count { it.path != PARTIAL_SCAN_MARKER } - visible.size).coerceAtLeast(0)
        val partial = records.any { it.path == PARTIAL_SCAN_MARKER }
        val throughSequence = records.maxOf { it.sequence }
        val batchId = "computer-changes-${UUID.randomUUID()}"
        preparedChangeBatches[batchId] = PreparedChangeBatch(
            workspaceSessionId = current.descriptor.sessionId,
            throughSequence = throughSequence
        )
        while (preparedChangeBatches.size > MAX_PREPARED_BATCHES) {
            preparedChangeBatches.remove(preparedChangeBatches.keys.first())
        }
        ComputerWorkspaceChangeBatch(
            opaqueId = batchId,
            attachment = buildString {
                appendLine("<computer_workspace_external_changes workspace=\"${escapeAttribute(current.descriptor.label)}\">")
                appendLine("这些文件由电脑上的终端、Git 或其他编辑器在 Murong 工具之外修改；操作前请重新读取。")
                visible.forEach { record ->
                    val type = if (record.directory) "directory" else "file"
                    appendLine("- ${record.kind} $type: ${record.path}")
                }
                if (omitted > 0) appendLine("- 另有 $omitted 项变化未展开")
                if (partial) appendLine("- 变化扫描达到上限，本摘要可能不完整")
                append("</computer_workspace_external_changes>")
            }
        )
    }

    override fun acknowledgeExternalChanges(batch: ComputerWorkspaceChangeBatch) {
        synchronized(lock) {
            val prepared = preparedChangeBatches.remove(batch.opaqueId) ?: return
            if (active?.descriptor?.sessionId != prepared.workspaceSessionId) return
            changesByPath.entries.removeAll { it.value.sequence <= prepared.throughSequence }
        }
    }

    private fun validateResult(
        request: ComputerWorkspaceRequest,
        result: LanWebWorkspaceResultRequest
    ): ComputerWorkspaceResponse {
        if (!result.success) {
            return unavailable(
                result.errorCode?.take(80) ?: "node_rejected",
                result.errorMessage?.take(500) ?: "电脑端拒绝了操作"
            )
        }
        require(result.entries.size <= request.maxEntries) { "目录结果超过上限" }
        val contentBytes = result.content?.toByteArray(Charsets.UTF_8)?.size ?: 0
        require(contentBytes <= request.maxBytes) { "文件结果超过上限" }
        if (request.operation == ComputerWorkspaceOperation.READ) {
            require(result.content != null) { "读取结果缺少 content" }
            require(isSha256(result.sha256)) { "读取结果缺少有效 SHA-256" }
        }
        if (request.operation == ComputerWorkspaceOperation.WRITE) {
            require(isSha256(result.sha256)) { "写入结果缺少有效 SHA-256" }
        }
        if (request.operation == ComputerWorkspaceOperation.MKDIR) {
            require(result.directory == true) { "建目录结果缺少 directory=true" }
        }
        if (request.operation == ComputerWorkspaceOperation.RUN) {
            require(result.exitCode != null) { "终端结果缺少 exitCode" }
            val outputBytes = result.stdout.orEmpty().toByteArray(Charsets.UTF_8).size +
                result.stderr.orEmpty().toByteArray(Charsets.UTF_8).size
            require(outputBytes <= request.maxBytes) { "终端输出超过上限" }
        }
        return ComputerWorkspaceResponse(
            success = true,
            entries = result.entries.map { entry ->
                ComputerWorkspaceEntry(
                    name = entry.name.take(255),
                    relativePath = validateRelativePath(entry.path),
                    directory = entry.directory,
                    size = entry.size?.takeIf { it >= 0L },
                    lastModified = entry.lastModified?.takeIf { it >= 0L }
                )
            },
            content = result.content,
            sha256 = result.sha256?.lowercase(),
            size = result.size?.takeIf { it >= 0L },
            lastModified = result.lastModified?.takeIf { it >= 0L },
            directory = result.directory,
            created = result.created,
            diffPreview = result.diffPreview?.take(MAX_DIFF_PREVIEW_CHARS),
            stdout = result.stdout,
            stderr = result.stderr,
            exitCode = result.exitCode,
            timedOut = result.timedOut
        )
    }

    private fun timeoutFor(request: ComputerWorkspaceRequest): Long = when (request.operation) {
        ComputerWorkspaceOperation.WRITE,
        ComputerWorkspaceOperation.MKDIR -> writeTimeoutMillis
        ComputerWorkspaceOperation.RUN -> {
            val commandTimeout = (request.timeoutMillis ?: DEFAULT_TERMINAL_TIMEOUT_MILLIS)
                .coerceIn(MIN_TERMINAL_TIMEOUT_MILLIS, MAX_TERMINAL_TIMEOUT_MILLIS)
            commandTimeout + TERMINAL_TRANSPORT_GRACE_MILLIS
        }
        else -> readTimeoutMillis
    }

    private fun expireLocked(now: Long) {
        val current = active ?: return
        if (now - current.lastHeartbeatAt <= SESSION_EXPIRY_MILLIS) return
        active = null
        invalidatePendingLocked("workspace_expired", "电脑工作区心跳已过期")
        resetChangesLocked()
    }

    private fun invalidatePendingLocked(code: String, message: String) {
        val response = unavailable(code, message)
        pending.values.forEach { it.deferred.complete(response) }
        pending.clear()
    }

    private fun resetChangesLocked() {
        changesByPath.clear()
        processedChangeReportIds.clear()
        preparedChangeBatches.clear()
        changeSequence = 0L
    }

    private fun unavailable(code: String, message: String) = ComputerWorkspaceResponse(
        success = false,
        errorCode = code,
        errorMessage = message
    )

    private fun normalizeLabel(raw: String): String? {
        val value = raw.trim().replace(Regex("\\s+"), " ")
        if (value.isEmpty() || value.length > 80) return null
        if (value.any { it.code < 0x20 || it.code == 0x7f }) return null
        return value
    }

    private fun validateRelativePath(raw: String): String {
        require(raw.isNotEmpty() && raw.length <= 1024) { "相对路径无效" }
        require(raw == raw.trim() && !raw.startsWith('/')) { "相对路径无效" }
        require('\\' !in raw && ':' !in raw) { "相对路径无效" }
        require(raw.none { it.code == 0 || it.code < 0x20 || it.code == 0x7f }) { "相对路径无效" }
        val segments = raw.split('/')
        require(segments.size <= 64 && segments.none { it.isEmpty() || it == "." || it == ".." }) {
            "相对路径越界"
        }
        require(segments.all { it.length <= 255 }) { "相对路径段过长" }
        return raw
    }

    private fun isSha256(value: String?): Boolean = value != null && SHA256_PATTERN.matches(value)

    private fun escapeAttribute(value: String): String = value
        .replace("&", "&amp;")
        .replace("\"", "&quot;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    private companion object {
        const val HEARTBEAT_INTERVAL_MILLIS = 10_000L
        const val SESSION_EXPIRY_MILLIS = 30_000L
        const val READ_TIMEOUT_MILLIS = 30_000L
        const val WRITE_TIMEOUT_MILLIS = 120_000L
        const val DEFAULT_TERMINAL_TIMEOUT_MILLIS = 120_000L
        const val MIN_TERMINAL_TIMEOUT_MILLIS = 1_000L
        const val MAX_TERMINAL_TIMEOUT_MILLIS = 600_000L
        const val TERMINAL_TRANSPORT_GRACE_MILLIS = 5_000L
        const val MAX_CHANGES_PER_REPORT = 200
        const val MAX_PROCESSED_REPORT_IDS = 128
        const val MAX_CHANGES_IN_ATTACHMENT = 100
        const val MAX_PREPARED_BATCHES = 16
        const val MAX_DIFF_PREVIEW_CHARS = 64 * 1024
        const val PARTIAL_SCAN_MARKER = "__murong_partial_scan__"
        val VALID_CHANGE_KINDS = setOf("created", "modified", "deleted")
        val WRITE_OPERATIONS = setOf(
            ComputerWorkspaceOperation.WRITE,
            ComputerWorkspaceOperation.MKDIR
        )
        val SHA256_PATTERN = Regex("^[A-Fa-f0-9]{64}$")
    }
}
