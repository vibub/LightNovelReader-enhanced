package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content

import io.nightfish.lightnovelreader.api.book.ChapterContent
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterContentNavigationTest {
    @Test
    fun missingAdjacentChaptersAreReportedAsUnavailable() {
        val content = chapterContent(prevChapter = null, nextChapter = null)

        assertFalse(content.hasPrevChapter())
        assertFalse(content.hasNextChapter())
    }

    @Test
    fun adjacentChapterIdsAreReportedAsAvailable() {
        val content = chapterContent(
            prevChapter = "chapter-prev",
            nextChapter = "chapter-next"
        )

        assertTrue(content.hasPrevChapter())
        assertTrue(content.hasNextChapter())
    }

    private fun chapterContent(
        prevChapter: String?,
        nextChapter: String?
    ) = ChapterContent(
        id = "chapter-current",
        title = "当前章节",
        content = buildJsonObject { },
        prevChapter = prevChapter,
        nextChapter = nextChapter
    )
}
