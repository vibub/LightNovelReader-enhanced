package indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.sync

import indi.dmzz_yyhyy.lightnovelreader.data.local.room.dao.LinovelibChapterBookmarkDao
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.entity.LinovelibChapterBookmarkEntity
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.LinovelibConstants
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LinovelibBookmarkRepository @Inject constructor(
    private val bookmarkDao: LinovelibChapterBookmarkDao
) {
    fun getBookmarkFlow(bookId: String): Flow<LinovelibChapterBookmarkEntity?> =
        bookmarkDao.getFlow(bookId).map { it?.toLocalBookmark() }

    suspend fun upsertRemoteBookmark(
        bookId: String,
        chapterId: String,
        chapterTitle: String,
        resolved: Boolean
    ) {
        val now = LocalDateTime.now()
        bookmarkDao.upsert(
            LinovelibChapterBookmarkEntity(
                bookId = bookId,
                chapterId = chapterId.toLocalBookmarkChapterId(),
                chapterTitle = chapterTitle,
                updatedAt = now,
                remoteUpdatedAt = now,
                syncState = if (resolved) LinovelibBookmarkSyncState.Synced.value else LinovelibBookmarkSyncState.Unresolved.value
            )
        )
    }

    suspend fun matchRemoteBookmarkManually(
        bookId: String,
        chapterId: String,
        chapterTitle: String
    ) {
        val now = LocalDateTime.now()
        val local = bookmarkDao.get(bookId) ?: return
        bookmarkDao.upsert(
            local.copy(
                chapterId = chapterId.toLocalBookmarkChapterId(),
                chapterTitle = chapterTitle,
                updatedAt = now,
                remoteUpdatedAt = local.remoteUpdatedAt ?: now,
                syncState = LinovelibBookmarkSyncState.Synced.value
            )
        )
    }

}

private fun LinovelibChapterBookmarkEntity.toLocalBookmark(): LinovelibChapterBookmarkEntity =
    copy(chapterId = chapterId.toLocalBookmarkChapterId())

private fun String.toLocalBookmarkChapterId(): String =
    LinovelibConstants.run { this@toLocalBookmarkChapterId.normalizeChapterId().substringBefore('_') }

enum class LinovelibBookmarkSyncState(val value: String) {
    Synced("SYNCED"),
    Unresolved("UNRESOLVED")
}
