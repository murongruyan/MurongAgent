package com.murong.agent.ui.project

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.murong.agent.ui.MurongNestedPredictiveBackHost

@Composable
internal fun ProjectNestedPredictiveBackHost(
    detailVisible: Boolean,
    backProgress: Float,
    modifier: Modifier = Modifier,
    detailContent: @Composable () -> Unit,
    listContent: @Composable () -> Unit
) {
    MurongNestedPredictiveBackHost(
        detailVisible = detailVisible,
        backProgress = backProgress,
        modifier = modifier,
        wrapDetailInSecondarySurface = false,
        wrapListInSecondarySurface = false,
        wrapLayersInSharedSecondarySurface = true,
        detailContent = detailContent,
        listContent = listContent
    )
}
