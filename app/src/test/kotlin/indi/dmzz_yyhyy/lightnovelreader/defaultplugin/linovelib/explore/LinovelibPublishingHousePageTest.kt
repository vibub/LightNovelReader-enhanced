package indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.explore

import indi.dmzz_yyhyy.lightnovelreader.data.explore.MultiChoiceExploreFilter
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.LinovelibConstants
import io.nightfish.lightnovelreader.api.web.explore.filter.SingleChoiceFilter
import io.nightfish.lightnovelreader.api.web.search.SearchResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

class LinovelibPublishingHousePageTest {
    private val defaultUrl = LinovelibFilterUrl.Default.toAbsoluteUrl()
    private val metadataUrl = LinovelibFilterUrl.Metadata.toAbsoluteUrl()
    private val filterDocument by lazy {
        Jsoup.parse(
            checkNotNull(javaClass.getResource("/linovelib/explore/filter-page.html")).readText(),
            LinovelibConstants.FILTER_BASE_URL
        )
    }

    @Test
    fun parserReadsRealFilterAttributesCommentedPublishingHousesBooksAndPagination() {
        val page = LinovelibFilterPageParser.parse(filterDocument, metadataUrl)

        assertEquals(
            listOf(
                "文库地区",
                "出版社",
                "作品主题",
                "排序方式",
                "是否动画",
                "作品字数",
                "写作状态"
            ),
            page.filters.map { it.title }
        )
        assertEquals(
            listOf("所有文库", "电击文库", "富士见文库", "MF文库J"),
            page.filters.single { it.title == "出版社" }.options.map { it.title }
        )
        assertEquals(4, page.filters.single { it.title == "作品主题" }.maxSelections)
        assertEquals(listOf("7001", "7002", "7003"), page.bookIds)
        assertTrue(page.hasNextPage)
    }

    @Test
    fun filterUrlSupportsMultipleThemesAndPublishingHouseRegionDependency() {
        val multiThemeUrl = LinovelibFilterUrl.parse(
            "${LinovelibConstants.FILTER_BASE_URL}/wenku/lastupdate_64-48-63_0_0_0_0_0_0_4_0.html"
        )!!
        assertEquals("64-48-63", multiThemeUrl.parameters[0])
        assertEquals(4, multiThemeUrl.page)

        val publishingHouseOption = LinovelibFilterUrl.Metadata.withValue(
            key = LinovelibFilterKey.Parameter(
                LinovelibFilterUrl.PUBLISHING_HOUSE_PARAMETER_INDEX
            ),
            value = "1",
            relatedRegion = "1"
        )
        assertEquals(
            "${LinovelibConstants.FILTER_BASE_URL}/wenku/lastupdate_0_0_0_1_1_0_0_1_0.html",
            LinovelibFilterUrl.Default.withPage(3)
                .withOption(
                    LinovelibFilterKey.Parameter(
                        LinovelibFilterUrl.PUBLISHING_HOUSE_PARAMETER_INDEX
                    ),
                    publishingHouseOption
                )
                .toAbsoluteUrl()
        )
    }

    @Test
    fun dataSourceSelectsEntryPublishingHouseAndItsJapaneseRegion() = runBlocking {
        val requestedUrls = mutableListOf<String>()
        val cache = LinovelibFilterPageCache(loadDocument = { url ->
            requestedUrls += url
            if (url == metadataUrl) filterDocument else resultDocument(url, listOf("7101"))
        })
        val dataSource = LinovelibFilterPageDataSource("电击文库", cache)

        val results = dataSource.getResultFlow()
            .takeWhile { it !is SearchResult.End }
            .toList()

        assertEquals(
            listOf("7101"),
            results.filterIsInstance<SearchResult.MultipleBook>().map { it.bookId }
        )
        val singleChoiceFilters = dataSource.filters.filterIsInstance<SingleChoiceFilter>()
        assertEquals(
            "电击文库",
            singleChoiceFilters.single { it.getAllChoices().contains("电击文库") }.value
        )
        assertEquals(
            "日本轻小说",
            singleChoiceFilters.single { it.getAllChoices().contains("日本轻小说") }.value
        )
        assertTrue(
            requestedUrls.contains(
                "${LinovelibConstants.FILTER_BASE_URL}/wenku/lastupdate_0_0_0_1_1_0_0_1_0.html"
            )
        )
    }

    @Test
    fun changingPublishingHouseCanReturnToAllPublishingHouses() = runBlocking {
        val requestedUrls = mutableListOf<String>()
        val cache = LinovelibFilterPageCache(loadDocument = { url ->
            requestedUrls += url
            if (url == metadataUrl) filterDocument else resultDocument(url, listOf("7101"))
        })
        val dataSource = LinovelibFilterPageDataSource("电击文库", cache)
        dataSource.getResultFlow().takeWhile { it !is SearchResult.End }.toList()
        val singleChoiceFilters = dataSource.filters.filterIsInstance<SingleChoiceFilter>()
        val publishingHouseFilter = singleChoiceFilters
            .single { it.getAllChoices().contains("电击文库") }
        val regionFilter = singleChoiceFilters
            .single { it.getAllChoices().contains("日本轻小说") }

        publishingHouseFilter.value = "所有文库"
        dataSource.getResultFlow().takeWhile { it !is SearchResult.End }.toList()

        assertEquals(defaultUrl, requestedUrls.last())
        assertEquals("不限", regionFilter.value)

        publishingHouseFilter.value = "富士见文库"

        assertEquals("日本轻小说", regionFilter.value)
    }

    @Test
    fun changingRegionClearsPublishingHouseSelection() = runBlocking {
        val cache = LinovelibFilterPageCache(loadDocument = { url ->
            if (url == metadataUrl) filterDocument else resultDocument(url, listOf("7101"))
        })
        val dataSource = LinovelibFilterPageDataSource("电击文库", cache)
        dataSource.getResultFlow().takeWhile { it !is SearchResult.End }.toList()
        val singleChoiceFilters = dataSource.filters.filterIsInstance<SingleChoiceFilter>()
        val publishingHouseFilter = singleChoiceFilters
            .single { it.getAllChoices().contains("电击文库") }
        val regionFilter = singleChoiceFilters
            .single { it.getAllChoices().contains("日本轻小说") }

        regionFilter.value = "华文轻小说"

        assertEquals("所有文库", publishingHouseFilter.value)
    }

    @Test
    fun themeFilterSelectsAtMostFourThemesInWebsiteOrder() = runBlocking {
        val requestedUrls = mutableListOf<String>()
        val cache = LinovelibFilterPageCache(loadDocument = { url ->
            requestedUrls += url
            if (url == metadataUrl) filterDocument else resultDocument(url, listOf("7101"))
        })
        val dataSource = LinovelibFilterPageDataSource("电击文库", cache)
        dataSource.getResultFlow().takeWhile { it !is SearchResult.End }.toList()
        val themeFilter = dataSource.filters.filterIsInstance<MultiChoiceExploreFilter>().single()
        val selected = themeFilter.normalizeSelection(
            linkedSetOf("转生", "校园", "百合", "后宫", "恋爱")
        )

        assertEquals(linkedSetOf("恋爱", "后宫", "校园", "百合"), selected)
        themeFilter.value = selected
        dataSource.getResultFlow().takeWhile { it !is SearchResult.End }.toList()

        assertEquals(
            "${LinovelibConstants.FILTER_BASE_URL}/wenku/lastupdate_64-48-63-27_0_0_1_1_0_0_1_0.html",
            requestedUrls.last()
        )
    }

    @Test
    fun scrollingLoadsNextPageAndDeduplicatesBooks() = runBlocking {
        val cache = LinovelibFilterPageCache(loadDocument = { url ->
            when {
                url == metadataUrl -> filterDocument
                LinovelibFilterUrl.parse(url)?.page == 1 ->
                    resultDocument(url, listOf("7201", "7202"), hasNextPage = true)
                else -> resultDocument(url, listOf("7202", "7203"))
            }
        })
        val dataSource = LinovelibFilterPageDataSource("电击文库", cache)
        val results = mutableListOf<SearchResult>()

        withTimeout(5.seconds) {
            dataSource.getResultFlow().collect { result ->
                results += result
                if (result is SearchResult.End) dataSource.requestLoadMore()
            }
        }

        assertEquals(
            listOf("7201", "7202", "7203"),
            results.filterIsInstance<SearchResult.MultipleBook>().map { it.bookId }
        )
        assertEquals(2, results.count { it is SearchResult.End })
    }

    @Test
    fun paginationFailureKeepsLoadedBooksAndReportsError() = runBlocking {
        val cache = LinovelibFilterPageCache(loadDocument = { url ->
            when {
                url == metadataUrl -> filterDocument
                LinovelibFilterUrl.parse(url)?.page == 1 ->
                    resultDocument(url, listOf("7251", "7252"), hasNextPage = true)
                else -> error("pagination failed")
            }
        })
        val dataSource = LinovelibFilterPageDataSource("电击文库", cache)
        val results = mutableListOf<SearchResult>()

        withTimeout(5.seconds) {
            dataSource.getResultFlow().collect { result ->
                results += result
                if (result is SearchResult.End) dataSource.requestLoadMore()
            }
        }

        assertEquals(
            listOf("7251", "7252"),
            results.filterIsInstance<SearchResult.MultipleBook>().map { it.bookId }
        )
        assertTrue(results.last() is SearchResult.Error)
    }

    @Test
    fun metadataAndResultsAreCachedForTenMinutes() = runBlocking {
        var now = 0L
        var loads = 0
        val cache = LinovelibFilterPageCache(
            loadDocument = { url ->
                loads++
                if (url == metadataUrl) filterDocument else resultDocument(url, listOf("7301"))
            },
            currentTimeMillis = { now }
        )
        val selectedUrl = LinovelibFilterUrl.Metadata.withValue(
            key = LinovelibFilterKey.Parameter(
                LinovelibFilterUrl.PUBLISHING_HOUSE_PARAMETER_INDEX
            ),
            value = "1",
            relatedRegion = "1"
        )

        cache.getMetadata()
        cache.getMetadata()
        cache.getPage(selectedUrl)
        cache.getPage(selectedUrl)
        assertEquals(2, loads)

        now += LinovelibFilterPageCache.DEFAULT_CACHE_DURATION_MILLIS + 1
        cache.getMetadata()
        cache.getPage(selectedUrl)
        assertEquals(4, loads)
    }

    @Test
    fun refreshingCurrentCombinationInvalidatesAllItsCachedPagesOnly() = runBlocking {
        val loads = mutableMapOf<String, Int>()
        val cache = LinovelibFilterPageCache(loadDocument = { url ->
            loads[url] = loads.getOrDefault(url, 0) + 1
            resultDocument(url, listOf("7401"))
        })
        val current = LinovelibFilterUrl.Default.withValue(
            LinovelibFilterKey.Parameter(0),
            "64"
        )
        val other = LinovelibFilterUrl.Default.withValue(
            LinovelibFilterKey.Parameter(0),
            "48"
        )
        val currentPageOne = current.withPage(1).toAbsoluteUrl()
        val currentPageTwo = current.withPage(2).toAbsoluteUrl()
        val otherPageOne = other.withPage(1).toAbsoluteUrl()

        cache.getPage(current.withPage(1))
        cache.getPage(current.withPage(2))
        cache.getPage(other)
        cache.invalidateResultCache(current)
        cache.getPage(current.withPage(1))
        cache.getPage(current.withPage(2))
        cache.getPage(other)

        assertEquals(2, loads[currentPageOne])
        assertEquals(2, loads[currentPageTwo])
        assertEquals(1, loads[otherPageOne])
    }

    @Test
    fun requestFailureIsReportedWithoutHomepageFallback() = runBlocking {
        val dataSource = LinovelibFilterPageDataSource(
            initialPublishingHouse = "电击文库",
            pageCache = LinovelibFilterPageCache(
                loadDocument = { error("request failed") }
            )
        )

        val results = dataSource.getResultFlow().toList()

        assertEquals(1, results.size)
        assertTrue(results.single() is SearchResult.Error)
        assertTrue(dataSource.filters.isEmpty())
    }

    @Test
    fun parserRejectsCloudflareVerificationPage() {
        val verificationDocument = Jsoup.parse(
            """
            <!doctype html>
            <html lang="en">
            <head><title>Just a moment...</title></head>
            <body>Performing security verification</body>
            </html>
            """.trimIndent(),
            LinovelibConstants.FILTER_BASE_URL
        )

        assertThrows(IllegalStateException::class.java) {
            LinovelibFilterPageParser.parse(verificationDocument, metadataUrl)
        }
    }

    @Test
    fun cancellationIsNotSwallowed() {
        val dataSource = LinovelibFilterPageDataSource(
            initialPublishingHouse = "电击文库",
            pageCache = LinovelibFilterPageCache(
                loadDocument = { throw CancellationException("cancelled") }
            )
        )

        assertThrows(CancellationException::class.java) {
            runBlocking {
                dataSource.getResultFlow().toList()
            }
        }
    }

    private fun resultDocument(
        pageUrl: String,
        bookIds: List<String>,
        hasNextPage: Boolean = false
    ): Document {
        val currentUrl = checkNotNull(LinovelibFilterUrl.parse(pageUrl))
        val books = bookIds.joinToString("\n") { bookId ->
            """
            <li class="book-li">
                <a class="book-layout" href="${LinovelibConstants.FILTER_BASE_URL}/novel/$bookId.html">
                    <h4 class="book-title">小说 $bookId</h4>
                </a>
            </li>
            """.trimIndent()
        }
        val pagination = if (hasNextPage) {
            """
            <div class="pagelink">
                <a class="next" href="${currentUrl.withPage(currentUrl.page + 1).toAbsoluteUrl()}">下一页</a>
            </div>
            """.trimIndent()
        } else {
            ""
        }
        return Jsoup.parse(
            """
            <!doctype html>
            <html lang="zh-CN">
            <body>
                <ol class="book-ol">$books</ol>
                $pagination
            </body>
            </html>
            """.trimIndent(),
            LinovelibConstants.FILTER_BASE_URL
        )
    }
}
