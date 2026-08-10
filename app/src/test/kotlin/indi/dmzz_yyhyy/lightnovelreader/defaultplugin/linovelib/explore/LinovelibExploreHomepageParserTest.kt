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
    fun parsesRecommendationCarouselAndStrongRecommendations() {
        val books = snapshot[LinovelibExploreSection.HomeRecommended]

        assertEquals(listOf("1001", "1002"), books.map { it.id })
        assertEquals(listOf("轮播推荐一", "强推推荐二"), books.map { it.title })
        assertEquals("作者甲", books.first().author)
        assertEquals(
            "${LinovelibConstants.BASE_URL}/files/article/image/1/1001/1001s.jpg",
            books.first().coverUrl
        )
        assertEquals(inferLinovelibCoverUrl("1002"), books[1].coverUrl)
    }

    @Test
    fun parsesAllHomepageSectionsWithoutGlobalLimit() {
        assertEquals(listOf("2001", "2002"), snapshot[LinovelibExploreSection.NewBooks].map { it.id })
        val popularIds = snapshot[LinovelibExploreSection.Popular].map { it.id }
        assertEquals(
            listOf("3001", "1001") + (3002..3014).map(Int::toString),
            popularIds
        )
        assertTrue(popularIds.size > 12)
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
        val allBooks = LinovelibExploreSection.entries.flatMap(snapshot::get)

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
    fun deduplicatesWithinSectionButKeepsCrossSectionBooks() {
        assertEquals(1, snapshot[LinovelibExploreSection.Popular].count { it.id == "3002" })
        assertTrue(snapshot[LinovelibExploreSection.HomeRecommended].any { it.id == "1001" })
        assertTrue(snapshot[LinovelibExploreSection.Popular].any { it.id == "1001" })
    }
}
