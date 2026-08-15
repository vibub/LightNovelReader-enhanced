package indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.explore

import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.LinovelibConstants
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LinovelibExploreHomepageParserTest {
    private val snapshot by lazy {
        val html = checkNotNull(javaClass.getResource("/linovelib/explore/homepage.html"))
            .readText()
        LinovelibExploreHomepageParser.parse(
            Jsoup.parse(html, LinovelibConstants.BASE_URL)
        )
    }

    @Test
    fun homepageRecommendationOnlyContainsCarouselBooks() {
        val books = snapshot[LinovelibExploreSection.HomeRecommended]

        assertEquals(listOf("1001"), books.map { it.id })
        assertEquals(listOf("轮播推荐一"), books.map { it.title })
        assertEquals("作者甲", books.single().author)
        assertEquals(
            "${LinovelibConstants.BASE_URL}/files/article/image/1/1001/1001s.jpg",
            books.single().coverUrl
        )
    }

    @Test
    fun parsesSixHomepageRowsAndExcludesPublishingHouseBooksFromPopularRow() {
        assertEquals(listOf("2001", "2002"), snapshot[LinovelibExploreSection.NewBooks].map { it.id })
        assertEquals(listOf("3001", "3002"), snapshot[LinovelibExploreSection.Popular].map { it.id })
        assertEquals(
            listOf("4001", "4002"),
            snapshot[LinovelibExploreSection.ClassicCompleted].map { it.id }
        )
        assertEquals(
            listOf("5001", "5002"),
            snapshot[LinovelibExploreSection.CompletedRecommended].map { it.id }
        )
        assertEquals(
            listOf("6001", "6002"),
            snapshot[LinovelibExploreSection.RecentUpdates].map { it.id }
        )
        assertTrue(snapshot[LinovelibExploreSection.Popular].none { it.id == "3101" })
    }

    @Test
    fun parsesHomepageRankingsInDisplayedOrder() {
        assertEquals(
            listOf("1001", "1002"),
            snapshot[LinovelibExploreSection.StrongRecommended].map { it.id }
        )
        assertEquals(
            listOf("1101", "1102"),
            snapshot[LinovelibExploreSection.NewBookRanking].map { it.id }
        )
        assertEquals(
            listOf("1201", "1202"),
            snapshot[LinovelibExploreSection.HotRanking].map { it.id }
        )
        assertEquals(
            listOf("榜单作者甲", "榜单作者乙"),
            snapshot[LinovelibExploreSection.StrongRecommended].map { it.author }
        )
        assertEquals(
            listOf("榜单作者丙", "榜单作者丁"),
            snapshot[LinovelibExploreSection.NewBookRanking].map { it.author }
        )
        assertEquals(
            listOf("榜单作者戊", "榜单作者己"),
            snapshot[LinovelibExploreSection.HotRanking].map { it.author }
        )
    }

    @Test
    fun dynamicallyParsesPublishingHousesAndTheirBooks() {
        assertEquals(listOf("电击文库", "富士见文库"), snapshot.publishingHouses.map { it.title })
        assertEquals(listOf("dengekibunko", "fujimibunko"), snapshot.publishingHouses.map { it.id })
        assertEquals(
            listOf("3002", "3101"),
            snapshot.publishingHouses.first().books.map { it.id }
        )
        assertEquals(
            listOf("3201", "3202"),
            snapshot.publishingHouses.last().books.map { it.id }
        )
    }

    @Test
    fun keepsAvailableAuthorsAndLeavesTextOnlyEntriesBlank() {
        assertEquals("作者乙", snapshot[LinovelibExploreSection.NewBooks].first().author)
        assertEquals("作者丙", snapshot[LinovelibExploreSection.Popular].first().author)
        assertEquals("", snapshot[LinovelibExploreSection.Popular].last().author)
        assertEquals("作者丁", snapshot[LinovelibExploreSection.ClassicCompleted].first().author)
        assertEquals("作者戊", snapshot[LinovelibExploreSection.CompletedRecommended].first().author)
        assertEquals(
            listOf("作者己", "作者庚"),
            snapshot[LinovelibExploreSection.RecentUpdates].map { it.author }
        )
    }

    @Test
    fun fillsEveryMissingOrPlaceholderCoverFromBookId() {
        val allBooks = LinovelibExploreSection.entries.flatMap(snapshot::get) +
            snapshot.publishingHouses.flatMap { it.books }

        assertTrue(allBooks.all { it.coverUrl.isNotBlank() })
        assertEquals(
            "${LinovelibConstants.BASE_URL}/files/article/image/5/5001/5001s.jpg",
            snapshot[LinovelibExploreSection.CompletedRecommended].first().coverUrl
        )
        assertEquals(
            inferLinovelibCoverUrl("3002"),
            snapshot[LinovelibExploreSection.Popular].first { it.id == "3002" }.coverUrl
        )
        assertEquals(
            "${LinovelibConstants.BASE_URL}/files/article/image/0/8/8s.jpg",
            inferLinovelibCoverUrl("8")
        )
    }

    @Test
    fun deduplicatesWithinRowsButKeepsCrossRowBooks() {
        assertEquals(1, snapshot.publishingHouses.first().books.count { it.id == "3101" })
        assertTrue(snapshot[LinovelibExploreSection.HomeRecommended].any { it.id == "1001" })
        assertTrue(snapshot[LinovelibExploreSection.StrongRecommended].any { it.id == "1001" })
        assertTrue(snapshot[LinovelibExploreSection.Popular].any { it.id == "3002" })
        assertTrue(snapshot.publishingHouses.first().books.any { it.id == "3002" })
    }
}
