package com.murong.agent.ui.assistant

import org.junit.Assert.assertEquals
import org.junit.Test

class AssistantOffscreenDisplaySettingsTest {
    @Test
    fun defaultsKeepOriginalResolutionAndRefreshRate() {
        val settings = AssistantOffscreenDisplaySettings().normalized()

        assertEquals(0, settings.maxLongEdge)
        assertEquals(0, settings.targetFps)
        assertEquals(92, settings.jpegQuality)
        assertEquals(0, settings.targetBitrateMbps)
    }

    @Test
    fun invalidPersistedValuesAreNormalized() {
        val settings = AssistantOffscreenDisplaySettings(
            maxLongEdge = 12,
            targetFps = 999,
            jpegQuality = 120,
            targetBitrateMbps = 1,
        ).normalized()

        assertEquals(0, settings.maxLongEdge)
        assertEquals(0, settings.targetFps)
        assertEquals(100, settings.jpegQuality)
        assertEquals(0, settings.targetBitrateMbps)
    }
}
