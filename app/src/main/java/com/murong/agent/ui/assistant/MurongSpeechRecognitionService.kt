package com.murong.agent.ui.assistant

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionService
import android.speech.RecognitionSupport
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.murong.agent.core.voice.VoiceRecognitionRequest
import com.murong.agent.voice.OfflineSherpaSpeechRecognitionService
import com.murong.agent.voice.OfflineVoiceModelManager
import com.murong.agent.voice.SpeechEndpointDetector
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * The recognition component required by Android's VoiceInteractionService contract. It uses the
 * same downloadable offline Sherpa model as chat voice input and never forwards audio to a server.
 */
class MurongSpeechRecognitionService : RecognitionService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var modelManager: OfflineVoiceModelManager
    private lateinit var recognizer: OfflineSherpaSpeechRecognitionService
    private var recognitionJob: Job? = null
    private var partialJob: Job? = null
    private var volumeJob: Job? = null
    private var stopRequest: CompletableDeferred<Unit>? = null

    override fun onCreate() {
        super.onCreate()
        modelManager = OfflineVoiceModelManager(this)
        recognizer = OfflineSherpaSpeechRecognitionService(this, modelManager)
    }

    override fun onStartListening(recognizerIntent: Intent, callback: Callback) {
        if (recognitionJob?.isActive == true) {
            safely { callback.error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY) }
            return
        }
        if (!recognizer.isAvailable()) {
            safely { callback.error(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE) }
            return
        }
        val language = recognizerIntent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE)
            ?.takeIf(String::isNotBlank)
            ?: Locale.getDefault().toLanguageTag()
        stopRequest = CompletableDeferred()
        recognitionJob = scope.launch {
            runCatching {
                recognizer.start(VoiceRecognitionRequest(language))
                safely { callback.readyForSpeech(Bundle.EMPTY) }
                safely { callback.beginningOfSpeech() }
                partialJob = launch {
                    recognizer.partialText.collectLatest { text ->
                        if (text.isNotBlank()) safely {
                            callback.partialResults(recognitionBundle(text))
                        }
                    }
                }
                volumeJob = launch {
                    recognizer.volume.collectLatest { value ->
                        safely { callback.rmsChanged((value * 10f).coerceIn(0f, 10f)) }
                    }
                }
                val endpointDetector = SpeechEndpointDetector()
                endpointDetector.reset(System.currentTimeMillis())
                while (true) {
                    val now = System.currentTimeMillis()
                    if (
                        stopRequest?.isCompleted == true ||
                        endpointDetector.shouldStop(
                            nowMillis = now,
                            volume = recognizer.volume.value,
                            partialText = recognizer.partialText.value,
                        )
                    ) break
                    delay(100L)
                }
                safely { callback.endOfSpeech() }
                val result = recognizer.stop().trim()
                if (result.isBlank()) {
                    safely { callback.error(SpeechRecognizer.ERROR_NO_MATCH) }
                } else {
                    safely { callback.results(recognitionBundle(result)) }
                }
            }.onFailure { error ->
                if (error !is kotlinx.coroutines.CancellationException) {
                    safely { callback.error(SpeechRecognizer.ERROR_AUDIO) }
                }
            }
            clearSessionJobs()
        }
    }

    override fun onStopListening(callback: Callback) {
        stopRequest?.complete(Unit)
    }

    override fun onCancel(callback: Callback) {
        recognitionJob?.cancel()
        clearSessionJobs()
        scope.launch { recognizer.cancel() }
    }

    override fun onCheckRecognitionSupport(
        recognizerIntent: Intent,
        supportCallback: SupportCallback,
    ) {
        if (!recognizer.isAvailable()) {
            supportCallback.onError(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)
            return
        }
        supportCallback.onSupportResult(
            RecognitionSupport.Builder()
                .setInstalledOnDeviceLanguages(listOf("zh-CN", "en-US"))
                .setSupportedOnDeviceLanguages(listOf("zh-CN", "en-US"))
                .build(),
        )
    }

    override fun onDestroy() {
        recognitionJob?.cancel()
        clearSessionJobs()
        recognizer.close()
        modelManager.close()
        scope.cancel()
        super.onDestroy()
    }

    private fun clearSessionJobs() {
        partialJob?.cancel()
        volumeJob?.cancel()
        partialJob = null
        volumeJob = null
        recognitionJob = null
        stopRequest = null
    }

    private fun recognitionBundle(text: String): Bundle = Bundle().apply {
        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
        putFloatArray(SpeechRecognizer.CONFIDENCE_SCORES, floatArrayOf(-1f))
    }

    private inline fun safely(block: () -> Unit) {
        runCatching(block)
    }
}
