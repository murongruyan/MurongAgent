package com.murong.agent.lan

import java.nio.ByteBuffer
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LanWebConnectionCoordinatorTest {
    private class MemorySyncKeyStore : LanWebSyncKeyStore {
        private val values = linkedMapOf<String, ByteArray>()
        override fun put(clientId: String, key: ByteArray) { values[clientId] = key.copyOf() }
        override fun read(clientId: String): ByteArray? = values[clientId]?.copyOf()
        override fun remove(clientId: String) { values.remove(clientId)?.fill(0) }
        override fun clear() { values.values.forEach { it.fill(0) }; values.clear() }
    }

    @Test
    fun `manual request approval returns signed ECDH encrypted bootstrap`() {
        val vault = MemorySyncKeyStore()
        val access = LanWebAccessStore(
            createTempDirectory("device-link-").resolve("access.json").toFile(),
            syncKeyStore = vault,
        )
        val phone = identity()
        val desktop = identity()
        val requesterEphemeral = keyPair()
        val coordinator = LanWebConnectionCoordinator(access, phone)
        val request = signedRequest(desktop, requesterEphemeral, "connect-request-0001", 1_000L)

        val acknowledgement = coordinator.submit(request, "192.168.1.20", "lan", now = 1_000L).getOrThrow()
        assertEquals(LanWebConnectionCoordinator.STATUS_PENDING, acknowledgement.status)
        assertEquals(1, coordinator.summaries(now = 1_001L).size)
        assertTrue(coordinator.approve(request.requestId, now = 2_000L))

        val statusRequest = signedStatusRequest(desktop, request.requestId, 2_100L)
        val response = coordinator.status(statusRequest, now = 2_100L).getOrThrow()
        assertEquals(LanWebConnectionCoordinator.STATUS_APPROVED, response.status)
        assertTrue(
            LanWebDeviceIdentity.verify(
                response.responderPublicKey,
                LanWebConnectionCoordinator.connectionResponseSignaturePayload(response.copy(responderSignature = "")),
                response.responderSignature,
            )
        )
        assertEquals(phone.snapshot.deviceId, response.responderDeviceId)
        assertNotNull(response.secureChannel)

        val linkContext = "murong-device-link-context-v1\n${request.requestId}\n${desktop.snapshot.deviceId}\n${phone.snapshot.deviceId}"
            .toByteArray()
        val linkKey = LanWebDeviceIdentity.deriveLinkSecret(
            requesterEphemeral,
            response.responderEphemeralPublicKey,
            linkContext,
        )
        val (token, syncKey) = decryptBootstrap(linkKey, request, response)
        assertNotNull(access.authenticate(token, now = 2_101L))
        assertContentEquals(access.syncKey(response.clientId), syncKey)
        linkKey.fill(0)
        syncKey.fill(0)
    }

    @Test
    fun `trusted peer reconnects without approval while DND rejects strangers`() {
        val access = LanWebAccessStore(
            createTempDirectory("device-link-trust-").resolve("access.json").toFile(),
            syncKeyStore = MemorySyncKeyStore(),
        )
        val phone = identity()
        val desktop = identity()
        val coordinator = LanWebConnectionCoordinator(access, phone)
        val first = signedRequest(desktop, keyPair(), "connect-request-0002", 10_000L)
        coordinator.submit(first, "192.168.1.20", "lan", now = 10_000L).getOrThrow()
        assertTrue(coordinator.approve(first.requestId, now = 10_100L))

        val reconnect = signedRequest(desktop, keyPair(), "connect-request-0003", 11_000L)
        assertEquals(
            LanWebConnectionCoordinator.STATUS_APPROVED,
            coordinator.submit(reconnect, "192.168.1.20", "lan", now = 11_000L).getOrThrow().status,
        )

        access.setDoNotDisturb(true)
        val stranger = identity()
        val unknown = signedRequest(stranger, keyPair(), "connect-request-0004", 12_000L)
        assertEquals(
            LanWebConnectionCoordinator.STATUS_REJECTED,
            coordinator.submit(unknown, "192.168.1.30", "lan", now = 12_000L).getOrThrow().status,
        )
        assertFalse(coordinator.approve(unknown.requestId, now = 12_001L))
    }

    @Test
    fun `valid ADB proof establishes trust without confirmation`() {
        val access = LanWebAccessStore(
            createTempDirectory("device-link-adb-").resolve("access.json").toFile(),
            syncKeyStore = MemorySyncKeyStore(),
        )
        val phone = identity()
        val desktop = identity()
        var proofConsumed = false
        val coordinator = LanWebConnectionCoordinator(
            accessStore = access,
            identity = phone,
            adbAuthenticator = { request, _ ->
                proofConsumed = request.authProof == "_RlJ5mEYNA5gmgBsf_Sj-5BZdkOI-1JP93JTCLZ66EA"
                proofConsumed
            },
        )
        val unsigned = LanWebConnectionRequest(
            requestId = "connect-request-adb1",
            clientName = "Murong Desktop",
            deviceId = desktop.snapshot.deviceId,
            devicePublicKey = desktop.snapshot.publicKey,
            deviceFingerprint = desktop.snapshot.publicKeyFingerprint,
            ephemeralPublicKey = LanWebDeviceIdentity.publicKey(keyPair()),
            platform = "windows",
            issuedAt = 20_000L,
            authMethod = LanWebTrustSource.ADB,
            authProof = "_RlJ5mEYNA5gmgBsf_Sj-5BZdkOI-1JP93JTCLZ66EA",
            signature = "",
        )
        val request = unsigned.copy(
            signature = desktop.sign(LanWebConnectionCoordinator.connectionRequestSignaturePayload(unsigned)),
        )

        val acknowledgement = coordinator.submit(request, "127.0.0.1", "adb", now = 20_000L).getOrThrow()
        assertTrue(proofConsumed)
        assertEquals(LanWebConnectionCoordinator.STATUS_APPROVED, acknowledgement.status)
        assertTrue(coordinator.summaries(now = 20_001L).isEmpty())
        assertEquals(LanWebTrustSource.ADB, access.clients().single().trustSource)
    }

    @Test
    fun `same GitHub account proof auto approves while an unverified proof remains pending`() {
        val access = LanWebAccessStore(
            createTempDirectory("device-link-github-").resolve("access.json").toFile(),
            syncKeyStore = MemorySyncKeyStore(),
        )
        val phone = identity()
        val desktop = identity()
        val ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { (it + 1).toByte() })
        var verificationCalls = 0
        val coordinator = LanWebConnectionCoordinator(
            accessStore = access,
            identity = phone,
            githubAccountAuthenticator = { request, _ ->
                verificationCalls += 1
                request.authProof == ticket
            },
        )
        val approvedUnsigned = LanWebConnectionRequest(
            requestId = "connect-request-github1",
            clientName = "Murong Desktop",
            deviceId = desktop.snapshot.deviceId,
            devicePublicKey = desktop.snapshot.publicKey,
            deviceFingerprint = desktop.snapshot.publicKeyFingerprint,
            ephemeralPublicKey = LanWebDeviceIdentity.publicKey(keyPair()),
            platform = "windows",
            issuedAt = 25_000L,
            authMethod = LanWebTrustSource.GITHUB_ACCOUNT,
            authProof = ticket,
            signature = "",
        )
        val approvedRequest = approvedUnsigned.copy(
            signature = desktop.sign(LanWebConnectionCoordinator.connectionRequestSignaturePayload(approvedUnsigned)),
        )
        val approved = coordinator.submit(approvedRequest, "192.168.1.25", "lan", now = 25_000L).getOrThrow()
        assertEquals(LanWebConnectionCoordinator.STATUS_APPROVED, approved.status)
        assertEquals(1, verificationCalls)
        assertEquals(LanWebTrustSource.GITHUB_ACCOUNT, access.clients().single().trustSource)

        val stranger = identity()
        val wrongTicket = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { (it + 2).toByte() })
        val pendingUnsigned = LanWebConnectionRequest(
            requestId = "connect-request-github2",
            clientName = "Other Desktop",
            deviceId = stranger.snapshot.deviceId,
            devicePublicKey = stranger.snapshot.publicKey,
            deviceFingerprint = stranger.snapshot.publicKeyFingerprint,
            ephemeralPublicKey = LanWebDeviceIdentity.publicKey(keyPair()),
            platform = "windows",
            issuedAt = 26_000L,
            authMethod = LanWebTrustSource.GITHUB_ACCOUNT,
            authProof = wrongTicket,
            signature = "",
        )
        val pendingRequest = pendingUnsigned.copy(
            signature = stranger.sign(LanWebConnectionCoordinator.connectionRequestSignaturePayload(pendingUnsigned)),
        )
        val pending = coordinator.submit(pendingRequest, "192.168.1.26", "lan", now = 26_000L).getOrThrow()
        assertEquals(LanWebConnectionCoordinator.STATUS_PENDING, pending.status)
        assertEquals(2, verificationCalls)
    }

    @Test
    fun `temporary code SCRAM proof auto approves stable device and returns server proof`() {
        val directory = createTempDirectory("device-link-scram-")
        val access = LanWebAccessStore(
            directory.resolve("access.json").toFile(),
            syncKeyStore = MemorySyncKeyStore(),
        )
        val authenticator = LanWebPairingAuthenticator(directory.resolve("auth.json").toFile())
        val phone = identity()
        val desktop = identity()
        val requesterEphemeral = keyPair()
        val now = 30_000L
        val code = authenticator.beginTemporaryCode(now)
        val clientNonce = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(18) { it.toByte() })
        val unsignedChallenge = LanWebPairChallengeRequest(
            requestId = "connect-request-scram1",
            clientName = "Murong Desktop",
            deviceId = desktop.snapshot.deviceId,
            devicePublicKey = desktop.snapshot.publicKey,
            deviceFingerprint = desktop.snapshot.publicKeyFingerprint,
            ephemeralPublicKey = LanWebDeviceIdentity.publicKey(requesterEphemeral),
            platform = "windows",
            issuedAt = now,
            authMethod = LanWebTrustSource.TEMPORARY_CODE,
            clientNonce = clientNonce,
            signature = "",
        )
        val challengeRequest = unsignedChallenge.copy(
            signature = desktop.sign(LanWebPairingAuthenticator.pairChallengeSignaturePayload(unsignedChallenge)),
        )
        val challenge = authenticator.beginChallenge(challengeRequest, "192.168.1.40", now).getOrThrow()
        val salt = requireNotNull(LanWebScramCrypto.decode(challenge.salt, LanWebScramCrypto.SALT_BYTES))
        val authMessage = LanWebScramCrypto.authMessage(challengeRequest, challenge)
        val clientProof = LanWebScramCrypto.clientProof(
            code.value.replace("-", ""), salt, challenge.iterations, authMessage,
        )
        salt.fill(0)
        authMessage.fill(0)
        val unsignedRequest = LanWebConnectionRequest(
            requestId = challengeRequest.requestId,
            clientName = challengeRequest.clientName,
            deviceId = challengeRequest.deviceId,
            devicePublicKey = challengeRequest.devicePublicKey,
            deviceFingerprint = challengeRequest.deviceFingerprint,
            ephemeralPublicKey = challengeRequest.ephemeralPublicKey,
            platform = challengeRequest.platform,
            issuedAt = challengeRequest.issuedAt,
            authMethod = challengeRequest.authMethod,
            authProof = "${challenge.sessionId}.${clientProof.proof}",
            signature = "",
        )
        val request = unsignedRequest.copy(
            signature = desktop.sign(LanWebConnectionCoordinator.connectionRequestSignaturePayload(unsignedRequest)),
        )
        val coordinator = LanWebConnectionCoordinator(
            accessStore = access,
            identity = phone,
            passwordAuthenticator = authenticator::authenticate,
        )
        val acknowledgement = coordinator.submit(request, "192.168.1.40", "lan", now).getOrThrow()
        assertEquals(LanWebConnectionCoordinator.STATUS_APPROVED, acknowledgement.status)
        val response = coordinator.status(signedStatusRequest(desktop, request.requestId, now + 1), now + 1).getOrThrow()
        assertEquals(clientProof.expectedServerProof, response.authServerProof)
        assertEquals(LanWebTrustSource.TEMPORARY_CODE, access.clients().single().trustSource)
        assertFalse(authenticator.snapshot(now + 1).temporaryCodeAvailable)
        assertTrue(
            LanWebDeviceIdentity.verify(
                response.responderPublicKey,
                LanWebConnectionCoordinator.connectionResponseSignaturePayload(response.copy(responderSignature = "")),
                response.responderSignature,
            )
        )
    }

    private fun signedRequest(
        desktop: LanWebDeviceIdentity,
        ephemeral: KeyPair,
        requestId: String,
        issuedAt: Long,
    ): LanWebConnectionRequest {
        val unsigned = LanWebConnectionRequest(
            requestId = requestId,
            clientName = "Murong Desktop",
            deviceId = desktop.snapshot.deviceId,
            devicePublicKey = desktop.snapshot.publicKey,
            deviceFingerprint = desktop.snapshot.publicKeyFingerprint,
            ephemeralPublicKey = LanWebDeviceIdentity.publicKey(ephemeral),
            platform = "windows",
            issuedAt = issuedAt,
            signature = "",
        )
        return unsigned.copy(signature = desktop.sign(LanWebConnectionCoordinator.connectionRequestSignaturePayload(unsigned)))
    }

    private fun signedStatusRequest(
        desktop: LanWebDeviceIdentity,
        requestId: String,
        issuedAt: Long,
    ): LanWebConnectionStatusRequest {
        val unsigned = LanWebConnectionStatusRequest(requestId, desktop.snapshot.deviceId, issuedAt, "")
        return unsigned.copy(signature = desktop.sign(LanWebConnectionCoordinator.statusSignaturePayload(unsigned)))
    }

    private fun decryptBootstrap(
        linkKey: ByteArray,
        request: LanWebConnectionRequest,
        response: LanWebConnectionStatusResponse,
    ): Pair<String, ByteArray> {
        val envelope = requireNotNull(response.secureChannel)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(linkKey, "AES"),
            GCMParameterSpec(128, Base64.getUrlDecoder().decode(envelope.nonce)),
        )
        cipher.updateAAD(
            LanWebDeviceLinkCrypto.aad(
                request.requestId,
                request.deviceId,
                response.responderDeviceId,
                LanWebClientSummary(response.clientId, response.clientName, response.createdAt, secureSync = true),
            )
        )
        val plain = cipher.doFinal(Base64.getUrlDecoder().decode(envelope.ciphertext))
        return try {
            val buffer = ByteBuffer.wrap(plain)
            val tokenLength = buffer.short.toInt() and 0xffff
            val tokenBytes = ByteArray(tokenLength).also(buffer::get)
            val syncKey = ByteArray(32).also(buffer::get)
            tokenBytes.toString(Charsets.UTF_8) to syncKey
        } finally {
            plain.fill(0)
        }
    }

    private fun identity() = LanWebDeviceIdentity { keyPair() }

    private fun keyPair(): KeyPair = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1"))
        generateKeyPair()
    }
}
