package indi.dmzz_yyhyy.lightnovelreader.ui.home.bookshelf.home

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.github.michaelbull.result.onErr
import com.github.michaelbull.result.onOk
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import indi.dmzz_yyhyy.lightnovelreader.data.book.BookRepository
import indi.dmzz_yyhyy.lightnovelreader.data.bookshelf.BookshelfRepository
import indi.dmzz_yyhyy.lightnovelreader.data.userdata.UserDataRepository
import indi.dmzz_yyhyy.lightnovelreader.data.work.ImportDataWork
import indi.dmzz_yyhyy.lightnovelreader.data.work.SaveBookshelfWork
import indi.dmzz_yyhyy.lightnovelreader.ui.home.bookshelf.BookshelfCardSnapshot
import indi.dmzz_yyhyy.lightnovelreader.ui.home.bookshelf.lastChapterTitleOrNull
import indi.dmzz_yyhyy.lightnovelreader.ui.home.bookshelf.toBookshelfUiState
import io.nightfish.lightnovelreader.api.bookshelf.BookshelfBookMetadata
import io.nightfish.lightnovelreader.api.bookshelf.BookshelfSortType
import io.nightfish.lightnovelreader.api.userdata.UserDataPath
import io.nightfish.lightnovelreader.api.web.WebDataSourcePriority
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.Collections
import javax.inject.Inject

@HiltViewModel
class BookshelfHomeViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val bookshelfRepository: BookshelfRepository,
    private val bookRepository: BookRepository,
    userDataRepository: UserDataRepository,
    private val workManager: WorkManager,
) : ViewModel() {
    private val _uiState = MutableBookshelfHomeUiState(
        changePage = ::changePage,
        changeSortType = ::changeSortType,
        changeSortReversed = ::changeSortReversed,
        changeBookSelectState = ::changeBookSelectState,
        enableReorderMode = ::enableReorderMode,
        disableReorderMode = ::disableReorderMode,
        moveBook = ::moveBook,
        enableBookshelfReorderMode = ::enableBookshelfReorderMode,
        disableBookshelfReorderMode = ::disableBookshelfReorderMode,
        moveBookshelf = ::moveBookshelf,
        onEnableSelectMode = ::enableSelectMode,
        onDisableSelectMode = ::disableSelectMode,
        onSelectAll = ::selectAllBooks,
        onPin = ::pinSelectedBooks,
        onRemove = ::removeSelectedBooks,
        saveAllBookshelfJsonData = ::saveAllBookshelf,
        saveBookshelfJsonData = ::saveThisBookshelf,
        importBookshelf = ::importBookshelf,
        clearToast = ::clearToast,
    )
    val uiState: BookshelfHomeUiState = _uiState
    private val bookshelfOrderUserData = userDataRepository.intListUserData(UserDataPath.BookshelfOrder.path)
    private val bookMetadataFlows = mutableMapOf<String, Flow<BookshelfBookMetadata?>>()
    private val cardSnapshots = mutableMapOf<String, MutableStateFlow<BookshelfCardSnapshot>>()
    private val detailRefreshJobs = mutableMapOf<String, Job>()
    private val volumeRefreshJobs = mutableMapOf<String, Job>()
    private val locallyLoadedBookIds = mutableSetOf<String>()
    private val refreshedBookIds = Collections.synchronizedSet(mutableSetOf<String>())
    private val loadedVolumeIds = Collections.synchronizedSet(mutableSetOf<String>())
    private val prefetchSemaphore = Semaphore(2)
    @Volatile
    private var knownBookshelfBookIds = emptySet<String>()
    private var visibleWindow = BookshelfVisibleWindow()
    private var sortRequestedBookIds = emptySet<String>()
    private var loadJob: Job? = null

    internal val dataSources = BookshelfHomeDataSources(
        cardSnapshot = ::getCardSnapshot,
        metadataFlow = ::getBookMetadataFlow,
        updateVisibleWindow = ::updateVisibleWindow,
        requestBookInformation = ::requestBookInformation,
    )

    private fun getCardSnapshot(id: String): StateFlow<BookshelfCardSnapshot> =
        synchronized(cardSnapshots) {
            cardSnapshots.getOrPut(id) { MutableStateFlow(BookshelfCardSnapshot()) }
        }

    private fun getMutableCardSnapshot(id: String): MutableStateFlow<BookshelfCardSnapshot> =
        synchronized(cardSnapshots) {
            cardSnapshots.getOrPut(id) { MutableStateFlow(BookshelfCardSnapshot()) }
        }

    private fun getBookMetadataFlow(id: String): Flow<BookshelfBookMetadata?> =
        bookMetadataFlows.getOrPut(id) {
            bookshelfRepository.getBookshelfBookMetadataFlow(id)
                .shareIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
                    replay = 1
                )
        }

    private fun updateBookshelfBookIds(bookIds: Set<String>) {
        val removedIds = knownBookshelfBookIds - bookIds
        knownBookshelfBookIds = bookIds
        removedIds.forEach(::clearBookSnapshot)

        val idsToLoad = bookIds - locallyLoadedBookIds
        if (idsToLoad.isEmpty()) return
        locallyLoadedBookIds += idsToLoad
        viewModelScope.launch(Dispatchers.IO) {
            val localInformation = bookRepository.getLocalBookshelfBookInformation(idsToLoad.toList())
            idsToLoad.forEach { id ->
                if (id !in knownBookshelfBookIds) return@forEach
                val information = localInformation[id]
                if (information != null) {
                    getMutableCardSnapshot(id).update { snapshot ->
                        snapshot.copy(
                            bookInformation = information,
                            loading = false,
                            error = null,
                        )
                    }
                }
            }
        }
    }

    private fun clearBookSnapshot(id: String) {
        synchronized(detailRefreshJobs) { detailRefreshJobs.remove(id) }?.cancel()
        synchronized(volumeRefreshJobs) { volumeRefreshJobs.remove(id) }?.cancel()
        synchronized(cardSnapshots) { cardSnapshots.remove(id) }
        bookMetadataFlows.remove(id)
        locallyLoadedBookIds.remove(id)
        refreshedBookIds.remove(id)
        loadedVolumeIds.remove(id)
    }

    private fun updateVisibleWindow(window: BookshelfVisibleWindow) {
        visibleWindow = window
        updateDetailRequests()
        updateVolumeRequests()
    }

    private fun requestBookInformation(bookIds: List<String>) {
        sortRequestedBookIds = bookIds.toSet()
        updateDetailRequests()
    }

    private fun updateDetailRequests() {
        val targetIds = (visibleWindow.detailBookIds + sortRequestedBookIds)
            .filterTo(linkedSetOf()) { it in knownBookshelfBookIds }
        synchronized(detailRefreshJobs) {
            detailRefreshJobs.keys
                .filterNot(targetIds::contains)
                .mapNotNull(detailRefreshJobs::remove)
                .forEach(Job::cancel)
        }
        targetIds.forEach(::startDetailRefresh)
    }

    private fun startDetailRefresh(id: String) {
        if (id in refreshedBookIds) return
        synchronized(detailRefreshJobs) {
            if (detailRefreshJobs[id]?.isActive == true) return
            val job = viewModelScope.launch(Dispatchers.IO, start = kotlinx.coroutines.CoroutineStart.LAZY) {
                var succeeded = false
                try {
                    prefetchSemaphore.withPermit {
                        bookRepository.getBookshelfBookInformationFlow(
                            id = id,
                            priority = WebDataSourcePriority.Low
                        ).collect { result ->
                            result.onOk { information ->
                                succeeded = true
                                getMutableCardSnapshot(id).update { snapshot ->
                                    snapshot.copy(
                                        bookInformation = information,
                                        loading = false,
                                        error = null,
                                    )
                                }
                            }.onErr { error ->
                                getMutableCardSnapshot(id).update { snapshot ->
                                    snapshot.copy(loading = false, error = error)
                                }
                            }
                        }
                    }
                    if (succeeded) refreshedBookIds += id
                } catch (exception: CancellationException) {
                    throw exception
                }
            }
            detailRefreshJobs[id] = job
            job.invokeOnCompletion {
                synchronized(detailRefreshJobs) {
                    if (detailRefreshJobs[id] === job) detailRefreshJobs.remove(id)
                }
            }
            job.start()
        }
    }

    private fun updateVolumeRequests() {
        val targetIds = visibleWindow.updatedBookIds
            .filterTo(linkedSetOf()) { it in knownBookshelfBookIds }
        synchronized(volumeRefreshJobs) {
            volumeRefreshJobs.keys
                .filterNot(targetIds::contains)
                .mapNotNull(volumeRefreshJobs::remove)
                .forEach(Job::cancel)
        }
        targetIds.forEach(::startVolumeRefresh)
    }

    private fun startVolumeRefresh(id: String) {
        if (id in loadedVolumeIds) return
        synchronized(volumeRefreshJobs) {
            if (volumeRefreshJobs[id]?.isActive == true) return
            val job = viewModelScope.launch(Dispatchers.IO, start = kotlinx.coroutines.CoroutineStart.LAZY) {
                var succeeded = false
                try {
                    prefetchSemaphore.withPermit {
                        bookRepository.getBookVolumesFlow(
                            id = id,
                            priority = WebDataSourcePriority.Low
                        ).collect { result ->
                            result.onOk {
                                succeeded = true
                                val title = result.lastChapterTitleOrNull()
                                getMutableCardSnapshot(id).update { snapshot ->
                                    snapshot.copy(
                                        lastUpdatedChapterTitle = mergeLatestChapterTitle(
                                            previousTitle = snapshot.lastUpdatedChapterTitle,
                                            requestedTitle = title,
                                            requestSucceeded = true,
                                        )
                                    )
                                }
                            }.onErr {
                                getMutableCardSnapshot(id).update { snapshot ->
                                    snapshot.copy(
                                        lastUpdatedChapterTitle = mergeLatestChapterTitle(
                                            previousTitle = snapshot.lastUpdatedChapterTitle,
                                            requestedTitle = null,
                                            requestSucceeded = false,
                                        )
                                    )
                                }
                            }
                        }
                    }
                    if (succeeded) loadedVolumeIds += id
                } catch (exception: CancellationException) {
                    throw exception
                }
            }
            volumeRefreshJobs[id] = job
            job.invokeOnCompletion {
                synchronized(volumeRefreshJobs) {
                    if (volumeRefreshJobs[id] === job) volumeRefreshJobs.remove(id)
                }
            }
            job.start()
        }
    }

    fun load() {
        if (loadJob?.isActive == true) return

        loadJob = viewModelScope.launch(Dispatchers.IO) {
            val bookshelfIdsFlow = bookshelfRepository.getAllBookshelvesFlow()
            val savedOrderFlow = bookshelfOrderUserData.getFlowWithDefault(emptyList())
            combine(bookshelfIdsFlow, savedOrderFlow) { bookshelves, savedOrder ->
                val stableIndexMap = savedOrder.withIndex().associate { it.value to it.index }
                bookshelves.sortedBy {
                    stableIndexMap[it.id] ?: Int.MAX_VALUE
                }
            }.map { bookshelves ->
                bookshelves.map { it.toBookshelfUiState() }
            }.collect { bookshelfUiStates ->
                if (_uiState.selectedBookshelf == null)
                    bookshelfUiStates.getOrNull(0)?.let {
                        changePage(it.id)
                    }
                _uiState.bookshelfList = bookshelfUiStates
                updateBookshelfBookIds(
                    bookshelfUiStates.flatMapTo(linkedSetOf()) { it.allBookIds }
                )
            }
        }
    }

    fun changePage(bookshelfId: Int) {
        _uiState.selectedBookshelfId = bookshelfId
        visibleWindow = BookshelfVisibleWindow()
        sortRequestedBookIds = emptySet()
        updateDetailRequests()
        updateVolumeRequests()
    }

    fun changeSortType(sortType: BookshelfSortType) {
        viewModelScope.launch(Dispatchers.IO) {
            bookshelfRepository.updateBookshelf(_uiState.selectedBookshelfId) {
                it.copy(
                    sortType = sortType
                )
            }
        }
    }

    fun changeSortReversed(sortReversed: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            bookshelfRepository.updateBookshelf(_uiState.selectedBookshelfId) {
                it.copy(
                    sortReversed = sortReversed
                )
            }
        }
    }

    fun enableReorderMode(bookshelfId: Int = _uiState.selectedBookshelfId) {
        val bookshelf = _uiState.bookshelfList.firstOrNull { it.id == bookshelfId } ?: return
        _uiState.selectedBookshelfId = bookshelfId
        if (bookshelf.sortType != BookshelfSortType.Default) return
        _uiState.reorderBookIds.clear()
        _uiState.reorderBookIds.addAll(bookshelf.allBookIds)
        _uiState.reorderMode = true
    }

    fun disableReorderMode() {
        if (_uiState.reorderMode) {
            val reorderedIds = _uiState.reorderBookIds.toList()
            viewModelScope.launch(Dispatchers.IO) {
                bookshelfRepository.updateBookshelf(_uiState.selectedBookshelfId) { oldBookshelf ->
                    oldBookshelf.copy(
                        allBookIds = reorderedIds
                    )
                }
            }
        }
        _uiState.reorderMode = false
        _uiState.reorderBookIds.clear()
    }

    fun moveBook(fromIndex: Int, toIndex: Int) {
        if (fromIndex == toIndex) return
        if (fromIndex !in _uiState.reorderBookIds.indices || toIndex !in _uiState.reorderBookIds.indices) return
        val item = _uiState.reorderBookIds.removeAt(fromIndex)
        _uiState.reorderBookIds.add(toIndex, item)
    }

    fun enableBookshelfReorderMode() {
        _uiState.reorderBookshelfIds.clear()
        _uiState.reorderBookshelfIds.addAll(_uiState.bookshelfList.map { it.id })
        _uiState.reorderBookshelfMode = true
    }

    fun disableBookshelfReorderMode() {
        disableBookshelfReorderMode(_uiState.reorderBookshelfIds.toList())
    }

    fun disableBookshelfReorderMode(reorderedIds: List<Int>) {
        viewModelScope.launch(Dispatchers.IO) {
            if (_uiState.reorderBookshelfMode) {
                _uiState.reorderBookshelfIds.clear()
                _uiState.reorderBookshelfIds.addAll(reorderedIds)
                bookshelfOrderUserData.set(reorderedIds)
            }
            _uiState.reorderBookshelfMode = false
            _uiState.reorderBookshelfIds.clear()
        }
    }

    fun moveBookshelf(fromIndex: Int, toIndex: Int) {
        if (fromIndex == toIndex) return
        if (fromIndex !in _uiState.reorderBookshelfIds.indices || toIndex !in _uiState.reorderBookshelfIds.indices) return
        val item = _uiState.reorderBookshelfIds.removeAt(fromIndex)
        _uiState.reorderBookshelfIds.add(toIndex, item)
    }

    fun enableSelectMode() {
        _uiState.selectMode = true
        _uiState.selectedBookIds.clear()
    }

    fun disableSelectMode() {
        _uiState.selectMode = false
        _uiState.selectedBookIds.clear()
    }

    fun changeBookSelectState(bookId: String) {
        if (_uiState.selectedBookIds.contains(bookId))
            _uiState.selectedBookIds.remove(bookId)
        else _uiState.selectedBookIds.add(bookId)
        if (_uiState.selectedBookIds.isEmpty()) disableSelectMode()
    }

    fun selectAllBooks() {
        val allBookIds = _uiState.selectedBookshelf?.allBookIds ?: return
        if (_uiState.selectedBookIds.size == allBookIds.size) {
            _uiState.selectedBookIds.clear()
            return
        }
        _uiState.selectedBookIds.clear()
        _uiState.selectedBookIds.addAll(allBookIds)
    }

    fun pinSelectedBooks() {
        viewModelScope.launch(Dispatchers.IO) {
            val pinnedBookIds = _uiState.selectedBookshelf?.pinnedBookIds ?: return@launch
            val newPinnedBooksIds = _uiState.selectedBookIds
                .filter { pinnedBookIds.contains(it) }
                .let { removeList ->
                    (pinnedBookIds + (_uiState.selectedBookIds))
                        .toMutableList()
                        .apply {
                            removeAll { removeList.contains(it) }
                        }
                }
                .distinct()

            bookshelfRepository.updateBookshelf(_uiState.selectedBookshelfId) {
                it.copy(
                    pinnedBookIds = newPinnedBooksIds
                )
            }
            disableSelectMode()
        }
    }


    fun removeSelectedBooks() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.selectedBookIds.forEach {
                bookshelfRepository.deleteBookFromBookshelf(
                    _uiState.selectedBookshelfId,
                    it
                )
            }
            _uiState.selectedBookIds.clear()
        }
    }

    @Suppress("UNUSED")
    fun markSelectedBooks(bookshelfIds: List<Int>) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.selectedBookIds.forEach { bookId ->
                bookshelfIds.forEach { bookshelfId ->
                    bookRepository.getBookInformationFlow(bookId).last().onOk {
                        bookshelfRepository.addBookIntoBookShelf(
                            bookshelfId,
                            it
                        )
                    }
                }
            }
            _uiState.selectedBookIds.clear()
            _uiState.selectMode = false
        }
    }

    fun saveAllBookshelf(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val workRequest = OneTimeWorkRequestBuilder<SaveBookshelfWork>()
                .setInputData(
                    workDataOf(
                        "uri" to uri.toString(),
                        "bookshelfId" to -1
                    )
                )
                .build()
            workManager.enqueueUniqueWork(
                uri.toString(),
                ExistingWorkPolicy.KEEP,
                workRequest
            )
            CoroutineScope(Dispatchers.Main).launch {
                workManager.getWorkInfoByIdFlow(workRequest.id).collect {
                    it ?: return@collect
                    when(it.state) {
                        WorkInfo.State.SUCCEEDED -> Toast.makeText(context, "导出成功", Toast.LENGTH_LONG).show()
                        WorkInfo.State.FAILED -> Toast.makeText(context, "导出失败", Toast.LENGTH_LONG).show()
                        else -> return@collect
                    }
                }
            }
        }
    }

    fun saveThisBookshelf(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val workRequest = OneTimeWorkRequestBuilder<SaveBookshelfWork>()
                .setInputData(
                    workDataOf(
                        "uri" to uri.toString(),
                        "bookshelfId" to uiState.selectedBookshelfId
                    )
                )
                .build()
            workManager.enqueueUniqueWork(
                uri.toString(),
                ExistingWorkPolicy.KEEP,
                workRequest
            )
            CoroutineScope(Dispatchers.Main).launch {
                workManager.getWorkInfoByIdFlow(workRequest.id).collect {
                    it ?: return@collect
                    when(it.state) {
                        WorkInfo.State.SUCCEEDED -> Toast.makeText(context, "导出成功", Toast.LENGTH_LONG).show()
                        WorkInfo.State.FAILED -> Toast.makeText(context, "导出失败", Toast.LENGTH_LONG).show()
                        else -> return@collect
                    }
                }
            }
        }
    }

    fun importBookshelf(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val workRequest = OneTimeWorkRequestBuilder<ImportDataWork>()
                .setInputData(
                    workDataOf(
                        "uri" to uri.toString(),
                    )
                )
                .build()
            workManager.enqueueUniqueWork(
                uri.toString(),
                ExistingWorkPolicy.KEEP,
                workRequest
            )
            workManager.getWorkInfoByIdFlow(workRequest.id).collect {
                it ?: return@collect
                when(it.state) {
                    WorkInfo.State.ENQUEUED -> return@collect
                    WorkInfo.State.RUNNING -> return@collect
                    WorkInfo.State.SUCCEEDED -> load()
                    WorkInfo.State.FAILED -> _uiState.toast = "文件损坏或格式错误，请检查后重试。"
                    WorkInfo.State.BLOCKED -> return@collect
                    WorkInfo.State.CANCELLED -> return@collect
                }
            }
        }
    }

    fun clearToast() {
        _uiState.toast = ""
    }
}
