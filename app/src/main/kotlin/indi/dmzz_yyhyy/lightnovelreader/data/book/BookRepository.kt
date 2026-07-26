package indi.dmzz_yyhyy.lightnovelreader.data.book

import android.net.Uri
import android.util.Log
import androidx.navigation.NavController
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.map
import com.github.michaelbull.result.onErr
import com.github.michaelbull.result.onOk
import indi.dmzz_yyhyy.lightnovelreader.data.bookshelf.BookshelfRepository
import indi.dmzz_yyhyy.lightnovelreader.data.local.LocalBookDataSource
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonArray
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

@Singleton
class BookRepository @Inject constructor(
    private val webBookDataSourceProvider: WebBookDataSourceProvider,
    private val localBookDataSource: LocalBookDataSource,
    private val bookshelfRepository: BookshelfRepository,
    private val textProcessingRepository: TextProcessingRepository,
    private val workManager: WorkManager
): BookRepositoryApi {
    companion object {
        private const val TAG = "BookRepository"
    }

    private val webBookDataSource get() = webBookDataSourceProvider.value

    override fun getBookInformationFlow(
        id: String,
        priority: WebDataSourcePriority
    ): Flow<Result<BookInformation, WebRequestError>> = flow {
        val local = localBookDataSource.getBookInformation(id)
            ?.takeIf(::isUsableBookInformation)
        local?.let { emit(Ok(it)) }
        val remote = webBookDataSource.getBookInformation(id, priority)
            .onOk { remote ->
                localBookDataSource.updateBookInformation(remote)
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

    override fun getBookVolumesFlow(
        id: String,
        priority: WebDataSourcePriority
    ): Flow<Result<BookVolumes, WebRequestError>> = flow {
        val local = localBookDataSource.getBookVolumes(id)
            ?.takeIf(::isUsableBookVolumes)
        local?.let { emit(Ok(it)) }
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
        val local = localBookDataSource.getChapterContent(sourceId, bookId, chapterId)
            ?.takeIf(::isUsableChapterContent)
        local?.let { emit(Ok(it)) }
        val remote = webBookDataSource.getChapterContent(chapterId, bookId, priority)
            .onOk { remote ->
                localBookDataSource.updateChapterContent(sourceId, bookId, remote)
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
        webBookDataSource.getChapterContent(chapterId, bookId, priority)
            .onOk { remote ->
                localBookDataSource.updateChapterContent(sourceId, bookId, remote)
            }.onErr {
                Log.e(TAG, "Failed to request web data (title=${it.title}, message=${it.message})")
                it.throwable?.printStackTrace()
            }
    }

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

    fun cacheBook(bookId: String): OneTimeWorkRequest {
        val workRequest = OneTimeWorkRequestBuilder<CacheBookWork>()
            .setInputData(
                workDataOf(
                    "bookId" to bookId
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
        localBookDataSource.getBookVolumes(bookId)?.let { bookVolumes ->
            if (bookVolumes.volumes.isEmpty())
                return false
            bookVolumes.volumes.forEach { bookVolume ->
                bookVolume.chapters.forEach {
                    if (!localBookDataSource.isChapterContentExists(sourceId, bookId, it.id))
                        return false
                }
            }
        } ?: return false
        return true
    }

    override fun progressBookTagClick(tag: String, navController: NavController) =
        webBookDataSource.progressBookTagClick(tag, navController)
}