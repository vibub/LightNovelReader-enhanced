package indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.explore

import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.LinovelibConstants
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.book.LinovelibWebsiteDataSource
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.net.LinovelibJsoup
import io.nightfish.lightnovelreader.api.web.search.SearchResult
import kotlinx.coroutines.CancellationException
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
        val provider = LinovelibExplorePageProvider(
            jsoup = jsoup,
            websiteDataSource = LinovelibWebsiteDataSource(jsoup)
        )

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
        val publishingHouseDocument = loadPublishingHouseFixture()
        val provider = LinovelibExplorePageProvider(
            homepageLoader = LinovelibExploreHomepageLoader(
                loadDesktopSnapshot = { snapshot(emptyMap()) },
                loadMobileRecommendations = { emptyList() },
                onError = {}
            ),
            loadPublishingHouseDocument = { publishingHouseDocument }
        )
        val publishingHouse = LinovelibExplorePublishingHouse(
            id = "dengekibunko",
            title = "电击文库",
            pageUrl = "${LinovelibConstants.BASE_URL}/wenku/dengekibunko/1.html",
            books = listOf(book("4"))
        )

        provider.registerPublishingHousePages(listOf(publishingHouse))

        val results = provider.exploreExpandedPageDataSourceMap
            .getValue(publishingHouse.expandedPageDataSourceId)
            .getResultFlow()
            .toList()
        assertEquals(
            listOf("7001", "7002", "7003"),
            results.filterIsInstance<SearchResult.MultipleBook>().map { it.bookId }
        )
        assertTrue(results.last() is SearchResult.End)
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
            loadMobileRecommendations = { error("mobile fallback should not be called") },
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
            loadMobileRecommendations = { emptyList() },
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
            loadMobileRecommendations = { error("mobile fallback should not replace old cache") },
            currentTimeMillis = { now },
            onError = {}
        )

        assertEquals("3", loader.getSnapshot()[LinovelibExploreSection.Popular].single().id)
        now += LinovelibExploreHomepageLoader.DEFAULT_CACHE_DURATION_MILLIS + 1
        assertEquals("3", loader.getSnapshot()[LinovelibExploreSection.Popular].single().id)
        assertEquals(2, desktopLoads)
    }

    @Test
    fun firstDesktopFailureCachesMobileRecommendationsOnlyForHomepageRecommendation() = runBlocking {
        var desktopLoads = 0
        var mobileLoads = 0
        val loader = LinovelibExploreHomepageLoader(
            loadDesktopSnapshot = {
                desktopLoads++
                error("desktop failed")
            },
            loadMobileRecommendations = {
                mobileLoads++
                listOf(book(""), book("4"), book("4", "duplicate"), book("5"))
            },
            onError = {}
        )

        val snapshot = loader.getSnapshot()
        assertEquals(
            listOf("4", "5"),
            snapshot[LinovelibExploreSection.HomeRecommended].map { it.id }
        )
        assertTrue(snapshot[LinovelibExploreSection.NewBooks].isEmpty())
        assertTrue(snapshot[LinovelibExploreSection.StrongRecommended].isEmpty())
        assertTrue(snapshot.publishingHouses.isEmpty())
        assertEquals(1, desktopLoads)
        assertEquals(1, mobileLoads)
    }

    @Test
    fun authorResolverCachesSuccessfulAndFailedLookups() = runBlocking {
        val loadCounts = mutableMapOf<String, Int>()
        val resolver = LinovelibExploreAuthorResolver { bookId ->
            loadCounts[bookId] = loadCounts.getOrDefault(bookId, 0) + 1
            if (bookId == "2") error("author failed")
            "author-$bookId"
        }

        assertEquals("author-1", resolver.resolve("1"))
        assertEquals("author-1", resolver.resolve("1"))
        assertEquals("", resolver.resolve("2"))
        assertEquals("", resolver.resolve("2"))
        assertEquals(mapOf("1" to 1, "2" to 1), loadCounts)
    }

    @Test
    fun cancellationIsNotSwallowed() {
        val loader = LinovelibExploreHomepageLoader(
            loadDesktopSnapshot = { throw CancellationException("cancelled") },
            loadMobileRecommendations = { emptyList() },
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

    private fun loadPublishingHouseFixture() = Jsoup.parse(
        checkNotNull(javaClass.getResource("/linovelib/explore/publishing-house.html")).readText(),
        LinovelibConstants.BASE_URL
    )
}
