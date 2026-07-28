package indi.dmzz_yyhyy.lightnovelreader.ui.home.bookshelf.home

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import indi.dmzz_yyhyy.lightnovelreader.ui.home.bookshelf.lastChapterTitleOrNull
import indi.dmzz_yyhyy.lightnovelreader.ui.home.bookshelf.toBookshelfUiState
import io.nightfish.lightnovelreader.api.book.BookVolumes
import io.nightfish.lightnovelreader.api.book.ChapterInformation
import io.nightfish.lightnovelreader.api.book.Volume
import io.nightfish.lightnovelreader.api.bookshelf.Bookshelf
import io.nightfish.lightnovelreader.api.bookshelf.BookshelfSortType
import io.nightfish.lightnovelreader.api.error.WebRequestError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class BookshelfHomePerformanceTest {
    @Test
    fun uiStateRetainsOnlyBookshelfBookIds() {
        val uiState = Bookshelf(
            id = 1,
            name = "测试书架",
            sortType = BookshelfSortType.Default,
            sortReversed = false,
            autoCache = true,
            systemUpdateReminder = true,
            allBookIds = listOf("book-1", "book-2", "book-3"),
            pinnedBookIds = listOf("book-2"),
            updatedBookIds = listOf("book-3")
        ).toBookshelfUiState()

        assertEquals(listOf("book-1", "book-2", "book-3"), uiState.allBookIds)
        assertEquals(listOf("book-2"), uiState.pinnedBookIds)
        assertEquals(listOf("book-3"), uiState.updatedBookIds)
    }

    @Test
    fun defaultSortUsesStableAllBookOrderWithoutSnapshots() {
        val sortedIds = sortBookIds(
            source = listOf("book-3", "book-2"),
            allBookIds = listOf("book-1", "book-2", "book-3"),
            sortType = BookshelfSortType.Default,
            sortReversed = true,
            bookSortSnapshots = emptyList()
        )

        assertEquals(listOf("book-2", "book-3"), sortedIds)
    }

    @Test
    fun latestSortUsesSharedInformationSnapshots() {
        val sortedIds = sortBookIds(
            source = listOf("book-1", "book-2", "book-3"),
            allBookIds = listOf("book-1", "book-2", "book-3"),
            sortType = BookshelfSortType.Latest,
            sortReversed = false,
            bookSortSnapshots = listOf(
                BookSortSnapshot("book-1", LocalDateTime.of(2026, 1, 1, 0, 0), "a", 1),
                BookSortSnapshot("book-2", LocalDateTime.of(2026, 1, 3, 0, 0), "b", 2),
                BookSortSnapshot("book-3", LocalDateTime.of(2026, 1, 2, 0, 0), "c", 3),
            )
        )

        assertEquals(listOf("book-2", "book-3", "book-1"), sortedIds)
    }

    @Test
    fun updatedCardLeavesLatestChapterEmptyWhenVolumesFail() {
        val bookVolumes: Result<BookVolumes, WebRequestError> = Err(
            WebRequestError("目录失败", "无法加载目录")
        )
        val latestChapterTitle = bookVolumes.lastChapterTitleOrNull()

        assertNull(latestChapterTitle)
    }

    @Test
    fun updatedCardDisplaysLatestChapterWhenVolumesSucceed() {
        val bookVolumes: Result<BookVolumes, WebRequestError> = Ok(
            BookVolumes(
                bookId = "book-1",
                volumes = listOf(
                    Volume(
                        volumeId = "volume-1",
                        volumeTitle = "第一卷",
                        chapters = listOf(ChapterInformation("chapter-1", "第一章"))
                    )
                )
            )
        )
        val latestChapterTitle = bookVolumes.lastChapterTitleOrNull()

        assertEquals("第一卷 第一章", latestChapterTitle)
    }

    @Test
    fun bookshelfItemKeyParserIgnoresHeadersAndReturnsSection() {
        assertEquals(
            BookshelfBookListItem(BookshelfBookSection.Updated, "book-1"),
            parseBookshelfBookListItemKey("updated_book-1")
        )
        assertEquals(
            BookshelfBookListItem(BookshelfBookSection.Pinned, "book-2"),
            parseBookshelfBookListItemKey("pinned_book-2")
        )
        assertEquals(
            BookshelfBookListItem(BookshelfBookSection.All, "book-3"),
            parseBookshelfBookListItemKey("book_book-3")
        )
        assertNull(parseBookshelfBookListItemKey("header_all"))
        assertNull(parseBookshelfBookListItemKey(null))
    }

    @Test
    fun visibleWindowIncludesFourBooksBeforeAndAfterVisibleBook() {
        val allBookIds = (1..12).map { "book-$it" }

        val window = createBookshelfVisibleWindow(
            visibleItemKeys = listOf("book_book-6"),
            updatedBookIds = emptyList(),
            updatedExpanded = false,
            pinnedBookIds = emptyList(),
            pinnedExpanded = false,
            allBookIds = allBookIds,
            allExpanded = true
        )

        assertEquals((2..10).map { "book-$it" }.toSet(), window.detailBookIds)
        assertTrue(window.updatedBookIds.isEmpty())
    }

    @Test
    fun visibleWindowDeduplicatesBooksAcrossExpandedGroups() {
        val window = createBookshelfVisibleWindow(
            visibleItemKeys = listOf("updated_book-2", "pinned_book-2", "book_book-2"),
            updatedBookIds = listOf("book-1", "book-2", "book-3"),
            updatedExpanded = true,
            pinnedBookIds = listOf("book-2", "book-4"),
            pinnedExpanded = true,
            allBookIds = listOf("book-1", "book-2", "book-3", "book-4"),
            allExpanded = true
        )

        assertEquals(setOf("book-1", "book-2", "book-3", "book-4"), window.detailBookIds)
        assertEquals(setOf("book-1", "book-2", "book-3"), window.updatedBookIds)
    }

    @Test
    fun collapsedGroupDoesNotContributeToVisibleWindow() {
        val window = createBookshelfVisibleWindow(
            visibleItemKeys = listOf("updated_book-2"),
            updatedBookIds = listOf("book-1", "book-2", "book-3"),
            updatedExpanded = false,
            pinnedBookIds = emptyList(),
            pinnedExpanded = false,
            allBookIds = listOf("book-1", "book-2", "book-3"),
            allExpanded = false
        )

        assertTrue(window.detailBookIds.isEmpty())
        assertTrue(window.updatedBookIds.isEmpty())
    }

    @Test
    fun initialWindowUsesFirstEightBooksFromFirstExpandedGroup() {
        val updatedBookIds = (1..12).map { "book-$it" }

        val window = createBookshelfVisibleWindow(
            visibleItemKeys = emptyList(),
            updatedBookIds = updatedBookIds,
            updatedExpanded = true,
            pinnedBookIds = listOf("pinned-1"),
            pinnedExpanded = true,
            allBookIds = updatedBookIds,
            allExpanded = true
        )

        assertEquals((1..8).map { "book-$it" }.toSet(), window.detailBookIds)
        assertEquals((1..8).map { "book-$it" }.toSet(), window.updatedBookIds)
    }

    @Test
    fun existingDetailsSurviveRemoteFailure() {
        assertTrue(shouldKeepExistingBookInformation(hasExisting = true, remoteSucceeded = false))
        assertFalse(shouldKeepExistingBookInformation(hasExisting = false, remoteSucceeded = false))
    }

    @Test
    fun failedVolumesKeepPreviousLatestChapterTitle() {
        assertEquals(
            "第一卷 第一章",
            mergeLatestChapterTitle(
                previousTitle = "第一卷 第一章",
                requestedTitle = null,
                requestSucceeded = false
            )
        )
        assertNull(
            mergeLatestChapterTitle(
                previousTitle = null,
                requestedTitle = null,
                requestSucceeded = false
            )
        )
    }
}