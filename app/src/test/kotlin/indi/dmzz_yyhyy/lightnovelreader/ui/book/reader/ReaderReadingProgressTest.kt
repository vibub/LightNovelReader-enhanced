package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader

import io.nightfish.lightnovelreader.api.book.UserReadingData
import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderReadingProgressTest {
    @Test
    fun bookProgressIncludesTheCurrentChapterSnapshot() {
        val readingData = UserReadingData(
            id = "book",
            maxChapterReadingProgressMap = mapOf("chapter-1" to 0.5f)
        )

        val updated = readingData.copyWithUpdatedBookReadingProgress(
            chapterId = "chapter-2",
            progress = 1f,
            totalChapterCount = 2
        )

        assertEquals(0.75f, updated.readingProgress, 0.0001f)
        assertEquals(1f, updated.maxChapterReadingProgressMap["chapter-2"]!!, 0.0001f)
    }
}
