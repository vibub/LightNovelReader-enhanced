package indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.search

import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.LinovelibConstants
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.book.LinovelibWebsiteDataSource
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.net.LinovelibBlockedException
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.net.LinovelibJsoup
import io.nightfish.lightnovelreader.api.util.Cache
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
    private val websiteDataSource: LinovelibWebsiteDataSource,
    private val cache: Cache
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
        val blockedErrors = mutableListOf<LinovelibBlockedException>()
        val otherErrors = mutableListOf<Throwable>()
        var hasSuccessfulEmptyResult = false
        val encodedKeyword = URLEncoder.encode(trimmedKeyword, Charsets.UTF_8.name())
        SEARCH_URLS.forEach { searchUrl ->
            try {
                val document = jsoup.getDocument(
                    url = searchUrl.url(encodedKeyword),
                    referer = searchUrl.referer,
                    retryTime = 0,
                    userAgentMode = LinovelibJsoup.UserAgentMode.Mobile
                )
                val books = websiteDataSource.parseSearchBooks(document)
                if (books.isNotEmpty()) {
                    books.forEach { bookInformation ->
                        cache.cache(bookInformation.id.hashCode(), bookInformation)
                        emit(SearchResult.MultipleBook(bookInformation.id))
                    }
                    emit(SearchResult.End())
                    return@flow
                }
                hasSuccessfulEmptyResult = true
            } catch (blocked: LinovelibBlockedException) {
                blockedErrors += blocked
            } catch (throwable: Throwable) {
                throwable.printStackTrace()
                otherErrors += throwable
            }
        }
        when {
            hasSuccessfulEmptyResult -> emit(SearchResult.Empty())
            otherErrors.isNotEmpty() -> emit(SearchResult.Error(otherErrors.first()))
            blockedErrors.isNotEmpty() -> emit(SearchResult.Error(LinovelibConstants.SEARCH_BLOCKED_MESSAGE))
            else -> emit(SearchResult.Empty())
        }
    }.flowOn(Dispatchers.IO)

    companion object {
        private const val SEARCH_BY_NAME = "articlename"

        private val SEARCH_URLS = listOf(
            SearchUrl(
                referer = LinovelibConstants.MOBILE_BASE_URL,
                url = { keyword -> "${LinovelibConstants.MOBILE_BASE_URL}/search.html?searchkey=$keyword" }
            ),
            SearchUrl(
                referer = "${LinovelibConstants.MOBILE_BASE_URL}/search.html",
                url = { keyword -> "${LinovelibConstants.MOBILE_BASE_URL}/search.php?searchkey=$keyword" }
            )
        )
    }
}

private data class SearchUrl(
    val referer: String,
    val url: (String) -> String
)
