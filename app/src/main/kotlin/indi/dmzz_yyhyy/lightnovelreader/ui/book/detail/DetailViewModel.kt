package indi.dmzz_yyhyy.lightnovelreader.ui.book.detail

import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.github.michaelbull.result.get
import com.github.michaelbull.result.onOk
import dagger.hilt.android.lifecycle.HiltViewModel
import indi.dmzz_yyhyy.lightnovelreader.data.book.BookRepository
import indi.dmzz_yyhyy.lightnovelreader.data.bookshelf.BookshelfRepository
import indi.dmzz_yyhyy.lightnovelreader.data.download.ChapterDownloadRepository
import indi.dmzz_yyhyy.lightnovelreader.data.download.DownloadProgressRepository
import indi.dmzz_yyhyy.lightnovelreader.data.download.DownloadType
import indi.dmzz_yyhyy.lightnovelreader.data.statistics.StatsRepository
import indi.dmzz_yyhyy.lightnovelreader.data.web.WebBookDataSourceProvider
import indi.dmzz_yyhyy.lightnovelreader.data.work.ExportBookToEPUBWork
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.LinovelibConstants
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.sync.LinovelibBookmarkRepository
import indi.dmzz_yyhyy.lightnovelreader.utils.toLegacyCompatibleSourceId
import io.nightfish.lightnovelreader.api.book.UserReadingData
import io.nightfish.lightnovelreader.api.web.WebDataSourcePriority
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import javax.inject.Inject

internal fun UserReadingData.copyWithMarkedChaptersAsRead(
    chapterIds: Collection<String>,
    allChapterIds: List<String>
): UserReadingData {
    val validChapterIds = chapterIds.toSet().intersect(allChapterIds.toSet())
    if (validChapterIds.isEmpty()) return this

    val updatedCurrentProgress = currentChapterReadingProgressMap.toMutableMap()
    val updatedMaxProgress = maxChapterReadingProgressMap.toMutableMap()
    validChapterIds.forEach { chapterId ->
        updatedCurrentProgress[chapterId] = 1f
        updatedMaxProgress[chapterId] = 1f
    }
    val readingProgress = if (allChapterIds.isEmpty()) {
        readingProgress
    } else {
        (allChapterIds.sumOf { (updatedMaxProgress[it] ?: 0f).toDouble() } /
            allChapterIds.size).toFloat().coerceIn(0f, 1f)
    }

    return copy(
        readingProgress = readingProgress,
        currentChapterReadingProgressMap = updatedCurrentProgress,
        maxChapterReadingProgressMap = updatedMaxProgress
    )
}

@HiltViewModel
class DetailViewModel @Inject constructor(
    private val bookRepository: BookRepository,
    private val bookshelfRepository: BookshelfRepository,
    private val chapterDownloadRepository: ChapterDownloadRepository,
    private val downloadProgressRepository: DownloadProgressRepository,
    private val workManager: WorkManager,
    private val statsRepository: StatsRepository,
    private val linovelibBookmarkRepository: LinovelibBookmarkRepository,
    private val webBookDataSourceProvider: WebBookDataSourceProvider
) : ViewModel() {
    private val _uiState = MutableDetailUiState()
    var exportSettings = ExportSettings()
    var navController: NavController? = null
    val uiState: DetailUiState = _uiState

    var isInitialized by mutableStateOf(false)
        private set

    fun init(bookId: String) {
        Log.d("DetailViewModel", "Init bookId = $bookId")
        if (isInitialized) return
        isInitialized = true
        val isLinovelibSource = webBookDataSourceProvider.value.id == LinovelibConstants.SOURCE_ID
        _uiState.isLinovelibSource = isLinovelibSource
        viewModelScope.launch(Dispatchers.IO) {
            bookRepository.getBookInformationFlow(bookId, WebDataSourcePriority.High).collect { result ->
                result.onOk {
                    val bookshelfBookMetadata = bookshelfRepository.getBookshelfBookMetadata(bookId) ?: return@onOk
                    bookshelfBookMetadata.bookShelfIds.forEach { bookshelfId ->
                        bookshelfRepository.deleteBookFromBookshelfUpdatedBookIds(bookshelfId, bookId)
                    }
                    bookshelfRepository.updateBookshelfBookMetadataLastUpdateTime(bookId, it.lastUpdated)
                }
                _uiState.bookInformation = result
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            val sourceId = webBookDataSourceProvider.value.id.toLegacyCompatibleSourceId()
            bookRepository.getBookVolumesFlow(bookId, WebDataSourcePriority.High).collect { result ->
                _uiState.bookVolumes = result
                result.component1()?.let { bookVolumes ->
                    val chapterIds = bookVolumes.volumes
                        .flatMap { volume -> volume.chapters.map { chapter -> chapter.id } }
                    chapterDownloadRepository.migrateLegacyCachedChapters(
                        sourceId = sourceId,
                        bookId = bookId,
                        chapterIds = chapterIds
                    )
                    _uiState.isCached = chapterDownloadRepository.isBookFullyDownloaded(
                        sourceId = sourceId,
                        bookId = bookId,
                        chapterIds = chapterIds
                    )
                }
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            val sourceId = webBookDataSourceProvider.value.id.toLegacyCompatibleSourceId()
            chapterDownloadRepository.getStatesFlow(sourceId, bookId).collect { states ->
                _uiState.chapterDownloadStates = states
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            bookRepository.getUserReadingDataFlow(bookId).collect {
                _uiState.userReadingData = it
            }
        }
        if (isLinovelibSource) {
            viewModelScope.launch(Dispatchers.IO) {
                linovelibBookmarkRepository.getBookmarkFlow(bookId).collect { bookmark ->
                    _uiState.bookmarkUiState = DetailBookmarkUiState(
                        chapterId = bookmark?.chapterId.orEmpty(),
                        chapterTitle = bookmark?.chapterTitle.orEmpty(),
                        syncState = bookmark?.syncState.orEmpty()
                    )
                }
            }
        } else {
            _uiState.bookmarkUiState = DetailBookmarkUiState()
        }
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.isCached = bookRepository.getIsBookCached(bookId)
        }
        viewModelScope.launch(Dispatchers.IO) {
            bookshelfRepository.getBookshelfBookMetadataFlow(bookId).collect {
                _uiState.isInBookshelf = it != null
            }
        }
        viewModelScope.launch {
            val sourceId = webBookDataSourceProvider.value.id.toLegacyCompatibleSourceId()
            snapshotFlow { downloadProgressRepository.downloadItemIdList }.collect {
                _uiState.downloadItem = downloadProgressRepository.downloadItemIdList.findLast {
                    it.bookId == bookId &&
                        it.sourceId == sourceId &&
                        it.type == DownloadType.CACHE
                }
            }
        }
    }

    suspend fun cacheBook(
        bookId: String,
        chapterIds: List<String>,
        forceRefresh: Boolean = false
    ): Flow<WorkInfo?> {
        val dataSource = webBookDataSourceProvider.value
        val sourceId = dataSource.id.toLegacyCompatibleSourceId()
        bookRepository.cacheBook(
            bookId = bookId,
            chapterIds = chapterIds,
            forceRefresh = forceRefresh,
            onTaskRegistered = {
                downloadProgressRepository.addCacheItem(
                    bookId = bookId,
                    sourceId = sourceId,
                    sourceKey = dataSource.id.toString()
                )
            }
        ) ?: return emptyFlow()
        val isCachedFlow = bookRepository.isCacheBookWorkFlow(sourceId, bookId)
        viewModelScope.launch(Dispatchers.IO) {
            isCachedFlow.collect { workInfo ->
                if (workInfo?.state?.isFinished == true) {
                    _uiState.isCached = bookRepository.getIsBookCached(bookId)
                }
            }
        }
        return isCachedFlow
    }

    fun clearCachedChapters(bookId: String, chapterIds: List<String>) {
        viewModelScope.launch(Dispatchers.IO) {
            bookRepository.clearCachedChapters(bookId, chapterIds)
            _uiState.isCached = bookRepository.getIsBookCached(bookId)
        }
    }

    fun markChaptersAsRead(bookId: String, chapterIds: List<String>) {
        val allChapterIds = _uiState.bookVolumes?.get()?.volumes
            ?.flatMap { it.chapters }
            ?.map { it.id }
            ?: return
        val selectedChapterIds = chapterIds.toSet().intersect(allChapterIds.toSet())
        if (selectedChapterIds.isEmpty()) return

        viewModelScope.launch(Dispatchers.IO) {
            bookRepository.updateUserReadingData(bookId) { userReadingData ->
                userReadingData.copyWithMarkedChaptersAsRead(
                    chapterIds = selectedChapterIds,
                    allChapterIds = allChapterIds
                ).copy(lastReadTime = LocalDateTime.now())
            }
            val readingData = bookRepository.getUserReadingData(bookId)
            if (readingData.readingProgress >= 1f) {
                statsRepository.markBookFinished(bookId)
            }
        }
    }

    fun onClickTag(tag: String) {
        if (navController == null) return
        bookRepository.progressBookTagClick(tag, navController!!)
    }

    fun matchLinovelibBookmark(bookId: String, chapterId: String): Boolean {
        if (!_uiState.isLinovelibSource) return false
        val chapter = _uiState.bookVolumes?.get()?.volumes
            ?.asSequence()
            ?.flatMap { it.chapters.asSequence() }
            ?.firstOrNull { it.id == chapterId }
            ?: return false
        viewModelScope.launch(Dispatchers.IO) {
            linovelibBookmarkRepository.matchRemoteBookmarkManually(
                bookId = bookId,
                chapterId = chapter.id,
                chapterTitle = chapter.title
            )
        }
        return true
    }


    fun exportToEpub(uri: Uri, bookId: String, title: String): Flow<WorkInfo?> {
        val workRequest = OneTimeWorkRequestBuilder<ExportBookToEPUBWork>()
            .setInputData(
                workDataOf(
                    "bookId" to bookId,
                    "uri" to uri.toString(),
                    "title" to title,
                    "includeImages" to exportSettings.includeImages,
                    "exportType" to exportSettings.exportType.name,
                    "selectedVolume" to exportSettings.selectedVolumeIds.joinToString(",")
                )
            )
            .build()
        workManager.enqueueUniqueWork(
            ExportBookToEPUBWork.ofId(bookId),
            ExistingWorkPolicy.KEEP,
            workRequest
        )
        return workManager.getWorkInfoByIdFlow(workRequest.id)
    }
}