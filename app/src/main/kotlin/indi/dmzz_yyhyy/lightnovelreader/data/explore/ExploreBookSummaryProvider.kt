package indi.dmzz_yyhyy.lightnovelreader.data.explore

import io.nightfish.lightnovelreader.api.book.BookInformation

internal interface ExploreBookSummaryProvider {
    /** 只用于列表展示，不请求详情，也不写入完整详情缓存。 */
    fun getBookSummary(bookId: String): BookInformation?
}
