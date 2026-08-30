package indi.dmzz_yyhyy.lightnovelreader.data.download

import androidx.work.NetworkType
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.dao.DownloadTaskDao
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.entity.DownloadTaskEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadTaskRepositoryTest {
    @Test
    fun stateUpdateKeepsProgressCountersAndStorageStatistics() = runBlocking {
        val dao = InMemoryDownloadTaskDao()
        val repository = DownloadTaskRepository(dao)
        repository.markPaused(
            sourceId = 7,
            bookId = "book",
            progress = 0.4f,
            total = 10,
            processed = 4,
            sourceKey = "source:one",
            waitingReason = "等待存储空间",
            estimatedBytes = 10_000L,
            writtenBytes = 4_000L,
            currentChapterId = "chapter-5",
            currentChapterTitle = "第五章"
        )

        repository.updateItemState(
            sourceId = 7,
            bookId = "book",
            state = DownloadTaskStatus.COMPLETED,
            progress = 1f,
            sourceKey = "source:one"
        )

        val task = repository.get(7, "book")!!
        assertEquals(DownloadTaskStatus.COMPLETED.name, task.state)
        assertEquals("source:one", task.sourceKey)
        assertEquals(10, task.total)
        assertEquals(4, task.processed)
        assertEquals(10_000L, task.estimatedBytes)
        assertEquals(4_000L, task.writtenBytes)
        assertNull(task.currentChapterId)
        assertNull(task.currentChapterTitle)
        assertNull(task.waitingReason)
    }

    @Test
    fun resumeKeepsStoredSourceAndCounters() = runBlocking {
        val dao = InMemoryDownloadTaskDao()
        val repository = DownloadTaskRepository(dao)
        repository.markPaused(
            sourceId = 1,
            bookId = "book",
            progress = 0.5f,
            total = 8,
            processed = 4,
            sourceKey = "source:one",
            queueAll = true,
            estimatedBytes = 800L,
            writtenBytes = 400L
        )

        repository.resume(sourceId = 1, bookId = "book")

        val task = repository.get(1, "book")!!
        assertEquals(DownloadTaskStatus.RUNNING.name, task.state)
        assertEquals("source:one", task.sourceKey)
        assertTrue(task.queueAll)
        assertEquals(8, task.total)
        assertEquals(4, task.processed)
        assertEquals(800L, task.estimatedBytes)
        assertEquals(400L, task.writtenBytes)
        assertNull(task.waitingReason)
    }

    @Test
    fun resumeClearsStaleCurrentChapter() = runBlocking {
        val dao = InMemoryDownloadTaskDao()
        val repository = DownloadTaskRepository(dao)
        repository.markPaused(
            sourceId = 1,
            bookId = "book",
            progress = 0.5f,
            total = 8,
            processed = 4,
            currentChapterId = "chapter-5",
            currentChapterTitle = "第五章"
        )

        repository.resume(sourceId = 1, bookId = "book")

        val task = repository.get(1, "book")!!
        assertEquals(DownloadTaskStatus.RUNNING.name, task.state)
        assertNull(task.currentChapterId)
        assertNull(task.currentChapterTitle)
    }

    @Test
    fun progressUpdateWithoutChapterClearsStaleCurrentChapter() = runBlocking {
        val dao = InMemoryDownloadTaskDao()
        val repository = DownloadTaskRepository(dao)
        repository.markRunning(
            sourceId = 1,
            bookId = "book",
            total = 2,
            processed = 1,
            currentChapterId = "chapter-1",
            currentChapterTitle = "第一章"
        )

        repository.updateProgress(
            sourceId = 1,
            bookId = "book",
            progress = 0.5f,
            total = 2,
            processed = 1,
            currentChapterTitle = null
        )

        val task = repository.get(1, "book")!!
        assertNull(task.currentChapterId)
        assertNull(task.currentChapterTitle)
    }

    @Test
    fun runningStateUpdateWithoutChapterClearsStaleCurrentChapter() = runBlocking {
        val dao = InMemoryDownloadTaskDao()
        val repository = DownloadTaskRepository(dao)
        repository.markPaused(
            sourceId = 1,
            bookId = "book",
            currentChapterId = "chapter-5",
            currentChapterTitle = "第五章"
        )

        repository.updateItemState(
            sourceId = 1,
            bookId = "book",
            state = DownloadTaskStatus.RUNNING,
            progress = 0.5f
        )

        val task = repository.get(1, "book")!!
        assertNull(task.currentChapterId)
        assertNull(task.currentChapterTitle)
    }

    @Test
    fun failedStatePersistsErrorMessage() = runBlocking {
        val dao = InMemoryDownloadTaskDao()
        val repository = DownloadTaskRepository(dao)

        repository.markFailed(
            sourceId = 1,
            bookId = "book",
            message = "章节请求超时",
            total = 3,
            processed = 1
        )

        repository.updateItemState(
            sourceId = 1,
            bookId = "book",
            state = DownloadTaskStatus.FAILED,
            progress = 1f,
            errorMessage = "章节请求超时"
        )

        assertEquals("章节请求超时", repository.get(1, "book")!!.errorMessage)
    }

    @Test
    fun downloadSettingsUseConfiguredNetworkConstraint() {
        assertEquals(
            NetworkType.UNMETERED,
            DownloadSettings(DownloadNetworkPolicy.WIFI_ONLY).constraints().requiredNetworkType
        )
        assertEquals(
            NetworkType.CONNECTED,
            DownloadSettings(DownloadNetworkPolicy.ANY_NETWORK).constraints().requiredNetworkType
        )
    }

    private class InMemoryDownloadTaskDao : DownloadTaskDao {
        private val tasks = mutableMapOf<Pair<Int, String>, DownloadTaskEntity>()

        override suspend fun upsert(entity: DownloadTaskEntity) {
            tasks[entity.sourceId to entity.bookId] = entity
        }

        override suspend fun upsertAll(entities: List<DownloadTaskEntity>) {
            for (entity in entities) upsert(entity)
        }

        override suspend fun get(sourceId: Int, bookId: String): DownloadTaskEntity? =
            tasks[sourceId to bookId]

        override suspend fun getAll(): List<DownloadTaskEntity> = tasks.values.toList()

        override suspend fun delete(sourceId: Int, bookId: String) {
            tasks.remove(sourceId to bookId)
        }

        override suspend fun clear() {
            tasks.clear()
        }
    }
}
