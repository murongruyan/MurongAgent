package com.murong.agent.lan

class LanWebConnectionCoordinator(
    private val accessStore: LanWebAccessStore,
    private val identity: LanWebDeviceIdentity,
    private val adbAuthenticator: ((LanWebConnectionRequest, Long) -> Boolean)? = null,
    private val passwordAuthenticator: ((LanWebConnectionRequest, Long) -> LanWebPairingAuthentication?)? = null,
    private val githubAccountAuthenticator: ((LanWebConnectionRequest, Long) -> Boolean)? = null,
    private val onChanged: () -> Unit = {},
) {
    private data class Entry(
        val request: LanWebConnectionRequest,
        val remoteAddress: String,
        val transport: String,
        val createdAt: Long,
        val expiresAt: Long,
        var status: String = STATUS_PENDING,
        var response: LanWebConnectionStatusResponse? = null,
    )

    private val lock = Any()
    private val entries = linkedMapOf<String, Entry>()
    private val expectedPeers = linkedMapOf<String, Pair<String, Long>>()
    private val sourceLimiter = LanWebRateLimiter(limit = 6, windowMillis = 5 * 60 * 1000L)

    fun submit(
        request: LanWebConnectionRequest,
        remoteAddress: String,
        transport: String,
        now: Long = System.currentTimeMillis(),
    ): Result<LanWebConnectionRequestAck> = synchronized(lock) {
        val outcome = runCatching {
            cleanupLocked(now)
            validateRequest(request, now)
            require(sourceLimiter.tryAcquire("$remoteAddress:${request.deviceId}", now)) {
                "连接申请过于频繁，请稍后再试"
            }
            entries[request.requestId]?.let { existing ->
                require(existing.request.deviceId == request.deviceId) { "连接申请 ID 已被占用" }
                return@runCatching existing.ack()
            }
            val entry = Entry(
                request = request,
                remoteAddress = remoteAddress,
                transport = transport.take(24),
                createdAt = now,
                expiresAt = now + REQUEST_TTL_MILLIS,
            )
            entries[request.requestId] = entry

            val trusted = accessStore.trustedClient(request.deviceId, request.deviceFingerprint)
            when {
                accessStore.isBlocked(request.deviceId, request.deviceFingerprint) -> {
                    entry.status = STATUS_BLOCKED
                    entry.response = terminalResponse(entry, STATUS_BLOCKED, "连接申请已拒绝")
                }
                consumeExpectedPeerLocked(request.deviceId, request.deviceFingerprint, now) ->
                    approveLocked(entry, LanWebTrustSource.CONNECTION_APPROVAL, now)
                request.authMethod == LanWebTrustSource.TEMPORARY_CODE ||
                    request.authMethod == LanWebTrustSource.SECURITY_PASSWORD -> {
                    val authentication = passwordAuthenticator?.invoke(request, now)
                    if (authentication == null) {
                        entry.status = STATUS_REJECTED
                        entry.response = terminalResponse(entry, STATUS_REJECTED, "临时验证码或安全密码无效")
                    } else {
                        approveLocked(entry, authentication.trustSource, now, authentication.serverProof)
                    }
                }
                trusted != null -> approveLocked(entry, trusted.trustSource, now)
                request.authMethod == LanWebTrustSource.ADB && adbAuthenticator?.invoke(request, now) == true ->
                    approveLocked(entry, LanWebTrustSource.ADB, now)
                request.authMethod == LanWebTrustSource.ADB -> {
                    entry.status = STATUS_REJECTED
                    entry.response = terminalResponse(entry, STATUS_REJECTED, "ADB 连接证明无效或已过期")
                }
                request.authMethod == LanWebTrustSource.GITHUB_ACCOUNT &&
                    githubAccountAuthenticator?.invoke(request, now) == true ->
                    approveLocked(entry, LanWebTrustSource.GITHUB_ACCOUNT, now)
                accessStore.doNotDisturb() -> {
                    entry.status = STATUS_REJECTED
                    entry.response = terminalResponse(entry, STATUS_REJECTED, "接收端当前不接受陌生设备连接")
                }
            }
            entry.ack()
        }
        onChanged()
        outcome
    }

    fun status(
        request: LanWebConnectionStatusRequest,
        now: Long = System.currentTimeMillis(),
    ): Result<LanWebConnectionStatusResponse> = synchronized(lock) {
        runCatching {
            cleanupLocked(now)
            val entry = entries[request.requestId] ?: error("连接申请不存在或已过期")
            require(request.deviceId == entry.request.deviceId) { "连接申请设备不匹配" }
            require(request.issuedAt in (now - SIGNATURE_CLOCK_WINDOW_MILLIS)..(now + 30_000L)) {
                "连接状态签名时间无效"
            }
            require(
                LanWebDeviceIdentity.verify(
                    entry.request.devicePublicKey,
                    statusSignaturePayload(request),
                    request.signature,
                )
            ) { "连接状态签名无效" }
            entry.response ?: LanWebConnectionStatusResponse(
                requestId = entry.request.requestId,
                status = entry.status,
                message = "等待接收端确认",
            )
        }
    }

    fun approve(requestId: String, now: Long = System.currentTimeMillis()): Boolean = synchronized(lock) {
        cleanupLocked(now)
        val entry = entries[requestId] ?: return@synchronized false
        if (entry.status != STATUS_PENDING) return@synchronized false
        approveLocked(entry, LanWebTrustSource.CONNECTION_APPROVAL, now)
        onChanged()
        true
    }

    fun reject(requestId: String, block: Boolean, now: Long = System.currentTimeMillis()): Boolean = synchronized(lock) {
        cleanupLocked(now)
        val entry = entries[requestId] ?: return@synchronized false
        if (entry.status != STATUS_PENDING) return@synchronized false
        if (block) {
            accessStore.blockPeer(
                entry.request.deviceId,
                entry.request.clientName,
                entry.request.deviceFingerprint,
                now,
            )
            entry.status = STATUS_BLOCKED
            entry.response = terminalResponse(entry, STATUS_BLOCKED, "连接申请已拒绝")
        } else {
            entry.status = STATUS_REJECTED
            entry.response = terminalResponse(entry, STATUS_REJECTED, "连接申请已拒绝")
        }
        onChanged()
        true
    }

    fun summaries(now: Long = System.currentTimeMillis()): List<LanWebConnectionRequestSummary> = synchronized(lock) {
        cleanupLocked(now)
        entries.values
            .filter { it.status == STATUS_PENDING }
            .map { entry ->
                LanWebConnectionRequestSummary(
                    requestId = entry.request.requestId,
                    deviceId = entry.request.deviceId,
                    deviceDisplayId = entry.request.deviceId.chunked(4).joinToString("-"),
                    clientName = entry.request.clientName,
                    platform = entry.request.platform,
                    publicKeyFingerprint = entry.request.deviceFingerprint,
                    authMethod = entry.request.authMethod,
                    transport = entry.transport,
                    createdAt = entry.createdAt,
                    expiresAt = entry.expiresAt,
                    status = entry.status,
                )
            }
    }

    fun expectPeer(
        deviceId: String,
        fingerprint: String,
        now: Long = System.currentTimeMillis(),
    ) = synchronized(lock) {
        val normalized = requireNotNull(LanWebDeviceIdentity.normalizeDeviceId(deviceId)) { "设备 ID 无效" }
        require(fingerprint.isNotBlank()) { "设备指纹无效" }
        cleanupLocked(now)
        expectedPeers[normalized] = fingerprint to (now + EXPECTED_PEER_TTL_MILLIS)
    }

    private fun validateRequest(request: LanWebConnectionRequest, now: Long) {
        require(LanWebContract.requestIdPattern.matches(request.requestId)) { "连接申请 ID 无效" }
        require(request.issuedAt in (now - SIGNATURE_CLOCK_WINDOW_MILLIS)..(now + 30_000L)) {
            "连接申请签名时间无效"
        }
        require(
            request.authMethod == LanWebTrustSource.CONNECTION_APPROVAL ||
                request.authMethod == LanWebTrustSource.ADB ||
                request.authMethod == LanWebTrustSource.GITHUB_ACCOUNT ||
                request.authMethod == LanWebTrustSource.TEMPORARY_CODE ||
                request.authMethod == LanWebTrustSource.SECURITY_PASSWORD
        ) { "当前连接认证方式不受支持" }
        when (request.authMethod) {
            LanWebTrustSource.ADB -> {
                require(request.authProof.length == SHA256_BASE64URL_LENGTH && request.authProof.all(::isBase64UrlCharacter)) {
                    "ADB 连接证明格式无效"
                }
            }
            LanWebTrustSource.TEMPORARY_CODE, LanWebTrustSource.SECURITY_PASSWORD -> {
                val parts = request.authProof.split('.')
                require(
                    parts.size == 2 && parts[0].length == SCRAM_SESSION_ID_LENGTH &&
                        parts[1].length == SHA256_BASE64URL_LENGTH &&
                        parts.all { part -> part.all(::isBase64UrlCharacter) }
                ) { "密码认证证明格式无效" }
            }
            LanWebTrustSource.GITHUB_ACCOUNT -> {
                require(request.authProof.length == SHA256_BASE64URL_LENGTH && request.authProof.all(::isBase64UrlCharacter)) {
                    "GitHub 账号证明格式无效"
                }
            }
            else -> require(request.authProof.isBlank()) { "连接申请包含不需要的认证证明" }
        }
        require(request.platform.length in 1..24 && request.platform.none(Char::isISOControl)) { "设备平台无效" }
        requireNotNull(LanWebSecurity.normalizeClientName(request.clientName)) { "客户端名称无效" }
        val normalizedDeviceId = LanWebDeviceIdentity.normalizeDeviceId(request.deviceId)
        require(normalizedDeviceId == request.deviceId) { "设备 ID 无效" }
        require(LanWebDeviceIdentity.deviceIdForPublicKey(request.devicePublicKey) == request.deviceId) {
            "设备 ID 与公钥不匹配"
        }
        require(LanWebDeviceIdentity.fingerprintForPublicKey(request.devicePublicKey) == request.deviceFingerprint) {
            "设备指纹与公钥不匹配"
        }
        require(LanWebDeviceIdentity.deviceIdForPublicKey(request.ephemeralPublicKey) != null) { "临时设备公钥无效" }
        require(
            LanWebDeviceIdentity.verify(
                request.devicePublicKey,
                connectionRequestSignaturePayload(request),
                request.signature,
            )
        ) { "连接申请签名无效" }
    }

    private fun approveLocked(entry: Entry, trustSource: String, now: Long, authServerProof: String = "") {
        val responderEphemeral = LanWebDeviceLinkCrypto.ephemeralKeyPair()
        val context = linkContext(entry.request.requestId, entry.request.deviceId, identity.snapshot.deviceId)
        val linkKey = LanWebDeviceIdentity.deriveLinkSecret(
            responderEphemeral,
            entry.request.ephemeralPublicKey,
            context,
        )
        val issued = accessStore.issueTrustedClient(
            rawClientName = entry.request.clientName,
            deviceId = entry.request.deviceId,
            publicKeyFingerprint = entry.request.deviceFingerprint,
            trustSource = trustSource,
            now = now,
        )
        val syncKey = requireNotNull(issued.syncKey)
        try {
            val responderEphemeralPublic = LanWebDeviceIdentity.publicKey(responderEphemeral)
            val envelope = LanWebDeviceLinkCrypto.encryptBootstrap(
                linkKey = linkKey,
                requestId = entry.request.requestId,
                requesterDeviceId = entry.request.deviceId,
                responderDeviceId = identity.snapshot.deviceId,
                summary = issued.summary,
                accessToken = issued.accessToken,
                syncKey = syncKey,
            )
            val unsigned = LanWebConnectionStatusResponse(
                requestId = entry.request.requestId,
                status = STATUS_APPROVED,
                message = "连接已获准",
                responderDeviceId = identity.snapshot.deviceId,
                responderPublicKey = identity.snapshot.publicKey,
                responderEphemeralPublicKey = responderEphemeralPublic,
                clientId = issued.summary.id,
                clientName = issued.summary.name,
                createdAt = issued.summary.createdAt,
                authServerProof = authServerProof,
                secureChannel = envelope,
            )
            entry.status = STATUS_APPROVED
            entry.response = unsigned.copy(
                responderSignature = identity.sign(connectionResponseSignaturePayload(unsigned)),
            )
        } catch (error: Throwable) {
            accessStore.revokeClient(issued.summary.id)
            entry.status = STATUS_REJECTED
            entry.response = terminalResponse(entry, STATUS_REJECTED, "无法建立安全连接")
            throw error
        } finally {
            linkKey.fill(0)
            syncKey.fill(0)
        }
    }

    private fun consumeExpectedPeerLocked(deviceId: String, fingerprint: String, now: Long): Boolean {
        val expected = expectedPeers[deviceId] ?: return false
        if (expected.second <= now || expected.first != fingerprint) return false
        expectedPeers.remove(deviceId)
        return true
    }

    private fun terminalResponse(entry: Entry, status: String, message: String) = LanWebConnectionStatusResponse(
        requestId = entry.request.requestId,
        status = status,
        message = message,
        responderDeviceId = identity.snapshot.deviceId,
        responderPublicKey = identity.snapshot.publicKey,
    )

    private fun Entry.ack() = LanWebConnectionRequestAck(
        requestId = request.requestId,
        status = status,
        expiresAt = expiresAt,
        message = when (status) {
            STATUS_APPROVED -> "已信任设备，正在建立安全连接"
            STATUS_PENDING -> "连接申请已发送，等待接收端确认"
            else -> "连接申请未获准"
        },
    )

    private fun cleanupLocked(now: Long) {
        expectedPeers.entries.removeAll { it.value.second <= now }
        entries.entries.removeAll { (_, entry) -> entry.expiresAt <= now || now - entry.createdAt > RETENTION_MILLIS }
        while (entries.size > MAX_REQUESTS) entries.remove(entries.keys.first())
    }

    companion object {
        const val STATUS_PENDING = "pending"
        const val STATUS_APPROVED = "approved"
        const val STATUS_REJECTED = "rejected"
        const val STATUS_BLOCKED = "blocked"
        private const val REQUEST_TTL_MILLIS = 2 * 60 * 1000L
        private const val RETENTION_MILLIS = 10 * 60 * 1000L
        private const val SIGNATURE_CLOCK_WINDOW_MILLIS = 2 * 60 * 1000L
        private const val MAX_REQUESTS = 64
        private const val SHA256_BASE64URL_LENGTH = 43
        private const val SCRAM_SESSION_ID_LENGTH = 24
        private const val EXPECTED_PEER_TTL_MILLIS = 2 * 60 * 1000L

        fun connectionRequestSignaturePayload(request: LanWebConnectionRequest): ByteArray = buildString {
            append("murong-device-connect-request-v1")
            append('\n').append(request.requestId)
            append('\n').append(request.clientName.trim())
            append('\n').append(request.deviceId)
            append('\n').append(request.devicePublicKey)
            append('\n').append(request.deviceFingerprint)
            append('\n').append(request.ephemeralPublicKey)
            append('\n').append(request.platform)
            append('\n').append(request.issuedAt)
            append('\n').append(request.authMethod)
            if (request.authProof.isNotBlank()) append('\n').append(request.authProof)
        }.toByteArray(Charsets.UTF_8)

        fun statusSignaturePayload(request: LanWebConnectionStatusRequest): ByteArray = buildString {
            append("murong-device-connect-status-v1")
            append('\n').append(request.requestId)
            append('\n').append(request.deviceId)
            append('\n').append(request.issuedAt)
        }.toByteArray(Charsets.UTF_8)

        fun connectionResponseSignaturePayload(response: LanWebConnectionStatusResponse): ByteArray = buildString {
            append("murong-device-connect-response-v1")
            append('\n').append(response.requestId)
            append('\n').append(response.status)
            append('\n').append(response.responderDeviceId)
            append('\n').append(response.responderPublicKey)
            append('\n').append(response.responderEphemeralPublicKey)
            append('\n').append(response.clientId)
            append('\n').append(response.clientName)
            append('\n').append(response.createdAt)
            append('\n').append(response.secureChannel?.version.orEmpty())
            append('\n').append(response.secureChannel?.nonce.orEmpty())
            append('\n').append(response.secureChannel?.ciphertext.orEmpty())
            if (response.authServerProof.isNotBlank()) append('\n').append(response.authServerProof)
        }.toByteArray(Charsets.UTF_8)

        private fun linkContext(requestId: String, requesterDeviceId: String, responderDeviceId: String): ByteArray =
            "murong-device-link-context-v1\n$requestId\n$requesterDeviceId\n$responderDeviceId".toByteArray(Charsets.UTF_8)

        private fun isBase64UrlCharacter(value: Char): Boolean =
            value in 'A'..'Z' || value in 'a'..'z' || value in '0'..'9' || value == '-' || value == '_'
    }
}
