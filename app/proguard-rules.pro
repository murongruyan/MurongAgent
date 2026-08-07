# Murong Agent ProGuard Rules

# Keep serialization classes
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep DataStore
-keep class androidx.datastore.** { *; }

# Keep WorkManager runtime internals used during Startup initialization.
-keep class androidx.work.** { *; }
-keep class com.murong.agent.core.tool.OplusAccessibilityGuideRestoreWorker { *; }
-keep class androidx.startup.** { *; }

# Keep Hilt
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }

# Keep OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }

# sherpa-onnx reaches its native recognizer through JNI method names. Keep the upstream API
# intact in minified releases so the optional offline voice provider is not stripped or renamed.
-keep class com.k2fsa.sherpa.onnx.** { *; }

# sherpa-onnx streams TTS output through Kotlin callback lambdas. Its JNI bridge
# reflects the "invoke([F)Ljava/lang/Integer;" method on the lambda class, and
# R8's inlining/unboxing rewrites would break that reflection. Keep the whole
# voice package unoptimized so the streaming callback survives minification.
-keep class com.murong.agent.voice.** { *; }

# JGit pulls in some desktop/JVM-only integrations that Android does not provide.
# Suppress those optional references so release minification can complete.
-dontwarn java.lang.ProcessHandle
-dontwarn java.lang.management.ManagementFactory
-dontwarn javax.management.InstanceAlreadyExistsException
-dontwarn javax.management.InstanceNotFoundException
-dontwarn javax.management.JMException
-dontwarn javax.management.MBeanRegistrationException
-dontwarn javax.management.MBeanServer
-dontwarn javax.management.MXBean
-dontwarn javax.management.MalformedObjectNameException
-dontwarn javax.management.NotCompliantMBeanException
-dontwarn javax.management.ObjectInstance
-dontwarn javax.management.ObjectName
-dontwarn org.ietf.jgss.GSSContext
-dontwarn org.ietf.jgss.GSSCredential
-dontwarn org.ietf.jgss.GSSException
-dontwarn org.ietf.jgss.GSSManager
-dontwarn org.ietf.jgss.GSSName
-dontwarn org.ietf.jgss.Oid
-dontwarn org.slf4j.impl.StaticLoggerBinder

# JGit uses runtime lookups/internal factories that break under aggressive release obfuscation.
# Keep the package intact so local repository status still works in release builds.
-keep class org.eclipse.jgit.** { *; }

# Keep our model classes
-keep class com.murong.agent.core.provider.** { *; }
-keep class com.murong.agent.core.config.** { *; }
-keep class com.murong.agent.core.loop.** { *; }

# Project terminal history uses kotlinx.serialization models declared in the app UI layer.
# Keep them for release so ProjectTerminalSection can restore persisted state during startup.
-keep class com.murong.agent.ui.project.PersistedProjectTerminal** { *; }

# Release startup can crash inside Compose attachComposition when R8 strips runtime
# source-information helpers that compiled composables still call.
-keep class androidx.compose.runtime.ComposerKt { *; }
-keep class androidx.compose.runtime.internal.ComposableLambdaImpl { *; }
# JNI entry points are resolved by their Kotlin/JVM class and method names.
-keep class com.murong.agent.core.tool.BuiltinVisionNative { *; }
-keep class com.murong.agent.core.tool.BuiltinVisionTokenListener { *; }
-keep class com.google.ai.edge.litertlm.** { *; }

# RootAgentDisplayMain is launched externally by /system/bin/app_process. There is no
# ordinary JVM call edge for R8 to discover, so release builds must retain main(String[]).
-keep class com.murong.agent.shizuku.RootAgentDisplayMain {
    public static void main(java.lang.String[]);
}

# RootAgentDisplayMain reaches HiddenApiBypass by Class.forName()/getDeclaredMethod()
# from the app_process VM. Keep the whole small bridge: addHiddenApiExemptions returns boolean,
# and the library's static initialization also depends on its private reflected fields.
-keep class org.lsposed.hiddenapibypass.HiddenApiBypass { *; }
