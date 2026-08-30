package indi.dmzz_yyhyy.lightnovelreader.data.local

import indi.dmzz_yyhyy.lightnovelreader.data.local.room.dao.BookInformationDao
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.dao.BookVolumesDao
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.dao.ChapterContentDao
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.dao.UserReadingDataDao
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.entity.ChapterContentEntity
import io.nightfish.lightnovelreader.api.book.BookInformation
import io.nightfish.lightnovelreader.api.book.BookVolumes
import io.nightfish.lightnovelreader.api.book.ChapterContent
import io.nightfish.lightnovelreader.api.book.LocalBookDataSourceApi
import io.nightfish.lightnovelreader.api.book.UserReadingData
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalBookDataSource @Inject constructor(
    private val bookInformationDao: BookInformationDao,
    private val bookVolumesDao: BookVolumesDao,
    private val chapterContentDao: ChapterContentDao,
    private val userReadingDataDao: UserReadingDataDao
): LocalBookDataSourceApi {
    private val userReadingDataUpdateMutex = Mutex()

    override suspend fun getBookInformation(id: String): BookInformation? = bookInformationDao.get(id)

    suspend fun getBookInformation(sourceId: Int, id: String): BookInformation? =
        bookInformationDao.getForSource(sourceId, id)

    suspend fun getBookInformationByIds(
        sourceId: Int,
        ids: List<String>
    ): Map<String, BookInformation> = ids.distinct()
        .chunked(500)
        .flatMap { bookInformationDao.getByIdsForSource(sourceId, it) }
        .associateBy(BookInformation::id)

    override suspend fun updateBookInformation(info: BookInformation) =
        bookInformationDao.insert(info)

    suspend fun updateBookInformation(sourceId: Int, info: BookInformation) =
        bookInformationDao.insertForSource(sourceId, info)

    override suspend fun getBookVolumes(id: String): BookVolumes? =
        bookVolumesDao.getBookVolumes(id).takeIf(::hasVolumes)

    suspend fun getBookVolumes(sourceId: Int, id: String): BookVolumes? =
        bookVolumesDao.getBookVolumes(sourceId, id).takeIf(::hasVolumes)

    override suspend fun updateBookVolumes(bookVolumes: BookVolumes) =
        bookVolumesDao.insertVolume(bookVolumes.bookId, bookVolumes)

    suspend fun updateBookVolumes(sourceId: Int, bookVolumes: BookVolumes) =
        bookVolumesDao.insertVolume(sourceId, bookVolumes.bookId, bookVolumes)

    override suspend fun getChapterContent(id: String) =
        chapterContentDao.get(id)?.toChapterContent()

    suspend fun getChapterContent(sourceId: Int, bookId: String, id: String): ChapterContent? =
        chapterContentDao.getScoped(sourceId, bookId, id)?.toChapterContent()

    suspend fun getExactChapterContent(sourceId: Int, bookId: String, id: String): ChapterContent? =
        chapterContentDao.get(sourceId, bookId, id)?.toChapterContent()

    override suspend fun updateChapterContent(chapterContent: ChapterContent) =
        chapterContentDao.update(chapterContent)

    suspend fun updateChapterContent(sourceId: Int, bookId: String, chapterContent: ChapterContent) =
        chapterContentDao.update(sourceId, bookId, chapterContent)

    suspend fun deleteChapterContent(sourceId: Int, bookId: String, chapterIds: List<String>) {
        val ids = chapterIds.map(String::trim).filter(String::isNotBlank).distinct()
        if (ids.isEmpty()) return
        chapterContentDao.deleteByIds(sourceId, bookId, ids)
        if (sourceId == ChapterContentEntity.LEGACY_SOURCE_ID) {
            chapterContentDao.deleteLegacyByIds(ids)
        }
    }

    override suspend fun getUserReadingData(id: String) = userReadingDataDao.getEntity(id).let {
        it ?: return@let UserReadingData(id)
        UserReadingData(
            it.id,
            if (it.lastReadTime == LocalDateTime.MIN) null else it.lastReadTime,
            it.totalReadTime,
            it.readingProgress,
            it.lastReadChapterId.ifEmpty { null },
            it.lastReadChapterTitle.ifEmpty { null },
            it.currentChapterReadingProgressMap,
            it.maxChapterReadingProgressMap

        )
    }

    fun getUserReadingDataFlow(id: String) = userReadingDataDao.getEntityFlow(id).map {
        it ?: return@map UserReadingData(
            id,
            null,
            0,
            0f,
            null,
            null,
            emptyMap(),
            emptyMap()
        )
        UserReadingData(
            it.id,
            if (it.lastReadTime == LocalDateTime.MIN) null else it.lastReadTime,
            it.totalReadTime,
            it.readingProgress,
            it.lastReadChapterId.ifEmpty { null },
            it.lastReadChapterTitle.ifEmpty { null },
            it.currentChapterReadingProgressMap,
            it.maxChapterReadingProgressMap
        )
    }

    override suspend fun updateUserReadingData(
        id: String,
        update: (UserReadingData) -> UserReadingData
    ) = userReadingDataUpdateMutex.withLock {
        val userReadingData = userReadingDataDao.getEntity(id)?.let {
            UserReadingData(
                it.id,
                it.lastReadTime,
                it.totalReadTime,
                it.readingProgress,
                it.lastReadChapterId,
                it.lastReadChapterTitle,
                it.currentChapterReadingProgressMap,
                it.maxChapterReadingProgressMap
            )
        } ?: UserReadingData(id)
        val new = update(userReadingData)
        userReadingDataDao.insert(
            id = new.id,
            lastReadTime = new.lastReadTime ?: LocalDateTime.MIN,
            totalReadTime = new.totalReadTime,
            readingProgress = new.readingProgress,
            lastReadChapterId = new.lastReadChapterId ?: "",
            lastReadChapterTitle = new.lastReadChapterTitle ?: "",
            currentChapterReadingProgressMap = new.currentChapterReadingProgressMap,
            maxChapterReadingProgressMap = new.maxChapterReadingProgressMap
        )
    }

    override suspend fun getAllUserReadingData(): List<UserReadingData> =
        userReadingDataDao.getAll().map {
            UserReadingData(
                it.id,
                it.lastReadTime,
                it.totalReadTime,
                it.readingProgress,
                it.lastReadChapterId,
                it.lastReadChapterTitle,
                it.currentChapterReadingProgressMap,
                it.maxChapterReadingProgressMap
            )
        }

    override suspend fun isChapterContentExists(id: String): Boolean =
        chapterContentDao.getId(id) != null

    override suspend fun clear() {
        userReadingDataDao.clear()
        bookInformationDao.clear()
        bookVolumesDao.clear()
        chapterContentDao.clear()
    }

    private fun hasVolumes(bookVolumes: BookVolumes): Boolean = bookVolumes.volumes.isNotEmpty()

    private fun ChapterContentEntity.toChapterContent() = ChapterContent(
        id,
        title,
        content,
        prevChapter.ifEmpty { null },
        nextChapter.ifEmpty { null }
    )
}
