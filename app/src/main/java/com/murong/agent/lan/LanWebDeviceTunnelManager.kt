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
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

/** Internal encrypted tunnel used only after Relay v2 has authenticated both stable device IDs. */
@Singleton
class LanWebDeviceTunnelManager @Inject internal constructor() {
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
    private val temporaryMutex = Mutex()
    private var temporaryJob: Job? = null
    private var activeTemporarySession: RelaySession? = null

    internal suspend fun openTemporaryRoom(
        relayUrl: String,
        roomId: String,
        secret: ByteArray,
        localPort: Int,
    ): Result<Unit> = runCatching {
        temporaryMutex.withLock {
            val normalizedUrl = LanWebDeviceTunnelProtocol.normalizeRelayUrl(relayUrl)
            LanWebDeviceTunnelProtocol.requireRoomCredentials(roomId, secret)
            temporaryJob?.cancel()
            activeTemporarySession?.close()
            activeTemporarySession = null
            val ready = CompletableDeferred<Unit>()
            val ownedSecret = secret.copyOf()
            val job = scope.launch {
                try {
                    runCatching {
                        connectRoom(normalizedUrl, roomId, ownedSecret, localPort, ready)
                    }.onFailure { error ->
                        if (!ready.isCompleted) ready.completeExceptionally(error)
                    }
                } finally {
                    ownedSecret.fill(0)
                }
            }
            temporaryJob = job
            try {
                withTimeout(20_000L) { ready.await() }
            } catch (error: Throwable) {
                job.cancel()
                activeTemporarySession?.close()
                activeTemporarySession = null
                throw error
            }
        }
    }

    internal fun stop() {
        temporaryJob?.cancel()
        temporaryJob = null
        activeTemporarySession?.close()
        activeTemporarySession = null
    }

    private suspend fun connectRoom(
        relayUrl: String,
        roomId: String,
        secret: ByteArray,
        localPort: Int,
        ready: CompletableDeferred<Unit>,
    ) {
        val completed = CompletableDeferred<Throwable?>()
        val incoming = Channel<ByteArray>(capacity = 64)
        val request = Request.Builder()
            .url("$relayUrl?room=$roomId&role=${LanWebDeviceTunnelProtocol.ROLE_PHONE}&v=2")
            .header("Sec-WebSocket-Protocol", LanWebDeviceTunnelProtocol.SUBPROTOCOL)
            .build()
        lateinit var session: RelaySession
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (response.header("Sec-WebSocket-Protocol") != LanWebDeviceTunnelProtocol.SUBPROTOCOL) {
                    webSocket.close(1008, "subprotocol mismatch")
                    val error = IllegalStateException("加密隧道协议协商失败")
                    if (!ready.isCompleted) ready.completeExceptionally(error)
                    completed.complete(error)
                    return
                }
                session.webSocket = webSocket
                activeTemporarySession = session
                if (!ready.isCompleted) ready.complete(Unit)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (!incoming.trySend(bytes.toByteArray()).isSuccess) {
                    webSocket.close(1008, "relay input queue full")
                    completed.complete(IllegalStateException("加密隧道接收队列已满"))
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                webSocket.close(1003, "binary encrypted frames only")
                completed.complete(IllegalStateException("加密隧道返回了非加密数据"))
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                val error = IllegalStateException("加密隧道已关闭")
                if (!ready.isCompleted) ready.completeExceptionally(error)
                completed.complete(error)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!ready.isCompleted) ready.completeExceptionally(t)
                completed.complete(t)
            }
        }
        session = RelaySession(roomId, secret, incoming, completed, localPort)
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
            if (activeTemporarySession === session) activeTemporarySession = null
        }
    }

    private suspend fun processIncoming(session: RelaySession) {
        for (encoded in session.incoming) {
            val message = LanWebDeviceTunnelProtocol.decrypt(
                secret = session.secret,
                roomId = session.roomId,
                senderRole = LanWebDeviceTunnelProtocol.ROLE_DESKTOP,
                encoded = encoded,
            )
            if (!session.replay.claim(message.messageId, message.issuedAt)) continue
            when (message.kind) {
                "request_start" -> {
                    check(session.requestSlots.size < MAX_CONCURRENT_REQUESTS && session.requestSlots.add(message.requestId)) {
                        "加密隧道并发请求过多或请求重复"
                    }
                    val previous = session.incomingRequests.putIfAbsent(
                        message.requestId,
                        IncomingRelayRequest(message.method, message.path, message.headers),
                    )
                    if (previous != null) {
                        session.requestSlots.remove(message.requestId)
                        error("加密隧道请求重复开始")
                    }
                }
                "request_chunk" -> {
                    val request = session.incomingRequests[message.requestId] ?: error("加密隧道请求不存在")
                    val chunk = Base64.getUrlDecoder().decode(message.chunk)
                    check(request.body.size() + chunk.size <= LanWebDeviceTunnelProtocol.MAX_BODY_BYTES) {
                        "加密隧道请求正文超过大小限制"
                    }
                    request.body.write(chunk)
                }
                "request_end" -> {
                    val request = session.incomingRequests.remove(message.requestId) ?: error("加密隧道请求不存在")
                    scope.launch { proxyRequest(session, message.requestId, request) }
                }
                "cancel" -> {
                    session.incomingRequests.remove(message.requestId)
                    session.activeCalls.remove(message.requestId)?.cancel()
                    session.requestSlots.remove(message.requestId)
                }
                else -> error("手机加密隧道收到不允许的消息类型")
            }
        }
    }

    private fun proxyRequest(session: RelaySession, requestId: String, incoming: IncomingRelayRequest) {
        val mediaType = incoming.headers.entries
            .firstOrNull { it.key.equals("content-type", ignoreCase = true) }
            ?.value?.firstOrNull()?.toMediaTypeOrNull()
        val requestBuilder = Request.Builder().url("http://127.0.0.1:${session.localPort}${incoming.path}")
        LanWebDeviceTunnelProtocol.filterHeaders(incoming.headers, request = true).forEach { (name, values) ->
            values.forEach { requestBuilder.addHeader(name, it) }
        }
        if (incoming.method == "POST") {
            requestBuilder.post(incoming.body.toByteArray().toRequestBody(mediaType))
        } else {
            requestBuilder.get()
        }
        val call = localHttpClient.newCall(requestBuilder.build())
        session.activeCalls[requestId] = call
        try {
            call.execute().use { response ->
                session.send(
                    LanWebDeviceTunnelProtocol.newMessage(requestId, "response_start").copy(
                        status = response.code,
                        headers = LanWebDeviceTunnelProtocol.filterHeaders(response.headers.toMultimap(), request = false),
                    )
                )
                response.body?.byteStream()?.use { stream ->
                    val buffer = ByteArray(LanWebDeviceTunnelProtocol.CHUNK_BYTES)
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        session.send(
                            LanWebDeviceTunnelProtocol.newMessage(requestId, "response_chunk").copy(
                                chunk = Base64.getUrlEncoder().withoutPadding().encodeToString(buffer.copyOf(count)),
                            )
                        )
                    }
                }
                session.send(LanWebDeviceTunnelProtocol.newMessage(requestId, "response_end"))
            }
        } catch (error: Throwable) {
            if (!call.isCanceled()) {
                runCatching {
                    session.send(
                        LanWebDeviceTunnelProtocol.newMessage(requestId, "error").copy(
                            error = safeError(error.message ?: "手机本地节点请求失败"),
                        )
                    )
                }
            }
        } finally {
            session.activeCalls.remove(requestId, call)
            session.requestSlots.remove(requestId)
        }
    }

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
        val replay = LanWebDeviceTunnelReplayCache()
        val activeCalls = ConcurrentHashMap<String, Call>()
        val incomingRequests = ConcurrentHashMap<String, IncomingRelayRequest>()
        val requestSlots = ConcurrentHashMap.newKeySet<String>()
        @Volatile var webSocket: WebSocket? = null

        fun send(message: LanWebDeviceTunnelMessage) {
            val payload = LanWebDeviceTunnelProtocol.encrypt(
                secret = secret,
                roomId = roomId,
                senderRole = LanWebDeviceTunnelProtocol.ROLE_PHONE,
                message = message,
            )
            check(webSocket?.send(payload.toByteString()) == true) { "加密隧道发送失败" }
        }

        fun close() {
            activeCalls.values.forEach(Call::cancel)
            activeCalls.clear()
            incomingRequests.clear()
            requestSlots.clear()
            webSocket?.close(1000, "phone tunnel stopped")
            webSocket?.cancel()
            webSocket = null
            secret.fill(0)
        }
    }
}

private const val MAX_CONCURRENT_REQUESTS = 16
