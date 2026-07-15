package com.murong.agent.ui.chat

internal class ReplayTriggerGate {
    private var armedForActiveReplay: Boolean = true
    private var armedForReattachReplay: Boolean = false
    private var lastObservedSessionId: String? = null
    private var lastAttachedSessionId: String? = null

    fun onScreenAttached(sessionId: String) {
        armedForReattachReplay = lastAttachedSessionId == sessionId
        lastAttachedSessionId = sessionId
    }

    fun shouldReplayOnScreenState(sessionId: String, isScreenActive: Boolean): Boolean {
        if (!isScreenActive) {
            armedForActiveReplay = true
            lastObservedSessionId = sessionId
            return false
        }
        val shouldReplay =
            armedForActiveReplay ||
                armedForReattachReplay ||
                lastObservedSessionId != sessionId
        armedForActiveReplay = false
        armedForReattachReplay = false
        lastObservedSessionId = sessionId
        return shouldReplay
    }

    fun markReplayHandled(sessionId: String) {
        armedForActiveReplay = false
        armedForReattachReplay = false
        lastObservedSessionId = sessionId
    }
}
