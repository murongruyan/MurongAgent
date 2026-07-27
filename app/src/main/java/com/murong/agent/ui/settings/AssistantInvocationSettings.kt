package com.murong.agent.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.murong.agent.R
import com.murong.agent.core.config.ProviderConfig
import com.murong.agent.core.tool.BuiltinVisionModelManager
import com.murong.agent.core.tool.BuiltinVisionModels
import com.murong.agent.core.tool.BuiltinVisionTier
import com.murong.agent.ui.MurongLargeDialogScaffold
import com.murong.agent.ui.assistant.AssistantInvocationPreferences
import com.murong.agent.ui.assistant.DEFAULT_WAKE_PHRASE
import com.murong.agent.ui.assistant.MurongAssistActivity
import com.murong.agent.ui.assistant.MurongVoiceInteractionService
import com.murong.agent.ui.assistant.VoiceWakeWordService
import com.murong.agent.ui.assistant.WakeWordRuntimeStatus
import com.murong.agent.ui.tools.BuiltinVisionModelDownloadService
import com.murong.agent.ui.tools.PhoneAgentModelOption
import com.murong.agent.ui.tools.buildAssistantCodeModelOptions
import com.murong.agent.ui.tools.buildPhoneAgentModelOptions
import com.murong.agent.voice.OfflineVoiceModelUiState
import com.murong.agent.voice.SpeakerProfileStatus
import com.murong.agent.voice.SpeakerProfileUiState
import com.murong.agent.voice.SpeakerVerificationManager
import com.murong.agent.voice.SpeakerVerificationModel
import com.murong.agent.voice.SPEAKER_ENROLLMENT_SAMPLE_COUNT
import kotlinx.coroutines.delay

@Composable
internal fun AssistantInvocationSettings(
    offlineModelState: OfflineVoiceModelUiState,
    config: ProviderConfig,
    onConfigChanged: (ProviderConfig) -> Unit,
) {
    val context = LocalContext.current
    val speakerManager = remember { SpeakerVerificationManager(context) }
    val speakerState by speakerManager.state.collectAsState()
    val fastModelManager = remember {
        BuiltinVisionModelManager.shared(context.applicationContext)
    }
    val fastModelState by fastModelManager.state.collectAsState()
    val fastDescriptor = BuiltinVisionModels.GLM_EDGE_1_5B_CHAT
    var settings by remember { mutableStateOf(AssistantInvocationPreferences.read(context)) }
    var localMessage by remember { mutableStateOf<String?>(null) }
    var showVoiceprintEnrollment by remember { mutableStateOf(false) }
    var enrollmentSequenceActive by remember { mutableStateOf(false) }
    var startEnrollmentAfterPermission by remember { mutableStateOf(false) }
    var showPhoneModelPicker by remember { mutableStateOf(false) }
    var showCodeModelPicker by remember { mutableStateOf(false) }
    val runtime by VoiceWakeWordService.runtime.collectAsState()
    val installedFastModels = BuiltinVisionModels.all.filter { descriptor ->
        descriptor.androidSupported &&
            !descriptor.supportsVision &&
            descriptor.tier in fastModelState.installedTiers
    }
    val phoneModelOptions = remember(config, fastModelState.installedTiers) {
        buildPhoneAgentModelOptions(config, fastModelState.installedTiers)
    }
    val codeModelOptions = remember(config, fastModelState.installedTiers) {
        buildAssistantCodeModelOptions(config, fastModelState.installedTiers)
    }
    val selectedPhoneModel = phoneModelOptions.selectedOption(
        config.phoneAgentProviderId,
        config.phoneAgentRelayId,
    )
    val selectedCodeModel = codeModelOptions.selectedOption(
        config.assistantCodeProviderId,
        config.assistantCodeRelayId,
    )
    val resolvedPhoneConfig = config.getPhoneAgentResolvedConfig()
    val microphoneLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted && startEnrollmentAfterPermission) {
            startEnrollmentAfterPermission = false
            enrollmentSequenceActive = true
            localMessage = null
            speakerManager.captureEnrollmentSample()
        } else {
            startEnrollmentAfterPermission = false
            localMessage = if (granted) {
                if (speakerState.enrolled) {
                    "麦克风权限已允许，请再次启用离线唤醒词"
                } else {
                    "麦克风权限已允许，请先完成声纹录入，再启用持续唤醒"
                }
            } else {
                "未获得麦克风权限，不能录入声纹或持续监听唤醒词"
            }
        }
    }

    DisposableEffect(speakerManager) {
        speakerManager.refresh()
        onDispose { speakerManager.close() }
    }

    LaunchedEffect(
        showVoiceprintEnrollment,
        enrollmentSequenceActive,
        speakerState.status,
        speakerState.enrollmentSamples,
    ) {
        if (!showVoiceprintEnrollment || !enrollmentSequenceActive) {
            return@LaunchedEffect
        }
        when {
            speakerState.enrolled -> enrollmentSequenceActive = false
            speakerState.status == SpeakerProfileStatus.ERROR ->
                enrollmentSequenceActive = false

            speakerState.status == SpeakerProfileStatus.READY_TO_ENROLL &&
                speakerState.enrollmentSamples in 1 until
                SPEAKER_ENROLLMENT_SAMPLE_COUNT -> {
                delay(1_400L)
                if (enrollmentSequenceActive) {
                    speakerManager.captureEnrollmentSample()
                }
            }
        }
    }

    if (showVoiceprintEnrollment) {
        VoiceprintEnrollmentDialog(
            state = speakerState,
            sequenceActive = enrollmentSequenceActive,
            onStart = {
                if (
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.RECORD_AUDIO,
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    startEnrollmentAfterPermission = true
                    microphoneLauncher.launch(Manifest.permission.RECORD_AUDIO)
                } else {
                    enrollmentSequenceActive = true
                    localMessage = null
                    speakerManager.captureEnrollmentSample()
                }
            },
            onPause = {
                enrollmentSequenceActive = false
                speakerManager.cancelEnrollmentCapture()
            },
            onReset = {
                enrollmentSequenceActive = false
                VoiceWakeWordService.setEnabled(context, false)
                speakerManager.resetEnrollment()
                settings = AssistantInvocationPreferences.read(context)
            },
            onDismiss = {
                enrollmentSequenceActive = false
                startEnrollmentAfterPermission = false
                speakerManager.cancelEnrollmentCapture()
                showVoiceprintEnrollment = false
            },
        )
    }

    if (showPhoneModelPicker) {
        AssistantModelPickerDialog(
            title = "选择手机操作视觉模型",
            options = phoneModelOptions,
            selectedKey = selectedPhoneModel?.key,
            onSelect = { option ->
                onConfigChanged(
                    config.copy(
                        phoneAgentProviderId = option.providerId.orEmpty(),
                        phoneAgentRelayId = option.relayId,
                    ),
                )
                showPhoneModelPicker = false
            },
            onDismiss = { showPhoneModelPicker = false },
        )
    }
    if (showCodeModelPicker) {
        AssistantModelPickerDialog(
            title = "选择后台编码模型",
            options = codeModelOptions,
            selectedKey = selectedCodeModel?.key,
            onSelect = { option ->
                onConfigChanged(
                    config.copy(
                        assistantCodeProviderId = option.providerId.orEmpty(),
                        assistantCodeRelayId = option.relayId,
                    ),
                )
                showCodeModelPicker = false
            },
            onDismiss = { showCodeModelPicker = false },
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("语音助手唤醒", style = MaterialTheme.typography.titleSmall)
        Text(
            "默认助理键由系统管理；也可用“慕容慕容 + 本机声纹”或音量加减键组合三连按唤醒。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text("仅本人声纹", style = MaterialTheme.typography.titleSmall)
        Text(
            speakerState.message,
            style = MaterialTheme.typography.bodySmall,
            color = if (speakerState.status == SpeakerProfileStatus.ERROR) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        if (speakerState.status == SpeakerProfileStatus.DOWNLOADING) {
            LinearProgressIndicator(
                progress = { speakerState.progress },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "${formatBytes(speakerState.downloadedBytes)} / ${
                    formatBytes(speakerState.totalBytes)
                }",
                style = MaterialTheme.typography.labelSmall,
            )
        }
        when {
            !speakerState.modelInstalled -> Button(
                enabled = !speakerState.busy,
                onClick = { speakerManager.installModel() },
            ) {
                Text(if (speakerState.busy) "正在安装…" else "安装离线声纹模型（约 27 MB）")
            }

            !speakerState.enrolled -> Button(
                enabled = !speakerState.busy,
                onClick = { showVoiceprintEnrollment = true },
            ) {
                Text(
                    if (speakerState.busy) {
                        "正在录入…"
                    } else if (speakerState.enrollmentSamples > 0) {
                        "继续录入（${speakerState.enrollmentSamples}/" +
                            "$SPEAKER_ENROLLMENT_SAMPLE_COUNT）"
                    } else {
                        "开始录入本人声纹"
                    },
                )
            }

            else -> OutlinedButton(
                onClick = { showVoiceprintEnrollment = true },
            ) {
                Text("管理或重新录入声纹")
            }
        }
        Text(
            "原始录音不会保存或上传，只持久化 Android Keystore 加密后的声纹特征。" +
                "声纹可降低误唤醒，但不能替代支付等高风险操作的确认，也不能完全防录音重放。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "模型：3D-Speaker CAMPPlus · ${SpeakerVerificationModel.LICENSE}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text("声纹严格度", style = MaterialTheme.typography.bodyMedium)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            listOf(
                0.52f to "宽松",
                0.58f to "平衡",
                0.66f to "严格",
            ).forEach { (threshold, label) ->
                FilterChip(
                    selected = kotlin.math.abs(
                        settings.speakerVerificationThreshold - threshold,
                    ) < 0.005f,
                    onClick = {
                        AssistantInvocationPreferences.setSpeakerVerificationThreshold(
                            context,
                            threshold,
                        )
                        settings = settings.copy(speakerVerificationThreshold = threshold)
                    },
                    label = { Text(label) },
                )
            }
        }

        AssistantToggleRow(
            title = "离线唤醒词",
            subtitle = wakeRuntimeDescription(runtime.status, runtime.detail),
            checked = settings.wakeWordEnabled &&
                runtime.status !in setOf(
                    WakeWordRuntimeStatus.STOPPED,
                    WakeWordRuntimeStatus.MODEL_MISSING,
                    WakeWordRuntimeStatus.PERMISSION_MISSING,
                    WakeWordRuntimeStatus.VOICEPRINT_MISSING,
                ),
            onCheckedChange = { enabled ->
                localMessage = null
                when {
                    !enabled -> {
                        VoiceWakeWordService.setEnabled(context, false)
                        settings = AssistantInvocationPreferences.read(context)
                    }

                    !offlineModelState.isInstalled ->
                        localMessage = "请先下载离线语音识别模型"

                    !speakerState.enrolled ->
                        localMessage = "请先完成上方 $SPEAKER_ENROLLMENT_SAMPLE_COUNT 段本人声纹录入"

                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.RECORD_AUDIO,
                    ) != PackageManager.PERMISSION_GRANTED ->
                        microphoneLauncher.launch(Manifest.permission.RECORD_AUDIO)

                    else -> {
                        runCatching { VoiceWakeWordService.setEnabled(context, true) }
                            .onFailure { localMessage = "无法启动麦克风前台服务：${it.message}" }
                        settings = AssistantInvocationPreferences.read(context)
                    }
                }
            },
        )

        OutlinedTextField(
            value = settings.wakePhrase,
            onValueChange = { value ->
                val normalized = value.take(16)
                settings = settings.copy(wakePhrase = normalized)
                AssistantInvocationPreferences.setWakePhrase(
                    context,
                    normalized.ifBlank { DEFAULT_WAKE_PHRASE },
                )
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("离线唤醒词") },
            supportingText = { Text("默认“慕容慕容”；匹配成功后还必须通过本人声纹验证") },
        )

        AssistantToggleRow(
            title = "音量加减键组合三连按",
            subtitle = "同时按下音量 + 和音量 -，完全松开后重复三次；需启用 Murong 无障碍",
            checked = settings.volumeChordTripleEnabled,
            onCheckedChange = { enabled ->
                AssistantInvocationPreferences.setVolumeChordTripleEnabled(context, enabled)
                settings = settings.copy(volumeChordTripleEnabled = enabled)
            },
        )

        Text("任务模型分工", style = MaterialTheme.typography.titleSmall)
        Text(
            "轻量模型只负责日常对话和检索摘要；屏幕文字、图片与圈选识别会单独使用下面的视觉模型，代码和进程任务可另选更强的编码模型。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AssistantModelSelectionRow(
            title = "手机操作 / 屏幕理解模型",
            selection = selectedPhoneModel?.title ?: "当前选择已失效，请重新选择",
            subtitle = selectedPhoneModel?.subtitle
                ?: "必须选择支持图片输入的已安装本地模型或 API 模型",
            error = selectedPhoneModel?.enabled == false || selectedPhoneModel == null,
            onClick = { showPhoneModelPicker = true },
        )
        if (
            !resolvedPhoneConfig.usesCodexChatGptBackend() &&
            !resolvedPhoneConfig.isActiveProviderLocal()
        ) {
            AssistantToggleRow(
                title = "允许向手机操作 API 发送截图",
                subtitle = "关闭时远程模型不会收到屏幕；本地视觉模型始终只在本机处理",
                checked = config.phoneAgentAllowRemoteScreenshots,
                onCheckedChange = { enabled ->
                    onConfigChanged(config.copy(phoneAgentAllowRemoteScreenshots = enabled))
                },
            )
        }
        AssistantModelSelectionRow(
            title = "后台写代码 / 进程任务模型",
            selection = selectedCodeModel?.title ?: "当前选择已失效，请重新选择",
            subtitle = selectedCodeModel?.subtitle ?: "可选择已安装本地模型或已配置 API",
            error = selectedCodeModel == null,
            onClick = { showCodeModelPicker = true },
        )

        Text("极速本地助手模型", style = MaterialTheme.typography.titleSmall)
        Text(
            "日常聊天和联网结果总结优先走这里；未安装时自动回退当前 API/主模型。" +
                "计时器和闹钟直接调用 Android，不经过模型。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FilterChip(
                selected = settings.fastLocalModelId.isBlank(),
                onClick = {
                    AssistantInvocationPreferences.setFastLocalModelId(context, "")
                    settings = settings.copy(fastLocalModelId = "")
                },
                label = { Text("自动选择最快") },
            )
            installedFastModels.forEach { descriptor ->
                FilterChip(
                    selected = settings.fastLocalModelId == descriptor.id,
                    onClick = {
                        AssistantInvocationPreferences.setFastLocalModelId(
                            context,
                            descriptor.id,
                        )
                        settings = settings.copy(fastLocalModelId = descriptor.id)
                    },
                    label = { Text(descriptor.displayName) },
                )
            }
        }
        if (BuiltinVisionTier.GLM_EDGE_1_5B_CHAT !in fastModelState.installedTiers) {
            Text(
                if (installedFastModels.isEmpty()) {
                    "尚未安装纯文本本地模型。可直接在这里安装；下载会留在通知栏并支持暂停、断点续传和 SHA-256 校验。"
                } else {
                    "还可安装更适合日常对话的 ${fastDescriptor.displayName}；下载会留在通知栏并支持断点续传和 SHA-256 校验。"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            if (fastModelState.installingTier == BuiltinVisionTier.GLM_EDGE_1_5B_CHAT) {
                LinearProgressIndicator(
                    progress = { fastModelState.progress },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "${(fastModelState.progress * 100).toInt()}% · " +
                        "${formatBytes(fastModelState.downloadedBytes)} / " +
                        "${formatBytes(fastModelState.totalBytes)} · ${fastModelState.message}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = fastModelManager::cancelInstall) {
                    Text("暂停下载")
                }
            } else {
                Button(
                    enabled = !fastModelState.isInstalling,
                    onClick = {
                        BuiltinVisionModelDownloadService.enqueue(
                            context,
                            BuiltinVisionTier.GLM_EDGE_1_5B_CHAT,
                        )
                    },
                ) {
                    Text("下载 ${fastDescriptor.displayName}（约 1.05 GB）")
                }
            }
            fastModelState.error?.let { error ->
                Text(
                    "上次安装未完成：$error",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Button(
                onClick = { MurongVoiceInteractionService.requestShow(context, "settings_test") },
                modifier = Modifier.weight(1f),
            ) {
                Text("测试弹窗")
            }
            OutlinedButton(
                onClick = { localMessage = pinAssistantShortcut(context) },
                modifier = Modifier.weight(1f),
            ) {
                Text("添加桌面入口")
            }
        }

        localMessage?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

private fun List<PhoneAgentModelOption>.selectedOption(
    providerId: String,
    relayId: String,
): PhoneAgentModelOption? {
    val key = providerId.trim().takeIf { it.isNotBlank() }
        ?.let { "$it:${relayId.trim()}" }
        ?: "follow-chat"
    return firstOrNull { it.key == key }
}

@Composable
private fun AssistantModelSelectionRow(
    title: String,
    selection: String,
    subtitle: String,
    error: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large,
        color = if (error) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(
                selection,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = if (error) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

@Composable
private fun AssistantModelPickerDialog(
    title: String,
    options: List<PhoneAgentModelOption>,
    selectedKey: String?,
    onSelect: (PhoneAgentModelOption) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                options.forEach { option ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = option.enabled) { onSelect(option) },
                        shape = MaterialTheme.shapes.medium,
                        color = if (option.key == selectedKey) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                buildString {
                                    if (option.key == selectedKey) append("✓ ")
                                    append(option.title)
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (option.enabled) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                            )
                            Text(
                                option.subtitle,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (option.enabled) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

@Composable
private fun VoiceprintEnrollmentDialog(
    state: SpeakerProfileUiState,
    sequenceActive: Boolean,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    val completed = state.enrollmentSamples.coerceIn(
        0,
        SPEAKER_ENROLLMENT_SAMPLE_COUNT,
    )
    val nextSample = (completed + 1).coerceAtMost(SPEAKER_ENROLLMENT_SAMPLE_COUNT)
    val recording = state.status == SpeakerProfileStatus.RECORDING
    val processing = state.status == SpeakerProfileStatus.PROCESSING

    MurongLargeDialogScaffold(onDismissRequest = onDismiss) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 22.dp, top = 16.dp, end = 12.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "录入本人声纹",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "共 $SPEAKER_ENROLLMENT_SAMPLE_COUNT 次，全程只在本机处理",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            LinearProgressIndicator(
                progress = {
                    completed.toFloat() / SPEAKER_ENROLLMENT_SAMPLE_COUNT
                },
                modifier = Modifier.fillMaxWidth(),
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                (1..SPEAKER_ENROLLMENT_SAMPLE_COUNT).forEach { sample ->
                    Surface(
                        shape = MaterialTheme.shapes.large,
                        color = if (sample <= completed) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                    ) {
                        Text(
                            if (sample <= completed) "第 $sample 次 ✓" else "第 $sample 次",
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (sample <= completed) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge,
                color = when {
                    state.enrolled -> MaterialTheme.colorScheme.primaryContainer
                    state.status == SpeakerProfileStatus.ERROR ->
                        MaterialTheme.colorScheme.errorContainer
                    else -> MaterialTheme.colorScheme.secondaryContainer
                },
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        when {
                            state.enrolled -> "录入完成"
                            recording -> "正在聆听第 $nextSample 次"
                            processing -> "正在提取第 $nextSample 次声纹"
                            state.status == SpeakerProfileStatus.ERROR -> "这一段没有录好"
                            completed > 0 -> "准备录入第 $nextSample 次"
                            else -> "准备开始"
                        },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (!state.enrolled) {
                        Text(
                            if (recording) {
                                "现在自然地说："
                            } else {
                                voiceprintEnrollmentHint(nextSample)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (recording) {
                            Text(
                                "“慕容慕容”",
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                "说完保持安静，约 1 秒后自动结束",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Text(
                        state.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = when {
                            state.status == SpeakerProfileStatus.ERROR ->
                                MaterialTheme.colorScheme.onErrorContainer
                            state.enrolled -> MaterialTheme.colorScheme.onPrimaryContainer
                            else -> MaterialTheme.colorScheme.onSecondaryContainer
                        },
                    )
                }
            }

            Text(
                "怎么录",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "• 在较安静的环境中，手机距离嘴部约 20–40 厘米。\n" +
                    "• 每次只说一遍“慕容慕容”，使用平时说话的音量。\n" +
                    "• 说完停顿即可，应用会自动切到下一次，无需反复点按钮。\n" +
                    "• 五次会稍微改变语速和手机角度，让日常唤醒更稳定。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "不会保存原始录音，也不会上传；只保存 Android Keystore 加密后的声纹特征。",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                state.enrolled -> Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("完成")
                }

                recording || processing || sequenceActive -> Button(
                    onClick = onPause,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("暂停并保留进度")
                }

                else -> Button(
                    onClick = onStart,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (completed > 0) {
                            "继续录入第 $nextSample 次"
                        } else {
                            "开始连续录入"
                        },
                    )
                }
            }
            if (completed > 0 && !recording && !processing && !sequenceActive) {
                OutlinedButton(
                    onClick = onReset,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("清除进度并重新开始")
                }
            }
        }
    }
}

internal fun voiceprintEnrollmentHint(sample: Int): String = when (sample) {
    1 -> "保持正常姿势，用平时的语速说一次"
    2 -> "这一次稍慢一点，但不要拖长或大声喊"
    3 -> "这一次稍快一点，仍要完整说清楚"
    4 -> "手机稍微偏离正前方，再自然说一次"
    5 -> "恢复最自然的姿势和语速，完成最后一次"
    else -> "自然说一次“慕容慕容”"
}

private fun wakeRuntimeDescription(status: WakeWordRuntimeStatus, detail: String): String =
    when (status) {
        WakeWordRuntimeStatus.LISTENING,
        WakeWordRuntimeStatus.STARTING,
        WakeWordRuntimeStatus.PAUSED,
        WakeWordRuntimeStatus.VERIFYING_SPEAKER,
        WakeWordRuntimeStatus.SPEAKER_REJECTED,
        WakeWordRuntimeStatus.ERROR,
        -> detail

        WakeWordRuntimeStatus.MODEL_MISSING -> "需要先下载离线语音识别模型"
        WakeWordRuntimeStatus.PERMISSION_MISSING -> "需要麦克风权限"
        WakeWordRuntimeStatus.VOICEPRINT_MISSING -> "需要先录入本人声纹"
        WakeWordRuntimeStatus.STOPPED -> "持续监听会增加耗电；音频不会保存或上传"
    }

@Composable
private fun AssistantToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000L -> "${"%.1f".format(bytes / 1_000_000.0)} MB"
    bytes >= 1_000L -> "${bytes / 1_000L} KB"
    else -> "$bytes B"
}

private fun pinAssistantShortcut(context: android.content.Context): String {
    val manager = context.getSystemService(ShortcutManager::class.java)
        ?: return "当前桌面不支持快捷方式"
    if (!manager.isRequestPinShortcutSupported) return "当前桌面不支持固定快捷方式"
    val shortcut = ShortcutInfo.Builder(context, "murong_voice_assistant")
        .setShortLabel("Murong 助手")
        .setLongLabel("打开 Murong 语音助手")
        .setIcon(Icon.createWithResource(context, R.drawable.app_icon))
        .setIntent(
            MurongAssistActivity.createIntent(context, "pinned_shortcut")
                .setAction(Intent.ACTION_VIEW),
        )
        .build()
    return if (manager.requestPinShortcut(shortcut, null)) {
        "已请求桌面添加助手入口"
    } else {
        "桌面拒绝了快捷方式请求"
    }
}
