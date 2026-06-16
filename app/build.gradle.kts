import java.io.File
import java.util.Base64
import java.util.Properties
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
    ?: "0.9.0-preview"
val appVersionCode = (findProperty("APP_VERSION_CODE") as String?)
    ?.toIntOrNull()
    ?: 1
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

    defaultConfig {
        applicationId = "com.murong.agent"
        minSdk = 33
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersionName

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        create("release") {
            var keystoreFile = rootProject.file("app/release.jks")
            val legacyKeystoreFile = rootProject.file("app/慕容调度.jks")
            val localBase64Keystore = rootProject.file("app/release.jks.b64")
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
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    if (bundledToolchainEnabled) {
        sourceSets.getByName("main").assets.srcDir(generatedToolchainAssetsDirFile)
        sourceSets.getByName("main").jniLibs.srcDir(generatedToolchainJniLibsDirFile)
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

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget("25"))
    }
}

dependencies {
    implementation(project(path = ":core"))
    implementation(project(path = ":common"))
    implementation(project(path = ":terminal-view"))

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
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
}
