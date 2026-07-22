package com.murong.agent.lan

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.io.IOException
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal data class LanWebDiscoveryAnnouncement(
    val version: Int = LanWebDiscoveryProtocol.VERSION,
    val nonce: String,
    val deviceId: String,
    val deviceDisplayId: String,
    val devicePublicKey: String,
    val deviceFingerprint: String,
    val name: String,
    val platform: String = "android",
    val port: Int = LanWebContract.PORT,
    val issuedAt: Long,
    val signature: String,
)

internal object LanWebDiscoveryProtocol {
    const val VERSION = 1
    const val PORT = 18_765
    const val REQUEST_PREFIX = "MURONG_DISCOVER_V1\n"
    const val MAX_DATAGRAM_BYTES = 2_048
    const val CLOCK_WINDOW_MILLIS = 2 * 60 * 1000L

    fun parseNonce(payload: ByteArray, length: Int): String? {
        if (length <= REQUEST_PREFIX.length || length > 128) return null
        val text = payload.decodeToString(0, length)
        if (!text.startsWith(REQUEST_PREFIX)) return null
        val nonce = text.removePrefix(REQUEST_PREFIX)
        val decoded = runCatching { Base64.getUrlDecoder().decode(nonce) }.getOrNull()
        return nonce.takeIf { decoded?.size == 16 }
    }

    fun signaturePayload(value: LanWebDiscoveryAnnouncement): ByteArray = buildString {
        append("murong-lan-discovery-v1")
        append('\n').append(value.nonce)
        append('\n').append(value.deviceId)
        append('\n').append(value.devicePublicKey)
        append('\n').append(value.deviceFingerprint)
        append('\n').append(value.name)
        append('\n').append(value.platform)
        append('\n').append(value.port)
        append('\n').append(value.issuedAt)
    }.toByteArray(Charsets.UTF_8)
}

internal class LanWebDiscoveryResponder(
    private val scope: CoroutineScope,
    private val identity: LanWebDeviceIdentity,
    private val name: String,
    private val servicePort: Int = LanWebContract.PORT,
) {
    private val running = AtomicBoolean(false)
    private val json = Json { encodeDefaults = true; explicitNulls = false }
    private var socket: DatagramSocket? = null
    private var job: Job? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        job = scope.launch {
            val current = DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress("0.0.0.0", LanWebDiscoveryProtocol.PORT))
                soTimeout = 1_000
            }
            socket = current
            val buffer = ByteArray(LanWebDiscoveryProtocol.MAX_DATAGRAM_BYTES)
            try {
                while (running.get()) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        current.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        continue
                    } catch (error: IOException) {
                        if (!running.get()) break
                        throw error
                    }
                    val remoteAddress = packet.address?.hostAddress.orEmpty()
                    if (!LanWebSecurity.isAllowedRemoteAddress(remoteAddress)) continue
                    val nonce = LanWebDiscoveryProtocol.parseNonce(packet.data, packet.length) ?: continue
                    val snapshot = identity.snapshot
                    val unsigned = LanWebDiscoveryAnnouncement(
                        nonce = nonce,
                        deviceId = snapshot.deviceId,
                        deviceDisplayId = snapshot.displayId,
                        devicePublicKey = snapshot.publicKey,
                        deviceFingerprint = snapshot.publicKeyFingerprint,
                        name = name.take(80),
                        port = servicePort,
                        issuedAt = System.currentTimeMillis(),
                        signature = "",
                    )
                    val signed = unsigned.copy(signature = identity.sign(LanWebDiscoveryProtocol.signaturePayload(unsigned)))
                    val response = json.encodeToString(signed).toByteArray(Charsets.UTF_8)
                    if (response.size <= LanWebDiscoveryProtocol.MAX_DATAGRAM_BYTES) {
                        current.send(DatagramPacket(response, response.size, packet.address, packet.port))
                    }
                }
            } finally {
                current.close()
                if (socket === current) socket = null
            }
        }
    }

    fun stop() {
        running.set(false)
        socket?.close()
        socket = null
        job?.cancel()
        job = null
    }
}
