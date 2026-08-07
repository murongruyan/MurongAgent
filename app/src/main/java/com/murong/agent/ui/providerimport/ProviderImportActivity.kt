package com.murong.agent.ui.providerimport

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.murong.agent.core.config.ConfigRepository
import com.murong.agent.core.config.ProviderImportDeepLink
import com.murong.agent.core.config.ProviderImportPayload
import com.murong.agent.ui.MainActivity
import com.murong.agent.ui.MurongTheme
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@AndroidEntryPoint
class ProviderImportActivity : ComponentActivity() {
    private val viewModel: ProviderImportViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        viewModel.load(intent?.dataString)
        setContent {
            MurongTheme {
                ProviderImportScreen(
                    viewModel = viewModel,
                    onClose = ::finish,
                    onOpenMurong = ::openMurong,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        viewModel.load(intent.dataString)
    }

    private fun openMurong() {
        startActivity(
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        )
        finish()
    }
}

data class ProviderImportUiState(
    val payload: ProviderImportPayload? = null,
    val error: String? = null,
    val importing: Boolean = false,
    val imported: Boolean = false,
)

@HiltViewModel
class ProviderImportViewModel @Inject constructor(
    private val configRepository: ConfigRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(ProviderImportUiState())
    val state: StateFlow<ProviderImportUiState> = _state.asStateFlow()

    fun load(rawLink: String?) {
        val link = rawLink?.trim().orEmpty()
        if (link.isEmpty()) {
            _state.value = ProviderImportUiState(error = "没有收到可导入的供应商链接")
            return
        }
        ProviderImportDeepLink.parse(link)
            .onSuccess { _state.value = ProviderImportUiState(payload = it) }
            .onFailure { error ->
                _state.value = ProviderImportUiState(error = error.message ?: "导入链接格式无效")
            }
    }

    fun confirm(activate: Boolean, enableUsage: Boolean) {
        val payload = _state.value.payload ?: return
        if (_state.value.importing) return
        _state.value = _state.value.copy(importing = true, error = null)
        viewModelScope.launch {
            runCatching {
                val updated = payload.applyTo(
                    current = configRepository.getConfig(),
                    activate = activate,
                    enableUsage = enableUsage,
                )
                configRepository.saveConfig(updated)
            }.onSuccess {
                _state.value = _state.value.copy(importing = false, imported = true)
            }.onFailure { error ->
                _state.value = _state.value.copy(
                    importing = false,
                    error = error.message ?: "保存供应商失败",
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderImportScreen(
    viewModel: ProviderImportViewModel,
    onClose: () -> Unit,
    onOpenMurong: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val payload = state.payload
    var activate by remember(payload) { mutableStateOf(payload?.requestedActive == true) }
    var enableUsage by remember(payload) {
        mutableStateOf(payload?.requestedUsageEnabled == true && payload.usageRule != null)
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("确认导入供应商", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "CC Switch V1 兼容链接",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "取消导入")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        when {
            state.imported -> ImportSuccess(
                modifier = Modifier.padding(padding),
                payload = payload,
                onOpenMurong = onOpenMurong,
            )
            payload != null -> ImportPreview(
                modifier = Modifier.padding(padding),
                payload = payload,
                activate = activate,
                onActivateChanged = { activate = it },
                enableUsage = enableUsage,
                onEnableUsageChanged = { enableUsage = it },
                importing = state.importing,
                error = state.error,
                onConfirm = { viewModel.confirm(activate, enableUsage) },
                onCancel = onClose,
            )
            else -> ImportError(
                modifier = Modifier.padding(padding),
                message = state.error ?: "正在读取导入链接…",
                onClose = onClose,
            )
        }
    }
}

@Composable
private fun ImportPreview(
    modifier: Modifier,
    payload: ProviderImportPayload,
    activate: Boolean,
    onActivateChanged: (Boolean) -> Unit,
    enableUsage: Boolean,
    onEnableUsageChanged: (Boolean) -> Unit,
    importing: Boolean,
    error: String?,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ImportSecurityNotice(payload.sourceScheme)
            ImportField("应用类型", providerAppLabel(payload.app))
            ImportField("供应商名称", payload.name)
            payload.homepage?.let { ImportField("官网地址", it) }
            ImportField("API 端点", payload.endpoints.joinToString("\n"), monospace = true)
            ImportField("API 密钥", payload.maskedApiKey, monospace = true)
            payload.model?.let { ImportField("模型", it, monospace = true) }
            payload.notes?.let { ImportField("备注", it) }
            Divider()
            ImportToggle(
                title = "导入后设为当前连接",
                detail = "关闭时只加入供应商列表，不会影响正在使用的模型。",
                checked = activate,
                enabled = !importing,
                onCheckedChange = onActivateChanged,
            )
            val usageScript = payload.usageScript
            if (usageScript != null) {
                ImportToggle(
                    title = "启用余额自动查询",
                    detail = payload.usageRule?.let { rule ->
                        buildString {
                            append(rule.sourceLabel)
                            payload.usageAutoIntervalMinutes?.let { append(" · 原链接建议每 $it 分钟") }
                        }
                    } ?: "脚本无法安全转换，只能查看，不能执行。",
                    checked = enableUsage,
                    enabled = !importing && payload.usageRule != null,
                    onCheckedChange = onEnableUsageChanged,
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("原始用量脚本", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "这段 JavaScript 来自外部链接。Murong 不会执行它，只会识别受限的同源 GET 余额规则。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.extraSmall,
                    ) {
                        Text(
                            text = usageScript,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 240.dp)
                                .verticalScroll(rememberScrollState())
                                .padding(12.dp),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Surface(shadowElevation = 8.dp, color = MaterialTheme.colorScheme.surface) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onCancel, enabled = !importing) { Text("取消") }
                Spacer(Modifier.width(10.dp))
                Button(onClick = onConfirm, enabled = !importing) {
                    if (importing) {
                        CircularProgressIndicator(
                            modifier = Modifier.width(18.dp).height(18.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (importing) "正在导入" else "确认导入")
                }
            }
        }
    }
}

@Composable
private fun ImportSecurityNotice(sourceScheme: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(Icons.Outlined.Security, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("导入前检查", fontWeight = FontWeight.SemiBold)
            Text(
                if (sourceScheme == "ccswitch") {
                    "这是 CC Switch 兼容链接。请核对来源、端点和密钥后再导入。"
                } else {
                    "这是 Murong 原生导入链接。请核对来源、端点和密钥后再导入。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ImportField(label: String, value: String, monospace: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
        )
    }
}

@Composable
private fun ImportToggle(
    title: String,
    detail: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                enabled = enabled,
                onValueChange = onCheckedChange,
            )
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ImportSuccess(
    modifier: Modifier,
    payload: ProviderImportPayload?,
    onOpenMurong: () -> Unit,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(
                Icons.Outlined.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.width(48.dp).height(48.dp),
            )
            Text("供应商已导入", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(
                payload?.name.orEmpty(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = onOpenMurong) { Text("进入 Murong") }
        }
    }
}

@Composable
private fun ImportError(modifier: Modifier, message: String, onClose: () -> Unit) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(Icons.Outlined.Security, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            Text("无法导入", style = MaterialTheme.typography.headlineSmall)
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onClose) { Text("关闭") }
        }
    }
}

private fun providerAppLabel(app: String): String = when (app) {
    "codex" -> "Codex / OpenAI-compatible"
    "claude" -> "Claude / Anthropic Messages"
    "gemini" -> "Google Gemini"
    else -> "$app / OpenAI-compatible"
}
