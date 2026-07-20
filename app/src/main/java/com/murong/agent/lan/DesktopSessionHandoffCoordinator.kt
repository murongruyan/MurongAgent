package com.murong.agent.lan

import android.content.Context
import com.murong.agent.core.loop.ChatSessionManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class DesktopSessionHandoffRecord(
    val version: Int = HANDOFF_RECORD_VERSION,
    val desktopSessionId: String,
    val desktopTitle: String,
    val localSessionId: String,
    val handoffToken: String,
    val sourcePlatform: String,
    val sourceArchitecture: String,
    val startedAt: Long
)

data class DesktopSessionHandoffState(
    val records: List<DesktopSessionHandoffRecord> = emptyList(),
    val busySessionId: String? = null,
    val notice: String? = null
) {
    fun recordFor(desktopSessionId: String): DesktopSessionHandoffRecord? =
        records.firstOrNull { it.desktopSessionId == desktopSessionId }
}

@Serializable
private data class DesktopSessionHandoffDocument(
    val schemaVersion: Int = HANDOFF_RECORD_VERSION,
    val records: List<DesktopSessionHandoffRecord> = emptyList()
)

internal class DesktopSessionHandoffStore internal constructor(
    private val stateFile: File
) {
    constructor(context: Context) : this(File(context.filesDir, "lan/desktop_session_handoffs_v1.json"))

    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }

    fun load(): List<DesktopSessionHandoffRecord> = synchronized(lock) {
        if (!stateFile.isFile || stateFile.length() !in 1..MAX_HANDOFF_STATE_BYTES.toLong()) return emptyList()
        runCatching {
            val document = json.decodeFromString<DesktopSessionHandoffDocument>(stateFile.readText())
            require(document.schemaVersion == HANDOFF_RECORD_VERSION) { "接管记录版本不受支持" }
            document.records.also(::validateRecords)
        }.getOrDefault(emptyList())
    }

    fun put(record: DesktopSessionHandoffRecord): List<DesktopSessionHandoffRecord> = synchronized(lock) {
        validateRecord(record)
        val updated = load()
            .filterNot { it.desktopSessionId == record.desktopSessionId }
            .plus(record)
            .sortedByDescending { it.startedAt }
        require(updated.size <= MAX_HANDOFF_RECORDS) { "手机接管任务数量超过上限" }
        write(updated)
        updated
    }

    fun remove(desktopSessionId: String): List<DesktopSessionHandoffRecord> = synchronized(lock) {
        val updated = load().filterNot { it.desktopSessionId == desktopSessionId }
        write(updated)
        updated
    }

    private fun write(records: List<DesktopSessionHandoffRecord>) {
        validateRecords(records)
        stateFile.parentFile?.mkdirs()
        val encoded = json.encodeToString(DesktopSessionHandoffDocument(records = records))
        require(encoded.toByteArray(Charsets.UTF_8).size <= MAX_HANDOFF_STATE_BYTES) { "接管记录文件过大" }
        val temporary = File(stateFile.parentFile, stateFile.name + ".tmp")
        FileOutputStream(temporary).use { output ->
            output.write(encoded.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        if (stateFile.exists() && !stateFile.delete()) error("无法替换接管记录")
        if (!temporary.renameTo(stateFile)) error("无法提交接管记录")
    }

    private fun validateRecords(records: List<DesktopSessionHandoffRecord>) {
        require(records.size <= MAX_HANDOFF_RECORDS) { "手机接管任务数量超过上限" }
        require(records.map { it.desktopSessionId }.distinct().size == records.size) { "电脑任务接管记录重复" }
        require(records.map { it.localSessionId }.distinct().size == records.size) { "手机会话接管记录重复" }
        records.forEach(::validateRecord)
    }

    private fun validateRecord(record: DesktopSessionHandoffRecord) {
        require(record.version == HANDOFF_RECORD_VERSION) { "接管记录版本无效" }
        require(LanWebContract.sessionIdPattern.matches(record.desktopSessionId)) { "电脑任务 ID 无效" }
        require(record.localSessionId.matches(Regex("^[A-Za-z0-9_-]{4,128}$"))) { "手机会话 ID 无效" }
        require(record.handoffToken.matches(HANDOFF_TOKEN_PATTERN)) { "接管令牌无效" }
        require(record.desktopTitle.length <= 200 && record.startedAt > 0L) { "接管记录元数据无效" }
        require(normalizeDesktopPlatform(record.sourcePlatform) == record.sourcePlatform) { "接管来源系统无效" }
        require(normalizeDesktopArchitecture(record.sourceArchitecture) == record.sourceArchitecture) { "接管来源架构无效" }
    }
}

@Singleton
class DesktopSessionHandoffCoordinator @Inject constructor(
    @param:ApplicationContext context: Context,
    private val desktopBridge: LanWebDesktopAgentBridge,
    private val sessionManager: ChatSessionManager
) {
    private val store = DesktopSessionHandoffStore(context)
    private val operationMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow(DesktopSessionHandoffState(records = store.load()))
    val state: StateFlow<DesktopSessionHandoffState> = mutableState.asStateFlow()

    init {
        scope.launch {
            desktopBridge.state.collectLatest { status ->
                operationMutex.withLock {
                    reconcileDesktopAuthority(status)
                }
            }
        }
    }

    suspend fun takeOver(desktopSessionId: String): Result<DesktopSessionHandoffRecord> = operationMutex.withLock {
        runCatching {
            val existing = mutableState.value.recordFor(desktopSessionId)
            if (existing != null) {
                require(sessionManager.loadSession(existing.localSessionId)) { "手机接管会话不存在" }
                return@runCatching existing
            }
            val desktopState = desktopBridge.state.value
            val desktopSession = desktopState.snapshot?.activeSession
                ?.takeIf { it.id == desktopSessionId }
                ?: error("请先打开要接管的电脑任务")
            require(desktopSession.executionOwner == "desktop") { "这项电脑任务已经交给手机" }
            mutableState.value = mutableState.value.copy(busySessionId = desktopSessionId, notice = null)
            val response = desktopBridge.command("begin_handoff", sessionId = desktopSessionId).getOrThrow()
            require(response.success) { response.errorMessage ?: "电脑拒绝交出任务执行权" }
            val token = response.handoffToken ?: error("电脑没有返回接管令牌")
            val portable = response.portableSession ?: error("电脑没有返回跨端会话")
            val imported = try {
                sessionManager.importPortableHandoff(portable, desktopSession.title)
            } catch (error: Throwable) {
                desktopBridge.command(
                    operation = "abort_handoff",
                    sessionId = desktopSessionId,
                    handoffToken = token
                )
                throw error
            }
            val record = DesktopSessionHandoffRecord(
                desktopSessionId = desktopSessionId,
                desktopTitle = desktopSession.title,
                localSessionId = imported.sessionId,
                handoffToken = token,
                sourcePlatform = desktopState.sourcePlatform ?: "windows",
                sourceArchitecture = desktopState.sourceArchitecture ?: "amd64",
                startedAt = response.session?.handoffStartedAt ?: System.currentTimeMillis()
            )
            try {
                val records = store.put(record)
                mutableState.value = DesktopSessionHandoffState(
                    records = records,
                    notice = "已接管到手机；关闭电脑任务窗口后，从聊天页继续。"
                )
            } catch (error: Throwable) {
                sessionManager.discardPortableHandoffSession(imported.sessionId)
                desktopBridge.command(
                    operation = "abort_handoff",
                    sessionId = desktopSessionId,
                    handoffToken = token
                )
                throw error
            }
            record
        }.also {
            if (it.isFailure) mutableState.value = mutableState.value.copy(busySessionId = null)
        }
    }

    suspend fun returnToDesktop(desktopSessionId: String): Result<Unit> = operationMutex.withLock {
        runCatching {
            val record = mutableState.value.recordFor(desktopSessionId)
                ?: error("没有找到这项任务的手机接管记录")
            mutableState.value = mutableState.value.copy(busySessionId = desktopSessionId, notice = null)
            val portable = sessionManager.exportPortableHandoff(record.localSessionId).getOrThrow()
            require(portable.toByteArray(Charsets.UTF_8).size <= LanWebContract.MAX_DESKTOP_HANDOFF_BYTES) {
                "手机任务超过 1.5 MiB；请先压缩上下文或分叉较短的任务"
            }
            val response = desktopBridge.command(
                operation = "return_handoff",
                sessionId = desktopSessionId,
                handoffToken = record.handoffToken,
                portableSession = portable
            ).getOrThrow()
            require(response.success) { response.errorMessage ?: "电脑拒绝合并手机任务" }
            val records = store.remove(desktopSessionId)
            val discarded = sessionManager.discardPortableHandoffSession(record.localSessionId)
            mutableState.value = DesktopSessionHandoffState(
                records = records,
                notice = if (discarded) {
                    "手机新增记录已安全合并回电脑，执行权已归还。"
                } else {
                    "任务已归还电脑；手机副本未能自动删除，可稍后手动清理。"
                }
            )
            Unit
        }.also {
            if (it.isFailure) mutableState.value = mutableState.value.copy(busySessionId = null)
        }
    }

    suspend fun abandon(desktopSessionId: String): Result<Unit> = operationMutex.withLock {
        runCatching {
            val record = mutableState.value.recordFor(desktopSessionId)
                ?: error("没有找到这项任务的手机接管记录")
            mutableState.value = mutableState.value.copy(busySessionId = desktopSessionId, notice = null)
            val response = desktopBridge.command(
                operation = "abort_handoff",
                sessionId = desktopSessionId,
                handoffToken = record.handoffToken
            ).getOrThrow()
            require(response.success) { response.errorMessage ?: "电脑拒绝撤销接管" }
            val records = store.remove(desktopSessionId)
            sessionManager.discardPortableHandoffSession(record.localSessionId)
            mutableState.value = DesktopSessionHandoffState(
                records = records,
                notice = "已放弃手机副本，电脑任务恢复可写。"
            )
            Unit
        }.also {
            if (it.isFailure) mutableState.value = mutableState.value.copy(busySessionId = null)
        }
    }

    fun openLocal(desktopSessionId: String): Result<Unit> = runCatching {
        val record = mutableState.value.recordFor(desktopSessionId)
            ?: error("没有找到这项任务的手机接管记录")
        require(sessionManager.loadSession(record.localSessionId)) { "手机接管会话不存在" }
        mutableState.value = mutableState.value.copy(notice = "已切换到手机接管会话。")
    }

    private fun reconcileDesktopAuthority(status: LanWebDesktopAgentStatusResponse) {
        val current = mutableState.value
        if (!status.connected || current.records.isEmpty()) return
        val summaries = status.snapshot?.sessions.orEmpty().associateBy { it.id }
        val reclaimed = current.records.filter { record ->
            summaries[record.desktopSessionId]?.executionOwner == "desktop"
        }
        if (reclaimed.isEmpty()) return
        var records = current.records
        reclaimed.forEach { record ->
            records = store.remove(record.desktopSessionId)
        }
        mutableState.value = DesktopSessionHandoffState(
            records = records,
            notice = "电脑已强制收回执行权；手机副本保留为独立会话，未自动删除。"
        )
    }
}

private const val HANDOFF_RECORD_VERSION = 1
private const val MAX_HANDOFF_RECORDS = 64
private const val MAX_HANDOFF_STATE_BYTES = 256 * 1024
private val HANDOFF_TOKEN_PATTERN = Regex("^handoff-[0-9a-f]{64}$")
