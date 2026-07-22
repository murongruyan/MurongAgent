package com.murong.agent.lan

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LanWebGitHubAccountTrustTest {
	@Test
	fun `phone issues device-bound proof for desktop target`() {
		val phone = identity()
		val desktop = identity()
		val ephemeral = keyPair()
		val ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { (it + 11).toByte() })
		val now = System.currentTimeMillis()
		val request = LanWebDeviceRelayProtocol.connectionRequest(
			identity = phone,
			targetDeviceId = desktop.snapshot.deviceId,
			deviceName = "Murong Phone",
			authMethod = LanWebTrustSource.GITHUB_ACCOUNT,
			ephemeralPublicKey = LanWebDeviceIdentity.publicKey(ephemeral),
			requestId = "connect-phone-github-0001",
			now = now,
		)
		val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
		server.createContext("/api/device_trust.php") { exchange ->
			assertEquals("issue", exchange.requestURI.query.substringAfter("action="))
			assertEquals("Bearer phone-backend-session", exchange.requestHeaders.getFirst("Authorization"))
			val body = Json.parseToJsonElement(exchange.requestBody.bufferedReader().readText()).jsonObject
			assertEquals(request.requestId, body["requestId"]?.jsonPrimitive?.contentOrNull)
			assertEquals(desktop.snapshot.deviceId, body["targetDeviceId"]?.jsonPrimitive?.contentOrNull)
			val proofNonce = body["proofNonce"]!!.jsonPrimitive.content
			val signature = body["deviceSignature"]!!.jsonPrimitive.content
			assertTrue(
				LanWebDeviceIdentity.verify(
					phone.snapshot.publicKey,
					LanWebGitHubAccountTrust.issueSignaturePayload(
						request,
						desktop.snapshot.publicKey,
						desktop.snapshot.publicKeyFingerprint,
						proofNonce,
					),
					signature,
				)
			)
			val response = """{"success":true,"data":{"version":"github-account-proof-v1","ticket":"$ticket","expires_at":${now + 60_000L}}}"""
			exchange.responseHeaders.add("Content-Type", "application/json")
			exchange.sendResponseHeaders(200, response.toByteArray().size.toLong())
			exchange.responseBody.use { it.write(response.toByteArray()) }
		}
		server.start()
		try {
			val trust = LanWebGitHubAccountTrust(
				identity = phone,
				sessionTokenProvider = { "phone-backend-session" },
				backendUrl = "http://127.0.0.1:${server.address.port}/api/device_trust.php?action=verify",
			)
			assertEquals(ticket, trust.issue(request, desktop.snapshot.publicKey, desktop.snapshot.publicKeyFingerprint, now))
		} finally {
			server.stop(0)
		}
	}

    @Test
    fun `receiver verifies one-time proof with backend session and stable identity signature`() {
        val phone = identity()
        val desktop = identity()
        val ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { (it + 3).toByte() })
        val issuedAt = System.currentTimeMillis()
        val unsigned = LanWebConnectionRequest(
            requestId = "connect-github-proof-0001",
            clientName = "Murong Desktop",
            deviceId = desktop.snapshot.deviceId,
            devicePublicKey = desktop.snapshot.publicKey,
            deviceFingerprint = desktop.snapshot.publicKeyFingerprint,
            ephemeralPublicKey = LanWebDeviceIdentity.publicKey(keyPair()),
            platform = "windows",
            issuedAt = issuedAt,
            authMethod = LanWebTrustSource.GITHUB_ACCOUNT,
            authProof = ticket,
            signature = "",
        )
        val request = unsigned.copy(
            signature = desktop.sign(LanWebConnectionCoordinator.connectionRequestSignaturePayload(unsigned)),
        )
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/device_trust.php") { exchange ->
            assertEquals("Bearer phone-backend-session", exchange.requestHeaders.getFirst("Authorization"))
            assertEquals("verify", exchange.requestURI.query.substringAfter("action="))
            val body = Json.parseToJsonElement(exchange.requestBody.bufferedReader().readText()).jsonObject
            assertEquals(request.requestId, body["requestId"]?.jsonPrimitive?.contentOrNull)
            assertEquals(ticket, body["ticket"]?.jsonPrimitive?.contentOrNull)
            assertEquals(phone.snapshot.deviceId, body["targetDeviceId"]?.jsonPrimitive?.contentOrNull)
            val receiverIssuedAt = body["receiverIssuedAt"]!!.jsonPrimitive.content.toLong()
            val signature = body["receiverSignature"]!!.jsonPrimitive.content
            assertTrue(
                LanWebDeviceIdentity.verify(
                    phone.snapshot.publicKey,
                    LanWebGitHubAccountTrust.verificationSignaturePayload(
                        ticket,
                        request.requestId,
                        phone.snapshot.deviceId,
                        phone.snapshot.publicKey,
                        phone.snapshot.publicKeyFingerprint,
                        receiverIssuedAt,
                    ),
                    signature,
                )
            )
            val response = """{"success":true,"data":{"trusted":true,"version":"github-account-proof-v1","trust_source":"github_account","issuer_device_id":"${desktop.snapshot.deviceId}","issuer_fingerprint":"${desktop.snapshot.publicKeyFingerprint}"}}"""
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, response.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(response.toByteArray()) }
        }
        server.start()
        try {
            val trust = LanWebGitHubAccountTrust(
                identity = phone,
                sessionTokenProvider = { "phone-backend-session" },
                backendUrl = "http://127.0.0.1:${server.address.port}/api/device_trust.php?action=verify",
            )
            assertTrue(trust.isAvailable())
            assertTrue(trust.verify(request, System.currentTimeMillis()))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `verification signature payload is language neutral`() {
        val payload = LanWebGitHubAccountTrust.verificationSignaturePayload(
            ticket = "ticket-value",
            requestId = "connect-request-0001",
            deviceId = "DMB77YSEX4BLAFRU",
            publicKey = "public-key",
            fingerprint = "fingerprint",
            issuedAt = 123456789L,
        ).toString(Charsets.UTF_8)
        assertEquals(
            "murong-github-device-proof-verify-v1\nticket-value\nconnect-request-0001\nDMB77YSEX4BLAFRU\npublic-key\nfingerprint\n123456789",
            payload,
        )
    }

    private fun identity() = LanWebDeviceIdentity { keyPair() }

    private fun keyPair(): KeyPair = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1"))
        generateKeyPair()
    }
}
