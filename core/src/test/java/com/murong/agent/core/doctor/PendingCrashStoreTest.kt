package com.murong.agent.core.doctor

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PendingCrashStoreTest {

    @Test
    fun persistLoadAndConsumePendingCrash_roundTripsSanitizedReport() {
        val tempDir = createTempDirectory().toFile()
        val store = PendingCrashStore(tempDir.resolve("pending-crash.json"))

        store.persistPendingCrash(
            throwable = IllegalStateException(
                "Bearer abcdefghijklmn at /home/alice/repo for alice@example.com"
            ),
            threadName = "worker-1",
            timestamp = 123L
        )

        val loaded = store.loadPendingCrash()

        assertNotNull(loaded)
        assertEquals(123L, loaded.timestamp)
        assertEquals("worker-1", loaded.threadName)
        assertEquals("java.lang.IllegalStateException", loaded.exceptionType)
        assertTrue(loaded.message.contains("[REDACTED_BEARER]"))
        assertTrue(loaded.message.contains("[REDACTED_PATH]"))
        assertTrue(loaded.message.contains("[REDACTED_EMAIL]"))
        assertFalse(loaded.stackTrace.contains("/home/alice/repo"))

        val consumed = store.consumePendingCrash()
        assertNotNull(consumed)
        assertFalse(tempDir.resolve("pending-crash.json").exists())
    }
}
