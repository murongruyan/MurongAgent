package com.murong.agent.core.voice

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import java.io.Closeable

@Serializable
data class VoiceSettings(
    val inputEnabled: Boolean = true,
    val languageTag: String? = null,
    val recognitionProvider: VoiceRecognitionProvider = VoiceRecognitionProvider.AUTOMATIC,
    val autoReadFinalAnswers: Boolean = false,
    val speechRate: Float = 1f,
    val pitch: Float = 1f
)

/** Selects the voice-input implementation without binding chat UI to an Android engine. */
@Serializable
enum class VoiceRecognitionProvider {
    /** Prefer a registered Android recognizer and fall back to an installed local model. */
    AUTOMATIC,
    /** Use only the ROM or user-installed Android RecognitionService. */
    SYSTEM,
    /** Use only Murong's user-installed, on-device speech model. */
    OFFLINE,
}

/** Resolves the persisted preference to a concrete engine without importing Android classes. */
fun selectAvailableVoiceRecognitionProvider(
    preferred: VoiceRecognitionProvider,
    systemAvailable: Boolean,
    offlineAvailable: Boolean,
): VoiceRecognitionProvider? = when (preferred) {
    VoiceRecognitionProvider.SYSTEM -> VoiceRecognitionProvider.SYSTEM.takeIf { systemAvailable }
    VoiceRecognitionProvider.OFFLINE -> VoiceRecognitionProvider.OFFLINE.takeIf { offlineAvailable }
    VoiceRecognitionProvider.AUTOMATIC -> when {
        systemAvailable -> VoiceRecognitionProvider.SYSTEM
        offlineAvailable -> VoiceRecognitionProvider.OFFLINE
        else -> null
    }
}

@Serializable
enum class VoiceRecognitionState { IDLE, PREPARING, LISTENING, FINALIZING, ERROR }

@Serializable
enum class VoicePlaybackState { IDLE, PREPARING, SPEAKING, PAUSED, ERROR }

data class VoiceRecognitionRequest(val languageTag: String? = null)

data class VoicePlaybackRequest(
    val messageId: Long,
    val text: String,
    val languageTag: String? = null,
    val rate: Float = 1f,
    val pitch: Float = 1f
)

sealed interface VoiceRecognitionEvent {
    data class FinalText(val value: String) : VoiceRecognitionEvent
    data class Failure(val userMessage: String) : VoiceRecognitionEvent
}

sealed interface VoicePlaybackEvent {
    /** All queued TTS segments for this message finished naturally. */
    data class Completed(val messageId: Long) : VoicePlaybackEvent
    data class Failure(val messageId: Long, val userMessage: String) : VoicePlaybackEvent
}

interface VoiceRecognitionService : Closeable {
    val state: StateFlow<VoiceRecognitionState>
    val partialText: StateFlow<String>
    val volume: StateFlow<Float>
    val events: Flow<VoiceRecognitionEvent>

    suspend fun start(request: VoiceRecognitionRequest)
    suspend fun stop(): String
    suspend fun cancel()
}

interface VoicePlaybackService : Closeable {
    val state: StateFlow<VoicePlaybackState>
    /**
     * The message that owns the current playback controls, or null when no utterance is queued.
     * Keeping this in the playback service prevents UI state from being left on a completed
     * utterance after TextToSpeech reports its final callback.
     */
    val activeMessageId: StateFlow<Long?>
    val events: Flow<VoicePlaybackEvent>

    suspend fun speak(request: VoicePlaybackRequest)
    suspend fun pause()
    suspend fun resume()
    suspend fun stop()
}

/** A bounded, safe-to-speak portion of a final assistant response. */
data class VoicePlaybackTextChunk(
    val text: String,
    /** Offset in the sanitized source that can be used for the next explicit “continue” action. */
    val nextCharacterOffset: Int? = null,
)

fun isValidVoiceRecognitionTransition(
    from: VoiceRecognitionState,
    to: VoiceRecognitionState
): Boolean = when (from) {
    VoiceRecognitionState.IDLE -> to in setOf(VoiceRecognitionState.IDLE, VoiceRecognitionState.PREPARING)
    VoiceRecognitionState.PREPARING -> to in setOf(
        VoiceRecognitionState.LISTENING,
        // The user can release immediately after pressing the microphone, before Android has
        // delivered onReadyForSpeech. That is a normal stop, not an invalid transition.
        VoiceRecognitionState.FINALIZING,
        VoiceRecognitionState.IDLE,
        VoiceRecognitionState.ERROR
    )
    VoiceRecognitionState.LISTENING -> to in setOf(
        VoiceRecognitionState.LISTENING,
        VoiceRecognitionState.FINALIZING,
        VoiceRecognitionState.IDLE,
        VoiceRecognitionState.ERROR
    )
    VoiceRecognitionState.FINALIZING -> to in setOf(
        VoiceRecognitionState.IDLE,
        VoiceRecognitionState.ERROR
    )
    VoiceRecognitionState.ERROR -> to == VoiceRecognitionState.IDLE
}

/**
 * Streaming transducer models may expose Chinese characters as space-separated tokens while
 * still using meaningful spaces for English words. Join only CJK-to-CJK and CJK-punctuation
 * boundaries so `你 好 HELLO WORLD` becomes `你好 HELLO WORLD` without damaging English.
 */
fun normalizeVoiceRecognitionText(value: String): String {
    val compactWhitespace = value.trim().replace(Regex("\\s+"), " ")
    if (' ' !in compactWhitespace) return compactWhitespace
    return buildString(compactWhitespace.length) {
        compactWhitespace.forEachIndexed { index, character ->
            if (character != ' ') {
                append(character)
                return@forEachIndexed
            }
            val previous = lastOrNull()
            val next = compactWhitespace.getOrNull(index + 1)
            val joinsCjkTokens = previous != null && next != null && (
                (previous.isCjkRecognitionCharacter() &&
                    (next.isCjkRecognitionCharacter() || next.isCjkRecognitionPunctuation())) ||
                    (previous.isCjkRecognitionPunctuation() && next.isCjkRecognitionCharacter())
                )
            if (!joinsCjkTokens && lastOrNull() != ' ') append(' ')
        }
    }
}

private fun Char.isCjkRecognitionCharacter(): Boolean = when (Character.UnicodeScript.of(code)) {
    Character.UnicodeScript.HAN,
    Character.UnicodeScript.HIRAGANA,
    Character.UnicodeScript.KATAKANA,
    Character.UnicodeScript.HANGUL -> true
    else -> false
}

private fun Char.isCjkRecognitionPunctuation(): Boolean = this in "，。！？；：、（）【】《》“”‘’"

/** Removes text that should remain visible in the chat but must never be spoken aloud. */
fun sanitizeVoicePlaybackText(value: String, maxCharacters: Int = 1_600): String {
    if (value.isBlank()) return ""
    val withoutCodeBlocks = value.replace(Regex("```[\\s\\S]*?```"), "")
    val withoutLinks = withoutCodeBlocks.replace(Regex("\\[([^]]+)]\\([^)]*\\)"), "${'$'}1")
    // Keep the punctuation following a URL. It is meaningful both for the displayed sentence
    // and for TTS segmentation (for example `https://example.com。`).
    val withoutUrls = withoutLinks.replace(
        Regex("(?i)https?://\\S+?(?=\\s|[。！？]|[.!?,，、；;:：）)](?:\\s|$)|$)"),
        ""
    )
    return withoutUrls
        .lineSequence()
        .map { line -> line.trim() }
        .filter { line ->
            line.isNotBlank() &&
                !line.startsWith("$") &&
                !line.startsWith("bash:", ignoreCase = true) &&
                !line.startsWith("tool", ignoreCase = true) &&
                !line.startsWith("工具", ignoreCase = true) &&
                !line.startsWith("思考", ignoreCase = true)
        }
        .joinToString("\n")
        .replace(Regex("\\s+([。！？.!?])"), "${'$'}1")
        .replace(Regex("^#{1,6}\\s*", RegexOption.MULTILINE), "")
        .replace(Regex("^[*-]\\s+", RegexOption.MULTILINE), "")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(maxCharacters.coerceAtLeast(0))
}

/**
 * Produces one bounded speech batch without treating code, URLs, tool output or reasoning as
 * readable content.  The caller must wait for [VoicePlaybackEvent.Completed] before offering
 * the returned continuation, so stopping playback never skips unseen text.
 */
fun voicePlaybackTextChunk(
    value: String,
    startCharacterOffset: Int = 0,
    maxCharacters: Int = 1_600,
): VoicePlaybackTextChunk {
    val readable = sanitizeVoicePlaybackText(value, maxCharacters = Int.MAX_VALUE)
    val start = startCharacterOffset.coerceIn(0, readable.length)
    if (start >= readable.length) return VoicePlaybackTextChunk(text = "")
    val end = (start.toLong() + maxCharacters.coerceAtLeast(1).toLong())
        .coerceAtMost(readable.length.toLong())
        .toInt()
    return VoicePlaybackTextChunk(
        text = readable.substring(start, end),
        nextCharacterOffset = end.takeIf { it < readable.length },
    )
}

/**
 * Keeps Android TTS calls below vendor-specific utterance limits while retaining sentence-like
 * boundaries.  The caller owns cancellation: starting another target or leaving chat must stop
 * the queued utterances before any subsequent segment can play.
 */
fun splitVoicePlaybackText(value: String, maxCharactersPerSegment: Int = 360): List<String> {
    val normalized = value.trim()
    val max = maxCharactersPerSegment.coerceAtLeast(1)
    if (normalized.isBlank()) return emptyList()
    val sentences = normalized
        .split(Regex("(?<=[。！？.!?])\\s*|\\n+"))
        .flatMap { sentence ->
            sentence.trim().chunked(max).filter { it.isNotBlank() }
        }
    val result = mutableListOf<String>()
    var current = StringBuilder()
    sentences.forEach { sentence ->
        if (current.isNotEmpty() && current.length + sentence.length + 1 > max) {
            result += current.toString()
            current = StringBuilder()
        }
        if (current.isNotEmpty()) current.append(' ')
        current.append(sentence)
    }
    if (current.isNotEmpty()) result += current.toString()
    return result
}
