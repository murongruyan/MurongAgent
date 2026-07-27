package com.murong.agent.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpeakerVerificationMathTest {
    @Test
    fun `voiceprint enrollment uses five samples`() {
        assertEquals(5, SPEAKER_ENROLLMENT_SAMPLE_COUNT)
    }

    @Test
    fun `cosine similarity distinguishes matching and opposing embeddings`() {
        assertEquals(1f, cosineSimilarity(floatArrayOf(1f, 2f), floatArrayOf(1f, 2f)), 0.0001f)
        assertEquals(-1f, cosineSimilarity(floatArrayOf(1f, 0f), floatArrayOf(-1f, 0f)))
        assertEquals(-1f, cosineSimilarity(floatArrayOf(1f), floatArrayOf(1f, 2f)))
    }

    @Test
    fun `silence trimmer removes long quiet margins but keeps speech`() {
        val samples = FloatArray(16_000 * 4)
        for (index in 16_000 until 32_000) samples[index] = 0.1f

        val trimmed = trimSilence(samples)

        assertTrue(trimmed.isNotEmpty())
        assertTrue(trimmed.size < samples.size)
        assertTrue(trimmed.any { it == 0.1f })
    }
}
