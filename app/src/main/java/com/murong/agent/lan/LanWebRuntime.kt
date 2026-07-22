package com.murong.agent.lan

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.content.ContextCompat
import com.murong.agent.core.config.ConfigRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.KeyStore
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Singleton
class LanWebRuntime @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val chatBridge: LanWebChatBridge,
    private val computerWorkspaceBridge: LanWebComputerWorkspaceBridge,
    private val desktopAgentBridge: LanWebDesktopAgentBridge,
    private val credentialSyncBridge: LanWebCredentialSyncBridge,
    private val deviceTunnelManager: LanWebDeviceTunnelManager,
    private val configRepository: ConfigRepository,
) {
    private val accessStore = LanWebAccessStore(context)
    private val deviceIdentity = LanWebDeviceIdentity(context)
    private val adbPairingStore = LanWebAdbPairingStore(context)
    private val pairingAuthenticator = LanWebPairingAuthenticator(context)
    private val githubAccountTrust = LanWebGitHubAccountTrust(configRepository, deviceIdentity)
    private val discoveryScanner = LanWebDiscoveryScanner(deviceIdentity)
    private val connectionCoordinator = LanWebConnectionCoordinator(
        accessStore = accessStore,
        identity = deviceIdentity,
        adbAuthenticator = adbPairingStore::consumeProof,
        passwordAuthenticator = pairingAuthenticator::authenticate,
        githubAccountAuthenticator = githubAccountTrust::verify,
        onChanged = ::refreshConnectionRequests,
    )
    private val deviceRelayManager = LanWebDeviceRelayManager(
        identity = deviceIdentity,
        deviceTunnelManager = deviceTunnelManager,
        isBlocked = accessStore::isBlocked,
		githubAccountTrust = githubAccountTrust,
        onConnectionAccepted = { deviceId, fingerprint -> connectionCoordinator.expectPeer(deviceId, fingerprint) },
    )
    private val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleMutex = Mutex()
    private val eventHub = LanWebEventHub()
    private val json = Json { encodeDefaults = true; explicitNulls = false }
    private val mutableState = MutableStateFlow(
        LanWebServiceState(
            clients = accessStore.clients(),
            blockedPeers = accessStore.blockedPeers(),
            doNotDisturb = accessStore.doNotDisturb(),
            deviceId = deviceIdentity.snapshot.deviceId,
            deviceDisplayId = deviceIdentity.snapshot.displayId,
            deviceFingerprint = deviceIdentity.snapshot.publicKeyFingerprint,
            securityPasswordConfigured = pairingAuthenticator.snapshot().securityPasswordConfigured,
        ).withDeviceRelay(deviceRelayManager.state.value)
    )
    private var lanServer: LanWebServer? = null
    private var relayLoopbackServer: LanWebServer? = null
    private var stateRelayJob: Job? = null
    private var workspaceRelayJob: Job? = null
    private var desktopAgentRelayJob: Job? = null
    private var deviceRelayStateJob: Job? = null
    private var workspaceStatusJob: Job? = null
    private var pairingExpiryJob: Job? = null
    private var discoveryResponder: LanWebDiscoveryResponder? = null

    val state: StateFlow<LanWebServiceState> = mutableState.asStateFlow()

    init {
        removeLegacyRelayArtifacts()
    }

    suspend fun start(): Result<Unit> = lifecycleMutex.withLock {
        runCatching {
            mutableState.value = mutableState.value.copy(starting = true, error = null)
            val bindAddress = if (hasLocalNetworkPermission()) resolvePrivateWifiOrEthernetAddress() else null
            if (bindAddress != null && lanServer == null) {
                lanServer = createServer(bindAddress, LanWebContract.PORT).also {
                    it.start(SOCKET_READ_TIMEOUT_MILLIS, false)
                }
                discoveryResponder = LanWebDiscoveryResponder(
                    scope = runtimeScope,
                    identity = deviceIdentity,
                    name = "${Build.MANUFACTURER} ${Build.MODEL}".trim().ifBlank { "Murong Android" },
                ).also { it.start() }
            }
            if (relayLoopbackServer == null) {
                relayLoopbackServer = createServer(
                    LOOPBACK_ADDRESS,
                    LanWebDeviceTunnelProtocol.LOOPBACK_PORT,
                ).also { it.start(SOCKET_READ_TIMEOUT_MILLIS, false) }
            }
            if (deviceRelayStateJob == null) {
                deviceRelayStateJob = runtimeScope.launch {
                    deviceRelayManager.state.collect { relay ->
                        mutableState.value = mutableState.value.withDeviceRelay(relay)
                    }
                }
                deviceRelayManager.start(LanWebDeviceTunnelProtocol.LOOPBACK_PORT).getOrThrow()
            }
            if (stateRelayJob == null) {
                stateRelayJob = runtimeScope.launch {
                    chatBridge.state.collect {
                        eventHub.publish("session_state", json.encodeToString(chatBridge.liveState()))
                    }
                }
            }
            if (workspaceRelayJob == null) {
                workspaceRelayJob = runtimeScope.launch {
                    computerWorkspaceBridge.requests.collect { dispatch ->
                        eventHub.publish(
                            type = "workspace_request",
                            jsonData = json.encodeToString(dispatch.event),
                            targetClientId = dispatch.targetClientId,
                            replayable = false
                        )
                    }
                }
            }
            if (desktopAgentRelayJob == null) {
                desktopAgentRelayJob = runtimeScope.launch {
                    desktopAgentBridge.commands.collect { dispatch ->
                        eventHub.publish(
                            type = "desktop_agent_command",
                            jsonData = json.encodeToString(dispatch.event),
                            targetClientId = dispatch.targetClientId,
                            replayable = false
                        )
                    }
                }
            }
            if (workspaceStatusJob == null) {
                workspaceStatusJob = runtimeScope.launch {
                    while (true) {
                        val workspace = computerWorkspaceBridge.activeWorkspace()
                        val desktopAgent = desktopAgentBridge.status()
                        mutableState.value = mutableState.value.copy(
                            workspaceConnected = workspace != null,
                            workspaceLabel = workspace?.label,
                            workspacePlatform = workspace?.platform,
                            workspaceArchitecture = workspace?.architecture,
                            workspaceWritable = workspace?.writable == true,
                            workspaceTerminal = workspace?.terminal == true,
                            workspaceTerminals = workspace?.effectiveTerminalBackends.orEmpty().map {
                                LanWebTerminalBackend(it.id, it.label, it.version)
                            },
                            desktopAgentConnected = desktopAgent.connected,
                            desktopAgentControlAllowed = desktopAgent.controlAllowed
                        )
                        delay(1_000L)
                    }
                }
            }
            mutableState.value = mutableState.value.copy(
                running = true,
                starting = false,
                address = bindAddress ?: mutableState.value.address,
                clients = accessStore.clients(),
                error = null
            ).withDeviceRelay(deviceRelayManager.state.value)
            scheduleTemporaryCodeRefresh()
        }.onFailure { error ->
            lanServer?.stop()
            lanServer = null
            discoveryResponder?.stop()
            discoveryResponder = null
            relayLoopbackServer?.stop()
            relayLoopbackServer = null
            deviceTunnelManager.stop()
            deviceRelayManager.stop()
            deviceRelayStateJob?.cancel()
            deviceRelayStateJob = null
            stateRelayJob?.cancel()
            stateRelayJob = null
            workspaceRelayJob?.cancel()
            workspaceRelayJob = null
            desktopAgentRelayJob?.cancel()
            desktopAgentRelayJob = null
            workspaceStatusJob?.cancel()
            workspaceStatusJob = null
            computerWorkspaceBridge.shutdown()
            desktopAgentBridge.shutdown()
            mutableState.value = mutableState.value.copy(
                running = false,
                starting = false,
                address = null,
                error = safeError(error.message ?: "局域网服务启动失败")
            )
        }
    }

    suspend fun stop(clearError: Boolean = true) = lifecycleMutex.withLock {
        pairingExpiryJob?.cancel()
        pairingExpiryJob = null
        accessStore.cancelPairing()
        pairingAuthenticator.cancelTemporaryCode()
        adbPairingStore.clear()
        stateRelayJob?.cancel()
        stateRelayJob = null
        workspaceRelayJob?.cancel()
        workspaceRelayJob = null
        desktopAgentRelayJob?.cancel()
        desktopAgentRelayJob = null
        deviceRelayStateJob?.cancel()
        deviceRelayStateJob = null
        workspaceStatusJob?.cancel()
        workspaceStatusJob = null
        deviceTunnelManager.stop()
        deviceRelayManager.stop()
        discoveryResponder?.stop()
        discoveryResponder = null
        lanServer?.stop()
        lanServer = null
        relayLoopbackServer?.stop()
        relayLoopbackServer = null
        computerWorkspaceBridge.shutdown()
        desktopAgentBridge.shutdown()
        val previousError = mutableState.value.error
        mutableState.value = mutableState.value.copy(
            running = false,
            starting = false,
            address = null,
            pairingCode = null,
            pairingExpiresAt = null,
            pairingCooldownUntil = pairingAuthenticator.snapshot().cooldownUntil,
            securityPasswordConfigured = pairingAuthenticator.snapshot().securityPasswordConfigured,
            clients = accessStore.clients(),
            workspaceConnected = false,
            workspaceLabel = null,
            workspacePlatform = null,
            workspaceArchitecture = null,
            workspaceWritable = false,
            workspaceTerminal = false,
			workspaceTerminals = emptyList(),
            desktopAgentConnected = false,
            desktopAgentControlAllowed = false,
            error = if (clearError) null else previousError
        ).withDeviceRelay(deviceRelayManager.state.value)
    }

    fun beginPairing(): Result<LanWebPairingCode> = runCatching {
        require(state.value.running) { "请先启动电脑节点服务" }
        val code = pairingAuthenticator.beginTemporaryCode()
        try {
            accessStore.beginPairing(rawCode = code.value)
        } catch (error: Throwable) {
            pairingAuthenticator.cancelTemporaryCode()
            throw error
        }
        mutableState.value = mutableState.value.copy(
            pairingCode = code.value,
            pairingExpiresAt = code.expiresAt,
            pairingCooldownUntil = null,
            error = null
        )
        pairingExpiryJob?.cancel()
        pairingExpiryJob = runtimeScope.launch {
            delay((code.expiresAt - System.currentTimeMillis()).coerceAtLeast(0L))
            refreshPairingAuthState()
        }
        code
    }.onFailure { error ->
        mutableState.value = mutableState.value.copy(error = safeError(error.message ?: "无法生成配对码"))
    }

    fun setSecurityPassword(password: String): Result<Unit> = runCatching {
        pairingAuthenticator.setSecurityPassword(password)
        refreshPairingAuthState()
    }.onFailure { error ->
        mutableState.value = mutableState.value.copy(error = safeError(error.message ?: "无法设置安全密码"))
    }

    fun clearSecurityPassword(): Result<Unit> = runCatching {
        pairingAuthenticator.clearSecurityPassword()
        refreshPairingAuthState()
    }.onFailure { error ->
        mutableState.value = mutableState.value.copy(error = safeError(error.message ?: "无法清除安全密码"))
    }

    suspend fun requestDeviceConnection(deviceId: String, authMethod: String = "", secret: String = ""): Result<Unit> =
        deviceRelayManager.requestConnection(
            targetDeviceId = deviceId,
            deviceName = "${Build.MANUFACTURER} ${Build.MODEL}".trim().ifBlank { "Murong Android" },
            authMethod = authMethod,
            secret = secret,
        )

    suspend fun discoverEnvironmentDevices(): Result<List<LanWebDiscoveredDevice>> = runCatching {
        require(mutableState.value.running) { "请先开启远程控制" }
        mutableState.value = mutableState.value.copy(discoveringDevices = true, error = null)
        discoveryScanner.discover().also { devices ->
            mutableState.value = mutableState.value.copy(
                discoveringDevices = false,
                environmentDevices = devices,
            )
        }
    }.onFailure { error ->
        mutableState.value = mutableState.value.copy(
            discoveringDevices = false,
            error = safeError(error.message ?: "无法查找当前环境中的设备"),
        )
    }

    fun revokeClient(clientId: String): Boolean {
        val revoked = accessStore.revokeClient(clientId)
        if (revoked) {
            computerWorkspaceBridge.disconnect(clientId)
            desktopAgentBridge.disconnect(clientId)
            desktopAgentBridge.forgetClient(clientId)
        }
        refreshClients()
        return revoked
    }

    fun revokeAllClients() {
        accessStore.revokeAll()
        computerWorkspaceBridge.shutdown()
        desktopAgentBridge.forgetAllClients()
        refreshClients()
        clearPairingDisplay()
    }

    fun setDoNotDisturb(enabled: Boolean) {
        accessStore.setDoNotDisturb(enabled)
        refreshClients()
    }

    fun blockClient(client: LanWebClientSummary): Boolean {
        if (client.deviceId.isBlank() || client.publicKeyFingerprint.isBlank()) return false
        val blocked = accessStore.blockPeer(client.deviceId, client.name, client.publicKeyFingerprint)
        if (blocked) {
            computerWorkspaceBridge.disconnect(client.id)
            desktopAgentBridge.disconnect(client.id)
            desktopAgentBridge.forgetClient(client.id)
        }
        refreshClients()
        return blocked
    }

    fun unblockPeer(deviceId: String): Boolean {
        val unblocked = accessStore.unblockPeer(deviceId)
        refreshClients()
        return unblocked
    }

    fun approveConnectionRequest(requestId: String): Boolean {
        val approved = runCatching { connectionCoordinator.approve(requestId) }
            .onFailure { error -> reportServiceError(error.message ?: "无法批准连接申请") }
            .getOrDefault(false)
        refreshClients()
        refreshConnectionRequests()
        return approved
    }

    fun rejectConnectionRequest(requestId: String, block: Boolean): Boolean {
        val rejected = runCatching { connectionCoordinator.reject(requestId, block) }
            .onFailure { error -> reportServiceError(error.message ?: "无法处理连接申请") }
            .getOrDefault(false)
        refreshClients()
        refreshConnectionRequests()
        return rejected
    }

    fun reportPermissionDenied() {
        mutableState.value = mutableState.value.copy(error = "未授予局域网访问权限，服务保持关闭")
    }

    fun reportServiceError(message: String) {
        mutableState.value = mutableState.value.copy(
            running = false,
            starting = false,
            address = null,
            error = safeError(message)
        )
    }

    fun clearError() {
        mutableState.value = mutableState.value.copy(error = null)
    }

    fun hasLocalNetworkPermission(): Boolean {
        if (Build.VERSION.SDK_INT < 37) return true
        return ContextCompat.checkSelfPermission(
            context,
            LanWebContract.LOCAL_NETWORK_PERMISSION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun refreshClients() {
        mutableState.value = mutableState.value.copy(
            clients = accessStore.clients(),
            blockedPeers = accessStore.blockedPeers(),
            doNotDisturb = accessStore.doNotDisturb(),
        )
    }

    private fun refreshConnectionRequests() {
        refreshClients()
        val previous = mutableState.value.connectionRequests.associateBy { it.requestId }
        val next = connectionCoordinator.summaries()
        next.filterNot { it.requestId in previous }.forEach { request ->
            LanWebConnectionRequestNotifier.notify(context, request)
        }
        previous.keys.filterNot { id -> next.any { it.requestId == id } }.forEach { id ->
            LanWebConnectionRequestNotifier.cancel(context, id)
        }
        mutableState.value = mutableState.value.copy(
            connectionRequests = next,
        )
        refreshPairingAuthState()
    }

    private fun createServer(
        bindAddress: String,
        port: Int,
    ) = LanWebServer(
        context = context,
        bindAddress = bindAddress,
        accessStore = accessStore,
        deviceIdentity = deviceIdentity,
        connectionCoordinator = connectionCoordinator,
        pairingAuthenticator = pairingAuthenticator,
        githubAccountAvailable = githubAccountTrust::isAvailable,
        chatBridge = chatBridge,
        eventHub = eventHub,
        computerWorkspaceBridge = computerWorkspaceBridge,
        desktopAgentBridge = desktopAgentBridge,
        credentialSyncBridge = credentialSyncBridge,
        onAccessChanged = ::refreshClients,
        onPairingConsumed = ::clearPairingDisplay,
        port = port,
    )

    private fun clearPairingDisplay() {
        accessStore.cancelPairing()
        pairingAuthenticator.cancelTemporaryCode()
        mutableState.value = mutableState.value.copy(
            pairingCode = null,
            pairingExpiresAt = null,
            pairingCooldownUntil = pairingAuthenticator.snapshot().cooldownUntil,
        )
        scheduleTemporaryCodeRefresh()
    }

    private fun refreshPairingAuthState() {
        val snapshot = pairingAuthenticator.snapshot()
        if (!snapshot.temporaryCodeAvailable) accessStore.cancelPairing()
        mutableState.value = mutableState.value.copy(
            pairingCode = mutableState.value.pairingCode.takeIf { snapshot.temporaryCodeAvailable },
            pairingExpiresAt = snapshot.temporaryCodeExpiresAt,
            pairingCooldownUntil = snapshot.cooldownUntil,
            securityPasswordConfigured = snapshot.securityPasswordConfigured,
        )
        scheduleTemporaryCodeRefresh(snapshot)
    }

    private fun scheduleTemporaryCodeRefresh(
        snapshot: LanWebPairingAuthSnapshot = pairingAuthenticator.snapshot(),
    ) {
        if (!mutableState.value.running || snapshot.temporaryCodeAvailable) return
        pairingExpiryJob?.cancel()
        pairingExpiryJob = runtimeScope.launch {
            val retryAt = snapshot.cooldownUntil ?: System.currentTimeMillis()
            delay((retryAt - System.currentTimeMillis()).coerceAtLeast(0L))
            if (mutableState.value.running && mutableState.value.pairingCode == null) {
                beginPairing()
            }
        }
    }

    private fun resolvePrivateWifiOrEthernetAddress(): String? {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val tailnetAddress = runCatching {
            Collections.list(NetworkInterface.getNetworkInterfaces())
                .asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { networkInterface -> Collections.list(networkInterface.inetAddresses).asSequence() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull(::isTailnetIpv4)
                ?.hostAddress
        }.getOrNull()
        if (!tailnetAddress.isNullOrBlank()) return tailnetAddress

        val vpnTailnetAddress = connectivity.allNetworks.asSequence()
            .mapNotNull { network ->
                val capabilities = connectivity.getNetworkCapabilities(network) ?: return@mapNotNull null
                if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@mapNotNull null
                connectivity.getLinkProperties(network)?.linkAddresses
                    ?.asSequence()
                    ?.map { it.address }
                    ?.filterIsInstance<Inet4Address>()
                    ?.firstOrNull(::isTailnetIpv4)
                    ?.hostAddress
            }
            .firstOrNull()
        if (!vpnTailnetAddress.isNullOrBlank()) return vpnTailnetAddress

        return connectivity.allNetworks.asSequence()
            .mapNotNull { network ->
                val capabilities = connectivity.getNetworkCapabilities(network) ?: return@mapNotNull null
                val allowedTransport = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                if (!allowedTransport || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    return@mapNotNull null
                }
                connectivity.getLinkProperties(network)?.linkAddresses
                    ?.asSequence()
                    ?.map { it.address }
                    ?.filterIsInstance<Inet4Address>()
                    ?.firstOrNull { address ->
                        !address.isAnyLocalAddress &&
                            !address.isLoopbackAddress &&
                            LanWebSecurity.isAllowedRemoteAddress(address.hostAddress)
                    }
                    ?.hostAddress
            }
            .firstOrNull()
    }

    private fun isTailnetIpv4(address: Inet4Address): Boolean {
        val bytes = address.address
        val first = bytes[0].toInt() and 0xff
        val second = bytes[1].toInt() and 0xff
        return first == 100 && second in 64..127
    }

    private fun safeError(message: String): String =
        com.murong.agent.core.doctor.SensitiveDataSanitizer
            .sanitizeText(message, redactPaths = true)
            .take(500)

    private fun removeLegacyRelayArtifacts() {
        runCatching { context.noBackupFilesDir.resolve("lan_cloud_relay.json").delete() }
        runCatching {
            context.getSharedPreferences("murong_lan_cloud_relay", Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit()
        }
        runCatching {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                .deleteEntry("murong_lan_cloud_relay_v1")
        }
    }

    private companion object {
        const val SOCKET_READ_TIMEOUT_MILLIS = 10_000
        const val LOOPBACK_ADDRESS = "127.0.0.1"
    }
}

private fun LanWebServiceState.withDeviceRelay(relay: LanWebDeviceRelayState): LanWebServiceState = copy(
    deviceRelayRunning = relay.running,
    deviceRelayConnecting = relay.connecting,
    deviceRelayConnected = relay.connected,
    deviceRelayStatus = relay.status,
    deviceRelayError = relay.error,
    outgoingDeviceConnection = relay.outgoingConnection,
    outgoingDeviceConnectionStatus = relay.outgoingConnectionStatus,
)
