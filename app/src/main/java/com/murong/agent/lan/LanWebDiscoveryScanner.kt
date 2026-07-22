package com.murong.agent.lan

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.Base64
import java.util.Collections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class LanWebDiscoveredDevice(
    val deviceId: String,
    val deviceDisplayId: String,
    val name: String,
    val platform: String,
    val publicKey: String,
    val fingerprint: String,
    val address: String,
    val lastSeenAt: Long,
)

internal class LanWebDiscoveryScanner(
    private val identity: LanWebDeviceIdentity,
    private val secureRandom: SecureRandom = SecureRandom(),
) {
    private val json = Json { ignoreUnknownKeys = false }

    suspend fun discover(durationMillis: Long = 1_800L): List<LanWebDiscoveredDevice> = withContext(Dispatchers.IO) {
        val duration = durationMillis.coerceIn(500L, 10_000L)
        val nonceBytes = ByteArray(16).also(secureRandom::nextBytes)
        val nonce = try {
            Base64.getUrlEncoder().withoutPadding().encodeToString(nonceBytes)
        } finally {
            nonceBytes.fill(0)
        }
        val payload = (LanWebDiscoveryProtocol.REQUEST_PREFIX + nonce).toByteArray(Charsets.UTF_8)
        DatagramSocket(null).use { socket ->
            socket.reuseAddress = true
            socket.broadcast = true
            socket.bind(java.net.InetSocketAddress("0.0.0.0", 0))
            broadcastAddresses().forEach { address ->
                runCatching {
                    socket.send(DatagramPacket(payload, payload.size, address, LanWebDiscoveryProtocol.PORT))
                }
            }
            val deadline = System.currentTimeMillis() + duration
            val devices = linkedMapOf<String, LanWebDiscoveredDevice>()
            val buffer = ByteArray(LanWebDiscoveryProtocol.MAX_DATAGRAM_BYTES)
            while (System.currentTimeMillis() < deadline) {
                socket.soTimeout = (deadline - System.currentTimeMillis()).coerceIn(1L, 500L).toInt()
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                val address = packet.address?.hostAddress.orEmpty()
                if (!LanWebSecurity.isAllowedRemoteAddress(address)) continue
                val announcement = runCatching {
                    json.decodeFromString<LanWebDiscoveryAnnouncement>(
                        packet.data.decodeToString(0, packet.length)
                    )
                }.getOrNull() ?: continue
                if (!validAnnouncement(announcement, nonce) ||
                    announcement.deviceId == identity.snapshot.deviceId ||
                    announcement.platform.equals("android", ignoreCase = true)
                ) continue
                devices[announcement.deviceId] = LanWebDiscoveredDevice(
                    deviceId = announcement.deviceId,
                    deviceDisplayId = announcement.deviceDisplayId,
                    name = announcement.name.trim(),
                    platform = announcement.platform,
                    publicKey = announcement.devicePublicKey,
                    fingerprint = announcement.deviceFingerprint,
                    address = address,
                    lastSeenAt = announcement.issuedAt,
                )
            }
            devices.values.sortedWith(compareBy(LanWebDiscoveredDevice::name, LanWebDiscoveredDevice::deviceId))
        }
    }

    private fun validAnnouncement(value: LanWebDiscoveryAnnouncement, expectedNonce: String): Boolean {
        val now = System.currentTimeMillis()
        if (value.version != LanWebDiscoveryProtocol.VERSION || value.nonce != expectedNonce ||
            value.port !in 1..65_535 || value.issuedAt !in
            (now - LanWebDiscoveryProtocol.CLOCK_WINDOW_MILLIS)..(now + 30_000L)
        ) return false
        if (value.name.isBlank() || value.name.length > 80 || value.name.any(Char::isISOControl) ||
            value.platform.isBlank() || value.platform.length > 24 || value.platform.any(Char::isISOControl)
        ) return false
        if (LanWebDeviceIdentity.normalizeDeviceId(value.deviceId) != value.deviceId ||
            value.deviceDisplayId != value.deviceId.chunked(4).joinToString("-") ||
            LanWebDeviceIdentity.deviceIdForPublicKey(value.devicePublicKey) != value.deviceId ||
            LanWebDeviceIdentity.fingerprintForPublicKey(value.devicePublicKey) != value.deviceFingerprint
        ) return false
        return LanWebDeviceIdentity.verify(
            value.devicePublicKey,
            LanWebDiscoveryProtocol.signaturePayload(value),
            value.signature,
        )
    }

    private fun broadcastAddresses(): List<InetAddress> {
        val result = linkedMapOf<String, InetAddress>()
        InetAddress.getByName("255.255.255.255").let { result[it.hostAddress] = it }
        val interfaces: List<NetworkInterface> = runCatching {
            Collections.list(NetworkInterface.getNetworkInterfaces())
        }.getOrElse { emptyList() }
        interfaces
            .asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.interfaceAddresses.asSequence() }
            .forEach { interfaceAddress ->
                val broadcast = interfaceAddress.broadcast
                if (broadcast is Inet4Address) result[broadcast.hostAddress] = broadcast
            }
        return result.values.toList()
    }
}
