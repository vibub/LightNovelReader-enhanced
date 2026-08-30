package indi.dmzz_yyhyy.lightnovelreader

import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import indi.dmzz_yyhyy.lightnovelreader.data.download.ChapterDownloadRepository
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.LightNovelReaderDatabase
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.entity.ChapterDownloadEntity
import io.nightfish.lightnovelreader.api.book.BookInformation
import io.nightfish.lightnovelreader.api.book.BookVolumes
import io.nightfish.lightnovelreader.api.book.ChapterContent
import io.nightfish.lightnovelreader.api.book.ChapterInformation
import io.nightfish.lightnovelreader.api.book.Volume
import io.nightfish.lightnovelreader.api.book.WordCount
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime

@RunWith(AndroidJUnit4::class)
class SourceScopedDatabaseTest {
    private lateinit var database: LightNovelReaderDatabase

    @Before
    fun createDatabase() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, LightNovelReaderDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun sameBookIdKeepsBookVolumesChaptersAndContentSeparated() = runBlocking {
        val sourceA = 101
        val sourceB = 202
        val bookId = "same-book-id"
        val volumeId = "same-volume-id"
        val chapterId = "same-chapter-id"

        database.bookInformationDao().insertForSource(
            sourceA,
            bookInformation(bookId, "Source A book")
        )
        database.bookInformationDao().insertForSource(
            sourceB,
            bookInformation(bookId, "Source B book")
        )
        database.bookVolumesDao().insertVolume(
            sourceA,
            bookId,
            volumes(bookId, volumeId, chapterId, "Source A chapter")
        )
        database.bookVolumesDao().insertVolume(
            sourceB,
            bookId,
            volumes(bookId, volumeId, chapterId, "Source B chapter")
        )
        database.chapterContentDao().update(
            sourceA,
            bookId,
            ChapterContent(chapterId, "Source A chapter", JsonObject(emptyMap()), null, null)
        )
        database.chapterContentDao().update(
            sourceB,
            bookId,
            ChapterContent(chapterId, "Source B chapter", JsonObject(emptyMap()), null, null)
        )
        database.chapterDownloadDao().upsert(
            ChapterDownloadEntity(
                sourceId = sourceA,
                bookId = bookId,
                chapterId = chapterId,
                status = "COMPLETED"
            )
        )
        database.chapterDownloadDao().upsert(
            ChapterDownloadEntity(
                sourceId = sourceB,
                bookId = bookId,
                chapterId = chapterId,
                status = "FAILED"
            )
        )

        assertEquals(
            "Source A book",
            database.bookInformationDao().getForSource(sourceA, bookId)!!.title
        )
        assertEquals(
            "Source B book",
            database.bookInformationDao().getForSource(sourceB, bookId)!!.title
        )
        assertEquals(
            "Source A chapter",
            database.bookVolumesDao().getBookVolumes(sourceA, bookId)
                .volumes.single().chapters.single().title
        )
        assertEquals(
            "Source B chapter",
            database.bookVolumesDao().getBookVolumes(sourceB, bookId)
                .volumes.single().chapters.single().title
        )
        assertEquals(
            "Source A chapter",
            database.chapterContentDao().get(sourceA, bookId, chapterId)!!.title
        )
        assertEquals(
            "Source B chapter",
            database.chapterContentDao().get(sourceB, bookId, chapterId)!!.title
        )
        assertEquals(
            "COMPLETED",
            database.chapterDownloadDao().getStatus(sourceA, bookId, chapterId)
        )
        assertEquals(
            "FAILED",
            database.chapterDownloadDao().getStatus(sourceB, bookId, chapterId)
        )
        assertNotSame(
            database.bookVolumesDao().getBookVolumes(sourceA, bookId),
            database.bookVolumesDao().getBookVolumes(sourceB, bookId)
        )
    }

    @Test
    fun legacyChapterContentIsClaimedByOneSourceAndNotLeakedToAnother() = runBlocking {
        val sourceA = 101
        val sourceB = 202
        val bookId = "legacy-book"
        val chapterId = "legacy-chapter"

        database.chapterContentDao().update(
            ChapterContent(chapterId, "Legacy chapter", JsonObject(emptyMap()), null, null)
        )

        ChapterDownloadRepository(
            database.chapterDownloadDao(),
            database.chapterContentDao()
        ).migrateLegacyCachedChapters(sourceA, bookId, listOf(chapterId))

        assertEquals(
            "Legacy chapter",
            database.chapterContentDao().get(sourceA, bookId, chapterId)!!.title
        )
        assertEquals(null, database.chapterContentDao().getLegacy(chapterId))
        assertEquals(null, database.chapterContentDao().getScoped(sourceB, bookId, chapterId))
        assertEquals(
            "COMPLETED",
            database.chapterDownloadDao().getStatus(sourceA, bookId, chapterId)
        )
    }

    @Test
    fun legacyBookMetadataRemainsAvailableAsSourceFallback() = runBlocking {
        val bookId = "legacy-book"
        val volumeId = "legacy-volume"
        val chapterId = "legacy-chapter"
        val sourceId = 303

        database.bookInformationDao().insert(bookInformation(bookId, "Legacy book"))
        database.bookVolumesDao().insertVolume(
            bookId,
            volumes(bookId, volumeId, chapterId, "Legacy chapter")
        )

        assertEquals(
            "Legacy book",
            database.bookInformationDao().getForSource(sourceId, bookId)!!.title
        )
        assertEquals(
            "Legacy chapter",
            database.bookVolumesDao().getBookVolumes(sourceId, bookId)
                .volumes.single().chapters.single().title
        )
    }

    private fun bookInformation(id: String, title: String) = BookInformation(
        id = id,
        title = title,
        subtitle = "",
        coverUri = Uri.EMPTY,
        author = "",
        description = "",
        tags = emptyList(),
        publishingHouse = "",
        wordCount = WordCount(1),
        lastUpdated = LocalDateTime.now(),
        isComplete = false
    )

    private fun volumes(
        bookId: String,
        volumeId: String,
        chapterId: String,
        chapterTitle: String
    ) = BookVolumes(
        bookId,
        listOf(
            Volume(
                volumeId,
                "Volume $chapterTitle",
                listOf(ChapterInformation(chapterId, chapterTitle))
            )
        )
    )
}
