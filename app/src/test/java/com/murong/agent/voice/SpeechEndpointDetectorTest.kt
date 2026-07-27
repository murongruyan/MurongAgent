package com.murong.agent.voice

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpeechEndpointDetectorTest {
    @Test
    fun `unchanged partial text does not keep resetting silence`() {
        val detector = SpeechEndpointDetector(silenceMillis = 1_000L)
        detector.reset(10_000L)

        assertFalse(detector.shouldStop(10_100L, 0f, "你"))
        assertFalse(detector.shouldStop(10_900L, 0f, "你"))
        assertTrue(detector.shouldStop(11_100L, 0f, "你"))
    }

    @Test
    fun `changed partial extends the speech endpoint`() {
        val detector = SpeechEndpointDetector(silenceMillis = 1_000L)
        detector.reset(20_000L)

        assertFalse(detector.shouldStop(20_100L, 0f, "你"))
        assertFalse(detector.shouldStop(20_900L, 0f, "你好"))
        assertFalse(detector.shouldStop(21_800L, 0f, "你好"))
        assertTrue(detector.shouldStop(21_901L, 0f, "你好"))
    }

    @Test
    fun `persistent background volume does not block endpoint after transcript stabilizes`() {
        val detector = SpeechEndpointDetector(
            silenceMillis = 1_000L,
            volumeThreshold = 0.025f,
        )
        detector.reset(25_000L)

        assertFalse(detector.shouldStop(25_100L, 0.2f, "设置"))
        assertFalse(detector.shouldStop(25_900L, 0.2f, "设置一个日程"))
        assertFalse(detector.shouldStop(26_899L, 0.2f, "设置一个日程"))
        assertTrue(detector.shouldStop(26_900L, 0.2f, "设置一个日程"))
    }

    @Test
    fun `silent input still ends at the hard deadline`() {
        val detector = SpeechEndpointDetector(maximumListeningMillis = 3_000L)
        detector.reset(30_000L)

        assertFalse(detector.shouldStop(32_999L, 0f, ""))
        assertTrue(detector.shouldStop(33_000L, 0f, ""))
    }
}
