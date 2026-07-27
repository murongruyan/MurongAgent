package com.murong.agent.core.tool

import android.view.KeyEvent
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidAssistantShortcutTest {
    @Test
    fun `three complete volume chords trigger once`() {
        val detector = VolumeChordWakeDetector()

        assertFalse(performChord(detector, 1_000L))
        assertFalse(performChord(detector, 1_700L))
        assertTrue(performChord(detector, 2_400L))
        assertFalse(performChord(detector, 3_100L))
    }

    @Test
    fun `keys pressed too far apart are not a chord`() {
        val detector = VolumeChordWakeDetector(chordWindowMillis = 200L)

        detector.onKeyEvent(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.ACTION_DOWN, 1_000L, 0)
        val result = detector.onKeyEvent(
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.ACTION_DOWN,
            1_250L,
            0,
        )

        assertFalse(result.consume)
        assertFalse(result.triggered)
    }

    @Test
    fun `a held chord cannot be counted repeatedly`() {
        val detector = VolumeChordWakeDetector()
        detector.onKeyEvent(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.ACTION_DOWN, 1_000L, 0)
        detector.onKeyEvent(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.ACTION_DOWN, 1_050L, 0)

        repeat(5) { index ->
            val repeated = detector.onKeyEvent(
                KeyEvent.KEYCODE_VOLUME_DOWN,
                KeyEvent.ACTION_DOWN,
                1_100L + index,
                0,
            )
            assertFalse(repeated.triggered)
        }
    }

    @Test
    fun `sequence expires before third chord`() {
        val detector = VolumeChordWakeDetector(sequenceWindowMillis = 1_000L)

        assertFalse(performChord(detector, 1_000L))
        assertFalse(performChord(detector, 1_400L))
        assertFalse(performChord(detector, 2_500L))
    }

    private fun performChord(detector: VolumeChordWakeDetector, at: Long): Boolean {
        detector.onKeyEvent(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.ACTION_DOWN, at, 0)
        val result = detector.onKeyEvent(
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.ACTION_DOWN,
            at + 40L,
            0,
        )
        detector.onKeyEvent(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.ACTION_UP, at + 100L, 0)
        detector.onKeyEvent(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.ACTION_UP, at + 110L, 0)
        return result.triggered
    }
}
