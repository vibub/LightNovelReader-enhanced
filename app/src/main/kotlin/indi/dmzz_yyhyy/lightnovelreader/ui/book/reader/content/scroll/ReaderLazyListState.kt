package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.scroll

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListPrefetchScope
import androidx.compose.foundation.lazy.LazyListPrefetchStrategy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.layout.NestedPrefetchScope

@OptIn(ExperimentalFoundationApi::class)
internal fun createReaderLazyListState(): LazyListState =
    LazyListState(prefetchStrategy = ReaderChapterPrefetchStrategy)

/** 列表的一项是整章，不能在滑动时预组合、预测量相邻整章；章节数据缓存仍由 ViewModel 管理。 */
@OptIn(ExperimentalFoundationApi::class)
private object ReaderChapterPrefetchStrategy : LazyListPrefetchStrategy {
    override fun LazyListPrefetchScope.onScroll(delta: Float, layoutInfo: LazyListLayoutInfo) = Unit

    override fun LazyListPrefetchScope.onVisibleItemsUpdated(layoutInfo: LazyListLayoutInfo) = Unit

    override fun NestedPrefetchScope.onNestedPrefetch(firstVisibleItemIndex: Int) = Unit
}
