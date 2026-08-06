package com.murong.agent.core.provider

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderTransportTest {
    @Test
    fun providerFailureUserVisibleMessage_imageSchemaFailure_isFriendlyAndRedacted() {
        val raw = "messages.content.type must be one of [text] and received image_url"
        val message = providerFailureUserVisibleMessage(
            ProviderHttpException(statusCode = 400, body = raw),
            hasImages = true
        )

        assertTrue(message.contains("不支持图片输入"))
        assertFalse(message.contains("messages.content.type"))
        assertFalse(message.contains("image_url"))
    }

    @Test
    fun providerFailureUserVisibleMessage_serverFailure_doesNotExposeResponseBody() {
        val message = providerFailureUserVisibleMessage(
            ProviderHttpException(statusCode = 503, body = "internal request id secret-request"),
            hasImages = false
        )

        assertTrue(message.contains("暂时不可用"))
        assertFalse(message.contains("secret-request"))
    }
}
