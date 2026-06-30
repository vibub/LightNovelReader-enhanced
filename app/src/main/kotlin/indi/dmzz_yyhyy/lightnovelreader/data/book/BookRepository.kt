package indi.dmzz_yyhyy.lightnovelreader.data.book

import androidx.navigation.NavController
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import indi.dmzz_yyhyy.lightnovelreader.data.bookshelf.BookshelfRepository
import indi.dmzz_yyhyy.lightnovelreader.data.local.LocalBookDataSource
import indi.dmzz_yyhyy.lightnovelreader.data.text.TextProcessingRepository
import indi.dmzz_yyhyy.lightnovelreader.data.web.WebBookDataSourceProvider
import indi.dmzz_yyhyy.lightnovelreader.data.work.CacheBookWork
import io.nightfish.lightnovelreader.api.book.BookInformation
import io.nightfish.lightnovelreader.api.book.BookRepositoryApi
import io.nightfish.lightnovelreader.api.book.BookVolumes
import io.nightfish.lightnovelreader.api.book.ChapterContent
import io.nightfish.lightnovelreader.api.book.MutableBookInformation
import io.nightfish.lightnovelreader.api.book.MutableChapterContent
import io.nightfish.lightnovelreader.api.book.MutableUserReadingData
import io.nightfish.lightnovelreader.api.book.UserReadingData
import io.nightfish.lightnovelreader.api.web.WebDataSourcePriority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BookRepository @Inject constructor(
    private val webBookDataSourceProvider: WebBookDataSourceProvider,
    private val localBookDataSource: LocalBookDataSource,
    private val bookshelfRepository: BookshelfRepository,
    private val textProcessingRepository: TextProcessingRepository,
    private val workManager: WorkManager
): BookRepositoryApi {
    private val chapterRefreshLock = Any()
    private val recentChapterRemoteFetches = mutableMapOf<String, Long>()

    fun WebDataSourcePriority.get() =
        when(this) {
            WebDataSourcePriority.High -> webBookDataSourceProvider.highPriority
            WebDataSourcePriority.Default -> webBookDataSourceProvider.default
            WebDataSourcePriority.Low -> webBookDataSourceProvider.lowPriority
        }

    override suspend fun getBookInformation(
        id: String,
        priority: WebDataSourcePriority
    ) =
        withContext(Dispatchers.IO) {
            textProcessingRepository.coroutineProcessBookInformation {
                val webChapterContent = priority.get().getBookInformation(id)
                if (!webChapterContent.isEmpty()) {
                    localBookDataSource.updateBookInformation(webChapterContent)
                    return@coroutineProcessBookInformation webChapterContent
                }
                return@coroutineProcessBookInformation localBookDataSource.getBookInformation(id) ?: MutableBookInformation.empty().apply { this.id = id }
            }
        }

    override fun getStateBookInformation(
        id: String,
        coroutineScope: CoroutineScope,
        priority: WebDataSourcePriority
    ): BookInformation =
        textProcessingRepository.processBookInformation {
            val bookInformation = MutableBookInformation.empty()
            bookInformation.id = id
            coroutineScope.launch(Dispatchers.IO) {
                localBookDataSource.getBookInformation(id)?.let(bookInformation::update)
                priority.get().getBookInformation(id).let { webInfo ->
                    if (!webInfo.isEmpty()) {
                        localBookDataSource.updateBookInformation(webInfo)
                        bookInformation.update(
                            textProcessingRepository.processBookInformation { webInfo }
                        )
                    }
                }
            }
            return@processBookInformation bookInformation
        }

    override fun getBookInformationFlow(
        id: String,
        priority: WebDataSourcePriority
    ): Flow<BookInformation> = flow {
        val local = localBookDataSource.getBookInformation(id) ?: BookInformation.empty(id)
        emit(local)
        val remote = priority.get().getBookInformation(id)
        if (remote.isEmpty()) return@flow
        localBookDataSource.updateBookInformation(remote)
        emit(remote)
        val bookshelfBookMetadata = bookshelfRepository.getBookshelfBookMetadata(remote.id) ?: return@flow
        if (bookshelfBookMetadata.lastUpdate.isBefore(remote.lastUpdated))
            bookshelfBookMetadata.bookShelfIds.forEach {
                bookshelfRepository.updateBookshelfBookMetadataLastUpdateTime(
                    remote.id,
                    remote.lastUpdated
                )
                bookshelfRepository.addUpdatedBooksIntoBookShelf(it, id)
            }
    }.map { textProcessingRepository.processBookInformation { it } }

    override fun getBookVolumesFlow(
        id: String,
        priority: WebDataSourcePriority
    ): Flow<BookVolumes> = flow {
        val local = localBookDataSource.getBookVolumes(id) ?: BookVolumes.empty(id)
        emit(local)
        val remote = priority.get().getBookVolumes(id)
        if (remote.isEmpty()) return@flow
        localBookDataSource.updateBookVolumes(remote)
        emit(remote)
    }.map { textProcessingRepository.processBookVolumes { it } }

    override fun getStateChapterContent(
        chapterId: String,
        bookId: String,
        coroutineScope: CoroutineScope,
        priority: WebDataSourcePriority
    ): ChapterContent =
        textProcessingRepository.processChapterContent(bookId) {
            val chapterContent = MutableChapterContent.empty()
            chapterContent.id = chapterId
            coroutineScope.launch(Dispatchers.IO) {
                localBookDataSource.getChapterContent(chapterId)?.let {
                    if (it.isEmpty()) return@launch
                    chapterContent.update(
                        it.toMutable().apply {
                            this.content = textProcessingRepository
                                .processChapterContent(bookId) { this }
                                .content
                        })
                }
                priority.get().getChapterContent(chapterId, bookId).let {
                    if (it.isEmpty()) return@launch
                    localBookDataSource.updateChapterContent(it)
                    markChapterRemoteFetched(bookId, chapterId)
                    chapterContent.update(
                        it.toMutable().apply {
                            this.content = textProcessingRepository
                                .processChapterContent(bookId) { this }
                                .content
                        })
                }
            }
            return@processChapterContent chapterContent
        }

    override suspend fun getChapterContent(
        chapterId: String,
        bookId: String,
        priority: WebDataSourcePriority
    ): ChapterContent =
        withContext(Dispatchers.IO) {
            textProcessingRepository.coroutineProcessChapterContent(bookId) {
                val webChapterContent = priority.get().getChapterContent(chapterId, bookId)
                if (!webChapterContent.isEmpty()) {
                    localBookDataSource.updateChapterContent(webChapterContent)
                    markChapterRemoteFetched(bookId, chapterId)
                    return@coroutineProcessChapterContent webChapterContent
                }
                return@coroutineProcessChapterContent localBookDataSource.getChapterContent(chapterId) ?: MutableChapterContent.empty().apply { id = chapterId }
            }
        }

    suspend fun prefetchChapterContent(
        chapterId: String,
        bookId: String,
        priority: WebDataSourcePriority = WebDataSourcePriority.Low
    ): ChapterContent =
        withContext(Dispatchers.IO) {
            textProcessingRepository.coroutineProcessChapterContent(bookId) {
                val localChapter = localBookDataSource.getChapterContent(chapterId)
                if (localChapter != null && !localChapter.isEmpty()) return@coroutineProcessChapterContent localChapter
                val webChapterContent = priority.get().getChapterContent(chapterId, bookId)
                if (!webChapterContent.isEmpty()) {
                    localBookDataSource.updateChapterContent(webChapterContent)
                    markChapterRemoteFetched(bookId, chapterId)
                    return@coroutineProcessChapterContent webChapterContent
                }
                return@coroutineProcessChapterContent MutableChapterContent.empty().apply { id = chapterId }
            }
        }

    override fun getChapterContentFlow(
        chapterId: String,
        bookId: String,
        priority: WebDataSourcePriority
    ): Flow<ChapterContent> = flow {
        val localChapter = localBookDataSource.getChapterContent(chapterId) ?: MutableChapterContent.empty()
                .apply { id = chapterId }
        emit(localChapter)
        if (!localChapter.isEmpty() && isChapterRecentlyFetched(bookId, chapterId)) return@flow
        val remoteChapter = priority.get().getChapterContent(
            chapterId = chapterId,
            bookId = bookId
        )
        if (remoteChapter.isEmpty()) return@flow
        localBookDataSource.updateChapterContent(remoteChapter)
        markChapterRemoteFetched(bookId, chapterId)
        emit(remoteChapter)
    }.map { textProcessingRepository.processChapterContent(bookId) { it } }

    override fun getStateUserReadingData(bookId: String, coroutineScope: CoroutineScope): UserReadingData {
        val userReadingData = MutableUserReadingData.empty()
        userReadingData.id = bookId
        coroutineScope.launch(Dispatchers.IO) {
            localBookDataSource.getUserReadingData(bookId).let(userReadingData::update)
        }
        return userReadingData
    }

    override fun getUserReadingData(bookId: String): UserReadingData =
        localBookDataSource.getUserReadingData(bookId)

    override fun getUserReadingDataFlow(bookId: String): Flow<UserReadingData> =
        localBookDataSource.getUserReadingDataFlow(bookId)

    override fun getAllUserReadingData(): List<UserReadingData> =
        localBookDataSource.getAllUserReadingData()

    override fun updateUserReadingData(id: String, update: (MutableUserReadingData) -> UserReadingData) {
        localBookDataSource.updateUserReadingData(id, update)
    }

    private fun markChapterRemoteFetched(bookId: String, chapterId: String) {
        synchronized(chapterRefreshLock) {
            recentChapterRemoteFetches[chapterRefreshKey(bookId, chapterId)] = System.currentTimeMillis()
        }
    }

    private fun isChapterRecentlyFetched(bookId: String, chapterId: String): Boolean {
        val now = System.currentTimeMillis()
        synchronized(chapterRefreshLock) {
            val iterator = recentChapterRemoteFetches.iterator()
            while (iterator.hasNext()) {
                if (now - iterator.next().value > CHAPTER_REMOTE_REFRESH_TTL_MILLIS) iterator.remove()
            }
            val lastFetchedAt = recentChapterRemoteFetches[chapterRefreshKey(bookId, chapterId)] ?: return false
            return now - lastFetchedAt <= CHAPTER_REMOTE_REFRESH_TTL_MILLIS
        }
    }

    private fun chapterRefreshKey(bookId: String, chapterId: String): String = "$bookId/$chapterId"

    fun isCacheBookWorkFlow(bookId: String): Flow<WorkInfo?> =
        workManager.getWorkInfosForUniqueWorkFlow(CacheBookWork.ofId(bookId))
            .map { workInfos ->
                workInfos.firstOrNull { !it.state.isFinished } ?: workInfos.firstOrNull()
            }

    fun cacheBook(bookId: String) {
        val workRequest = OneTimeWorkRequestBuilder<CacheBookWork>()
            .setInputData(
                workDataOf(
                    CacheBookWork.KEY_BOOK_ID to bookId
                )
            )
            .build()
        workManager.enqueueUniqueWork(
            CacheBookWork.ofId(bookId),
            ExistingWorkPolicy.KEEP,
            workRequest
        )
    }

    override suspend fun getIsBookCached(bookId: String): Boolean {
        localBookDataSource.getBookVolumes(bookId)?.let { bookVolumes ->
            if (bookVolumes.volumes.isEmpty())
                return false
            bookVolumes.volumes.forEach { bookVolume ->
                bookVolume.chapters.forEach {
                    if (!localBookDataSource.isChapterContentExists(it.id))
                        return false
                }
            }
        } ?: return false
        return true
    }

    override fun progressBookTagClick(tag: String, navController: NavController) =
        webBookDataSourceProvider.default.progressBookTagClick(tag, navController)

    companion object {
        private const val CHAPTER_REMOTE_REFRESH_TTL_MILLIS = 10 * 60 * 1000L
    }
}