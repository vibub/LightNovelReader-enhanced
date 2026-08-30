package indi.dmzz_yyhyy.lightnovelreader.data.download

import indi.dmzz_yyhyy.lightnovelreader.data.local.room.dao.ChapterContentDao
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.dao.ChapterDownloadDao
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.entity.ChapterDownloadEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChapterDownloadRepository @Inject constructor(
    private val chapterDownloadDao: ChapterDownloadDao,
    private val chapterContentDao: ChapterContentDao
) {
    fun getStatesFlow(sourceId: Int, bookId: String): Flow<Map<String, ChapterDownloadState>> =
        combine(
            chapterDownloadDao.getByBookFlow(sourceId, bookId),
            chapterContentDao.getIdsFlow(sourceId, bookId)
        ) { entities, cachedIds ->
            val cachedIdSet = cachedIds.toSet()
            entities.associate { entity ->
                entity.chapterId to entity.toState(cachedIdSet)
            }
        }

    suspend fun getStates(sourceId: Int, bookId: String): Map<String, ChapterDownloadState> {
        val cachedIds = chapterContentDao.getIds(sourceId, bookId).toSet()
        return chapterDownloadDao.getByBook(sourceId, bookId).associate { entity ->
            entity.chapterId to entity.toState(cachedIds)
        }
    }

    /**
     * 将旧版使用 legacy 主键保存的章节正文迁移到当前数据源和书籍。
     * 旧版正文可能只是网络阅读缓存，迁移时不能创建下载完成状态。
     * 迁移后删除 legacy 行，避免不同数据源再次共享同一份章节正文。
     */
    suspend fun migrateLegacyCachedChapters(
        sourceId: Int,
        bookId: String,
        chapterIds: List<String>
    ) {
        chapterContentDao.migrateLegacyCachedChapterIds(
            sourceId = sourceId,
            bookId = bookId,
            chapterIds = chapterIds
        )
    }

    suspend fun queue(
        sourceId: Int,
        bookId: String,
        chapterIds: List<String>,
        forceRefresh: Boolean = false
    ) {
        val ids = chapterIds.map(String::trim).filter(String::isNotBlank).distinct()
        if (ids.isEmpty()) return
        val existing = chapterDownloadDao.getByBook(sourceId, bookId).associateBy { it.chapterId }
        val cachedIds = chapterContentDao.getIds(sourceId, bookId).toSet()
        val now = System.currentTimeMillis()
        chapterDownloadDao.upsertAll(
            ids.mapNotNull { chapterId ->
                val old = existing[chapterId]
                if (!forceRefresh &&
                    old?.status == ChapterDownloadStatus.COMPLETED.name &&
                    chapterId in cachedIds
                ) {
                    null
                } else {
                    ChapterDownloadEntity(
                        sourceId = sourceId,
                        bookId = bookId,
                        chapterId = chapterId,
                        status = ChapterDownloadStatus.QUEUED.name,
                        errorMessage = null,
                        updatedAt = now
                    )
                }
            }
        )
    }

    suspend fun resetDownloading(sourceId: Int, bookId: String) {
        chapterDownloadDao.resetDownloading(sourceId, bookId, System.currentTimeMillis())
    }

    suspend fun pause(sourceId: Int, bookId: String) {
        resetDownloading(sourceId, bookId)
    }

    suspend fun getQueuedChapterIds(sourceId: Int, bookId: String): List<String> =
        chapterDownloadDao.getQueuedChapterIds(sourceId, bookId)

    suspend fun isDownloadRequested(sourceId: Int, bookId: String, chapterId: String): Boolean =
        chapterDownloadDao.getStatus(sourceId, bookId, chapterId) in setOf(
            ChapterDownloadStatus.QUEUED.name,
            ChapterDownloadStatus.DOWNLOADING.name
        )

    suspend fun markDownloading(sourceId: Int, bookId: String, chapterId: String) {
        upsert(sourceId, bookId, chapterId, ChapterDownloadStatus.DOWNLOADING)
    }

    suspend fun markCompleted(sourceId: Int, bookId: String, chapterId: String) {
        upsert(sourceId, bookId, chapterId, ChapterDownloadStatus.COMPLETED)
    }

    suspend fun markPartial(sourceId: Int, bookId: String, chapterId: String, message: String?) {
        upsert(sourceId, bookId, chapterId, ChapterDownloadStatus.PARTIAL, message)
    }

    suspend fun markFailed(sourceId: Int, bookId: String, chapterId: String, message: String?) {
        upsert(sourceId, bookId, chapterId, ChapterDownloadStatus.FAILED, message)
    }

    suspend fun isOfflineReady(sourceId: Int, bookId: String, chapterId: String): Boolean {
        val entity = chapterDownloadDao.getByBook(sourceId, bookId)
            .firstOrNull { it.chapterId == chapterId }
            ?: return false
        if (entity.status != ChapterDownloadStatus.COMPLETED.name &&
            entity.status != ChapterDownloadStatus.PARTIAL.name
        ) return false
        return chapterContentDao.getIds(sourceId, bookId).contains(chapterId)
    }

    suspend fun isBookFullyDownloaded(sourceId: Int, bookId: String, chapterIds: List<String>): Boolean {
        if (chapterIds.isEmpty()) return false
        val states = chapterDownloadDao.getByBook(sourceId, bookId).associateBy { it.chapterId }
        val cachedIds = chapterContentDao.getIds(sourceId, bookId).toSet()
        return chapterIds.distinct().all { chapterId ->
            chapterId in cachedIds &&
                states[chapterId]?.status == ChapterDownloadStatus.COMPLETED.name
        }
    }

    suspend fun clearPending(sourceId: Int, bookId: String) {
        chapterDownloadDao.deletePending(sourceId, bookId)
    }

    suspend fun clearChapters(sourceId: Int, bookId: String, chapterIds: List<String>) {
        if (chapterIds.isNotEmpty()) {
            chapterDownloadDao.deleteByChapterIds(sourceId, bookId, chapterIds)
        }
    }

    suspend fun clearBook(sourceId: Int, bookId: String) {
        chapterDownloadDao.deleteByBook(sourceId, bookId)
    }

    private suspend fun upsert(
        sourceId: Int,
        bookId: String,
        chapterId: String,
        status: ChapterDownloadStatus,
        errorMessage: String? = null
    ) {
        chapterDownloadDao.upsert(
            ChapterDownloadEntity(
                sourceId = sourceId,
                bookId = bookId,
                chapterId = chapterId,
                status = status.name,
                errorMessage = errorMessage,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    private fun ChapterDownloadEntity.toState(cachedIds: Set<String>): ChapterDownloadState {
        val parsedStatus = runCatching { ChapterDownloadStatus.valueOf(status) }
            .getOrDefault(ChapterDownloadStatus.NOT_DOWNLOADED)
        val actualStatus = if (
            parsedStatus == ChapterDownloadStatus.COMPLETED ||
            parsedStatus == ChapterDownloadStatus.PARTIAL
        ) {
            parsedStatus.takeIf { chapterId in cachedIds }
                ?: ChapterDownloadStatus.NOT_DOWNLOADED
        } else {
            parsedStatus
        }
        return ChapterDownloadState(actualStatus, errorMessage)
    }
}
