package indi.dmzz_yyhyy.lightnovelreader.data.download

import indi.dmzz_yyhyy.lightnovelreader.data.local.room.dao.DownloadTaskDao
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.entity.DownloadTaskEntity
import javax.inject.Inject
import javax.inject.Singleton

enum class DownloadTaskStatus {
    RUNNING,
    PAUSED,
    FAILED,
    COMPLETED
}

@Singleton
class DownloadTaskRepository @Inject constructor(
    private val downloadTaskDao: DownloadTaskDao
) {
    suspend fun get(sourceId: Int, bookId: String): DownloadTaskEntity? =
        downloadTaskDao.get(sourceId, bookId)

    suspend fun getAll(): List<DownloadTaskEntity> = downloadTaskDao.getAll()

    suspend fun markRunning(
        sourceId: Int,
        bookId: String,
        progress: Float? = null,
        total: Int? = null,
        processed: Int? = null,
        sourceKey: String = "",
        queueAll: Boolean? = null,
        constraintsKey: String? = null,
        estimatedBytes: Long? = null,
        writtenBytes: Long? = null,
        currentChapterId: String? = null,
        currentChapterTitle: String? = null
    ) = upsert(
        sourceId = sourceId,
        bookId = bookId,
        sourceKey = sourceKey,
        queueAll = queueAll,
        constraintsKey = constraintsKey,
        state = DownloadTaskStatus.RUNNING,
        progress = progress,
        total = total,
        processed = processed,
        errorMessage = null,
        estimatedBytes = estimatedBytes,
        writtenBytes = writtenBytes,
        currentChapterId = currentChapterId,
        currentChapterTitle = currentChapterTitle,
        clearCurrentChapter = currentChapterId == null && currentChapterTitle == null,
        clearWaitingReason = true
    )

    suspend fun resume(sourceId: Int, bookId: String, sourceKey: String = "") {
        val old = downloadTaskDao.get(sourceId, bookId)
        markRunning(
            sourceId = sourceId,
            bookId = bookId,
            progress = old?.progress,
            total = old?.total,
            processed = old?.processed,
            sourceKey = sourceKey.ifBlank { old?.sourceKey.orEmpty() },
            queueAll = old?.queueAll,
            constraintsKey = old?.constraintsKey,
            estimatedBytes = old?.estimatedBytes,
            writtenBytes = old?.writtenBytes,
            currentChapterId = null,
            currentChapterTitle = null
        )
    }

    suspend fun markPaused(
        sourceId: Int,
        bookId: String,
        progress: Float? = null,
        total: Int? = null,
        processed: Int? = null,
        sourceKey: String = "",
        queueAll: Boolean? = null,
        waitingReason: String? = null,
        estimatedBytes: Long? = null,
        writtenBytes: Long? = null,
        currentChapterId: String? = null,
        currentChapterTitle: String? = null,
        clearWaitingReason: Boolean = false
    ) = upsert(
        sourceId = sourceId,
        bookId = bookId,
        sourceKey = sourceKey,
        queueAll = queueAll,
        state = DownloadTaskStatus.PAUSED,
        progress = progress,
        total = total,
        processed = processed,
        errorMessage = null,
        waitingReason = waitingReason,
        estimatedBytes = estimatedBytes,
        writtenBytes = writtenBytes,
        currentChapterId = currentChapterId,
        currentChapterTitle = currentChapterTitle,
        clearWaitingReason = clearWaitingReason
    )

    suspend fun markFailed(
        sourceId: Int,
        bookId: String,
        message: String?,
        progress: Float? = null,
        total: Int? = null,
        processed: Int? = null,
        sourceKey: String = "",
        estimatedBytes: Long? = null,
        writtenBytes: Long? = null,
        currentChapterId: String? = null,
        currentChapterTitle: String? = null
    ) = upsert(
        sourceId = sourceId,
        bookId = bookId,
        sourceKey = sourceKey,
        state = DownloadTaskStatus.FAILED,
        progress = progress,
        total = total,
        processed = processed,
        errorMessage = message,
        estimatedBytes = estimatedBytes,
        writtenBytes = writtenBytes,
        currentChapterId = currentChapterId,
        currentChapterTitle = currentChapterTitle,
        clearCurrentChapter = true,
        clearWaitingReason = true
    )

    suspend fun updateProgress(
        sourceId: Int,
        bookId: String,
        progress: Float,
        total: Int,
        processed: Int,
        sourceKey: String = "",
        estimatedBytes: Long? = null,
        writtenBytes: Long? = null,
        currentChapterId: String? = null,
        currentChapterTitle: String? = null
    ) {
        val old = downloadTaskDao.get(sourceId, bookId)
        upsert(
            sourceId = sourceId,
            bookId = bookId,
            sourceKey = sourceKey,
            state = old?.state?.let(::parseStatus) ?: DownloadTaskStatus.RUNNING,
            progress = progress,
            total = total,
            processed = processed,
            errorMessage = old?.errorMessage,
            preserveErrorMessage = true,
            estimatedBytes = estimatedBytes,
            writtenBytes = writtenBytes,
            currentChapterId = currentChapterId,
            currentChapterTitle = currentChapterTitle,
            clearCurrentChapter = currentChapterTitle == null
        )
    }

    /** 更新任务状态，同时保留已经持久化的章节计数和空间统计。 */
    suspend fun updateItemState(
        sourceId: Int,
        bookId: String,
        state: DownloadTaskStatus,
        progress: Float,
        errorMessage: String? = null,
        sourceKey: String = "",
        estimatedBytes: Long? = null,
        writtenBytes: Long? = null,
        currentChapterTitle: String? = null,
        waitingReason: String? = null
    ) {
        val old = downloadTaskDao.get(sourceId, bookId)
        upsert(
            sourceId = sourceId,
            bookId = bookId,
            sourceKey = sourceKey,
            state = state,
            progress = progress,
            total = old?.total,
            processed = old?.processed,
            errorMessage = errorMessage,
            preserveErrorMessage = state == DownloadTaskStatus.PAUSED ||
                state == DownloadTaskStatus.FAILED,
            estimatedBytes = estimatedBytes ?: old?.estimatedBytes,
            writtenBytes = writtenBytes ?: old?.writtenBytes,
            currentChapterId = old?.currentChapterId,
            currentChapterTitle = currentChapterTitle ?: old?.currentChapterTitle,
            waitingReason = waitingReason ?: old?.waitingReason,
            clearCurrentChapter = state == DownloadTaskStatus.COMPLETED ||
                state == DownloadTaskStatus.FAILED ||
                currentChapterTitle == null,
            clearWaitingReason = state != DownloadTaskStatus.PAUSED
        )
    }

    suspend fun markCompleted(
        sourceId: Int,
        bookId: String,
        progress: Float? = 1f,
        total: Int? = null,
        processed: Int? = null,
        sourceKey: String = "",
        estimatedBytes: Long? = null,
        writtenBytes: Long? = null
    ) = upsert(
        sourceId = sourceId,
        bookId = bookId,
        sourceKey = sourceKey,
        state = DownloadTaskStatus.COMPLETED,
        progress = progress,
        total = total,
        processed = processed ?: total,
        errorMessage = null,
        estimatedBytes = estimatedBytes,
        writtenBytes = writtenBytes,
        clearCurrentChapter = true,
        clearWaitingReason = true
    )

    suspend fun clear(sourceId: Int, bookId: String) {
        downloadTaskDao.delete(sourceId, bookId)
    }

    private suspend fun upsert(
        sourceId: Int,
        bookId: String,
        sourceKey: String,
        state: DownloadTaskStatus,
        progress: Float?,
        total: Int?,
        processed: Int?,
        queueAll: Boolean? = null,
        constraintsKey: String? = null,
        errorMessage: String?,
        preserveErrorMessage: Boolean = false,
        estimatedBytes: Long? = null,
        writtenBytes: Long? = null,
        currentChapterId: String? = null,
        currentChapterTitle: String? = null,
        waitingReason: String? = null,
        clearCurrentChapter: Boolean = false,
        clearWaitingReason: Boolean = false
    ) {
        val old = downloadTaskDao.get(sourceId, bookId)
        val resolvedTotal = (total ?: old?.total ?: 0).coerceAtLeast(0)
        val resolvedProcessed = (processed ?: old?.processed ?: 0)
            .coerceAtLeast(0)
            .coerceAtMost(resolvedTotal)
        downloadTaskDao.upsert(
            DownloadTaskEntity(
                sourceId = sourceId,
                bookId = bookId,
                sourceKey = sourceKey.ifBlank { old?.sourceKey.orEmpty() },
                queueAll = queueAll ?: old?.queueAll ?: false,
                constraintsKey = constraintsKey?.ifBlank { null }
                    ?: old?.constraintsKey.orEmpty(),
                state = state.name,
                progress = (progress ?: old?.progress ?: 0f).coerceIn(0f, 1f),
                total = resolvedTotal,
                processed = resolvedProcessed,
                currentChapterId = if (clearCurrentChapter) null
                else currentChapterId ?: old?.currentChapterId,
                currentChapterTitle = if (clearCurrentChapter) null
                else currentChapterTitle ?: old?.currentChapterTitle,
                estimatedBytes = (estimatedBytes ?: old?.estimatedBytes ?: 0L).coerceAtLeast(0L),
                writtenBytes = (writtenBytes ?: old?.writtenBytes ?: 0L).coerceAtLeast(0L),
                waitingReason = if (clearWaitingReason) null
                else waitingReason ?: old?.waitingReason,
                errorMessage = if (preserveErrorMessage) errorMessage ?: old?.errorMessage
                else errorMessage
            )
        )
    }

    private fun parseStatus(value: String): DownloadTaskStatus = runCatching {
        DownloadTaskStatus.valueOf(value)
    }.getOrDefault(DownloadTaskStatus.RUNNING)
}
