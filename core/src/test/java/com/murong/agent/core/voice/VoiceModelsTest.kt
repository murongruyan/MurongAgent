package com.murong.agent.core.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VoiceModelsTest {
    @Test
    fun `automatic recognition falls back to the installed offline model`() {
        assertEquals(
            VoiceRecognitionProvider.OFFLINE,
            selectAvailableVoiceRecognitionProvider(
                preferred = VoiceRecognitionProvider.AUTOMATIC,
                systemAvailable = false,
                offlineAvailable = true,
            ),
        )
        assertEquals(
            VoiceRecognitionProvider.SYSTEM,
            selectAvailableVoiceRecognitionProvider(
                preferred = VoiceRecognitionProvider.AUTOMATIC,
                systemAvailable = true,
                offlineAvailable = true,
            ),
        )
    }

    @Test
    fun `explicit recognition choice does not silently switch engines`() {
        assertEquals(
            null,
            selectAvailableVoiceRecognitionProvider(
                preferred = VoiceRecognitionProvider.OFFLINE,
                systemAvailable = true,
                offlineAvailable = false,
            ),
        )
        assertEquals(
            null,
            selectAvailableVoiceRecognitionProvider(
                preferred = VoiceRecognitionProvider.SYSTEM,
                systemAvailable = false,
                offlineAvailable = true,
            ),
        )
    }

    @Test
    fun `playback text excludes code and terminal output`() {
        val text = """
            # 结果
            已完成。
            ```bash
            rm -rf /tmp/demo
            ```
            $ sdcard > pkg install python
            工具输出：ignored
            下一步请检查设置。
        """.trimIndent()
        assertEquals("结果 已完成。 下一步请检查设置。", sanitizeVoicePlaybackText(text))
    }

    @Test
    fun `recognition cannot jump from idle to finalizing`() {
        assertFalse(isValidVoiceRecognitionTransition(VoiceRecognitionState.IDLE, VoiceRecognitionState.FINALIZING))
        assertTrue(isValidVoiceRecognitionTransition(VoiceRecognitionState.LISTENING, VoiceRecognitionState.FINALIZING))
    }

    @Test
    fun `recognition can stop while microphone preparation is still pending`() {
        assertTrue(isValidVoiceRecognitionTransition(VoiceRecognitionState.PREPARING, VoiceRecognitionState.FINALIZING))
    }

    @Test
    fun `streaming recognition joins Chinese tokens but preserves English word spaces`() {
        assertEquals("你好你好你好", normalizeVoiceRecognitionText("你 好 你 好 你 好"))
        assertEquals("你好，世界", normalizeVoiceRecognitionText("你 好 ， 世 界"))
        assertEquals("你好 HELLO WORLD", normalizeVoiceRecognitionText("你 好   HELLO WORLD"))
    }

    @Test
    fun `playback text removes raw urls and preserves readable link labels`() {
        assertEquals(
            "请查看官方文档，或访问。",
            sanitizeVoicePlaybackText("请查看[官方文档](https://example.com/docs)，或访问 https://example.com/help。")
        )
    }

    @Test
    fun `long playback is split at sentence boundaries`() {
        val segments = splitVoicePlaybackText("第一句。第二句。第三句。", maxCharactersPerSegment = 9)

        assertEquals(listOf("第一句。 第二句。", "第三句。"), segments)
        assertTrue(segments.all { it.length <= 9 })
    }

    @Test
    fun `playback continuation starts only after the bounded readable batch`() {
        val first = voicePlaybackTextChunk("一二三四五六", maxCharacters = 4)
        val second = voicePlaybackTextChunk(
            "一二三四五六",
            startCharacterOffset = first.nextCharacterOffset!!,
            maxCharacters = 4,
        )

        assertEquals("一二三四", first.text)
        assertEquals(4, first.nextCharacterOffset)
        assertEquals("五六", second.text)
        assertEquals(null, second.nextCharacterOffset)
    }
}
