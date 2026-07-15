package com.murong.agent.ui.chat

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReplayTriggerGateTest {

    @Test
    fun shouldReplayOnScreenState_replaysOnFirstActiveAndAfterReattach() {
        val gate = ReplayTriggerGate()

        assertTrue(gate.shouldReplayOnScreenState(sessionId = "s1", isScreenActive = true))
        assertFalse(gate.shouldReplayOnScreenState(sessionId = "s1", isScreenActive = true))
        assertFalse(gate.shouldReplayOnScreenState(sessionId = "s1", isScreenActive = false))
        assertTrue(gate.shouldReplayOnScreenState(sessionId = "s1", isScreenActive = true))
    }

    @Test
    fun shouldReplayOnScreenState_replaysWhenSessionChangesWhileActive() {
        val gate = ReplayTriggerGate()

        assertTrue(gate.shouldReplayOnScreenState(sessionId = "s1", isScreenActive = true))
        assertTrue(gate.shouldReplayOnScreenState(sessionId = "s2", isScreenActive = true))
        assertFalse(gate.shouldReplayOnScreenState(sessionId = "s2", isScreenActive = true))
    }

    @Test
    fun markReplayHandled_suppressesImmediateDuplicateReplay() {
        val gate = ReplayTriggerGate()

        gate.markReplayHandled("s1")

        assertFalse(gate.shouldReplayOnScreenState(sessionId = "s1", isScreenActive = true))
        assertFalse(gate.shouldReplayOnScreenState(sessionId = "s1", isScreenActive = true))
    }

    @Test
    fun onScreenAttached_replaysWhenSameSessionReattachesWhileStillActive() {
        val gate = ReplayTriggerGate()

        gate.onScreenAttached("s1")
        assertTrue(gate.shouldReplayOnScreenState(sessionId = "s1", isScreenActive = true))
        gate.onScreenAttached("s1")

        assertTrue(gate.shouldReplayOnScreenState(sessionId = "s1", isScreenActive = true))
        assertFalse(gate.shouldReplayOnScreenState(sessionId = "s1", isScreenActive = true))
    }

    @Test
    fun onScreenAttached_afterHandledLoadSessionDoesNotDuplicateReplayForNewSession() {
        val gate = ReplayTriggerGate()

        gate.markReplayHandled("s2")
        gate.onScreenAttached("s2")

        assertFalse(gate.shouldReplayOnScreenState(sessionId = "s2", isScreenActive = true))
    }

    @Test
    fun onScreenAttached_afterHandledLoadSessionStillReplaysOnLaterRealReattach() {
        val gate = ReplayTriggerGate()

        gate.markReplayHandled("s2")
        gate.onScreenAttached("s2")
        assertFalse(gate.shouldReplayOnScreenState(sessionId = "s2", isScreenActive = true))

        gate.onScreenAttached("s2")

        assertTrue(gate.shouldReplayOnScreenState(sessionId = "s2", isScreenActive = true))
        assertFalse(gate.shouldReplayOnScreenState(sessionId = "s2", isScreenActive = true))
    }
}
