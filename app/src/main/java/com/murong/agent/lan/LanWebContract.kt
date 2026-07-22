package com.murong.agent.lan

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

object LanWebContract {
    const val PORT = 8765
    const val API_PREFIX = "/api/v1"
    const val PAIR_PATH = "$API_PREFIX/pair"
    const val PAIR_CHALLENGE_PATH = "$API_PREFIX/pair/challenge"
    const val CONNECTION_REQUEST_PATH = "$API_PREFIX/connection/request"
    const val CONNECTION_STATUS_PATH = "$API_PREFIX/connection/status"
    const val PUBLIC_STATUS_PATH = "$API_PREFIX/public/status"
    const val SESSIONS_PATH = "$API_PREFIX/sessions"
    const val MESSAGES_PATH = "$API_PREFIX/messages"
    const val APPROVAL_PATH = "$API_PREFIX/approval"
    const val EVENTS_PATH = "$API_PREFIX/events"
    const val UNPAIR_PATH = "$API_PREFIX/unpair"
    const val WORKSPACE_STATUS_PATH = "$API_PREFIX/workspace/status"
    const val WORKSPACE_REGISTER_PATH = "$API_PREFIX/workspace/register"
    const val WORKSPACE_HEARTBEAT_PATH = "$API_PREFIX/workspace/heartbeat"
    const val WORKSPACE_RESULT_PATH = "$API_PREFIX/workspace/result"
    const val WORKSPACE_CHANGES_PATH = "$API_PREFIX/workspace/changes"
    const val WORKSPACE_DISCONNECT_PATH = "$API_PREFIX/workspace/disconnect"
    const val DESKTOP_AGENT_STATUS_PATH = "$API_PREFIX/desktop-agent/status"
    const val DESKTOP_AGENT_REGISTER_PATH = "$API_PREFIX/desktop-agent/register"
    const val DESKTOP_AGENT_SNAPSHOT_PATH = "$API_PREFIX/desktop-agent/snapshot"
    const val DESKTOP_AGENT_RESULT_PATH = "$API_PREFIX/desktop-agent/result"
    const val DESKTOP_AGENT_DISCONNECT_PATH = "$API_PREFIX/desktop-agent/disconnect"
    const val DEVICE_SYNC_PUSH_PATH = "$API_PREFIX/device-sync/push"
    const val DEVICE_SYNC_PULL_PATH = "$API_PREFIX/device-sync/pull"

    /** Password-wrapped bootstrap used to establish the long-lived device sync key. */
    const val SECURE_PAIRING_VERSION = "pbkdf2-sha256-aesgcm-v1"
    const val SCRAM_PAIRING_VERSION = "scram-sha-256-v1"
    const val DEVICE_SYNC_ENVELOPE_VERSION = "aes256-gcm-v1"
    const val DEVICE_LINK_ENVELOPE_VERSION = "ecdh-p256-aesgcm-v1"
    const val PAIRING_CODE_LENGTH = 16

    const val ACTION_START = "com.murong.agent.lan.START"
    const val ACTION_STOP = "com.murong.agent.lan.STOP"
    const val ACTION_APPROVE_CONNECTION = "com.murong.agent.lan.APPROVE_CONNECTION"
    const val ACTION_REJECT_CONNECTION = "com.murong.agent.lan.REJECT_CONNECTION"
    const val ACTION_BLOCK_CONNECTION = "com.murong.agent.lan.BLOCK_CONNECTION"
    const val ACTION_ADB_PAIR_CHALLENGE = "com.murong.agent.lan.ADB_PAIR_CHALLENGE"
    const val EXTRA_CONNECTION_REQUEST_ID = "com.murong.agent.lan.extra.CONNECTION_REQUEST_ID"
    const val EXTRA_ADB_CHALLENGE = "com.murong.agent.lan.extra.ADB_CHALLENGE"

    const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

    const val MAX_REQUEST_BODY_BYTES = 64 * 1024
    // A valid 1 MiB UTF-8 file can grow substantially after JSON escaping. Keep the
    // transport envelope larger than the semantic file limit; the bridge still
    // validates decoded UTF-8 bytes against the per-request maxBytes value.
    const val MAX_WORKSPACE_RESULT_BODY_BYTES = 8 * 1024 * 1024
    const val MAX_WORKSPACE_CHANGES_BODY_BYTES = 1024 * 1024
    const val MAX_DESKTOP_AGENT_BODY_BYTES = 4 * 1024 * 1024
    const val MAX_DESKTOP_HANDOFF_BYTES = 1536 * 1024
    const val MAX_DEVICE_SYNC_PLAIN_BYTES = 32 * 1024 * 1024
    const val MAX_DEVICE_SYNC_BODY_BYTES = 48 * 1024 * 1024
    const val MAX_MESSAGE_CHARS = 20_000
    const val MAX_CLIENT_NAME_CHARS = 40
    const val MAX_HISTORY_MESSAGES = 200
    const val MAX_MESSAGE_RESPONSE_CHARS = 50_000
    const val MAX_SSE_CONNECTIONS_PER_CLIENT = 2

    val requestIdPattern = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{7,95}$")
    val sessionIdPattern = Regex("^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$")
}

object LanWebTrustSource {
    const val LEGACY_CODE = "legacy_code"
    const val TEMPORARY_CODE = "temporary_code"
    const val SECURITY_PASSWORD = "security_password"
    const val CONNECTION_APPROVAL = "connection_approval"
    const val GITHUB_ACCOUNT = "github_account"
    const val LAN_CONFIRMATION = "lan_confirmation"
    const val ADB = "adb"

    val values = setOf(
        LEGACY_CODE,
        TEMPORARY_CODE,
        SECURITY_PASSWORD,
        CONNECTION_APPROVAL,
        GITHUB_ACCOUNT,
        LAN_CONFIRMATION,
        ADB,
    )
}

@Serializable
data class LanWebErrorResponse(
    val error: String,
    val code: String
)

@Serializable
data class LanWebPublicStatusResponse(
    val service: String = "murong-lan-web",
    val pairingAvailable: Boolean,
    val pairingMethods: List<String> = emptyList(),
    val protocolVersion: Int = 3,
    val deviceId: String = "",
    val deviceDisplayId: String = "",
    val devicePublicKey: String = "",
    val deviceFingerprint: String = "",
    val platform: String = "android",
)

@Serializable
data class LanWebPairRequest(
    val code: String = "",
    val clientName: String,
    val secureChannelVersion: String? = null,
    val codeProof: String? = null,
)

@Serializable
data class LanWebPairResponse(
    val clientId: String,
    val clientName: String,
    val createdAt: Long,
    val secureChannel: LanWebSecurePairingEnvelope? = null
)

@Serializable
data class LanWebPairChallengeRequest(
    val requestId: String,
    val clientName: String,
    val deviceId: String,
    val devicePublicKey: String,
    val deviceFingerprint: String,
    val ephemeralPublicKey: String,
    val platform: String = "desktop",
    val issuedAt: Long,
    val authMethod: String,
    val clientNonce: String,
    val signature: String,
)

@Serializable
data class LanWebPairChallengeResponse(
    val version: String = LanWebContract.SCRAM_PAIRING_VERSION,
    val sessionId: String,
    val serverNonce: String,
    val salt: String,
    val iterations: Int,
    val expiresAt: Long,
)

@Serializable
data class LanWebConnectionRequest(
    val requestId: String,
    val clientName: String,
    val deviceId: String,
    val devicePublicKey: String,
    val deviceFingerprint: String,
    val ephemeralPublicKey: String,
    val platform: String = "desktop",
    val issuedAt: Long,
    val authMethod: String = LanWebTrustSource.CONNECTION_APPROVAL,
    val authProof: String = "",
    val signature: String,
)

@Serializable
data class LanWebConnectionRequestAck(
    val requestId: String,
    val status: String,
    val expiresAt: Long,
    val message: String,
)

@Serializable
data class LanWebConnectionStatusRequest(
    val requestId: String,
    val deviceId: String,
    val issuedAt: Long,
    val signature: String,
)

@Serializable
data class LanWebConnectionStatusResponse(
    val requestId: String,
    val status: String,
    val message: String,
    val responderDeviceId: String = "",
    val responderPublicKey: String = "",
    val responderEphemeralPublicKey: String = "",
    val responderSignature: String = "",
    val clientId: String = "",
    val clientName: String = "",
    val createdAt: Long = 0L,
    val authServerProof: String = "",
    val secureChannel: LanWebDeviceLinkEnvelope? = null,
)

@Serializable
data class LanWebDeviceLinkEnvelope(
    val version: String = LanWebContract.DEVICE_LINK_ENVELOPE_VERSION,
    val nonce: String,
    val ciphertext: String,
)

@Serializable
data class LanWebConnectionRequestSummary(
    val requestId: String,
    val deviceId: String,
    val deviceDisplayId: String,
    val clientName: String,
    val platform: String,
    val publicKeyFingerprint: String,
    val authMethod: String,
    val transport: String,
    val createdAt: Long,
    val expiresAt: Long,
    val status: String,
)

@Serializable
data class LanWebSecurePairingEnvelope(
    val version: String,
    val salt: String,
    val nonce: String,
    val ciphertext: String
)

@Serializable
data class LanWebDeviceSyncEnvelope(
    val version: String = LanWebContract.DEVICE_SYNC_ENVELOPE_VERSION,
    val requestId: String,
    val issuedAt: Long,
    val direction: String,
    val nonce: String,
    val ciphertext: String
)

@Serializable
data class LanWebDeviceSyncOptions(
    val includeSessions: Boolean = false,
    val includeProviderCredentials: Boolean = true,
    val includeCodexLogin: Boolean = true,
    val includeGitHubCredentials: Boolean = true,
    val includeAgentSettings: Boolean = false,
    val includeKnowledge: Boolean = false,
    val includeMcp: Boolean = false,
    val includeMcpCredentials: Boolean = false,
    val includeSavedWorkflows: Boolean = false,
    val sessionCursor: Int = 0,
)

@Serializable
data class LanWebSyncedSession(
    val sourceSessionId: String,
    val originPlatform: String = "",
    val originSessionId: String = "",
    val document: JsonObject,
)

@Serializable
data class LanWebSyncedGitHubCredential(
    val apiBaseUrl: String = "https://api.github.com",
    val token: String? = null,
    val viewerLogin: String = "",
)

@Serializable
data class LanWebSyncedProviderCredential(
    val profileId: String,
    val providerId: String,
    val name: String,
    val baseUrl: String,
    val model: String,
    val reasoningEffort: String = "",
    val apiMode: String = "",
    val contextWindowTokens: Int? = null,
    val apiKey: String? = null
)

@Serializable
data class LanWebSyncedAgentSettings(
    val approvalMode: String,
    val systemPrompt: String,
    val responseVerbosity: String,
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val enableMultimodalMessages: Boolean? = null,
    val plannerProfileEnabled: Boolean? = null,
    val plannerModel: String? = null,
    val plannerReasoningEffort: String? = null,
    val subagentDefaultProfileEnabled: Boolean? = null,
    val subagentDefaultModel: String? = null,
    val subagentDefaultReasoningEffort: String? = null,
)

@Serializable
data class LanWebSyncedRule(
    val id: String,
    val title: String,
    val content: String,
    val enabled: Boolean,
)

@Serializable
data class LanWebSyncedMemory(
    val id: String,
    val title: String,
    val content: String,
    val enabled: Boolean,
)

@Serializable
data class LanWebSyncedSkill(
    val id: String,
    val title: String,
    val description: String,
    val content: String,
    val runAs: String,
    val allowedTools: List<String> = emptyList(),
    val preferredModel: String = "",
    val enabled: Boolean,
)

@Serializable
data class LanWebSyncedKnowledge(
    val rules: List<LanWebSyncedRule> = emptyList(),
    val memories: List<LanWebSyncedMemory> = emptyList(),
    val skills: List<LanWebSyncedSkill> = emptyList(),
)

@Serializable
data class LanWebSyncedMcpServer(
    val id: String,
    val name: String,
    val transport: String,
    val command: String = "",
    val args: List<String> = emptyList(),
    val url: String = "",
    val requestTimeoutSeconds: Int = 60,
    val trustedReadOnlyTools: List<String> = emptyList(),
    val enabled: Boolean = false,
    val autoStart: Boolean = false,
    val environment: Map<String, String> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
)

@Serializable
data class LanWebSyncedWorkflowNode(
    val id: String,
    val label: String,
    val dependsOn: List<String> = emptyList(),
    val requiredPermission: String,
    val timeoutSeconds: Int,
    val maxRetries: Int,
)

@Serializable
data class LanWebSyncedSavedWorkflow(
    val id: String,
    val name: String,
    val template: String,
    val githubRepository: String? = null,
    val nodes: List<LanWebSyncedWorkflowNode> = emptyList(),
    val intervalMinutes: Int,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
data class LanWebCredentialSyncBundle(
    val schemaVersion: Int = 6,
    val sourcePlatform: String,
    val generatedAt: Long,
    val activeProviderId: String? = null,
    val activeProfileId: String? = null,
    val providers: List<LanWebSyncedProviderCredential> = emptyList(),
    val codexAuthJson: String? = null,
    val github: LanWebSyncedGitHubCredential? = null,
    val agentSettings: LanWebSyncedAgentSettings? = null,
    val knowledge: LanWebSyncedKnowledge? = null,
    val mcpServers: List<LanWebSyncedMcpServer> = emptyList(),
    val mcpCredentialsIncluded: Boolean = false,
    val savedWorkflows: List<LanWebSyncedSavedWorkflow> = emptyList(),
    val sessions: List<LanWebSyncedSession> = emptyList(),
    val sessionNextCursor: Int? = null,
)

@Serializable
data class LanWebCredentialSyncResult(
    val importedSessions: Int = 0,
    val conflictSessions: Int = 0,
    val skippedSessions: Int = 0,
    val importedProviders: Int = 0,
    val importedApiKeys: Int = 0,
    val importedCodexLogin: Boolean = false,
    val importedGitHubToken: Boolean = false,
    val accountEmail: String? = null,
    val importedSettings: Boolean = false,
    val importedRules: Int = 0,
    val importedMemories: Int = 0,
    val importedSkills: Int = 0,
    val importedMcpServers: Int = 0,
    val importedWorkflows: Int = 0,
    val disabledMcpServers: Int = 0,
    val skippedWorkflows: Int = 0,
)

@Serializable
data class LanWebClientSummary(
    val id: String,
    val name: String,
    val createdAt: Long,
    val lastSeenAt: Long? = null,
    val secureSync: Boolean = false,
    val deviceId: String = "",
    val publicKeyFingerprint: String = "",
    val trustSource: String = LanWebTrustSource.LEGACY_CODE,
)

@Serializable
data class LanWebBlockedPeerSummary(
    val deviceId: String,
    val name: String,
    val publicKeyFingerprint: String,
    val blockedAt: Long,
)

@Serializable
data class LanWebSessionsResponse(
    val activeSessionId: String,
    val sessions: List<LanWebSessionSummary>
)

@Serializable
data class LanWebSessionSummary(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val messageCount: Int,
    val providerId: String,
    val modelName: String,
    val projectLabel: String? = null,
    val active: Boolean = false,
    val processing: Boolean = false,
    val pendingApproval: Boolean = false
)

@Serializable
data class LanWebMessage(
    val id: Long,
    val role: String,
    val content: String,
    val streaming: Boolean,
    val timestamp: Long,
    val attachmentNames: List<String> = emptyList()
)

@Serializable
data class LanWebApproval(
    val approvalId: String,
    val sessionId: String,
    val toolName: String,
    val summary: String,
    val detail: String,
    val arguments: String,
    val riskLevel: String,
    val explanationLabel: String? = null,
    val explanationDetail: String? = null,
    val approveEnabled: Boolean
)

@Serializable
data class LanWebSessionDetail(
    val id: String,
    val title: String,
    val active: Boolean,
    val processing: Boolean,
    val error: String? = null,
    val messages: List<LanWebMessage>,
    val pendingApproval: LanWebApproval? = null
)

@Serializable
data class LanWebLiveState(
    val session: LanWebLiveSessionState,
    val updatedAt: Long
)

@Serializable
data class LanWebLiveSessionState(
    val id: String,
    val title: String,
    val processing: Boolean,
    val error: String? = null,
    val lastMessage: LanWebMessage? = null,
    val pendingApproval: LanWebApproval? = null
)

@Serializable
data class LanWebSendMessageRequest(
    val sessionId: String,
    val message: String,
    val requestId: String
)

@Serializable
data class LanWebApprovalDecisionRequest(
    val sessionId: String,
    val approvalId: String,
    val decision: String,
    val requestId: String
)

@Serializable
data class LanWebTerminalBackend(
    val id: String,
    val label: String,
    val version: String = ""
)

@Serializable
data class LanWebWorkspaceRegisterRequest(
    val workspaceSessionId: String,
    val label: String,
    val platform: String = LEGACY_DESKTOP_PLATFORM,
    val architecture: String = "",
    val readable: Boolean = true,
    val writable: Boolean = false,
    val terminal: Boolean = false,
    val terminals: List<LanWebTerminalBackend> = emptyList(),
    val requestId: String
)

@Serializable
data class LanWebWorkspaceHeartbeatRequest(
    val workspaceSessionId: String
)

@Serializable
data class LanWebWorkspaceDisconnectRequest(
    val workspaceSessionId: String
)

@Serializable
data class LanWebWorkspaceStatusResponse(
    val connected: Boolean,
    val workspaceSessionId: String? = null,
    val label: String? = null,
    val platform: String? = null,
    val architecture: String? = null,
    val readable: Boolean = false,
    val writable: Boolean = false,
    val terminal: Boolean = false,
    val terminals: List<LanWebTerminalBackend> = emptyList(),
    val heartbeatIntervalMillis: Long = 10_000L,
    val expiresAfterMillis: Long = 30_000L
)

@Serializable
data class LanWebWorkspaceRpcEvent(
    val workspaceSessionId: String,
    val requestId: String,
    val operation: String,
    val path: String,
    val content: String? = null,
    val expectedSha256: String? = null,
    val command: String? = null,
    val terminalId: String? = null,
    val timeoutMillis: Long? = null,
    val maxBytes: Int,
    val maxEntries: Int,
    val expiresAt: Long
)

data class LanWebWorkspaceRpcDispatch(
    val targetClientId: String,
    val event: LanWebWorkspaceRpcEvent
)

@Serializable
data class LanWebWorkspaceEntryResponse(
    val name: String,
    val path: String,
    val directory: Boolean,
    val size: Long? = null,
    val lastModified: Long? = null
)

@Serializable
data class LanWebWorkspaceResultRequest(
    val workspaceSessionId: String,
    val requestId: String,
    val success: Boolean,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val entries: List<LanWebWorkspaceEntryResponse> = emptyList(),
    val content: String? = null,
    val sha256: String? = null,
    val size: Long? = null,
    val lastModified: Long? = null,
    val directory: Boolean? = null,
    val created: Boolean = false,
    val diffPreview: String? = null,
    val stdout: String? = null,
    val stderr: String? = null,
    val exitCode: Int? = null,
    val timedOut: Boolean = false
)

@Serializable
data class LanWebWorkspaceObservedChange(
    val path: String,
    val kind: String,
    val directory: Boolean = false
)

@Serializable
data class LanWebWorkspaceChangeReportRequest(
    val workspaceSessionId: String,
    val reportId: String,
    val changes: List<LanWebWorkspaceObservedChange>,
    val partialScan: Boolean = false
)

@Serializable
data class LanWebDesktopAgentRegisterRequest(
    val nodeSessionId: String,
    val protocolVersion: Int = 1,
    val sourcePlatform: String = LEGACY_DESKTOP_PLATFORM,
    val sourceArchitecture: String = "",
    val controlAllowed: Boolean = false,
    val requestId: String
)

@Serializable
data class LanWebDesktopAgentDisconnectRequest(
    val nodeSessionId: String
)

@Serializable
data class LanWebDesktopAgentTaskSummary(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val messageCount: Int,
    val running: Boolean = false,
    val pendingApproval: Boolean = false,
    val pendingQuestion: Boolean = false,
    val executionOwner: String = "desktop",
    val handoffStartedAt: Long? = null
)

@Serializable
data class LanWebDesktopAgentMessage(
    val id: String,
    val role: String,
    val content: String,
    val timestamp: Long,
    val kind: String? = null,
    val toolName: String? = null,
    val attachmentNames: List<String> = emptyList()
)

@Serializable
data class LanWebDesktopAgentApproval(
    val id: String,
    val sessionId: String,
    val toolName: String,
    val summary: String,
    val detail: String,
    val risk: String
)

@Serializable
data class LanWebDesktopAgentAskOption(
    val label: String,
    val description: String = ""
)

@Serializable
data class LanWebDesktopAgentAskQuestion(
    val id: String,
    val header: String = "",
    val question: String,
    val options: List<LanWebDesktopAgentAskOption>,
    val multiSelect: Boolean = false
)

@Serializable
data class LanWebDesktopAgentAskRequest(
    val id: String,
    val sessionId: String,
    val questions: List<LanWebDesktopAgentAskQuestion>,
    val createdAt: Long
)

@Serializable
data class LanWebDesktopAgentAskAnswer(
    val questionId: String,
    val selectedOptions: List<String>
)

@Serializable
data class LanWebDesktopAgentWorkflowStepSignOff(
    val stepIndex: Int,
    val resultSummary: String,
    val matchedEvidence: Int,
    val totalEvidence: Int,
    val matchedToolNames: List<String> = emptyList(),
    val signedOffAt: Long
)

@Serializable
data class LanWebDesktopAgentWorkflowPlan(
    val id: String,
    val summary: String,
    val steps: List<String>,
    val currentStepIndex: Int,
    val status: String,
    val nextStepHint: String = "",
    val stepSignOffs: List<LanWebDesktopAgentWorkflowStepSignOff> = emptyList(),
    val createdAt: Long,
    val executionStartedAt: Long? = null,
    val updatedAt: Long
)

@Serializable
data class LanWebDesktopAgentTaskDetail(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val running: Boolean = false,
    val messages: List<LanWebDesktopAgentMessage> = emptyList(),
    val messageCount: Int = messages.size,
    val pendingApproval: LanWebDesktopAgentApproval? = null,
    val pendingAsk: LanWebDesktopAgentAskRequest? = null,
    val workflowPlan: LanWebDesktopAgentWorkflowPlan? = null,
    val executionOwner: String = "desktop",
    val handoffStartedAt: Long? = null
)

@Serializable
data class LanWebDesktopAgentSnapshotRequest(
    val nodeSessionId: String,
    val sourcePlatform: String = LEGACY_DESKTOP_PLATFORM,
    val sourceArchitecture: String = "",
    val sequence: Long,
    val generatedAt: Long,
    val sessions: List<LanWebDesktopAgentTaskSummary> = emptyList(),
    val activeSession: LanWebDesktopAgentTaskDetail? = null,
    /**
     * 最近在电脑端发生变化的其他任务。它们不会改变手机当前打开的任务，
     * 只用于让 Android 的持久镜像在后台收敛。
     */
    val sessionUpdates: List<LanWebDesktopAgentTaskDetail> = emptyList()
)

@Serializable
data class LanWebDesktopAgentStatusResponse(
    val connected: Boolean = false,
    val nodeSessionId: String? = null,
    val sourcePlatform: String? = null,
    val sourceArchitecture: String? = null,
    val controlAllowed: Boolean = false,
    val lastSeenAt: Long? = null,
    val snapshot: LanWebDesktopAgentSnapshotRequest? = null,
    val heartbeatIntervalMillis: Long = 3_000L,
    val expiresAfterMillis: Long = 30_000L
)

@Serializable
data class LanWebDesktopAgentCommandEvent(
    val nodeSessionId: String,
    val requestId: String,
    val operation: String,
    val sessionId: String? = null,
    val content: String? = null,
    val approvalId: String? = null,
    val approve: Boolean? = null,
    val askId: String? = null,
    val askAnswers: List<LanWebDesktopAgentAskAnswer> = emptyList(),
    val dismissAsk: Boolean = false,
    val handoffToken: String? = null,
    val portableSession: String? = null,
    val handoffEnvelope: LanWebDeviceSyncEnvelope? = null,
    val expiresAt: Long
)

data class LanWebDesktopAgentCommandDispatch(
    val targetClientId: String,
    val event: LanWebDesktopAgentCommandEvent
)

@Serializable
data class LanWebDesktopAgentCommandResultRequest(
    val nodeSessionId: String,
    val requestId: String,
    val success: Boolean,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val snapshot: LanWebDesktopAgentSnapshotRequest? = null,
    val session: LanWebDesktopAgentTaskDetail? = null,
    val handoffToken: String? = null,
    val portableSession: String? = null,
    val handoffEnvelope: LanWebDeviceSyncEnvelope? = null
)

@Serializable
data class LanWebAcceptedResponse(
    val accepted: Boolean,
    val requestId: String,
    val message: String
)

@Serializable
data class LanWebServiceState(
    val running: Boolean = false,
    val starting: Boolean = false,
    val address: String? = null,
    val port: Int = LanWebContract.PORT,
    val deviceId: String = "",
    val deviceDisplayId: String = "",
    val deviceFingerprint: String = "",
    val pairingCode: String? = null,
    val pairingExpiresAt: Long? = null,
    val pairingCooldownUntil: Long? = null,
    val securityPasswordConfigured: Boolean = false,
    val clients: List<LanWebClientSummary> = emptyList(),
    val blockedPeers: List<LanWebBlockedPeerSummary> = emptyList(),
    val doNotDisturb: Boolean = false,
    val connectionRequests: List<LanWebConnectionRequestSummary> = emptyList(),
    val workspaceConnected: Boolean = false,
    val workspaceLabel: String? = null,
    val workspacePlatform: String? = null,
    val workspaceArchitecture: String? = null,
    val workspaceWritable: Boolean = false,
    val workspaceTerminal: Boolean = false,
    val workspaceTerminals: List<LanWebTerminalBackend> = emptyList(),
    val desktopAgentConnected: Boolean = false,
    val desktopAgentControlAllowed: Boolean = false,
    val deviceRelayRunning: Boolean = false,
    val deviceRelayConnecting: Boolean = false,
    val deviceRelayConnected: Boolean = false,
    val deviceRelayStatus: String = "公网本机 ID 未启动",
    val deviceRelayError: String? = null,
    val outgoingDeviceConnection: Boolean = false,
    val outgoingDeviceConnectionStatus: String = "",
    val discoveringDevices: Boolean = false,
    val environmentDevices: List<LanWebDiscoveredDevice> = emptyList(),
    val error: String? = null
) {
    val nodeUrl: String?
        get() = address?.let { host ->
            val formatted = if (host.contains(':')) "[$host]" else host
            "http://$formatted:$port/"
        }

    /** Historical Web Chat compatibility alias. New UI should call this the node address. */
    val browserUrl: String?
        get() = nodeUrl
}

data class LanWebIssuedClient(
    val summary: LanWebClientSummary,
    val accessToken: String,
    val syncKey: ByteArray? = null,
    val pairingSecret: ByteArray? = null,
)

data class LanWebPairingCode(
    val value: String,
    val expiresAt: Long
)
