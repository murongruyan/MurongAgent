import java.io.File
import java.util.Properties
import org.gradle.api.tasks.compile.JavaCompile

plugins {
    alias(libs.plugins.android.library)
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use(::load)
    }
}
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

android {
    namespace = "com.termux.emulator"
    compileSdk = 37
    ndkVersion = resolvedNdkVersion
    if (!localNdkPath.isNullOrBlank()) {
        ndkPath = localNdkPath
    }

    defaultConfig {
        minSdk = 33
        externalNativeBuild {
            ndkBuild {
                arguments += listOf()
            }
        }
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            consumerProguardFiles("proguard-rules.pro")
        }
    }

    externalNativeBuild {
        ndkBuild {
            path = file("src/main/jni/Android.mk")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_25
        targetCompatibility = JavaVersion.VERSION_25
    }
}

dependencies {
    implementation("androidx.annotation:annotation:1.9.1")
}

tasks.withType(JavaCompile::class.java).configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:none", "-nowarn"))
}
