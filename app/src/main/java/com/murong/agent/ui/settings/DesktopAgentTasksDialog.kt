package com.murong.agent.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.murong.agent.lan.LanWebDesktopAgentMessage
import com.murong.agent.lan.LanWebDesktopAgentAskAnswer
import com.murong.agent.lan.LanWebDesktopAgentWorkflowPlan
import com.murong.agent.lan.desktopPlatformLabel

@Composable
internal fun DesktopAgentChatScreen(
    viewModel: LanWebSettingsViewModel,
    onExit: () -> Unit,
    bottomReservedPadding: Dp = 0.dp,
) {
    val state by viewModel.desktopAgentState.collectAsStateWithLifecycle()
    val handoffState by viewModel.desktopHandoffState.collectAsStateWithLifecycle()
    val error by viewModel.desktopAgentError.collectAsStateWithLifecycle()
    val snapshot = state.snapshot
    val active = snapshot?.activeSession
    val sourceLabel = desktopPlatformLabel(state.sourcePlatform, state.sourceArchitecture)
    var composerText by remember(active?.id) { mutableStateOf("") }
    var askSelections by remember(active?.pendingAsk?.id) { mutableStateOf<Map<String, Set<String>>>(emptyMap()) }
    var askCustomAnswers by remember(active?.pendingAsk?.id) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var confirmAbandonSessionId by remember { mutableStateOf<String?>(null) }

    BackHandler(onBack = onExit)

    confirmAbandonSessionId?.let { sessionId ->
        AlertDialog(
            onDismissRequest = { confirmAbandonSessionId = null },
            title = { Text("放弃手机接管？") },
            text = { Text("手机端在接管后新增的聊天记录不会合并回电脑；电脑原任务会恢复可写。") },
            confirmButton = {
                Button(onClick = {
                    confirmAbandonSessionId = null
                    viewModel.abandonDesktopSession(sessionId)
                }) { Text("放弃并恢复电脑") }
            },
            dismissButton = {
                TextButton(onClick = { confirmAbandonSessionId = null }) { Text("取消") }
            }
        )
    }

    Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .padding(bottom = bottomReservedPadding),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("电脑任务", style = MaterialTheme.typography.titleLarge)
                        Text(
                            if (state.connected) {
                                "${snapshot?.sessions?.size ?: 0} 个任务 · ${if (state.controlAllowed) "可控制" else "只读查看"}"
                            } else {
                                "$sourceLabel 已断开 · 记录已保存在手机"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = viewModel::refreshDesktopAgent, enabled = state.connected) { Text("刷新") }
                    TextButton(onClick = onExit) { Text("本地聊天") }
                }

                error?.let {
                    Text(
                        text = it,
                        modifier = Modifier.fillMaxWidth()
                            .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(10.dp))
                            .padding(10.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                handoffState.notice?.let {
                    Text(
                        text = it,
                        modifier = Modifier.fillMaxWidth()
                            .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(10.dp))
                            .padding(10.dp),
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    snapshot?.sessions.orEmpty().forEach { session ->
                        val selected = active?.id == session.id
                        if (selected) {
                            FilledTonalButton(onClick = { viewModel.openDesktopSession(session.id) }) {
                                Text(taskLabel(session.title, session.running, session.pendingApproval, session.pendingQuestion, session.executionOwner))
                            }
                        } else {
                            OutlinedButton(onClick = { viewModel.openDesktopSession(session.id) }) {
                                Text(taskLabel(session.title, session.running, session.pendingApproval, session.pendingQuestion, session.executionOwner))
                            }
                        }
                    }
                }

                if (active == null) {
                    Card(modifier = Modifier.fillMaxWidth().weight(1f)) {
                        Column(modifier = Modifier.fillMaxSize().padding(18.dp)) {
                            Text(if (state.connected) "电脑端还没有任务" else "还没有可查看的电脑任务副本")
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "在 $sourceLabel Murong Desktop 的远程节点设置中开启“在手机显示桌面任务”。同步过的记录会写入手机应用私有目录；重新连接后按电脑端版本继续更新。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(active.title, style = MaterialTheme.typography.titleMedium)
                            Text(
                                when {
                                    active.executionOwner == "android" -> "执行权在手机"
                                    active.running -> "正在电脑运行"
                                    else -> "执行权在电脑 · 空闲"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (active.running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (active.running && state.connected && state.controlAllowed) {
                            OutlinedButton(onClick = { viewModel.cancelDesktopRun(active.id) }) { Text("停止") }
                        }
                    }

                    active.workflowPlan?.let { plan ->
                        DesktopWorkflowPlanCard(plan = plan, connected = state.connected)
                    }

                    val handoffRecord = handoffState.recordFor(active.id)
                    val handoffBusy = handoffState.busySessionId == active.id
                    if (active.executionOwner == "android") {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text("这项任务已冻结在电脑", style = MaterialTheme.typography.labelLarge)
                                Text(
                                    if (handoffRecord != null) {
                                        "请从手机聊天页继续。完成后归还，电脑只会合并接管后新增的消息。"
                                    } else {
                                        "当前手机没有对应接管令牌，可能由另一台手机接管；只能在电脑端强制收回。"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (handoffRecord != null) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton(
                                            onClick = { viewModel.openLocalDesktopHandoff(active.id) },
                                            enabled = !handoffBusy,
                                            modifier = Modifier.weight(1f)
                                        ) { Text("打开手机会话") }
                                        Button(
                                            onClick = { viewModel.returnDesktopSession(active.id) },
                                            enabled = state.connected && state.controlAllowed && !handoffBusy,
                                            modifier = Modifier.weight(1f)
                                        ) { Text(if (handoffBusy) "处理中" else "归还电脑") }
                                    }
                                    TextButton(
                                        onClick = { confirmAbandonSessionId = active.id },
                                        enabled = state.connected && state.controlAllowed && !handoffBusy
                                    ) { Text("放弃手机副本并恢复电脑") }
                                }
                            }
                        }
                    } else if (state.connected && state.controlAllowed && !active.running && active.pendingApproval == null && active.pendingAsk == null) {
                        FilledTonalButton(
                            onClick = { viewModel.takeOverDesktopSession(active.id) },
                            enabled = !handoffBusy,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(if (handoffBusy) "正在安全接管…" else "接管到手机继续") }
                    }

                    active.pendingApproval?.let { approval ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text("等待电脑工具审批 · ${approval.toolName}", style = MaterialTheme.typography.labelLarge)
                                Text(approval.summary, style = MaterialTheme.typography.bodyMedium)
                                if (approval.detail.isNotBlank()) {
                                    Text(
                                        approval.detail,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (state.connected && state.controlAllowed) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton(
                                            onClick = { viewModel.decideDesktopApproval(active.id, approval.id, false) },
                                            modifier = Modifier.weight(1f)
                                        ) { Text("拒绝") }
                                        Button(
                                            onClick = { viewModel.decideDesktopApproval(active.id, approval.id, true) },
                                            modifier = Modifier.weight(1f)
                                        ) { Text("批准") }
                                    }
                                }
                            }
                        }
                    }

                    active.pendingAsk?.let { ask ->
                        val canAnswer = state.connected && state.controlAllowed
                        val complete = ask.questions.all { question ->
                            askCustomAnswers[question.id].orEmpty().isNotBlank() || askSelections[question.id].orEmpty().isNotEmpty()
                        }
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text("电脑任务需要你的决定", style = MaterialTheme.typography.labelLarge)
                                ask.questions.forEachIndexed { index, question ->
                                    Column(
                                        modifier = Modifier.fillMaxWidth()
                                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
                                            .padding(10.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            question.header.ifBlank { "问题 ${index + 1}" },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Text(question.question, style = MaterialTheme.typography.bodyMedium)
                                        question.options.forEach { option ->
                                            val selected = option.label in askSelections[question.id].orEmpty()
                                            Row(
                                                modifier = Modifier.fillMaxWidth().clickable(enabled = canAnswer) {
                                                    val current = askSelections[question.id].orEmpty()
                                                    val next = if (question.multiSelect) {
                                                        if (selected) current - option.label else current + option.label
                                                    } else {
                                                        setOf(option.label)
                                                    }
                                                    askSelections = askSelections + (question.id to next)
                                                    askCustomAnswers = askCustomAnswers - question.id
                                                }.padding(vertical = 2.dp),
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                if (question.multiSelect) {
                                                    Checkbox(
                                                        checked = selected,
                                                        onCheckedChange = null,
                                                        enabled = canAnswer
                                                    )
                                                } else {
                                                    RadioButton(
                                                        selected = selected,
                                                        onClick = null,
                                                        enabled = canAnswer
                                                    )
                                                }
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(option.label, style = MaterialTheme.typography.bodySmall)
                                                    if (option.description.isNotBlank()) {
                                                        Text(
                                                            option.description,
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                        OutlinedTextField(
                                            value = askCustomAnswers[question.id].orEmpty(),
                                            onValueChange = { value ->
                                                askCustomAnswers = askCustomAnswers + (question.id to value.take(500))
                                                if (value.isNotBlank()) askSelections = askSelections - question.id
                                            },
                                            modifier = Modifier.fillMaxWidth(),
                                            enabled = canAnswer,
                                            singleLine = true,
                                            label = { Text("自定义答案") }
                                        )
                                    }
                                }
                                if (canAnswer) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton(
                                            onClick = { viewModel.answerDesktopQuestion(active.id, ask.id, emptyList(), true) },
                                            modifier = Modifier.weight(1f)
                                        ) { Text("按默认假设继续") }
                                        Button(
                                            onClick = {
                                                val answers = ask.questions.map { question ->
                                                    val custom = askCustomAnswers[question.id].orEmpty().trim()
                                                    LanWebDesktopAgentAskAnswer(
                                                        questionId = question.id,
                                                        selectedOptions = if (custom.isNotEmpty()) listOf(custom) else askSelections[question.id].orEmpty().toList()
                                                    )
                                                }
                                                viewModel.answerDesktopQuestion(active.id, ask.id, answers, false)
                                            },
                                            enabled = complete,
                                            modifier = Modifier.weight(1f)
                                        ) { Text("提交答案") }
                                    }
                                } else {
                                    Text(
                                        "当前只能查看问题；请在电脑端回答，或让电脑开启手机控制。",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(active.messages, key = { it.id }) { message ->
                            DesktopMessageCard(message)
                        }
                        if (active.messages.isEmpty() && active.messageCount > 0) {
                            item(key = "mirror-not-cached") {
                                Text(
                                    if (state.connected) "正在从 $sourceLabel 读取这项任务的聊天记录…" else "这项任务的详细聊天尚未缓存；重新连接 $sourceLabel 后打开即可同步。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    if (state.connected && state.controlAllowed && active.executionOwner == "desktop") {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = composerText,
                                onValueChange = { composerText = it.take(20_000) },
                                modifier = Modifier.weight(1f),
                                enabled = !active.running,
                                placeholder = { Text(if (active.running) "电脑任务运行中" else "从手机继续电脑任务") },
                                maxLines = 4
                            )
                            Button(
                                onClick = {
                                    val content = composerText.trim()
                                    if (content.isNotEmpty()) {
                                        viewModel.sendDesktopMessage(active.id, content)
                                        composerText = ""
                                    }
                                },
                                enabled = composerText.isNotBlank() && !active.running
                            ) { Text("发送") }
                        }
                    }
                }
            }
    }
}

@Composable
private fun DesktopWorkflowPlanCard(
    plan: LanWebDesktopAgentWorkflowPlan,
    connected: Boolean
) {
    val total = plan.steps.size
    val current = plan.currentStepIndex.coerceIn(0, total)
    val statusLabel = when (plan.status) {
        "ready" -> "待电脑确认"
        "executing" -> "电脑执行中"
        "blocked" -> "等待电脑继续"
        "completed" -> "已完成"
        else -> plan.status
    }
    val signOffs = plan.stepSignOffs.associateBy { it.stepIndex }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("电脑规范计划", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(plan.summary, style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    "$statusLabel · $current/$total",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            LinearProgressIndicator(
                progress = { if (total == 0) 0f else current.toFloat() / total.toFloat() },
                modifier = Modifier.fillMaxWidth()
            )
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 210.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                itemsIndexed(plan.steps, key = { index, _ -> "${plan.id}-$index" }) { index, step ->
                    val completed = index < current
                    val isCurrent = index == current && plan.status != "completed"
                    val background = when {
                        completed -> MaterialTheme.colorScheme.tertiaryContainer
                        isCurrent -> MaterialTheme.colorScheme.primaryContainer
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    }
                    val signOff = signOffs[index]
                    Column(
                        modifier = Modifier.fillMaxWidth()
                            .background(background, RoundedCornerShape(10.dp))
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Text(
                            "${if (completed) "✓" else index + 1}. $step",
                            style = MaterialTheme.typography.bodySmall
                        )
                        signOff?.let {
                            val tools = it.matchedToolNames.joinToString("、").ifBlank { "工具收据" }
                            Text(
                                "${it.resultSummary} · ${it.matchedEvidence}/${it.totalEvidence} 条证据 · $tools",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (isCurrent && signOff == null) {
                            Text(
                                "当前步骤，完成后由电脑 Agent 使用真实工具收据签收",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
            if (plan.nextStepHint.isNotBlank()) {
                Text(plan.nextStepHint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!connected) {
                Text(
                    "电脑已断开；这里显示最后一次安全同步的计划进度。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun DesktopMessageCard(message: LanWebDesktopAgentMessage) {
    val isUser = message.role == "user"
    val background = when {
        isUser -> MaterialTheme.colorScheme.primaryContainer
        message.role == "tool" -> MaterialTheme.colorScheme.surfaceVariant
        message.kind == "error" -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.surface
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().background(background).padding(11.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                when (message.role) {
                    "user" -> "你 · 电脑任务"
                    "assistant" -> "Murong · 电脑端"
                    "tool" -> message.toolName?.let { "工具 · $it" } ?: "工具"
                    else -> message.role
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (message.attachmentNames.isNotEmpty()) {
                Text(
                    text = "图片 · " + message.attachmentNames.joinToString("、"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(message.content, style = MaterialTheme.typography.bodyMedium, color = Color.Unspecified)
        }
    }
}

private fun taskLabel(title: String, running: Boolean, approval: Boolean, question: Boolean, executionOwner: String): String = buildString {
    append(title.ifBlank { "未命名任务" })
    if (executionOwner == "android") append(" · 手机接管")
    else if (approval) append(" · 待审批")
    else if (question) append(" · 待回答")
    else if (running) append(" · 运行中")
}
