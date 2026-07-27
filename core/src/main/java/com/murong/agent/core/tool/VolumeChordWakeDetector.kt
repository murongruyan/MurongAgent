package com.murong.agent.core.tool

import android.view.KeyEvent
import kotlin.math.abs

internal data class VolumeChordDetectionResult(
    val consume: Boolean = false,
    val triggered: Boolean = false,
)

/**
 * Counts a chord only when volume-up and volume-down go down close together and both keys have
 * subsequently been released before the next chord. Three chords inside one short sequence wake
 * the assistant.
 */
internal class VolumeChordWakeDetector(
    private val chordWindowMillis: Long = 280L,
    private val sequenceWindowMillis: Long = 3_500L,
    private val requiredChords: Int = 3,
) {
    private var volumeUpPressed = false
    private var volumeDownPressed = false
    private var volumeUpDownAt = 0L
    private var volumeDownDownAt = 0L
    private var activeChord = false
    private var chordCount = 0
    private var sequenceStartedAt = 0L

    fun onKeyEvent(
        keyCode: Int,
        action: Int,
        eventTime: Long,
        repeatCount: Int,
    ): VolumeChordDetectionResult {
        if (
            keyCode != KeyEvent.KEYCODE_VOLUME_UP &&
            keyCode != KeyEvent.KEYCODE_VOLUME_DOWN
        ) {
            return VolumeChordDetectionResult()
        }
        if (repeatCount != 0) return VolumeChordDetectionResult(consume = activeChord)

        if (action == KeyEvent.ACTION_UP) {
            val consume = activeChord
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> volumeUpPressed = false
                KeyEvent.KEYCODE_VOLUME_DOWN -> volumeDownPressed = false
            }
            if (!volumeUpPressed && !volumeDownPressed) activeChord = false
            return VolumeChordDetectionResult(consume = consume)
        }
        if (action != KeyEvent.ACTION_DOWN) return VolumeChordDetectionResult()

        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                if (!volumeUpPressed) volumeUpDownAt = eventTime
                volumeUpPressed = true
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (!volumeDownPressed) volumeDownDownAt = eventTime
                volumeDownPressed = true
            }
        }
        if (
            activeChord ||
            !volumeUpPressed ||
            !volumeDownPressed ||
            abs(volumeUpDownAt - volumeDownDownAt) > chordWindowMillis
        ) {
            return VolumeChordDetectionResult(consume = activeChord)
        }

        activeChord = true
        if (
            sequenceStartedAt == 0L ||
            eventTime - sequenceStartedAt > sequenceWindowMillis
        ) {
            sequenceStartedAt = eventTime
            chordCount = 0
        }
        chordCount++
        val triggered = chordCount >= requiredChords
        if (triggered) {
            chordCount = 0
            sequenceStartedAt = 0L
        }
        return VolumeChordDetectionResult(consume = true, triggered = triggered)
    }

    fun reset() {
        volumeUpPressed = false
        volumeDownPressed = false
        volumeUpDownAt = 0L
        volumeDownDownAt = 0L
        activeChord = false
        chordCount = 0
        sequenceStartedAt = 0L
    }
}
