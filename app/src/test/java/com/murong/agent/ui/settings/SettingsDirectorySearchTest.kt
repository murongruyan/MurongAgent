package com.murong.agent.ui.settings

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsDirectorySearchTest {
    private val localRuntimeFields = arrayOf(
        "本地模型与推理",
        "模型安装、CPU 核心与线程、GPU 后端、LiteRT、MNN 和性能模式",
    )

    @Test
    fun `local runtime settings can be found by user-facing and engine keywords`() {
        listOf("本地模型", "CPU", "gpu", "线程", "LiteRT", "MNN", "性能模式").forEach { query ->
            assertTrue(
                matchesSettingsQuery(query, *localRuntimeFields),
                "Expected local runtime settings to match $query",
            )
        }
    }

    @Test
    fun `unrelated setting does not match local runtime entry`() {
        assertFalse(matchesSettingsQuery("声纹", *localRuntimeFields))
    }
}
