package com.murong.agent.lan

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LanWebEventHubTest {
    @Test
    fun `targeted events are visible only to their client and are not replayed when disabled`() {
        val hub = LanWebEventHub(maxReplayEvents = 8)
        val public = hub.publish("public", "{}")
        val targeted = hub.publish(
            type = "workspace_request",
            jsonData = "{\"secret\":true}",
            targetClientId = "client-a",
            replayable = false
        )

        assertTrue(targeted.isVisibleTo("client-a"))
        assertFalse(targeted.isVisibleTo("client-b"))
        assertEquals(emptyList(), hub.replayAfter(public.id, "client-a"))
        assertEquals(emptyList(), hub.replayAfter(public.id, "client-b"))
    }

    @Test
    fun `bounded replay reports a gap instead of pretending continuity`() {
        val hub = LanWebEventHub(maxReplayEvents = 3)
        hub.publish("state", "{\"n\":1}")
        hub.publish("state", "{\"n\":2}")
        hub.publish("state", "{\"n\":3}")
        hub.publish("state", "{\"n\":4}")

        assertNull(hub.replayAfter(0L))
        assertEquals(listOf(3L, 4L), hub.replayAfter(2L)?.map { it.id })
        assertEquals(4L, hub.currentSequence())
    }
}
