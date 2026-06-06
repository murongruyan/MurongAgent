package dev.reasonix.mobile.ui.project

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex

private const val PROJECT_NESTED_BACK_PREVIEW_THRESHOLD = 0.02f

@Composable
internal fun ProjectNestedPredictiveBackHost(
    detailVisible: Boolean,
    backProgress: Float,
    modifier: Modifier = Modifier,
    detailContent: @Composable () -> Unit,
    listContent: @Composable () -> Unit
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val widthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val predictivePreviewProgress = backProgress.coerceIn(0f, 1f)
        val showPredictivePreview = detailVisible &&
            predictivePreviewProgress > PROJECT_NESTED_BACK_PREVIEW_THRESHOLD

        when {
            !detailVisible -> {
                listContent()
            }

            showPredictivePreview -> {
                val currentTranslationX = widthPx * predictivePreviewProgress
                val previousTranslationX = currentTranslationX - widthPx
                key("listPreview") {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .zIndex(0f)
                            .graphicsLayer {
                                translationX = previousTranslationX
                                alpha = 1f
                            }
                    ) {
                        listContent()
                    }
                }
                key("detailPreview") {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .zIndex(1f)
                            .graphicsLayer {
                                translationX = currentTranslationX
                                alpha = 1f
                            }
                    ) {
                        detailContent()
                    }
                }
            }

            else -> {
                detailContent()
            }
        }
    }
}
