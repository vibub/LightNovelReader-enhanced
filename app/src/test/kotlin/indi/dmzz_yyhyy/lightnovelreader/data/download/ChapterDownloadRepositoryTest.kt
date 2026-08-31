package indi.dmzz_yyhyy.lightnovelreader.data.download

import indi.dmzz_yyhyy.lightnovelreader.data.local.room.dao.ChapterContentDao
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.dao.ChapterDownloadDao
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.entity.ChapterContentEntity
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.entity.ChapterDownloadEntity
import io.nightfish.lightnovelreader.api.book.ChapterContent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterDownloadRepositoryTest {
    @Test
    fun queueDeduplicatesIdsAndSkipsCompletedCachedChapter() = runBlocking {
        val statusDao = InMemoryChapterDownloadDao()
        val contentDao = FakeChapterContentDao(setOf("cached"))
        statusDao.upsert(
            ChapterDownloadEntity(
                sourceId = 1,
                bookId = "book",
                chapterId = "cached",
                status = ChapterDownloadStatus.COMPLETED.name
            )
        )
        val repository = ChapterDownloadRepository(statusDao, contentDao)

        val queuedChapterIds = repository.queue(
            sourceId = 1,
            bookId = "book",
            chapterIds = listOf("cached", "queued", "queued")
        )

        assertEquals(listOf("queued"), queuedChapterIds)
        assertEquals(listOf("queued"), statusDao.getQueuedChapterIds(1, "book"))
    }

    @Test
    fun forceRefreshQueuesCompletedCachedChapter() = runBlocking {
        val statusDao = InMemoryChapterDownloadDao()
        val contentDao = FakeChapterContentDao(setOf("cached"))
        statusDao.upsert(
            ChapterDownloadEntity(
                sourceId = 1,
                bookId = "book",
                chapterId = "cached",
                status = ChapterDownloadStatus.COMPLETED.name
            )
        )
        val repository = ChapterDownloadRepository(statusDao, contentDao)

        repository.queue(1, "book", listOf("cached"), forceRefresh = true)

        assertEquals(listOf("cached"), statusDao.getQueuedChapterIds(1, "book"))
    }

    @Test
    fun legacyContentIsMigratedWithoutCreatingDownloadStatus() = runBlocking {
        val statusDao = InMemoryChapterDownloadDao()
        val contentDao = FakeChapterContentDao(emptySet(), legacyIds = setOf("chapter"))
        val repository = ChapterDownloadRepository(statusDao, contentDao)

        repository.migrateLegacyCachedChapters(3, "book", listOf("chapter", "chapter"))

        assertEquals(null, statusDao.getStatus(3, "book", "chapter"))
    }

    @Test
    fun existingScopedContentIsNotMarkedAsDownloaded() = runBlocking {
        val statusDao = InMemoryChapterDownloadDao()
        val contentDao = FakeChapterContentDao(setOf("chapter"))
        val repository = ChapterDownloadRepository(statusDao, contentDao)

        repository.migrateLegacyCachedChapters(3, "book", listOf("chapter"))

        assertEquals(null, statusDao.getStatus(3, "book", "chapter"))
        assertTrue(repository.getStates(3, "book").isEmpty())
    }

    @Test
    fun partialContentIsAvailableOfflineButNotFullyDownloaded() = runBlocking {
        val statusDao = InMemoryChapterDownloadDao()
        val contentDao = FakeChapterContentDao(setOf("chapter"))
        statusDao.upsert(
            ChapterDownloadEntity(
                sourceId = 1,
                bookId = "book",
                chapterId = "chapter",
                status = ChapterDownloadStatus.PARTIAL.name
            )
        )
        val repository = ChapterDownloadRepository(statusDao, contentDao)

        assertTrue(repository.isOfflineReady(1, "book", "chapter"))
        assertFalse(repository.isBookFullyDownloaded(1, "book", listOf("chapter")))
    }

    @Test
    fun statusesAreIsolatedBySourceAndBook() = runBlocking {
        val statusDao = InMemoryChapterDownloadDao()
        val contentDao = FakeChapterContentDao(setOf("chapter"))
        statusDao.upsert(
            ChapterDownloadEntity(
                sourceId = 1,
                bookId = "book-a",
                chapterId = "chapter",
                status = ChapterDownloadStatus.COMPLETED.name
            )
        )
        val repository = ChapterDownloadRepository(statusDao, contentDao)

        assertEquals(ChapterDownloadStatus.COMPLETED, repository.getStates(1, "book-a")["chapter"]!!.status)
        assertTrue(repository.getStates(2, "book-a").isEmpty())
        assertTrue(repository.getStates(1, "book-b").isEmpty())
    }

    private class InMemoryChapterDownloadDao : ChapterDownloadDao {
        private val entities = mutableMapOf<Triple<Int, String, String>, ChapterDownloadEntity>()

        override suspend fun upsert(entity: ChapterDownloadEntity) {
            entities[Triple(entity.sourceId, entity.bookId, entity.chapterId)] = entity
        }

        override suspend fun upsertAll(entities: List<ChapterDownloadEntity>) {
            for (entity in entities) upsert(entity)
        }

        override suspend fun getByBook(sourceId: Int, bookId: String): List<ChapterDownloadEntity> =
            entities.values.filter { it.sourceId == sourceId && it.bookId == bookId }

        override fun getByBookFlow(sourceId: Int, bookId: String): Flow<List<ChapterDownloadEntity>> =
            flowOf(entities.values.filter { it.sourceId == sourceId && it.bookId == bookId })

        override suspend fun getAll(): List<ChapterDownloadEntity> = entities.values.toList()

        override suspend fun getStatus(sourceId: Int, bookId: String, chapterId: String): String? =
            entities[Triple(sourceId, bookId, chapterId)]?.status

        override suspend fun delete(sourceId: Int, bookId: String, chapterId: String) {
            entities.remove(Triple(sourceId, bookId, chapterId))
        }

        override suspend fun getQueuedChapterIds(sourceId: Int, bookId: String): List<String> =
            getByBook(sourceId, bookId)
                .filter { it.status == ChapterDownloadStatus.QUEUED.name }
                .sortedWith(compareBy({ it.updatedAt }, { it.chapterId }))
                .map { it.chapterId }

        override suspend fun resetDownloading(sourceId: Int, bookId: String, updatedAt: Long) {
            getByBook(sourceId, bookId)
                .filter { it.status == ChapterDownloadStatus.DOWNLOADING.name }
                .forEach {
                    upsert(it.copy(status = ChapterDownloadStatus.QUEUED.name, updatedAt = updatedAt))
                }
        }

        override suspend fun deletePending(sourceId: Int, bookId: String) {
            getByBook(sourceId, bookId)
                .filter {
                    it.status == ChapterDownloadStatus.QUEUED.name ||
                        it.status == ChapterDownloadStatus.DOWNLOADING.name
                }
                .forEach { delete(sourceId, bookId, it.chapterId) }
        }

        override suspend fun deleteByChapterIds(sourceId: Int, bookId: String, chapterIds: List<String>) {
            chapterIds.forEach { delete(sourceId, bookId, it) }
        }

        override suspend fun deleteByBook(sourceId: Int, bookId: String) {
            getByBook(sourceId, bookId).forEach { delete(sourceId, bookId, it.chapterId) }
        }

        override suspend fun clear() = entities.clear()
    }

    private class FakeChapterContentDao(
        private val scopedIds: Set<String>,
        private val legacyIds: Set<String> = emptySet()
    ) : ChapterContentDao {
        private fun entity(id: String, sourceId: Int, bookId: String) = ChapterContentEntity(
            sourceId = sourceId,
            bookId = bookId,
            id = id,
            title = id,
            content = JsonObject(emptyMap()),
            prevChapter = "",
            nextChapter = ""
        )

        override suspend fun update(
            sourceId: Int,
            bookId: String,
            id: String,
            title: String,
            content: JsonObject,
            prevChapter: String,
            nextChapter: String
        ) = Unit

        override suspend fun update(sourceId: Int, bookId: String, chapterContent: ChapterContent) = Unit
        override suspend fun update(chapterContent: ChapterContent) = Unit
        override suspend fun update(chapterContent: ChapterContentEntity) = Unit
        override suspend fun get(sourceId: Int, bookId: String, id: String): ChapterContentEntity? =
            id.takeIf { it in scopedIds }?.let { entity(it, sourceId, bookId) }
        override suspend fun getLegacy(id: String): ChapterContentEntity? =
            id.takeIf(legacyIds::contains)?.let { entity(it, -1, "") }
        override suspend fun getScoped(sourceId: Int, bookId: String, id: String): ChapterContentEntity? =
            get(sourceId, bookId, id) ?: getLegacy(id)
        override suspend fun get(id: String): ChapterContentEntity? =
            getLegacy(id)
        override suspend fun getId(sourceId: Int, bookId: String, id: String): String? =
            id.takeIf { it in scopedIds }
        override suspend fun getId(id: String): String? =
            id.takeIf { it in scopedIds || it in legacyIds }
        override suspend fun getIds(sourceId: Int, bookId: String): List<String> = scopedIds.toList()
        override fun getIdsFlow(sourceId: Int, bookId: String): Flow<List<String>> = flowOf(scopedIds.toList())
        override suspend fun getLegacyId(id: String): String? = id.takeIf(legacyIds::contains)
        override suspend fun clear() = Unit
        override suspend fun delete(sourceId: Int, bookId: String, id: String) = Unit
        override suspend fun deleteByIds(sourceId: Int, bookId: String, ids: List<String>) = Unit
        override suspend fun deleteByBookIds(bookIds: List<String>) = Unit
        override suspend fun deleteLegacyByIds(ids: List<String>) = Unit
        override suspend fun deleteByBookIdAndIds(bookId: String, ids: List<String>) = Unit
        override suspend fun deleteByIds(ids: List<String>) = Unit
        override suspend fun updateEntities(vararg entities: ChapterContentEntity) = Unit
        override suspend fun getAllEntities(): List<ChapterContentEntity> = emptyList()
    }
}
