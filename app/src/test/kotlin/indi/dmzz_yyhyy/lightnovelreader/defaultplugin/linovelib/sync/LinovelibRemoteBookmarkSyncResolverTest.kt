package indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.sync

import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.account.LinovelibAccountDataSource
import io.nightfish.lightnovelreader.api.book.BookVolumes
import io.nightfish.lightnovelreader.api.book.ChapterInformation
import io.nightfish.lightnovelreader.api.book.Volume
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinovelibRemoteBookmarkSyncResolverTest {
    @Test
    fun resolveDirectUsesBookcaseChapterId() {
        val result = LinovelibRemoteBookmarkSyncResolver.resolveDirect(
            remoteBook = remoteBook(
                bookmarkChapterId = "287057_2",
                bookmarkChapterTitle = "插图"
            )
        )

        assertEquals("287057_2", result?.chapterId)
        assertEquals("插图", result?.chapterTitle)
        assertTrue(result?.resolved == true)
    }

    @Test
    fun resolveDirectReturnsNullWithoutBookcaseChapterId() {
        val result = LinovelibRemoteBookmarkSyncResolver.resolveDirect(
            remoteBook = remoteBook(
                bookmarkChapterId = "",
                bookmarkChapterTitle = "第1章 开始"
            )
        )

        assertNull(result)
    }

    @Test
    fun resolveDirectReturnsNullForZeroChapterId() {
        val result = LinovelibRemoteBookmarkSyncResolver.resolveDirect(
            remoteBook = remoteBook(
                bookmarkChapterId = "0",
                bookmarkChapterTitle = "第1章 开始"
            )
        )

        assertNull(result)
    }

    @Test
    fun resolveWithVolumesMatchesTitleAfterCatalogFetch() {
        val result = LinovelibRemoteBookmarkSyncResolver.resolveWithVolumes(
            remoteBook = remoteBook(
                bookmarkChapterId = "",
                bookmarkChapterTitle = "第1章 开始"
            ),
            volumes = bookVolumes(
                volume("第一卷", chapter("1001", "第1章 开始"))
            )
        )

        assertEquals("1001", result.chapterId)
        assertEquals("第1章 开始", result.chapterTitle)
        assertTrue(result.resolved)
    }

    @Test
    fun resolveWithVolumesKeepsDuplicateTitleUnresolved() {
        val result = LinovelibRemoteBookmarkSyncResolver.resolveWithVolumes(
            remoteBook = remoteBook(
                bookmarkChapterId = "",
                bookmarkChapterTitle = "插图"
            ),
            volumes = bookVolumes(
                volume("第一卷", chapter("1", "插图")),
                volume("第二卷", chapter("2", "插图"))
            )
        )

        assertEquals("", result.chapterId)
        assertEquals("插图", result.chapterTitle)
        assertFalse(result.resolved)
    }

    @Test
    fun resolveWithVolumesIgnoresHrefIdWithoutBookcaseChapterId() {
        val result = LinovelibRemoteBookmarkSyncResolver.resolveWithVolumes(
            remoteBook = remoteBook(
                bookmarkChapterId = "",
                bookmarkChapterTitle = "插图",
                bookmarkHref = "https://www.linovelib.com/novel/2734/2.html"
            ),
            volumes = bookVolumes(
                volume("第一卷", chapter("1", "插图")),
                volume("第二卷", chapter("2", "插图"))
            )
        )

        assertEquals("", result.chapterId)
        assertEquals("插图", result.chapterTitle)
        assertFalse(result.resolved)
    }

    private fun remoteBook(
        bookmarkChapterId: String,
        bookmarkChapterTitle: String,
        bookmarkHref: String = ""
    ): LinovelibAccountDataSource.LinovelibRemoteBook = LinovelibAccountDataSource.LinovelibRemoteBook(
        bookId = "2734",
        title = "转生公主与天才千金的魔法革命",
        bookmarkChapterId = bookmarkChapterId,
        bookmarkChapterTitle = bookmarkChapterTitle,
        bookmarkHref = bookmarkHref
    )

    private fun bookVolumes(vararg volumes: Volume): BookVolumes = BookVolumes("2734", volumes.toList())

    private fun volume(title: String, vararg chapters: ChapterInformation): Volume =
        Volume(title, title, chapters.toList())

    private fun chapter(id: String, title: String): ChapterInformation = ChapterInformation(id, title)
}
