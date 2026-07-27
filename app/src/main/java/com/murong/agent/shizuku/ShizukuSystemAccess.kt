package com.murong.agent.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
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
    private const val REQUEST_CODE = 0x4D52
    private const val USER_SERVICE_TAG = "murong-system-command-v1"

    private val _state = MutableStateFlow(ShizukuAccessState())
    val state: StateFlow<ShizukuAccessState> = _state.asStateFlow()

    @Volatile private var appContext: Context? = null
    @Volatile private var initialized = false
    @Volatile private var commandService: IShizukuCommandService? = null
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

    override fun execute(command: String, timeoutSeconds: Int): String {
        if (!isAvailable()) return "Error: ${_state.value.message}"
        val service = obtainCommandService() ?: return "Error: Shizuku 用户服务连接失败。"
        return runCatching { service.execute(command, timeoutSeconds.coerceIn(1, 120)) }
            .getOrElse { error ->
                commandService = null
                "Error: Shizuku 执行失败：${error.message.orEmpty()}"
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
                        ).tag(USER_SERVICE_TAG).version(1).daemon(false),
                        serviceConnection
                    )
                }.onFailure {
                    isBinding = false
                    serviceReady?.countDown()
                }
            }
            serviceReady
        }
        latch?.await(5, TimeUnit.SECONDS)
        return commandService
    }

    private fun openShizuku(context: Context) {
        val launch = context.packageManager
            .getLaunchIntentForPackage("moe.shizuku.privileged.api")
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (launch != null) runCatching { context.startActivity(launch) }
    }
}
