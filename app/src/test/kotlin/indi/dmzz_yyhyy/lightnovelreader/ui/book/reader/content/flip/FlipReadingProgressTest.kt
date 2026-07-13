package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.flip

import org.junit.Assert.assertEquals
import org.junit.Test

class FlipReadingProgressTest {
    @Test
    fun firstAndMiddleContentPagesUseContentPageCount() {
        assertEquals(0.25f, flipReadingProgress(settledPage = 0, contentPageCount = 4), 0.0001f)
        assertEquals(0.5f, flipReadingProgress(settledPage = 1, contentPageCount = 4), 0.0001f)
    }

    @Test
    fun lastContentPageAndChapterEndPageBothMeanComplete() {
        assertEquals(1f, flipReadingProgress(settledPage = 3, contentPageCount = 4), 0.0001f)
        assertEquals(1f, flipReadingProgress(settledPage = 4, contentPageCount = 4), 0.0001f)
    }

    @Test
    fun completedSavedProgressRestoresToLastContentPage() {
        assertEquals(3, flipRestoreContentPage(progress = 1f, contentPageCount = 4))
    }

    @Test
    fun restoreMapsIntermediateProgressWithinContentPages() {
        assertEquals(1, flipRestoreContentPage(progress = 0.5f, contentPageCount = 4))
    }

    @Test
    fun emptyAndOutOfRangeInputsConvergeSafely() {
        assertEquals(0f, flipReadingProgress(settledPage = 10, contentPageCount = 0), 0.0001f)
        assertEquals(0.25f, flipReadingProgress(settledPage = -5, contentPageCount = 4), 0.0001f)
        assertEquals(0, flipRestoreContentPage(progress = Float.NaN, contentPageCount = 4))
        assertEquals(0, flipRestoreContentPage(progress = -1f, contentPageCount = 4))
        assertEquals(3, flipRestoreContentPage(progress = 2f, contentPageCount = 4))
        assertEquals(0, flipRestoreContentPage(progress = 1f, contentPageCount = 0))
    }
}
