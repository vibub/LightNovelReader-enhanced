package indi.dmzz_yyhyy.lightnovelreader.data.explore

import io.nightfish.lightnovelreader.api.web.explore.filter.Filter
import kotlinx.coroutines.flow.StateFlow

internal interface ManagedExploreExpandedPageDataSource {
    val filtersFlow: StateFlow<List<Filter<*>>>

    fun reset()

    fun requestLoadMore(): Boolean

    fun invalidateCurrentResultCache()
}
