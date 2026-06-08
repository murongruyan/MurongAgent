import java.util.Base64
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("org.jetbrains.kotlin.kapt")
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt.android)
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use(::load)
    }
}

val appVersionName = (findProperty("APP_VERSION_NAME") as String?)
    ?.takeIf { it.isNotBlank() }
    ?: "1.0.0"
val appVersionCode = (findProperty("APP_VERSION_CODE") as String?)
    ?.toIntOrNull()
    ?: 1

android {
    namespace = "dev.reasonix.mobile"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.reasonix.mobile"
        minSdk = 33
        targetSdk = 36
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

kapt {
    correctErrorTypes = true
}

hilt {
    enableAggregatingTask = false
}

dependencies {
    implementation(project(":core"))
    implementation(project(":common"))

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
    kapt(libs.hilt.compiler)
    kapt(libs.kotlin.metadata.jvm)
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
}
