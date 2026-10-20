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
import java.net.URI
import java.net.URLDecoder
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
        if (!LinovelibConstants.isDesktopPageUrl(url)) return null
        val document = Jsoup.parse(html, url)
        val text = document.body().text()
        // 电脑端 POST 结果可能没有关键词参数或回显表单，使用可见结果摘要绑定请求。
        val desktopSummary = Regex(
            """共\s*(?:搜索|搜尋)\s*到\s*(\d+)\s*[部本]\s*[与與]\s*[“"'「『](.*?)[”"'」』]\s*(?:相关|相關)\s*(?:结果|結果)"""
        ).find(text)
        if (!LinovelibConstants.isDesktopSearchPageUrl(url) && desktopSummary == null) return null
        val summaryKeyword = desktopSummary?.groupValues?.get(2)
        if (summaryKeyword != null && summaryKeyword != request.keyword) return null
        val title = document.title()
        val queryKeyword = runCatching {
            URI(url).rawQuery?.split('&')?.firstOrNull { it.substringBefore('=') == "searchkey" }
                ?.substringAfter('=', "")?.let { URLDecoder.decode(it, Charsets.UTF_8.name()) }
        }.getOrNull()
        val formKeyword = document.selectFirst("meta[name=lnr-search-keyword]")?.attr("content")
        if (queryKeyword != null && queryKeyword != request.keyword) return null
        if (summaryKeyword == null && formKeyword != null && formKeyword != request.keyword) return null
        // 结果摘要优先于通用页头搜索框，仍拒绝不匹配的查询参数和其他关键词的结果。
        val hasResultTitle = title.startsWith("${request.keyword} 搜索结果") ||
            title.startsWith("${request.keyword} 搜尋結果")
        if (summaryKeyword != request.keyword && queryKeyword != request.keyword &&
            formKeyword != request.keyword && !hasResultTitle
        ) return null
        val searchContainers = document.select(".search-result-list, .search-result, .search-results")
        val resultCount = desktopSummary?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("(?:相关共有|相關共有|共有?|总共|總共)\\s*(\\d+)\\s*(?:[条條][记記][录錄]|本[书書]|[个個][结結]果)")
                .find(text)?.groupValues?.get(1)?.toIntOrNull()
        val hasEmptyMessage = document.select(".no-result, .no-results, .search-empty").any {
            it.text().contains("没有找到") || it.text().contains("沒有找到") || it.text().contains("未找到")
        }
        if (resultCount == 0 || hasEmptyMessage) return emptyList()
        if (!hasResultTitle && resultCount == null && searchContainers.isEmpty()) return null
        val resultContainers = searchContainers.ifEmpty { document.select(".book-ol-normal") }
        val results = if (resultContainers.isEmpty()) document else Jsoup.parseBodyFragment(
            resultContainers.joinToString("") { it.outerHtml() }, url
        )
        return websiteDataSource.parseSearchBooks(results).takeIf { it.isNotEmpty() }
    }

    override fun search(searchType: SearchType, keyword: String): Flow<SearchResult> = flow {
        val trimmedKeyword = keyword.trim()
        if (trimmedKeyword.isBlank()) {
            emit(SearchResult.Empty())
            return@flow
        }
        val otherErrors = mutableListOf<Throwable>()
        var hasSuccessfulEmptyResult = false
        val encodedKeyword = URLEncoder.encode(trimmedKeyword, Charsets.UTF_8.name())
        SEARCH_URLS.forEach { searchUrl ->
            try {
                val document = jsoup.getDocument(
                    url = searchUrl.url(encodedKeyword),
                    referer = searchUrl.referer,
                    retryTime = 0,
                    userAgentMode = LinovelibJsoup.UserAgentMode.Desktop
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
            } catch (_: LinovelibBlockedException) {
                emit(SearchResult.Error(LinovelibConstants.SEARCH_BLOCKED_MESSAGE))
                return@flow
            } catch (throwable: Throwable) {
                if (throwable is CancellationException) throw throwable
                throwable.printStackTrace()
                otherErrors += throwable
            }
        }
        when {
            hasSuccessfulEmptyResult -> emit(SearchResult.Empty())
            otherErrors.isNotEmpty() -> emit(SearchResult.Error(otherErrors.first()))
            else -> emit(SearchResult.Empty())
        }
    }.flowOn(Dispatchers.IO)

    companion object {
        private const val SEARCH_BY_NAME = "articlename"

        private val SEARCH_URLS = listOf(
            SearchUrl(
                referer = LinovelibConstants.BASE_URL,
                url = { keyword -> "${LinovelibConstants.BASE_URL}/search.html?searchkey=$keyword" }
            ),
            SearchUrl(
                referer = "${LinovelibConstants.BASE_URL}/search.html",
                url = { keyword -> "${LinovelibConstants.BASE_URL}/search.php?searchkey=$keyword" }
            )
        )
    }
}

private data class SearchUrl(
    val referer: String,
    val url: (String) -> String
)
