package indi.dmzz_yyhyy.lightnovelreader.data.book

import android.net.Uri
import android.util.Log
import androidx.navigation.NavController
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.map
import com.github.michaelbull.result.onErr
import com.github.michaelbull.result.onOk
import indi.dmzz_yyhyy.lightnovelreader.BuildConfig
import indi.dmzz_yyhyy.lightnovelreader.data.download.ChapterDownloadRepository
import indi.dmzz_yyhyy.lightnovelreader.data.download.ChapterDownloadStatus
import indi.dmzz_yyhyy.lightnovelreader.data.bookshelf.BookshelfRepository
import indi.dmzz_yyhyy.lightnovelreader.data.local.LocalBookDataSource
import indi.dmzz_yyhyy.lightnovelreader.data.local.OfflineContentCache
import indi.dmzz_yyhyy.lightnovelreader.data.text.TextProcessingRepository
import indi.dmzz_yyhyy.lightnovelreader.data.web.WebBookDataSourceProvider
import indi.dmzz_yyhyy.lightnovelreader.data.work.CacheBookWork
import indi.dmzz_yyhyy.lightnovelreader.utils.toLegacyCompatibleSourceId
import io.nightfish.lightnovelreader.api.book.BookInformation
import io.nightfish.lightnovelreader.api.book.BookRepositoryApi
import io.nightfish.lightnovelreader.api.book.BookVolumes
import io.nightfish.lightnovelreader.api.book.ChapterContent
import io.nightfish.lightnovelreader.api.book.UserReadingData
import io.nightfish.lightnovelreader.api.error.WebRequestError
import io.nightfish.lightnovelreader.api.web.WebDataSourcePriority
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlin.time.Duration.Companion.seconds
import java.io.File
import java.time.LocalDateTime
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

internal fun isUsableBookInformationData(
    id: String,
    title: String,
    hasCover: Boolean,
    subtitle: String,
    author: String,
    description: String,
    hasTags: Boolean,
    publishingHouse: String,
    wordCount: Int,
    lastUpdated: LocalDateTime,
    isComplete: Boolean
): Boolean = id.isNotBlank() && title.isNotBlank() && (
    hasCover ||
        subtitle.isNotBlank() ||
        author.isNotBlank() ||
        description.isNotBlank() ||
        hasTags ||
        publishingHouse.isNotBlank() ||
        wordCount > 0 ||
        lastUpdated != LocalDateTime.MIN ||
        isComplete
    )

internal fun isUsableBookInformation(bookInformation: BookInformation): Boolean =
    isUsableBookInformationData(
        id = bookInformation.id,
        title = bookInformation.title,
        hasCover = bookInformation.coverUri != Uri.EMPTY && bookInformation.coverUri.toString().isNotBlank(),
        subtitle = bookInformation.subtitle,
        author = bookInformation.author,
        description = bookInformation.description,
        hasTags = bookInformation.tags.isNotEmpty(),
        publishingHouse = bookInformation.publishingHouse,
        wordCount = bookInformation.wordCount.count,
        lastUpdated = bookInformation.lastUpdated,
        isComplete = bookInformation.isComplete
    )

internal fun isUsableBookVolumes(bookVolumes: BookVolumes): Boolean =
    bookVolumes.bookId.isNotBlank() && bookVolumes.volumes.any { volume ->
        volume.chapters.any { chapter -> chapter.id.isNotBlank() }
    }

internal fun isUsableChapterContent(chapterContent: ChapterContent): Boolean =
    chapterContent.id.isNotBlank() &&
        (chapterContent.content["components"] as? JsonArray)?.isNotEmpty() == true

internal fun <T, E> shouldEmitRemoteResult(
    hasUsableLocal: Boolean,
    remote: Result<T, E>
): Boolean = !hasUsableLocal || remote.isOk

internal fun <T, E> preserveLocalFallback(
    local: T?,
    remote: Flow<Result<T, E>>
): Flow<Result<T, E>> = flow {
    local?.let { emit(Ok(it)) }
    remote.collect { result ->
        if (shouldEmitRemoteResult(local != null, result)) emit(result)
    }
}.distinctUntilChanged()

internal fun shouldRequestRemoteChapter(
    hasUsableLocal: Boolean,
    wasRecentlyFetched: Boolean
): Boolean = !hasUsableLocal || !wasRecentlyFetched

@Singleton
class BookRepository @Inject constructor(
    private val webBookDataSourceProvider: WebBookDataSourceProvider,
    private val localBookDataSource: LocalBookDataSource,
    private val chapterDownloadRepository: ChapterDownloadRepository,
    private val offlineContentCache: OfflineContentCache,
    private val bookshelfRepository: BookshelfRepository,
    private val textProcessingRepository: TextProcessingRepository,
    private val workManager: WorkManager
): BookRepositoryApi {
    companion object {
        private const val TAG = "BookRepository"
        private const val CHAPTER_REMOTE_REFRESH_TTL_MILLIS = 10 * 60 * 1000L
        private val CHAPTER_REMOTE_TIMEOUT = 60.seconds
    }

    private val chapterRefreshLock = Any()
    private val recentChapterRemoteFetches = mutableMapOf<String, Long>()
    private val webBookDataSource get() = webBookDataSourceProvider.value

    override fun getBookInformationFlow(
        id: String,
        priority: WebDataSourcePriority
    ): Flow<Result<BookInformation, WebRequestError>> = flow {
        val local = localBookDataSource.getBookInformation(id)
            ?.takeIf(::isUsableBookInformation)
        local?.let { emit(Ok(it)) }
        if (BuildConfig.BENCHMARK && local != null) return@flow
        val remote = webBookDataSource.getBookInformation(id, priority)
            .onOk { remote ->
                val cachedCover = local?.coverUri?.takeIf { uri ->
                    uri.scheme.equals("file", ignoreCase = true) &&
                        uri.path?.let(::File)?.isFile == true
                }
                localBookDataSource.updateBookInformation(
                    cachedCover?.let { remote.copy(coverUri = it) } ?: remote
                )
                val bookshelfBookMetadata = bookshelfRepository.getBookshelfBookMetadata(remote.id) ?: return@onOk
                if (bookshelfBookMetadata.lastUpdate.isBefore(remote.lastUpdated))
                    bookshelfBookMetadata.bookShelfIds.forEach {
                        bookshelfRepository.updateBookshelfBookMetadataLastUpdateTime(
                            remote.id,
                            remote.lastUpdated
                        )
                        bookshelfRepository.addUpdatedBooksIntoBookShelf(it, id)
                    }
            }.onErr {
                Log.e(TAG, "Failed to request web data (title=${it.title}, message=${it.message})")
                it.throwable?.printStackTrace()
            }
        if (shouldEmitRemoteResult(local != null, remote)) emit(remote)
    }.map { result ->
        result.map {
            textProcessingRepository.processBookInformation { it }
        }
    }

    internal suspend fun getLocalBookshelfBookInformation(
        ids: List<String>
    ): Map<String, BookInformation> = localBookDataSource.getBookInformationByIds(ids)
        .mapValues { (_, bookInformation) ->
            textProcessingRepository.processBookInformation { bookInformation }
        }

    internal fun getBookshelfBookInformationFlow(
        id: String,
        priority: WebDataSourcePriority = WebDataSourcePriority.Default
    ): Flow<Result<BookInformation, WebRequestError>> = flow {
        val local = localBookDataSource.getBookInformation(id)?.let { bookInformation ->
            textProcessingRepository.processBookInformation { bookInformation }
        }
        emitAll(
            preserveLocalFallback(
                local = local,
                remote = getBookInformationFlow(id, priority)
            )
        )
    }

    override fun getBookVolumesFlow(
        id: String,
        priority: WebDataSourcePriority
    ): Flow<Result<BookVolumes, WebRequestError>> = flow {
        val local = localBookDataSource.getBookVolumes(id)
            ?.takeIf(::isUsableBookVolumes)
        local?.let { emit(Ok(it)) }
        if (BuildConfig.BENCHMARK && local != null) return@flow
        val remote = webBookDataSource.getBookVolumes(id, priority)
            .onOk { remote ->
                localBookDataSource.updateBookVolumes(remote)
            }.onErr {
                Log.e(TAG, "Failed to request web data (title=${it.title}, message=${it.message})")
                it.throwable?.printStackTrace()
            }
        if (shouldEmitRemoteResult(local != null, remote)) emit(remote)
    }.map { result ->
        result.map {
            textProcessingRepository.processBookVolumes { it }
        }
    }

    override fun getChapterContentFlow(
        chapterId: String,
        bookId: String,
        priority: WebDataSourcePriority
    ): Flow<Result<ChapterContent, WebRequestError>> = flow {
        val sourceId = webBookDataSource.id.toLegacyCompatibleSourceId()
        chapterDownloadRepository.migrateLegacyCachedChapters(
            sourceId = sourceId,
            bookId = bookId,
            chapterIds = listOf(chapterId)
        )
        val local = localBookDataSource.getChapterContent(sourceId, bookId, chapterId)
            ?.takeIf(::isUsableChapterContent)
        local?.let { emit(Ok(it)) }
        if (BuildConfig.BENCHMARK && local != null) return@flow
        if (local != null && chapterDownloadRepository.isOfflineReady(sourceId, bookId, chapterId)) {
            return@flow
        }
        if (!shouldRequestRemoteChapter(local != null, isChapterRecentlyFetched(sourceId, bookId, chapterId))) {
            return@flow
        }
        // 远程章节调用需要保证有界：代理合并层或数据源挂起时，超时兜底确保流必然产出终态，
        // 避免阅读器对无缓存章节永久显示加载。
        val remote = try {
            withTimeoutOrNull(CHAPTER_REMOTE_TIMEOUT) {
                webBookDataSource.getChapterContent(chapterId, bookId, priority)
            }
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            Err(
                WebRequestError(
                    title = "章节加载失败",
                    message = "无法获取章节 $chapterId",
                    throwable = throwable
                )
            )
        } ?: Err(
            WebRequestError(
                title = "章节加载超时",
                message = "章节 $chapterId 加载超时，请重试"
            )
        )
        remote.onOk {
            localBookDataSource.updateChapterContent(sourceId, bookId, it)
            markChapterRemoteFetched(sourceId, bookId, chapterId)
        }.onErr {
            Log.e(TAG, "Failed to request web data (title=${it.title}, message=${it.message})")
            it.throwable?.printStackTrace()
        }
        if (shouldEmitRemoteResult(local != null, remote)) emit(remote)
    }.map { result ->
        result.map {
            textProcessingRepository.processChapterContent(bookId) { it }
        }
    }

    override suspend fun preloadChapterContent(
        chapterId: String,
        bookId: String,
        priority: WebDataSourcePriority
    ) {
        val sourceId = webBookDataSource.id.toLegacyCompatibleSourceId()
        chapterDownloadRepository.migrateLegacyCachedChapters(
            sourceId = sourceId,
            bookId = bookId,
            chapterIds = listOf(chapterId)
        )
        val local = localBookDataSource.getChapterContent(sourceId, bookId, chapterId)
            ?.takeIf(::isUsableChapterContent)
        if (local != null) return

        webBookDataSource.getChapterContent(chapterId, bookId, priority)
            .onOk { remote ->
                localBookDataSource.updateChapterContent(sourceId, bookId, remote)
                markChapterRemoteFetched(sourceId, bookId, chapterId)
            }.onErr {
                Log.e(TAG, "Failed to request web data (title=${it.title}, message=${it.message})")
                it.throwable?.printStackTrace()
            }
    }

    private fun markChapterRemoteFetched(sourceId: Int, bookId: String, chapterId: String) {
        synchronized(chapterRefreshLock) {
            recentChapterRemoteFetches[chapterRefreshKey(sourceId, bookId, chapterId)] =
                System.currentTimeMillis()
        }
    }

    private fun isChapterRecentlyFetched(sourceId: Int, bookId: String, chapterId: String): Boolean {
        val now = System.currentTimeMillis()
        synchronized(chapterRefreshLock) {
            val iterator = recentChapterRemoteFetches.iterator()
            while (iterator.hasNext()) {
                if (now - iterator.next().value > CHAPTER_REMOTE_REFRESH_TTL_MILLIS) {
                    iterator.remove()
                }
            }
            val lastFetchedAt = recentChapterRemoteFetches[
                chapterRefreshKey(sourceId, bookId, chapterId)
            ] ?: return false
            return now - lastFetchedAt <= CHAPTER_REMOTE_REFRESH_TTL_MILLIS
        }
    }

    private fun chapterRefreshKey(sourceId: Int, bookId: String, chapterId: String): String =
        "$sourceId/$bookId/$chapterId"

    override suspend fun getUserReadingData(bookId: String): UserReadingData =
        localBookDataSource.getUserReadingData(bookId)

    override fun getUserReadingDataFlow(bookId: String): Flow<UserReadingData> =
        localBookDataSource.getUserReadingDataFlow(bookId)

    override suspend fun getAllUserReadingData(): List<UserReadingData> =
        localBookDataSource.getAllUserReadingData()

    override suspend fun updateUserReadingData(id: String, update: (UserReadingData) -> UserReadingData) {
        localBookDataSource.updateUserReadingData(id, update)
    }

    fun isCacheBookWorkFlow(workId: UUID) = workManager.getWorkInfoByIdFlow(workId)

    suspend fun cacheBook(
        bookId: String,
        chapterIds: List<String>? = null,
        forceRefresh: Boolean = false
    ): OneTimeWorkRequest {
        val sourceId = webBookDataSource.id.toLegacyCompatibleSourceId()
        val localBookVolumes = localBookDataSource.getBookVolumes(bookId)
        val selectedChapterIds = chapterIds
            ?.map(String::trim)
            ?.filter(String::isNotBlank)
            ?.distinct()
        val chaptersToQueue = selectedChapterIds ?: localBookVolumes
            ?.volumes
            ?.flatMap { it.chapters }
            ?.map { it.id }
        if (!chaptersToQueue.isNullOrEmpty()) {
            chapterDownloadRepository.migrateLegacyCachedChapters(
                sourceId = sourceId,
                bookId = bookId,
                chapterIds = chaptersToQueue
            )
            chapterDownloadRepository.queue(
                sourceId = sourceId,
                bookId = bookId,
                chapterIds = chaptersToQueue,
                forceRefresh = forceRefresh
            )
        }

        return enqueueCacheWork(
            bookId = bookId,
            queueAll = selectedChapterIds == null && localBookVolumes == null
        )
    }

    fun resumeCachedBook(bookId: String): OneTimeWorkRequest =
        enqueueCacheWork(bookId = bookId, queueAll = false)

    suspend fun retryCachedBook(bookId: String): OneTimeWorkRequest {
        val sourceId = webBookDataSource.id.toLegacyCompatibleSourceId()
        val chapterIds = localBookDataSource.getBookVolumes(bookId)
            ?.volumes
            ?.flatMap { it.chapters }
            ?.map { it.id }
            .orEmpty()
        if (chapterIds.isNotEmpty()) {
            val states = chapterDownloadRepository.getStates(sourceId, bookId)
            val retryIds = chapterIds.filter { chapterId ->
                states[chapterId]?.status in setOf(
                    ChapterDownloadStatus.PARTIAL,
                    ChapterDownloadStatus.FAILED
                )
            }
            if (retryIds.isNotEmpty()) {
                chapterDownloadRepository.queue(
                    sourceId = sourceId,
                    bookId = bookId,
                    chapterIds = retryIds,
                    forceRefresh = true
                )
            }
        }
        return enqueueCacheWork(bookId = bookId, queueAll = false)
    }

    private fun enqueueCacheWork(
        bookId: String,
        queueAll: Boolean
    ): OneTimeWorkRequest {
        val workRequest = OneTimeWorkRequestBuilder<CacheBookWork>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .build()
            )
            .setInputData(
                workDataOf(
                    "bookId" to bookId,
                    "queueAll" to queueAll
                )
            )
            .build()
        workManager.enqueueUniqueWork(
            CacheBookWork.ofId(bookId),
            ExistingWorkPolicy.KEEP,
            workRequest
        )
        return workRequest
    }

    override suspend fun getIsBookCached(bookId: String): Boolean {
        val sourceId = webBookDataSource.id.toLegacyCompatibleSourceId()
        val bookVolumes = localBookDataSource.getBookVolumes(bookId) ?: return false
        val chapterIds = bookVolumes.volumes.flatMap { volume -> volume.chapters.map { it.id } }
        if (chapterIds.isEmpty()) return false
        chapterDownloadRepository.migrateLegacyCachedChapters(sourceId, bookId, chapterIds)
        return chapterDownloadRepository.isBookFullyDownloaded(sourceId, bookId, chapterIds)
    }

    suspend fun cancelCachedBook(bookId: String) {
        val sourceId = webBookDataSource.id.toLegacyCompatibleSourceId()
        chapterDownloadRepository.clearPending(sourceId, bookId)
    }

    suspend fun clearCachedChapters(bookId: String, chapterIds: List<String>) {
        val ids = chapterIds.map(String::trim).filter(String::isNotBlank).distinct()
        if (ids.isEmpty()) return
        val sourceId = webBookDataSource.id.toLegacyCompatibleSourceId()
        localBookDataSource.deleteChapterContent(sourceId, bookId, ids)
        chapterDownloadRepository.clearChapters(sourceId, bookId, ids)
        ids.forEach { chapterId ->
            offlineContentCache.deleteChapterImages(sourceId, bookId, chapterId)
        }
    }

    suspend fun clearCachedBook(bookId: String) {
        val sourceId = webBookDataSource.id.toLegacyCompatibleSourceId()
        val chapterIds = localBookDataSource.getBookVolumes(bookId)
            ?.volumes
            ?.flatMap { it.chapters }
            ?.map { it.id }
            .orEmpty()
        clearCachedChapters(bookId, chapterIds)
        chapterDownloadRepository.clearBook(sourceId, bookId)
        offlineContentCache.deleteBookImages(sourceId, bookId)
    }

    override fun progressBookTagClick(tag: String, navController: NavController) =
        webBookDataSource.progressBookTagClick(tag, navController)
}
