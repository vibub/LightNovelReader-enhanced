package indi.dmzz_yyhyy.lightnovelreader.data.local

import indi.dmzz_yyhyy.lightnovelreader.data.local.room.dao.BookInformationDao
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.dao.BookVolumesDao
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.dao.ChapterContentDao
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.dao.UserReadingDataDao
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.entity.UserReadingDataEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.time.LocalDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class LocalBookDataSourceTest {
    @Test
    fun concurrentUserReadingUpdatesDoNotOverwriteEachOther() {
        val dao = BlockingUserReadingDataDao()
        val dataSource = LocalBookDataSource(
            bookInformationDao = unusedDao(BookInformationDao::class.java),
            bookVolumesDao = unusedDao(BookVolumesDao::class.java),
            chapterContentDao = unusedDao(ChapterContentDao::class.java),
            userReadingDataDao = dao
        )
        val executor = Executors.newFixedThreadPool(2)

        try {
            val progressUpdate = executor.submit {
                runBlocking {
                    dataSource.updateUserReadingData(BOOK_ID) {
                        it.copyWithUpdatedChapterReadingProgress(CHAPTER_ID, 0.5f)
                    }
                }
            }
            assertTrue(dao.firstInsertStarted.await(5, TimeUnit.SECONDS))

            val readingTimeUpdate = executor.submit {
                runBlocking {
                    dataSource.updateUserReadingData(BOOK_ID) {
                        it.copy(totalReadTime = 42)
                    }
                }
            }
            try {
                assertFalse(dao.secondReadStarted.await(200, TimeUnit.MILLISECONDS))
            } finally {
                dao.releaseFirstInsert.countDown()
            }

            progressUpdate.get(5, TimeUnit.SECONDS)
            readingTimeUpdate.get(5, TimeUnit.SECONDS)

            val stored = dao.stored.get()
            assertEquals(42, stored.totalReadTime)
            assertEquals(0.5f, stored.currentChapterReadingProgressMap[CHAPTER_ID]!!, 0.0001f)
        } finally {
            dao.releaseFirstInsert.countDown()
            executor.shutdownNow()
        }
    }

    private class BlockingUserReadingDataDao: UserReadingDataDao {
        val stored = AtomicReference(
            UserReadingDataEntity(
                id = BOOK_ID,
                lastReadTime = LocalDateTime.MIN,
                totalReadTime = 0,
                readingProgress = 0f,
                lastReadChapterId = "",
                lastReadChapterTitle = "",
                currentChapterReadingProgressMap = emptyMap(),
                maxChapterReadingProgressMap = emptyMap()
            )
        )
        val firstInsertStarted = CountDownLatch(1)
        val releaseFirstInsert = CountDownLatch(1)
        val secondReadStarted = CountDownLatch(1)
        private val readCount = AtomicInteger()
        private val insertCount = AtomicInteger()

        override suspend fun insert(
            id: String,
            lastReadTime: LocalDateTime,
            totalReadTime: Int,
            readingProgress: Float,
            lastReadChapterId: String,
            lastReadChapterTitle: String,
            currentChapterReadingProgressMap: Map<String, Float>,
            maxChapterReadingProgressMap: Map<String, Float>
        ) {
            val entity = UserReadingDataEntity(
                id = id,
                lastReadTime = lastReadTime,
                totalReadTime = totalReadTime,
                readingProgress = readingProgress,
                lastReadChapterId = lastReadChapterId,
                lastReadChapterTitle = lastReadChapterTitle,
                currentChapterReadingProgressMap = currentChapterReadingProgressMap,
                maxChapterReadingProgressMap = maxChapterReadingProgressMap
            )
            if (insertCount.getAndIncrement() == 0) {
                firstInsertStarted.countDown()
                releaseFirstInsert.await(5, TimeUnit.SECONDS)
            }
            stored.set(entity)
        }

        override suspend fun insert(userReading: UserReadingDataEntity) {
            stored.set(userReading)
        }

        override suspend fun getEntity(id: String): UserReadingDataEntity? {
            if (readCount.incrementAndGet() == 2) secondReadStarted.countDown()
            return stored.get()
        }

        override fun getEntityFlow(id: String): Flow<UserReadingDataEntity?> = flowOf(stored.get())

        override suspend fun getAll(): List<UserReadingDataEntity> = listOf(stored.get())

        override suspend fun deleteByIds(ids: List<String>) = Unit

        override suspend fun clear() = Unit
    }

    companion object {
        private const val BOOK_ID = "book"
        private const val CHAPTER_ID = "chapter"

        @Suppress("UNCHECKED_CAST")
        private fun <T> unusedDao(type: Class<T>): T = Proxy.newProxyInstance(
            type.classLoader,
            arrayOf(type)
        ) { _, method, _ ->
            throw AssertionError("Unexpected ${method.name} call")
        } as T
    }
}
