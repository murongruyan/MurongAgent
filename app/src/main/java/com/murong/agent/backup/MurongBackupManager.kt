package com.murong.agent.backup

import android.content.Context
import android.net.Uri
import android.os.Environment
import com.murong.agent.automation.SavedWorkflowScheduler
import com.murong.agent.core.automation.SavedWorkflowDefinition
import com.murong.agent.core.config.ConfigBackupSnapshot
import com.murong.agent.core.config.ConfigRepository
import com.murong.agent.core.config.ProviderConfig
import com.murong.agent.core.codex.CodexAppServerClient
import com.murong.agent.core.loop.PortableConversationBackupRecord
import com.murong.agent.core.loop.PortableConversationBackupStore
import com.murong.agent.lan.LanWebCredentialSyncBridge
import com.murong.agent.core.mcp.McpRegistry
import com.murong.agent.core.mcp.McpServerConfig
import com.murong.agent.core.voice.VoiceSettings
import com.murong.agent.voice.VoiceSettingsRepository
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class MurongBackupManager(
    context: Context,
    private val configRepository: ConfigRepository,
    private val mcpRegistry: McpRegistry,
    credentialSyncBridge: LanWebCredentialSyncBridge? = null
) {
    private val appContext = context.applicationContext
    private val preferences = MurongBackupPreferences(appContext)
    private val workflowScheduler = SavedWorkflowScheduler(appContext, reconcileInterruptedRuns = false)
    private val credentialSyncBridge = credentialSyncBridge ?: LanWebCredentialSyncBridge(
        appContext,
        configRepository,
        CodexAppServerClient(appContext),
        mcpRegistry
    )
    private val portableConversationStore = PortableConversationBackupStore(appContext)
    private val json = Json { ignoreUnknownKeys = false; prettyPrint = true; encodeDefaults = true }
    private val legacyBackupRoot = File(appContext.filesDir, "backups")
    private val backupRoot = resolveBackupRoot()
    private val automaticBackupDir = File(backupRoot, "automatic")
    private val preRestoreBackupDir = File(backupRoot, "pre_restore")
    private val legacyAutomaticBackupDir = File(legacyBackupRoot, "automatic")
    private val legacyPreRestoreBackupDir = File(legacyBackupRoot, "pre_restore")
    private val workRoot = File(appContext.cacheDir, "backup-work")

    init {
        runCatching(::migrateLegacyInternalBackups)
    }

    fun suggestedManualFileName(now: LocalDateTime = LocalDateTime.now()): String {
        return "murong_backup_${now.format(FILE_TIMESTAMP)}.$MURONG_BACKUP_EXTENSION"
    }

    fun status(): MurongBackupStatus {
        val automatic = listBackupFiles(automaticBackupDir, legacyAutomaticBackupDir)
        val snapshots = listBackupFiles(preRestoreBackupDir, legacyPreRestoreBackupDir)
        return MurongBackupStatus(
            settings = preferences.settings(),
            lastBackupAt = preferences.lastResultAt(),
            lastBackupMessage = preferences.lastResultMessage(),
            lastBackupFailed = preferences.lastResultFailed(),
            automaticBackupCount = automatic.size,
            preRestoreSnapshotCount = snapshots.size,
            latestPreRestoreSnapshotName = snapshots.firstOrNull()?.name,
            storageLocation = backupRoot.absolutePath,
            usesDurablePublicStorage = backupRoot != legacyBackupRoot
        )
    }

    suspend fun updateSettings(settings: MurongBackupSettingsSnapshot): MurongBackupStatus = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            val normalized = settings.copy(maxBackupCount = settings.maxBackupCount.coerceIn(1, 100))
            preferences.restoreSettings(normalized)
            pruneInternalBackups(normalized.maxBackupCount)
            MurongBackupScheduler.applySettings(appContext, normalized.dailyBackupEnabled)
            status()
        }
    }

    suspend fun exportManualBackup(uri: Uri): MurongBackupOperationResult = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            val temp = File(workRoot, "manual-${UUID.randomUUID()}.$MURONG_BACKUP_EXTENSION")
            try {
                val manifest = createArchiveFileUnlocked(temp, MurongBackupKind.MANUAL)
                val output = appContext.contentResolver.openOutputStream(uri, "wt")
                    ?: error("无法打开备份保存位置")
                output.use { destination ->
                    BufferedInputStream(temp.inputStream()).use { source -> source.copyTo(destination) }
                }
                val message = "手动备份完成，共 ${manifest.entries.size} 个条目"
                preferences.markResult(System.currentTimeMillis(), message, failed = false)
                MurongBackupOperationResult(manifest = manifest, message = message)
            } catch (error: Throwable) {
                preferences.markResult(System.currentTimeMillis(), error.message ?: "手动备份失败", failed = true)
                throw error
            } finally {
                temp.delete()
            }
        }
    }

    suspend fun createAutomaticBackupIfNeeded(): MurongBackupOperationResult = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            val settings = preferences.settings()
            if (!settings.dailyBackupEnabled) {
                return@withLock MurongBackupOperationResult(message = "每日备份未启用", skipped = true)
            }
            val day = LocalDate.now().format(DateTimeFormatter.ISO_DATE)
            if (preferences.lastAutomaticBackupDay() == day) {
                return@withLock MurongBackupOperationResult(message = "今天已经完成过自动备份", skipped = true)
            }
            val target = File(
                automaticBackupDir,
                "murong_auto_${LocalDateTime.now().format(FILE_TIMESTAMP)}.$MURONG_BACKUP_EXTENSION"
            )
            try {
                val manifest = createArchiveFileUnlocked(target, MurongBackupKind.AUTOMATIC)
                preferences.markAutomaticBackupDay(day)
                pruneInternalBackups(settings.maxBackupCount)
                val message = "每日备份完成，共 ${manifest.entries.size} 个条目"
                preferences.markResult(System.currentTimeMillis(), message, failed = false)
                MurongBackupOperationResult(manifest = manifest, message = message)
            } catch (error: Throwable) {
                preferences.markResult(System.currentTimeMillis(), error.message ?: "每日备份失败", failed = true)
                throw error
            }
        }
    }

    suspend fun restoreFromUri(uri: Uri): MurongBackupOperationResult = withContext(Dispatchers.IO) {
        val imported = File(workRoot, "import-${UUID.randomUUID()}.$MURONG_BACKUP_EXTENSION")
        workRoot.mkdirs()
        try {
            val input = appContext.contentResolver.openInputStream(uri) ?: error("无法打开备份包")
            input.use { source -> copyCompressedBackupWithLimit(source, imported) }
            operationMutex.withLock { restoreFileUnlocked(imported) }
        } catch (error: Throwable) {
            preferences.markResult(System.currentTimeMillis(), error.message ?: "恢复失败", failed = true)
            throw error
        } finally {
            imported.delete()
        }
    }

    private suspend fun restoreFileUnlocked(archive: File): MurongBackupOperationResult {
        val staging = File(workRoot, "restore-${UUID.randomUUID()}")
        try {
            val validated = archive.inputStream().use { input ->
                MurongBackupArchive.extractAndValidate(input, staging)
            }
            val restoreState = decodeAndValidateRestoreState(validated)
            val snapshotFile = File(
                preRestoreBackupDir,
                "murong_pre_restore_${LocalDateTime.now().format(FILE_TIMESTAMP)}.$MURONG_BACKUP_EXTENSION"
            )
            createArchiveFileUnlocked(snapshotFile, MurongBackupKind.PRE_RESTORE)
            pruneInternalBackups(preferences.settings().maxBackupCount)
            val applyResult = applyRestoreTransaction(validated.payloadRoot, restoreState)
            val message = if (applyResult.crossPlatform) {
                buildString {
                    append("跨系统恢复完成：合并 ")
                    append(applyResult.importedSessions)
                    append(" 个会话")
                    if (applyResult.conflictCopies > 0) append("，保留 ${applyResult.conflictCopies} 个冲突副本")
                    if (applyResult.skippedSessions > 0) append("，跳过 ${applyResult.skippedSessions} 个重复或已删除会话")
                    append("；本机路径、凭据和设备运行态保持不变，重启 Murong 后全部生效")
                }
            } else {
                "恢复完成，共 ${validated.manifest.entries.size} 个条目；重启 Murong 后全部生效"
            }
            preferences.markResult(System.currentTimeMillis(), message, failed = false)
            return MurongBackupOperationResult(
                manifest = validated.manifest,
                message = message,
                restoredEntryCount = validated.manifest.entries.size,
                preRestoreSnapshotName = snapshotFile.name,
                restartRequired = true
            )
        } finally {
            staging.deleteRecursively()
        }
    }

    private suspend fun createArchiveFileUnlocked(target: File, kind: MurongBackupKind): MurongBackupManifest {
        target.parentFile?.mkdirs()
        val staging = File(workRoot, "create-${UUID.randomUUID()}")
        val temporary = File(target.parentFile, "${target.name}.tmp-${UUID.randomUUID()}")
        try {
            val payloads = materializePayloads(staging)
            val packageInfo = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
            val manifest = temporary.outputStream().use { output ->
                MurongBackupArchive.write(
                    output = output,
                    createdAtEpochMillis = System.currentTimeMillis(),
                    appVersionName = packageInfo.versionName.orEmpty().ifBlank { "unknown" },
                    appVersionCode = packageInfo.longVersionCode.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                    kind = kind,
                    payloads = payloads
                )
            }
            if (target.exists()) require(target.delete()) { "无法替换旧备份文件" }
            if (!temporary.renameTo(target)) {
                temporary.copyTo(target, overwrite = true)
                require(temporary.delete()) { "无法清理备份临时文件" }
            }
            return manifest
        } finally {
            staging.deleteRecursively()
            temporary.delete()
        }
    }

    private suspend fun materializePayloads(staging: File): List<MurongBackupPayload> {
        staging.mkdirs()
        val payloads = mutableListOf<MurongBackupPayload>()
        fun writeState(path: String, category: MurongBackupCategory, content: String) {
            val file = File(staging, path)
            file.parentFile?.mkdirs()
            file.writeText(content)
            payloads += MurongBackupPayload(path, category, file)
        }

        writeState(
            PROVIDER_SETTINGS_PATH,
            MurongBackupCategory.PROVIDER_SETTINGS,
            json.encodeToString(ConfigBackupSnapshot.serializer(), configRepository.exportBackupSnapshot())
        )
        writeState(
            MCP_CONFIG_PATH,
            MurongBackupCategory.MCP_CONFIG,
            json.encodeToString(ListSerializer(McpServerConfig.serializer()), mcpRegistry.exportBackupConfigs())
        )
        writeState(
            SAVED_WORKFLOWS_PATH,
            MurongBackupCategory.SAVED_WORKFLOWS,
            json.encodeToString(
                ListSerializer(SavedWorkflowDefinition.serializer()),
                workflowScheduler.list()
            )
        )
        writeState(
            VOICE_SETTINGS_PATH,
            MurongBackupCategory.VOICE_SETTINGS,
            json.encodeToString(VoiceSettings.serializer(), captureVoiceSettings())
        )
        writeState(
            UI_SETTINGS_PATH,
            MurongBackupCategory.UI_SETTINGS,
            json.encodeToString(MurongUiPreferencesSnapshot.serializer(), captureUiPreferences())
        )
        writeState(
            BACKUP_SETTINGS_PATH,
            MurongBackupCategory.BACKUP_SETTINGS,
            json.encodeToString(MurongBackupSettingsSnapshot.serializer(), preferences.settings())
        )
        val portableGeneratedAt = System.currentTimeMillis()
        val portableState = MurongPortableBackupState(
            sourcePlatform = "android",
            generatedAt = portableGeneratedAt,
            deviceState = credentialSyncBridge.exportPortableBackupBundle(portableGeneratedAt),
            sessions = portableConversationStore.exportAll().map { record ->
                MurongPortableBackupSession(
                    sourceSessionId = record.sourceSessionId,
                    document = json.parseToJsonElement(record.portableJson).jsonObject
                )
            }
        )
        validatePortableState(portableState)
        writeState(
            PORTABLE_STATE_PATH,
            MurongBackupCategory.PORTABLE_STATE,
            json.encodeToString(MurongPortableBackupState.serializer(), portableState)
        )
        copyDataTree(File(appContext.filesDir, "conversations"), staging, "data/conversations", MurongBackupCategory.CONVERSATIONS, payloads)
        copyDataTree(File(appContext.filesDir, "conversation_media"), staging, "data/conversation_media", MurongBackupCategory.CONVERSATION_MEDIA, payloads)
        copyDataTree(File(appContext.filesDir, "memories"), staging, "data/memories", MurongBackupCategory.MEMORIES, payloads)
        return payloads
    }

    private fun copyDataTree(
        sourceRoot: File,
        staging: File,
        destinationPrefix: String,
        category: MurongBackupCategory,
        payloads: MutableList<MurongBackupPayload>
    ) {
        if (!sourceRoot.isDirectory) return
        val canonicalRoot = sourceRoot.canonicalFile
        val canonicalPrefix = canonicalRoot.path.trimEnd(File.separatorChar) + File.separator
        sourceRoot.walkTopDown()
            .filter { it.isFile && !it.name.endsWith(".tmp") }
            .forEach { source ->
                val canonicalSource = source.canonicalFile
                require(canonicalSource.path.startsWith(canonicalPrefix)) { "备份源包含越界符号链接" }
                val relative = canonicalSource.relativeTo(canonicalRoot).invariantSeparatorsPath
                val path = "$destinationPrefix/$relative"
                MurongBackupArchive.validateRelativePath(path)
                val destination = File(staging, path)
                destination.parentFile?.mkdirs()
                BufferedInputStream(canonicalSource.inputStream()).use { input ->
                    BufferedOutputStream(destination.outputStream()).use { output -> input.copyTo(output) }
                }
                payloads += MurongBackupPayload(path, category, destination)
                require(payloads.size <= MurongBackupArchive.MAX_ENTRY_COUNT) { "备份条目数量超过上限" }
            }
    }

    private fun decodeAndValidateRestoreState(validated: ValidatedMurongBackup): RestoreState {
        val root = validated.payloadRoot
        val manifestPaths = validated.manifest.entries.mapTo(hashSetOf(), MurongBackupEntry::path)
        val portableState = if (PORTABLE_STATE_PATH in manifestPaths) {
            runCatching {
                json.decodeFromString(
                    MurongPortableBackupState.serializer(),
                    File(root, PORTABLE_STATE_PATH).readText()
                )
            }.getOrElse { error ->
                throw IllegalArgumentException("跨端备份状态无法解析：${error.message}", error)
            }.also(::validatePortableState)
        } else {
            null
        }
        if (validated.manifest.formatVersion >= 2) {
            require(portableState != null) { "v2 备份缺少跨平台可移植状态" }
        }
        if (portableState != null && portableState.sourcePlatform != "android") {
            return CrossPlatformRestoreState(portableState)
        }
        REQUIRED_STATE_PATHS.forEach { path -> require(path in manifestPaths) { "备份缺少必要状态：$path" } }
        fun read(path: String): String = File(root, path).readText()
        val state = NativeRestoreState(
            config = json.decodeFromString(ConfigBackupSnapshot.serializer(), read(PROVIDER_SETTINGS_PATH)),
            mcpConfigs = json.decodeFromString(ListSerializer(McpServerConfig.serializer()), read(MCP_CONFIG_PATH)),
            workflows = json.decodeFromString(
                ListSerializer(SavedWorkflowDefinition.serializer()),
                read(SAVED_WORKFLOWS_PATH)
            ),
            voiceSettings = json.decodeFromString(VoiceSettings.serializer(), read(VOICE_SETTINGS_PATH)),
            uiPreferences = json.decodeFromString(MurongUiPreferencesSnapshot.serializer(), read(UI_SETTINGS_PATH)),
            backupSettings = json.decodeFromString(MurongBackupSettingsSnapshot.serializer(), read(BACKUP_SETTINGS_PATH))
        )
        configRepository.validateBackupSnapshot(state.config)
        mcpRegistry.validateBackupConfigs(state.mcpConfigs)
        require(state.workflows.size <= 500) { "保存的工作流数量超过上限" }
        require(state.workflows.map { it.id }.distinct().size == state.workflows.size) { "保存的工作流 ID 重复" }
        require(state.workflows.all { it.id.isNotBlank() && it.name.length <= 500 && it.nodes.size <= 100 }) {
            "保存的工作流定义无效或过大"
        }
        require(state.backupSettings.maxBackupCount in 1..100) { "备份保留数量无效" }
        validateUiPreferences(state.uiPreferences)
        return state
    }

    private suspend fun applyRestoreTransaction(payloadRoot: File, restored: RestoreState): RestoreApplyResult {
        if (restored is CrossPlatformRestoreState) {
            return applyCrossPlatformRestore(restored.portableState)
        }
        restored as NativeRestoreState
        val previousConfig: ProviderConfig = configRepository.getConfig()
        val previousInputHistory = configRepository.getInputHistory()
        val previousMcp = mcpRegistry.loadConfigs()
        val previousWorkflows = workflowScheduler.list()
        val previousVoice = captureVoiceSettings()
        val previousUi = captureUiPreferences()
        val previousBackupSettings = preferences.settings()
        val swaps = mutableListOf<DirectorySwap>()
        try {
            swaps += swapDirectory(File(payloadRoot, "data/conversations"), File(appContext.filesDir, "conversations"))
            swaps += swapDirectory(File(payloadRoot, "data/conversation_media"), File(appContext.filesDir, "conversation_media"))
            swaps += swapDirectory(File(payloadRoot, "data/memories"), File(appContext.filesDir, "memories"))
            configRepository.restoreBackupSnapshot(restored.config)
            mcpRegistry.restoreBackupConfigs(restored.mcpConfigs)
            workflowScheduler.restoreAll(restored.workflows)
            restoreVoiceSettings(restored.voiceSettings)
            restoreUiPreferences(restored.uiPreferences)
            preferences.restoreSettings(restored.backupSettings)
            MurongBackupScheduler.applySettings(appContext, restored.backupSettings.dailyBackupEnabled)
            swaps.forEach(DirectorySwap::commit)
            return RestoreApplyResult()
        } catch (restoreError: Throwable) {
            val rollbackErrors = mutableListOf<Throwable>()
            swaps.asReversed().forEach { swap -> runCatching(swap::rollback).exceptionOrNull()?.let(rollbackErrors::add) }
            runCatching {
                configRepository.saveConfig(previousConfig)
                configRepository.saveInputHistory(previousInputHistory)
            }.exceptionOrNull()?.let(rollbackErrors::add)
            runCatching { mcpRegistry.saveConfigs(previousMcp) }.exceptionOrNull()?.let(rollbackErrors::add)
            runCatching { workflowScheduler.restoreAll(previousWorkflows) }.exceptionOrNull()?.let(rollbackErrors::add)
            runCatching { restoreVoiceSettings(previousVoice) }.exceptionOrNull()?.let(rollbackErrors::add)
            runCatching { restoreUiPreferences(previousUi) }.exceptionOrNull()?.let(rollbackErrors::add)
            runCatching {
                preferences.restoreSettings(previousBackupSettings)
                MurongBackupScheduler.applySettings(appContext, previousBackupSettings.dailyBackupEnabled)
            }.exceptionOrNull()?.let(rollbackErrors::add)
            if (rollbackErrors.isNotEmpty()) {
                restoreError.addSuppressed(IllegalStateException("恢复失败，且回滚出现 ${rollbackErrors.size} 个错误"))
                rollbackErrors.forEach(restoreError::addSuppressed)
            }
            throw restoreError
        }
    }

    private suspend fun applyCrossPlatformRestore(portableState: MurongPortableBackupState): RestoreApplyResult {
        val records = portableState.sessions.map { session ->
            PortableConversationBackupRecord(
                sourceSessionId = session.sourceSessionId,
                portableJson = session.document.toString()
            )
        }
        val merged = File(workRoot, "portable-restore-${UUID.randomUUID()}")
        try {
            val result = portableConversationStore.prepareMergedDirectory(
                sourcePlatform = portableState.sourcePlatform,
                records = records,
                destination = merged
            )
            val swap = swapDirectory(merged, File(appContext.filesDir, "conversations"))
            try {
                credentialSyncBridge.importPortableBackupBundle(portableState.deviceState)
                swap.commit()
            } catch (error: Throwable) {
                runCatching(swap::rollback).exceptionOrNull()?.let(error::addSuppressed)
                throw error
            }
            return RestoreApplyResult(
                crossPlatform = true,
                importedSessions = result.importedSessions,
                conflictCopies = result.conflictCopies,
                skippedSessions = result.skippedSessions
            )
        } finally {
            merged.deleteRecursively()
        }
    }

    private fun validatePortableState(state: MurongPortableBackupState) {
        validatePortableBackupEnvelope(state)
        credentialSyncBridge.validatePortableBackupBundle(state.deviceState)
        val records = state.sessions.map { session ->
            PortableConversationBackupRecord(session.sourceSessionId, session.document.toString())
        }
        portableConversationStore.validateRecords(state.sourcePlatform, records)
    }

    private fun swapDirectory(staged: File, target: File): DirectorySwap {
        target.parentFile?.mkdirs()
        val previous = File(target.parentFile, "${target.name}.restore-old-${UUID.randomUUID()}")
        val hadPrevious = target.exists()
        if (hadPrevious) require(target.renameTo(previous)) { "无法暂存现有目录：${target.name}" }
        return try {
            if (staged.exists()) {
                if (!staged.renameTo(target)) {
                    require(staged.copyRecursively(target, overwrite = true)) {
                        "无法复制恢复目录：${target.name}"
                    }
                }
            } else {
                require(target.mkdirs() || target.isDirectory) { "无法创建恢复目录：${target.name}" }
            }
            DirectorySwap(target, previous, hadPrevious)
        } catch (error: Throwable) {
            target.deleteRecursively()
            if (hadPrevious) previous.renameTo(target)
            throw error
        }
    }

    private fun captureUiPreferences(): MurongUiPreferencesSnapshot {
        val values = appContext.getSharedPreferences(UI_PREFERENCES_NAME, Context.MODE_PRIVATE).all
            .filterKeys(UI_PREFERENCE_KEYS::contains)
        return MurongUiPreferencesSnapshot(
            strings = values.mapNotNull { (key, value) -> (value as? String)?.let { key to it } }.toMap(),
            ints = values.mapNotNull { (key, value) -> (value as? Int)?.let { key to it } }.toMap(),
            longs = values.mapNotNull { (key, value) -> (value as? Long)?.let { key to it } }.toMap(),
            floats = values.mapNotNull { (key, value) -> (value as? Float)?.let { key to it } }.toMap(),
            booleans = values.mapNotNull { (key, value) -> (value as? Boolean)?.let { key to it } }.toMap(),
            stringSets = values.mapNotNull { (key, value) ->
                @Suppress("UNCHECKED_CAST")
                (value as? Set<String>)?.let { key to it.toSet() }
            }.toMap()
        )
    }

    private suspend fun captureVoiceSettings(): VoiceSettings {
        return VoiceSettingsRepository(appContext).use { it.exportBackupSnapshot() }
    }

    private suspend fun restoreVoiceSettings(settings: VoiceSettings) {
        VoiceSettingsRepository(appContext).use { it.restoreBackupSnapshot(settings) }
    }

    private fun validateUiPreferences(snapshot: MurongUiPreferencesSnapshot) {
        val allKeys = snapshot.strings.keys + snapshot.ints.keys + snapshot.longs.keys + snapshot.floats.keys +
            snapshot.booleans.keys + snapshot.stringSets.keys
        require(allKeys.all(UI_PREFERENCE_KEYS::contains)) { "界面设置包含未知字段" }
        require(allKeys.size == allKeys.distinct().size) { "界面设置字段类型重复" }
        require(snapshot.strings.values.all { it.length <= 20_000 }) { "界面字符串设置过大" }
        require(snapshot.stringSets.values.sumOf { it.size } <= 1_000) { "界面集合设置过大" }
    }

    private fun restoreUiPreferences(snapshot: MurongUiPreferencesSnapshot) {
        validateUiPreferences(snapshot)
        val editor = appContext.getSharedPreferences(UI_PREFERENCES_NAME, Context.MODE_PRIVATE).edit()
        UI_PREFERENCE_KEYS.forEach(editor::remove)
        snapshot.strings.forEach { (key, value) -> editor.putString(key, value) }
        snapshot.ints.forEach { (key, value) -> editor.putInt(key, value) }
        snapshot.longs.forEach { (key, value) -> editor.putLong(key, value) }
        snapshot.floats.forEach { (key, value) -> editor.putFloat(key, value) }
        snapshot.booleans.forEach { (key, value) -> editor.putBoolean(key, value) }
        snapshot.stringSets.forEach { (key, value) -> editor.putStringSet(key, value) }
        require(editor.commit()) { "无法写入界面设置" }
    }

    private fun pruneInternalBackups(keepLatest: Int) {
        listBackupFiles(automaticBackupDir, legacyAutomaticBackupDir)
            .drop(keepLatest.coerceIn(1, 100))
            .forEach(File::delete)
        listBackupFiles(preRestoreBackupDir, legacyPreRestoreBackupDir)
            .drop(keepLatest.coerceIn(1, 100))
            .forEach(File::delete)
    }

    private fun listBackupFiles(vararg directories: File): List<File> {
        return directories
            .distinctBy { it.absolutePath }
            .flatMap { directory ->
                directory.listFiles { file ->
                    file.isFile && (
                        file.extension.equals(MURONG_BACKUP_EXTENSION, ignoreCase = true) ||
                            file.extension.equals(LEGACY_BACKUP_EXTENSION, ignoreCase = true)
                        )
                }.orEmpty().toList()
            }
            .distinctBy(File::getName)
            .sortedWith(compareByDescending<File>(File::lastModified).thenByDescending(File::getName))
    }

    private fun resolveBackupRoot(): File {
        return runCatching {
            if (Environment.isExternalStorageManager()) {
                val publicRoot = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "Murong/backups"
                )
                if (publicRoot.mkdirs() || publicRoot.isDirectory) return@runCatching publicRoot
            }
            legacyBackupRoot
        }.getOrDefault(legacyBackupRoot)
    }

    private fun migrateLegacyInternalBackups() {
        if (backupRoot == legacyBackupRoot) return
        synchronized(MIGRATION_LOCK) {
            migrateBackupDirectory(legacyAutomaticBackupDir, automaticBackupDir)
            migrateBackupDirectory(legacyPreRestoreBackupDir, preRestoreBackupDir)
        }
    }

    private fun migrateBackupDirectory(source: File, destination: File) {
        if (!source.isDirectory) return
        destination.mkdirs()
        listBackupFiles(source).forEach { legacy ->
            runCatching {
                val target = File(destination, legacy.name)
                if (!target.exists()) {
                    if (!legacy.renameTo(target)) {
                        legacy.copyTo(target, overwrite = false)
                        if (target.length() == legacy.length()) legacy.delete()
                    }
                } else if (target.length() == legacy.length()) {
                    legacy.delete()
                }
            }
        }
    }

    private fun copyCompressedBackupWithLimit(source: java.io.InputStream, destination: File) {
        destination.parentFile?.mkdirs()
        var total = 0L
        BufferedInputStream(source).use { input ->
            BufferedOutputStream(destination.outputStream()).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count <= 0) break
                    total = Math.addExact(total, count.toLong())
                    require(total <= MAX_COMPRESSED_ARCHIVE_BYTES) { "备份包文件大小超过上限" }
                    output.write(buffer, 0, count)
                }
            }
        }
    }

    private sealed interface RestoreState

    private data class NativeRestoreState(
        val config: ConfigBackupSnapshot,
        val mcpConfigs: List<McpServerConfig>,
        val workflows: List<SavedWorkflowDefinition>,
        val voiceSettings: VoiceSettings,
        val uiPreferences: MurongUiPreferencesSnapshot,
        val backupSettings: MurongBackupSettingsSnapshot
    ) : RestoreState

    private data class CrossPlatformRestoreState(
        val portableState: MurongPortableBackupState
    ) : RestoreState

    private data class RestoreApplyResult(
        val crossPlatform: Boolean = false,
        val importedSessions: Int = 0,
        val conflictCopies: Int = 0,
        val skippedSessions: Int = 0
    )

    private data class DirectorySwap(
        val target: File,
        val previous: File,
        val hadPrevious: Boolean
    ) {
        fun commit() {
            if (hadPrevious) previous.deleteRecursively()
        }

        fun rollback() {
            target.deleteRecursively()
            if (hadPrevious) require(previous.renameTo(target)) { "无法回滚目录：${target.name}" }
        }
    }

    private companion object {
        val operationMutex = Mutex()
        val MIGRATION_LOCK = Any()
        val FILE_TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS")
        const val MAX_COMPRESSED_ARCHIVE_BYTES = 600L * 1024L * 1024L
        const val LEGACY_BACKUP_EXTENSION = "mrbak"
        const val PROVIDER_SETTINGS_PATH = "state/provider-settings.json"
        const val MCP_CONFIG_PATH = "state/mcp-config.json"
        const val SAVED_WORKFLOWS_PATH = "state/saved-workflows.json"
        const val VOICE_SETTINGS_PATH = "state/voice-settings.json"
        const val UI_SETTINGS_PATH = "state/ui-settings.json"
        const val BACKUP_SETTINGS_PATH = "state/backup-settings.json"
        const val PORTABLE_STATE_PATH = "state/portable-state.json"
        val REQUIRED_STATE_PATHS = setOf(
            PROVIDER_SETTINGS_PATH,
            MCP_CONFIG_PATH,
            SAVED_WORKFLOWS_PATH,
            VOICE_SETTINGS_PATH,
            UI_SETTINGS_PATH,
            BACKUP_SETTINGS_PATH
        )
        const val UI_PREFERENCES_NAME = "murong_ui"
        val UI_PREFERENCE_KEYS = setOf(
            "theme_mode",
            "theme_style",
            "theme_color_hex",
            "background_mode",
            "background_color_hex",
            "surface_color_hex",
            "chrome_color_hex",
            "muted_text_color_hex",
            "terminal_icon_color_hex",
            "terminal_path_color_hex",
            "terminal_error_color_hex",
            "custom_background_uri",
            "background_blur_radius",
            "font_scale",
            "ui_scale"
        )
    }
}
