package com.murong.agent.ui.settings

import android.Manifest
import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.murong.agent.core.tool.AndroidGuiAccessibilityService
import com.murong.agent.core.tool.AndroidGuiAccessibilityAccess
import com.murong.agent.core.tool.AndroidExecutionMode
import com.murong.agent.core.tool.RootAccessibilityEnableResult
import com.murong.agent.ui.MurongGlassSurface
import com.murong.agent.shizuku.ShizukuAvailability
import com.murong.agent.shizuku.ShizukuSystemAccess
import com.murong.agent.ui.assistant.isMurongVoiceInteractionServiceActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * A single, transparent place for every device capability the agent can actually use.
 *
 * This deliberately does not pretend that Root replaces Android permissions. Root is an
 * optional execution capability; accessibility, microphone, overlay, notifications, storage
 * and the system-assistant role each remain explicitly controlled by Android and by the user.
 */
@Composable
internal fun DevicePermissionCenter(
    rootStatus: Boolean?,
    isCheckingRoot: Boolean,
    onCheckRoot: () -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var accessibilityEnabled by remember { mutableStateOf(isMurongAccessibilityEnabled(context)) }
    var isEnablingAccessibility by remember { mutableStateOf(false) }
    var accessibilityMessage by remember { mutableStateOf<String?>(null) }
    var overlayEnabled by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var microphoneGranted by remember { mutableStateOf(hasPermission(context, Manifest.permission.RECORD_AUDIO)) }
    var notificationsGranted by remember {
        mutableStateOf(hasPermission(context, Manifest.permission.POST_NOTIFICATIONS))
    }
    var promotedNotificationsAllowed by remember {
        mutableStateOf(canPostPromotedNotifications(context))
    }
    var allFilesGranted by remember { mutableStateOf(MurongExternalStorageAccess.hasAccess(context)) }
    var assistantRoleHeld by remember { mutableStateOf(isMurongAssistantRoleHeld(context)) }
    var assistantServiceActive by remember {
        mutableStateOf(isMurongVoiceInteractionServiceActive(context))
    }
    var assistStructureEnabled by remember { mutableStateOf(isAssistStructureEnabled(context)) }
    var assistScreenshotEnabled by remember { mutableStateOf(isAssistScreenshotEnabled(context)) }
    var assistDisclosureEnabled by remember { mutableStateOf(isAssistDisclosureEnabled(context)) }
    var selectedExecutionMode by remember {
        mutableStateOf(DeviceExecutionProfilePreferences.selected(context))
    }
    val shizukuState by ShizukuSystemAccess.state.collectAsState()

    fun refresh() {
        accessibilityEnabled = isMurongAccessibilityEnabled(context)
        overlayEnabled = Settings.canDrawOverlays(context)
        microphoneGranted = hasPermission(context, Manifest.permission.RECORD_AUDIO)
        notificationsGranted = hasPermission(context, Manifest.permission.POST_NOTIFICATIONS)
        promotedNotificationsAllowed = canPostPromotedNotifications(context)
        allFilesGranted = MurongExternalStorageAccess.hasAccess(context)
        assistantRoleHeld = isMurongAssistantRoleHeld(context)
        assistantServiceActive = isMurongVoiceInteractionServiceActive(context)
        assistStructureEnabled = isAssistStructureEnabled(context)
        assistScreenshotEnabled = isAssistScreenshotEnabled(context)
        assistDisclosureEnabled = isAssistDisclosureEnabled(context)
    }

    val settingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { refresh() }
    val microphoneLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { microphoneGranted = it }
    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { notificationsGranted = it }

    LaunchedEffect(Unit) { refresh() }

    fun enableAccessibilityWithRoot() {
        if (isEnablingAccessibility) return
        isEnablingAccessibility = true
        accessibilityMessage = null
        coroutineScope.launch {
            val result = try {
                AndroidGuiAccessibilityAccess.enableWithRoot(context.applicationContext)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                RootAccessibilityEnableResult(
                    success = false,
                    serviceConnected = false,
                    message = "Root 启用失败：${error.message ?: error.javaClass.simpleName}",
                )
            } finally {
                isEnablingAccessibility = false
            }
            accessibilityMessage = result.message
            refresh()
        }
    }

    Text(
        text = "设备权限",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onBackground
    )
    Text(
        text = "Root 不是万能授权。以下能力均由 Android 单独管理，可按需开启。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    ExecutionModeChooser(
        selectedMode = selectedExecutionMode,
        onSelect = { mode ->
            DeviceExecutionProfilePreferences.select(context, mode)
            selectedExecutionMode = mode
        }
    )

    DevicePermissionCard(
        title = "Root 执行",
        description = when {
            isCheckingRoot -> "正在检测 Root 可用性…"
            rootStatus == true -> "已可用；仅在需要系统级 Shell 操作时使用"
            rootStatus == false -> "未授权或不可用；普通聊天、文件和无障碍能力不受影响"
            else -> "尚未检测"
        },
        granted = rootStatus == true,
        actionText = "重新检测",
        onAction = onCheckRoot,
        busy = isCheckingRoot
    )
    DevicePermissionCard(
        title = "Shizuku / Sui 系统通道",
        description = when (shizukuState.availability) {
            ShizukuAvailability.READY -> "${shizukuState.message}；可在 Shizuku 模式执行系统 Shell"
            ShizukuAvailability.NEEDS_PERMISSION -> "${shizukuState.message}；点击后授予 Murong 权限"
            ShizukuAvailability.DENIED -> "${shizukuState.message}；请在 Shizuku 中重新允许 Murong"
            ShizukuAvailability.UNSUPPORTED -> shizukuState.message
            ShizukuAvailability.NOT_RUNNING -> "未检测到 Shizuku / Sui；启动后可用作非 Root 的系统执行通道"
        },
        granted = shizukuState.availability == ShizukuAvailability.READY,
        actionText = when (shizukuState.availability) {
            ShizukuAvailability.READY -> "刷新状态"
            ShizukuAvailability.NEEDS_PERMISSION -> "授权 Shizuku"
            else -> "启动或管理 Shizuku"
        },
        onAction = { ShizukuSystemAccess.requestPermissionOrOpen(context) }
    )
    DevicePermissionCard(
        title = "无障碍自动启用",
        description = if (accessibilityEnabled) {
            "已启用；手机操作任务可读取语义树并执行界面操作"
        } else {
            if (rootStatus == true) {
                "未启用；手机操作任务需要读屏时会通过 Root 自动启用，不会覆盖其他服务"
            } else {
                "未启用且 Root 不可用；请在系统详情页手动启用"
            }
        },
        granted = accessibilityEnabled,
        actionText = when {
            accessibilityEnabled -> "查看设置"
            rootStatus == true -> "立即用 Root 自动启用"
            else -> "手动启用无障碍"
        },
        onAction = {
            if (!accessibilityEnabled && rootStatus == true) {
                enableAccessibilityWithRoot()
            } else {
                settingsLauncher.launch(murongAccessibilitySettingsIntent(context))
            }
        },
        busy = isEnablingAccessibility,
    )
    accessibilityMessage?.let { message ->
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = if (accessibilityEnabled) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.error
            },
        )
    }
    DevicePermissionCard(
        title = "麦克风",
        description = if (microphoneGranted) {
            "已授权；可使用系统或离线语音识别"
        } else {
            "未授权；语音助手和聊天语音输入无法开始识别"
        },
        granted = microphoneGranted,
        actionText = if (microphoneGranted) "已授权" else "授权麦克风",
        actionEnabled = !microphoneGranted,
        onAction = { microphoneLauncher.launch(Manifest.permission.RECORD_AUDIO) }
    )
    DevicePermissionCard(
        title = "通知",
        description = if (notificationsGranted) {
            "已授权；可显示下载、任务和语音助手状态"
        } else {
            "未授权；后台任务和下载的状态提示可能看不到"
        },
        granted = notificationsGranted,
        actionText = if (notificationsGranted) "已授权" else "授权通知",
        actionEnabled = !notificationsGranted,
        onAction = { notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
        DevicePermissionCard(
            title = "实时活动 / 流体云",
            description = if (promotedNotificationsAllowed) {
                "系统已允许提升持续通知；执行中的手机任务可显示为状态栏胶囊、实时进度或厂商实时活动"
            } else {
                "任务通知已符合 Android 16 实时活动格式，但还需在系统中允许 Murong 显示提升通知"
            },
            granted = promotedNotificationsAllowed,
            actionText = if (promotedNotificationsAllowed) "管理实时活动" else "开启实时活动",
            onAction = {
                settingsLauncher.launch(promotedNotificationSettingsIntent(context))
            },
        )
    }
    DevicePermissionCard(
        title = "悬浮窗",
        description = if (overlayEnabled) {
            "已授权；可显示项目终端和后续的助手悬浮入口"
        } else {
            "未授权；需要悬浮显示的功能会在前台页面中打开"
        },
        granted = overlayEnabled,
        actionText = if (overlayEnabled) "查看设置" else "授权悬浮窗",
        onAction = {
            settingsLauncher.launch(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                )
            )
        }
    )
    DevicePermissionCard(
        title = "全部文件访问",
        description = if (allFilesGranted) {
            "已授权；终端、项目和 Agent 可访问共享存储中的普通文件"
        } else {
            "未授权；文件工具只能访问用户显式选择的内容"
        },
        granted = allFilesGranted,
        actionText = if (allFilesGranted) "已授权" else "授权文件访问",
        actionEnabled = !allFilesGranted,
        onAction = {
            settingsLauncher.launch(MurongExternalStorageAccess.settingsIntent(context))
        }
    )
    DevicePermissionCard(
        title = "系统语音助手",
        description = when {
            assistantServiceActive ->
                "系统语音交互服务已激活；助理键/手势会在当前界面弹出 Murong"
            assistantRoleHeld ->
                "系统已选中 Murong，但语音交互服务尚未激活；请重新选择一次默认数字助理"
            else ->
                "设为默认数字助理后，系统助理键/手势会在当前界面弹出 Murong"
        },
        granted = assistantServiceActive,
        actionText = if (assistantServiceActive) "管理默认助手" else "设为默认助手",
        onAction = {
            settingsLauncher.launch(
                defaultAssistantSettingsIntent(
                    context = context,
                    roleHeld = assistantRoleHeld,
                )
            )
        }
    )
    DevicePermissionCard(
        title = "助手屏幕内容",
        description = buildString {
            append(if (assistStructureEnabled) "屏幕文字：允许" else "屏幕文字：关闭")
            append(" · ")
            append(if (assistScreenshotEnabled) "截图：允许" else "截图：关闭")
            append(" · ")
            append(if (assistDisclosureEnabled) "调用闪烁提示：开启" else "调用闪烁提示：关闭")
            append("。这些开关由 Android 管理，Murong 只在本次助手请求中读取。")
        },
        granted = assistStructureEnabled && assistScreenshotEnabled && assistDisclosureEnabled,
        actionText = "管理文字、截图与闪烁提示",
        onAction = {
            settingsLauncher.launch(defaultAssistantSettingsIntent(context, roleHeld = true))
        }
    )
}

@Composable
private fun ExecutionModeChooser(
    selectedMode: AndroidExecutionMode,
    onSelect: (AndroidExecutionMode) -> Unit,
) {
    MurongGlassSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        contentPadding = PaddingValues(16.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("系统执行分级", style = MaterialTheme.typography.bodyLarge)
            Text(
                "为 Shell / Android 系统命令选择执行通道；聊天、文件和普通应用能力始终可用。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            AndroidExecutionMode.entries.forEach { mode ->
                val selected = mode == selectedMode
                FilledTonalButton(
                    onClick = { onSelect(mode) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(if (selected) "${mode.label}（当前）" else mode.label)
                        Text(
                            executionModeDescription(mode),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }
    }
}

private fun executionModeDescription(mode: AndroidExecutionMode): String = when (mode) {
    AndroidExecutionMode.AUTO -> "推荐：优先 Root，其次已授权 Shizuku；均不可用时回退标准能力。"
    AndroidExecutionMode.STANDARD -> "仅普通应用权限；不执行系统 Shell。"
    AndroidExecutionMode.ACCESSIBILITY -> "标准能力加无障碍界面操作；不把无障碍误当作系统 Shell。"
    AndroidExecutionMode.SHIZUKU -> "只经已授权 Shizuku / Sui 执行系统命令，不回退到 Root。"
    AndroidExecutionMode.ROOT -> "只经 Root 执行系统命令，不回退到 Shizuku。"
}

@Composable
private fun DevicePermissionCard(
    title: String,
    description: String,
    granted: Boolean,
    actionText: String,
    onAction: () -> Unit,
    actionEnabled: Boolean = true,
    busy: Boolean = false,
) {
    MurongGlassSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        contentPadding = PaddingValues(16.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        description,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (granted) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                }
            }
            FilledTonalButton(
                onClick = onAction,
                enabled = actionEnabled && !busy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(actionText)
            }
        }
    }
}

private fun hasPermission(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == android.content.pm.PackageManager.PERMISSION_GRANTED

private fun canPostPromotedNotifications(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) return false
    return context.getSystemService(NotificationManager::class.java)
        ?.canPostPromotedNotifications() == true
}

private fun promotedNotificationSettingsIntent(context: Context): Intent {
    val promotionSettings = Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    if (promotionSettings.resolveActivity(context.packageManager) != null) {
        return promotionSettings
    }
    return Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
}

private fun isMurongAccessibilityEnabled(context: Context): Boolean {
    val expected = ComponentName(context, AndroidGuiAccessibilityService::class.java)
    val services = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ).orEmpty()
    return services.split(':')
        .mapNotNull(ComponentName::unflattenFromString)
        .any { it == expected }
}

private fun murongAccessibilitySettingsIntent(context: Context): Intent {
    val detailsIntent = Intent(AndroidGuiAccessibilityAccess.DETAILS_SETTINGS_ACTION)
        .putExtra(
            Intent.EXTRA_COMPONENT_NAME,
            AndroidGuiAccessibilityAccess.serviceComponentName(context.applicationContext),
        )
    return detailsIntent.takeIf { it.resolveActivity(context.packageManager) != null }
        ?: Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
}

private fun isMurongAssistantRoleHeld(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
    val roleManager = context.getSystemService(RoleManager::class.java) ?: return false
    return roleManager.isRoleAvailable(RoleManager.ROLE_ASSISTANT) &&
        roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT)
}

private fun defaultAssistantSettingsIntent(context: Context, roleHeld: Boolean): Intent {
    if (!roleHeld && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val roleManager = context.getSystemService(RoleManager::class.java)
        if (roleManager?.isRoleAvailable(RoleManager.ROLE_ASSISTANT) == true) {
            return roleManager.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT)
        }
    }
    val candidates = listOf(
        Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
        Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
        Intent(Settings.ACTION_SETTINGS),
    )
    return candidates.firstOrNull { it.resolveActivity(context.packageManager) != null }
        ?: Intent(Settings.ACTION_SETTINGS)
}

private fun isAssistStructureEnabled(context: Context): Boolean =
    Settings.Secure.getInt(context.contentResolver, "assist_structure_enabled", 1) != 0

private fun isAssistScreenshotEnabled(context: Context): Boolean =
    Settings.Secure.getInt(context.contentResolver, "assist_screenshot_enabled", 1) != 0

private fun isAssistDisclosureEnabled(context: Context): Boolean =
    Settings.Secure.getInt(context.contentResolver, "assist_disclosure_enabled", 1) != 0
