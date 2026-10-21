package indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.explore

import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.LinovelibConstants
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.book.LinovelibWebsiteDataSource
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.net.LinovelibJsoup
import io.nightfish.lightnovelreader.api.web.search.SearchResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LinovelibExplorePageProviderTest {
    @Test
    fun providerRegistersThreeTopPagesInSpecifiedOrder() {
        val jsoup = LinovelibJsoup()
        val provider = LinovelibExplorePageProvider(jsoup)

        assertEquals(
            LinovelibExplorePage.entries.map { it.pageId },
            provider.explorePageIdList
        )
        assertEquals(
            LinovelibExplorePage.entries.map { it.title },
            provider.explorePageIdList.map { provider.exploreTapPageDataSourceMap.getValue(it).title }
        )
    }

    @Test
    fun providerRegistersPublishingHouseExpandedPages() = runBlocking {
        val filterDocument = loadFilterFixture()
        val provider = LinovelibExplorePageProvider(
            homepageLoader = LinovelibExploreHomepageLoader(
                loadDesktopSnapshot = { snapshot(emptyMap()) },
                onError = {}
            ),
            loadFilterDocument = { filterDocument }
        )
        val publishingHouse = LinovelibExplorePublishingHouse(
            id = "dengekibunko",
            title = "电击文库",
            books = listOf(book("4"))
        )

        provider.registerPublishingHousePages(listOf(publishingHouse))

        val dataSource = provider.exploreExpandedPageDataSourceMap
            .getValue(publishingHouse.expandedPageDataSourceId)
        val results = dataSource
            .getResultFlow()
            .takeWhile { it !is SearchResult.End }
            .toList()
        assertEquals("筛选", dataSource.title)
        assertEquals(
            listOf("7001", "7002", "7003"),
            results.filterIsInstance<SearchResult.MultipleBook>().map { it.bookId }
        )
    }

    @Test
    fun loaderSharesDesktopSnapshotAcrossTopPages() = runBlocking {
        var desktopLoads = 0
        val loader = LinovelibExploreHomepageLoader(
            loadDesktopSnapshot = {
                desktopLoads++
                snapshot(
                    sections = mapOf(
                        LinovelibExploreSection.HomeRecommended to listOf(book("1")),
                        LinovelibExploreSection.NewBooks to listOf(book("2"))
                    )
                )
            },
            onError = {}
        )

        assertEquals(listOf("1"), loader.getSnapshot()[LinovelibExploreSection.HomeRecommended].map { it.id })
        assertEquals(listOf("2"), loader.getSnapshot()[LinovelibExploreSection.NewBooks].map { it.id })
        assertEquals(1, desktopLoads)
    }

    @Test
    fun invalidationForcesDesktopReloadWithinCacheDuration() = runBlocking {
        var desktopLoads = 0
        val loader = LinovelibExploreHomepageLoader(
            loadDesktopSnapshot = {
                desktopLoads++
                snapshot(
                    sections = mapOf(
                        LinovelibExploreSection.HomeRecommended to listOf(book(desktopLoads.toString()))
                    )
                )
            },
            onError = {}
        )

        assertEquals("1", loader.getSnapshot()[LinovelibExploreSection.HomeRecommended].single().id)
        loader.invalidate()
        assertEquals("2", loader.getSnapshot()[LinovelibExploreSection.HomeRecommended].single().id)
        assertEquals(2, desktopLoads)
    }

    @Test
    fun expiredDesktopFailureKeepsPreviousSnapshot() = runBlocking {
        var now = 0L
        var desktopLoads = 0
        val loader = LinovelibExploreHomepageLoader(
            loadDesktopSnapshot = {
                desktopLoads++
                if (desktopLoads == 1) {
                    snapshot(
                        sections = mapOf(
                            LinovelibExploreSection.Popular to listOf(book("3"))
                        )
                    )
                } else {
                    error("desktop failed")
                }
            },
            currentTimeMillis = { now },
            onError = {}
        )

        assertEquals("3", loader.getSnapshot()[LinovelibExploreSection.Popular].single().id)
        now += LinovelibExploreHomepageLoader.DEFAULT_CACHE_DURATION_MILLIS + 1
        assertEquals("3", loader.getSnapshot()[LinovelibExploreSection.Popular].single().id)
        assertEquals(2, desktopLoads)
    }

    @Test
    fun firstDesktopFailureCachesEmptySnapshotWithoutMobileFallback() = runBlocking {
        var desktopLoads = 0
        val loader = LinovelibExploreHomepageLoader(
            loadDesktopSnapshot = {
                desktopLoads++
                error("desktop failed")
            },
            onError = {}
        )

        val snapshot = loader.getSnapshot()
        assertTrue(!snapshot.hasBooks)
        assertTrue(snapshot.publishingHouses.isEmpty())
        assertEquals(snapshot, loader.getSnapshot())
        assertEquals(1, desktopLoads)
    }

    @Test
    fun cancellationIsNotSwallowed() {
        val loader = LinovelibExploreHomepageLoader(
            loadDesktopSnapshot = { throw CancellationException("cancelled") },
            onError = {}
        )

        assertThrows(CancellationException::class.java) {
            runBlocking {
                loader.getSnapshot()
            }
        }
    }

    private fun snapshot(
        sections: Map<LinovelibExploreSection, List<LinovelibWebsiteDataSource.LinovelibExploreBook>>,
        publishingHouses: List<LinovelibExplorePublishingHouse> = emptyList()
    ): LinovelibExploreSnapshot = LinovelibExploreSnapshot(sections, publishingHouses)

    private fun book(
        id: String,
        title: String = id
    ): LinovelibWebsiteDataSource.LinovelibExploreBook =
        LinovelibWebsiteDataSource.LinovelibExploreBook(
            id = id,
            title = title,
            author = "author-$id",
            coverUrl = ""
        )

    private fun loadFilterFixture() = Jsoup.parse(
        checkNotNull(javaClass.getResource("/linovelib/explore/filter-page.html")).readText(),
        LinovelibConstants.BASE_URL
    )
}
