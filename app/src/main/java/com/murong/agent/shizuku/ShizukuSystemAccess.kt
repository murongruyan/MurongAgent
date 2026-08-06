package com.murong.agent.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import com.murong.agent.common.shell.KeepShellPublic
import com.murong.agent.core.tool.AndroidSystemExecution
import com.murong.agent.core.tool.ExternalSystemCommandBridge
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku

enum class ShizukuAvailability {
    NOT_RUNNING,
    NEEDS_PERMISSION,
    READY,
    DENIED,
    UNSUPPORTED
}

data class ShizukuAccessState(
    val availability: ShizukuAvailability = ShizukuAvailability.NOT_RUNNING,
    val uid: Int? = null,
    val message: String = "未检测到运行中的 Shizuku 或 Sui"
)

/**
 * Official Shizuku/Sui bridge. The app never assumes that ADB-shell identity equals Root: the
 * current UID is reported in the UI and commands still receive Android's normal SELinux limits.
 */
object ShizukuSystemAccess : ExternalSystemCommandBridge {
    private const val TAG = "MurongShizuku"
    private const val REQUEST_CODE = 0x4D52
    private const val USER_SERVICE_TAG = "murong-system-command-v3"

    private val _state = MutableStateFlow(ShizukuAccessState())
    val state: StateFlow<ShizukuAccessState> = _state.asStateFlow()

    @Volatile private var appContext: Context? = null
    @Volatile private var initialized = false
    @Volatile private var commandService: IShizukuCommandService? = null
    @Volatile private var rootCommandService: IShizukuCommandService? = null
    @Volatile private var rootProcess: RootAgentDisplayAppProcess? = null
    @Volatile private var rootRetryAfterElapsedMs: Long = 0L
    private val rootClient = object : IRootAgentDisplayClient.Stub() {
        override fun ping() = Unit
    }
    @Volatile private var agentDisplayOwnerService: IShizukuCommandService? = null
    @Volatile private var serviceReady: CountDownLatch? = null
    @Volatile private var isBinding = false

    override val label: String = "Shizuku"

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            commandService = IShizukuCommandService.Stub.asInterface(service)
            isBinding = false
            serviceReady?.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            commandService = null
            isBinding = false
            refresh()
        }
    }

    fun initialize(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            appContext = context.applicationContext
            initialized = true
            AndroidSystemExecution.installExternalBridge(this)
            runCatching {
                Shizuku.addBinderReceivedListener { refresh() }
                Shizuku.addBinderDeadListener {
                    commandService = null
                    refresh()
                }
                Shizuku.addRequestPermissionResultListener { requestCode, grantResult ->
                    if (requestCode == REQUEST_CODE) refresh(grantResult == PackageManager.PERMISSION_GRANTED)
                }
            }
            refresh()
            Thread(
                {
                    if (KeepShellPublic.checkRoot()) {
                        obtainRootCommandService(maxAttempts = ROOT_COLD_START_ATTEMPTS)
                    }
                },
                "MurongRootDisplayWarmup",
            ).apply { isDaemon = true }.start()
        }
    }

    fun refresh(permissionGrantedOverride: Boolean? = null) {
        val snapshot = runCatching {
            if (!Shizuku.pingBinder()) {
                ShizukuAccessState()
            } else if (Shizuku.isPreV11()) {
                ShizukuAccessState(
                    availability = ShizukuAvailability.UNSUPPORTED,
                    message = "Shizuku 版本过旧，需要 API v11 或更高版本"
                )
            } else {
                val granted = permissionGrantedOverride ?: (
                    Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
                )
                if (!granted) {
                    ShizukuAccessState(
                        availability = if (Shizuku.shouldShowRequestPermissionRationale()) {
                            ShizukuAvailability.DENIED
                        } else {
                            ShizukuAvailability.NEEDS_PERMISSION
                        },
                        message = "Shizuku 正在运行，尚未向 Murong 授权"
                    )
                } else {
                    val uid = Shizuku.getUid()
                    ShizukuAccessState(
                        availability = ShizukuAvailability.READY,
                        uid = uid,
                        message = if (uid == 0) "已授权（Root 身份）" else "已授权（ADB Shell 身份，UID $uid）"
                    )
                }
            }
        }.getOrElse {
            ShizukuAccessState(message = "Shizuku 不可用：${it.message.orEmpty()}")
        }
        _state.value = snapshot
    }

    /** Opens Shizuku for startup when absent, otherwise asks its own permission dialog. */
    fun requestPermissionOrOpen(context: Context) {
        initialize(context)
        refresh()
        when (_state.value.availability) {
            ShizukuAvailability.READY -> Unit
            ShizukuAvailability.NEEDS_PERMISSION -> runCatching { Shizuku.requestPermission(REQUEST_CODE) }
                .onFailure { refresh() }
            ShizukuAvailability.DENIED -> openShizuku(context)
            ShizukuAvailability.NOT_RUNNING,
            ShizukuAvailability.UNSUPPORTED -> openShizuku(context)
        }
    }

    override fun isAvailable(): Boolean = _state.value.availability == ShizukuAvailability.READY

    /** Agent Display can run from a direct Root app_process even when Shizuku is absent. */
    internal fun isAgentDisplayAvailable(): Boolean =
        rootCommandService?.asBinder()?.isBinderAlive == true ||
            KeepShellPublic.checkRoot() ||
            isAvailable()

    override fun execute(command: String, timeoutSeconds: Int): String {
        if (!isAvailable()) return "Error: ${_state.value.message}"
        val service = obtainCommandService() ?: return "Error: Shizuku 用户服务连接失败。"
        return runCatching { service.execute(command, timeoutSeconds.coerceIn(1, 120)) }
            .getOrElse { error ->
                commandService = null
                "Error: Shizuku 执行失败：${error.message.orEmpty()}"
            }
    }

    internal fun agentDisplayService(): IShizukuCommandService? {
        agentDisplayOwnerService?.let { owner ->
            if (owner.asBinder().isBinderAlive) return owner
            agentDisplayOwnerService = null
        }
        // A rooted device does not need the Shizuku manager. app_process gives the same user
        // service class a privileged Android context and returns its binder directly to Murong.
        obtainRootCommandService(maxAttempts = ROOT_COLD_START_ATTEMPTS)?.let { return it }

        // Shizuku publishes its binder asynchronously during a cold app start. A Phone Agent
        // request can arrive before the binder-received listener refreshes our cached state, so
        // always take a fresh snapshot at the actual point of use instead of trusting startup data.
        refresh()
        if (!isAvailable()) {
            Log.w(TAG, "Agent Display unavailable: ${_state.value}")
            return null
        }
        return obtainCommandService()
    }

    /**
     * Returns only an already-connected display broker. Cancellation and teardown must never
     * cold-start a new Root/Shizuku process just to discover that there is no display to close.
     */
    internal fun connectedAgentDisplayService(): IShizukuCommandService? {
        agentDisplayOwnerService?.let { owner ->
            if (owner.asBinder().isBinderAlive) return owner
            agentDisplayOwnerService = null
        }
        rootCommandService?.let { service ->
            if (service.asBinder().isBinderAlive) return service
            rootCommandService = null
        }
        commandService?.let { service ->
            if (service.asBinder().isBinderAlive) return service
            commandService = null
        }
        return null
    }

    internal fun rememberAgentDisplayOwner(service: IShizukuCommandService) {
        agentDisplayOwnerService = service
    }

    internal fun clearAgentDisplayOwner(service: IShizukuCommandService) {
        if (agentDisplayOwnerService?.asBinder() === service.asBinder()) {
            agentDisplayOwnerService = null
        }
    }

    private fun obtainRootCommandService(maxAttempts: Int): IShizukuCommandService? {
        rootCommandService?.let { service ->
            if (service.asBinder().isBinderAlive) return service
            rootCommandService = null
            rootProcess?.close()
            rootProcess = null
        }
        val context = appContext ?: return null
        if (!KeepShellPublic.checkRoot()) return null
        if (android.os.SystemClock.elapsedRealtime() < rootRetryAfterElapsedMs) return null
        return synchronized(this) {
            rootCommandService?.let { service ->
                if (service.asBinder().isBinderAlive) return@synchronized service
            }
            // A request may have entered this method while the startup warm-up owned the lock.
            // Re-check after acquiring it so a failed warm-up cannot immediately trigger a third
            // Root launch and keep a BroadcastReceiver alive long enough to cause an ANR.
            if (android.os.SystemClock.elapsedRealtime() < rootRetryAfterElapsedMs) {
                return@synchronized null
            }
            var lastError: Throwable? = null
            repeat(maxAttempts.coerceIn(1, ROOT_COLD_START_ATTEMPTS)) { index ->
                val connected = runCatching {
                    val process = RootAgentDisplayAppProcess(
                        binderTimeoutSeconds = ROOT_COLD_START_TIMEOUT_SECONDS,
                    )
                    val service = process.start(context)
                        ?: error("Root Agent Display broker timed out")
                    service.registerAgentDisplayClient(rootClient)
                    check(service.remoteUid() == 0) { "Root Agent Display returned UID ${service.remoteUid()}" }
                    rootProcess = process
                    rootCommandService = service
                    rootRetryAfterElapsedMs = 0L
                    Log.i(
                        TAG,
                        "Agent Display connected through direct Root app_process " +
                            "(attempt ${index + 1})",
                    )
                    service
                }.onFailure { error ->
                    lastError = error
                    rootCommandService = null
                    rootProcess = null
                    Log.w(
                        TAG,
                        "Direct Root Agent Display attempt ${index + 1} failed: ${error.message}",
                    )
                }.getOrNull()
                if (connected != null) return@synchronized connected
            }
            rootRetryAfterElapsedMs = android.os.SystemClock.elapsedRealtime() + ROOT_RETRY_COOLDOWN_MS
            Log.w(TAG, "Direct Root Agent Display unavailable; trying Shizuku", lastError)
            null
        }
    }

    private fun obtainCommandService(): IShizukuCommandService? {
        commandService?.let { return it }
        val context = appContext ?: return null
        val latch = synchronized(this) {
            commandService?.let { return it }
            serviceReady = CountDownLatch(1)
            if (!isBinding) {
                isBinding = true
                runCatching {
                    Shizuku.bindUserService(
                        Shizuku.UserServiceArgs(
                            ComponentName(context, ShizukuCommandUserService::class.java)
                        ).processNameSuffix("agentdisplay")
                            .tag(USER_SERVICE_TAG)
                            .version(3)
                            .daemon(false),
                        serviceConnection
                    )
                }.onFailure {
                    Log.e(TAG, "Unable to dispatch Shizuku user service bind", it)
                    isBinding = false
                    serviceReady?.countDown()
                }
            }
            serviceReady
        }
        val connected = latch?.await(20, TimeUnit.SECONDS) == true
        if (!connected || commandService == null) {
            Log.w(TAG, "Shizuku user service bind timed out or returned no binder")
        }
        return commandService
    }

    private fun openShizuku(context: Context) {
        val launch = context.packageManager
            .getLaunchIntentForPackage("moe.shizuku.privileged.api")
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (launch != null) runCatching { context.startActivity(launch) }
    }

    // The direct broker normally responds in a few seconds; keep a conservative allowance for
    // Android's first class loading pass immediately after an APK replacement.
    private const val ROOT_COLD_START_ATTEMPTS = 1
    private const val ROOT_COLD_START_TIMEOUT_SECONDS = 20L
    private const val ROOT_RETRY_COOLDOWN_MS = 60_000L
}
