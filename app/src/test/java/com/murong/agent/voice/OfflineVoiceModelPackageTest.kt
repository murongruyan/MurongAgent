package com.murong.agent.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OfflineVoiceModelPackageTest {
    @Test
    fun `model archive accepts ordinary relative entries`() {
        assertTrue(isSafeOfflineVoiceArchiveEntryName("./"))
        assertTrue(isSafeOfflineVoiceArchiveEntryName("./sensevoice/model.int8.onnx"))
        assertTrue(isSafeOfflineVoiceArchiveEntryName("sensevoice/model.int8.onnx"))
        assertTrue(isSafeOfflineVoiceArchiveEntryName("sensevoice/tokens.txt"))
    }

    @Test
    fun `model archive rejects traversal and absolute paths`() {
        assertFalse(isSafeOfflineVoiceArchiveEntryName("../model.int8.onnx"))
        assertFalse(isSafeOfflineVoiceArchiveEntryName("/data/local/tmp/model.int8.onnx"))
        assertFalse(isSafeOfflineVoiceArchiveEntryName("C:/tmp/model.int8.onnx"))
        assertFalse(isSafeOfflineVoiceArchiveEntryName("model/../../tokens.txt"))
    }

    @Test
    fun `streaming model descriptor pins the verified official archive`() {
        assertEquals(133_898_007L, OFFLINE_VOICE_MODEL_ARCHIVE_BYTES)
        assertTrue(OfflineVoiceModelDescriptor.SOURCE_URL.startsWith("https://github.com/k2-fsa/sherpa-onnx/releases/"))
        assertTrue(OfflineVoiceModelDescriptor.SOURCE_URL.contains("x-asr-160ms-streaming"))
        assertTrue(OfflineVoiceModelDescriptor.SOURCE_URL.contains("-zh-en-punct-int8-"))
        assertEquals("8a6fca056e1a342546edd78be4d50274e2c01898e7b8ae8fc336f6410319c399", OfflineVoiceModelDescriptor.ARCHIVE_SHA256)
        assertEquals("encoder.int8.onnx", OfflineVoiceModelDescriptor.ENCODER_FILE_NAME)
        assertEquals("decoder.onnx", OfflineVoiceModelDescriptor.DECODER_FILE_NAME)
        assertEquals("joiner.int8.onnx", OfflineVoiceModelDescriptor.JOINER_FILE_NAME)
    }
}
