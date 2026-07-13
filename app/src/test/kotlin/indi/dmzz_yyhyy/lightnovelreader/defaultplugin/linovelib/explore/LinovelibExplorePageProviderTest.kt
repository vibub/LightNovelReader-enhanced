package indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.explore

import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.book.LinovelibWebsiteDataSource
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.net.LinovelibBlockedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LinovelibExplorePageProviderTest {
    @Test
    fun desktopResultAtLimitSkipsMobileSource() = runBlocking {
        var mobileCalled = false

        val books = loadLinovelibExploreBooks(onError = {}) { source ->
            when (source) {
                LinovelibExploreSource.Desktop -> (1..12).map { book(it.toString()) }
                LinovelibExploreSource.Mobile -> {
                    mobileCalled = true
                    emptyList()
                }
            }
        }

        assertEquals((1..12).map(Int::toString), books.map { it.id })
        assertFalse(mobileCalled)
    }

    @Test
    fun desktopResultOverLimitKeepsFirstTwelve() = runBlocking {
        val books = loadLinovelibExploreBooks(onError = {}) { source ->
            if (source == LinovelibExploreSource.Desktop) {
                (1..15).map { book(it.toString()) }
            } else {
                error("mobile source should not be called")
            }
        }

        assertEquals((1..12).map(Int::toString), books.map { it.id })
    }

    @Test
    fun mobileSourceSupplementsPartialDesktopResult() = runBlocking {
        val books = loadLinovelibExploreBooks(onError = {}) { source ->
            when (source) {
                LinovelibExploreSource.Desktop -> listOf(book("1"), book("2"), book("3"))
                LinovelibExploreSource.Mobile -> listOf(book("2", "mobile-2"), book("4"), book("5"))
            }
        }

        assertEquals(listOf("1", "2", "3", "4", "5"), books.map { it.id })
        assertEquals("2", books[1].title)
    }

    @Test
    fun blockedDesktopSourceFallsBackToMobileSource() = runBlocking {
        val books = loadLinovelibExploreBooks(onError = {}) { source ->
            when (source) {
                LinovelibExploreSource.Desktop -> throw LinovelibBlockedException("blocked")
                LinovelibExploreSource.Mobile -> listOf(book("4"), book("5"))
            }
        }

        assertEquals(listOf("4", "5"), books.map { it.id })
    }

    @Test
    fun failedDesktopSourceFallsBackToMobileSource() = runBlocking {
        val books = loadLinovelibExploreBooks(onError = {}) { source ->
            when (source) {
                LinovelibExploreSource.Desktop -> error("desktop failed")
                LinovelibExploreSource.Mobile -> listOf(book("4"), book("5"))
            }
        }

        assertEquals(listOf("4", "5"), books.map { it.id })
    }

    @Test
    fun failedMobileSourceKeepsPartialDesktopResult() = runBlocking {
        val books = loadLinovelibExploreBooks(onError = {}) { source ->
            when (source) {
                LinovelibExploreSource.Desktop -> listOf(book("1"), book("2"))
                LinovelibExploreSource.Mobile -> error("mobile failed")
            }
        }

        assertEquals(listOf("1", "2"), books.map { it.id })
    }

    @Test
    fun failedSourcesReturnEmptyResult() = runBlocking {
        val books = loadLinovelibExploreBooks(onError = {}) {
            error("source failed")
        }

        assertTrue(books.isEmpty())
    }

    @Test
    fun cancellationIsNotSwallowed() {
        assertThrows(CancellationException::class.java) {
            runBlocking {
                loadLinovelibExploreBooks(onError = {}) {
                    throw CancellationException("cancelled")
                }
            }
        }
    }

    @Test
    fun blankAndDuplicateBookIdsAreFilteredBeforeSupplementing() = runBlocking {
        val books = loadLinovelibExploreBooks(onError = {}) { source ->
            when (source) {
                LinovelibExploreSource.Desktop -> listOf(
                    book(""),
                    book("1", "desktop-1"),
                    book("1", "desktop-duplicate")
                )
                LinovelibExploreSource.Mobile -> listOf(
                    book("1", "mobile-1"),
                    book("2")
                )
            }
        }

        assertEquals(listOf("1", "2"), books.map { it.id })
        assertEquals("desktop-1", books.first().title)
    }

    private fun book(
        id: String,
        title: String = id
    ): LinovelibWebsiteDataSource.LinovelibExploreBook =
        LinovelibWebsiteDataSource.LinovelibExploreBook(
            id = id,
            title = title,
            author = "author-$id",
            coverUrl = "/files/article/image/$id.jpg"
        )
}
