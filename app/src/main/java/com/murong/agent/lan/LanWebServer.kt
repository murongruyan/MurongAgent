package com.murong.agent.lan

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import java.io.BufferedWriter
import java.io.FilterInputStream
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class LanWebServer(
    context: Context?,
    private val bindAddress: String,
    private val accessStore: LanWebAccessStore,
    private val chatBridge: LanWebChatGateway,
    private val eventHub: LanWebEventHub,
    private val computerWorkspaceBridge: LanWebComputerWorkspaceBridge? = null,
    private val desktopAgentBridge: LanWebDesktopAgentBridge? = null,
    private val credentialSyncBridge: LanWebCredentialSyncBridge? = null,
    private val onAccessChanged: () -> Unit,
    private val onPairingConsumed: () -> Unit,
    port: Int = LanWebContract.PORT,
    assetTextLoader: ((String) -> String)? = null
) : NanoHTTPD(bindAddress, port) {
    private val loadAssetText: (String) -> String = assetTextLoader ?: { path ->
        requireNotNull(context) { "Android context or test asset loader is required" }
            .applicationContext
            .assets
            .open(path)
            .bufferedReader(StandardCharsets.UTF_8)
            .use { it.readText() }
    }
    private val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val apiRateLimiter = LanWebRateLimiter(limit = 180, windowMillis = 60_000L)
    private val mutationRateLimiter = LanWebRateLimiter(limit = 30, windowMillis = 60_000L)
    private val workspaceRateLimiter = LanWebRateLimiter(limit = 180, windowMillis = 60_000L)
    private val sseConnections = ConcurrentHashMap<String, AtomicInteger>()
    private val json = Json {
        ignoreUnknownKeys = false
        encodeDefaults = true
        explicitNulls = false
    }
    private val staticAssets by lazy {
        mapOf(
            "/" to loadAsset("lan_web/index.html", "text/html; charset=utf-8"),
            "/index.html" to loadAsset("lan_web/index.html", "text/html; charset=utf-8"),
            "/workspace-core.js" to loadAsset("lan_web/workspace-core.js", "text/javascript; charset=utf-8"),
            "/app.js" to loadAsset("lan_web/app.js", "text/javascript; charset=utf-8"),
            "/styles.css" to loadAsset("lan_web/styles.css", "text/css; charset=utf-8")
        )
    }

    override fun serve(session: IHTTPSession): Response {
        val securityFailure = validateRequestSource(session)
        if (securityFailure != null) return securityFailure
        return try {
            when {
                session.method == Method.GET && session.uri in staticAssets.keys -> serveStatic(session.uri)
                session.method == Method.GET && session.uri == LanWebContract.PUBLIC_STATUS_PATH ->
                    jsonResponse(
                        Response.Status.OK,
                        LanWebPublicStatusResponse(
                            pairingAvailable = accessStore.isPairingAvailable()
                        )
                    )
                session.method == Method.POST && session.uri == LanWebContract.PAIR_PATH -> handlePair(session)
                session.uri.startsWith(LanWebContract.API_PREFIX) -> handleAuthenticatedApi(session)
                else -> errorResponse(Response.Status.NOT_FOUND, "not_found", "页面或接口不存在")
            }
        } catch (_: CancellationException) {
            errorResponse(Response.Status.INTERNAL_ERROR, "cancelled", "请求已取消")
        } catch (error: Throwable) {
            errorResponse(
                Response.Status.INTERNAL_ERROR,
                "internal_error",
                safeError(error.message ?: "服务器内部错误")
            )
        }
    }

    override fun useGzipWhenAccepted(response: Response): Boolean {
        if (response.mimeType?.startsWith("text/event-stream") == true) return false
        return super.useGzipWhenAccepted(response)
    }

    override fun stop() {
        super.stop()
        serverScope.cancel()
    }

    private fun handlePair(session: IHTTPSession): Response {
        val body = readJsonBody<LanWebPairRequest>(session)
            ?: return errorResponse(Response.Status.BAD_REQUEST, "invalid_request", "配对请求格式无效")
        if (
            body.secureChannelVersion != null &&
            body.secureChannelVersion != LanWebContract.SECURE_PAIRING_VERSION
        ) {
            return errorResponse(Response.Status.BAD_REQUEST, "unsupported_secure_pairing", "安全配对协议版本不受支持")
        }
        val securePairing = body.secureChannelVersion == LanWebContract.SECURE_PAIRING_VERSION
        val remoteAddress = session.remoteIpAddress.orEmpty()
        val issued = accessStore.pair(
            rawCode = body.code,
            rawCodeProof = body.codeProof,
            rawClientName = body.clientName,
            remoteAddress = remoteAddress,
            secureSync = securePairing,
        ).getOrElse { error ->
            return errorResponse(
                Response.Status.UNAUTHORIZED,
                "pairing_failed",
                safeError(error.message ?: "配对失败")
            )
        }
        val secureEnvelope = if (securePairing) {
            val syncKey = issued.syncKey
                ?: run {
                    accessStore.revokeClient(issued.summary.id)
                    return errorResponse(
                        Response.Status.INTERNAL_ERROR,
                        "secure_pairing_failed",
                        "无法建立设备同步密钥",
                    )
                }
            val pairingSecret = issued.pairingSecret
                ?: run {
                    syncKey.fill(0)
                    accessStore.revokeClient(issued.summary.id)
                    return errorResponse(
                        Response.Status.INTERNAL_ERROR,
                        "secure_pairing_failed",
                        "无法读取安全配对密钥",
                    )
                }
            try {
                LanWebPairingCrypto.encryptBootstrap(
                    pairingSecret = pairingSecret,
                    summary = issued.summary,
                    accessToken = issued.accessToken,
                    syncKey = syncKey,
                )
            } catch (error: Throwable) {
                accessStore.revokeClient(issued.summary.id)
                return errorResponse(
                    Response.Status.INTERNAL_ERROR,
                    "secure_pairing_failed",
                    safeError(error.message ?: "安全配对失败"),
                )
            } finally {
                syncKey.fill(0)
                pairingSecret.fill(0)
            }
        } else {
            null
        }
        onPairingConsumed()
        onAccessChanged()
        val response = jsonResponse(
            Response.Status.OK,
            LanWebPairResponse(
                clientId = issued.summary.id,
                clientName = issued.summary.name,
                createdAt = issued.summary.createdAt,
                secureChannel = secureEnvelope,
            )
        )
        if (secureEnvelope == null) {
            response.addHeader(
                "Set-Cookie",
                sessionCookie(issued.accessToken, maxAgeSeconds = SESSION_COOKIE_MAX_AGE_SECONDS),
            )
        }
        return response
    }

    private fun handleAuthenticatedApi(session: IHTTPSession): Response {
        val client = authenticate(session)
            ?: return errorResponse(Response.Status.UNAUTHORIZED, "unauthorized", "客户端凭据无效或已撤销")
        if (!apiRateLimiter.tryAcquire(client.id)) {
            return errorResponse(Response.Status.FORBIDDEN, "rate_limited", "请求过于频繁，请稍后再试")
        }
        return when {
            session.method == Method.GET && session.uri == LanWebContract.SESSIONS_PATH ->
                jsonResponse(Response.Status.OK, chatBridge.listSessions())
            session.method == Method.GET && session.uri.startsWith("${LanWebContract.SESSIONS_PATH}/") ->
                handleSessionDetail(session)
            session.method == Method.POST && session.uri == LanWebContract.MESSAGES_PATH ->
                handleSendMessage(session, client)
            session.method == Method.GET && session.uri == LanWebContract.APPROVAL_PATH ->
                jsonResponse(Response.Status.OK, chatBridge.liveState().session.pendingApproval)
            session.method == Method.POST && session.uri == LanWebContract.APPROVAL_PATH ->
                handleApprovalDecision(session, client)
            session.method == Method.GET && session.uri == LanWebContract.EVENTS_PATH ->
                handleEvents(session, client)
            session.method == Method.GET && session.uri == LanWebContract.WORKSPACE_STATUS_PATH ->
                jsonResponse(
                    Response.Status.OK,
                    computerWorkspaceBridge?.status(client.id) ?: LanWebWorkspaceStatusResponse(false)
                )
            session.method == Method.POST && session.uri == LanWebContract.WORKSPACE_REGISTER_PATH ->
                handleWorkspaceRegister(session, client)
            session.method == Method.POST && session.uri == LanWebContract.WORKSPACE_HEARTBEAT_PATH ->
                handleWorkspaceHeartbeat(session, client)
            session.method == Method.POST && session.uri == LanWebContract.WORKSPACE_RESULT_PATH ->
                handleWorkspaceResult(session, client)
            session.method == Method.POST && session.uri == LanWebContract.WORKSPACE_CHANGES_PATH ->
                handleWorkspaceChanges(session, client)
            session.method == Method.POST && session.uri == LanWebContract.WORKSPACE_DISCONNECT_PATH ->
                handleWorkspaceDisconnect(session, client)
            session.method == Method.GET && session.uri == LanWebContract.DESKTOP_AGENT_STATUS_PATH ->
                jsonResponse(
                    Response.Status.OK,
                    desktopAgentBridge?.status(client.id) ?: LanWebDesktopAgentStatusResponse()
                )
            session.method == Method.POST && session.uri == LanWebContract.DESKTOP_AGENT_REGISTER_PATH ->
                handleDesktopAgentRegister(session, client)
            session.method == Method.POST && session.uri == LanWebContract.DESKTOP_AGENT_SNAPSHOT_PATH ->
                handleDesktopAgentSnapshot(session, client)
            session.method == Method.POST && session.uri == LanWebContract.DESKTOP_AGENT_RESULT_PATH ->
                handleDesktopAgentResult(session, client)
            session.method == Method.POST && session.uri == LanWebContract.DESKTOP_AGENT_DISCONNECT_PATH ->
                handleDesktopAgentDisconnect(session, client)
            session.method == Method.POST && session.uri == LanWebContract.DEVICE_SYNC_PUSH_PATH ->
                handleDeviceSyncPush(session, client)
            session.method == Method.POST && session.uri == LanWebContract.DEVICE_SYNC_PULL_PATH ->
                handleDeviceSyncPull(session, client)
            session.method == Method.POST && session.uri == LanWebContract.UNPAIR_PATH ->
                handleUnpair(client)
            else -> errorResponse(Response.Status.NOT_FOUND, "api_not_found", "接口不存在")
        }
    }

    private fun handleSessionDetail(session: IHTTPSession): Response {
        val sessionId = session.uri.removePrefix("${LanWebContract.SESSIONS_PATH}/")
        if (!LanWebContract.sessionIdPattern.matches(sessionId)) {
            return errorResponse(Response.Status.BAD_REQUEST, "invalid_session", "会话 ID 无效")
        }
        val detail = chatBridge.sessionDetail(sessionId)
            ?: return errorResponse(Response.Status.NOT_FOUND, "session_not_found", "会话不存在")
        return jsonResponse(Response.Status.OK, detail)
    }

    private fun handleSendMessage(session: IHTTPSession, client: LanWebClientSummary): Response {
        if (!mutationRateLimiter.tryAcquire(client.id)) {
            return errorResponse(Response.Status.FORBIDDEN, "mutation_rate_limited", "操作过于频繁，请稍后再试")
        }
        val body = readJsonBody<LanWebSendMessageRequest>(session)
            ?: return errorResponse(Response.Status.BAD_REQUEST, "invalid_request", "消息请求格式无效")
        if (!LanWebContract.requestIdPattern.matches(body.requestId)) {
            return errorResponse(Response.Status.BAD_REQUEST, "invalid_request_id", "request_id 无效")
        }
        if (!accessStore.claimRequest(client.id, body.requestId)) {
            return errorResponse(Response.Status.CONFLICT, "duplicate_request", "该 request_id 已使用")
        }
        chatBridge.enqueueMessage(body.sessionId, body.message).getOrElse { error ->
            return errorResponse(
                Response.Status.CONFLICT,
                "message_conflict",
                safeError(error.message ?: "消息无法提交")
            )
        }
        return jsonResponse(
            Response.Status.ACCEPTED,
            LanWebAcceptedResponse(true, body.requestId, "消息已进入当前 Murong 会话并开始处理")
        )
    }

    private fun handleApprovalDecision(session: IHTTPSession, client: LanWebClientSummary): Response {
        if (!mutationRateLimiter.tryAcquire(client.id)) {
            return errorResponse(Response.Status.FORBIDDEN, "mutation_rate_limited", "操作过于频繁，请稍后再试")
        }
        val body = readJsonBody<LanWebApprovalDecisionRequest>(session)
            ?: return errorResponse(Response.Status.BAD_REQUEST, "invalid_request", "审批请求格式无效")
        val approve = when (body.decision.lowercase()) {
            "approve" -> true
            "reject" -> false
            else -> return errorResponse(Response.Status.BAD_REQUEST, "invalid_decision", "decision 必须是 approve 或 reject")
        }
        if (!LanWebContract.requestIdPattern.matches(body.requestId)) {
            return errorResponse(Response.Status.BAD_REQUEST, "invalid_request_id", "request_id 无效")
        }
        if (!accessStore.claimRequest(client.id, body.requestId)) {
            return errorResponse(Response.Status.CONFLICT, "duplicate_request", "该 request_id 已使用")
        }
        chatBridge.decideApproval(body.sessionId, body.approvalId, approve).getOrElse { error ->
            return errorResponse(
                Response.Status.CONFLICT,
                "approval_conflict",
                safeError(error.message ?: "审批状态已变化")
            )
        }
        return jsonResponse(
            Response.Status.ACCEPTED,
            LanWebAcceptedResponse(
                accepted = true,
                requestId = body.requestId,
                message = if (approve) "已把批准决定交给 Murong 核心" else "已把拒绝决定交给 Murong 核心"
            )
        )
    }

    private fun handleUnpair(client: LanWebClientSummary): Response {
        if (!mutationRateLimiter.tryAcquire(client.id)) {
            return errorResponse(Response.Status.FORBIDDEN, "mutation_rate_limited", "操作过于频繁，请稍后再试")
        }
        accessStore.revokeClient(client.id)
        computerWorkspaceBridge?.disconnect(client.id)
        desktopAgentBridge?.disconnect(client.id)
        desktopAgentBridge?.forgetClient(client.id)
        onAccessChanged()
        return jsonResponse(
            Response.Status.OK,
            LanWebAcceptedResponse(true, "self-revoke", "当前客户端已撤销")
        ).apply {
            addHeader("Set-Cookie", sessionCookie("deleted", maxAgeSeconds = 0))
        }
    }

    private fun handleWorkspaceRegister(
        session: IHTTPSession,
        client: LanWebClientSummary
    ): Response {
        if (!workspaceRateLimiter.tryAcquire(client.id)) {
            return errorResponse(Response.Status.FORBIDDEN, "workspace_rate_limited", "电脑工作区操作过于频繁")
        }
        val bridge = computerWorkspaceBridge
            ?: return errorResponse(Response.Status.INTERNAL_ERROR, "workspace_unavailable", "电脑工作区桥接不可用")
        val body = readJsonBody<LanWebWorkspaceRegisterRequest>(session)
            ?: return errorResponse(Response.Status.BAD_REQUEST, "invalid_request", "工作区注册格式无效")
        if (!accessStore.claimRequest(client.id, body.requestId)) {
            return errorResponse(Response.Status.CONFLICT, "duplicate_request", "该 request_id 已使用")
        }
        bridge.register(client.id, body).getOrElse { error ->
            return errorResponse(
                Response.Status.CONFLICT,
                "workspace_register_conflict",
                safeError(error.message ?: "工作区无法注册")
            )
        }
        return jsonResponse(Response.Status.OK, bridge.status(client.id))
    }

    private fun handleWorkspaceHeartbeat(
        session: IHTTPSession,
        client: LanWebClientSummary
    ): Response {
        if (!workspaceRateLimiter.tryAcquire(client.id)) {
            return errorResponse(Response.Status.FORBIDDEN, "workspace_rate_limited", "电脑工作区操作过于频繁")
        }
        val body = readJsonBody<LanWebWorkspaceHeartbeatRequest>(session)
            ?: return errorResponse(Response.Status.BAD_REQUEST, "invalid_request", "工作区心跳格式无效")
        computerWorkspaceBridge?.heartbeat(client.id, body.workspaceSessionId)?.getOrElse { error ->
            return errorResponse(
                Response.Status.CONFLICT,
                "workspace_session_stale",
                safeError(error.message ?: "工作区会话已失效")
            )
        } ?: return errorResponse(
            Response.Status.INTERNAL_ERROR,
            "workspace_unavailable",
            "电脑工作区桥接不可用"
        )
        return jsonResponse(
            Response.Status.OK,
            LanWebAcceptedResponse(true, body.workspaceSessionId, "电脑工作区心跳已接收")
        )
    }

    private fun handleWorkspaceResult(
        session: IHTTPSession,
        client: LanWebClientSummary
    ): Response {
        if (!workspaceRateLimiter.tryAcquire(client.id)) {
            return errorResponse(Response.Status.FORBIDDEN, "workspace_rate_limited", "电脑工作区操作过于频繁")
        }
        val body = readJsonBody<LanWebWorkspaceResultRequest>(
            session,
            maxBytes = LanWebContract.MAX_WORKSPACE_RESULT_BODY_BYTES
        ) ?: return errorResponse(Response.Status.BAD_REQUEST, "invalid_request", "工作区结果格式无效或过大")
        computerWorkspaceBridge?.complete(client.id, body)?.getOrElse { error ->
            return errorResponse(
                Response.Status.CONFLICT,
                "workspace_result_conflict",
                safeError(error.message ?: "工作区结果已失效")
            )
        } ?: return errorResponse(
            Response.Status.INTERNAL_ERROR,
            "workspace_unavailable",
            "电脑工作区桥接不可用"
        )
        return jsonResponse(
            Response.Status.ACCEPTED,
            LanWebAcceptedResponse(true, body.requestId, "电脑工作区结果已交给 Murong 核心")
        )
    }

    private fun handleWorkspaceChanges(
        session: IHTTPSession,
        client: LanWebClientSummary
    ): Response {
        if (!workspaceRateLimiter.tryAcquire(client.id)) {
            return errorResponse(Response.Status.FORBIDDEN, "workspace_rate_limited", "电脑工作区操作过于频繁")
        }
        val body = readJsonBody<LanWebWorkspaceChangeReportRequest>(
            session,
            maxBytes = LanWebContract.MAX_WORKSPACE_CHANGES_BODY_BYTES
        )
            ?: return errorResponse(Response.Status.BAD_REQUEST, "invalid_request", "工作区变化格式无效")
        computerWorkspaceBridge?.recordChanges(client.id, body)?.getOrElse { error ->
            return errorResponse(
                Response.Status.CONFLICT,
                "workspace_changes_conflict",
                safeError(error.message ?: "工作区变化无法接收")
            )
        } ?: return errorResponse(
            Response.Status.INTERNAL_ERROR,
            "workspace_unavailable",
            "电脑工作区桥接不可用"
        )
        return jsonResponse(
            Response.Status.ACCEPTED,
            LanWebAcceptedResponse(true, body.reportId, "电脑工作区变化已记录")
        )
    }

    private fun handleWorkspaceDisconnect(
        session: IHTTPSession,
        client: LanWebClientSummary
    ): Response {
        val body = readJsonBody<LanWebWorkspaceDisconnectRequest>(session)
            ?: return errorResponse(Response.Status.BAD_REQUEST, "invalid_request", "工作区断开格式无效")
        computerWorkspaceBridge?.disconnect(client.id, body.workspaceSessionId)
        return jsonResponse(
            Response.Status.OK,
            LanWebAcceptedResponse(true, body.workspaceSessionId, "电脑工作区已断开")
        )
    }

    private fun handleDesktopAgentRegister(
        session: IHTTPSession,
        client: LanWebClientSummary
    ): Response {
        if (!workspaceRateLimiter.tryAcquire(client.id)) {
            return errorResponse(Response.Status.FORBIDDEN, "desktop_agent_rate_limited", "桌面任务同步过于频繁")
        }
        val body = readJsonBody<LanWebDesktopAgentRegisterRequest>(session)
            ?: return errorResponse(Response.Status.BAD_REQUEST, "invalid_request", "桌面 Agent 注册格式无效")
        if (!accessStore.claimRequest(client.id, body.requestId)) {
            return errorResponse(Response.Status.CONFLICT, "duplicate_request", "该 request_id 已使用")
        }
        val bridge = desktopAgentBridge
            ?: return errorResponse(Response.Status.INTERNAL_ERROR, "desktop_agent_unavailable", "桌面任务桥接不可用")
        val state = bridge.register(client.id, body).getOrElse { error ->
            return errorResponse(
                Response.Status.CONFLICT,
                "desktop_agent_register_conflict",
                safeError(error.message ?: "桌面 Agent 无法注册")
            )
        }
        return jsonResponse(Response.Status.OK, state)
    }

    private fun handleDesktopAgentSnapshot(
        session: IHTTPSession,
        client: LanWebClientSummary
    ): Response {
        if (!workspaceRateLimiter.tryAcquire(client.id)) {
            return errorResponse(Response.Status.FORBIDDEN, "desktop_agent_rate_limited", "桌面任务同步过于频繁")
        }
        val body = readJsonBody<LanWebDesktopAgentSnapshotRequest>(
            session,
            maxBytes = LanWebContract.MAX_DESKTOP_AGENT_BODY_BYTES
        ) ?: return errorResponse(Response.Status.BAD_REQUEST, "invalid_request", "桌面任务快照格式无效或过大")
        val bridge = desktopAgentBridge
            ?: return errorResponse(Response.Status.INTERNAL_ERROR, "desktop_agent_unavailable", "桌面任务桥接不可用")
        bridge.publishSnapshot(client.id, body).getOrElse { error ->
            return errorResponse(
                Response.Status.CONFLICT,
                "desktop_agent_snapshot_conflict",
                safeError(error.message ?: "桌面任务快照已失效")
            )
        }
        return jsonResponse(
            Response.Status.ACCEPTED,
            LanWebAcceptedResponse(true, body.nodeSessionId, "桌面任务快照已接收")
        )
    }

    private fun handleDesktopAgentResult(
        session: IHTTPSession,
        client: LanWebClientSummary
    ): Response {
        if (!workspaceRateLimiter.tryAcquire(client.id)) {
            return errorResponse(Response.Status.FORBIDDEN, "desktop_agent_rate_limited", "桌面任务同步过于频繁")
        }
        val body = readJsonBody<LanWebDesktopAgentCommandResultRequest>(
            session,
            maxBytes = LanWebContract.MAX_DESKTOP_AGENT_BODY_BYTES
        ) ?: return errorResponse(Response.Status.BAD_REQUEST, "invalid_request", "桌面任务命令结果格式无效或过大")
        val bridge = desktopAgentBridge
            ?: return errorResponse(Response.Status.INTERNAL_ERROR, "desktop_agent_unavailable", "桌面任务桥接不可用")
        bridge.complete(client.id, body).getOrElse { error ->
            return errorResponse(
                Response.Status.CONFLICT,
                "desktop_agent_result_conflict",
                safeError(error.message ?: "桌面任务命令结果已失效")
            )
        }
        return jsonResponse(
            Response.Status.ACCEPTED,
            LanWebAcceptedResponse(true, body.requestId, "桌面任务命令结果已接收")
        )
    }

    private fun handleDesktopAgentDisconnect(
        session: IHTTPSession,
        client: LanWebClientSummary
    ): Response {
        val body = readJsonBody<LanWebDesktopAgentDisconnectRequest>(session)
            ?: return errorResponse(Response.Status.BAD_REQUEST, "invalid_request", "桌面 Agent 断开格式无效")
        desktopAgentBridge?.disconnect(client.id, body.nodeSessionId)
        return jsonResponse(
            Response.Status.OK,
            LanWebAcceptedResponse(true, body.nodeSessionId, "桌面 Agent 已断开")
        )
    }

    private fun handleDeviceSyncPush(session: IHTTPSession, client: LanWebClientSummary): Response {
        if (!mutationRateLimiter.tryAcquire("device-sync:${client.id}")) {
            return errorResponse(Response.Status.FORBIDDEN, "rate_limited", "凭据同步请求过于频繁")
        }
        val bridge = credentialSyncBridge
            ?: return errorResponse(Response.Status.NOT_IMPLEMENTED, "device_sync_unavailable", "当前版本未启用设备同步")
        val envelope = readJsonBody<LanWebDeviceSyncEnvelope>(session, LanWebContract.MAX_DEVICE_SYNC_BODY_BYTES)
            ?: return errorResponse(Response.Status.BAD_REQUEST, "invalid_sync_envelope", "设备同步请求格式无效")
        val key = validatedSyncKey(client, envelope, LanWebDeviceSyncCrypto.WINDOWS_TO_ANDROID)
            ?: return errorResponse(Response.Status.FORBIDDEN, "secure_pairing_required", "请使用新版 Murong 重新配对后再同步")
        return try {
            val plaintext = LanWebDeviceSyncCrypto.decrypt(key, envelope)
            val bundle = json.decodeFromString<LanWebCredentialSyncBundle>(plaintext)
            val result = runBlocking(Dispatchers.IO) { bridge.importBundle(bundle) }
            encryptedSyncResponse(key, envelope.requestId, json.encodeToString(result))
        } catch (error: Throwable) {
            errorResponse(
                Response.Status.BAD_REQUEST,
                "credential_sync_failed",
                safeError(error.message ?: "凭据同步失败"),
            )
        } finally {
            key.fill(0)
        }
    }

    private fun handleDeviceSyncPull(session: IHTTPSession, client: LanWebClientSummary): Response {
        if (!mutationRateLimiter.tryAcquire("device-sync:${client.id}")) {
            return errorResponse(Response.Status.FORBIDDEN, "rate_limited", "凭据同步请求过于频繁")
        }
        val bridge = credentialSyncBridge
            ?: return errorResponse(Response.Status.NOT_IMPLEMENTED, "device_sync_unavailable", "当前版本未启用设备同步")
        val envelope = readJsonBody<LanWebDeviceSyncEnvelope>(session, LanWebContract.MAX_DEVICE_SYNC_BODY_BYTES)
            ?: return errorResponse(Response.Status.BAD_REQUEST, "invalid_sync_envelope", "设备同步请求格式无效")
        val key = validatedSyncKey(client, envelope, LanWebDeviceSyncCrypto.WINDOWS_TO_ANDROID)
            ?: return errorResponse(Response.Status.FORBIDDEN, "secure_pairing_required", "请使用新版 Murong 重新配对后再同步")
        return try {
            val plaintext = LanWebDeviceSyncCrypto.decrypt(key, envelope)
            val options = json.decodeFromString<LanWebDeviceSyncOptions>(plaintext)
            require(
                options.includeProviderCredentials || options.includeCodexLogin || options.includeAgentSettings ||
                    options.includeKnowledge || options.includeMcp || options.includeSavedWorkflows
            ) { "至少选择一种同步内容" }
            val bundle = runBlocking(Dispatchers.IO) { bridge.exportBundle(options) }
            encryptedSyncResponse(key, envelope.requestId, json.encodeToString(bundle))
        } catch (error: Throwable) {
            errorResponse(
                Response.Status.BAD_REQUEST,
                "credential_sync_failed",
                safeError(error.message ?: "凭据同步失败"),
            )
        } finally {
            key.fill(0)
        }
    }

    private fun validatedSyncKey(
        client: LanWebClientSummary,
        envelope: LanWebDeviceSyncEnvelope,
        expectedDirection: String,
    ): ByteArray? {
        if (!client.secureSync || envelope.direction != expectedDirection) return null
        val now = System.currentTimeMillis()
        if (envelope.issuedAt < now - DEVICE_SYNC_CLOCK_WINDOW_MILLIS || envelope.issuedAt > now + 30_000L) return null
        if (!accessStore.claimRequest(client.id, envelope.requestId, now)) return null
        return accessStore.syncKey(client.id)
    }

    private fun encryptedSyncResponse(key: ByteArray, requestId: String, plaintext: String): Response {
        val response = LanWebDeviceSyncCrypto.encrypt(
            key = key,
            requestId = requestId,
            issuedAt = System.currentTimeMillis(),
            direction = LanWebDeviceSyncCrypto.ANDROID_TO_WINDOWS,
            plaintext = plaintext,
        )
        return jsonResponse(Response.Status.OK, response)
    }

    private fun handleEvents(session: IHTTPSession, client: LanWebClientSummary): Response {
        val counter = sseConnections.computeIfAbsent(client.id) { AtomicInteger(0) }
        if (counter.incrementAndGet() > LanWebContract.MAX_SSE_CONNECTIONS_PER_CLIENT) {
            counter.decrementAndGet()
            return errorResponse(Response.Status.FORBIDDEN, "too_many_streams", "该客户端的事件连接过多")
        }

        val input = PipedInputStream(SSE_PIPE_BUFFER_SIZE)
        val output = PipedOutputStream(input)
        val lastEventId = session.headers["last-event-id"]?.toLongOrNull()
        val streamJob: Job = serverScope.launch {
            try {
                BufferedWriter(OutputStreamWriter(output, StandardCharsets.UTF_8)).use { writer ->
                    val replay = eventHub.replayAfter(lastEventId, client.id)
                    if (lastEventId == null || replay == null) {
                        writeSseEvent(
                            writer,
                            LanWebEvent(
                                id = eventHub.currentSequence(),
                                type = "snapshot",
                                jsonData = json.encodeToString(chatBridge.liveState())
                            )
                        )
                    } else {
                        replay.forEach { writeSseEvent(writer, it) }
                        writeSseEvent(
                            writer,
                            LanWebEvent(
                                id = eventHub.currentSequence(),
                                type = "snapshot",
                                jsonData = json.encodeToString(chatBridge.liveState())
                            )
                        )
                    }
                    writer.write(": connected\n\n")
                    writer.flush()

                    val heartbeat = launch {
                        while (true) {
                            delay(SSE_HEARTBEAT_MILLIS)
                            synchronized(writer) {
                                writer.write(": heartbeat\n\n")
                                writer.flush()
                            }
                        }
                    }
                    try {
                        eventHub.events.collect { event ->
                            if ((lastEventId == null || event.id > lastEventId) && event.isVisibleTo(client.id)) {
                                synchronized(writer) {
                                    writeSseEvent(writer, event)
                                }
                            }
                        }
                    } finally {
                        heartbeat.cancel()
                    }
                }
            } catch (_: IOException) {
                // Normal browser disconnect.
            } finally {
                counter.decrementAndGet()
                runCatching { output.close() }
            }
        }

        val responseInput = object : FilterInputStream(input) {
            override fun close() {
                try {
                    super.close()
                } finally {
                    streamJob.cancel()
                }
            }
        }
        return newChunkedResponse(
            Response.Status.OK,
            "text/event-stream; charset=utf-8",
            responseInput
        ).secureHeaders(cacheStatic = false).apply {
            addHeader("Connection", "keep-alive")
            addHeader("X-Accel-Buffering", "no")
        }
    }

    private fun validateRequestSource(session: IHTTPSession): Response? {
        if (!LanWebSecurity.isAllowedRemoteAddress(session.remoteIpAddress)) {
            return errorResponse(Response.Status.FORBIDDEN, "remote_forbidden", "只允许局域网客户端访问")
        }
        val host = session.headers["host"]
        if (!LanWebSecurity.isAllowedHost(host, bindAddress, listeningPort)) {
            return errorResponse(Response.Status.FORBIDDEN, "host_forbidden", "Host 不在当前局域网服务范围")
        }
        if (!LanWebSecurity.isAllowedOrigin(session.headers["origin"], host, listeningPort)) {
            return errorResponse(Response.Status.FORBIDDEN, "origin_forbidden", "Origin 与当前服务不匹配")
        }
        return null
    }

    private fun authenticate(session: IHTTPSession): LanWebClientSummary? {
        val header = session.headers["authorization"]?.trim().orEmpty()
        val bearerToken = header.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
            ?.substringAfter(' ')
            ?.trim()
        val cookieToken = session.headers["cookie"]
            ?.split(';')
            ?.asSequence()
            ?.map { it.trim() }
            ?.firstOrNull { it.startsWith("$SESSION_COOKIE_NAME=") }
            ?.substringAfter('=')
            ?.trim()
        val token = bearerToken?.takeIf { it.isNotBlank() }
            ?: cookieToken?.takeIf { it.isNotBlank() }
            ?: return null
        return accessStore.authenticate(token)
    }

    private fun sessionCookie(token: String, maxAgeSeconds: Int): String =
        "$SESSION_COOKIE_NAME=$token; Path=/; HttpOnly; SameSite=Strict; Max-Age=$maxAgeSeconds"

    private inline fun <reified T> readJsonBody(
        session: IHTTPSession,
        maxBytes: Int = LanWebContract.MAX_REQUEST_BODY_BYTES
    ): T? {
        val contentType = session.headers["content-type"]?.substringBefore(';')?.trim()
        if (!contentType.equals("application/json", ignoreCase = true)) return null
        val length = session.headers["content-length"]?.toIntOrNull() ?: return null
        if (length <= 0 || length > maxBytes) return null
        val bytes = ByteArray(length)
        var offset = 0
        while (offset < bytes.size) {
            val read = session.inputStream.read(bytes, offset, bytes.size - offset)
            if (read < 0) return null
            offset += read
        }
        val text = String(bytes, StandardCharsets.UTF_8)
        return runCatching { json.decodeFromString<T>(text) }.getOrNull()
    }

    private fun serveStatic(path: String): Response {
        val asset = staticAssets[path]
            ?: return errorResponse(Response.Status.NOT_FOUND, "not_found", "静态资源不存在")
        return newFixedLengthResponse(Response.Status.OK, asset.mimeType, asset.text)
            .secureHeaders(cacheStatic = true)
    }

    private inline fun <reified T> jsonResponse(status: Response.Status, body: T): Response =
        newFixedLengthResponse(status, "application/json; charset=utf-8", json.encodeToString(body))
            .secureHeaders(cacheStatic = false)

    private fun errorResponse(
        status: Response.Status,
        code: String,
        message: String
    ): Response = jsonResponse(status, LanWebErrorResponse(message.take(500), code))

    private fun Response.secureHeaders(cacheStatic: Boolean): Response = apply {
        addHeader("Cache-Control", if (cacheStatic) "no-cache" else "no-store")
        addHeader("X-Content-Type-Options", "nosniff")
        addHeader("X-Frame-Options", "DENY")
        addHeader("Referrer-Policy", "no-referrer")
        addHeader(
            "Content-Security-Policy",
            "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; " +
                "connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'"
        )
        addHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=()")
    }

    private fun writeSseEvent(writer: BufferedWriter, event: LanWebEvent) {
        writer.write("id: ${event.id}\n")
        writer.write("event: ${event.type}\n")
        event.jsonData.lineSequence().forEach { line -> writer.write("data: $line\n") }
        writer.write("\n")
        writer.flush()
    }

    private fun loadAsset(path: String, mimeType: String): StaticAsset {
        return StaticAsset(mimeType, loadAssetText(path))
    }

    private fun safeError(message: String): String =
        com.murong.agent.core.doctor.SensitiveDataSanitizer
            .sanitizeText(message, redactPaths = true)
            .take(500)

    private data class StaticAsset(
        val mimeType: String,
        val text: String
    )

    private companion object {
        const val SSE_PIPE_BUFFER_SIZE = 128 * 1024
        const val SSE_HEARTBEAT_MILLIS = 15_000L
        const val SESSION_COOKIE_NAME = "murong_lan_session"
        const val SESSION_COOKIE_MAX_AGE_SECONDS = 30 * 24 * 60 * 60
        const val DEVICE_SYNC_CLOCK_WINDOW_MILLIS = 2 * 60 * 1000L
    }
}
