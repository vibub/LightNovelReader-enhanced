package indi.dmzz_yyhyy.lightnovelreader.ui.book.detail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingEntryTest {
    @Test
    fun nullOrBlankChapterIdIsNotAReadingRecord() {
        assertFalse(hasReadingRecord(null))
        assertFalse(hasReadingRecord(""))
        assertFalse(hasReadingRecord("   "))
    }

    @Test
    fun nonBlankChapterIdIsAReadingRecord() {
        assertTrue(hasReadingRecord("chapter-12"))
    }

    @Test
    fun existingRecordResumesSavedChapterAndProgress() {
        assertEquals(
            ReadingEntry(
                chapterId = "chapter-12",
                restoreProgress = true
            ),
            resolveReadingEntry(
                lastReadChapterId = "chapter-12",
                firstChapterId = "chapter-1"
            )
        )
    }

    @Test
    fun missingRecordStartsFirstChapterWithoutRestoringProgress() {
        assertEquals(
            ReadingEntry(
                chapterId = "chapter-1",
                restoreProgress = false
            ),
            resolveReadingEntry(
                lastReadChapterId = " ",
                firstChapterId = "chapter-1"
            )
        )
    }

    @Test
    fun missingRecordAndCatalogProduceNoEntry() {
        assertNull(
            resolveReadingEntry(
                lastReadChapterId = null,
                firstChapterId = ""
            )
        )
    }
}
