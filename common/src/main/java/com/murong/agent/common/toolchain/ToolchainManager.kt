package com.murong.agent.common.toolchain

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Paths

/**
 * 应用私有工具链管理器。
 *
 * 负责：
 * 1. 从 assets/toolchain/<abi>/ 解压依赖库到 filesDir/toolchain/<abi>/
 * 2. 维护 filesDir/toolchain/<abi>/bin 下指向 nativeLibraryDir 的命令入口
 * 3. 生成统一的 PATH/HOME/TMPDIR/LD_LIBRARY_PATH
 * 4. 暴露私有命令路径给 KeepShell / Terminal / Agent tools 使用
 */
object ToolchainManager {

    private const val TAG = "ToolchainManager"
    private const val EXTENSION_PACKAGE_NAME = "cc.rl1.murong.terminalextension"
    private const val TERMUX_PREFIX_PLACEHOLDER = "/data/data/com.termux/files/usr"
    private const val TERMUX_HOME_PLACEHOLDER = "/data/data/com.termux/files/home"
    private const val TERMUX_CACHE_PLACEHOLDER = "/data/data/com.termux/cache"
    private const val SYSTEM_PATH =
        "/system/bin:/system/xbin:/system_ext/bin:/product/bin:/apex/com.android.runtime/bin"
    private val systemLibraryCandidates = listOf(
        "/system/lib64",
        "/system/lib",
        "/system_ext/lib64",
        "/system_ext/lib",
        "/product/lib64",
        "/product/lib",
        "/vendor/lib64",
        "/vendor/lib",
        "/odm/lib64",
        "/odm/lib",
        "/apex/com.android.runtime/lib64",
        "/apex/com.android.runtime/lib"
    )
    private val blockedCommandOverrides = setOf("su", "su2", "sudo")
    private val preferredSuCandidates = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/su/bin/su"
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val installLock = Any()

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var cachedToolchain: InstalledToolchain? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    fun warmUpAsync() {
        val context = appContext ?: return
        Thread {
            ensureInstalled(context)
        }.apply {
            name = "murong-toolchain-warmup"
            isDaemon = true
        }.start()
    }

    fun ensureInstalled(context: Context? = appContext): InstalledToolchain {
        val safeContext = context?.applicationContext ?: return InstalledToolchain.unavailable()
        initialize(safeContext)

        synchronized(installLock) {
            val abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
            if (abi.isBlank()) {
                return InstalledToolchain.unavailable()
            }

            val host = resolveToolchainHost(safeContext, abi)
            val assetBase = "toolchain/$abi"
            val manifestAssetPath = "$assetBase/manifest.json"
            val manifestText = host?.manifestText
                ?: return InstalledToolchain.unavailable(
                    abi = abi,
                    rootDir = File(safeContext.filesDir, "toolchain/$abi"),
                    binDir = File(safeContext.filesDir, "toolchain/$abi/bin"),
                    libDir = File(safeContext.filesDir, "toolchain/$abi/lib"),
                    sourcePackage = host?.packageName.orEmpty(),
                    sourceType = host?.sourceType.orEmpty()
                )
            val manifest = try {
                json.decodeFromString<ToolchainManifest>(manifestText)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse toolchain manifest for $abi", e)
                return InstalledToolchain.unavailable(
                    abi = abi,
                    rootDir = File(safeContext.filesDir, "toolchain/$abi"),
                    binDir = File(safeContext.filesDir, "toolchain/$abi/bin"),
                    libDir = File(safeContext.filesDir, "toolchain/$abi/lib"),
                    sourcePackage = host.packageName,
                    sourceType = host.sourceType
                )
            }

            val rootDir = File(safeContext.filesDir, "toolchain/$abi")
            if (!isValidManifest(manifest, rootDir, host.nativeLibraryDir)) {
                Log.w(TAG, "Rejected unsafe toolchain manifest from ${host.packageName}")
                return InstalledToolchain.unavailable(
                    abi = abi,
                    rootDir = rootDir,
                    binDir = File(rootDir, "bin"),
                    libDir = File(rootDir, "lib"),
                    sourcePackage = host.packageName,
                    sourceType = host.sourceType
                )
            }
            val binDir = File(rootDir, "bin")
            val libDir = File(rootDir, "lib")
            val versionFile = File(rootDir, ".version")
            val installFingerprint = "${host.packageName}:${manifest.version}:relocatable-v1"
            val nativeLibraryDir = host.nativeLibraryDir
                ?: File("/system/lib64")
            val commandTargetPaths = manifest.commands
                .filterKeys { it !in blockedCommandOverrides }
                .mapValues { (_, relativePath) ->
                    resolveRuntimePath(
                        relativePath = relativePath,
                        rootDir = rootDir,
                        nativeLibraryDir = nativeLibraryDir
                    ).absolutePath
                }
            val commandEntryPaths = commandTargetPaths.keys.associateWith { command ->
                File(binDir, command).absolutePath
            }
            val requiredFiles = manifest.files.map { entry ->
                File(rootDir, entry.path.ifBlank { entry.asset.removePrefix("bin/") })
            } + manifest.links.map { entry ->
                File(rootDir, entry.path)
            } + commandTargetPaths.values.map(::File) + commandEntryPaths.values.map(::File)
            val missingRequiredFiles = requiredFiles.filterNot(File::exists)

            val cached = cachedToolchain
            if (cached != null &&
                cached.available &&
                cached.abi == abi &&
                cached.version == manifest.version &&
                cached.sourcePackage == host.packageName &&
                cached.sourceType == host.sourceType &&
                versionFile.takeIf(File::exists)?.readText() == installFingerprint &&
                requiredFiles.all(File::exists)
            ) {
                ensureToolchainLinks(rootDir, manifest.links)
                ensureCommandLinks(binDir, commandTargetPaths)
                return cached
            }

            val needsInstall =
                versionFile.takeIf(File::exists)?.readText() != installFingerprint ||
                    requiredFiles.any { !it.exists() }

            if (needsInstall) {
                try {
                    rootDir.deleteRecursively()
                    check(rootDir.mkdirs() || rootDir.isDirectory) { "Cannot create toolchain directory" }
                    manifest.files.forEach { entry ->
                        val target = File(rootDir, entry.path.ifBlank { entry.asset })
                        check(target.parentFile?.mkdirs() != false || target.parentFile?.isDirectory == true) {
                            "Cannot create toolchain file directory"
                        }
                        host.context.assets.open("$assetBase/${entry.asset}").use { input ->
                            FileOutputStream(target).use { output ->
                                input.copyTo(output)
                            }
                        }
                        relocateTermuxTextFile(
                            file = target,
                            rootDir = rootDir,
                            homeDir = safeContext.filesDir,
                            cacheDir = safeContext.cacheDir
                        )
                        if (entry.executable) {
                            target.setExecutable(true, false)
                            target.setReadable(true, false)
                        }
                    }
                    ensureToolchainLinks(rootDir, manifest.links)
                    versionFile.writeText(installFingerprint)
                } catch (error: Exception) {
                    Log.w(TAG, "Failed to install toolchain", error)
                    rootDir.deleteRecursively()
                    cachedToolchain = null
                    return InstalledToolchain.unavailable(
                        abi = abi,
                        rootDir = rootDir,
                        binDir = binDir,
                        libDir = libDir,
                        sourcePackage = host.packageName,
                        sourceType = host.sourceType
                    )
                }
            }
            ensureToolchainLinks(rootDir, manifest.links)
            ensureCommandLinks(binDir, commandTargetPaths)

            return InstalledToolchain(
                available = requiredFiles.all(File::exists),
                abi = abi,
                version = manifest.version,
                rootDir = rootDir,
                binDir = binDir,
                libDir = libDir,
                sourcePackage = host.packageName,
                sourceType = host.sourceType,
                commandPaths = commandEntryPaths.filterValues { File(it).exists() }
            ).also { installed ->
                cachedToolchain = installed
            }
        }
    }

    fun getBundledCommandPath(commandName: String, context: Context? = appContext): String? {
        val installed = ensureInstalled(context)
        return installed.commandPaths[commandName]?.takeIf { File(it).exists() }
    }

    fun findCommandPath(commandName: String, context: Context? = appContext): String? {
        val normalized = commandName.trim()
        if (normalized.isBlank() || normalized.contains('/') || normalized.contains('\\')) return null
        getBundledCommandPath(normalized, context)?.let { return it }
        return buildPreferredPath(context)
            .split(':')
            .asSequence()
            .map { File(it, normalized) }
            .firstOrNull { it.isFile && it.canExecute() }
            ?.absolutePath
    }

    /** The bundled upstream Termux apt/dpkg binaries use a fixed Termux prefix. */
    fun hasRelocatablePackageManager(context: Context? = appContext): Boolean = false

    fun hasBundledCommands(context: Context? = appContext): Boolean {
        val installed = ensureInstalled(context)
        return installed.available && installed.commandPaths.isNotEmpty()
    }

    fun hasAvailableToolchain(context: Context? = appContext): Boolean {
        return ensureInstalled(context).available
    }

    fun isUsingExtensionPackage(context: Context? = appContext): Boolean {
        return ensureInstalled(context).sourceType == ToolchainSourceType.EXTENSION.id
    }

    fun describeActiveEnvironment(
        context: Context? = appContext,
        preferSystem: Boolean = false
    ): String {
        if (preferSystem) return "系统环境"
        return when (ensureInstalled(context).sourceType) {
            ToolchainSourceType.EXTENSION.id -> "扩展包环境"
            ToolchainSourceType.BUNDLED.id -> "内置环境"
            else -> "系统环境"
        }
    }

    fun buildSystemPath(): String = SYSTEM_PATH

    fun buildSystemLibraryPath(): String {
        return systemLibraryCandidates
            .asSequence()
            .map(::File)
            .filter(File::exists)
            .joinToString(":") { it.absolutePath }
    }

    fun resolveSystemCommandPath(commandName: String): String {
        val normalized = commandName.trim()
        if (normalized.isBlank()) return commandName
        if (normalized == "su") {
            preferredSuCandidates.firstOrNull { File(it).exists() }?.let { return it }
        }
        SYSTEM_PATH.split(':')
            .asSequence()
            .map { File(it, normalized) }
            .firstOrNull(File::exists)
            ?.let { return it.absolutePath }
        return normalized
    }

    fun buildPreferredPath(context: Context? = appContext): String {
        val installed = ensureInstalled(context)
        val privateBin = installed.binDir.takeIf { installed.available && it.exists() }?.absolutePath
        return if (privateBin.isNullOrBlank()) {
            SYSTEM_PATH
        } else {
            "$privateBin:$SYSTEM_PATH"
        }
    }

    fun buildPreferredLibraryPath(context: Context? = appContext): String {
        val installed = ensureInstalled(context)
        return installed.libDir.takeIf { installed.available && it.exists() }?.absolutePath.orEmpty()
    }

    fun buildShellBootstrapCommands(context: Context? = appContext): String {
        val safeContext = context?.applicationContext ?: appContext ?: return ""
        val path = buildPreferredPath(safeContext)
        val libraryPath = buildPreferredLibraryPath(safeContext)
        val home = safeContext.filesDir.absolutePath
        val tmpDir = safeContext.cacheDir.absolutePath
        val prefix = ensureInstalled(safeContext).rootDir.absolutePath
        val commands = mutableListOf(
            "export HOME=${shellQuote(home)}",
            "export TMPDIR=${shellQuote(tmpDir)}",
            "export PREFIX=${shellQuote(prefix)}",
            "export TERMUX__PREFIX=${shellQuote(prefix)}",
            "export TERMUX_APP_PACKAGE_MANAGER=apt",
            "export PATH=${shellQuote(path)}",
            "hash -r >/dev/null 2>&1 || true"
        )
        buildAliasCommands(safeContext)
            .takeIf { it.isNotBlank() }
            ?.let(commands::add)
        if (libraryPath.isNotBlank()) {
            commands.add("export LD_LIBRARY_PATH=${shellQuote(libraryPath)}")
        }
        return commands.joinToString("; ")
    }

    fun applyProcessEnvironment(
        environment: MutableMap<String, String>,
        context: Context? = appContext
    ) {
        val safeContext = context?.applicationContext ?: appContext ?: return
        val installed = ensureInstalled(safeContext)
        environment["HOME"] = safeContext.filesDir.absolutePath
        environment["TMPDIR"] = safeContext.cacheDir.absolutePath
        environment["PREFIX"] = installed.rootDir.absolutePath
        environment["TERMUX__PREFIX"] = installed.rootDir.absolutePath
        environment["TERMUX_APP_PACKAGE_MANAGER"] = "apt"
        environment["PATH"] = buildPreferredPath(safeContext)
        buildPreferredLibraryPath(safeContext)
            .takeIf { it.isNotBlank() }
            ?.let { environment["LD_LIBRARY_PATH"] = it }
    }

    fun buildAliasCommands(context: Context? = appContext): String {
        val installed = ensureInstalled(context)
        if (!installed.available || installed.commandPaths.isEmpty()) return ""
        return installed.commandPaths.entries.joinToString("; ") { (command, path) ->
            "alias $command=${shellQuote(path)}"
        }
    }

    private fun resolveToolchainHost(context: Context, abi: String): ToolchainHost? {
        resolveExternalToolchainHost(context, abi)?.let { return it }
        val manifestText = readAssetOrNull(context, "toolchain/$abi/manifest.json") ?: return null
        val nativeLibraryDir = context.applicationInfo.nativeLibraryDir?.let(::File)
        return ToolchainHost(
            context = context,
            packageName = context.packageName,
            sourceType = ToolchainSourceType.BUNDLED.id,
            manifestText = manifestText,
            nativeLibraryDir = nativeLibraryDir
        )
    }

    private fun resolveExternalToolchainHost(context: Context, abi: String): ToolchainHost? {
        val packageManager = context.packageManager
        val extensionContext = try {
            val installed = isPackageInstalled(packageManager, EXTENSION_PACKAGE_NAME)
            if (!installed) return null
            context.createPackageContext(EXTENSION_PACKAGE_NAME, 0)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create extension package context", e)
            return null
        }
        if (packageManager.checkSignatures(context.packageName, EXTENSION_PACKAGE_NAME) != PackageManager.SIGNATURE_MATCH) {
            Log.w(TAG, "Rejected terminal extension with a different signing certificate")
            return null
        }
        val manifestText = readAssetOrNull(extensionContext, "toolchain/$abi/manifest.json") ?: return null
        val nativeLibraryDir = extensionContext.applicationInfo.nativeLibraryDir?.let(::File)
        return ToolchainHost(
            context = extensionContext,
            packageName = EXTENSION_PACKAGE_NAME,
            sourceType = ToolchainSourceType.EXTENSION.id,
            manifestText = manifestText,
            nativeLibraryDir = nativeLibraryDir
        )
    }

    private fun isPackageInstalled(packageManager: PackageManager, packageName: String): Boolean {
        return runCatching {
            packageManager.getApplicationInfo(packageName, 0)
            true
        }.getOrDefault(false)
    }

    private fun readAssetOrNull(context: Context, assetPath: String): String? {
        return try {
            context.assets.open(assetPath).bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            null
        }
    }

    private fun isValidManifest(
        manifest: ToolchainManifest,
        rootDir: File,
        nativeLibraryDir: File?
    ): Boolean {
        if (manifest.files.any { !isSafeRelativePath(it.asset) || !isSafeRelativePath(it.path.ifBlank { it.asset }) }) return false
        if (manifest.links.any { !isSafeRelativePath(it.path) || !isSafeLinkTarget(it.path, it.target) }) return false
        if (manifest.commands.any { (name, path) ->
                !name.matches(Regex("[A-Za-z0-9._+-]+")) ||
                    (path.startsWith("native/") && !isSafeNativePath(path, nativeLibraryDir)) ||
                    (!path.startsWith("native/") && !isSafeRelativePath(path))
            }) return false
        return rootDir.toPath().isAbsolute
    }

    private fun isSafeRelativePath(path: String): Boolean {
        if (path.isBlank() || path.startsWith('/') || path.contains('\\')) return false
        val normalized = Paths.get(path).normalize()
        return !normalized.isAbsolute && !normalized.startsWith("..")
    }

    private fun isSafeLinkTarget(path: String, target: String): Boolean {
        if (target.isBlank() || target.startsWith('/') || target.contains('\\')) return false
        val parent = Paths.get(path).parent ?: Paths.get("")
        val normalized = parent.resolve(target).normalize()
        return !normalized.isAbsolute && !normalized.startsWith("..")
    }

    private fun isSafeNativePath(path: String, nativeLibraryDir: File?): Boolean {
        val name = path.removePrefix("native/")
        return nativeLibraryDir != null && name.isNotBlank() && !name.contains('/') && !name.contains('\\') && !name.contains("..")
    }

    private fun resolveRuntimePath(
        relativePath: String,
        rootDir: File,
        nativeLibraryDir: File
    ): File {
        return if (relativePath.startsWith("native/")) {
            File(nativeLibraryDir, relativePath.removePrefix("native/"))
        } else {
            File(rootDir, relativePath)
        }
    }

    private fun relocateTermuxTextFile(
        file: File,
        rootDir: File,
        homeDir: File,
        cacheDir: File
    ) {
        if (!file.isFile || file.length() > 2 * 1024 * 1024) return
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return
        if (bytes.take(4).toByteArray().contentEquals(byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()))) {
            return
        }
        val text = runCatching { bytes.toString(Charsets.UTF_8) }.getOrNull() ?: return
        if (!text.contains(TERMUX_PREFIX_PLACEHOLDER)) return
        if (file.parentFile?.name == "bin" && file.name == "pkg" && !hasRelocatablePackageManager()) {
            file.writeText(
                "#!/system/bin/sh\n" +
                    "echo 'pkg is unavailable: this terminal extension bundles upstream Termux apt/dpkg binaries with a fixed Termux prefix.' >&2\n" +
                    "echo 'Install an extension built with a relocatable package manager to enable pkg install.' >&2\n" +
                    "exit 126\n",
                Charsets.UTF_8
            )
            return
        }
        val relocated = text
            .replace(TERMUX_PREFIX_PLACEHOLDER, rootDir.absolutePath)
            .replace(TERMUX_HOME_PLACEHOLDER, homeDir.absolutePath)
            .replace(TERMUX_CACHE_PLACEHOLDER, cacheDir.absolutePath)
        if (relocated != text) {
            file.writeText(relocated, Charsets.UTF_8)
        }
    }

    private fun ensureCommandLinks(binDir: File, commandPaths: Map<String, String>) {
        binDir.mkdirs()
        commandPaths.forEach { (command, path) ->
            val linkFile = File(binDir, command)
            val target = File(path)
            if (!target.exists()) return@forEach
            val existingTarget = runCatching { Files.readSymbolicLink(linkFile.toPath()).toString() }.getOrNull()
            if (linkFile.exists() && existingTarget == path) return@forEach
            linkFile.delete()
            runCatching {
                Files.createSymbolicLink(linkFile.toPath(), target.toPath())
            }.onFailure {
                Log.w(TAG, "Failed to create command symlink for $command -> $path", it)
            }
        }
    }

    private fun ensureToolchainLinks(rootDir: File, links: List<ToolchainLinkEntry>) {
        links.forEach { entry ->
            val linkFile = File(rootDir, entry.path)
            linkFile.parentFile?.mkdirs()
            val existingTarget = runCatching {
                Files.readSymbolicLink(linkFile.toPath()).toString().replace('\\', '/')
            }.getOrNull()
            if (linkFile.exists() && existingTarget == entry.target) return@forEach
            linkFile.delete()
            runCatching {
                Files.createSymbolicLink(linkFile.toPath(), Paths.get(entry.target))
            }.onFailure { error ->
                val fallbackTarget = linkFile.parentFile
                    ?.toPath()
                    ?.resolve(entry.target)
                    ?.normalize()
                    ?.toFile()
                if (fallbackTarget != null && fallbackTarget.exists() && fallbackTarget.isFile) {
                    runCatching {
                        fallbackTarget.copyTo(linkFile, overwrite = true)
                    }.onFailure {
                        Log.w(TAG, "Failed to materialize fallback link ${entry.path} -> ${entry.target}", it)
                    }
                } else {
                    Log.w(TAG, "Failed to create toolchain link ${entry.path} -> ${entry.target}", error)
                }
            }
        }
    }

    private fun shellQuote(value: String): String {
        return "'${value.replace("'", "'\"'\"'")}'"
    }

    @Serializable
    private data class ToolchainManifest(
        val version: String = "0",
        val files: List<ToolchainFileEntry> = emptyList(),
        val links: List<ToolchainLinkEntry> = emptyList(),
        val commands: Map<String, String> = emptyMap()
    )

    @Serializable
    private data class ToolchainFileEntry(
        val asset: String,
        val path: String = asset,
        val executable: Boolean = false
    )

    @Serializable
    private data class ToolchainLinkEntry(
        val path: String,
        val target: String
    )

    private data class ToolchainHost(
        val context: Context,
        val packageName: String,
        val sourceType: String,
        val manifestText: String,
        val nativeLibraryDir: File?
    )

    private enum class ToolchainSourceType(val id: String) {
        BUNDLED("bundled"),
        EXTENSION("extension")
    }

    data class InstalledToolchain(
        val available: Boolean,
        val abi: String = "",
        val version: String = "",
        val rootDir: File,
        val binDir: File,
        val libDir: File,
        val sourcePackage: String = "",
        val sourceType: String = "",
        val commandPaths: Map<String, String> = emptyMap()
    ) {
        companion object {
            fun unavailable(
                abi: String = "",
                rootDir: File = File("."),
                binDir: File = File("."),
                libDir: File = File("."),
                sourcePackage: String = "",
                sourceType: String = ""
            ): InstalledToolchain {
                return InstalledToolchain(
                    available = false,
                    abi = abi,
                    rootDir = rootDir,
                    binDir = binDir,
                    libDir = libDir,
                    sourcePackage = sourcePackage,
                    sourceType = sourceType
                )
            }
        }
    }
}
