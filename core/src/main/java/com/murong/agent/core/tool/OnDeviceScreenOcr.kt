package com.murong.agent.core.tool

import android.graphics.BitmapFactory
import android.util.Base64
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

internal object OnDeviceScreenOcr {
    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    suspend fun recognize(screenshot: GuiScreenshot): List<PhoneAgentTextElement> {
        if (screenshot.base64Data.isBlank() || screenshot.width <= 0 || screenshot.height <= 0) {
            return emptyList()
        }
        return withTimeoutOrNull(6_000) {
            val bytes = Base64.decode(screenshot.base64Data, Base64.DEFAULT)
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@withTimeoutOrNull emptyList()
            try {
                val image = InputImage.fromBitmap(bitmap, 0)
                suspendCancellableCoroutine { continuation ->
                    recognizer.process(image)
                        .addOnSuccessListener { recognized ->
                            val elements = recognized.textBlocks
                                .flatMap { it.lines }
                                .mapNotNull { line ->
                                    val text = line.text.trim()
                                    val bounds = line.boundingBox
                                    if (text.isBlank() || bounds == null) null else PhoneAgentTextElement(
                                        text = text,
                                        centerX = ((bounds.centerX().toLong() * 1000L) / screenshot.width)
                                            .toInt().coerceIn(0, 1000),
                                        centerY = ((bounds.centerY().toLong() * 1000L) / screenshot.height)
                                            .toInt().coerceIn(0, 1000),
                                    )
                                }
                                .distinctBy { Triple(it.text, it.centerX, it.centerY) }
                            if (continuation.isActive) continuation.resume(elements)
                        }
                        .addOnFailureListener {
                            if (continuation.isActive) continuation.resume(emptyList())
                        }
                }
            } finally {
                bitmap.recycle()
            }
        }.orEmpty()
    }
}
