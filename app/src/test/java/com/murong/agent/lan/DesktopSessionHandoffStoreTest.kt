package com.murong.agent.lan

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopSessionHandoffStoreTest {
    @Test
    fun `handoff token and local mapping survive process recreation`() {
        val file = createTempDirectory("desktop-handoff-store").resolve("handoffs.json").toFile()
        val record = DesktopSessionHandoffRecord(
            desktopSessionId = "session_0001",
            desktopTitle = "Desktop task",
            localSessionId = "phone_0001",
            handoffToken = "handoff-" + "b".repeat(64),
            sourcePlatform = "linux",
            sourceArchitecture = "arm64",
            startedAt = 1_000
        )

        DesktopSessionHandoffStore(file).put(record)

        assertEquals(listOf(record), DesktopSessionHandoffStore(file).load())
    }

    @Test
    fun `corrupt or capability-shaped invalid state is never restored`() {
        val directory = createTempDirectory("desktop-handoff-corrupt").toFile()
        val file = File(directory, "handoffs.json")
        file.writeText(
            """{"schemaVersion":1,"records":[{"version":1,"desktopSessionId":"session_0001","desktopTitle":"x","localSessionId":"phone_0001","handoffToken":"not-a-token","sourcePlatform":"windows","sourceArchitecture":"amd64","startedAt":1}]}"""
        )

        assertTrue(DesktopSessionHandoffStore(file).load().isEmpty())
    }
}
