package com.murong.agent.lan

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.Inet4Address
import java.net.NetworkInterface
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
    private val cloudRelayManager: LanWebCloudRelayManager,
) {
    private val accessStore = LanWebAccessStore(context)
    private val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleMutex = Mutex()
    private val eventHub = LanWebEventHub()
    private val json = Json { encodeDefaults = true; explicitNulls = false }
    private val mutableState = MutableStateFlow(
        LanWebServiceState(clients = accessStore.clients()).withCloudRelay(cloudRelayManager.state.value)
    )
    private var lanServer: LanWebServer? = null
    private var relayLoopbackServer: LanWebServer? = null
    private var stateRelayJob: Job? = null
    private var workspaceRelayJob: Job? = null
    private var desktopAgentRelayJob: Job? = null
    private var cloudRelayStateJob: Job? = null
    private var workspaceStatusJob: Job? = null
    private var pairingExpiryJob: Job? = null

    val state: StateFlow<LanWebServiceState> = mutableState.asStateFlow()

    suspend fun start(): Result<Unit> = lifecycleMutex.withLock {
        runCatching {
            if (lanServer != null || relayLoopbackServer != null) return@runCatching
            mutableState.value = mutableState.value.copy(starting = true, error = null)
            val relayConfig = cloudRelayManager.configured()
            val bindAddress = if (hasLocalNetworkPermission()) resolvePrivateWifiOrEthernetAddress() else null
            require(bindAddress != null || relayConfig.enabled) {
                if (hasLocalNetworkPermission()) {
                    "没有可用的 Tailnet 或 Wi-Fi/以太网私有 IPv4 地址，也未启用云中继"
                } else {
                    "未授予局域网访问权限，也未启用云中继"
                }
            }
            if (bindAddress != null) {
                lanServer = createServer(bindAddress, LanWebContract.PORT).also {
                    it.start(SOCKET_READ_TIMEOUT_MILLIS, false)
                }
            }
            if (relayConfig.enabled) {
                relayLoopbackServer = createServer(
                    LOOPBACK_ADDRESS,
                    LanWebCloudRelayProtocol.LOOPBACK_PORT,
                ).also { it.start(SOCKET_READ_TIMEOUT_MILLIS, false) }
                cloudRelayStateJob = runtimeScope.launch {
                    cloudRelayManager.state.collect { relay ->
                        mutableState.value = mutableState.value.withCloudRelay(relay)
                    }
                }
                cloudRelayManager.start(LanWebCloudRelayProtocol.LOOPBACK_PORT).getOrThrow()
            }
            stateRelayJob = runtimeScope.launch {
                chatBridge.state.collect {
                    eventHub.publish("session_state", json.encodeToString(chatBridge.liveState()))
                }
            }
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
            mutableState.value = mutableState.value.copy(
                running = true,
                starting = false,
                address = bindAddress,
                clients = accessStore.clients(),
                error = null
            ).withCloudRelay(cloudRelayManager.state.value)
        }.onFailure { error ->
            lanServer?.stop()
            lanServer = null
            relayLoopbackServer?.stop()
            relayLoopbackServer = null
            cloudRelayManager.stop()
            cloudRelayStateJob?.cancel()
            cloudRelayStateJob = null
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
        stateRelayJob?.cancel()
        stateRelayJob = null
        workspaceRelayJob?.cancel()
        workspaceRelayJob = null
        desktopAgentRelayJob?.cancel()
        desktopAgentRelayJob = null
        cloudRelayStateJob?.cancel()
        cloudRelayStateJob = null
        workspaceStatusJob?.cancel()
        workspaceStatusJob = null
        cloudRelayManager.stop()
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
        ).withCloudRelay(cloudRelayManager.state.value)
    }

    fun configureCloudRelay(enabled: Boolean, relayUrl: String): Result<Unit> =
        cloudRelayManager.configure(enabled, relayUrl).map { relay ->
            mutableState.value = mutableState.value.withCloudRelay(relay)
        }.onFailure { error ->
            mutableState.value = mutableState.value.copy(error = safeError(error.message ?: "无法保存云中继配置"))
        }

    fun regenerateCloudRelayCode(): Result<String> =
        cloudRelayManager.regenerate().map { relay ->
            mutableState.value = mutableState.value.withCloudRelay(relay)
            requireNotNull(relay.shareCode)
        }.onFailure { error ->
            mutableState.value = mutableState.value.copy(error = safeError(error.message ?: "无法生成云中继连接码"))
        }

    fun beginPairing(): Result<LanWebPairingCode> = runCatching {
        require(state.value.running) { "请先启动电脑节点服务" }
        val code = accessStore.beginPairing()
        mutableState.value = mutableState.value.copy(
            pairingCode = code.value,
            pairingExpiresAt = code.expiresAt,
            error = null
        )
        pairingExpiryJob?.cancel()
        pairingExpiryJob = runtimeScope.launch {
            delay((code.expiresAt - System.currentTimeMillis()).coerceAtLeast(0L))
            if (!accessStore.isPairingAvailable()) clearPairingDisplay()
        }
        code
    }.onFailure { error ->
        mutableState.value = mutableState.value.copy(error = safeError(error.message ?: "无法生成配对码"))
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
        mutableState.value = mutableState.value.copy(clients = accessStore.clients())
    }

    private fun createServer(bindAddress: String, port: Int) = LanWebServer(
        context = context,
        bindAddress = bindAddress,
        accessStore = accessStore,
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
        mutableState.value = mutableState.value.copy(pairingCode = null, pairingExpiresAt = null)
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

    private companion object {
        const val SOCKET_READ_TIMEOUT_MILLIS = 10_000
        const val LOOPBACK_ADDRESS = "127.0.0.1"
    }
}

private fun LanWebServiceState.withCloudRelay(relay: LanWebCloudRelayState): LanWebServiceState = copy(
    cloudRelayConfigured = relay.configured,
    cloudRelayEnabled = relay.enabled,
    cloudRelayUrl = relay.relayUrl,
    cloudRelayRoomId = relay.roomId,
    cloudRelayShareCode = relay.shareCode,
    cloudRelayRunning = relay.running,
    cloudRelayConnecting = relay.connecting,
    cloudRelayConnected = relay.connected,
    cloudRelayStatus = relay.status,
    cloudRelayError = relay.error,
)
