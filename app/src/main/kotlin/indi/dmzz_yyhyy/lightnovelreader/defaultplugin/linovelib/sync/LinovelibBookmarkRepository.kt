package indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.sync

import indi.dmzz_yyhyy.lightnovelreader.data.local.room.dao.LinovelibChapterBookmarkDao
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.entity.LinovelibChapterBookmarkEntity
import kotlinx.coroutines.flow.Flow
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LinovelibBookmarkRepository @Inject constructor(
    private val bookmarkDao: LinovelibChapterBookmarkDao
) {
    fun getBookmarkFlow(bookId: String): Flow<LinovelibChapterBookmarkEntity?> =
        bookmarkDao.getFlow(bookId)

    fun getBookmark(bookId: String): LinovelibChapterBookmarkEntity? =
        bookmarkDao.get(bookId)

    fun upsertLocalBookmark(
        bookId: String,
        chapterId: String,
        chapterTitle: String
    ) {
        val now = LocalDateTime.now()
        bookmarkDao.upsert(
            LinovelibChapterBookmarkEntity(
                bookId = bookId,
                chapterId = chapterId,
                chapterTitle = chapterTitle,
                updatedAt = now,
                remoteUpdatedAt = bookmarkDao.get(bookId)?.remoteUpdatedAt,
                syncState = LinovelibBookmarkSyncState.Pending.value
            )
        )
    }

    fun upsertRemoteBookmark(
        bookId: String,
        chapterId: String,
        chapterTitle: String,
        resolved: Boolean
    ) {
        val now = LocalDateTime.now()
        bookmarkDao.upsert(
            LinovelibChapterBookmarkEntity(
                bookId = bookId,
                chapterId = chapterId,
                chapterTitle = chapterTitle,
                updatedAt = now,
                remoteUpdatedAt = now,
                syncState = if (resolved) LinovelibBookmarkSyncState.Synced.value else LinovelibBookmarkSyncState.Unresolved.value
            )
        )
    }

    fun matchRemoteBookmarkManually(
        bookId: String,
        chapterId: String,
        chapterTitle: String
    ) {
        val now = LocalDateTime.now()
        val local = bookmarkDao.get(bookId) ?: return
        bookmarkDao.upsert(
            local.copy(
                chapterId = chapterId,
                chapterTitle = chapterTitle,
                updatedAt = now,
                remoteUpdatedAt = local.remoteUpdatedAt ?: now,
                syncState = LinovelibBookmarkSyncState.Synced.value
            )
        )
    }

    fun markSynced(bookId: String) {
        val local = bookmarkDao.get(bookId) ?: return
        val now = LocalDateTime.now()
        bookmarkDao.upsert(
            local.copy(
                updatedAt = now,
                remoteUpdatedAt = now,
                syncState = LinovelibBookmarkSyncState.Synced.value
            )
        )
    }

    fun markFailed(bookId: String) {
        val local = bookmarkDao.get(bookId) ?: return
        bookmarkDao.upsert(
            local.copy(
                updatedAt = LocalDateTime.now(),
                syncState = LinovelibBookmarkSyncState.Failed.value
            )
        )
    }
}

enum class LinovelibBookmarkSyncState(val value: String) {
    Synced("SYNCED"),
    Pending("PENDING"),
    Failed("FAILED"),
    Unresolved("UNRESOLVED")
}
