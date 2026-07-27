package com.murong.agent.voice

/**
 * Detects end-of-speech without treating an unchanged partial transcript as fresh speech.
 *
 * Streaming recognizers keep their latest partial text until stop() is called. The old code
 * therefore reset the silence timer forever as soon as the first word appeared.
 */
internal class SpeechEndpointDetector(
    private val silenceMillis: Long = 1_050L,
    private val maximumListeningMillis: Long = 15_000L,
    private val volumeThreshold: Float = 0.025f,
) {
    private var startedAt = 0L
    private var lastSpeechSignalAt = 0L
    private var lastPartial = ""
    private var heardSpeech = false

    fun reset(nowMillis: Long) {
        startedAt = nowMillis
        lastSpeechSignalAt = 0L
        lastPartial = ""
        heardSpeech = false
    }

    fun shouldStop(
        nowMillis: Long,
        volume: Float,
        partialText: String,
    ): Boolean {
        if (startedAt == 0L) reset(nowMillis)
        val partial = partialText.trim()
        val partialChanged = partial.isNotBlank() && partial != lastPartial
        if (partialChanged) lastPartial = partial
        // Once the recognizer has produced text, only a changed transcript is evidence that the
        // user is still speaking. Several Android audio stacks report a permanently elevated RMS
        // floor, which previously kept extending the endpoint forever after the user went quiet.
        val volumeStartedSpeech = lastPartial.isBlank() && volume >= volumeThreshold
        if (volumeStartedSpeech || partialChanged) {
            heardSpeech = true
            lastSpeechSignalAt = nowMillis
        }
        if (
            heardSpeech &&
            lastSpeechSignalAt > 0L &&
            nowMillis - lastSpeechSignalAt >= silenceMillis
        ) {
            return true
        }
        return nowMillis - startedAt >= maximumListeningMillis
    }
}
