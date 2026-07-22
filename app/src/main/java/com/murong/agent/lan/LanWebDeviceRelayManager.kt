package com.murong.agent.lan

import com.murong.agent.core.doctor.SensitiveDataSanitizer
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.security.MessageDigest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

internal class LanWebDeviceRelayManager(
    private val identity: LanWebDeviceIdentity,
    private val deviceTunnelManager: LanWebDeviceTunnelManager,
    private val isBlocked: (deviceId: String, fingerprint: String) -> Boolean,
	private val githubAccountTrust: LanWebGitHubAccountTrust? = null,
    private val onConnectionAccepted: (deviceId: String, fingerprint: String) -> Unit = { _, _ -> },
    private val relayUrl: String = LanWebDeviceRelayProtocol.OFFICIAL_RELAY_URL,
    private val platform: String = "android",
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val webSocketClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val mutableState = MutableStateFlow(LanWebDeviceRelayState())
    private val replay = DeviceInviteReplayCache()
    private val pendingResponses = ConcurrentHashMap<String, CompletableDeferred<LanWebDeviceRelayMessage>>()
    private var connectionJob: Job? = null
    @Volatile private var activeSocket: WebSocket? = null

    val state: StateFlow<LanWebDeviceRelayState> = mutableState.asStateFlow()

    fun start(localPort: Int): Result<Unit> = runCatching {
        if (connectionJob != null) return@runCatching
        require(localPort in 1..65_535) { "本机节点端口无效" }
        LanWebDeviceRelayProtocol.tunnelUrlForDeviceRelay(relayUrl)
        mutableState.value = LanWebDeviceRelayState(
            running = true,
            connecting = true,
            status = "正在登记公网本机 ID…",
        )
        connectionJob = scope.launch { reconnectLoop(localPort) }
    }

    fun stop() {
        connectionJob?.cancel()
        connectionJob = null
        activeSocket?.close(1000, "device relay stopped")
        activeSocket?.cancel()
        activeSocket = null
        replay.clear()
        failPending(IllegalStateException("设备连接服务已停止"))
        mutableState.value = LanWebDeviceRelayState(status = "公网本机 ID 已停止")
    }

    suspend fun requestConnection(
        targetDeviceId: String,
        deviceName: String,
        authMethod: String = "",
        secret: String = "",
    ): Result<Unit> = runCatching {
        val socket = activeSocket ?: error("远程控制尚未联网，请稍后重试")
        check(mutableState.value.connected) { "远程控制尚未联网，请稍后重试" }
        val normalizedTarget = requireNotNull(LanWebDeviceIdentity.normalizeDeviceId(targetDeviceId)) {
            "请输入正确的 16 位设备码"
        }
        mutableState.value = mutableState.value.copy(
            outgoingConnection = true,
            outgoingConnectionStatus = "正在查找 ${normalizedTarget.chunked(4).joinToString("-")}…",
            error = null,
        )
        val lookup = LanWebDeviceRelayProtocol.lookup(identity, normalizedTarget)
        val peer = sendAndAwait(socket, lookup, "peer", 20_000L)
        LanWebDeviceRelayProtocol.validatePeer(peer, normalizedTarget)
        val fingerprint = requireNotNull(LanWebDeviceIdentity.fingerprintForPublicKey(peer.devicePublicKey)) {
            "目标设备指纹无效"
        }
        require(!isBlocked(normalizedTarget, fingerprint)) { "目标设备已被拉黑" }
        // The user explicitly selected this signed stable identity. Remember it before the
        // acknowledgement so the desktop can immediately call back without a race.
        onConnectionAccepted(normalizedTarget, fingerprint)
        var expectedServerProof = ""
		val request = if (authMethod.isBlank()) {
			val requestId = LanWebDeviceRelayProtocol.requestId("connect")
			val issuedAt = System.currentTimeMillis()
			val ephemeralPublicKey = LanWebDeviceIdentity.publicKey(LanWebDeviceLinkCrypto.ephemeralKeyPair())
			val githubDraft = LanWebDeviceRelayProtocol.connectionRequest(
				identity = identity, targetDeviceId = normalizedTarget, deviceName = deviceName,
				authMethod = LanWebTrustSource.GITHUB_ACCOUNT,
				ephemeralPublicKey = ephemeralPublicKey,
				requestId = requestId, now = issuedAt,
			)
			mutableState.value = mutableState.value.copy(outgoingConnectionStatus = "正在检查同一 GitHub 账号…")
			val githubTicket = githubAccountTrust?.issue(githubDraft, peer.devicePublicKey, fingerprint, issuedAt)
			if (githubTicket != null) {
				LanWebDeviceRelayProtocol.connectionRequest(
					identity = identity, targetDeviceId = normalizedTarget, deviceName = deviceName,
					authMethod = LanWebTrustSource.GITHUB_ACCOUNT, authProof = githubTicket,
					ephemeralPublicKey = ephemeralPublicKey,
					requestId = requestId, now = issuedAt,
				)
			} else {
				LanWebDeviceRelayProtocol.connectionRequest(
					identity = identity, targetDeviceId = normalizedTarget, deviceName = deviceName,
					ephemeralPublicKey = ephemeralPublicKey,
				)
			}
        } else {
            require(secret.isNotBlank()) { "请输入临时验证码或安全密码" }
            val authentication = LanWebDeviceRelayProtocol.authBegin(
                identity = identity,
                targetDeviceId = normalizedTarget,
                deviceName = deviceName,
                authMethod = authMethod,
                clientNonce = LanWebDeviceRelayProtocol.clientNonce(),
            )
            mutableState.value = mutableState.value.copy(outgoingConnectionStatus = "正在安全验证密码…")
            val challenge = sendAndAwait(socket, authentication, "auth_challenge", 20_000L)
            LanWebDeviceRelayProtocol.validateAuthChallenge(challenge, authentication, peer.devicePublicKey)
            val proof = LanWebDeviceRelayProtocol.scramClientProof(
                secret = secret, request = authentication, response = challenge, fingerprint = identity.snapshot.publicKeyFingerprint,
            )
            expectedServerProof = proof.expectedServerProof
            LanWebDeviceRelayProtocol.connectionRequest(
                identity = identity,
                targetDeviceId = normalizedTarget,
                deviceName = deviceName,
                authMethod = authMethod,
                clientNonce = authentication.clientNonce,
                authProof = challenge.sessionId + "." + proof.proof,
				ephemeralPublicKey = authentication.ephemeralPublicKey,
                requestId = authentication.requestId,
                now = authentication.issuedAt,
            )
        }
        mutableState.value = mutableState.value.copy(outgoingConnectionStatus = "等待对方确认连接…")
        val acknowledgement = sendAndAwait(socket, request, "connect_ack", 2 * 60_000L)
        LanWebDeviceRelayProtocol.validateConnectionAcknowledgement(
            message = acknowledgement,
            requestId = request.requestId,
            sourceDeviceId = normalizedTarget,
            targetDeviceId = identity.snapshot.deviceId,
            targetPublicKey = peer.devicePublicKey,
        )
        if (acknowledgement.status != "accepted") {
            error(acknowledgement.error.ifBlank { "对方拒绝了连接申请" })
        }
        if (expectedServerProof.isNotBlank()) {
            val expected = LanWebScramCrypto.decode(expectedServerProof, LanWebScramCrypto.PROOF_BYTES)
            val actual = LanWebScramCrypto.decode(acknowledgement.serverProof, LanWebScramCrypto.PROOF_BYTES)
            val valid = expected != null && actual != null && MessageDigest.isEqual(expected, actual)
            expected?.fill(0)
            actual?.fill(0)
            require(valid) { "设备返回的密码认证证明无效" }
        }
        mutableState.value = mutableState.value.copy(
            outgoingConnection = false,
            outgoingConnectionStatus = "对方已接受，正在建立安全连接…",
        )
    }.onFailure { error ->
        mutableState.value = mutableState.value.copy(
            outgoingConnection = false,
            outgoingConnectionStatus = safeError(error.message ?: "连接设备失败"),
            error = safeError(error.message ?: "连接设备失败"),
        )
    }

    private suspend fun sendAndAwait(
        webSocket: WebSocket,
        message: LanWebDeviceRelayMessage,
        expectedKind: String,
        timeoutMillis: Long,
    ): LanWebDeviceRelayMessage {
        val deferred = CompletableDeferred<LanWebDeviceRelayMessage>()
        check(pendingResponses.putIfAbsent(message.requestId, deferred) == null) { "设备连接请求重复" }
        try {
            check(webSocket.send(LanWebDeviceRelayProtocol.encode(message))) { "无法发送设备连接请求" }
            return withTimeout(timeoutMillis) { deferred.await() }.also { response ->
                if (response.kind == "error") error(response.error.ifBlank { "目标设备不在线或设备码不正确" })
                if (response.kind == "connect_ack" && response.status == "rejected") {
                    error(response.error.ifBlank { "对方设备拒绝了密码认证" })
                }
                require(response.kind == expectedKind) { "设备连接响应类型无效" }
            }
        } finally {
            pendingResponses.remove(message.requestId, deferred)
        }
    }

    private suspend fun reconnectLoop(localPort: Int) {
        var backoffMillis = 1_000L
        while (scope.isActive && connectionJob != null) {
            val result = runCatching { connectOnce(localPort) }
            if (connectionJob == null) return
            mutableState.value = LanWebDeviceRelayState(
                running = true,
                connected = false,
                connecting = false,
                status = "公网本机 ID 已离线，${backoffMillis / 1_000} 秒后重连",
                error = safeError(result.exceptionOrNull()?.message ?: "公网设备中继已断开"),
            )
            delay(backoffMillis)
            backoffMillis = (backoffMillis * 2).coerceAtMost(20_000L)
            mutableState.value = mutableState.value.copy(connecting = true, status = "正在重新登记公网本机 ID…")
        }
    }

    private suspend fun connectOnce(localPort: Int) {
        val completed = CompletableDeferred<Throwable?>()
        val incoming = Channel<String>(capacity = 32)
        val request = Request.Builder()
            .url(relayUrl)
            .header("Sec-WebSocket-Protocol", LanWebDeviceRelayProtocol.SUBPROTOCOL)
            .build()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (response.header("Sec-WebSocket-Protocol") != LanWebDeviceRelayProtocol.SUBPROTOCOL) {
                    webSocket.close(1008, "subprotocol mismatch")
                    completed.complete(IllegalStateException("公网设备中继协议协商失败"))
                    return
                }
                activeSocket = webSocket
                runCatching {
                    val registration = LanWebDeviceRelayProtocol.registration(identity, platform)
                    check(webSocket.send(LanWebDeviceRelayProtocol.encode(registration))) { "无法登记公网本机 ID" }
                }.onFailure(completed::complete)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (!incoming.trySend(text).isSuccess) {
                    webSocket.close(1008, "device relay input queue full")
                    completed.complete(IllegalStateException("公网设备中继接收队列已满"))
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                webSocket.close(1003, "JSON text frames only")
                completed.complete(IllegalStateException("公网设备中继返回了非 JSON 数据"))
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                completed.complete(IllegalStateException("公网设备中继连接已关闭"))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                completed.complete(t)
            }
        }
        val webSocket = webSocketClient.newWebSocket(request, listener)
        activeSocket = webSocket
        val processor = scope.launch {
            processIncoming(webSocket, incoming, localPort)
        }
        processor.invokeOnCompletion { error ->
            if (error != null) completed.complete(error)
        }
        try {
            val error = completed.await()
            if (error != null) throw error
        } finally {
            processor.cancel()
            incoming.close()
            webSocket.close(1000, "device relay reconnect")
            webSocket.cancel()
            if (activeSocket === webSocket) activeSocket = null
            failPending(IllegalStateException("设备连接服务已断开"))
        }
    }

    private suspend fun processIncoming(webSocket: WebSocket, incoming: Channel<String>, localPort: Int) {
        val registration = incoming.receive().let(LanWebDeviceRelayProtocol::decode)
        val snapshot = identity.snapshot
        require(
            registration.kind == "registered" &&
                registration.deviceId == snapshot.deviceId &&
                registration.devicePublicKey == snapshot.publicKey &&
                LanWebDeviceRelayProtocol.validClock(registration.issuedAt)
        ) { "公网设备中继注册响应无效" }
        mutableState.value = LanWebDeviceRelayState(
            running = true,
            connecting = false,
            connected = true,
            status = "公网本机 ID 在线：${snapshot.displayId}",
        )
        for (encoded in incoming) {
            val message = LanWebDeviceRelayProtocol.decode(encoded)
            when (message.kind) {
                "invite" -> handleInvite(webSocket, message, localPort)
                "peer", "auth_challenge", "connect_ack", "error" -> pendingResponses[message.requestId]?.complete(message)
                else -> error("公网设备中继返回了不允许的消息")
            }
        }
    }

    private fun failPending(error: Throwable) {
        pendingResponses.values.forEach { it.completeExceptionally(error) }
        pendingResponses.clear()
    }

    private suspend fun handleInvite(webSocket: WebSocket, message: LanWebDeviceRelayMessage, localPort: Int) {
        LanWebDeviceRelayProtocol.validateInvite(message, identity.snapshot)
        if (!replay.claim(message.requestId, message.issuedAt)) return
        val fingerprint = requireNotNull(LanWebDeviceIdentity.fingerprintForPublicKey(message.sourcePublicKey)) {
            "公网设备邀请指纹无效"
        }
        if (isBlocked(message.sourceDeviceId, fingerprint)) {
            sendAcknowledgement(webSocket, message, accepted = false, error = "设备已被拉黑")
            return
        }
        val room = runCatching { LanWebDeviceRelayProtocol.decryptInvitation(identity, message) }
            .getOrElse { error ->
                sendAcknowledgement(webSocket, message, accepted = false, error = safeError(error.message ?: "邀请无效"))
                return
            }
        try {
            val opened = deviceTunnelManager.openTemporaryRoom(
                relayUrl = LanWebDeviceRelayProtocol.tunnelUrlForDeviceRelay(relayUrl),
                roomId = room.roomId,
                secret = room.secret,
                localPort = localPort,
            )
            if (opened.isSuccess) {
                sendAcknowledgement(webSocket, message, accepted = true)
            } else {
                sendAcknowledgement(
                    webSocket,
                    message,
                    accepted = false,
                    error = safeError(opened.exceptionOrNull()?.message ?: "无法建立一次性隧道"),
                )
            }
        } finally {
            room.secret.fill(0)
        }
    }

    private fun sendAcknowledgement(
        webSocket: WebSocket,
        invitation: LanWebDeviceRelayMessage,
        accepted: Boolean,
        error: String = "",
    ) {
        val acknowledgement = LanWebDeviceRelayProtocol.acknowledgement(
            identity = identity,
            invitation = invitation,
            accepted = accepted,
            error = error,
        )
        check(webSocket.send(LanWebDeviceRelayProtocol.encode(acknowledgement))) {
            "无法发送公网设备邀请确认"
        }
    }

    private fun safeError(message: String): String =
        SensitiveDataSanitizer.sanitizeText(message, redactPaths = true).take(300)
}

internal data class LanWebDeviceRelayState(
    val running: Boolean = false,
    val connecting: Boolean = false,
    val connected: Boolean = false,
    val status: String = "公网本机 ID 未启动",
    val error: String? = null,
    val outgoingConnection: Boolean = false,
    val outgoingConnectionStatus: String = "",
)

private class DeviceInviteReplayCache {
    private val entries = linkedMapOf<String, Long>()

    @Synchronized
    fun claim(requestId: String, issuedAt: Long, now: Long = System.currentTimeMillis()): Boolean {
        entries.entries.removeAll { it.value < now - WINDOW_MILLIS }
        while (entries.size >= MAX_ENTRIES) entries.remove(entries.keys.first())
        if (requestId in entries) return false
        entries[requestId] = issuedAt
        return true
    }

    @Synchronized
    fun clear() = entries.clear()

    private companion object {
        const val WINDOW_MILLIS = 5 * 60 * 1_000L
        const val MAX_ENTRIES = 256
    }
}
