package dev.reasonix.mobile.ui.project

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.reasonix.mobile.ui.ReasonixNestedPredictiveBackHost

@Composable
internal fun ProjectNestedPredictiveBackHost(
    detailVisible: Boolean,
    backProgress: Float,
    modifier: Modifier = Modifier,
    detailContent: @Composable () -> Unit,
    listContent: @Composable () -> Unit
) {
    ReasonixNestedPredictiveBackHost(
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
