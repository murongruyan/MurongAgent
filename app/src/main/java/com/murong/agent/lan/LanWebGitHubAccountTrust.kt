package com.murong.agent.lan

import com.murong.agent.core.config.ConfigRepository
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

internal class LanWebGitHubAccountTrust(
    private val identity: LanWebDeviceIdentity,
    private val sessionTokenProvider: () -> String,
    private val backendUrl: String = OFFICIAL_DEVICE_TRUST_URL,
    private val httpClient: OkHttpClient = defaultHttpClient(),
) {
    constructor(
        configRepository: ConfigRepository,
        identity: LanWebDeviceIdentity,
    ) : this(
        identity = identity,
        sessionTokenProvider = {
            runBlocking { configRepository.getConfig().githubBackendSessionToken.trim() }
        },
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    fun isAvailable(): Boolean = runCatching { sessionTokenProvider().isNotBlank() }.getOrDefault(false)

	fun issue(
		request: LanWebDeviceRelayMessage,
		targetDevicePublicKey: String,
		targetDeviceFingerprint: String,
		now: Long = System.currentTimeMillis(),
	): String? {
		if (!isAvailable() || request.authMethod != LanWebTrustSource.GITHUB_ACCOUNT ||
			request.sourceDeviceId != identity.snapshot.deviceId || request.authProof.isNotBlank() ||
			request.ephemeralPublicKey.isBlank()
		) return null
		val targetDeviceId = LanWebDeviceIdentity.deviceIdForPublicKey(targetDevicePublicKey) ?: return null
		if (targetDeviceId != request.targetDeviceId ||
			LanWebDeviceIdentity.fingerprintForPublicKey(targetDevicePublicKey) != targetDeviceFingerprint
		) return null
		val sessionToken = runCatching { sessionTokenProvider().trim() }.getOrDefault("")
		if (sessionToken.isBlank()) return null
		val proofNonceBytes = ByteArray(16).also(SecureRandom()::nextBytes)
		val proofNonce = try {
			Base64.getUrlEncoder().withoutPadding().encodeToString(proofNonceBytes)
		} finally {
			proofNonceBytes.fill(0)
		}
		val signature = identity.sign(issueSignaturePayload(request, targetDevicePublicKey, targetDeviceFingerprint, proofNonce))
		val body = LanWebGitHubProofIssueRequest(
			requestId = request.requestId,
			clientName = request.deviceName.trim(),
			deviceId = request.sourceDeviceId,
			devicePublicKey = request.sourcePublicKey,
			deviceFingerprint = identity.snapshot.publicKeyFingerprint,
			ephemeralPublicKey = request.ephemeralPublicKey,
			platform = request.platform,
			issuedAt = request.issuedAt,
			targetDeviceId = request.targetDeviceId,
			targetDevicePublicKey = targetDevicePublicKey,
			targetDeviceFingerprint = targetDeviceFingerprint,
			proofNonce = proofNonce,
			deviceSignature = signature,
		)
		val httpRequest = Request.Builder()
			.url(endpoint("issue"))
			.header("Accept", "application/json")
			.header("Authorization", "Bearer $sessionToken")
			.header("User-Agent", "MurongAgent-Android/1.0")
			.post(json.encodeToString(body).toRequestBody(JSON_MEDIA_TYPE))
			.build()
		return runCatching {
			httpClient.newCall(httpRequest).execute().use { response ->
				if (!response.isSuccessful) return@use null
				val envelope = json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
				if (envelope["success"]?.jsonPrimitive?.booleanOrNull != true) return@use null
				val data = envelope["data"]?.jsonObject ?: return@use null
				val ticket = data["ticket"]?.jsonPrimitive?.contentOrNull ?: return@use null
				val expiresAt = data["expires_at"]?.jsonPrimitive?.longOrNull
					?: data["expiresAt"]?.jsonPrimitive?.longOrNull ?: return@use null
				if (data["version"]?.jsonPrimitive?.contentOrNull == PROOF_VERSION &&
					validTicket(ticket) && expiresAt > now
				) ticket else null
			}
		}.getOrNull()
	}

    fun verify(request: LanWebConnectionRequest, now: Long = System.currentTimeMillis()): Boolean {
        if (request.authMethod != LanWebTrustSource.GITHUB_ACCOUNT || !validTicket(request.authProof)) return false
        val sessionToken = runCatching { sessionTokenProvider().trim() }.getOrDefault("")
        if (sessionToken.isBlank()) return false
        val responder = identity.snapshot
        val receiverSignature = identity.sign(
            verificationSignaturePayload(
                ticket = request.authProof,
                requestId = request.requestId,
                deviceId = responder.deviceId,
                publicKey = responder.publicKey,
                fingerprint = responder.publicKeyFingerprint,
                issuedAt = now,
            )
        )
        val body = LanWebGitHubProofVerifyRequest(
            requestId = request.requestId,
            clientName = request.clientName.trim(),
            deviceId = request.deviceId,
            devicePublicKey = request.devicePublicKey,
            deviceFingerprint = request.deviceFingerprint,
            ephemeralPublicKey = request.ephemeralPublicKey,
            platform = request.platform,
            issuedAt = request.issuedAt,
            targetDeviceId = responder.deviceId,
            targetDevicePublicKey = responder.publicKey,
            targetDeviceFingerprint = responder.publicKeyFingerprint,
            ticket = request.authProof,
            receiverIssuedAt = now,
            receiverSignature = receiverSignature,
        )
        val httpRequest = Request.Builder()
			.url(endpoint("verify"))
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $sessionToken")
            .header("User-Agent", "MurongAgent-Android/1.0")
            .post(json.encodeToString(body).toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return runCatching {
            httpClient.newCall(httpRequest).execute().use { response ->
                if (!response.isSuccessful) return@use false
                val envelope = json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
                if (envelope["success"]?.jsonPrimitive?.booleanOrNull != true) return@use false
                val data = envelope["data"]?.jsonObject ?: return@use false
                data["trusted"]?.jsonPrimitive?.booleanOrNull == true &&
                    data["version"]?.jsonPrimitive?.contentOrNull == PROOF_VERSION &&
                    data["trust_source"]?.jsonPrimitive?.contentOrNull == LanWebTrustSource.GITHUB_ACCOUNT &&
                    data["issuer_device_id"]?.jsonPrimitive?.contentOrNull == request.deviceId &&
                    data["issuer_fingerprint"]?.jsonPrimitive?.contentOrNull == request.deviceFingerprint
            }
        }.getOrDefault(false)
    }

    companion object {
        private const val OFFICIAL_DEVICE_TRUST_URL =
            "https://murongagent.rl1.cc/api/device_trust.php?action=verify"
        private const val PROOF_VERSION = "github-account-proof-v1"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun verificationSignaturePayload(
            ticket: String,
            requestId: String,
            deviceId: String,
            publicKey: String,
            fingerprint: String,
            issuedAt: Long,
        ): ByteArray = buildString {
            append("murong-github-device-proof-verify-v1")
            append('\n').append(ticket)
            append('\n').append(requestId)
            append('\n').append(deviceId)
            append('\n').append(publicKey)
            append('\n').append(fingerprint)
            append('\n').append(issuedAt)
        }.toByteArray(Charsets.UTF_8)

		fun issueSignaturePayload(
			request: LanWebDeviceRelayMessage,
			targetDevicePublicKey: String,
			targetDeviceFingerprint: String,
			proofNonce: String,
		): ByteArray = buildString {
			append("murong-github-device-proof-issue-v1\n")
			append(connectionPayload(request, targetDevicePublicKey, targetDeviceFingerprint))
			append('\n').append(proofNonce)
		}.toByteArray(Charsets.UTF_8)

		fun connectionPayload(
			request: LanWebDeviceRelayMessage,
			targetDevicePublicKey: String,
			targetDeviceFingerprint: String,
		): String = buildString {
			append("murong-github-device-proof-connection-v1")
			append('\n').append(request.requestId)
			append('\n').append(request.deviceName.trim())
			append('\n').append(request.sourceDeviceId)
			append('\n').append(request.sourcePublicKey)
			append('\n').append(LanWebDeviceIdentity.fingerprintForPublicKey(request.sourcePublicKey).orEmpty())
			append('\n').append(request.ephemeralPublicKey)
			append('\n').append(request.platform)
			append('\n').append(request.issuedAt)
			append('\n').append(request.targetDeviceId)
			append('\n').append(targetDevicePublicKey)
			append('\n').append(targetDeviceFingerprint)
		}

        private fun validTicket(ticket: String): Boolean = runCatching {
            Base64.getUrlDecoder().decode(ticket).size == 32
        }.getOrDefault(false)

        private fun defaultHttpClient() = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()
    }

	private fun endpoint(action: String) = backendUrl.toHttpUrl().newBuilder()
		.setQueryParameter("action", action)
		.build()
}

@Serializable
private data class LanWebGitHubProofIssueRequest(
	val requestId: String,
	val clientName: String,
	val deviceId: String,
	val devicePublicKey: String,
	val deviceFingerprint: String,
	val ephemeralPublicKey: String,
	val platform: String,
	val issuedAt: Long,
	val targetDeviceId: String,
	val targetDevicePublicKey: String,
	val targetDeviceFingerprint: String,
	val proofNonce: String,
	val deviceSignature: String,
)

@Serializable
private data class LanWebGitHubProofVerifyRequest(
    val requestId: String,
    val clientName: String,
    val deviceId: String,
    val devicePublicKey: String,
    val deviceFingerprint: String,
    val ephemeralPublicKey: String,
    val platform: String,
    val issuedAt: Long,
    val targetDeviceId: String,
    val targetDevicePublicKey: String,
    val targetDeviceFingerprint: String,
    val ticket: String,
    val receiverIssuedAt: Long,
    val receiverSignature: String,
)
