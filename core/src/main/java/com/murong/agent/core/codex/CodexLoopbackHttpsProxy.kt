package com.murong.agent.core.codex

import android.util.Log
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A deliberately narrow HTTPS CONNECT bridge for the Linux Codex app-server.
 *
 * The official Android-compatible app-server artifact is a static Linux binary.
 * Its resolver cannot use Android's per-network DNS service reliably, while a
 * regular Java [Socket] can. This bridge is loopback-only, never terminates TLS,
 * and permits only the hosts required by the ChatGPT/Codex backend.
 */
internal class CodexLoopbackHttpsProxy private constructor(
    private val serverSocket: ServerSocket,
    private val workers: ExecutorService,
    private val upstreamProxy: CodexNetworkProxyEndpoint?,
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val acceptThread = Thread(::acceptConnections, "codex-loopback-proxy").apply {
        isDaemon = true
        start()
    }

    val proxyUrl: String
        get() = "http://$IPV4_LOOPBACK_HOST:${serverSocket.localPort}"

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { serverSocket.close() }
        workers.shutdownNow()
        acceptThread.interrupt()
    }

    private fun acceptConnections() {
        while (!closed.get()) {
            val client = try {
                serverSocket.accept()
            } catch (error: IOException) {
                if (!closed.get()) {
                    Log.w(TAG, "Codex loopback proxy accept failed", error)
                }
                break
            }
            workers.execute {
                handleClient(client)
            }
        }
    }

    private fun handleClient(client: Socket) {
        client.use { acceptedClient ->
            // Only the small plaintext CONNECT preface is bounded. Device-code
            // authorization uses a long-lived HTTPS poll that can legitimately
            // wait while the user completes the browser flow; applying this
            // timeout to the TLS tunnel would sever that poll after one minute.
            acceptedClient.soTimeout = CONNECT_REQUEST_TIMEOUT_MILLIS
            val input = BufferedInputStream(acceptedClient.getInputStream())
            val output = acceptedClient.getOutputStream()
            val target = runCatching { readConnectTarget(input) }.getOrElse { error ->
                writeResponse(output, "400 Bad Request")
                Log.w(TAG, "Rejected malformed Codex proxy request", error)
                return
            }
            if (!CodexProxyTargetPolicy.isAllowed(target.host, target.port)) {
                writeResponse(output, "403 Forbidden")
                Log.w(TAG, "Blocked disallowed Codex proxy destination ${target.host}:${target.port}")
                return
            }

            var upstream: CodexProxyConnection? = null
            try {
                // Host and port are deliberately safe to log: this proxy has no
                // URL paths, headers, device codes, or authentication payloads.
                Log.i(TAG, "Tunneling Codex HTTPS to ${target.host}:${target.port}")
                val connected = openUpstream(target)
                upstream = connected
                // The tunnel lifetime is owned by the child process and the
                // proxy Closeable. It must not inherit the header timeout.
                acceptedClient.soTimeout = 0
                connected.socket.soTimeout = 0
                writeResponse(output, "200 Connection Established")

                val clientToUpstream = workers.submit {
                    try {
                        input.copyTo(connected.output)
                        // Do not buffer a TLS tunnel: copyTo only returns after
                        // the peer closes, which would otherwise retain the TLS
                        // ClientHello/response until the request times out.
                        connected.output.flush()
                    } catch (_: IOException) {
                        // Closing either side is expected for a CONNECT tunnel.
                    } finally {
                        runCatching { connected.socket.shutdownOutput() }
                    }
                }
                try {
                    connected.input.copyTo(output)
                    output.flush()
                } catch (_: IOException) {
                    // Closing either side is expected for a CONNECT tunnel.
                } finally {
                    clientToUpstream.cancel(true)
                }
            } catch (error: IOException) {
                writeResponse(output, "502 Bad Gateway")
                Log.w(TAG, "Codex proxy could not reach ${target.host}:${target.port}", error)
            } finally {
                runCatching { upstream?.socket?.close() }
            }
        }
    }

    private fun openUpstream(target: CodexProxyTarget): CodexProxyConnection {
        val directError = runCatching { connectDirectly(target) }
            .onSuccess { return it }
            .exceptionOrNull()
        val configuredProxy = upstreamProxy
            ?: throw (directError ?: IOException("Android network connection failed"))
        Log.w(
            TAG,
            "Android network could not reach ${target.host}:${target.port}; retrying via system proxy ${configuredProxy.authority}",
            directError,
        )
        return try {
            connectThroughProxy(configuredProxy, target)
        } catch (proxyError: Throwable) {
            directError?.let(proxyError::addSuppressed)
            throw proxyError
        }
    }

    private fun connectDirectly(target: CodexProxyTarget): CodexProxyConnection {
        val socket = Socket()
        try {
            // Hostname resolution goes through Android's per-network DNS stack.
            socket.connect(InetSocketAddress(target.host, target.port), CONNECT_TIMEOUT_MILLIS)
            return CodexProxyConnection(
                socket = socket,
                input = BufferedInputStream(socket.getInputStream()),
                output = socket.getOutputStream(),
            )
        } catch (error: Throwable) {
            runCatching { socket.close() }
            throw error
        }
    }

    private fun connectThroughProxy(
        proxy: CodexNetworkProxyEndpoint,
        target: CodexProxyTarget,
    ): CodexProxyConnection {
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(proxy.host, proxy.port), CONNECT_TIMEOUT_MILLIS)
            socket.soTimeout = CONNECT_REQUEST_TIMEOUT_MILLIS
            val input = BufferedInputStream(socket.getInputStream())
            val output = socket.getOutputStream()
            val authority = "${target.host}:${target.port}"
            output.write(
                (
                    "CONNECT $authority HTTP/1.1\r\n" +
                        "Host: $authority\r\n" +
                        "Proxy-Connection: Keep-Alive\r\n\r\n"
                    ).toByteArray(Charsets.US_ASCII),
            )
            output.flush()
            val responseLine = readAsciiLine(input)
            val responseParts = responseLine.split(' ', limit = 3)
            val statusCode = responseParts.getOrNull(1)?.toIntOrNull()
            require(
                responseParts.size >= 2 &&
                    responseParts[0].startsWith("HTTP/") &&
                    statusCode != null &&
                    statusCode in 200..299,
            ) {
                "System proxy rejected CONNECT"
            }
            consumeHeaders(input)
            socket.soTimeout = 0
            return CodexProxyConnection(socket = socket, input = input, output = output)
        } catch (error: Throwable) {
            runCatching { socket.close() }
            throw error
        }
    }

    private fun consumeHeaders(input: BufferedInputStream) {
        for (index in 0 until MAX_HEADER_LINES) {
            if (readAsciiLine(input).isEmpty()) return
        }
        throw IllegalArgumentException("Too many proxy response headers")
    }

    private fun readConnectTarget(input: BufferedInputStream): CodexProxyTarget {
        val requestLine = readAsciiLine(input)
        val parts = requestLine.split(' ')
        require(parts.size == 3 && parts[0] == "CONNECT") { "Only CONNECT is supported" }
        require(parts[2] == "HTTP/1.0" || parts[2] == "HTTP/1.1") { "Unsupported HTTP version" }
        for (index in 0 until MAX_HEADER_LINES) {
            if (readAsciiLine(input).isEmpty()) {
                return CodexProxyTargetPolicy.parseAuthority(parts[1])
            }
        }
        throw IllegalArgumentException("Too many proxy request headers")
    }

    private fun readAsciiLine(input: BufferedInputStream): String {
        val bytes = ArrayList<Byte>(64)
        while (bytes.size < MAX_LINE_BYTES) {
            val next = input.read()
            if (next == -1) throw IOException("Unexpected end of proxy request")
            if (next == '\n'.code) break
            if (next != '\r'.code) bytes += next.toByte()
        }
        require(bytes.size < MAX_LINE_BYTES) { "Proxy request line is too long" }
        return bytes.toByteArray().toString(Charsets.US_ASCII)
    }

    private fun writeResponse(output: OutputStream, status: String) {
        runCatching {
            output.write("HTTP/1.1 $status\r\n\r\n".toByteArray(Charsets.US_ASCII))
            output.flush()
        }
    }

    companion object {
        private const val TAG = "CodexHttpsProxy"
        private const val IPV4_LOOPBACK_HOST = "127.0.0.1"
        private const val CONNECT_TIMEOUT_MILLIS = 15_000
        private const val CONNECT_REQUEST_TIMEOUT_MILLIS = 15_000
        private const val MAX_HEADER_LINES = 64
        private const val MAX_LINE_BYTES = 8 * 1024

        fun start(upstreamProxy: CodexNetworkProxyEndpoint? = null): CodexLoopbackHttpsProxy {
            val serverSocket = ServerSocket().apply {
                reuseAddress = true
                // getLoopbackAddress() may choose IPv6 (::1). The static
                // app-server is pointed at a literal IPv4 proxy URL, so bind
                // the same address explicitly instead of relying on dual-stack.
                bind(InetSocketAddress(IPV4_LOOPBACK_HOST, 0))
            }
            return CodexLoopbackHttpsProxy(
                serverSocket = serverSocket,
                workers = Executors.newCachedThreadPool { runnable ->
                    Thread(runnable, "codex-loopback-proxy-worker").apply { isDaemon = true }
                },
                upstreamProxy = upstreamProxy,
            )
        }
    }
}

private data class CodexProxyConnection(
    val socket: Socket,
    val input: BufferedInputStream,
    val output: OutputStream,
)

internal data class CodexProxyTarget(
    val host: String,
    val port: Int,
)

/** Kept Android-free so the security boundary can be unit tested. */
internal object CodexProxyTargetPolicy {
    private val allowedExactHosts = setOf(
        "auth.openai.com",
        "chatgpt.com",
        "api.openai.com",
    )

    fun parseAuthority(authority: String): CodexProxyTarget {
        val separator = authority.lastIndexOf(':')
        require(separator > 0 && separator < authority.lastIndex) { "Missing proxy target port" }
        val host = authority.substring(0, separator).lowercase(Locale.US).trimEnd('.')
        val port = authority.substring(separator + 1).toIntOrNull()
            ?: throw IllegalArgumentException("Invalid proxy target port")
        require(host.isNotBlank() && port in 1..65_535) { "Invalid proxy target" }
        return CodexProxyTarget(host = host, port = port)
    }

    fun isAllowed(host: String, port: Int): Boolean {
        val normalizedHost = host.lowercase(Locale.US).trimEnd('.')
        return port == 443 && (
            normalizedHost in allowedExactHosts ||
                normalizedHost.endsWith(".openai.com") ||
                normalizedHost.endsWith(".chatgpt.com")
            )
    }
}
