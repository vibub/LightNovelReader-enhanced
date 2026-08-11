package indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.explore

import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.LinovelibConstants
import io.nightfish.lightnovelreader.api.web.search.SearchResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LinovelibPublishingHousePageTest {
    private val document by lazy {
        Jsoup.parse(
            checkNotNull(javaClass.getResource("/linovelib/explore/publishing-house.html")).readText(),
            LinovelibConstants.BASE_URL
        )
    }

    @Test
    fun parserOnlyReturnsDistinctBooksFromPublishingHouseList() {
        assertEquals(
            listOf("7001", "7002", "7003"),
            LinovelibPublishingHousePageParser.parseBookIds(document)
        )
    }

    @Test
    fun dataSourceLoadsOnlyOnePageWithoutFilters() = runBlocking {
        var loads = 0
        val dataSource = LinovelibPublishingHousePageDataSource(
            title = "电击文库",
            pageUrl = "${LinovelibConstants.BASE_URL}/wenku/dengekibunko/1.html",
            loadDocument = {
                loads++
                document
            }
        )

        dataSource.loadMore()
        val results = dataSource.getResultFlow().toList()

        assertTrue(dataSource.filters.isEmpty())
        assertEquals(1, loads)
        assertEquals(
            listOf("7001", "7002", "7003"),
            results.filterIsInstance<SearchResult.MultipleBook>().map { it.bookId }
        )
        assertTrue(results.last() is SearchResult.End)
    }

    @Test
    fun requestFailureIsEmittedAsSearchError() = runBlocking {
        val dataSource = LinovelibPublishingHousePageDataSource(
            title = "电击文库",
            pageUrl = "${LinovelibConstants.BASE_URL}/wenku/dengekibunko/1.html",
            loadDocument = { error("request failed") }
        )

        assertTrue(dataSource.getResultFlow().toList().single() is SearchResult.Error)
    }

    @Test
    fun cancellationIsNotSwallowed() {
        val dataSource = LinovelibPublishingHousePageDataSource(
            title = "电击文库",
            pageUrl = "${LinovelibConstants.BASE_URL}/wenku/dengekibunko/1.html",
            loadDocument = { throw CancellationException("cancelled") }
        )

        assertThrows(CancellationException::class.java) {
            runBlocking {
                dataSource.getResultFlow().toList()
            }
        }
    }
}
