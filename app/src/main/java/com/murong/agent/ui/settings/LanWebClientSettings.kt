package com.murong.agent.ui.settings

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.murong.agent.lan.LanWebContract
import com.murong.agent.lan.DesktopSessionHandoffCoordinator
import com.murong.agent.lan.DesktopSessionHandoffState
import com.murong.agent.lan.LanWebDesktopAgentBridge
import com.murong.agent.lan.LanWebDesktopAgentAskAnswer
import com.murong.agent.lan.LanWebDesktopAgentStatusResponse
import com.murong.agent.lan.LanWebForegroundService
import com.murong.agent.lan.LanWebRuntime
import com.murong.agent.lan.LanWebServiceState
import com.murong.agent.lan.desktopPlatformLabel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class LanWebSettingsViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val runtime: LanWebRuntime,
    private val desktopAgentBridge: LanWebDesktopAgentBridge,
    private val desktopSessionHandoffCoordinator: DesktopSessionHandoffCoordinator
) : ViewModel() {
    val state: StateFlow<LanWebServiceState> = runtime.state
    val desktopAgentState: StateFlow<LanWebDesktopAgentStatusResponse> = desktopAgentBridge.state
    val desktopHandoffState: StateFlow<DesktopSessionHandoffState> = desktopSessionHandoffCoordinator.state
    private val mutableDesktopAgentError = MutableStateFlow<String?>(null)
    val desktopAgentError: StateFlow<String?> = mutableDesktopAgentError.asStateFlow()

    fun startService() = LanWebForegroundService.requestStart(context)

    fun stopService() = LanWebForegroundService.requestStop(context)

    fun beginPairing() {
        runtime.beginPairing()
    }

    fun revokeClient(clientId: String) {
        runtime.revokeClient(clientId)
    }

    fun revokeAllClients() {
        runtime.revokeAllClients()
    }

    fun reportPermissionDenied() = runtime.reportPermissionDenied()

    fun clearError() = runtime.clearError()

    fun configureCloudRelay(enabled: Boolean, relayUrl: String) {
        runtime.configureCloudRelay(enabled, relayUrl)
    }

    fun regenerateCloudRelayCode() {
        runtime.regenerateCloudRelayCode()
    }

    fun refreshDesktopAgent() = runDesktopAgentCommand { desktopAgentBridge.command("refresh") }

    fun openDesktopSession(sessionId: String) {
        val selectedFromMirror = desktopAgentBridge.selectCachedSession(sessionId)
        if (desktopAgentBridge.state.value.connected) {
            runDesktopAgentCommand { desktopAgentBridge.command("get_session", sessionId = sessionId) }
        } else if (!selectedFromMirror) {
            mutableDesktopAgentError.value = "这项电脑任务还没有同步到手机，连接 Murong Desktop 后再打开"
        }
    }

    fun sendDesktopMessage(sessionId: String, content: String) =
        runDesktopAgentCommand { desktopAgentBridge.command("send_message", sessionId = sessionId, content = content) }

    fun cancelDesktopRun(sessionId: String) =
        runDesktopAgentCommand { desktopAgentBridge.command("cancel", sessionId = sessionId) }

    fun decideDesktopApproval(sessionId: String, approvalId: String, approve: Boolean) =
        runDesktopAgentCommand {
            desktopAgentBridge.command(
                operation = "approval",
                sessionId = sessionId,
                approvalId = approvalId,
                approve = approve
            )
        }

    fun answerDesktopQuestion(
        sessionId: String,
        askId: String,
        answers: List<LanWebDesktopAgentAskAnswer>,
        dismiss: Boolean
    ) = runDesktopAgentCommand {
        desktopAgentBridge.command(
            operation = "ask",
            sessionId = sessionId,
            askId = askId,
            askAnswers = answers,
            dismissAsk = dismiss
        )
    }

    fun takeOverDesktopSession(sessionId: String) = runDesktopHandoffOperation {
        desktopSessionHandoffCoordinator.takeOver(sessionId).map { Unit }
    }

    fun returnDesktopSession(sessionId: String) = runDesktopHandoffOperation {
        desktopSessionHandoffCoordinator.returnToDesktop(sessionId)
    }

    fun abandonDesktopSession(sessionId: String) = runDesktopHandoffOperation {
        desktopSessionHandoffCoordinator.abandon(sessionId)
    }

    fun openLocalDesktopHandoff(sessionId: String) {
        desktopSessionHandoffCoordinator.openLocal(sessionId).onFailure { error ->
            mutableDesktopAgentError.value = error.message ?: "无法打开手机接管会话"
        }
    }

    fun clearDesktopAgentError() {
        mutableDesktopAgentError.value = null
    }

    private fun runDesktopAgentCommand(
        command: suspend () -> Result<com.murong.agent.lan.LanWebDesktopAgentCommandResultRequest>
    ) {
        mutableDesktopAgentError.value = null
        viewModelScope.launch {
            command().onFailure { error ->
                mutableDesktopAgentError.value = error.message ?: "桌面任务操作失败"
            }.onSuccess { result ->
                if (!result.success) {
                    mutableDesktopAgentError.value = result.errorMessage ?: "桌面任务操作失败"
                }
            }
        }
    }

    private fun runDesktopHandoffOperation(operation: suspend () -> Result<Unit>) {
        mutableDesktopAgentError.value = null
        viewModelScope.launch {
            operation().onFailure { error ->
                mutableDesktopAgentError.value = error.message ?: "跨端任务接管失败"
            }
        }
    }
}

@Composable
fun LanWebClientSettingsSection(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    viewModel: LanWebSettingsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val desktopAgentState by viewModel.desktopAgentState.collectAsStateWithLifecycle()
    val connectedPlatformLabel = desktopPlatformLabel(
        state.workspacePlatform ?: desktopAgentState.sourcePlatform,
        state.workspaceArchitecture ?: desktopAgentState.sourceArchitecture
    )
    var confirmRevokeAll by remember { mutableStateOf(false) }
    var showDesktopTasks by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var cloudRelayEnabledDraft by remember(state.cloudRelayEnabled) {
        mutableStateOf(state.cloudRelayEnabled)
    }
    var cloudRelayUrlDraft by remember(state.cloudRelayUrl) {
        mutableStateOf(state.cloudRelayUrl)
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.startService() else viewModel.reportPermissionDenied()
    }

    LaunchedEffect(state.pairingExpiresAt) {
        val expiresAt = state.pairingExpiresAt
        while (expiresAt != null && expiresAt > System.currentTimeMillis()) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }

    SettingsExpandableSectionCard(
        title = "电脑节点（手机控制）",
        subtitle = when {
            state.workspaceConnected -> "$connectedPlatformLabel 已连接 · ${state.workspaceLabel.orEmpty()}"
            state.running && state.nodeUrl != null -> "等待 Murong Desktop · ${state.nodeUrl}"
            state.running && state.cloudRelayEnabled -> "等待 Murong Desktop · ${state.cloudRelayStatus}"
            state.running -> "电脑节点服务已启动"
            state.starting -> "正在启动…"
            else -> "默认关闭；让手机 Agent 使用电脑文件和终端"
        },
        expanded = expanded,
        onExpandedChange = onExpandedChange
    ) {
                Text(
                    text = when {
                        state.workspaceConnected -> "$connectedPlatformLabel 已连接 · ${state.workspaceLabel.orEmpty()}"
                        state.running && state.nodeUrl != null -> "手机局域网节点地址：${state.nodeUrl}"
                        state.running && state.cloudRelayEnabled -> "异网节点：${state.cloudRelayStatus}"
                        state.running -> "电脑节点服务正在运行"
                        state.starting -> "正在启动…"
                        else -> "先在手机启动服务，再在电脑运行对应系统的 Murong Desktop。"
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "Windows、macOS 或 Linux 上的 Murong Desktop 都可给手机 Agent 提供电脑文件和所选终端，也可按电脑端开关把桌面任务同步到手机查看或控制。同一 Wi-Fi 或 Tailscale/Headscale 可直接连接；没有私网互通时可使用下面预填的 Murong 官方 WSS 中继，不需要自己准备服务器。高级用户仍可替换为自建地址。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("异网端到端加密中继（可选）", style = MaterialTheme.typography.labelLarge)
                                Text(
                                    "默认使用 Murong 官方地址 wss://murongagent.rl1.cc/relay/v1/connect。手机和电脑都只建立出站连接，中继仅转发 AES-256-GCM 密文；连接码中的端到端密钥只保存在自己的两台设备。也可改为自建 WSS 地址。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = cloudRelayEnabledDraft,
                                onCheckedChange = { cloudRelayEnabledDraft = it },
                                enabled = !state.running
                            )
                        }
                        OutlinedTextField(
                            value = cloudRelayUrlDraft,
                            onValueChange = { cloudRelayUrlDraft = it },
                            label = { Text("中继地址（默认官方，可改为自建）") },
                            placeholder = { Text("wss://murongagent.rl1.cc/relay/v1/connect") },
                            singleLine = true,
                            enabled = !state.running,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            "普通用户保持默认地址即可。自托管时，在公网 Linux 服务器运行 murong-cloud-relay，再用 Caddy/Nginx 提供 WSS；手机和电脑必须填写同一个地址。仅本机联调可用 ws://127.0.0.1:8787/v1/connect，不能用于异网。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            state.cloudRelayStatus,
                            style = MaterialTheme.typography.bodySmall,
                            color = when {
                                state.cloudRelayConnected -> MaterialTheme.colorScheme.primary
                                state.cloudRelayError != null -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                        state.cloudRelayError?.let { relayError ->
                            Text(
                                relayError,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    viewModel.configureCloudRelay(
                                        cloudRelayEnabledDraft,
                                        cloudRelayUrlDraft
                                    )
                                },
                                enabled = !state.running,
                                modifier = Modifier.weight(1f)
                            ) { Text("保存中继设置") }
                            OutlinedButton(
                                onClick = viewModel::regenerateCloudRelayCode,
                                enabled = !state.running,
                                modifier = Modifier.weight(1f)
                            ) { Text(if (state.cloudRelayConfigured) "更换连接码" else "生成连接码") }
                        }
                        state.cloudRelayShareCode?.let { shareCode ->
                            Text(
                                "连接码已就绪 · 房间 ${state.cloudRelayRoomId.take(8)}…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            FilledTonalButton(
                                onClick = { clipboard.setText(AnnotatedString(shareCode)) },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("复制云中继连接码到电脑") }
                        }
                    }
                }
                state.error?.let { error ->
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                if (state.running) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text("电脑端怎么运行", style = MaterialTheme.typography.labelLarge)
                            Text(
                                "1. 下载并启动当前系统与架构对应的 Murong Desktop：Windows 使用 .exe，macOS 使用 .app，Linux 使用发布压缩包。\n" +
                                    "2. 打开主程序的“手机远程节点”；同网或 Tailscale 异网选择直连并填写手机地址。普通异网选择云中继，保持两端相同的 Murong 官方地址并粘贴上面的连接码；自托管时再替换地址。\n" +
                                    "3. 在手机生成一次性配对码，填入电脑后按需要开启文件、终端或桌面任务能力，再点击“启动内置节点”。",
                                style = MaterialTheme.typography.bodySmall
                            )
                            state.nodeUrl?.let { nodeUrl ->
                                Text(
                                    "局域网地址：$nodeUrl",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            if (state.cloudRelayEnabled) {
                                Text(
                                    "异网中继：${state.cloudRelayStatus}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (state.cloudRelayConnected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                            Text(
								"电脑文件能力默认只读；可同时授权当前系统发现的多个终端。桌面聊天默认不共享，需在 Murong Desktop 明确开启。以后启动同一个桌面应用即可复用已加密保存的配对凭据。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    state.nodeUrl?.let { nodeUrl ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilledTonalButton(
                                onClick = { clipboard.setText(AnnotatedString(nodeUrl)) },
                                modifier = Modifier.weight(1f)
                            ) { Text("复制局域网地址") }
                            OutlinedButton(
                                onClick = viewModel::stopService,
                                modifier = Modifier.weight(1f)
                            ) { Text("停止节点服务") }
                        }
                    } ?: OutlinedButton(
                        onClick = viewModel::stopService,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("停止节点服务") }
                    Button(
                        onClick = viewModel::beginPairing,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (state.pairingCode == null) "生成一次性配对码" else "重新生成配对码") }
                } else {
                    Button(
                        onClick = {
                            if (state.cloudRelayEnabled || hasLanPermission(context)) {
                                viewModel.startService()
                            } else {
                                permissionLauncher.launch(LanWebContract.LOCAL_NETWORK_PERMISSION)
                            }
                        },
                        enabled = !state.starting,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("启动电脑节点服务") }
                }

                if (state.running) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Text("当前 $connectedPlatformLabel", style = MaterialTheme.typography.labelLarge)
                            if (state.workspaceConnected) {
                                Text(state.workspaceLabel.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                                Text(
									"读取：开启 · 写入：${if (state.workspaceWritable) "开启" else "关闭"} · 终端：${state.workspaceTerminals.joinToString { it.label }.ifBlank { "关闭" }}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    "桌面任务：${when {
                                        desktopAgentState.connected -> "实时同步"
                                        desktopAgentState.snapshot != null -> "已保留离线副本"
                                        else -> "未共享"
                                    }} · 手机控制：${if (desktopAgentState.controlAllowed) "开启" else "关闭"}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                Text(
                                    "尚未连接。请启动当前系统对应的 Murong Desktop，在“手机远程节点”中填写手机地址、选择电脑目录并输入配对码；不需要用 Chrome 打开这个地址。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                if (desktopAgentState.snapshot != null) {
                    FilledTonalButton(
                        onClick = { showDesktopTasks = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (desktopAgentState.connected) "打开电脑任务" else "查看电脑任务离线副本")
                    }
                }

                state.pairingCode?.let { code ->
                    val seconds = ((state.pairingExpiresAt.orEmpty() - now) / 1_000L).coerceAtLeast(0L)
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text("一次性配对码", style = MaterialTheme.typography.labelMedium)
                            Text(code, style = MaterialTheme.typography.headlineSmall)
                            Text(
                                "约 ${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')} 后过期，成功使用一次后立即失效。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            TextButton(
                                onClick = { clipboard.setText(AnnotatedString(code)) }
                            ) { Text("复制配对码") }
                        }
                    }
                }

                Text("已配对电脑 / 兼容客户端 ${state.clients.size}/8", style = MaterialTheme.typography.labelLarge)
                if (state.clients.isEmpty()) {
                    Text(
                        "暂无已配对电脑。服务停止不会删除节点凭据；可在这里随时撤销。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    state.clients.forEach { client ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(client.name, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "ID ${client.id.take(8)} · 最近使用 ${client.lastSeenAt?.let(::formatRelativeTime) ?: "从未"}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    if (client.secureSync) {
                                        "账号同步：端到端加密已就绪"
                                    } else {
                                        "账号同步：旧配对不支持，请撤销后重新配对"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (client.secureSync) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.error
                                    }
                                )
                            }
                            TextButton(onClick = { viewModel.revokeClient(client.id) }) { Text("撤销") }
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    OutlinedButton(
                        onClick = { confirmRevokeAll = true },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("撤销全部客户端") }
                }
    }

    if (confirmRevokeAll) {
        AlertDialog(
            onDismissRequest = { confirmRevokeAll = false },
            title = { Text("撤销全部客户端？") },
            text = { Text("所有 Murong Desktop 和旧网页客户端保存的令牌会立即失效，需要重新配对。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRevokeAll = false
                    viewModel.revokeAllClients()
                }) { Text("全部撤销") }
            },
            dismissButton = {
                TextButton(onClick = { confirmRevokeAll = false }) { Text("取消") }
            }
        )
    }

    if (showDesktopTasks) {
        DesktopAgentTasksDialog(
            viewModel = viewModel,
            onDismiss = {
                showDesktopTasks = false
                viewModel.clearDesktopAgentError()
            }
        )
    }
}

private fun hasLanPermission(context: Context): Boolean = ContextCompat.checkSelfPermission(
    context,
    LanWebContract.LOCAL_NETWORK_PERMISSION
) == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < 37

private fun Long?.orEmpty(): Long = this ?: 0L

private fun formatRelativeTime(timestamp: Long): String {
    val delta = (System.currentTimeMillis() - timestamp).coerceAtLeast(0L)
    return when {
        delta < 60_000L -> "刚刚"
        delta < 3_600_000L -> "${delta / 60_000L} 分钟前"
        delta < 86_400_000L -> "${delta / 3_600_000L} 小时前"
        else -> "${delta / 86_400_000L} 天前"
    }
}
