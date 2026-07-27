package com.murong.agent.ui.assistant

import android.content.Context

internal data class AssistantInvocationSettings(
    val wakeWordEnabled: Boolean = false,
    val wakePhrase: String = DEFAULT_WAKE_PHRASE,
    val volumeChordTripleEnabled: Boolean = false,
    val speakerVerificationThreshold: Float = DEFAULT_SPEAKER_VERIFICATION_THRESHOLD,
    val fastLocalModelId: String = "",
)

internal object AssistantInvocationPreferences {
    const val PREFERENCES_NAME = "assistant_invocation"
    const val KEY_VOLUME_CHORD_TRIPLE = "volume_chord_triple"
    private const val LEGACY_KEY_DOUBLE_VOLUME_UP = "double_volume_up"
    private const val KEY_WAKE_WORD_ENABLED = "wake_word_enabled"
    private const val KEY_WAKE_PHRASE = "wake_phrase"
    private const val KEY_SPEAKER_VERIFICATION_THRESHOLD = "speaker_verification_threshold"
    private const val KEY_FAST_LOCAL_MODEL_ID = "fast_local_model_id"
    private const val RETIRED_FAST_MODEL_ID = "qwen3.5-0.8b-fast"

    fun read(context: Context): AssistantInvocationSettings {
        val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        return AssistantInvocationSettings(
            wakeWordEnabled = preferences.getBoolean(KEY_WAKE_WORD_ENABLED, false),
            wakePhrase = preferences.getString(KEY_WAKE_PHRASE, DEFAULT_WAKE_PHRASE)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: DEFAULT_WAKE_PHRASE,
            volumeChordTripleEnabled = preferences.getBoolean(
                KEY_VOLUME_CHORD_TRIPLE,
                preferences.getBoolean(LEGACY_KEY_DOUBLE_VOLUME_UP, false),
            ),
            speakerVerificationThreshold = preferences.getFloat(
                KEY_SPEAKER_VERIFICATION_THRESHOLD,
                DEFAULT_SPEAKER_VERIFICATION_THRESHOLD,
            ).coerceIn(0.45f, 0.82f),
            fastLocalModelId = preferences.getString(KEY_FAST_LOCAL_MODEL_ID, "")
                ?.trim()
                .orEmpty()
                .takeUnless { it == RETIRED_FAST_MODEL_ID }
                .orEmpty(),
        )
    }

    fun setWakeWordEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_WAKE_WORD_ENABLED, enabled)
            .apply()
    }

    fun setWakePhrase(context: Context, phrase: String) {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_WAKE_PHRASE, phrase.trim().ifBlank { DEFAULT_WAKE_PHRASE })
            .apply()
    }

    fun setVolumeChordTripleEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_VOLUME_CHORD_TRIPLE, enabled)
            .remove(LEGACY_KEY_DOUBLE_VOLUME_UP)
            .apply()
    }

    fun setSpeakerVerificationThreshold(context: Context, threshold: Float) {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putFloat(
                KEY_SPEAKER_VERIFICATION_THRESHOLD,
                threshold.coerceIn(0.45f, 0.82f),
            )
            .apply()
    }

    fun setFastLocalModelId(context: Context, modelId: String) {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_FAST_LOCAL_MODEL_ID, modelId.trim())
            .apply()
    }
}

internal const val DEFAULT_WAKE_PHRASE = "慕容慕容"
internal const val DEFAULT_SPEAKER_VERIFICATION_THRESHOLD = 0.58f

internal fun normalizeWakePhrase(text: String): String =
    text.lowercase().filter(Char::isLetterOrDigit)

internal fun containsWakePhrase(transcript: String, phrase: String): Boolean {
    val normalizedPhrase = normalizeWakePhrase(phrase)
    return normalizedPhrase.length >= 2 &&
        normalizeWakePhrase(transcript).contains(normalizedPhrase)
}
