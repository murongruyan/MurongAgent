package dev.reasonix.mobile.core.mcp

import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * MCP 传输层——管理单个 MCP 服务器的连接和 JSON-RPC 通信
 *
 * 支持:
 * - stdio: 启动本地进程，通过 stdin/stdout JSONL 通信
 * - SSE: 通过 Server-Sent Events 连接远程服务器
 * - streamable-http: 通过 HTTP POST 直接进行 JSON-RPC 通信
 */
class McpTransport(private val config: McpServerConfig) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val pendingRequests = ConcurrentHashMap<String, CompletableResponse>()

    // stdio
    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var readerThread: Thread? = null

    // remote
    private var sseClient: OkHttpClient? = null
    private var sseEventSource: okhttp3.sse.EventSource? = null
    private var httpClient: OkHttpClient? = null

    private var isConnected = false
    private var serverInfo: JsonObject? = null

    data class CompletableResponse(
        val future: CompletableFuture<JsonRpcResponse>
    )

    /**
     * 连接到 MCP 服务器
     */
    suspend fun connect(): Result<JsonObject> {
        return try {
            when (config.transport) {
                McpTransportType.STDIO -> connectStdio()
                McpTransportType.SSE -> connectSSE()
                McpTransportType.STREAMABLE_HTTP -> connectStreamableHttp()
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 调用工具
     */
    suspend fun callTool(name: String, args: JsonObject): Result<JsonElement> {
        return sendRequest("tools/call", buildJsonObject {
            put("name", name)
            put("arguments", args)
        }).map { response ->
            response.result ?: buildJsonObject { put("error", "null result") }
        }
    }

    /**
     * 列出可用工具
     */
    suspend fun listTools(): Result<List<McpToolDef>> {
        return sendRequest("tools/list").map { response ->
            val toolsArray = response.result?.jsonObject?.get("tools")?.jsonArray
            toolsArray?.map { element ->
                val obj = element.jsonObject
                McpToolDef(
                    name = obj["name"]?.jsonPrimitive?.content ?: "",
                    description = obj["description"]?.jsonPrimitive?.contentOrNull ?: "",
                    inputSchema = parseSchema(obj["inputSchema"]?.jsonObject ?: buildJsonObject { }),
                    serverName = config.name
                )
            } ?: emptyList()
        }
    }

    suspend fun initialize(): Result<JsonObject> {
        return sendRequest("initialize", buildInitializeParams()).mapCatching { response ->
            val info = response.result?.jsonObject ?: buildJsonObject { }
            sendNotification("notifications/initialized", buildJsonObject { }).getOrThrow()
            serverInfo = info
            info
        }
    }

    fun isActive(): Boolean = isConnected

    fun getServerInfo(): JsonObject? = serverInfo

    /**
     * 断开连接
     */
    fun disconnect() {
        isConnected = false
        try {
            sseEventSource?.cancel()
            httpClient?.dispatcher?.executorService?.shutdown()
            httpClient?.connectionPool?.evictAll()
            sseClient?.dispatcher?.executorService?.shutdown()
            sseClient?.connectionPool?.evictAll()
            writer?.close()
            process?.destroy()
        } catch (_: Exception) {}
        // 完成所有 pending 请求
        pendingRequests.values.forEach { future ->
            future.future.complete(JsonRpcResponse(error = JsonRpcError(-1, "disconnected")))
        }
        pendingRequests.clear()
    }

    // ─── stdio 连接 ────────────────────────────

    private suspend fun connectStdio(): Result<JsonObject> {
        if (config.command.isBlank()) {
            return Result.failure(IllegalArgumentException("stdio command is empty"))
        }
        return try {
            val pb = ProcessBuilder(config.command, *config.args.toTypedArray())
            pb.redirectErrorStream(false)
            if (config.cwd.isNotBlank()) {
                pb.directory(File(config.cwd))
            }
            if (config.env.isNotEmpty()) {
                pb.environment().putAll(config.env)
            }
            process = pb.start()

            val inputStream = process!!.inputStream
            val errorStream = process!!.errorStream
            writer = BufferedWriter(OutputStreamWriter(process!!.outputStream))

            // 读取 stderr（丢弃或日志）
            Thread {
                try {
                    errorStream.bufferedReader().use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            // stderr 可用于调试
                        }
                    }
                } catch (_: IOException) {}
            }.apply { isDaemon = true }.start()

            // 读取 stdout（JSON-RPC 响应）
            val rt = Thread {
                try {
                    inputStream.bufferedReader().use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            handleResponseLine(line!!)
                        }
                    }
                } catch (_: IOException) {}
            }
            rt.isDaemon = true
            readerThread = rt
            rt.start()

            isConnected = true
            initialize()
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ─── SSE 连接 ──────────────────────────────

    private suspend fun connectSSE(): Result<JsonObject> {
        if (config.url.isBlank()) {
            return Result.failure(IllegalArgumentException("SSE URL is empty"))
        }
        return try {
            val client = buildRemoteClient(readTimeoutMillis = 0L)
            sseClient = client

            val request = buildRemoteRequest(config.url) {
                addHeader("Accept", "text/event-stream")
            }

            val eventSourceFactory = okhttp3.sse.EventSources.createFactory(client)
            sseEventSource = eventSourceFactory.newEventSource(request, object : okhttp3.sse.EventSourceListener() {
                override fun onEvent(eventSource: okhttp3.sse.EventSource, id: String?, type: String?, data: String) {
                    // SSE events carry JSON-RPC responses
                    handleResponseLine(data)
                }

                override fun onFailure(eventSource: okhttp3.sse.EventSource, t: Throwable?, response: Response?) {
                    isConnected = false
                }

                override fun onOpen(eventSource: okhttp3.sse.EventSource, response: Response) {
                    isConnected = true
                }
            })

            // 等待连接建立
            kotlinx.coroutines.delay(500)
            if (!isConnected) {
                return Result.failure(Exception("SSE connection failed"))
            }
            initialize()
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun connectStreamableHttp(): Result<JsonObject> {
        if (config.url.isBlank()) {
            return Result.failure(IllegalArgumentException("streamable-http URL is empty"))
        }
        return try {
            httpClient = buildRemoteClient(readTimeoutMillis = requestTimeoutMs())
            isConnected = true
            initialize()
        } catch (e: Exception) {
            isConnected = false
            Result.failure(e)
        }
    }

    // ─── JSON-RPC 通信 ────────────────────────

    private suspend fun sendRequest(method: String, params: JsonObject? = null): Result<JsonRpcResponse> {
        if (!isConnected) {
            return Result.failure(IllegalStateException("MCP server '${config.name}' is not connected"))
        }

        val requestId = java.util.UUID.randomUUID().toString()
        val request = JsonRpcRequest(
            id = requestId,
            method = method,
            params = params
        )

        val future = CompletableFuture<JsonRpcResponse>()
        pendingRequests[requestId] = CompletableResponse(future)

        try {
            when (config.transport) {
                McpTransportType.STDIO -> {
                    writer?.let { w ->
                        synchronized(w) {
                            w.write(json.encodeToString(JsonRpcRequest.serializer(), request))
                            w.newLine()
                            w.flush()
                        }
                    } ?: throw IOException("stdio writer not available")
                }
                McpTransportType.SSE -> {
                    // SSE 发送通过 HTTP POST
                    val httpRequest = buildRemoteRequest(config.url) {
                        post(
                            json.encodeToString(JsonRpcRequest.serializer(), request)
                                .toRequestBody("application/json".toMediaType())
                        )
                    }
                    sseClient?.newCall(httpRequest)?.execute()?.close()
                }
                McpTransportType.STREAMABLE_HTTP -> {
                    pendingRequests.remove(requestId)
                    return sendStreamableHttpRequest(request)
                }
            }
        } catch (e: Exception) {
            pendingRequests.remove(requestId)
            return Result.failure(e)
        }

        return try {
            val response = future.get(requestTimeoutMs(), TimeUnit.MILLISECONDS)
            if (response.error != null) {
                Result.failure(Exception("MCP error ${response.error.code}: ${response.error.message}"))
            } else {
                Result.success(response)
            }
        } catch (e: Exception) {
            pendingRequests.remove(requestId)
            Result.failure(e)
        }
    }

    private suspend fun sendNotification(method: String, params: JsonObject? = null): Result<Unit> {
        if (!isConnected) {
            return Result.failure(IllegalStateException("MCP server '${config.name}' is not connected"))
        }

        val notification = JsonRpcNotification(
            method = method,
            params = params
        )

        return try {
            when (config.transport) {
                McpTransportType.STDIO -> {
                    writer?.let { w ->
                        synchronized(w) {
                            w.write(json.encodeToString(JsonRpcNotification.serializer(), notification))
                            w.newLine()
                            w.flush()
                        }
                    } ?: throw IOException("stdio writer not available")
                }
                McpTransportType.SSE -> {
                    val httpRequest = buildRemoteRequest(config.url) {
                        post(
                            json.encodeToString(JsonRpcNotification.serializer(), notification)
                                .toRequestBody("application/json".toMediaType())
                        )
                    }
                    sseClient?.newCall(httpRequest)?.execute()?.use { response ->
                        if (!response.isSuccessful) {
                            throw IOException("HTTP ${response.code}: ${response.message.ifBlank { "notification failed" }}")
                        }
                    } ?: throw IOException("SSE client not available")
                }
                McpTransportType.STREAMABLE_HTTP -> {
                    val client = httpClient ?: throw IOException("streamable-http client is not initialized")
                    val httpRequest = buildRemoteRequest(config.url) {
                        post(
                            json.encodeToString(JsonRpcNotification.serializer(), notification)
                                .toRequestBody("application/json".toMediaType())
                        )
                    }
                    client.newCall(httpRequest).execute().use { response ->
                        if (!response.isSuccessful) {
                            throw IOException("HTTP ${response.code}: ${response.message.ifBlank { "notification failed" }}")
                        }
                    }
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun handleResponseLine(line: String) {
        if (line.isBlank()) return
        try {
            val response = json.decodeFromString<JsonRpcResponse>(line)
            val id = response.id
            if (id != null) {
                pendingRequests.remove(id)?.future?.complete(response)
            }
        } catch (_: Exception) {
            // 忽略非 JSON 行（如日志）
        }
    }

    private fun parseSchema(schema: JsonObject): Map<String, Any> {
        val result = mutableMapOf<String, Any>()
        result["type"] = schema["type"]?.jsonPrimitive?.contentOrNull ?: "object"
        val props = schema["properties"]?.jsonObject
        if (props != null) {
            val propsMap = mutableMapOf<String, Map<String, Any>>()
            props.forEach { (key, value) ->
                val propObj = value.jsonObject
                propsMap[key] = mapOf(
                    "type" to (propObj["type"]?.jsonPrimitive?.contentOrNull ?: "string"),
                    "description" to (propObj["description"]?.jsonPrimitive?.contentOrNull ?: "")
                )
            }
            result["properties"] = propsMap
        }
        val required = schema["required"]?.jsonArray
        if (required != null) {
            result["required"] = required.map { it.jsonPrimitive.content }
        }
        return result
    }

    private fun buildRemoteClient(readTimeoutMillis: Long): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(requestTimeoutMs(), TimeUnit.MILLISECONDS)
            .writeTimeout(requestTimeoutMs(), TimeUnit.MILLISECONDS)
            .readTimeout(readTimeoutMillis, TimeUnit.MILLISECONDS)
            .build()
    }

    private fun buildRemoteRequest(
        url: String,
        block: Request.Builder.() -> Request.Builder = { this }
    ): Request {
        val builder = Request.Builder().url(url)
        config.headers.forEach { (key, value) ->
            if (key.isNotBlank() && value.isNotBlank()) {
                builder.addHeader(key, value)
            }
        }
        return builder.block().build()
    }

    private suspend fun sendStreamableHttpRequest(request: JsonRpcRequest): Result<JsonRpcResponse> {
        val client = httpClient ?: return Result.failure(
            IllegalStateException("streamable-http client is not initialized")
        )
        return try {
            val httpRequest = buildRemoteRequest(config.url) {
                post(
                    json.encodeToString(JsonRpcRequest.serializer(), request)
                        .toRequestBody("application/json".toMediaType())
                )
            }
            client.newCall(httpRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    return Result.failure(
                        IOException("HTTP ${response.code}: ${response.message.ifBlank { "request failed" }}")
                    )
                }
                val body = response.body?.string().orEmpty().trim()
                if (body.isBlank()) {
                    return Result.failure(IOException("empty streamable-http response"))
                }
                Result.success(json.decodeFromString(JsonRpcResponse.serializer(), body))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun requestTimeoutMs(): Long {
        return (config.requestTimeoutMs ?: DEFAULT_REQUEST_TIMEOUT_MS).coerceAtLeast(1_000L)
    }

    companion object {
        internal const val PROTOCOL_VERSION = "2024-11-05"
        internal const val CLIENT_NAME = "murong-agent"
        internal const val CLIENT_VERSION = "1.0.0"
        private const val DEFAULT_REQUEST_TIMEOUT_MS = 30_000L
    }
}

internal fun buildInitializeParams(): JsonObject {
    return buildJsonObject {
        put("protocolVersion", McpTransport.PROTOCOL_VERSION)
        putJsonObject("capabilities") { }
        putJsonObject("clientInfo") {
            put("name", McpTransport.CLIENT_NAME)
            put("version", McpTransport.CLIENT_VERSION)
        }
    }
}

/**
 * 简单的 CompletableFuture 实现（不依赖 java.util.concurrent）
 */
class CompletableFuture<T> {
    private val completedSignal = CountDownLatch(1)
    private var result: T? = null
    private var exception: Exception? = null
    private var completed = false

    fun complete(value: T) {
        synchronized(this) {
            result = value
            completed = true
            completedSignal.countDown()
        }
    }

    fun completeExceptionally(e: Exception) {
        synchronized(this) {
            exception = e
            completed = true
            completedSignal.countDown()
        }
    }

    fun get(timeout: Long, unit: TimeUnit): T {
        if (!completed) {
            try {
                if (!completedSignal.await(timeout, unit)) {
                    throw Exception("Request timed out after $timeout ${unit}")
                }
            } catch (_: InterruptedException) {
                throw Exception("Request timed out")
            }
        }
        synchronized(this) {
            if (!completed) throw Exception("Request timed out after $timeout ${unit}")
            exception?.let { throw it }
            @Suppress("UNCHECKED_CAST")
            return result as T
        }
    }
}
