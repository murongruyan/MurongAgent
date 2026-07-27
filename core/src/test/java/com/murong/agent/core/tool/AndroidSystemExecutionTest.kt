package com.murong.agent.core.tool

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidSystemExecutionTest {

    @Test
    fun `standard and accessibility modes never pretend to be a system shell`() {
        try {
            AndroidSystemExecution.setPreferredMode(AndroidExecutionMode.STANDARD)
            assertFalse(AndroidSystemExecution.isSystemCommandAvailable())
            assertTrue(AndroidSystemExecution.unavailableReason().contains("标准应用"))

            AndroidSystemExecution.setPreferredMode(AndroidExecutionMode.ACCESSIBILITY)
            assertFalse(AndroidSystemExecution.isSystemCommandAvailable())
            assertTrue(AndroidSystemExecution.unavailableReason().contains("无障碍"))
        } finally {
            AndroidSystemExecution.setPreferredMode(AndroidExecutionMode.AUTO)
        }
    }

    @Test
    fun `explicit shizuku mode uses only the authorized external bridge`() {
        val bridge = object : ExternalSystemCommandBridge {
            override val label: String = "test-shizuku"
            override fun isAvailable(): Boolean = true
            override fun execute(command: String, timeoutSeconds: Int): String =
                "$label:$command:$timeoutSeconds"
        }
        try {
            AndroidSystemExecution.installExternalBridge(bridge)
            AndroidSystemExecution.setPreferredMode(AndroidExecutionMode.SHIZUKU)

            assertEquals(AndroidSystemExecutionRoute.SHIZUKU, AndroidSystemExecution.resolvedRoute())
            assertEquals(
                "test-shizuku:id:7",
                AndroidSystemExecution.executeSystemCommand("id", 7)
            )
        } finally {
            AndroidSystemExecution.installExternalBridge(null)
            AndroidSystemExecution.setPreferredMode(AndroidExecutionMode.AUTO)
        }
    }
}
