package indi.dmzz_yyhyy.lightnovelreader.data.storage

import org.junit.Assert.assertEquals
import org.junit.Test

class BookStorageUsageTest {
    @Test
    fun totalBytesIncludesOfflineContentBytes() {
        val usage = BookStorageUsage(
            bookId = "book",
            bookInformationBytes = 100L,
            volumeBytes = 200L,
            chapterInformationBytes = 300L,
            chapterContentBytes = 400L,
            offlineContentBytes = 500L
        )

        assertEquals(1_500L, usage.totalBytes)
    }

    @Test
    fun oldStorageSnapshotWithoutOfflineContentKeepsExistingTotal() {
        val usage = BookStorageUsage(
            bookId = "book",
            bookInformationBytes = 100L,
            volumeBytes = 200L,
            chapterInformationBytes = 300L,
            chapterContentBytes = 400L
        )

        assertEquals(1_000L, usage.totalBytes)
    }
}
