package indi.dmzz_yyhyy.lightnovelreader.data.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.github.michaelbull.result.onErr
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import indi.dmzz_yyhyy.lightnovelreader.R
import indi.dmzz_yyhyy.lightnovelreader.data.book.BookRepository
import indi.dmzz_yyhyy.lightnovelreader.data.book.isUsableBookVolumes
import indi.dmzz_yyhyy.lightnovelreader.data.book.isUsableChapterContent
import indi.dmzz_yyhyy.lightnovelreader.data.download.ChapterDownloadRepository
import indi.dmzz_yyhyy.lightnovelreader.data.download.DownloadProgressRepository
import indi.dmzz_yyhyy.lightnovelreader.data.download.DownloadItemState
import indi.dmzz_yyhyy.lightnovelreader.data.download.DownloadSettingsRepository
import indi.dmzz_yyhyy.lightnovelreader.data.download.DownloadStorageManager
import indi.dmzz_yyhyy.lightnovelreader.data.download.DownloadTaskRepository
import indi.dmzz_yyhyy.lightnovelreader.data.download.DownloadTaskStatus
import indi.dmzz_yyhyy.lightnovelreader.data.download.DownloadType
import indi.dmzz_yyhyy.lightnovelreader.data.download.MutableDownloadItem
import indi.dmzz_yyhyy.lightnovelreader.data.local.LocalBookDataSource
import indi.dmzz_yyhyy.lightnovelreader.data.local.OfflineContentCache
import indi.dmzz_yyhyy.lightnovelreader.data.web.WebBookDataSourceManager
import indi.dmzz_yyhyy.lightnovelreader.data.web.WebBookDataSourceProvider
import indi.dmzz_yyhyy.lightnovelreader.data.web.proxy.PriorityWebBookDataSource
import indi.dmzz_yyhyy.lightnovelreader.utils.convertOldId
import indi.dmzz_yyhyy.lightnovelreader.utils.toLegacyCompatibleSourceId
import io.nightfish.lightnovelreader.api.book.BookVolumes
import io.nightfish.lightnovelreader.api.book.ChapterContent
import io.nightfish.lightnovelreader.api.web.WebDataSourcePriority
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Duration.Companion.milliseconds

@HiltWorker
class CacheBookWork @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val localBookDataSource: LocalBookDataSource,
    private val webBookDataSourceProvider: WebBookDataSourceProvider,
    private val chapterDownloadRepository: ChapterDownloadRepository,
    private val offlineContentCache: OfflineContentCache,
    private val downloadProgressRepository: DownloadProgressRepository,
    private val downloadTaskRepository: DownloadTaskRepository,
    private val downloadSettingsRepository: DownloadSettingsRepository,
    private val downloadStorageManager: DownloadStorageManager,
    private val webBookDataSourceManager: WebBookDataSourceManager,
    private val bookRepository: BookRepository
) : CoroutineWorker(appContext, workerParams) {
    companion object {
        private const val TAG = "CacheBookWork"
        private const val CHANNEL_ID = "BookCache"
        private const val MAX_CHAPTER_ATTEMPTS = 3
        private const val NOTIFICATION_ID_OFFSET = 0x4c4e5200
        private const val DEFAULT_ESTIMATED_BYTES_PER_CHAPTER = 512L * 1024L
        private const val MIN_ESTIMATED_BYTES_PER_CHAPTER = 64L * 1024L
        private const val COVER_ESTIMATE_BYTES = 1L * 1024L * 1024L

        private fun saturatedAdd(first: Long, second: Long): Long =
            if (Long.MAX_VALUE - first < second) Long.MAX_VALUE else first + second

        fun ofId(id: String): String = "cache:$id"

        fun ofId(sourceId: Int, bookId: String): String = "cache:$sourceId:$bookId"
    }

    private data class ChapterFetchResult(
        val content: ChapterContent?,
        val errorMessage: String
    )

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val bookId = inputData.getString("bookId")?.trim().orEmpty()
        if (bookId.isBlank()) return@withContext Result.failure()

        try {
        val currentDataSource = webBookDataSourceProvider.value
        val requestedSource = inputData.getString("sourceKey")
            ?.takeIf(String::isNotBlank)
            ?.convertOldId()
        val expectedSourceId = inputData.getInt("sourceId", Int.MIN_VALUE)
        val webBookDataSource = when {
            requestedSource == null || requestedSource == currentDataSource.id -> currentDataSource
            else -> webBookDataSourceManager.getWebDataSourceProvider(requestedSource)
        }
        if (webBookDataSource == null) {
            val missingSourceId = expectedSourceId.takeIf { it != Int.MIN_VALUE }
                ?: requestedSource?.toLegacyCompatibleSourceId()
                ?: currentDataSource.id.toLegacyCompatibleSourceId()
            downloadTaskRepository.markFailed(
                sourceId = missingSourceId,
                bookId = bookId,
                message = "数据源不可用，请重新安装插件或切换回原数据源后重试",
                sourceKey = requestedSource?.toString().orEmpty()
            )
            return@withContext Result.failure(
                workDataOf("bookId" to bookId, "error" to "数据源不可用")
            )
        }
        val sourceKey = webBookDataSource.id.toString()
        val sourceId = webBookDataSource.id.toLegacyCompatibleSourceId()
        if (expectedSourceId != Int.MIN_VALUE && expectedSourceId != sourceId) {
            downloadTaskRepository.markFailed(
                sourceId = expectedSourceId,
                bookId = bookId,
                message = "数据源已变更，请切换回原数据源后重试",
                sourceKey = requestedSource?.toString().orEmpty()
            )
            return@withContext Result.failure()
        }
        if (downloadTaskRepository.get(sourceId, bookId)?.state ==
            DownloadTaskStatus.PAUSED.name
        ) {
            return@withContext Result.success()
        }
        val bookVolumes = loadBookVolumes(bookId, webBookDataSource)
            ?: return@withContext Result.retry()
        if (!isUsableBookVolumes(bookVolumes)) {
            downloadTaskRepository.markFailed(
                sourceId = sourceId,
                bookId = bookId,
                message = "数据源返回了无效目录",
                sourceKey = sourceKey
            )
            return@withContext Result.failure()
        }

        localBookDataSource.updateBookVolumes(sourceId, bookVolumes)
        val orderedChapters = bookVolumes.volumes.flatMap { it.chapters }
        val orderedChapterIds = orderedChapters.map { it.id }
        chapterDownloadRepository.migrateLegacyCachedChapters(
            sourceId = sourceId,
            bookId = bookId,
            chapterIds = orderedChapterIds
        )
        updateCachedChapterNavigation(sourceId, bookId, orderedChapters)
        chapterDownloadRepository.resetDownloading(sourceId, bookId)
        if (downloadTaskRepository.get(sourceId, bookId)?.state == DownloadTaskStatus.PAUSED.name) {
            return@withContext Result.success()
        }

        var queuedChapterIds = chapterDownloadRepository.getQueuedChapterIds(sourceId, bookId)
        if (queuedChapterIds.isEmpty() && inputData.getBoolean("queueAll", false)) {
            chapterDownloadRepository.queue(sourceId, bookId, orderedChapterIds)
            queuedChapterIds = chapterDownloadRepository.getQueuedChapterIds(sourceId, bookId)
        }
        val queuedSet = queuedChapterIds.toSet()
        val chaptersToDownload = orderedChapters.filter { it.id in queuedSet }
        if (chaptersToDownload.isEmpty()) {
            if (!inputData.getBoolean("queueAll", false) &&
                downloadTaskRepository.get(sourceId, bookId) == null
            ) {
                return@withContext Result.success()
            }
            if (shouldStopTask(sourceId, bookId)) return@withContext Result.success()
            cacheBookInformation(bookId, webBookDataSource)
            downloadTaskRepository.markCompleted(
                sourceId = sourceId,
                bookId = bookId,
                sourceKey = sourceKey
            )
            return@withContext Result.success()
        }

        val settings = downloadSettingsRepository.get()
        val cachedChapterBytes = chaptersToDownload.map { chapter ->
            val contentBytes = localBookDataSource
                .getChapterContent(sourceId, bookId, chapter.id)
                ?.content
                ?.toString()
                ?.encodeToByteArray()
                ?.size
                ?.toLong()
                ?: 0L
            val imageBytes = offlineContentCache.chapterImageBytes(
                sourceId = sourceId,
                bookId = bookId,
                chapterId = chapter.id
            )
            saturatedAdd(contentBytes, imageBytes)
        }
        val knownChapterBytes = cachedChapterBytes.filter { it > 0L }
        val averageChapterBytes = if (knownChapterBytes.isEmpty()) {
            DEFAULT_ESTIMATED_BYTES_PER_CHAPTER
        } else {
            (knownChapterBytes.sum() / knownChapterBytes.size)
                .coerceAtLeast(MIN_ESTIMATED_BYTES_PER_CHAPTER)
        }
        val estimatedPayloadBytes = cachedChapterBytes.fold(0L) { total, bytes ->
            saturatedAdd(total, if (bytes > 0L) bytes else averageChapterBytes)
        }
        // 原子替换章节图片时临时目录会与旧目录同时存在，保留旧图片大小作为峰值余量。
        val replacementBytes = chaptersToDownload.sumOf { chapter ->
            offlineContentCache.chapterImageBytes(sourceId, bookId, chapter.id)
        }
        val estimatedRequiredBytes = DownloadStorageManager.requiredBytes(
            chapterCount = 1,
            minimumFreeStorageBytes = settings.minimumFreeStorageBytes,
            estimatedBytesPerChapter = saturatedAdd(
                saturatedAdd(estimatedPayloadBytes, replacementBytes),
                COVER_ESTIMATE_BYTES
            ).coerceAtLeast(1L)
        )
        val downloadItem = MutableDownloadItem(
            type = DownloadType.CACHE,
            bookId = bookId,
            bookInformationFlow = bookRepository.getBookInformationFlowForSource(
                id = bookId,
                sourceKey = sourceKey,
                sourceId = sourceId
            ),
            sourceId = sourceId,
            sourceKey = sourceKey
        ).apply {
            this.estimatedBytes = estimatedRequiredBytes
        }
        runCatching {
            downloadStorageManager.requireEnoughSpace(estimatedRequiredBytes)
        }.onFailure { throwable ->
            val waitingReason = throwable.message ?: "存储空间不足"
            downloadTaskRepository.markPaused(
                sourceId = sourceId,
                bookId = bookId,
                total = chaptersToDownload.size,
                sourceKey = sourceKey,
                waitingReason = waitingReason,
                estimatedBytes = estimatedRequiredBytes
            )
            downloadItem.waitingReason = waitingReason
            downloadItem.state = DownloadItemState.PAUSED
            downloadProgressRepository.addExportItem(downloadItem)
        }.getOrElse {
            return@withContext Result.failure(
                workDataOf(
                    "bookId" to bookId,
                    "error" to (it.message ?: "存储空间不足")
                )
            )
        }

        downloadTaskRepository.markRunning(
            sourceId = sourceId,
            bookId = bookId,
            total = chaptersToDownload.size,
            processed = 0,
            sourceKey = sourceKey,
            constraintsKey = settings.constraintsKey,
            estimatedBytes = estimatedRequiredBytes,
            writtenBytes = 0L
        )
        if (shouldStopTask(sourceId, bookId)) return@withContext Result.success()
        createNotificationChannel()
        val notificationId = notificationId(sourceId, bookId)
        downloadProgressRepository.addExportItem(downloadItem)
        setProgress(
            workDataOf(
                "bookId" to bookId,
                "total" to chaptersToDownload.size,
                "completed" to 0,
                "failed" to 0
            )
        )
        setForeground(createForegroundInfo(0, chaptersToDownload.size, null, notificationId))

        var completedCount = 0
        var partialCount = 0
        var failedCount = 0
        var skippedCount = 0
        var lastErrorMessage: String? = null
        var writtenBytes = 0L
        val navigationByChapterId = orderedChapters.mapIndexed { index, chapter ->
            chapter.id to (
                orderedChapters.getOrNull(index - 1)?.id to
                    orderedChapters.getOrNull(index + 1)?.id
                )
        }.toMap()

        for ((chapterIndex, chapterInformation) in chaptersToDownload.withIndex()) {
            currentCoroutineContext().ensureActive()
            if (shouldStopTask(sourceId, bookId)) return@withContext Result.success()
            if (!chapterDownloadRepository.isDownloadRequested(sourceId, bookId, chapterInformation.id)) {
                skippedCount++
                updateProgress(
                    sourceId = sourceId,
                    bookId = bookId,
                    processed = completedCount + partialCount + failedCount + skippedCount,
                    total = chaptersToDownload.size,
                    failed = failedCount,
                    currentTitle = null,
                    downloadItem = downloadItem,
                    notificationId = notificationId,
                    sourceKey = sourceKey,
                    estimatedBytes = estimatedRequiredBytes,
                    writtenBytes = writtenBytes
                )
                continue
            }
            val remainingChapterEstimate = max(
                cachedChapterBytes.getOrElse(chapterIndex) { 0L },
                averageChapterBytes
            )
            val remainingStorageRequirement = saturatedAdd(
                settings.minimumFreeStorageBytes,
                remainingChapterEstimate
            )
            if (!downloadStorageManager.hasEnoughSpace(remainingStorageRequirement)) {
                chapterDownloadRepository.resetDownloading(sourceId, bookId)
                val message = "存储空间不足，已暂停缓存任务"
                val processed = completedCount + partialCount + failedCount + skippedCount
                val progress = if (chaptersToDownload.isEmpty()) 0f
                else processed.toFloat() / chaptersToDownload.size
                downloadTaskRepository.markPaused(
                    sourceId = sourceId,
                    bookId = bookId,
                    progress = progress,
                    total = chaptersToDownload.size,
                    processed = processed,
                    sourceKey = sourceKey,
                    waitingReason = message,
                    estimatedBytes = estimatedRequiredBytes,
                    writtenBytes = writtenBytes,
                    currentChapterId = chapterInformation.id,
                    currentChapterTitle = chapterInformation.title
                )
                downloadItem.progress = progress
                downloadItem.state = DownloadItemState.PAUSED
                return@withContext Result.failure(
                    workDataOf("bookId" to bookId, "error" to message)
                )
            }
            if (shouldStopTask(sourceId, bookId)) return@withContext Result.success()
            chapterDownloadRepository.markDownloading(sourceId, bookId, chapterInformation.id)
            downloadTaskRepository.updateProgress(
                sourceId = sourceId,
                bookId = bookId,
                progress = (completedCount + partialCount + failedCount + skippedCount)
                    .toFloat() / chaptersToDownload.size,
                total = chaptersToDownload.size,
                processed = completedCount + partialCount + failedCount + skippedCount,
                sourceKey = sourceKey,
                estimatedBytes = estimatedRequiredBytes,
                writtenBytes = writtenBytes,
                currentChapterId = chapterInformation.id,
                currentChapterTitle = chapterInformation.title
            )
            val fetchResult = fetchChapterContent(
                chapterId = chapterInformation.id,
                bookId = bookId,
                sourceId = sourceId,
                dataSource = webBookDataSource
            )
            if (shouldStopTask(sourceId, bookId)) return@withContext Result.success()
            if (!chapterDownloadRepository.isDownloadRequested(sourceId, bookId, chapterInformation.id)) {
                skippedCount++
                continue
            }
            val fetchedContent = fetchResult.content
            if (fetchedContent == null) {
                lastErrorMessage = fetchResult.errorMessage.takeIf(String::isNotBlank)
                val existing = localBookDataSource.getChapterContent(
                    sourceId,
                    bookId,
                    chapterInformation.id
                )
                if (existing != null && isUsableChapterContent(existing)) {
                    chapterDownloadRepository.markPartial(
                        sourceId,
                        bookId,
                        chapterInformation.id,
                        fetchResult.errorMessage
                    )
                    partialCount++
                } else {
                    chapterDownloadRepository.markFailed(
                        sourceId,
                        bookId,
                        chapterInformation.id,
                        fetchResult.errorMessage
                    )
                    failedCount++
                }
                updateProgress(
                    sourceId,
                    bookId,
                    completedCount + partialCount + failedCount + skippedCount,
                    chaptersToDownload.size,
                    failedCount,
                    currentTitle = chapterInformation.title,
                    downloadItem = downloadItem,
                    notificationId = notificationId,
                    sourceKey = sourceKey,
                    estimatedBytes = estimatedRequiredBytes,
                    writtenBytes = writtenBytes
                )
                continue
            }

            val (prevChapterId, nextChapterId) = navigationByChapterId[chapterInformation.id]
                ?: (null to null)
            val normalizedContent = fetchedContent.copy(
                prevChapter = prevChapterId,
                nextChapter = nextChapterId
            )
            if (shouldStopTask(sourceId, bookId)) return@withContext Result.success()
            val cacheResult = offlineContentCache.cacheChapterContent(
                sourceId = sourceId,
                bookId = bookId,
                chapterContent = normalizedContent,
                header = webBookDataSource.imageHeader
            )
            if (shouldStopTask(sourceId, bookId)) {
                offlineContentCache.deleteChapterImages(sourceId, bookId, chapterInformation.id)
                return@withContext Result.success()
            }
            if (!chapterDownloadRepository.isDownloadRequested(sourceId, bookId, chapterInformation.id)) {
                offlineContentCache.deleteChapterImages(sourceId, bookId, chapterInformation.id)
                skippedCount++
                continue
            }
            localBookDataSource.updateChapterContent(sourceId, bookId, cacheResult.content)
            writtenBytes += cacheResult.bytesWritten + cacheResult.content.toString().encodeToByteArray().size
            if (cacheResult.isComplete) {
                chapterDownloadRepository.markCompleted(sourceId, bookId, chapterInformation.id)
                completedCount++
            } else {
                val message = "图片下载失败 ${cacheResult.failedImageCount}/${cacheResult.imageCount}"
                lastErrorMessage = message
                chapterDownloadRepository.markPartial(
                    sourceId = sourceId,
                    bookId = bookId,
                    chapterId = chapterInformation.id,
                    message = message
                )
                partialCount++
            }
            updateProgress(
                sourceId = sourceId,
                bookId = bookId,
                processed = completedCount + partialCount + failedCount + skippedCount,
                total = chaptersToDownload.size,
                failed = failedCount,
                currentTitle = chapterInformation.title,
                downloadItem = downloadItem,
                notificationId = notificationId,
                sourceKey = sourceKey,
                estimatedBytes = estimatedRequiredBytes,
                writtenBytes = writtenBytes
            )
        }

        if (shouldStopTask(sourceId, bookId)) return@withContext Result.success()
        if (completedCount + partialCount + failedCount + skippedCount == 0) {
            return@withContext Result.success()
        }
        val processedCount = completedCount + partialCount + failedCount + skippedCount
        val progress = processedCount.toFloat() / chaptersToDownload.size
        val hasCancelledChapters = skippedCount > 0
        val isSuccessful = failedCount == 0 && partialCount == 0 && !hasCancelledChapters
        if (isSuccessful) {
            cacheBookInformation(bookId, webBookDataSource)
            downloadItem.progress = 1f
            downloadItem.estimatedBytes = estimatedRequiredBytes
            downloadItem.writtenBytes = writtenBytes
            downloadItem.currentChapterTitle = null
            downloadItem.waitingReason = null
            downloadItem.errorMessage = null
            downloadTaskRepository.markCompleted(
                sourceId = sourceId,
                bookId = bookId,
                total = chaptersToDownload.size,
                processed = processedCount,
                sourceKey = sourceKey,
                estimatedBytes = estimatedRequiredBytes,
                writtenBytes = writtenBytes
            )
        } else if (hasCancelledChapters && failedCount == 0 && partialCount == 0) {
            val message = "部分章节已取消，任务已暂停"
            downloadTaskRepository.markPaused(
                sourceId = sourceId,
                bookId = bookId,
                progress = progress,
                total = chaptersToDownload.size,
                processed = processedCount,
                sourceKey = sourceKey,
                waitingReason = message,
                estimatedBytes = estimatedRequiredBytes,
                writtenBytes = writtenBytes,
                clearWaitingReason = false
            )
            downloadItem.progress = progress.coerceAtMost(0.99f)
            downloadItem.estimatedBytes = estimatedRequiredBytes
            downloadItem.writtenBytes = writtenBytes
            downloadItem.currentChapterTitle = null
            downloadItem.waitingReason = message
            downloadItem.errorMessage = null
            downloadItem.state = DownloadItemState.PAUSED
        } else {
            cacheBookInformation(bookId, webBookDataSource)
            val message = buildString {
                append("部分章节需要重试")
                lastErrorMessage?.takeIf(String::isNotBlank)?.let {
                    append("：")
                    append(it)
                }
            }
            downloadItem.progress = -1f
            downloadItem.estimatedBytes = estimatedRequiredBytes
            downloadItem.writtenBytes = writtenBytes
            downloadItem.currentChapterTitle = null
            downloadItem.waitingReason = null
            downloadItem.errorMessage = message
            downloadTaskRepository.markFailed(
                sourceId = sourceId,
                bookId = bookId,
                message = message,
                progress = progress,
                total = chaptersToDownload.size,
                processed = processedCount,
                sourceKey = sourceKey,
                estimatedBytes = estimatedRequiredBytes,
                writtenBytes = writtenBytes
            )
        }
        setProgress(
            workDataOf(
                "bookId" to bookId,
                "total" to chaptersToDownload.size,
                "completed" to completedCount,
                "partial" to partialCount,
                "failed" to failedCount,
                "skipped" to skippedCount,
                "processed" to processedCount,
                "estimatedBytes" to estimatedRequiredBytes,
                "writtenBytes" to writtenBytes
            )
        )
        setForeground(
            createForegroundInfo(
                completed = processedCount,
                total = chaptersToDownload.size,
                currentTitle = null,
                notificationId = notificationId,
                finished = true,
                failed = failedCount > 0 || partialCount > 0,
                paused = hasCancelledChapters && failedCount == 0 && partialCount == 0
            )
        )
        if (failedCount > 0 || partialCount > 0) {
            Result.failure(workDataOf("failedCount" to failedCount, "partialCount" to partialCount))
        } else {
            Result.success(workDataOf("partialCount" to partialCount))
        }
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            val sourceId = inputData.getInt("sourceId", Int.MIN_VALUE)
                .takeIf { it != Int.MIN_VALUE }
                ?: webBookDataSourceProvider.value.id.toLegacyCompatibleSourceId()
            val sourceKey = inputData.getString("sourceKey").orEmpty()
            runCatching {
                downloadTaskRepository.markFailed(
                    sourceId = sourceId,
                    bookId = bookId,
                    message = throwable.message ?: "缓存任务执行失败",
                    sourceKey = sourceKey
                )
            }.onFailure { markError ->
                Log.e(TAG, "无法记录缓存任务失败状态", markError)
            }
            Log.e(TAG, "缓存任务执行失败：bookId=$bookId", throwable)
            Result.failure(
                workDataOf("bookId" to bookId, "error" to (throwable.message ?: "缓存任务执行失败"))
            )
        }
    }

    private suspend fun loadBookVolumes(
        bookId: String,
        dataSource: PriorityWebBookDataSource
    ): BookVolumes? {
        val remote = try {
            dataSource.getBookVolumes(
                bookId,
                WebDataSourcePriority.Default
            )
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            null
        }
        remote?.component1()?.takeIf(::isUsableBookVolumes)?.let { return it }
        val sourceId = dataSource.id.toLegacyCompatibleSourceId()
        return localBookDataSource.getBookVolumes(sourceId, bookId)?.takeIf(::isUsableBookVolumes)
    }

    private suspend fun fetchChapterContent(
        chapterId: String,
        bookId: String,
        sourceId: Int,
        dataSource: PriorityWebBookDataSource
    ): ChapterFetchResult {
        var lastError = "无法获取章节内容"
        repeat(MAX_CHAPTER_ATTEMPTS) { attempt ->
            currentCoroutineContext().ensureActive()
            var errorMessage = lastError
            val result = try {
                dataSource.getChapterContent(
                    chapterId = chapterId,
                    bookId = bookId,
                    priority = WebDataSourcePriority.Default
                )
            } catch (throwable: Throwable) {
                if (throwable is CancellationException) throw throwable
                errorMessage = throwable.message ?: throwable::class.simpleName.orEmpty()
                null
            }
            if (result != null) {
                result.onErr { errorMessage = it.message }
                val content = result.component1()
                if (content != null && isUsableChapterContent(content)) {
                    return ChapterFetchResult(content, "")
                }
                if (content != null) errorMessage = "数据源返回了无效章节内容"
            }
            lastError = errorMessage
            if (attempt + 1 < MAX_CHAPTER_ATTEMPTS) {
                if (shouldStopTask(sourceId, bookId)) {
                    return ChapterFetchResult(null, "下载任务已暂停")
                }
                delay(min(8_000L, 1_000L shl attempt).milliseconds)
            }
        }
        return ChapterFetchResult(null, lastError)
    }

    private suspend fun updateCachedChapterNavigation(
        sourceId: Int,
        bookId: String,
        orderedChapters: List<io.nightfish.lightnovelreader.api.book.ChapterInformation>
    ) {
        orderedChapters.forEachIndexed { index, chapterInformation ->
            val cached = localBookDataSource.getExactChapterContent(
                sourceId,
                bookId,
                chapterInformation.id
            ) ?: return@forEachIndexed
            val previousId = orderedChapters.getOrNull(index - 1)?.id
            val nextId = orderedChapters.getOrNull(index + 1)?.id
            if (cached.prevChapter != previousId || cached.nextChapter != nextId) {
                localBookDataSource.updateChapterContent(
                    sourceId,
                    bookId,
                    cached.copy(prevChapter = previousId, nextChapter = nextId)
                )
            }
        }
    }

    private suspend fun cacheBookInformation(
        bookId: String,
        dataSource: PriorityWebBookDataSource
    ) {
        val result = try {
            dataSource.getBookInformation(bookId)
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            null
        }
        val information = result?.component1() ?: return
        val cachedInformation = offlineContentCache.cacheBookInformation(
            sourceId = dataSource.id.toLegacyCompatibleSourceId(),
            information = information,
            header = dataSource.imageHeader
        )
        localBookDataSource.updateBookInformation(
            sourceId = dataSource.id.toLegacyCompatibleSourceId(),
            info = cachedInformation
        )
    }

    private suspend fun updateProgress(
        sourceId: Int,
        bookId: String,
        processed: Int,
        total: Int,
        failed: Int,
        currentTitle: String?,
        downloadItem: MutableDownloadItem,
        notificationId: Int,
        sourceKey: String,
        estimatedBytes: Long,
        writtenBytes: Long
    ) {
        val progress = if (total == 0) 1f else processed.toFloat() / total
        downloadItem.progress = progress.coerceAtMost(0.99f)
        downloadItem.estimatedBytes = estimatedBytes
        downloadItem.writtenBytes = writtenBytes
        downloadItem.currentChapterTitle = currentTitle
        downloadItem.waitingReason = null
        downloadTaskRepository.updateProgress(
            sourceId = sourceId,
            bookId = bookId,
            progress = progress,
            total = total,
            processed = processed,
            sourceKey = sourceKey,
            estimatedBytes = estimatedBytes,
            writtenBytes = writtenBytes,
            currentChapterId = null,
            currentChapterTitle = currentTitle
        )
        setProgress(
            workDataOf(
                "bookId" to bookId,
                "total" to total,
                "processed" to processed,
                "failed" to failed,
                "progress" to progress,
                "estimatedBytes" to estimatedBytes,
                "writtenBytes" to writtenBytes,
                "currentChapterTitle" to currentTitle
            )
        )
        setForeground(createForegroundInfo(processed, total, currentTitle, notificationId))
    }

    private suspend fun shouldStopTask(sourceId: Int, bookId: String): Boolean {
        currentCoroutineContext().ensureActive()
        return downloadTaskRepository.get(sourceId, bookId)?.state != DownloadTaskStatus.RUNNING.name
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "书籍缓存",
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    private fun createForegroundInfo(
        completed: Int,
        total: Int,
        currentTitle: String?,
        notificationId: Int,
        finished: Boolean = false,
        failed: Boolean = false,
        paused: Boolean = false
    ): ForegroundInfo {
        val text = when {
            paused -> "缓存已暂停"
            finished && failed -> "缓存完成，部分章节需要重试"
            finished -> "缓存完成"
            currentTitle.isNullOrBlank() -> "准备缓存章节"
            else -> "正在缓存：$currentTitle"
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.cloud_download_24px)
            .setContentTitle("书籍缓存")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(!finished && !paused)
            .setAutoCancel(finished || paused)
            .setProgress(total, completed, total == 0)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    private fun notificationId(sourceId: Int, bookId: String): Int =
        NOTIFICATION_ID_OFFSET + ((31 * sourceId + bookId.hashCode()) and 0x00ffffff)
}
