package indi.dmzz_yyhyy.lightnovelreader.data.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
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
import indi.dmzz_yyhyy.lightnovelreader.data.download.DownloadType
import indi.dmzz_yyhyy.lightnovelreader.data.download.MutableDownloadItem
import indi.dmzz_yyhyy.lightnovelreader.data.local.LocalBookDataSource
import indi.dmzz_yyhyy.lightnovelreader.data.local.OfflineContentCache
import indi.dmzz_yyhyy.lightnovelreader.data.web.WebBookDataSourceProvider
import indi.dmzz_yyhyy.lightnovelreader.data.web.proxy.PriorityWebBookDataSource
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
    private val bookRepository: BookRepository
) : CoroutineWorker(appContext, workerParams) {
    companion object {
        private const val CHANNEL_ID = "BookCache"
        private const val MAX_CHAPTER_ATTEMPTS = 3
        private const val NOTIFICATION_ID_OFFSET = 0x4c4e5200

        fun ofId(id: String): String = "cache:$id"
    }

    private data class ChapterFetchResult(
        val content: ChapterContent?,
        val errorMessage: String
    )

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val bookId = inputData.getString("bookId")?.trim().orEmpty()
        if (bookId.isBlank()) return@withContext Result.failure()

        val webBookDataSource = webBookDataSourceProvider.value
        val sourceId = webBookDataSource.id.toLegacyCompatibleSourceId()
        val bookVolumes = loadBookVolumes(bookId)
            ?: return@withContext Result.retry()
        if (!isUsableBookVolumes(bookVolumes)) return@withContext Result.failure()

        localBookDataSource.updateBookVolumes(bookVolumes)
        val orderedChapters = bookVolumes.volumes.flatMap { it.chapters }
        val orderedChapterIds = orderedChapters.map { it.id }
        chapterDownloadRepository.migrateLegacyCachedChapters(
            sourceId = sourceId,
            bookId = bookId,
            chapterIds = orderedChapterIds
        )
        updateCachedChapterNavigation(sourceId, bookId, orderedChapters)
        chapterDownloadRepository.resetDownloading(sourceId, bookId)

        var queuedChapterIds = chapterDownloadRepository.getQueuedChapterIds(sourceId, bookId)
        if (queuedChapterIds.isEmpty() && inputData.getBoolean("queueAll", false)) {
            chapterDownloadRepository.queue(sourceId, bookId, orderedChapterIds)
            queuedChapterIds = chapterDownloadRepository.getQueuedChapterIds(sourceId, bookId)
        }
        val queuedSet = queuedChapterIds.toSet()
        val chaptersToDownload = orderedChapters.filter { it.id in queuedSet }
        if (chaptersToDownload.isEmpty()) {
            cacheBookInformation(bookId, webBookDataSource.imageHeader)
            return@withContext Result.success()
        }

        createNotificationChannel()
        val notificationId = notificationId(bookId)
        val downloadItem = MutableDownloadItem(
            type = DownloadType.CACHE,
            bookId = bookId,
            bookInformationFlow = bookRepository.getBookInformationFlow(bookId)
        )
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
        val navigationByChapterId = orderedChapters.mapIndexed { index, chapter ->
            chapter.id to (
                orderedChapters.getOrNull(index - 1)?.id to
                    orderedChapters.getOrNull(index + 1)?.id
                )
        }.toMap()

        for (chapterInformation in chaptersToDownload) {
            currentCoroutineContext().ensureActive()
            if (!chapterDownloadRepository.isDownloadRequested(sourceId, bookId, chapterInformation.id)) {
                continue
            }
            chapterDownloadRepository.markDownloading(sourceId, bookId, chapterInformation.id)
            val fetchResult = fetchChapterContent(
                chapterId = chapterInformation.id,
                bookId = bookId,
                dataSource = webBookDataSource
            )
            if (!chapterDownloadRepository.isDownloadRequested(sourceId, bookId, chapterInformation.id)) {
                continue
            }
            val fetchedContent = fetchResult.content
            if (fetchedContent == null) {
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
                    bookId,
                    completedCount + partialCount + failedCount,
                    chaptersToDownload.size,
                    failedCount,
                    chapterInformation.title,
                    downloadItem,
                    notificationId
                )
                continue
            }

            val (prevChapterId, nextChapterId) = navigationByChapterId[chapterInformation.id]
                ?: (null to null)
            val normalizedContent = fetchedContent.copy(
                prevChapter = prevChapterId,
                nextChapter = nextChapterId
            )
            val cacheResult = offlineContentCache.cacheChapterContent(
                sourceId = sourceId,
                bookId = bookId,
                chapterContent = normalizedContent,
                header = webBookDataSource.imageHeader
            )
            if (!chapterDownloadRepository.isDownloadRequested(sourceId, bookId, chapterInformation.id)) {
                offlineContentCache.deleteChapterImages(sourceId, bookId, chapterInformation.id)
                continue
            }
            localBookDataSource.updateChapterContent(sourceId, bookId, cacheResult.content)
            if (cacheResult.isComplete) {
                chapterDownloadRepository.markCompleted(sourceId, bookId, chapterInformation.id)
                completedCount++
            } else {
                chapterDownloadRepository.markPartial(
                    sourceId = sourceId,
                    bookId = bookId,
                    chapterId = chapterInformation.id,
                    message = "图片下载失败 ${cacheResult.failedImageCount}/${cacheResult.imageCount}"
                )
                partialCount++
            }
            updateProgress(
                bookId,
                completedCount + partialCount + failedCount,
                chaptersToDownload.size,
                failedCount,
                chapterInformation.title,
                downloadItem,
                notificationId
            )
        }

        cacheBookInformation(bookId, webBookDataSource.imageHeader)
        val processedCount = completedCount + partialCount + failedCount
        downloadItem.progress = if (failedCount == 0 && partialCount == 0) 1f else -1f
        setProgress(
            workDataOf(
                "bookId" to bookId,
                "total" to chaptersToDownload.size,
                "completed" to completedCount,
                "partial" to partialCount,
                "failed" to failedCount,
                "processed" to processedCount
            )
        )
        setForeground(
            createForegroundInfo(
                completed = processedCount,
                total = chaptersToDownload.size,
                currentTitle = null,
                notificationId = notificationId,
                finished = true,
                failed = failedCount > 0 || partialCount > 0
            )
        )
        if (failedCount > 0) {
            Result.failure(workDataOf("failedCount" to failedCount, "partialCount" to partialCount))
        } else {
            Result.success(workDataOf("partialCount" to partialCount))
        }
    }

    private suspend fun loadBookVolumes(bookId: String): BookVolumes? {
        val remote = try {
            webBookDataSourceProvider.value.getBookVolumes(
                bookId,
                WebDataSourcePriority.Default
            )
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            null
        }
        remote?.component1()?.takeIf(::isUsableBookVolumes)?.let { return it }
        return localBookDataSource.getBookVolumes(bookId)?.takeIf(::isUsableBookVolumes)
    }

    private suspend fun fetchChapterContent(
        chapterId: String,
        bookId: String,
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

    private suspend fun cacheBookInformation(bookId: String, header: Map<String, String>) {
        val dataSource = webBookDataSourceProvider.value
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
            header = header
        )
        localBookDataSource.updateBookInformation(cachedInformation)
    }

    private suspend fun updateProgress(
        bookId: String,
        processed: Int,
        total: Int,
        failed: Int,
        currentTitle: String?,
        downloadItem: MutableDownloadItem,
        notificationId: Int
    ) {
        val progress = if (total == 0) 1f else processed.toFloat() / total
        downloadItem.progress = progress.coerceAtMost(0.99f)
        setProgress(
            workDataOf(
                "bookId" to bookId,
                "total" to total,
                "processed" to processed,
                "failed" to failed,
                "progress" to progress
            )
        )
        setForeground(createForegroundInfo(processed, total, currentTitle, notificationId))
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
        failed: Boolean = false
    ): ForegroundInfo {
        val text = when {
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
            .setOngoing(!finished)
            .setAutoCancel(finished)
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

    private fun notificationId(bookId: String): Int =
        NOTIFICATION_ID_OFFSET + (bookId.hashCode() and 0x00ffffff)
}
