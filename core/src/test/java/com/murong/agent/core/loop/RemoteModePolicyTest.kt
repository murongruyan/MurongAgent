package com.murong.agent.core.loop

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RemoteModePolicyTest {

    @Test
    fun hasRemoteTaskRepositoryContext_returnsTrueWhenOwnerAndRepoPresent() {
        val state = SessionState(
            remoteTaskRepositoryOwner = "murong",
            remoteTaskRepositoryName = "agent"
        )

        assertTrue(hasRemoteTaskRepositoryContext(state))
        assertFalse(shouldExposeLocalProjectTools(state))
    }

    @Test
    fun hasRemoteTaskRepositoryContext_returnsFalseWhenRemoteRepoIncomplete() {
        val missingRepoState = SessionState(
            remoteTaskRepositoryOwner = "murong",
            remoteTaskRepositoryName = ""
        )
        val missingOwnerState = SessionState(
            remoteTaskRepositoryOwner = "",
            remoteTaskRepositoryName = "agent"
        )

        assertFalse(hasRemoteTaskRepositoryContext(missingRepoState))
        assertFalse(hasRemoteTaskRepositoryContext(missingOwnerState))
        assertTrue(shouldExposeLocalProjectTools(missingRepoState))
        assertTrue(shouldExposeLocalProjectTools(missingOwnerState))
    }

    @Test
    fun hasRemoteTaskRepositoryContext_trimsWhitespaceBeforeDecidingIsolation() {
        val state = SessionState(
            remoteTaskRepositoryOwner = "  murong  ",
            remoteTaskRepositoryName = "  agent  "
        )
        val blankState = SessionState(
            remoteTaskRepositoryOwner = "   ",
            remoteTaskRepositoryName = "\t"
        )

        assertTrue(hasRemoteTaskRepositoryContext(state))
        assertFalse(shouldExposeLocalProjectTools(state))
        assertFalse(hasRemoteTaskRepositoryContext(blankState))
        assertTrue(shouldExposeLocalProjectTools(blankState))
    }
}
