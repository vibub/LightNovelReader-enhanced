package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.flip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FlipPageProgressSessionTest {
    @Test
    fun rebuiltPagerRestoresLatestProgressAndRejectsOldPagerUpdates() {
        val progressSession = FlipPageProgressSession()
        val visitId = progressSession.beginChapter("chapter-a")
        val firstPager = progressSession.installPager("chapter-a", contentPageCount = 6)!!

        assertNull(firstPager.restoreRequest)
        val initialRestore = progressSession.loadRestoreProgress(
            visitId = visitId,
            chapterId = "chapter-a",
            progress = 0.5f
        )!!
        assertTrue(progressSession.completeRestore(initialRestore))
        assertTrue(progressSession.acceptProgress(firstPager.session, 2f / 3f))

        val rebuiltPager = progressSession.installPager("chapter-a", contentPageCount = 6)!!
        val rebuiltRestore = rebuiltPager.restoreRequest
        assertNotNull(rebuiltRestore)
        assertEquals(2f / 3f, rebuiltRestore!!.progress, 0.0001f)
        assertFalse(progressSession.acceptProgress(firstPager.session, 1f))
        assertTrue(progressSession.completeRestore(rebuiltRestore))
        assertTrue(progressSession.acceptProgress(rebuiltPager.session, 5f / 6f))
    }

    @Test
    fun staleRestoreFromEarlierVisitCannotCompleteAfterReturningToSameChapter() {
        val progressSession = FlipPageProgressSession()
        val firstVisitId = progressSession.beginChapter("chapter-a")
        progressSession.installPager("chapter-a", contentPageCount = 6)
        val staleRestore = progressSession.loadRestoreProgress(
            visitId = firstVisitId,
            chapterId = "chapter-a",
            progress = 0.5f
        )!!

        progressSession.beginChapter("chapter-b")
        val currentVisitId = progressSession.beginChapter("chapter-a")
        progressSession.installPager("chapter-a", contentPageCount = 6)
        val currentRestore = progressSession.loadRestoreProgress(
            visitId = currentVisitId,
            chapterId = "chapter-a",
            progress = 0.25f
        )!!

        assertFalse(progressSession.completeRestore(staleRestore))
        assertTrue(progressSession.completeRestore(currentRestore))
    }

    @Test
    fun restoreLoadedBeforePagerIsAppliedWhenCurrentPagerArrives() {
        val progressSession = FlipPageProgressSession()
        val visitId = progressSession.beginChapter("chapter-b")

        assertNull(
            progressSession.loadRestoreProgress(
                visitId = visitId,
                chapterId = "chapter-b",
                progress = 0.75f
            )
        )
        assertNull(progressSession.installPager("chapter-a", contentPageCount = 4))

        val installation = progressSession.installPager("chapter-b", contentPageCount = 4)!!
        assertEquals(0.75f, installation.restoreRequest!!.progress, 0.0001f)
    }
}
