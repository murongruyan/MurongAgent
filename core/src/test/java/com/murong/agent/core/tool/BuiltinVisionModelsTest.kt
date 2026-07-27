package com.murong.agent.core.tool

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BuiltinVisionModelsTest {
    @Test
    fun llamaStreamIgnoresJsonNullInsteadOfPrintingNull() {
        assertEquals("", streamedJsonText(null))
        assertEquals("", streamedJsonText(JSONObject.NULL))
        assertEquals("", streamedJsonText(42))
        assertEquals("你好", streamedJsonText("你好"))
    }

    @Test
    fun huggingFaceManifestAcceptsCurrentLfsOidAndLegacyShaField() {
        val digest = "57d1997790d1744fba5b40a7317df71ea5e2acee28c47e78f0cce39c0703f8cf"
        fun lfs(vararg values: Pair<String, String>): JSONObject {
            val fields = values.toMap()
            return object : JSONObject() {
                override fun optString(name: String?): String = fields[name].orEmpty()
            }
        }

        assertEquals(
            digest,
            huggingFaceLfsSha256(lfs("oid" to digest)),
        )
        assertEquals(
            digest,
            huggingFaceLfsSha256(lfs("sha256" to "sha256:$digest")),
        )
        assertEquals("", huggingFaceLfsSha256(lfs("oid" to "git-sha1")))
    }

    @Test
    fun catalogOffersCodingVisionAndDesktopExperimentalModels() {
        assertEquals(13, BuiltinVisionModels.all.size)
        assertEquals(
            setOf(
                BuiltinVisionTier.GEMMA_12B_DESKTOP,
                BuiltinVisionTier.ULTRA_9B,
                BuiltinVisionTier.CODER_7B,
                BuiltinVisionTier.PRO_4B,
                BuiltinVisionTier.LITE_2B,
                BuiltinVisionTier.GEMMA_E4B,
                BuiltinVisionTier.GEMMA_E2B,
                BuiltinVisionTier.GLM_EDGE_V_2B,
                BuiltinVisionTier.GLM_EDGE_1_5B_CHAT,
                BuiltinVisionTier.DEEPSEEK_R1_QWEN_7B,
                BuiltinVisionTier.DEEPSEEK_R1_QWEN_1_5B,
                BuiltinVisionTier.DEEPSEEK_R1_LLAMA_8B,
                BuiltinVisionTier.DEEPSEEK_CODER_V2_LITE
            ),
            BuiltinVisionModels.all.mapTo(linkedSetOf()) { it.tier }
        )
        assertEquals(
            setOf(
                BuiltinVisionEngine.MNN,
                BuiltinVisionEngine.LITERT_LM,
                BuiltinVisionEngine.LLAMA_CPP
            ),
            BuiltinVisionModels.all.mapTo(linkedSetOf()) { it.engine }
        )
    }

    @Test
    fun capabilitiesAndThinkingModesMatchActualModels() {
        assertTrue(!BuiltinVisionModels.CODER_7B.supportsVision)
        assertTrue(BuiltinVisionModels.CODER_7B.reasoningModes.isEmpty())
        assertEquals(
            listOf("off", "on"),
            BuiltinVisionModels.ULTRA_9B.reasoningModes.map { it.id }
        )
        assertTrue(!BuiltinVisionModels.GEMMA_12B_DESKTOP.androidSupported)
        assertTrue(BuiltinVisionModels.GEMMA_12B_DESKTOP.unavailableReason.isNotBlank())
        assertTrue(BuiltinVisionModels.GLM_EDGE_V_2B.supportsVision)
        assertFalse(BuiltinVisionModels.GLM_EDGE_1_5B_CHAT.supportsVision)
        assertFalse(BuiltinVisionModels.DEEPSEEK_R1_QWEN_1_5B.supportsVision)
        assertEquals(
            "ggml-model-Q4_0.gguf",
            BuiltinVisionModels.GLM_EDGE_1_5B_CHAT.ggufModelFileName,
        )
        assertEquals(
            "zai-org/glm-edge-1.5b-chat-gguf",
            BuiltinVisionModels.GLM_EDGE_1_5B_CHAT.repository,
        )
        assertEquals(
            "ggml-model-Q4_K_M.gguf",
            BuiltinVisionModels.GLM_EDGE_V_2B.ggufModelFileName
        )
        assertEquals(
            "mmproj-model-f16.gguf",
            BuiltinVisionModels.GLM_EDGE_V_2B.ggufProjectorFileName
        )
        assertTrue(
            BuiltinVisionModels.DEEPSEEK_CODER_V2_LITE.recommendation
                .contains("16B 总参数 / 2.4B 激活参数")
        )
    }

    @Test
    fun catalogKeepsOnlyRequiredNamesAndResolvesMutableMetadataAtInstallTime() {
        BuiltinVisionModels.all.forEach { descriptor ->
            assertTrue(descriptor.totalBytes > 0)
            assertTrue(descriptor.files.isNotEmpty())
            descriptor.files.forEach { file ->
                assertEquals(0L, file.size)
                assertTrue(file.sha256.isEmpty())
                assertTrue(descriptor.fileUrl(file).startsWith("https://"))
            }
        }
    }

    @Test
    fun mnnRuntimeStartsFromLaunchConfigInsteadOfArchitectureMetadata() {
        BuiltinVisionModels.all
            .filter { it.engine == BuiltinVisionEngine.MNN }
            .forEach { descriptor ->
                assertEquals("config.json", descriptor.configFileName)
                assertTrue(descriptor.files.any { it.name == descriptor.configFileName })
            }
    }

    @Test
    fun mnnCacheFingerprintChangesWithRuntimeRelevantModelFiles() {
        val modelDirectory = createTempDirectory("murong-mnn-cache-test").toFile()
        try {
            File(modelDirectory, "config.json").writeText("""{"backend_type":"cpu"}""")
            File(modelDirectory, "llm_config.json").writeText("""{"model_type":"qwen3_5"}""")
            File(modelDirectory, "llm.mnn").writeBytes(byteArrayOf(1, 2, 3))

            val initial = mnnModelCacheFingerprint(
                modelDirectory,
                BuiltinVisionModels.ULTRA_9B
            )
            File(modelDirectory, "config.json").writeText("""{"backend_type":"opencl"}""")
            val changed = mnnModelCacheFingerprint(
                modelDirectory,
                BuiltinVisionModels.ULTRA_9B
            )

            assertEquals(20, initial.length)
            assertTrue(initial != changed)
            assertTrue(BUILTIN_MNN_CACHE_SCHEMA.contains("config-entry"))
        } finally {
            modelDirectory.deleteRecursively()
        }
    }

    @Test
    fun recommendationKeepsApiFallbackForInsufficientMemory() {
        assertEquals(
            BuiltinVisionTier.CODER_7B,
            BuiltinVisionModels.recommend(
                totalBytes = 12L * 1024 * 1024 * 1024,
                availableBytes = 6L * 1024 * 1024 * 1024
            ).recommendedTier
        )
        assertEquals(
            BuiltinVisionTier.GEMMA_E2B,
            BuiltinVisionModels.recommend(
                totalBytes = 4L * 1024 * 1024 * 1024,
                availableBytes = 2L * 1024 * 1024 * 1024
            ).recommendedTier
        )
        assertEquals(
            null,
            BuiltinVisionModels.recommend(
                totalBytes = 3L * 1024 * 1024 * 1024,
                availableBytes = 512L * 1024 * 1024
            ).recommendedTier
        )
    }

    @Test
    fun localRuntimeDefaultsToDetectedPerformanceCluster() {
        val topology = BuiltinVisionRuntime.cpuTopology()
        val settings = BuiltinLocalRuntimeSettings().normalized()

        assertEquals(
            BuiltinLocalCpuCorePolicy.PERFORMANCE_CLUSTER,
            settings.cpuCorePolicy
        )
        assertTrue(topology.performanceCoreIds.isNotEmpty())
        assertTrue(topology.performanceCoreIds.all { it in topology.allCoreIds })
        assertEquals(topology.recommendedThreadCount, settings.cpuThreads)
    }

    @Test
    fun automaticMnnBackendKeepsQwen35OnStableCpuPath() {
        val automatic = BuiltinLocalRuntimeSettings(
            backend = BuiltinLocalComputeBackend.AUTO
        )

        assertEquals(
            listOf("cpu"),
            automatic.mnnBackendOrder(BuiltinVisionModels.ULTRA_9B)
        )
        assertEquals(
            listOf("cpu"),
            automatic.mnnBackendOrder(BuiltinVisionModels.PRO_4B)
        )
        assertEquals(
            listOf("opencl", "cpu"),
            automatic.mnnBackendOrder(BuiltinVisionModels.CODER_7B)
        )
        assertEquals(
            listOf("opencl"),
            automatic.copy(backend = BuiltinLocalComputeBackend.GPU)
                .mnnBackendOrder(BuiltinVisionModels.ULTRA_9B)
        )
    }

    @Test
    fun llamaCppModelsResolveGlobalGpuPreferenceToCpuRuntime() {
        val gpuRequested = BuiltinLocalRuntimeSettings(
            backend = BuiltinLocalComputeBackend.GPU,
            cpuCorePolicy = BuiltinLocalCpuCorePolicy.PERFORMANCE_CLUSTER,
            cpuThreads = 3,
        )
        val automatic = gpuRequested.copy(backend = BuiltinLocalComputeBackend.AUTO)

        assertEquals(
            automatic.llamaCppSignature(),
            gpuRequested.llamaCppSignature(),
        )
        assertTrue(gpuRequested.llamaCppSignature().startsWith("CPU:"))
    }

    @Test
    fun runtimeOverlayLeavesAttentionModeToOfficialModelConfig() {
        val config = Json.parseToJsonElement(
            BuiltinVisionRuntime.mnnRuntimeConfig(
                cacheDirectory = File("build/test-mnn-cache"),
                backend = "cpu",
                cpuThreads = 4,
                cpuCorePolicy = BuiltinLocalCpuCorePolicy.PERFORMANCE_CLUSTER
            )
        ).jsonObject

        assertEquals("cpu", config["backend_type"].toString().trim('"'))
        assertFalse("attention_mode" in config)
    }

    @Test
    fun llamaRuntimePassesResolvedThinkingModeToTheOfficialChatTemplate() {
        val source = File("src/main/java/com/murong/agent/core/tool/BuiltinVisionRuntime.kt")
            .readText()

        assertTrue(source.contains("enableThinking = enableThinking"))
        assertTrue(source.contains("\"chat_template_kwargs\""))
        assertTrue(source.contains("JSONObject().put(\"enable_thinking\", enableThinking)"))
    }

    @Test
    fun tokenStreamGuardHoldsBackAndRejectsCollapsedTokenRun() {
        val emitted = mutableListOf<String>()
        val guard = BuiltinLocalTokenStreamGuard(
            emit = emitted::add,
            repeatedTokenLimit = 4
        )

        repeat(3) {
            assertTrue(guard.accept("!", tokenId = 0))
        }
        assertTrue(emitted.isEmpty())
        assertFalse(guard.accept("!", tokenId = 0))
        assertNotNull(guard.failureReason)
        guard.finish()
        assertTrue(emitted.isEmpty())
    }

    @Test
    fun tokenStreamGuardPreservesHealthyStreamingText() {
        val emitted = mutableListOf<String>()
        val guard = BuiltinLocalTokenStreamGuard(emitted::add)

        assertTrue(guard.accept("你", tokenId = 10))
        assertTrue(guard.accept("好", tokenId = 11))
        assertEquals(listOf("你"), emitted)
        guard.finish()
        assertEquals(listOf("你", "好"), emitted)
    }

    @Test
    fun localGenerationCancellationIsIdempotent() {
        val cancellation = BuiltinLocalGenerationCancellation()

        assertFalse(cancellation.isCancelled)
        assertTrue(cancellation.cancel())
        assertTrue(cancellation.isCancelled)
        assertFalse(cancellation.cancel())
    }
}
