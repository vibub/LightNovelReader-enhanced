package indi.dmzz_yyhyy.lightnovelreader.ui.bookmanager

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import androidx.work.WorkManager
import dagger.hilt.android.lifecycle.HiltViewModel
import indi.dmzz_yyhyy.lightnovelreader.data.book.BookRepository
import indi.dmzz_yyhyy.lightnovelreader.data.download.DownloadItem
import indi.dmzz_yyhyy.lightnovelreader.data.download.DownloadItemState
import indi.dmzz_yyhyy.lightnovelreader.data.download.DownloadProgressRepository
import indi.dmzz_yyhyy.lightnovelreader.data.download.DownloadType
import indi.dmzz_yyhyy.lightnovelreader.data.local.OfflineContentCache
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.LightNovelReaderDatabase
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.entity.ChapterContentEntity
import indi.dmzz_yyhyy.lightnovelreader.data.storage.StorageUsageRepository
import indi.dmzz_yyhyy.lightnovelreader.data.storage.StorageUsageSnapshot
import indi.dmzz_yyhyy.lightnovelreader.data.work.CacheBookWork
import indi.dmzz_yyhyy.lightnovelreader.data.work.ExportBookToEPUBWork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class BookManagerViewModel @Inject constructor(
    private val bookRepository: BookRepository,
    private val downloadProgressRepository: DownloadProgressRepository,
    private val database: LightNovelReaderDatabase,
    private val storageUsageRepository: StorageUsageRepository,
    private val offlineContentCache: OfflineContentCache,
    val workManager: WorkManager
) : ViewModel() {
    val downloadItemListFlow get() = downloadProgressRepository.downloadItemListFlow
    private val _clearedItemsFlow = MutableSharedFlow<Int>()
    val clearedItemsFlow = _clearedItemsFlow.asSharedFlow()
    val localBookManagerUiState = MutableLocalBookManagerUiState(
        load = ::loadLocalBooks,
        setSort = ::setLocalBookSort,
        setReverse = ::setLocalBookReverse,
        enterSelection = ::enterLocalBookSelection,
        exitSelection = ::exitLocalBookSelection,
        toggleSelect = ::toggleLocalBookSelect,
        selectAll = ::selectAllLocalBooks,
        deleteSelected = ::deleteSelected,
        clearOrphanedData = ::clearOrphanedData,
        clearBookData = ::clearBookData,
        openStorageOverview = {},
        openBookDetailScreen = {}
    )

    init {
        viewModelScope.launch(Dispatchers.IO) {
            storageUsageRepository.getCachedSnapshot()?.let { updateLocalBooks(it, false) }
            refreshLocalBooks()
        }
        viewModelScope.launch(Dispatchers.IO) {
            downloadProgressRepository.downloadItemListFlow
                .map { snapshots ->
                    snapshots.associate { snapshot ->
                        Triple(
                            snapshot.item.type,
                            snapshot.item.sourceId,
                            snapshot.item.bookId
                        ) to snapshot.state
                    }
                }
                .distinctUntilChanged()
                .collectLatest { refreshLocalBooks() }
        }
    }

    fun onClickCancel(item: DownloadItem) {
        workManager.cancelUniqueWork(workName(item))
        if (item.type == DownloadType.CACHE) {
            // 兼容升级前使用全局 cache:$bookId 命名的任务。
            workManager.cancelUniqueWork(CacheBookWork.ofId(item.bookId))
            viewModelScope.launch(Dispatchers.IO) {
                bookRepository.cancelCachedBook(
                    bookId = item.bookId,
                    sourceId = item.sourceId,
                    sourceKey = item.sourceKey
                )
            }
        }
        downloadProgressRepository.removeExportItem(item)
    }

    fun onClickPause(item: DownloadItem) {
        if (item.type != DownloadType.CACHE || item.state != DownloadItemState.RUNNING) return
        item.state = DownloadItemState.PAUSED
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                bookRepository.pauseCachedBook(
                    bookId = item.bookId,
                    sourceId = item.sourceId,
                    sourceKey = item.sourceKey
                )
                downloadProgressRepository.persistState(item, DownloadItemState.PAUSED)
                workManager.cancelUniqueWork(workName(item))
                // 兼容升级前使用全局 cache:$bookId 命名的任务。
                workManager.cancelUniqueWork(CacheBookWork.ofId(item.bookId))
            }.onFailure {
                item.state = DownloadItemState.FAILED
            }
        }
    }

    fun onClickResume(item: DownloadItem) {
        if (item.type != DownloadType.CACHE || item.state != DownloadItemState.PAUSED) return
        item.state = DownloadItemState.RUNNING
        item.waitingReason = null
        item.currentChapterTitle = null
        item.errorMessage = null
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                downloadProgressRepository.persistState(item, DownloadItemState.RUNNING)
                bookRepository.resumeCachedBook(
                    bookId = item.bookId,
                    sourceId = item.sourceId,
                    sourceKey = item.sourceKey
                )
            }.onFailure {
                item.state = DownloadItemState.FAILED
                downloadProgressRepository.persistState(item, DownloadItemState.FAILED)
            }
        }
    }

    fun onClickRetry(item: DownloadItem) {
        if (item.type != DownloadType.CACHE || item.state != DownloadItemState.FAILED) return
        item.progress = 0f
        item.state = DownloadItemState.RUNNING
        item.waitingReason = null
        item.currentChapterTitle = null
        item.errorMessage = null
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                downloadProgressRepository.persistState(item, DownloadItemState.RUNNING)
                bookRepository.retryCachedBook(
                    bookId = item.bookId,
                    sourceId = item.sourceId,
                    sourceKey = item.sourceKey
                )
            }.onFailure {
                item.state = DownloadItemState.FAILED
                downloadProgressRepository.persistState(item, DownloadItemState.FAILED)
            }
        }
    }

    private fun workName(item: DownloadItem): String = when (item.type) {
        DownloadType.EPUB_EXPORT -> ExportBookToEPUBWork.ofId(item.bookId)
        DownloadType.CACHE -> CacheBookWork.ofId(item.sourceId, item.bookId)
    }

    fun loadLocalBooks() {
        viewModelScope.launch(Dispatchers.IO) {
            refreshLocalBooks()
        }
    }

    fun setLocalBookSort(sort: LocalBookSort) {
        localBookManagerUiState.sort = sort
    }

    fun setLocalBookReverse(reverse: Boolean) {
        localBookManagerUiState.sortReverse = reverse
    }

    fun enterLocalBookSelection(id: String? = null) {
        localBookManagerUiState.isSelecting = true
        localBookManagerUiState.selectedIds = buildSet {
            addAll(localBookManagerUiState.selectedIds)
            if (id != null) add(id)
        }
    }

    fun exitLocalBookSelection() {
        localBookManagerUiState.isSelecting = false
        localBookManagerUiState.selectedIds = emptySet()
    }

    fun toggleLocalBookSelect(id: String) {
        localBookManagerUiState.selectedIds = localBookManagerUiState.selectedIds.toMutableSet().apply {
            if (!add(id)) remove(id)
        }
        localBookManagerUiState.isSelecting = true
    }

    fun selectAllLocalBooks() {
        localBookManagerUiState.isSelecting = true
        localBookManagerUiState.selectedIds = localBookManagerUiState.bookList.map { it.id }.toSet()
    }

    fun deleteSelected() {
        viewModelScope.launch(Dispatchers.IO) {
            val count = deleteSelectedLocalBooks()
            if (count > 0) _clearedItemsFlow.emit(count)
        }
    }

    fun clearOrphanedData() {
        viewModelScope.launch(Dispatchers.IO) {
            val count = clearOrphanedDataItems()
            if (count > 0) _clearedItemsFlow.emit(count)
        }
    }

    fun clearBookData(bookId: String, targets: List<LocalBookClearTarget>) {
        viewModelScope.launch(Dispatchers.IO) {
            val count = clearBookDataItems(bookId, targets)
            if (count > 0) _clearedItemsFlow.emit(count)
        }
    }

    suspend fun deleteSelectedLocalBooks(): Int {
        val ids = localBookManagerUiState.selectedIds.toList()
        if (ids.isEmpty()) return 0
        localBookManagerUiState.isDeleting = true
        val chapterIds = database.bookVolumesDao()
            .getVolumeEntitiesByBookIds(ids)
            .flatMap { it.chapterIds }
            .distinct()
        ids.forEach { bookRepository.clearCachedBook(it) }
        database.withTransaction {
            database.chapterContentDao().deleteByBookIds(ids)
            if (chapterIds.isNotEmpty()) {
                database.chapterContentDao().deleteLegacyByIds(chapterIds)
            }
            database.bookInformationDao().deleteByIds(ids)
        }
        storageUsageRepository.invalidateSnapshot()
        withContext(Dispatchers.Main) {
            localBookManagerUiState.isDeleting = false
            localBookManagerUiState.isSelecting = false
            localBookManagerUiState.selectedIds = emptySet()
        }
        refreshLocalBooks()
        return ids.size
    }

    suspend fun clearOrphanedDataItems(): Int {
        val volumeEntities = database.bookVolumesDao().getAllVolumeEntities()
        val linkedBookIds = volumeEntities.map { it.bookId }.toSet()
        val linkedChapterIds = volumeEntities
            .flatMap { it.chapterIds }
            .toSet()
        val linkedChapterKeys = volumeEntities
            .flatMap { volume -> volume.chapterIds.map { volume.bookId to it } }
            .toSet()
        val orphanChapterInfoIds = database.bookVolumesDao()
            .getAllChapterInformationEntities()
            .map { it.id }
            .filterNot(linkedChapterIds::contains)
        val orphanChapterContentEntities = database.chapterContentDao()
            .getAllEntities()
            .filter { entity ->
                if (entity.bookId.isBlank()) entity.id !in linkedChapterIds
                else entity.bookId to entity.id !in linkedChapterKeys
            }
        val orphanDownloadEntities = database.chapterDownloadDao()
            .getAll()
            .filter { entity -> entity.bookId to entity.chapterId !in linkedChapterKeys }
        val orphanChapterImageKeys = buildSet {
            orphanChapterContentEntities.forEach { entity ->
                add(Triple(entity.sourceId, entity.bookId, entity.id))
            }
            orphanDownloadEntities.forEach { entity ->
                add(Triple(entity.sourceId, entity.bookId, entity.chapterId))
            }
        }
        val orphanBookImageKeys = buildSet {
            orphanChapterImageKeys
                .filter { (_, bookId, _) -> bookId.isNotBlank() && bookId !in linkedBookIds }
                .forEach { (sourceId, bookId, _) -> add(sourceId to bookId) }
        }

        database.withTransaction {
            if (orphanChapterInfoIds.isNotEmpty()) {
                database.bookVolumesDao().deleteChapterInformationByIds(orphanChapterInfoIds)
            }
            orphanChapterContentEntities.forEach { entity ->
                database.chapterContentDao().delete(entity.sourceId, entity.bookId, entity.id)
            }
            orphanDownloadEntities.forEach { entity ->
                database.chapterDownloadDao().delete(
                    sourceId = entity.sourceId,
                    bookId = entity.bookId,
                    chapterId = entity.chapterId
                )
            }
        }
        orphanChapterImageKeys.forEach { (sourceId, bookId, chapterId) ->
            offlineContentCache.deleteChapterImages(sourceId, bookId, chapterId)
        }
        orphanBookImageKeys.forEach { (sourceId, bookId) ->
            offlineContentCache.deleteBookImages(sourceId, bookId)
        }
        storageUsageRepository.invalidateSnapshot()
        refreshLocalBooks()
        return orphanChapterInfoIds.size + orphanChapterContentEntities.size + orphanDownloadEntities.size
    }

    suspend fun clearBookDataItems(bookId: String, targets: List<LocalBookClearTarget>): Int {
        if (targets.isEmpty()) return 0

        val volumeEntities = database.bookVolumesDao().getVolumeEntitiesByBookId(bookId)
        val chapterIds = volumeEntities
            .flatMap { it.chapterIds }
            .distinct()
        val targetSet = targets.toSet()
        val hasReadingRecord = database.userReadingDataDao().getEntity(bookId) != null
        val chapterContentCount = if (LocalBookClearTarget.ChapterContent in targetSet) {
            database.chapterContentDao().getAllEntities().count { entity ->
                entity.bookId == bookId ||
                        (entity.sourceId == ChapterContentEntity.LEGACY_SOURCE_ID &&
                                entity.bookId == ChapterContentEntity.LEGACY_BOOK_ID &&
                                entity.id in chapterIds)
            }
        } else {
            0
        }

        if (LocalBookClearTarget.ChapterContent in targetSet) {
            bookRepository.clearCachedChapters(bookId, chapterIds)
        }
        database.withTransaction {
            if (LocalBookClearTarget.VolumeAndChapterIndex in targetSet) {
                if (chapterIds.isNotEmpty()) {
                    database.bookVolumesDao().deleteChapterInformationByIds(chapterIds)
                }
                database.bookVolumesDao().deleteByBookIds(listOf(bookId))
            }
            if (LocalBookClearTarget.ChapterContent in targetSet) {
                database.chapterContentDao().deleteByBookIds(listOf(bookId))
                if (chapterIds.isNotEmpty()) {
                    database.chapterContentDao().deleteLegacyByIds(chapterIds)
                }
            }
            if (LocalBookClearTarget.ReadingRecord in targetSet && hasReadingRecord) {
                database.userReadingDataDao().deleteByIds(listOf(bookId))
            }
        }

        storageUsageRepository.invalidateSnapshot()
        refreshLocalBooks()

        var clearedCount = 0
        if (LocalBookClearTarget.VolumeAndChapterIndex in targetSet) {
            clearedCount += volumeEntities.size + chapterIds.size
        }
        clearedCount += chapterContentCount
        if (LocalBookClearTarget.ReadingRecord in targetSet && hasReadingRecord) {
            clearedCount += 1
        }
        return clearedCount
    }

    private suspend fun refreshLocalBooks() {
        withContext(Dispatchers.Main) {
            localBookManagerUiState.isLoading = true
        }
        updateLocalBooks(storageUsageRepository.refreshSnapshot(), loading = false)
    }

    private suspend fun updateLocalBooks(snapshot: StorageUsageSnapshot, loading: Boolean) {
        val volumes = database.bookVolumesDao().getAllVolumeEntities()
        val readingData = database.userReadingDataDao().getAll()
        val bookReadingBytesMap = database.storageStatsDao()
            .getUserReadingBytes()
            .associate { it.id to it.bytes }
        val bookChapterCountMap = volumes.groupBy { it.bookId }
            .mapValues { (_, list) -> list.sumOf { it.chapterIds.size } }
        val bookVolumeCountMap = volumes.groupBy { it.bookId }
            .mapValues { (_, list) -> list.size }
        val bookLastReadTimeMap = readingData.associate { it.id to it.lastReadTime }

        val bookList = snapshot.books.map { usage ->
            val readingRecordBytes = bookReadingBytesMap[usage.bookId] ?: 0L
            LocalBookItem(
                id = usage.bookId,
                bookInformationFlow = bookRepository.getBookInformationFlow(usage.bookId),
                size = usage.totalBytes + readingRecordBytes,
                chapterCount = bookChapterCountMap[usage.bookId] ?: 0,
                volumeCount = bookVolumeCountMap[usage.bookId] ?: 0,
                lastReadTime = bookLastReadTimeMap[usage.bookId],
                bookInformationBytes = usage.bookInformationBytes,
                volumeBytes = usage.volumeBytes,
                chapterInformationBytes = usage.chapterInformationBytes,
                chapterContentBytes = usage.chapterContentBytes,
                offlineContentBytes = usage.offlineContentBytes,
                readingRecordBytes = readingRecordBytes
            )
        }.filter { it.size > 0L }
        val retainedSelectedIds = localBookManagerUiState.selectedIds.intersect(bookList.map { it.id }.toSet())

        localBookManagerUiState.isLoading = loading
        localBookManagerUiState.bookList = bookList
        localBookManagerUiState.selectedIds = retainedSelectedIds
        localBookManagerUiState.isSelecting = localBookManagerUiState.isSelecting && retainedSelectedIds.isNotEmpty()
    }
}
