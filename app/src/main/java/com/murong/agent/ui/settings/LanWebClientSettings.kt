package com.murong.agent.ui.settings

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
import kotlinx.coroutines.Dispatchers
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

    fun saveAndStartService() = LanWebForegroundService.requestStart(context)

    fun stopService() = LanWebForegroundService.requestStop(context)

    fun beginPairing() {
        runtime.beginPairing()
    }

    fun setSecurityPassword(password: String) {
        viewModelScope.launch(Dispatchers.Default) { runtime.setSecurityPassword(password) }
    }

    fun clearSecurityPassword() {
        viewModelScope.launch(Dispatchers.Default) { runtime.clearSecurityPassword() }
    }

    fun connectDevice(deviceId: String, authMethod: String = "", secret: String = "") {
        viewModelScope.launch {
            runtime.requestDeviceConnection(deviceId, authMethod, secret)
        }
    }

    fun discoverDevices() {
        viewModelScope.launch {
            runtime.discoverEnvironmentDevices()
        }
    }

    fun revokeClient(clientId: String) {
        runtime.revokeClient(clientId)
    }

    fun revokeAllClients() {
        runtime.revokeAllClients()
    }

    fun blockClient(clientId: String) {
        state.value.clients.firstOrNull { it.id == clientId }?.let(runtime::blockClient)
    }

    fun unblockPeer(deviceId: String) {
        runtime.unblockPeer(deviceId)
    }

    fun setDoNotDisturb(enabled: Boolean) {
        runtime.setDoNotDisturb(enabled)
    }

    fun approveConnectionRequest(requestId: String) {
        runtime.approveConnectionRequest(requestId)
    }

    fun rejectConnectionRequest(requestId: String, block: Boolean) {
        runtime.rejectConnectionRequest(requestId, block)
    }

    fun reportPermissionDenied() = runtime.reportPermissionDenied()

    fun clearError() = runtime.clearError()

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
    var securityPasswordDraft by remember { mutableStateOf("") }
    var showSecurityPasswordEditor by remember { mutableStateOf(false) }
    var showRemoteDetails by remember { mutableStateOf(false) }
    var targetDeviceId by remember { mutableStateOf("") }
    var pendingTargetDeviceId by remember { mutableStateOf<String?>(null) }
    var connectAuthMethod by remember { mutableStateOf(com.murong.agent.lan.LanWebTrustSource.TEMPORARY_CODE) }
    var connectSecret by remember { mutableStateOf("") }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        // 公网本机 ID 不依赖局域网权限；授权结果只决定能否同网自动发现。
        viewModel.saveAndStartService()
    }

    LaunchedEffect(state.pairingExpiresAt, state.pairingCooldownUntil) {
        val expiresAt = maxOf(state.pairingExpiresAt ?: 0L, state.pairingCooldownUntil ?: 0L)
        while (expiresAt > System.currentTimeMillis()) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }

    LaunchedEffect(expanded, state.running) {
        if (expanded && state.running) viewModel.discoverDevices()
    }

    SettingsExpandableSectionCard(
        title = "远程控制",
        subtitle = when {
            state.workspaceConnected -> "$connectedPlatformLabel 已连接"
            state.running -> "已开启 · ${state.clients.size} 台已配对设备"
            state.starting -> "正在启动…"
            else -> "未开启"
        },
        expanded = expanded,
        onExpandedChange = onExpandedChange
    ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("本机 ID", style = MaterialTheme.typography.labelLarge)
                        Text(
                            state.deviceDisplayId.ifBlank { "正在初始化" },
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    Row {
                        TextButton(
                            onClick = { clipboard.setText(AnnotatedString(state.deviceDisplayId)) },
                            enabled = state.deviceDisplayId.isNotBlank(),
                        ) { Text("复制") }
                        TextButton(
                            onClick = {
                                val text = "我的 Murong 本机 ID：${state.deviceDisplayId}"
                                context.startActivity(
                                    Intent.createChooser(
                                        Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_TEXT, text)
                                        },
                                        "分享本机 ID",
                                    )
                                )
                            },
                            enabled = state.deviceDisplayId.isNotBlank(),
                        ) { Text("分享") }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("临时验证码", style = MaterialTheme.typography.labelLarge)
                        Text(
                            state.pairingCode ?: when {
                                state.starting -> "正在生成"
                                state.running -> "正在生成"
                                else -> "开启后显示"
                            },
                            style = MaterialTheme.typography.titleMedium,
                        )
                        state.pairingExpiresAt?.takeIf { state.pairingCode != null }?.let { expiresAt ->
                            val seconds = ((expiresAt - now) / 1_000L).coerceAtLeast(0L)
                            Text(
                                "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')} 后自动更换",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    TextButton(
                        onClick = { state.pairingCode?.let { clipboard.setText(AnnotatedString(it)) } },
                        enabled = state.pairingCode != null,
                    ) { Text("复制") }
                }
                TextButton(onClick = { showSecurityPasswordEditor = !showSecurityPasswordEditor }) {
                    Text(if (state.securityPasswordConfigured) "更改安全密码" else "更改为安全密码")
                }

                Text("已配对设备：", style = MaterialTheme.typography.labelLarge)
                if (state.clients.isEmpty()) {
                    Text(
                        "暂无",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    state.clients.forEach { client ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(client.name, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    client.deviceId.takeIf { it.isNotBlank() }?.chunked(4)?.joinToString("-")
                                        ?: client.id.take(8),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { viewModel.revokeClient(client.id) }) { Text("撤销") }
                        }
                    }
                }

                Text("请输入要配对的设备：", style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = targetDeviceId,
                        onValueChange = { targetDeviceId = it.uppercase().take(24) },
                        placeholder = { Text("请输入设备码") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        onClick = { pendingTargetDeviceId = targetDeviceId },
                        enabled = state.running && !state.outgoingDeviceConnection && targetDeviceId.isNotBlank(),
                    ) { Text("连接") }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("当前环境已有设备：", style = MaterialTheme.typography.labelLarge)
                    TextButton(
                        onClick = viewModel::discoverDevices,
                        enabled = state.running && !state.discoveringDevices,
                    ) { Text(if (state.discoveringDevices) "查找中" else "刷新") }
                }
                if (state.environmentDevices.isEmpty()) {
                    Text(
                        if (state.discoveringDevices) "正在查找当前网络中的设备…" else "暂未发现，也可以直接输入设备码连接。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    state.environmentDevices.forEach { device ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(device.name, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "${device.deviceDisplayId} · ${device.platform}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(
                                onClick = {
                                    targetDeviceId = device.deviceDisplayId
                                    // Signed environment discovery already identifies the target.
                                    // Connect without asking the initiating user for a password;
                                    // an untrusted peer will ask for approval on the receiving device.
                                    viewModel.connectDevice(device.deviceDisplayId)
                                },
                                enabled = !state.outgoingDeviceConnection,
                            ) { Text("立即连接") }
                        }
                    }
                }
                state.outgoingDeviceConnectionStatus.takeIf { it.isNotBlank() }?.let { status ->
                    Text(
                        status,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.deviceRelayError == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                }
                if (showRemoteDetails) {
                    Text(
                        text = state.deviceRelayStatus,
                        style = MaterialTheme.typography.bodySmall,
                        color = when {
                            state.deviceRelayConnected -> MaterialTheme.colorScheme.primary
                            state.deviceRelayError != null -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    state.deviceRelayError?.let { relayError ->
                        Text(relayError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("免打扰", style = MaterialTheme.typography.labelLarge)
                            Text(
                                "开启后静默忽略陌生设备的连接申请；已信任设备仍可连接。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = state.doNotDisturb, onCheckedChange = viewModel::setDoNotDisturb)
                    }
                }
                if (state.connectionRequests.isNotEmpty()) {
                    Text("等待连接确认", style = MaterialTheme.typography.labelLarge)
                    state.connectionRequests.forEach { request ->
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(request.clientName, style = MaterialTheme.typography.titleSmall)
                            Text(
                                "${request.deviceDisplayId} · ${request.platform} · ${request.transport}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                "允许连接只建立设备信任，文件写入、终端、Root 和 GitHub 修改仍按审批设置执行。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { viewModel.approveConnectionRequest(request.requestId) }) {
                                    Text("同意")
                                }
                                OutlinedButton(
                                    onClick = { viewModel.rejectConnectionRequest(request.requestId, false) },
                                ) { Text("拒绝") }
                                TextButton(
                                    onClick = { viewModel.rejectConnectionRequest(request.requestId, true) },
                                ) { Text("拉黑") }
                            }
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
                    if (showRemoteDetails) Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text("连接说明", style = MaterialTheme.typography.labelLarge)
                            Text(
                                "1. 复制上面的 16 位本机 ID。\n" +
                                    "2. 在 Murong Desktop 选择“输入手机本机 ID”并粘贴。\n" +
                                    "3. 同一 GitHub 账号会直接连接；其他设备使用临时验证码、安全密码或连接确认。",
                                style = MaterialTheme.typography.bodySmall
                            )
                            state.nodeUrl?.let { nodeUrl ->
                                Text(
                                    "局域网地址：$nodeUrl",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Text(
								"电脑文件能力默认只读；可同时授权当前系统发现的多个终端。桌面聊天默认不共享，需在 Murong Desktop 明确开启。以后启动同一个桌面应用即可复用已加密保存的配对凭据。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    OutlinedButton(
                        onClick = viewModel::stopService,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("关闭远程控制") }
                } else {
                    Button(
                        onClick = {
                            if (hasLanPermission(context)) {
                                viewModel.saveAndStartService()
                            } else {
                                permissionLauncher.launch(LanWebContract.LOCAL_NETWORK_PERMISSION)
                            }
                        },
                        enabled = !state.starting,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("开启远程控制") }
                }

                if (showSecurityPasswordEditor) Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("安全密码", style = MaterialTheme.typography.labelLarge)
                        Text(
                            if (state.securityPasswordConfigured) {
                                "安全密码已设置。输入新密码即可替换；手机不会保存密码明文。"
                            } else {
                                "设置后可长期使用，不必每次输入临时验证码。密码不会通过网络发送。"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedTextField(
                            value = securityPasswordDraft,
                            onValueChange = { securityPasswordDraft = it.take(128) },
                            label = { Text(if (state.securityPasswordConfigured) "输入新密码以替换" else "8–128 个字符") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FilledTonalButton(
                                onClick = {
                                    viewModel.setSecurityPassword(securityPasswordDraft)
                                    securityPasswordDraft = ""
                                    showSecurityPasswordEditor = false
                                },
                                enabled = securityPasswordDraft.trim().length >= 8,
                                modifier = Modifier.weight(1f),
                            ) { Text(if (state.securityPasswordConfigured) "更换密码" else "设置密码") }
                            OutlinedButton(
                                onClick = {
                                    viewModel.clearSecurityPassword()
                                    showSecurityPasswordEditor = false
                                },
                                enabled = state.securityPasswordConfigured,
                                modifier = Modifier.weight(1f),
                            ) { Text("清除") }
                        }
                        state.pairingCooldownUntil?.takeIf { it > now }?.let { cooldownUntil ->
                            Text(
                                "临时验证码因连续失败已轮换，${((cooldownUntil - now + 999) / 1_000)} 秒后可重新生成。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }

                if (state.running && showRemoteDetails) {
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
                            if (state.deviceRelayRunning) {
                                "尚未连接。请在 Murong Desktop 选择“输入手机本机 ID”，填写 ${state.deviceDisplayId}；同账号直接连接，否则手机会弹出确认。"
                            } else {
                                "远程控制尚未开启。开启后可通过本机 ID、同网发现或已授权 ADB 与电脑连接。"
                            },
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

                if (showRemoteDetails && state.clients.isNotEmpty()) OutlinedButton(
                    onClick = { confirmRevokeAll = true },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("撤销全部客户端") }
                if (showRemoteDetails && state.blockedPeers.isNotEmpty()) {
                    Text("黑名单 ${state.blockedPeers.size}", style = MaterialTheme.typography.labelLarge)
                    state.blockedPeers.forEach { peer ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(peer.name, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    peer.deviceId.chunked(4).joinToString("-"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { viewModel.unblockPeer(peer.deviceId) }) { Text("移出") }
                        }
                    }
                }
                TextButton(onClick = { showRemoteDetails = !showRemoteDetails }) {
                    Text(if (showRemoteDetails) "收起更多设置" else "更多设置")
                }
    }

    pendingTargetDeviceId?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingTargetDeviceId = null; connectSecret = "" },
            title = { Text("连接设备") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(target, style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(
                            onClick = { connectAuthMethod = com.murong.agent.lan.LanWebTrustSource.TEMPORARY_CODE },
                            enabled = connectAuthMethod != com.murong.agent.lan.LanWebTrustSource.TEMPORARY_CODE,
                        ) { Text("临时验证码") }
                        FilledTonalButton(
                            onClick = { connectAuthMethod = com.murong.agent.lan.LanWebTrustSource.SECURITY_PASSWORD },
                            enabled = connectAuthMethod != com.murong.agent.lan.LanWebTrustSource.SECURITY_PASSWORD,
                        ) { Text("安全密码") }
                    }
                    OutlinedTextField(
                        value = connectSecret,
                        onValueChange = { connectSecret = it.take(128) },
                        label = {
                            Text(if (connectAuthMethod == com.murong.agent.lan.LanWebTrustSource.TEMPORARY_CODE) "输入对方临时验证码" else "输入对方安全密码")
                        },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "也可以不输入密码，直接发送申请让对方手动同意。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    pendingTargetDeviceId = null
                    viewModel.connectDevice(target, connectAuthMethod, connectSecret)
                    connectSecret = ""
                }, enabled = connectSecret.isNotBlank()) { Text("连接") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        pendingTargetDeviceId = null
                        connectSecret = ""
                        viewModel.connectDevice(target)
                    }) { Text("发送申请") }
                    TextButton(onClick = { pendingTargetDeviceId = null; connectSecret = "" }) { Text("取消") }
                }
            },
        )
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
        DesktopAgentChatScreen(
            viewModel = viewModel,
            onExit = {
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
