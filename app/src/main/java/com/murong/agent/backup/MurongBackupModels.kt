package com.murong.agent.backup

import kotlinx.serialization.Serializable

const val MURONG_BACKUP_FORMAT = "murong-backup"
const val MURONG_BACKUP_FORMAT_VERSION = 2
const val MURONG_BACKUP_MIN_SUPPORTED_VERSION = 1
const val MURONG_BACKUP_EXTENSION = "zip"

@Serializable
enum class MurongBackupKind { MANUAL, AUTOMATIC, PRE_RESTORE }

@Serializable
enum class MurongBackupCategory {
    CONVERSATIONS,
    CONVERSATION_MEDIA,
    MEMORIES,
    PROVIDER_SETTINGS,
    MCP_CONFIG,
    SAVED_WORKFLOWS,
    VOICE_SETTINGS,
    UI_SETTINGS,
    BACKUP_SETTINGS,
    PROJECT_AUDIT,
    PORTABLE_STATE
}

@Serializable
data class MurongBackupEntry(
    val path: String,
    val category: MurongBackupCategory,
    val sizeBytes: Long,
    val sha256: String
)

@Serializable
data class MurongBackupManifest(
    val format: String = MURONG_BACKUP_FORMAT,
    val formatVersion: Int = MURONG_BACKUP_FORMAT_VERSION,
    val createdAtEpochMillis: Long,
    val appVersionName: String,
    val appVersionCode: Int,
    val kind: MurongBackupKind,
    val entries: List<MurongBackupEntry>,
    val excludedByDefault: List<String> = DEFAULT_BACKUP_EXCLUSIONS
)

@Serializable
data class MurongUiPreferencesSnapshot(
    val strings: Map<String, String> = emptyMap(),
    val ints: Map<String, Int> = emptyMap(),
    val longs: Map<String, Long> = emptyMap(),
    val floats: Map<String, Float> = emptyMap(),
    val booleans: Map<String, Boolean> = emptyMap(),
    val stringSets: Map<String, Set<String>> = emptyMap()
)

@Serializable
data class MurongBackupSettingsSnapshot(
    val dailyBackupEnabled: Boolean = false,
    val maxBackupCount: Int = 7
)

data class MurongBackupStatus(
    val settings: MurongBackupSettingsSnapshot = MurongBackupSettingsSnapshot(),
    val lastBackupAt: Long? = null,
    val lastBackupMessage: String? = null,
    val lastBackupFailed: Boolean = false,
    val automaticBackupCount: Int = 0,
    val preRestoreSnapshotCount: Int = 0,
    val latestPreRestoreSnapshotName: String? = null,
    val storageLocation: String = "",
    val usesDurablePublicStorage: Boolean = false
)

data class MurongBackupOperationResult(
    val manifest: MurongBackupManifest? = null,
    val message: String,
    val restoredEntryCount: Int = 0,
    val preRestoreSnapshotName: String? = null,
    val restartRequired: Boolean = false,
    val skipped: Boolean = false
)

val DEFAULT_BACKUP_EXCLUSIONS = listOf(
    "API Key 与安全存储中的 MCP 鉴权值",
    "Codex/ChatGPT 与 GitHub 登录状态",
    "语音离线模型",
    "终端扩展、工具链、Shell 历史和终端日志",
    "缓存、WebView、构建产物、分析统计与崩溃诊断"
)
