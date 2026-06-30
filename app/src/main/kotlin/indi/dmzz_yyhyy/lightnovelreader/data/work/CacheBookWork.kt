package indi.dmzz_yyhyy.lightnovelreader.data.work

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import indi.dmzz_yyhyy.lightnovelreader.data.download.DownloadProgressRepository
import indi.dmzz_yyhyy.lightnovelreader.data.download.DownloadType
import indi.dmzz_yyhyy.lightnovelreader.data.download.MutableDownloadItem
import indi.dmzz_yyhyy.lightnovelreader.data.local.LocalBookDataSource
import indi.dmzz_yyhyy.lightnovelreader.data.web.WebBookDataSourceProvider

@HiltWorker
class CacheBookWork @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val localBookDataSource: LocalBookDataSource,
    private val webBookDataSourceProvider: WebBookDataSourceProvider,
    private val downloadProgressRepository: DownloadProgressRepository
) : CoroutineWorker(appContext, workerParams) {
    companion object {
        const val KEY_BOOK_ID = "bookId"

        fun ofId(id: String): String = "cache:$id"
    }

    override suspend fun doWork(): Result {
        val bookId = inputData.getString(KEY_BOOK_ID) ?: return Result.failure()
        if (bookId.isBlank()) return Result.failure()
        val webBookDataSource = webBookDataSourceProvider.lowPriority
        val sourceId = webBookDataSource.id
        val downloadItem = MutableDownloadItem(DownloadType.CACHE, bookId)
        downloadProgressRepository.addExportItem(downloadItem)
        var count = 0
        val bookVolumes = webBookDataSource.getBookVolumes(bookId)
        val total = bookVolumes.volumes.sumOf { it.chapters.size } + 1
        if (bookVolumes.volumes.isEmpty()) return Result.failure()
        localBookDataSource.updateBookVolumes(bookVolumes)
        val orderedChapters = bookVolumes.volumes.flatMap { it.chapters }
        val navigationByChapterId = orderedChapters.mapIndexed { index, chapter ->
            chapter.id to Pair(
                orderedChapters.getOrNull(index - 1)?.id ?: "",
                orderedChapters.getOrNull(index + 1)?.id ?: ""
            )
        }.toMap()
        fun updateProgress() {
            count++
            downloadItem.progress = count.toFloat() / total
        }
        for (volume in bookVolumes.volumes) {
            for (chapterInformation in volume.chapters) {
                val chapterId = chapterInformation.id
                val cachedChapter = localBookDataSource.getExactChapterContent(sourceId, bookId, chapterId)
                if (cachedChapter != null && !cachedChapter.isEmpty()) {
                    val (lastChapterId, nextChapterId) = navigationByChapterId[chapterId] ?: Pair("", "")
                    if (cachedChapter.lastChapter != lastChapterId || cachedChapter.nextChapter != nextChapterId) {
                        localBookDataSource.updateChapterContent(
                            sourceId,
                            bookId,
                            cachedChapter.toMutable().apply {
                                lastChapter = lastChapterId
                                nextChapter = nextChapterId
                            }
                        )
                    }
                    updateProgress()
                    continue
                }
                val chapter = webBookDataSource.getChapterContent(
                    chapterId = chapterId,
                    bookId = bookId
                )
                if (chapter.isEmpty()) {
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(applicationContext, "缓存失败，请检查网络环境", Toast.LENGTH_SHORT).show()
                    }
                    return Result.failure()
                }
                localBookDataSource.updateChapterContent(sourceId, bookId, chapter)
                updateProgress()
            }
        }
        webBookDataSource.getBookInformation(bookId)
            .let {
                if (it.isEmpty()) return Result.failure()
                localBookDataSource.updateBookInformation(it)
            }
        downloadItem.progress = 1f
        return Result.success()
    }
}