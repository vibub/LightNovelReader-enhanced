package indi.dmzz_yyhyy.lightnovelreader.ui.book.detail

import io.nightfish.lightnovelreader.api.book.UserReadingData
import org.junit.Assert.assertEquals
import org.junit.Test

class DetailReadingActionsTest {
    @Test
    fun markSelectedChaptersAsReadUpdatesCurrentAndMaxProgress() {
        val readingData = UserReadingData(
            id = "book",
            currentChapterReadingProgressMap = mapOf("chapter-1" to 0.25f),
            maxChapterReadingProgressMap = mapOf("chapter-1" to 0.25f)
        )

        val updated = readingData.copyWithMarkedChaptersAsRead(
            chapterIds = listOf("chapter-2"),
            allChapterIds = listOf("chapter-1", "chapter-2", "chapter-3")
        )

        assertEquals(1f, updated.currentChapterReadingProgressMap["chapter-2"]!!, 0.0001f)
        assertEquals(1f, updated.maxChapterReadingProgressMap["chapter-2"]!!, 0.0001f)
        assertEquals(0.41666666f, updated.readingProgress, 0.0001f)
    }

    @Test
    fun markReadThroughKeepsExistingProgressAndMarksTheWholeRange() {
        val readingData = UserReadingData(
            id = "book",
            currentChapterReadingProgressMap = mapOf("chapter-1" to 0.4f),
            maxChapterReadingProgressMap = mapOf("chapter-1" to 0.4f)
        )

        val updated = readingData.copyWithMarkedChaptersAsRead(
            chapterIds = listOf("chapter-1", "chapter-2"),
            allChapterIds = listOf("chapter-1", "chapter-2", "chapter-3")
        )

        assertEquals(1f, updated.currentChapterReadingProgressMap["chapter-1"]!!, 0.0001f)
        assertEquals(1f, updated.currentChapterReadingProgressMap["chapter-2"]!!, 0.0001f)
        assertEquals(2f / 3f, updated.readingProgress, 0.0001f)
    }

    @Test
    fun unknownChapterIdsDoNotChangeReadingData() {
        val readingData = UserReadingData(
            id = "book",
            currentChapterReadingProgressMap = mapOf("chapter-1" to 0.4f),
            maxChapterReadingProgressMap = mapOf("chapter-1" to 0.4f)
        )

        val updated = readingData.copyWithMarkedChaptersAsRead(
            chapterIds = listOf("unknown"),
            allChapterIds = listOf("chapter-1")
        )

        assertEquals(readingData, updated)
    }
}
