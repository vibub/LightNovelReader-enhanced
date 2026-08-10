package indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.explore

import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.book.LinovelibWebsiteDataSource
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.net.LinovelibJsoup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LinovelibExplorePageProviderTest {
    @Test
    fun providerRegistersHomepageSectionsInWebpageOrder() {
        val jsoup = LinovelibJsoup()
        val provider = LinovelibExplorePageProvider(
            jsoup = jsoup,
            websiteDataSource = LinovelibWebsiteDataSource(jsoup)
        )

        assertEquals(
            LinovelibExploreSection.entries.map { it.pageId },
            provider.explorePageIdList
        )
        assertEquals(
            LinovelibExploreSection.entries.map { it.title },
            provider.explorePageIdList.map { provider.exploreTapPageDataSourceMap.getValue(it).title }
        )
    }

    @Test
    fun loaderSharesDesktopSnapshotAcrossSections() = runBlocking {
        var desktopLoads = 0
        val loader = LinovelibExploreHomepageLoader(
            loadDesktopSnapshot = {
                desktopLoads++
                snapshot(
                    LinovelibExploreSection.HomeRecommended to listOf(book("1")),
                    LinovelibExploreSection.NewBooks to listOf(book("2"))
                )
            },
            loadMobileRecommendations = { error("mobile fallback should not be called") },
            onError = {}
        )

        assertEquals(listOf("1"), loader.getBooks(LinovelibExploreSection.HomeRecommended).map { it.id })
        assertEquals(listOf("2"), loader.getBooks(LinovelibExploreSection.NewBooks).map { it.id })
        assertEquals(1, desktopLoads)
    }

    @Test
    fun invalidationForcesDesktopReloadWithinCacheDuration() = runBlocking {
        var desktopLoads = 0
        val loader = LinovelibExploreHomepageLoader(
            loadDesktopSnapshot = {
                desktopLoads++
                snapshot(
                    LinovelibExploreSection.HomeRecommended to listOf(book(desktopLoads.toString()))
                )
            },
            loadMobileRecommendations = { emptyList() },
            onError = {}
        )

        assertEquals("1", loader.getBooks(LinovelibExploreSection.HomeRecommended).single().id)
        loader.invalidate()
        assertEquals("2", loader.getBooks(LinovelibExploreSection.HomeRecommended).single().id)
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
                    snapshot(LinovelibExploreSection.Popular to listOf(book("3")))
                } else {
                    error("desktop failed")
                }
            },
            loadMobileRecommendations = { error("mobile fallback should not replace old cache") },
            currentTimeMillis = { now },
            onError = {}
        )

        assertEquals("3", loader.getBooks(LinovelibExploreSection.Popular).single().id)
        now += LinovelibExploreHomepageLoader.DEFAULT_CACHE_DURATION_MILLIS + 1
        assertEquals("3", loader.getBooks(LinovelibExploreSection.Popular).single().id)
        assertEquals(2, desktopLoads)
    }

    @Test
    fun firstDesktopFailureCachesMobileRecommendationsOnlyForHomeTab() = runBlocking {
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

        assertEquals(
            listOf("4", "5"),
            loader.getBooks(LinovelibExploreSection.HomeRecommended).map { it.id }
        )
        assertTrue(loader.getBooks(LinovelibExploreSection.NewBooks).isEmpty())
        assertEquals(1, desktopLoads)
        assertEquals(1, mobileLoads)
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
                loader.getBooks(LinovelibExploreSection.HomeRecommended)
            }
        }
    }

    private fun snapshot(
        vararg sections: Pair<LinovelibExploreSection, List<LinovelibWebsiteDataSource.LinovelibExploreBook>>
    ): LinovelibExploreSnapshot = LinovelibExploreSnapshot(mapOf(*sections))

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
}
