package indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.search

import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.LinovelibConstants
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.book.LinovelibWebsiteDataSource
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.net.LinovelibBlockedException
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.net.LinovelibJsoup
import io.nightfish.lightnovelreader.api.book.BookInformation
import io.nightfish.lightnovelreader.api.util.Cache
import io.nightfish.lightnovelreader.api.util.local
import io.nightfish.lightnovelreader.api.web.search.AbstractSearchProvider
import io.nightfish.lightnovelreader.api.web.search.SearchResult
import io.nightfish.lightnovelreader.api.web.search.SearchType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.jsoup.Jsoup
import java.net.URLEncoder

data class LinovelibWebSearchRequest(val id: Long, val keyword: String)

class LinovelibSearchProvider(
    private val jsoup: LinovelibJsoup,
    private val websiteDataSource: LinovelibWebsiteDataSource,
    private val cache: Cache
) : AbstractSearchProvider() {
    init {
        registerSearchType(SEARCH_BY_NAME, "按书名搜索".local(), "请输入书本名称".local())
    }

    /** null 表示尚未识别到搜索结果，不能把验证页或搜索表单当成空结果。 */
    fun parseWebSearchPage(request: LinovelibWebSearchRequest, url: String, html: String): List<BookInformation>? {
        if (!LinovelibConstants.isSearchPageUrl(url)) return null
        val document = Jsoup.parse(html, url)
        val title = document.title()
        // POST 搜索的 URL 不带关键词，通过站点结果标题绑定当前请求。
        if (!title.startsWith("${request.keyword} 搜索结果") &&
            !title.startsWith("${request.keyword} 搜尋結果")
        ) return null
        val text = document.body().text()
        if (Regex("(?:相关共有|相關共有)\\s*0\\s*[条條][记記][录錄]").containsMatchIn(text)) {
            return emptyList()
        }
        return websiteDataSource.parseSearchBooks(document).takeIf { it.isNotEmpty() }
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
                if (throwable is CancellationException) throw throwable
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
