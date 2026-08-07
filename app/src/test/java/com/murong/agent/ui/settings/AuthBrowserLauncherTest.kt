package com.murong.agent.ui.settings

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthBrowserLauncherTest {
    @Test
    fun realmeAuth_usesVendorFloatingWindowWithoutHardcodedBrowser() {
        val command = buildAuthBrowserFloatingWindowCommand(
            rawUri = "https://auth.openai.com/codex/device?code=ABCD&source=murong",
            manufacturer = "realme",
        )

        assertTrue(command.startsWith("/system/bin/am start"))
        assertTrue(command.contains("--windowingMode 100"))
        assertTrue(command.contains("--activity-multiple-task"))
        assertTrue(command.contains("-a android.intent.action.VIEW"))
        assertTrue(command.contains("'https://auth.openai.com/codex/device?code=ABCD&source=murong'"))
        assertFalse(command.contains("LAUNCH_ADJACENT"))
        assertFalse(command.contains("mark.via"))
        assertFalse(command.contains("com.quark.browser"))
    }

    @Test
    fun shellQuote_keepsMetacharactersInsideOneArgument() {
        assertTrue(shellSingleQuote("a'b;$(id)") == "'a'\"'\"'b;$(id)'")
    }

    @Test
    fun commandResult_rejectsShellErrors() {
        assertTrue(authBrowserCommandStarted("Starting: Intent { act=android.intent.action.VIEW }\nStatus: ok"))
        assertFalse(authBrowserCommandStarted("Error: Activity not started"))
    }
}
