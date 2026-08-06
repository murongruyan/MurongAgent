import java.io.File
import java.net.URI
import java.util.Base64
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipFile
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.ksp)
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use(::load)
    }
}

val appVersionName = (findProperty("APP_VERSION_NAME") as String?)
    ?.takeIf { it.isNotBlank() }
    ?: "1.37"
val appVersionCode = (findProperty("APP_VERSION_CODE") as String?)
    ?.toIntOrNull()
    ?: 26080301
val defaultNdkVersion = "30.0.14904198"
val localNdkPath = localProperties.getProperty("murong.ndk.dir")?.replace('\\', '/')
val resolvedNdkVersion = run {
    if (localNdkPath.isNullOrBlank()) {
        defaultNdkVersion
    } else {
        val sourcePropsFile = File(localNdkPath, "source.properties")
        if (!sourcePropsFile.exists()) {
            defaultNdkVersion
        } else {
            val ndkProps = Properties().apply {
                sourcePropsFile.inputStream().use(::load)
            }
            ndkProps.getProperty("Pkg.BaseRevision")
                ?: ndkProps.getProperty("Pkg.Revision")?.replace("-beta1", "")
                ?: defaultNdkVersion
        }
    }
}
val bundledToolchainEnabled = ((findProperty("BUNDLED_TOOLCHAIN_ENABLED") as String?)
    ?: System.getenv("BUNDLED_TOOLCHAIN_ENABLED"))
    ?.toBooleanStrictOrNull()
    ?: false
val bundledToolchainAbi = "arm64-v8a"
val bundledToolchainVersion = "toolchain-v4"
val generatedToolchainAssetsDir = layout.buildDirectory.dir("generated/assets/toolchain")
val generatedToolchainAssetsDirFile = generatedToolchainAssetsDir.get().asFile
val generatedToolchainJniLibsDir = layout.buildDirectory.dir("generated/jnilibs/toolchain")
val generatedToolchainJniLibsDirFile = generatedToolchainJniLibsDir.get().asFile
// Kept outside source control: this is the Apache-2.0 upstream Android runtime used by the
// opt-in offline speech provider.  The model itself is never bundled in the APK.
val sherpaOnnxVersion = "1.13.2"
val sherpaOnnxAarUrl =
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaOnnxVersion/sherpa-onnx-$sherpaOnnxVersion.aar"
val sherpaOnnxAarSha256 = "aa5505c0ec4f8bdaee5f214a64ba3012be64f2aecc022e82a64f33392b8dd245"
val sherpaOnnxAar = layout.buildDirectory.file("generated/offline-stt/sherpa-onnx-$sherpaOnnxVersion.aar")
val mnnVersion = "3.5.0"
val mnnAndroidUrl =
    "https://github.com/alibaba/MNN/releases/download/$mnnVersion/" +
        "mnn_${mnnVersion}_android_armv7_armv8_cpu_opencl_vulkan.zip"
val mnnAndroidSha256 = "b5513459ee5d70dec98e7a0763ce2d09a9824897c150069e65b2b1a04570c573"
val mnnSourceUrl = "https://github.com/alibaba/MNN/archive/refs/tags/$mnnVersion.zip"
val mnnSourceSha256 = "a31f4d46417f6af64e9af079435e088690423eb2e282de5da61f7d082325446c"
val mnnAndroidArchive =
    layout.buildDirectory.file("generated/mnn/downloads/mnn-$mnnVersion-android.zip")
val mnnSourceArchive =
    layout.buildDirectory.file("generated/mnn/downloads/mnn-$mnnVersion-source.zip")
val generatedMnnRuntimeDir = layout.buildDirectory.dir("generated/mnn/runtime")
val generatedMnnSourceDir = layout.buildDirectory.dir("generated/mnn/source")
val generatedMnnRuntimeDirFile = generatedMnnRuntimeDir.get().asFile
val generatedMnnSourceDirFile = generatedMnnSourceDir.get().asFile
val llamaCppAndroidBuild = "b10092"
val llamaCppAndroidUrl =
    "https://github.com/ggml-org/llama.cpp/releases/download/$llamaCppAndroidBuild/" +
        "llama-$llamaCppAndroidBuild-bin-android-arm64.tar.gz"
val llamaCppAndroidSha256 =
    "4f23b4a91b7043db43789fd248142a739d7a1f632d403f756e8be920c45c8076"
val llamaCppAndroidArchive = layout.buildDirectory.file(
    "generated/llama.cpp/downloads/llama-$llamaCppAndroidBuild-bin-android-arm64.tar.gz"
)
val generatedLlamaCppRuntimeDir =
    layout.buildDirectory.dir("generated/llama.cpp/runtime")
val generatedLlamaCppRuntimeDirFile = generatedLlamaCppRuntimeDir.get().asFile

fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").also { digest ->
    file.inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count <= 0) break
            digest.update(buffer, 0, count)
        }
    }
}.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }

fun downloadPinnedArtifact(url: String, output: File, expectedSha256: String) {
    if (output.isFile && sha256(output).equals(expectedSha256, ignoreCase = true)) return
    output.delete()
    val partial = File(output.parentFile, "${output.name}.part")
    partial.parentFile?.mkdirs()
    partial.delete()
    try {
        val connection = URI(url).toURL().openConnection().apply {
            connectTimeout = 30_000
            readTimeout = 30_000
        }
        connection.getInputStream().use { input ->
            partial.outputStream().use { outputStream -> input.copyTo(outputStream) }
        }
        check(sha256(partial).equals(expectedSha256, ignoreCase = true)) {
            "SHA-256 verification failed for ${output.name}"
        }
        check(partial.renameTo(output)) { "Could not atomically activate ${output.name}" }
    } catch (error: Throwable) {
        partial.delete()
        throw error
    }
}

val prepareSherpaOnnxRuntime = tasks.register("prepareSherpaOnnxRuntime") {
    val output = sherpaOnnxAar.get().asFile
    notCompatibleWithConfigurationCache("Downloads and verifies the pinned offline STT runtime.")
    inputs.property("url", sherpaOnnxAarUrl)
    inputs.property("sha256", sherpaOnnxAarSha256)
    outputs.file(output)
    doLast {
        downloadPinnedArtifact(
            url = sherpaOnnxAarUrl,
            output = output,
            expectedSha256 = sherpaOnnxAarSha256,
        )
    }
}

val downloadMnnAndroidRuntime = tasks.register("downloadMnnAndroidRuntime") {
    val output = mnnAndroidArchive.get().asFile
    notCompatibleWithConfigurationCache("Downloads and verifies the pinned MNN Android runtime.")
    inputs.property("url", mnnAndroidUrl)
    inputs.property("sha256", mnnAndroidSha256)
    outputs.file(output)
    doLast {
        downloadPinnedArtifact(mnnAndroidUrl, output, mnnAndroidSha256)
    }
}

val downloadMnnSource = tasks.register("downloadMnnSource") {
    val output = mnnSourceArchive.get().asFile
    notCompatibleWithConfigurationCache("Downloads and verifies pinned MNN public headers.")
    inputs.property("url", mnnSourceUrl)
    inputs.property("sha256", mnnSourceSha256)
    outputs.file(output)
    doLast {
        downloadPinnedArtifact(mnnSourceUrl, output, mnnSourceSha256)
    }
}

val downloadLlamaCppAndroidRuntime = tasks.register("downloadLlamaCppAndroidRuntime") {
    val output = llamaCppAndroidArchive.get().asFile
    notCompatibleWithConfigurationCache("Downloads and verifies the pinned llama.cpp Android runtime.")
    inputs.property("url", llamaCppAndroidUrl)
    inputs.property("sha256", llamaCppAndroidSha256)
    outputs.file(output)
    doLast {
        downloadPinnedArtifact(llamaCppAndroidUrl, output, llamaCppAndroidSha256)
    }
}

val prepareLlamaCppAndroidRuntime =
    tasks.register<org.gradle.api.tasks.Sync>("prepareLlamaCppAndroidRuntime") {
        dependsOn(downloadLlamaCppAndroidRuntime)
        inputs.file(llamaCppAndroidArchive)
        outputs.dir(generatedLlamaCppRuntimeDir)
        from({
            tarTree(resources.gzip(llamaCppAndroidArchive.get().asFile))
        }) {
            include(
                "llama-$llamaCppAndroidBuild/llama-server",
                "llama-$llamaCppAndroidBuild/libllama-server-impl.so",
                "llama-$llamaCppAndroidBuild/libllama-common.so",
                "llama-$llamaCppAndroidBuild/libllama.so",
                "llama-$llamaCppAndroidBuild/libmtmd.so",
                "llama-$llamaCppAndroidBuild/libggml.so",
                "llama-$llamaCppAndroidBuild/libggml-base.so",
                "llama-$llamaCppAndroidBuild/libggml-rpc.so",
                "llama-$llamaCppAndroidBuild/libggml-cpu-android_*.so"
            )
            eachFile {
                // The release archive has a versioned top-level directory. Native libraries
                // must be placed directly in the ABI directory for AGP to package them.
                path = if (name == "llama-server") "libmurong_llama_server.so" else name
            }
            includeEmptyDirs = false
        }
        into(File(generatedLlamaCppRuntimeDir.get().asFile, "arm64-v8a"))
    }

val prepareMnnVisionRuntime = tasks.register("prepareMnnVisionRuntime") {
    dependsOn(downloadMnnAndroidRuntime, downloadMnnSource)
    val runtimeOutput = generatedMnnRuntimeDir.get().asFile
    val sourceOutput = generatedMnnSourceDir.get().asFile
    inputs.files(mnnAndroidArchive, mnnSourceArchive)
    outputs.dirs(runtimeOutput, sourceOutput)
    doLast {
        runtimeOutput.deleteRecursively()
        sourceOutput.deleteRecursively()
        File(runtimeOutput, "arm64-v8a").mkdirs()
        sourceOutput.mkdirs()

        ZipFile(mnnAndroidArchive.get().asFile).use { archive ->
            val arm64Marker = "/arm64-v8a/"
            archive.entries().asSequence()
                .filter { entry ->
                    !entry.isDirectory &&
                        entry.name.contains(arm64Marker) &&
                        entry.name.endsWith(".so")
                }
                .forEach { entry ->
                    val libraryName = entry.name.substringAfter(arm64Marker)
                    check('/' !in libraryName && '\\' !in libraryName) {
                        "Unexpected MNN Android library path: ${entry.name}"
                    }
                    val target = File(runtimeOutput, "arm64-v8a/$libraryName")
                    target.parentFile?.mkdirs()
                    archive.getInputStream(entry).use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                }
        }

        val sourcePrefix = "MNN-$mnnVersion/"
        ZipFile(mnnSourceArchive.get().asFile).use { archive ->
            archive.entries().asSequence()
                .filter { entry ->
                    !entry.isDirectory &&
                        entry.name.startsWith(sourcePrefix) &&
                        (
                            entry.name.startsWith("${sourcePrefix}include/") ||
                                entry.name.startsWith(
                                    "${sourcePrefix}transformers/llm/engine/include/"
                                )
                            )
                }
                .forEach { entry ->
                    val relative = entry.name.removePrefix(sourcePrefix)
                    val target = File(sourceOutput, relative)
                    target.parentFile?.mkdirs()
                    archive.getInputStream(entry).use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                }
        }
        check(File(runtimeOutput, "arm64-v8a/libllm.so").isFile) {
            "Pinned MNN Android archive does not contain libllm.so"
        }
        check(File(sourceOutput, "transformers/llm/engine/include/llm/llm.hpp").isFile) {
            "Pinned MNN source archive does not contain llm.hpp"
        }
    }
}

fun computeBundledCommandInstallName(commandName: String): String {
    val encoded = Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(commandName.toByteArray(Charsets.UTF_8))
    return "libmurong_exec_${encoded}.so"
}

fun generateBundledToolchainAssets(
    sourceRoot: File,
    abi: String,
    version: String,
    outputRoot: File
) {
    val abiDir = File(outputRoot, "toolchain/$abi")
    val libSourceDir = File(sourceRoot, "lib")
    val executableSources = sourceRoot.listFiles()
        ?.filter { it.isFile }
        ?.sortedBy { it.name }
        .orEmpty()
    val commandMappings = linkedMapOf<String, String>()

    outputRoot.deleteRecursively()
    File(abiDir, "lib").mkdirs()

    val bundledLibFiles = if (libSourceDir.exists()) {
        libSourceDir.walkTopDown()
            .filter { it.isFile }
            .toList()
    } else {
        emptyList()
    }

    bundledLibFiles.forEach { source ->
        val relative = source.relativeTo(sourceRoot).invariantSeparatorsPath
        val target = File(abiDir, relative)
        target.parentFile?.mkdirs()
        source.copyTo(target, overwrite = true)
        target.setReadable(true, false)
        if (source.name.contains(".so")) {
            target.setExecutable(true, false)
        }
    }

    executableSources.forEach { source ->
        commandMappings[source.name] = "native/${computeBundledCommandInstallName(source.name)}"
    }

    val manifestJson = buildString {
        appendLine("{")
        appendLine("""  "version": "$version",""")
        appendLine("""  "files": [""")
        bundledLibFiles.forEachIndexed { index, source ->
            val relativePath = source.relativeTo(sourceRoot).invariantSeparatorsPath
            val suffix = if (index == bundledLibFiles.size - 1) "" else ","
            appendLine("""    { "asset": "$relativePath", "path": "$relativePath", "executable": false }$suffix""")
        }
        appendLine("  ],")
        appendLine("""  "commands": {""")
        commandMappings.entries.forEachIndexed { index, (command, relativePath) ->
            val suffix = if (index == commandMappings.size - 1) "" else ","
            appendLine("""    "$command": "$relativePath"$suffix""")
        }
        appendLine("  }")
        appendLine("}")
    }
    File(abiDir, "manifest.json").writeText(manifestJson)
}

fun generateBundledToolchainJniLibs(
    sourceRoot: File,
    abi: String,
    outputRoot: File
) {
    val abiDir = File(outputRoot, abi)
    val executableSources = sourceRoot.listFiles()
        ?.filter { it.isFile }
        ?.sortedBy { it.name }
        .orEmpty()

    outputRoot.deleteRecursively()
    abiDir.mkdirs()

    executableSources.forEach { source ->
        val target = File(abiDir, computeBundledCommandInstallName(source.name))
        source.copyTo(target, overwrite = true)
        target.setExecutable(true, false)
        target.setReadable(true, false)
    }
}

val prepareBundledToolchainAssets = tasks.register("prepareBundledToolchainAssets") {
    val sourceRoot = rootProject.layout.projectDirectory.dir("bin").asFile
    val outputRoot = generatedToolchainAssetsDir.get().asFile
    notCompatibleWithConfigurationCache("Generates bundled toolchain metadata from the Gradle script.")
    inputs.dir(sourceRoot)
    inputs.property("abi", bundledToolchainAbi)
    inputs.property("version", bundledToolchainVersion)
    outputs.dir(outputRoot)
    doLast {
        generateBundledToolchainAssets(
            sourceRoot = sourceRoot,
            abi = bundledToolchainAbi,
            version = bundledToolchainVersion,
            outputRoot = outputRoot
        )
    }
}

val prepareBundledToolchainJniLibs = tasks.register("prepareBundledToolchainJniLibs") {
    val sourceRoot = rootProject.layout.projectDirectory.dir("bin").asFile
    val outputRoot = generatedToolchainJniLibsDir.get().asFile
    notCompatibleWithConfigurationCache("Generates bundled toolchain native executables from the Gradle script.")
    inputs.dir(sourceRoot)
    inputs.property("abi", bundledToolchainAbi)
    outputs.dir(outputRoot)
    doLast {
        generateBundledToolchainJniLibs(
            sourceRoot = sourceRoot,
            abi = bundledToolchainAbi,
            outputRoot = outputRoot
        )
    }
}

android {
    namespace = "com.murong.agent"
    compileSdk = 37
    ndkVersion = resolvedNdkVersion
    if (!localNdkPath.isNullOrBlank()) {
        ndkPath = localNdkPath
    }

    defaultConfig {
        applicationId = "com.murong.agent"
        minSdk = 33
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DMNN_RUNTIME_ROOT=${generatedMnnRuntimeDirFile.absolutePath.replace('\\', '/')}",
                    "-DMNN_SOURCE_ROOT=${generatedMnnSourceDirFile.absolutePath.replace('\\', '/')}"
                )
            }
        }
    }

    signingConfigs {
        create("release") {
            var keystoreFile = rootProject.file("app/release.jks")
            val legacyKeystoreFile = rootProject.file("app/慕容调度.jks")
            val localBase64Keystore = rootProject.file("app/release.jks.b64")
            val diaoduKeystoreFile = rootProject.file("../murongdiaodu-apk/murong/release.jks")
            val diaoduBase64Keystore = rootProject.file("../murongdiaodu-apk/murong/release.jks.b64")
            val keystoreBase64 = (findProperty("KEYSTORE_BASE64") as String?)
                ?: System.getenv("KEYSTORE_BASE64")
            if (!keystoreBase64.isNullOrBlank()) {
                val cleaned = keystoreBase64
                    .replace("-----BEGIN CERTIFICATE-----", "")
                    .replace("-----END CERTIFICATE-----", "")
                    .replace("\r", "")
                    .replace("\n", "")
                    .trim()
                if (cleaned.isNotEmpty()) {
                    keystoreFile.parentFile?.mkdirs()
                    keystoreFile.writeBytes(Base64.getDecoder().decode(cleaned))
                }
            } else if (diaoduKeystoreFile.exists()) {
                keystoreFile = diaoduKeystoreFile
            } else if (diaoduBase64Keystore.exists()) {
                val cleaned = diaoduBase64Keystore
                    .readText(Charsets.UTF_8)
                    .replace("-----BEGIN CERTIFICATE-----", "")
                    .replace("-----END CERTIFICATE-----", "")
                    .replace("\r", "")
                    .replace("\n", "")
                    .trim()
                if (cleaned.isNotEmpty()) {
                    keystoreFile.parentFile?.mkdirs()
                    keystoreFile.writeBytes(Base64.getDecoder().decode(cleaned))
                }
            } else if (!keystoreFile.exists() && legacyKeystoreFile.exists()) {
                keystoreFile = legacyKeystoreFile
            } else if (!keystoreFile.exists() && localBase64Keystore.exists()) {
                val cleaned = localBase64Keystore
                    .readText(Charsets.UTF_8)
                    .replace("-----BEGIN CERTIFICATE-----", "")
                    .replace("-----END CERTIFICATE-----", "")
                    .replace("\r", "")
                    .replace("\n", "")
                    .trim()
                if (cleaned.isNotEmpty()) {
                    keystoreFile.parentFile?.mkdirs()
                    keystoreFile.writeBytes(Base64.getDecoder().decode(cleaned))
                }
            }
            storeFile = keystoreFile
            storePassword =
                localProperties.getProperty("storePassword")
                    ?: (findProperty("STORE_PASSWORD") as String?)
                    ?: System.getenv("STORE_PASSWORD")
                    ?: ""
            keyAlias = "慕容调度"
            keyPassword =
                localProperties.getProperty("keyPassword")
                    ?: (findProperty("KEY_PASSWORD") as String?)
                    ?: System.getenv("KEY_PASSWORD")
                    ?: ""
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
            enableV4Signing = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_25
        targetCompatibility = JavaVersion.VERSION_25
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
        aidl = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    sourceSets.getByName("main").jniLibs.directories.add(
        generatedMnnRuntimeDirFile.absolutePath
    )
    sourceSets.getByName("main").jniLibs.directories.add(
        generatedLlamaCppRuntimeDirFile.absolutePath
    )
    if (bundledToolchainEnabled) {
        sourceSets.getByName("main").assets.directories.add(generatedToolchainAssetsDirFile.absolutePath)
        sourceSets.getByName("main").jniLibs.directories.add(generatedToolchainJniLibsDirFile.absolutePath)
    }
}

if (bundledToolchainEnabled) {
    tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach {
        dependsOn(prepareBundledToolchainAssets)
    }

    tasks.matching { it.name.startsWith("merge") && it.name.endsWith("NativeLibs") }.configureEach {
        dependsOn(prepareBundledToolchainJniLibs)
    }

    tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }.configureEach {
        dependsOn(prepareBundledToolchainJniLibs)
    }

    tasks.matching {
        it.name == "generateReleaseLintVitalReportModel" ||
            it.name == "lintVitalAnalyzeRelease"
    }.configureEach {
        dependsOn(prepareBundledToolchainAssets)
        dependsOn(prepareBundledToolchainJniLibs)
    }
}

// Local file dependencies are resolved before the Android merge tasks, so make every build
// explicitly materialize the verified runtime first. This also works in GitHub Actions.
tasks.named("preBuild").configure {
    dependsOn(prepareSherpaOnnxRuntime)
    dependsOn(prepareMnnVisionRuntime)
    dependsOn(prepareLlamaCppAndroidRuntime)
}

tasks.matching {
    it.name.contains("CMake", ignoreCase = true) ||
        it.name.contains("ExternalNativeBuild", ignoreCase = true) ||
        (it.name.startsWith("merge") && it.name.endsWith("NativeLibs")) ||
        (it.name.startsWith("merge") && it.name.endsWith("JniLibFolders"))
}.configureEach {
    dependsOn(prepareMnnVisionRuntime)
    dependsOn(prepareLlamaCppAndroidRuntime)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget("25"))
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":common"))
    implementation(project(":terminal-view"))

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation("androidx.compose.material:material-icons-extended")
    implementation(libs.compose.foundation)
    debugImplementation(libs.compose.ui.tooling)

    // AndroidX
    implementation(libs.appcompat)
    implementation(libs.core.ktx)
    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.datastore.preferences)
    implementation(libs.work.runtime.ktx)

    // Kotlinx
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.nanohttpd)
    implementation(libs.commons.compress)
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")
    implementation(files(sherpaOnnxAar))

    // DI
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // UI
    implementation(libs.haze)
    implementation(libs.jgit)
    implementation(platform(libs.sora.editor.bom))
    implementation(libs.sora.editor)
    implementation(libs.sora.language.monarch)
    implementation(libs.sora.language.textmate)
    implementation(libs.monarch.language.pack)
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    testImplementation(kotlin("test"))
    testImplementation(kotlin("test-junit"))
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
}
