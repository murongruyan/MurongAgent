package com.murong.agent.core.tool

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidGuiAccessibilityAccessTest {
    private val murongService =
        "com.murong.agent/com.murong.agent.core.tool.AndroidGuiAccessibilityService"

    @Test
    fun `merge preserves existing services and appends Murong once`() {
        val existing =
            "com.omarea.vtools/com.omarea.vtools.AccessibilitySceneMode:$murongService"
        val merged = AndroidGuiAccessibilityAccess.mergeEnabledAccessibilityServices(
            existing,
            murongService
        )

        assertEquals(existing, merged)
        assertEquals(2, merged.split(':').size)
    }

    @Test
    fun `merge handles empty and null settings without creating null service`() {
        listOf(null, "", "null", " NULL ").forEach { existing ->
            assertEquals(
                murongService,
                AndroidGuiAccessibilityAccess.mergeEnabledAccessibilityServices(
                    existing,
                    murongService
                )
            )
        }
    }

    @Test
    fun `root command quotes the merged list and reports an exit marker`() {
        val existing = "com.example/com.example.Service'Name"
        val merged = AndroidGuiAccessibilityAccess.mergeEnabledAccessibilityServices(
            existing,
            murongService
        )
        val command =
            AndroidGuiAccessibilityAccess.buildRootAccessibilityEnableCommand(merged)

        assertTrue(
            command.contains(
                "settings put --user current secure enabled_accessibility_services"
            )
        )
        assertTrue(
            command.contains("settings put --user current secure accessibility_enabled 1")
        )
        assertTrue(command.contains("'\\''"))
        assertFalse(command.startsWith("sh -c "))
        assertFalse(command.contains("enabled_accessibility_services null"))
        assertEquals(
            0,
            AndroidGuiAccessibilityAccess.parseRootExitCode(
                "ignored\n__MURONG_ACCESSIBILITY_EXIT__0"
            )
        )
        assertEquals(
            7,
            AndroidGuiAccessibilityAccess.parseRootExitCode(
                "__MURONG_ACCESSIBILITY_EXIT__7"
            )
        )
    }

    @Test
    fun `Oplus compatibility commands only change the exact guide component`() {
        val fullComponent =
            "com.oplus.safecenter/com.oplus.safecenter.accessibility.AccessibilityGuideCloseActivity"
        val shortComponent =
            "com.oplus.safecenter/.accessibility.AccessibilityGuideCloseActivity"
        val disable = AndroidGuiAccessibilityAccess.buildOplusGuideDisableCommand(0)
        val restore = AndroidGuiAccessibilityAccess.buildOplusGuideRestoreCommand(0)

        assertTrue(AndroidGuiAccessibilityAccess.isKnownOplusGuideComponent(fullComponent))
        assertTrue(AndroidGuiAccessibilityAccess.isKnownOplusGuideComponent(shortComponent))
        assertFalse(
            AndroidGuiAccessibilityAccess.isKnownOplusGuideComponent(
                "com.oplus.safecenter/.OtherActivity"
            )
        )
        assertTrue(disable.contains("pm disable --user 0"))
        assertFalse(disable.contains("pm disable-user"))
        assertTrue(disable.contains(fullComponent))
        assertFalse(disable.contains("pm disable --user 0 'com.oplus.safecenter'"))
        assertTrue(restore.contains("pm default-state --user 0"))
        assertTrue(restore.contains(fullComponent))
    }

    @Test
    fun `Oplus resolver uses the final nonblank line from vendor output`() {
        val component =
            "com.oplus.safecenter/.accessibility.AccessibilityGuideCloseActivity"

        assertEquals(
            component,
            AndroidGuiAccessibilityAccess.parseResolvedActivity(
                "priority=0 preferredOrder=0 isDefault=true\n$component\n"
            )
        )
        assertEquals(
            "No activity found",
            AndroidGuiAccessibilityAccess.parseResolvedActivity(
                "\nNo activity found\n"
            )
        )
    }
}
