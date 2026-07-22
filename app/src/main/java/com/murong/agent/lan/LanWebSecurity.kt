package com.murong.agent.lan

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.security.MessageDigest
import java.util.Locale

internal object LanWebSecurity {
    fun isAllowedRemoteAddress(rawAddress: String?): Boolean {
        val normalized = rawAddress
            ?.trim()
            ?.removePrefix("/")
            ?.substringBefore('%')
            ?.takeIf { it.isNotEmpty() }
            ?: return false
        val address = runCatching { InetAddress.getByName(normalized) }.getOrNull() ?: return false
        if (address.isAnyLocalAddress) return false
        if (address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress) {
            return true
        }
        return when (address) {
            is Inet4Address -> isCarrierGradeNat(address)
            is Inet6Address -> isUniqueLocalIpv6(address)
            else -> false
        }
    }

    fun isAllowedHost(
        hostHeader: String?,
        bindAddress: String,
        port: Int,
        allowLoopbackForwardPort: Boolean = false,
    ): Boolean {
        val actual = hostHeader?.trim()?.lowercase(Locale.ROOT) ?: return false
        val normalizedBind = bindAddress.trim().substringBefore('%').lowercase(Locale.ROOT)
        val expected = if (normalizedBind.contains(':')) {
            "[$normalizedBind]:$port"
        } else {
            "$normalizedBind:$port"
        }
        val loopbackHosts = setOf("127.0.0.1:$port", "localhost:$port", "[::1]:$port")
        if (actual == expected || actual in loopbackHosts) return true
        if (!allowLoopbackForwardPort || normalizedBind !in setOf("127.0.0.1", "::1")) return false
        val forwarded = runCatching { URI("http://$actual") }.getOrNull() ?: return false
        if (forwarded.userInfo != null || forwarded.port !in 1..65535) return false
        val forwardedHost = forwarded.host?.lowercase(Locale.ROOT) ?: return false
        if (forwardedHost == "localhost") return true
        return runCatching { InetAddress.getByName(forwardedHost).isLoopbackAddress }.getOrDefault(false)
    }

    fun isAllowedOrigin(originHeader: String?, hostHeader: String?, port: Int): Boolean {
        if (originHeader.isNullOrBlank()) return true
        val origin = runCatching { URI(originHeader.trim()) }.getOrNull() ?: return false
        if (!origin.scheme.equals("http", ignoreCase = true) || origin.userInfo != null) return false
        val effectivePort = if (origin.port >= 0) origin.port else 80
        if (effectivePort != port) return false
        val originHost = origin.host?.lowercase(Locale.ROOT) ?: return false
        val headerHost = parseHostName(hostHeader)?.lowercase(Locale.ROOT) ?: return false
        return MessageDigest.isEqual(
            originHost.toByteArray(Charsets.UTF_8),
            headerHost.toByteArray(Charsets.UTF_8)
        )
    }

    fun normalizeClientName(raw: String): String? {
        val normalized = raw.trim().replace(Regex("\\s+"), " ")
        if (normalized.isEmpty() || normalized.length > LanWebContract.MAX_CLIENT_NAME_CHARS) return null
        if (normalized.any { it.code < 0x20 || it.code == 0x7f }) return null
        return normalized
    }

    fun normalizeMessage(raw: String): String? {
        val normalized = raw.trim()
        if (normalized.isEmpty() || normalized.length > LanWebContract.MAX_MESSAGE_CHARS) return null
        if (normalized.any { it.code < 0x20 && it != '\n' && it != '\r' && it != '\t' }) return null
        return normalized
    }

    private fun parseHostName(hostHeader: String?): String? {
        val raw = hostHeader?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (raw.startsWith('[')) {
            val closing = raw.indexOf(']')
            if (closing <= 1) return null
            return raw.substring(1, closing)
        }
        return raw.substringBefore(':')
    }

    private fun isCarrierGradeNat(address: Inet4Address): Boolean {
        val bytes = address.address
        val first = bytes[0].toInt() and 0xff
        val second = bytes[1].toInt() and 0xff
        return first == 100 && second in 64..127
    }

    private fun isUniqueLocalIpv6(address: Inet6Address): Boolean {
        val first = address.address.firstOrNull()?.toInt()?.and(0xff) ?: return false
        return first and 0xfe == 0xfc
    }
}
internal class LanWebRateLimiter(
    private val limit: Int,
    private val windowMillis: Long
) {
    private val attempts = linkedMapOf<String, ArrayDeque<Long>>()

    @Synchronized
    fun tryAcquire(key: String, now: Long = System.currentTimeMillis()): Boolean {
        val cutoff = now - windowMillis
        attempts.values.forEach { queue ->
            while (queue.firstOrNull()?.let { it <= cutoff } == true) queue.removeFirst()
        }
        attempts.entries.removeAll { it.value.isEmpty() }
        val queue = attempts.getOrPut(key) { ArrayDeque() }
        if (queue.size >= limit) return false
        queue.addLast(now)
        return true
    }
}
