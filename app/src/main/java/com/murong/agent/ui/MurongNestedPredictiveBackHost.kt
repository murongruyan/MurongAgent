package com.murong.agent.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

private const val REASONIX_NESTED_BACK_PREVIEW_THRESHOLD = 0.001f
private const val REASONIX_NESTED_SETTLE_DURATION = 320
private const val REASONIX_NESTED_SETTLE_OFFSET_FRACTION = 1f
private data class MurongNestedLayerSpec(
    val showDetail: Boolean,
    val translationX: Float,
    val alpha: Float,
    val zIndex: Float
)

@Composable
fun MurongNestedPredictiveBackHost(
    detailVisible: Boolean,
    backProgress: Float,
    modifier: Modifier = Modifier,
    detailShape: Shape = RoundedCornerShape(0.dp),
    wrapDetailInSecondarySurface: Boolean = true,
    wrapListInSecondarySurface: Boolean = false,
    wrapLayersInSharedSecondarySurface: Boolean = false,
    detailContent: @Composable () -> Unit,
    listContent: @Composable () -> Unit
) {
    val settleProgress = remember { Animatable(1f) }
    var settledDetailVisible by remember { mutableStateOf(detailVisible) }
    var transitionFromDetailVisible by remember { mutableStateOf<Boolean?>(null) }
    var transitionToDetailVisible by remember { mutableStateOf<Boolean?>(null) }
    var transitionIsPush by remember { mutableStateOf(false) }
    var suppressNextPopSettle by remember { mutableStateOf(false) }
    var retainedDetailContent by remember { mutableStateOf<(@Composable () -> Unit)?>(null) }
    val predictivePreviewProgress = backProgress.coerceIn(0f, 1f)
    val predictivePreviewBucket = if (predictivePreviewProgress > 0f) {
        (predictivePreviewProgress * 10).toInt().coerceIn(0, 10)
    } else {
        -1
    }
    val activeDetailContent = if (detailVisible) detailContent else retainedDetailContent
    val stableDetailVisible = when {
        transitionFromDetailVisible != null &&
            transitionToDetailVisible != null &&
            settleProgress.value >= 1f -> {
            transitionToDetailVisible == true && activeDetailContent != null
        }

        else -> settledDetailVisible && activeDetailContent != null
    }

    SideEffect {
        if (detailVisible) {
            retainedDetailContent = detailContent
        }
    }

    LaunchedEffect(detailVisible) {
        if (settledDetailVisible == detailVisible) {
            if (suppressNextPopSettle) {
                suppressNextPopSettle = false
            }
            settleProgress.snapTo(1f)
            return@LaunchedEffect
        }
        if (suppressNextPopSettle && !detailVisible) {
            settledDetailVisible = false
            transitionFromDetailVisible = null
            transitionToDetailVisible = null
            retainedDetailContent = null
            suppressNextPopSettle = false
            settleProgress.snapTo(1f)
            return@LaunchedEffect
        }
        transitionFromDetailVisible = settledDetailVisible
        transitionToDetailVisible = detailVisible
        transitionIsPush = detailVisible
        settleProgress.snapTo(0f)
        settleProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = REASONIX_NESTED_SETTLE_DURATION,
                easing = FastOutSlowInEasing
            )
        )
        settledDetailVisible = detailVisible
        transitionFromDetailVisible = null
        transitionToDetailVisible = null
        if (!detailVisible) {
            retainedDetailContent = null
        }
    }

    LaunchedEffect(
        detailVisible,
        settledDetailVisible,
        predictivePreviewProgress,
        transitionFromDetailVisible,
        transitionToDetailVisible
    ) {
        if (
            detailVisible &&
            settledDetailVisible &&
            predictivePreviewProgress > REASONIX_NESTED_BACK_PREVIEW_THRESHOLD
        ) {
            suppressNextPopSettle = true
        } else if (
            detailVisible &&
            predictivePreviewProgress <= REASONIX_NESTED_BACK_PREVIEW_THRESHOLD &&
            transitionFromDetailVisible == null &&
            transitionToDetailVisible == null &&
            suppressNextPopSettle
        ) {
            suppressNextPopSettle = false
        }
    }

    @Composable
    fun RenderLayerStack() {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val widthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val layerSpecs = when {
            settledDetailVisible &&
                activeDetailContent != null &&
                predictivePreviewProgress > REASONIX_NESTED_BACK_PREVIEW_THRESHOLD -> {
                val currentTranslationX = widthPx * predictivePreviewProgress
                val previousTranslationX = currentTranslationX - widthPx
                listOf(
                    MurongNestedLayerSpec(
                        showDetail = false,
                        translationX = previousTranslationX,
                        alpha = 1f,
                        zIndex = 0f
                    ),
                    MurongNestedLayerSpec(
                        showDetail = true,
                        translationX = currentTranslationX,
                        alpha = 1f,
                        zIndex = 1f
                    )
                )
            }

            transitionFromDetailVisible != null &&
                transitionToDetailVisible != null &&
                settleProgress.value < 1f -> {
                val progress = settleProgress.value
                if (transitionIsPush) {
                    listOf(
                        MurongNestedLayerSpec(
                            showDetail = transitionFromDetailVisible == true,
                            translationX = -widthPx * REASONIX_NESTED_SETTLE_OFFSET_FRACTION * progress,
                            alpha = 1f - progress,
                            zIndex = 0f
                        ),
                        MurongNestedLayerSpec(
                            showDetail = transitionToDetailVisible == true,
                            translationX = widthPx * (1f - progress),
                            alpha = 1f,
                            zIndex = 1f
                        )
                    )
                } else {
                    listOf(
                        MurongNestedLayerSpec(
                            showDetail = transitionToDetailVisible == true,
                            translationX = -widthPx * REASONIX_NESTED_SETTLE_OFFSET_FRACTION * (1f - progress),
                            alpha = progress,
                            zIndex = 0f
                        ),
                        MurongNestedLayerSpec(
                            showDetail = transitionFromDetailVisible == true,
                            translationX = widthPx * progress,
                            alpha = 1f - progress,
                            zIndex = 1f
                        )
                    )
                }
            }

            else -> {
                listOf(
                    MurongNestedLayerSpec(
                        showDetail = stableDetailVisible,
                        translationX = 0f,
                        alpha = 1f,
                        zIndex = 0f
                    )
                )
            }
        }

        layerSpecs.forEach { layer ->
            key(layer.showDetail, layer.zIndex) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(layer.zIndex)
                        .graphicsLayer {
                            translationX = layer.translationX
                            alpha = layer.alpha
                        }
                ) {
                    if (layer.showDetail) {
                        activeDetailContent?.let { currentDetailContent ->
                            RenderMurongNestedLayerContent(
                                wrapInSecondarySurface = wrapDetailInSecondarySurface,
                                shape = detailShape,
                                content = currentDetailContent
                            )
                        }
                    } else {
                        RenderMurongNestedLayerContent(
                            wrapInSecondarySurface = wrapListInSecondarySurface,
                            shape = detailShape,
                            content = listContent
                        )
                    }
                }
            }
        }
    }
    }

    if (wrapLayersInSharedSecondarySurface) {
        MurongSecondaryPageSurface(
            modifier = modifier.fillMaxSize(),
            shape = detailShape,
            forceOpaque = false
        ) {
            RenderLayerStack()
        }
    } else {
        Box(modifier = modifier.fillMaxSize()) {
            RenderLayerStack()
        }
    }
}

@Composable
private fun RenderMurongNestedLayerContent(
    wrapInSecondarySurface: Boolean,
    shape: Shape,
    content: @Composable () -> Unit
) {
    if (wrapInSecondarySurface) {
        MurongSecondaryPageSurface(
            modifier = Modifier.fillMaxSize(),
            shape = shape,
            forceOpaque = false
        ) {
            content()
        }
    } else {
        content()
    }
}
