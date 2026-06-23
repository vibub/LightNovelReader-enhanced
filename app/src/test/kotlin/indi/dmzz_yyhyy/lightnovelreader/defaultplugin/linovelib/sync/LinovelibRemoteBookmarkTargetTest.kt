package indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinovelibRemoteBookmarkTargetTest {
    @Test
    fun fromUsesBaseChapterAndFirstPageForSinglePageChapter() {
        val target = LinovelibRemoteBookmarkTarget.from("2890", "245421")

        assertEquals("2890", target?.bookId)
        assertEquals("245421", target?.chapterId)
        assertEquals(1, target?.page)
        assertEquals("245421", target?.chapterPageId)
        assertEquals(
            "https://www.linovelib.com/modules/article/addbookcase.php?bid=2890&cid=245421&pid=1&ajax_request=1",
            target?.addBookcaseUrl
        )
        assertEquals(
            "https://www.linovelib.com/novel/2890/245421.html",
            target?.referer
        )
    }

    @Test
    fun fromSplitsPagedChapterIdIntoCidAndPid() {
        val target = LinovelibRemoteBookmarkTarget.from("/novel/2890.html", "245421_2.html")

        assertEquals("2890", target?.bookId)
        assertEquals("245421", target?.chapterId)
        assertEquals(2, target?.page)
        assertEquals("245421_2", target?.chapterPageId)
        assertEquals(
            "https://www.linovelib.com/modules/article/addbookcase.php?bid=2890&cid=245421&pid=2&ajax_request=1",
            target?.addBookcaseUrl
        )
        assertEquals(
            "https://www.linovelib.com/novel/2890/245421_2.html",
            target?.referer
        )
    }

    @Test
    fun fromRejectsInvalidPagedChapterId() {
        assertNull(LinovelibRemoteBookmarkTarget.from("2890", "245421_0"))
        assertNull(LinovelibRemoteBookmarkTarget.from("2890", "245421_"))
        assertNull(LinovelibRemoteBookmarkTarget.from("", "245421"))
    }
}
