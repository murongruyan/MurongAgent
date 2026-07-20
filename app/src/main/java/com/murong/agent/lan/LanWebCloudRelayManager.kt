package com.murong.agent.lan

import com.murong.agent.core.doctor.SensitiveDataSanitizer
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
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
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.RequestBody.Companion.toRequestBody
import okio.ByteString
import okio.ByteString.Companion.toByteString

@Singleton
class LanWebCloudRelayManager @Inject internal constructor(
    private val configStore: LanWebCloudRelayConfigStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val webSocketClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val localHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    private val mutableState = MutableStateFlow(publicState(configStore.load()))
    private val activeCalls = ConcurrentHashMap<String, Call>()
    private val incomingRequests = ConcurrentHashMap<String, IncomingRelayRequest>()
    private val requestSlots = ConcurrentHashMap.newKeySet<String>()
    private var connectionJob: Job? = null
    private var activeSession: RelaySession? = null

    internal val state: StateFlow<LanWebCloudRelayState> = mutableState.asStateFlow()

    internal fun configure(enabled: Boolean, relayUrl: String): Result<LanWebCloudRelayState> = runCatching {
        check(connectionJob == null) { "请先停止电脑节点服务再修改云中继" }
        val config = configStore.configure(enabled, relayUrl)
        publicState(config).also { mutableState.value = it }
    }

    internal fun regenerate(): Result<LanWebCloudRelayState> = runCatching {
        check(connectionJob == null) { "请先停止电脑节点服务再更换云中继连接码" }
        val (config, code) = configStore.regenerate()
        publicState(config, code).also { mutableState.value = it }
    }

    internal fun configured(): LanWebCloudRelayConfig = configStore.load()

    internal fun start(localPort: Int): Result<Unit> = runCatching {
        if (connectionJob != null) return@runCatching
        val config = configStore.load()
        require(config.enabled) { "云中继尚未启用" }
        require(config.relayUrl.isNotBlank() && config.roomId.isNotBlank()) { "云中继配置不完整" }
        val storedSecret = configStore.secret(config)
        require(storedSecret != null) { "云中继端到端密钥不可用，请重新生成连接码" }
        storedSecret.fill(0)
        mutableState.value = publicState(config).copy(
            running = true,
            connecting = true,
            connected = false,
            status = "正在连接云中继…",
            error = null,
        )
        connectionJob = scope.launch { reconnectLoop(config, localPort) }
    }

    internal fun stop() {
        connectionJob?.cancel()
        connectionJob = null
        activeSession?.close()
        activeSession = null
        activeCalls.values.forEach(Call::cancel)
        activeCalls.clear()
        incomingRequests.clear()
        requestSlots.clear()
        val config = configStore.load()
        mutableState.value = publicState(config).copy(status = if (config.enabled) "云中继已停止" else "云中继未启用")
    }

    private suspend fun reconnectLoop(config: LanWebCloudRelayConfig, localPort: Int) {
        var backoffMillis = 1_000L
        while (scope.isActive && connectionJob != null) {
            val result = runCatching { connectOnce(config, localPort) }
            if (connectionJob == null) return
            val message = result.exceptionOrNull()?.message ?: "云中继连接已断开"
            mutableState.value = publicState(config).copy(
                running = true,
                connecting = false,
                connected = false,
                status = "云中继已断开，${backoffMillis / 1_000} 秒后重连",
                error = safeError(message),
            )
            delay(backoffMillis)
            backoffMillis = (backoffMillis * 2).coerceAtMost(20_000L)
            mutableState.value = mutableState.value.copy(connecting = true, status = "正在重新连接云中继…")
        }
    }

    private suspend fun connectOnce(config: LanWebCloudRelayConfig, localPort: Int) {
        val secret = requireNotNull(configStore.secret(config)) { "云中继端到端密钥不可用" }
        val completed = CompletableDeferred<Throwable?>()
        val incoming = Channel<ByteArray>(capacity = 64)
        val request = Request.Builder()
            .url("${config.relayUrl}?room=${config.roomId}&role=${LanWebCloudRelayProtocol.ROLE_PHONE}&v=1")
            .header("Sec-WebSocket-Protocol", LanWebCloudRelayProtocol.SUBPROTOCOL)
            .build()
        lateinit var session: RelaySession
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (response.header("Sec-WebSocket-Protocol") != LanWebCloudRelayProtocol.SUBPROTOCOL) {
                    webSocket.close(1008, "subprotocol mismatch")
                    completed.complete(IllegalStateException("云中继协议协商失败"))
                    return
                }
                session.webSocket = webSocket
                activeSession = session
                mutableState.value = publicState(config).copy(
                    running = true,
                    connecting = false,
                    connected = true,
                    status = "云中继已连接，等待 Murong Desktop",
                    error = null,
                )
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (!incoming.trySend(bytes.toByteArray()).isSuccess) {
                    webSocket.close(1008, "relay input queue full")
                    completed.complete(IllegalStateException("云中继接收队列已满"))
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                webSocket.close(1003, "binary encrypted frames only")
                completed.complete(IllegalStateException("云中继返回了非加密数据"))
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                completed.complete(IllegalStateException("云中继连接已关闭"))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                completed.complete(t)
            }
        }
        session = RelaySession(config.roomId, secret, incoming, completed, localPort)
        val webSocket = webSocketClient.newWebSocket(request, listener)
        session.webSocket = webSocket
        val reader = scope.launch { processIncoming(session) }
        reader.invokeOnCompletion { error ->
            if (error != null) completed.complete(error)
        }
        try {
            val error = completed.await()
            if (error != null) throw error
        } finally {
            reader.cancel()
            incoming.close()
            session.close()
            if (activeSession === session) activeSession = null
            activeCalls.values.forEach(Call::cancel)
            activeCalls.clear()
            incomingRequests.clear()
            requestSlots.clear()
        }
    }

    private suspend fun processIncoming(session: RelaySession) {
        for (encoded in session.incoming) {
            val message = LanWebCloudRelayProtocol.decrypt(
                secret = session.secret,
                roomId = session.roomId,
                senderRole = LanWebCloudRelayProtocol.ROLE_DESKTOP,
                encoded = encoded,
            )
            if (!session.replay.claim(message.messageId, message.issuedAt)) continue
            when (message.kind) {
                "request_start" -> {
                    check(requestSlots.size < MAX_CONCURRENT_REQUESTS && requestSlots.add(message.requestId)) {
                        "云中继并发请求过多或请求重复"
                    }
                    val previous = incomingRequests.putIfAbsent(
                        message.requestId,
                        IncomingRelayRequest(message.method, message.path, message.headers)
                    )
                    if (previous != null) {
                        requestSlots.remove(message.requestId)
                        error("云中继请求重复开始")
                    }
                }
                "request_chunk" -> {
                    val request = incomingRequests[message.requestId] ?: error("云中继请求不存在")
                    val chunk = Base64.getUrlDecoder().decode(message.chunk)
                    check(request.body.size() + chunk.size <= LanWebCloudRelayProtocol.MAX_BODY_BYTES) {
                        "云中继请求正文超过大小限制"
                    }
                    request.body.write(chunk)
                }
                "request_end" -> {
                    val request = incomingRequests.remove(message.requestId) ?: error("云中继请求不存在")
                    scope.launch { proxyRequest(session, message.requestId, request) }
                }
                "cancel" -> {
                    incomingRequests.remove(message.requestId)
                    activeCalls.remove(message.requestId)?.cancel()
                    requestSlots.remove(message.requestId)
                }
                else -> error("手机云中继收到不允许的消息类型")
            }
        }
    }

    private fun proxyRequest(session: RelaySession, requestId: String, incoming: IncomingRelayRequest) {
        val mediaType = incoming.headers.entries
            .firstOrNull { it.key.equals("content-type", ignoreCase = true) }
            ?.value?.firstOrNull()?.toMediaTypeOrNull()
        val requestBuilder = Request.Builder()
            .url("http://127.0.0.1:${session.localPort}${incoming.path}")
        LanWebCloudRelayProtocol.filterHeaders(incoming.headers, request = true).forEach { (name, values) ->
            values.forEach { requestBuilder.addHeader(name, it) }
        }
        if (incoming.method == "POST") {
            requestBuilder.post(incoming.body.toByteArray().toRequestBody(mediaType))
        } else {
            requestBuilder.get()
        }
        val call = localHttpClient.newCall(requestBuilder.build())
        activeCalls[requestId] = call
        try {
            call.execute().use { response ->
                val start = LanWebCloudRelayProtocol.newMessage(requestId, "response_start").copy(
                    status = response.code,
                    headers = LanWebCloudRelayProtocol.filterHeaders(response.headers.toMultimap(), request = false),
                )
                session.send(start)
                val stream = response.body?.byteStream()
                if (stream != null) {
                    val buffer = ByteArray(LanWebCloudRelayProtocol.CHUNK_BYTES)
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        val chunk = LanWebCloudRelayProtocol.newMessage(requestId, "response_chunk").copy(
                            chunk = Base64.getUrlEncoder().withoutPadding().encodeToString(buffer.copyOf(count)),
                        )
                        session.send(chunk)
                    }
                }
                session.send(LanWebCloudRelayProtocol.newMessage(requestId, "response_end"))
            }
        } catch (error: Throwable) {
            if (!call.isCanceled()) {
                runCatching {
                    session.send(
                        LanWebCloudRelayProtocol.newMessage(requestId, "error").copy(
                            error = safeError(error.message ?: "手机本地节点请求失败")
                        )
                    )
                }
            }
        } finally {
            activeCalls.remove(requestId, call)
            requestSlots.remove(requestId)
        }
    }

    private fun publicState(config: LanWebCloudRelayConfig, shareCode: String? = configStore.shareCode(config)) =
        LanWebCloudRelayState(
            configured = config.roomId.isNotBlank() && shareCode != null,
            enabled = config.enabled,
            relayUrl = config.relayUrl,
            roomId = config.roomId,
            shareCode = shareCode,
            status = if (config.enabled) "云中继已配置" else "云中继未启用",
        )

    private fun safeError(message: String): String =
        SensitiveDataSanitizer.sanitizeText(message, redactPaths = true).take(500)

    private data class IncomingRelayRequest(
        val method: String,
        val path: String,
        val headers: Map<String, List<String>>,
        val body: ByteArrayOutputStream = ByteArrayOutputStream(),
    )

    private inner class RelaySession(
        val roomId: String,
        val secret: ByteArray,
        val incoming: Channel<ByteArray>,
        val completed: CompletableDeferred<Throwable?>,
        val localPort: Int,
    ) {
        val replay = LanWebCloudRelayReplayCache()
        @Volatile var webSocket: WebSocket? = null

        fun send(message: LanWebCloudRelayTunnelMessage) {
            val payload = LanWebCloudRelayProtocol.encrypt(
                secret = secret,
                roomId = roomId,
                senderRole = LanWebCloudRelayProtocol.ROLE_PHONE,
                message = message,
            )
            check(webSocket?.send(payload.toByteString()) == true) { "云中继发送失败" }
        }

        fun close() {
            webSocket?.close(1000, "phone relay stopped")
            webSocket?.cancel()
            webSocket = null
            secret.fill(0)
        }
    }
}

internal data class LanWebCloudRelayState(
    val configured: Boolean = false,
    val enabled: Boolean = false,
    val relayUrl: String = "",
    val roomId: String = "",
    val shareCode: String? = null,
    val running: Boolean = false,
    val connecting: Boolean = false,
    val connected: Boolean = false,
    val status: String = "云中继未启用",
    val error: String? = null,
)

private const val MAX_CONCURRENT_REQUESTS = 16
