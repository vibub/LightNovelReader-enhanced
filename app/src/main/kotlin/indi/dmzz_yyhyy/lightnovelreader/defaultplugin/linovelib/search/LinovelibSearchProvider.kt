package indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.search

import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.LinovelibConstants
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.book.LinovelibWebsiteDataSource
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.net.LinovelibBlockedException
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.net.LinovelibJsoup
import io.nightfish.lightnovelreader.api.util.local
import io.nightfish.lightnovelreader.api.web.search.AbstractSearchProvider
import io.nightfish.lightnovelreader.api.web.search.SearchResult
import io.nightfish.lightnovelreader.api.web.search.SearchType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.net.URLEncoder

class LinovelibSearchProvider(
    private val jsoup: LinovelibJsoup,
    private val websiteDataSource: LinovelibWebsiteDataSource
) : AbstractSearchProvider() {
    init {
        registerSearchType(SEARCH_BY_NAME, "按书名搜索".local(), "请输入书本名称".local())
    }

    override fun search(searchType: SearchType, keyword: String): Flow<SearchResult> = flow {
        val trimmedKeyword = keyword.trim()
        if (trimmedKeyword.isBlank()) {
            emit(SearchResult.Empty())
            return@flow
        }
        try {
            val encodedKeyword = URLEncoder.encode(trimmedKeyword, Charsets.UTF_8.name())
            val url = "${LinovelibConstants.BASE_URL}/S6/?searchkey=$encodedKeyword"
            val document = jsoup.getDocument(url, retryTime = 0)
            val books = websiteDataSource.parseSearchBooks(document)
            if (books.isEmpty()) {
                emit(SearchResult.Empty())
            } else {
                books.forEach { emit(SearchResult.MultipleBook(it)) }
                emit(SearchResult.End())
            }
        } catch (blocked: LinovelibBlockedException) {
            emit(SearchResult.Error("Linovelib 搜索被 Cloudflare 拦截，详情、目录和章节阅读仍可通过书籍 ID 访问。"))
        } catch (throwable: Throwable) {
            throwable.printStackTrace()
            emit(SearchResult.Error(throwable))
        }
    }.flowOn(Dispatchers.IO)

    companion object {
        private const val SEARCH_BY_NAME = "articlename"
    }
}
