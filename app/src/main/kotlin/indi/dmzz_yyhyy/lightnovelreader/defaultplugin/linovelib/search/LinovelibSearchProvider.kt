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

data class LinovelibWebSearchRequest(val id: Long, val keyword: String, val page: Int = 1)

data class LinovelibWebSearchPage(
    val books: List<BookInformation>,
    val totalCount: Int?,
    val page: Int,
    val hasNextPage: Boolean
)

class LinovelibSearchProvider(
    private val jsoup: LinovelibJsoup,
    private val websiteDataSource: LinovelibWebsiteDataSource,
    private val cache: Cache
) : AbstractSearchProvider() {
    init {
        registerSearchType(SEARCH_BY_NAME, "按书名搜索".local(), "请输入书本名称".local())
    }

    /** null 表示尚未识别到当前页结果，不能把验证页或其他页当成空结果。 */
    fun parseWebSearchPage(request: LinovelibWebSearchRequest, url: String, html: String): LinovelibWebSearchPage? {
        val address = parseSearchAddress(url) ?: return null
        if (address.first != request.keyword || address.second != request.page) return null
        val document = Jsoup.parse(html, url)
        val text = document.body().text()
        val summary = Regex(
            """共\s*(?:搜索|搜尋)\s*到\s*(\d+)\s*[部本]\s*[与與]\s*[“"'「『](.*?)[”"'」』]\s*(?:相关|相關)\s*(?:结果|結果)"""
        ).find(text)
        if (summary != null && summary.groupValues[2] != request.keyword) return null
        val resultCount = summary?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("(?:相关共有|相關共有|共有?|总共|總共)\\s*(\\d+)\\s*(?:[条條][记記][录錄]|本[书書]|[个個][结結]果)")
                .find(text)?.groupValues?.get(1)?.toIntOrNull()
        val hasEmptyMessage = document.select(".no-result, .no-results, .search-empty").any {
            it.text().contains("没有找到") || it.text().contains("沒有找到") || it.text().contains("未找到")
        }
        if (resultCount == 0 || hasEmptyMessage) {
            return LinovelibWebSearchPage(emptyList(), resultCount, request.page, false)
        }
        val containers = document.select(".search-result-list, .search-result, .search-results")
            .ifEmpty { document.select(".book-img-text, .book-ol-normal") }
        if (resultCount == null && containers.isEmpty()) return null
        val results = if (containers.isEmpty()) document else Jsoup.parseBodyFragment(
            containers.joinToString("") { it.outerHtml() }, url
        )
        val books = websiteDataSource.parseSearchBooks(results).takeIf { it.isNotEmpty() } ?: return null
        val pager = document.select(".pagelink, .pagination, .pages, .page")
        val pageStatsPattern = Regex("(\\d+)\\s*/\\s*(\\d+)")
        val pageStats = pager.select("*").firstNotNullOfOrNull {
            pageStatsPattern.matchEntire(it.ownText().trim())
        }
        val currentPage = pager.select(".pagenow, .current, .active, strong")
            .firstNotNullOfOrNull { it.text().trim().toIntOrNull() }
            ?: pageStats?.groupValues?.get(1)?.toIntOrNull()
            ?: request.page
        val linkedPages = document.select("a[href]").mapNotNull { link ->
            parseSearchAddress(link.absUrl("href"))?.takeIf { it.first == request.keyword }?.second
        }
        val totalPages = pageStats?.groupValues?.get(2)?.toIntOrNull()
            ?: maxOf(
                linkedPages.maxOrNull() ?: 1,
                resultCount?.let { ((it.toLong() + PAGE_SIZE - 1) / PAGE_SIZE).toInt() } ?: 1
            )
        return LinovelibWebSearchPage(
            books = books,
            totalCount = resultCount,
            page = currentPage,
            hasNextPage = currentPage == request.page && request.page < totalPages
        )
    }

    override fun search(searchType: SearchType, keyword: String): Flow<SearchResult> = flow {
        val trimmedKeyword = keyword.trim()
        if (trimmedKeyword.isBlank()) {
            emit(SearchResult.Empty())
            return@flow
        }
        val request = LinovelibWebSearchRequest(0, trimmedKeyword)
        val url = LinovelibConstants.desktopSearchUrl(trimmedKeyword)
        try {
            val document = jsoup.getDocument(
                url = url,
                referer = LinovelibConstants.BASE_URL,
                retryTime = 0,
                userAgentMode = LinovelibJsoup.UserAgentMode.Desktop,
                requestIntent = LinovelibJsoup.RequestIntent.UserInitiated
            )
            val result = parseWebSearchPage(request, url, document.outerHtml())
                ?: error("Linovelib 未返回可识别的搜索结果")
            if (result.books.isEmpty()) {
                emit(SearchResult.Empty())
            } else {
                result.books.forEach { book ->
                    cache.cache(book.id.hashCode(), book)
                    emit(SearchResult.MultipleBook(book.id))
                }
                emit(SearchResult.End())
            }
        } catch (_: LinovelibBlockedException) {
            emit(SearchResult.Error(LinovelibConstants.SEARCH_BLOCKED_MESSAGE))
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            emit(SearchResult.Error(throwable))
        }
    }.flowOn(Dispatchers.IO)

    companion object {
        private const val SEARCH_BY_NAME = "articlename"
        private const val PAGE_SIZE = 20

        private fun parseSearchAddress(url: String): Pair<String, Int>? = runCatching {
            if (!LinovelibConstants.isDesktopPageUrl(url)) return null
            val match = Regex("/S6/(.+)_(\\d+)\\.html").matchEntire(URI(url).rawPath) ?: return null
            val keyword = URLDecoder.decode(match.groupValues[1].replace("+", "%2B"), Charsets.UTF_8.name())
            val page = match.groupValues[2].toIntOrNull()?.takeIf { it > 0 } ?: return null
            keyword to page
        }.getOrNull()
    }
}
