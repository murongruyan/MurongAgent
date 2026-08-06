package com.murong.agent.ui.chat

import com.murong.agent.core.loop.MessageImageAttachmentUi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ChatRecentImagePresentationTest {
    @Test
    fun recentImageAttachmentKey_deduplicatesSameImageAcrossDifferentCacheCopies() {
        val first = MessageImageAttachmentUi(
            id = "first",
            fileName = "screen.png",
            mimeType = "image/png",
            localCachePath = "/cache/first.png",
            width = 1440,
            height = 3136,
            sizeBytes = 2048
        )
        val second = first.copy(id = "second", localCachePath = "/cache/second.png")

        assertEquals(recentImageAttachmentKey(first), recentImageAttachmentKey(second))
        assertNotEquals(
            recentImageAttachmentKey(first),
            recentImageAttachmentKey(second.copy(sizeBytes = 4096))
        )
    }
}
