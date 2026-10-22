package indi.dmzz_yyhyy.lightnovelreader.ui.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp

/** 只在用户滑到底后继续上拉时加载，每次手势最多请求一页，不响应布局或惯性滚动。 */
@Composable
internal fun rememberPullToLoadMoreConnection(
    listState: LazyListState,
    canLoadMore: () -> Boolean,
    onLoadMore: () -> Unit
): NestedScrollConnection {
    val currentCanLoadMore by rememberUpdatedState(canLoadMore)
    val currentLoadMore by rememberUpdatedState(onLoadMore)
    val pullThreshold = with(LocalDensity.current) { 48.dp.toPx() }
    return remember(listState, pullThreshold) {
        var pullDistance = 0f
        var requestedForGesture = false
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                if (source != NestedScrollSource.UserInput || requestedForGesture) return Offset.Zero
                if (available.y < 0 && !listState.canScrollForward && currentCanLoadMore()) {
                    pullDistance -= available.y
                    if (pullDistance >= pullThreshold) {
                        requestedForGesture = true
                        currentLoadMore()
                    }
                } else {
                    pullDistance = 0f
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                pullDistance = 0f
                requestedForGesture = false
                return Velocity.Zero
            }
        }
    }
}
