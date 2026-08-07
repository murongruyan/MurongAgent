package com.murong.agent.core.provider

import kotlin.test.Test
import kotlin.test.assertEquals

class OpenAIImageGenerationClientTest {
    @Test
    fun normalizeImageSize_acceptsOfficialNative4KAndRejectsInvalidGeometry() {
        assertEquals("3840x2160", OpenAIImageGenerationClient.normalizeImageSize("3840x2160"))
        assertEquals("2160x3840", OpenAIImageGenerationClient.normalizeImageSize("2160x3840"))
        assertEquals("2048x2048", OpenAIImageGenerationClient.normalizeImageSize("2048x2048"))
        assertEquals("1024x1024", OpenAIImageGenerationClient.normalizeImageSize("3841x2160"))
        assertEquals("1024x1024", OpenAIImageGenerationClient.normalizeImageSize("256x256"))
        assertEquals("1024x1024", OpenAIImageGenerationClient.normalizeImageSize("3840x1264"))
    }
}
