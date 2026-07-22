package com.murong.agent.core.loop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class DeviceSyncTimestampTest {
    private val previous = PersistedSession(
        id = "session",
        title = "unchanged",
        createdAt = 100,
        updatedAt = 200,
        providerId = "openai-compatible",
        modelName = "model",
        messages = emptyList(),
    )

    @Test
    fun exportFlushDoesNotChangeTimestampWhenSessionContentIsUnchanged() {
        val result = preserveSessionTimestampWhenUnchanged(
            previous,
            previous.copy(updatedAt = 300),
        )

        assertSame(previous, result)
        assertEquals(200, result.updatedAt)
    }

    @Test
    fun actualSessionChangeKeepsTheNewTimestamp() {
        val changed = previous.copy(title = "changed", updatedAt = 300)

        assertSame(changed, preserveSessionTimestampWhenUnchanged(previous, changed))
        assertEquals(300, changed.updatedAt)
    }
}
