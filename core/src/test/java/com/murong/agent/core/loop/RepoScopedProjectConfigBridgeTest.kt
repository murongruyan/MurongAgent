package com.murong.agent.core.loop

import com.murong.agent.core.config.GlobalMemory
import com.murong.agent.core.config.SessionProjectConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RepoScopedProjectConfigBridgeTest {

    @Test
    fun normalizeSessionProjectConfigs_normalizesCanonicalConfigMap() {
        val normalized = normalizeSessionProjectConfigs(
            configs = mapOf(
                " /workspace/repo/ " to SessionProjectConfig(
                    projectMemories = listOf(
                        GlobalMemory(
                            id = "memory-2",
                            title = "Canonical Memory",
                            content = "Normalize me."
                        )
                    )
                ),
                "   " to SessionProjectConfig()
            ),
            normalizePath = { path ->
                path?.trim()?.trimEnd('/', '\\')?.takeIf { it.isNotBlank() }
            }
        )

        assertEquals(1, normalized.size)
        assertTrue(normalized.containsKey("/workspace/repo"))
        assertEquals("memory-2", normalized.getValue("/workspace/repo").projectMemories.single().id)
    }
}
