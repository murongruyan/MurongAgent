package com.murong.agent.backup

import com.murong.agent.lan.LanWebCredentialSyncBundle
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class MurongPortableBackupSession(
    val sourceSessionId: String,
    val document: JsonObject
)

@Serializable
data class MurongPortableBackupState(
    val schemaVersion: Int = 1,
    val sourcePlatform: String,
    val generatedAt: Long,
    val deviceState: LanWebCredentialSyncBundle,
    val sessions: List<MurongPortableBackupSession> = emptyList()
)

internal fun validatePortableBackupEnvelope(state: MurongPortableBackupState) {
    require(state.schemaVersion == 1) { "跨端备份状态版本不受支持" }
    require(state.sourcePlatform in PORTABLE_BACKUP_PLATFORMS) { "跨端备份来源平台无效" }
    require(state.generatedAt > 0L && state.generatedAt == state.deviceState.generatedAt) { "跨端备份状态时间不一致" }
    require(state.deviceState.sourcePlatform == state.sourcePlatform) { "跨端备份配置来源不一致" }
    require(state.deviceState.codexAuthJson == null) { "跨端备份不得包含 Codex 登录" }
    require(state.deviceState.providers.all { it.apiKey == null }) { "跨端备份不得包含 API Key" }
    require(state.deviceState.github?.token == null && state.deviceState.github?.viewerLogin.orEmpty().isBlank()) {
        "跨端备份不得包含 GitHub 登录状态"
    }
    require(!state.deviceState.mcpCredentialsIncluded) { "跨端备份不得声明 MCP 凭据" }
    require(state.deviceState.mcpServers.all { it.environment.isEmpty() && it.headers.isEmpty() }) {
        "跨端备份不得包含 MCP 环境变量或请求头"
    }
    require(state.sessions.size <= 10_000) { "跨端备份会话数量超过上限" }
    require(state.sessions.map { it.sourceSessionId }.distinct().size == state.sessions.size) { "跨端备份会话 ID 重复" }
    require(state.sessions.all {
        it.sourceSessionId.isNotBlank() && it.sourceSessionId.length <= 500 && it.sourceSessionId.none(Char::isISOControl)
    }) { "跨端备份会话 ID 无效" }
}

internal val PORTABLE_BACKUP_PLATFORMS = setOf("android", "windows", "darwin", "linux", "desktop")
