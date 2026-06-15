package indi.dmzz_yyhyy.lightnovelreader.ui.home.reading.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import indi.dmzz_yyhyy.lightnovelreader.data.book.BookRepository
import indi.dmzz_yyhyy.lightnovelreader.data.userdata.UserDataRepository
import io.nightfish.lightnovelreader.api.book.BookInformation
import io.nightfish.lightnovelreader.api.book.BookVolumes
import io.nightfish.lightnovelreader.api.book.UserReadingData
import io.nightfish.lightnovelreader.api.userdata.UserDataPath
import io.nightfish.lightnovelreader.api.web.WebDataSourcePriority
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class ChapterSheetUi(
    val bookId: String,
    val readingChapterId: String,
    val selectedVolumeId: String = ""
)

@HiltViewModel
class ReadingHomeViewModel @Inject constructor(
    private val bookRepository: BookRepository,
    userDataRepository: UserDataRepository
) : ViewModel() {

    private val readingBooksUserData =
        userDataRepository.stringListUserData(UserDataPath.ReadingBooks.path)

    var recentReadingBookIds: List<String> by mutableStateOf(listOf())
        private set

    private val _recentReadingBookInformationMap = mutableStateMapOf<String, BookInformation>()
    private val _recentReadingUserReadingDataMap = mutableStateMapOf<String, UserReadingData>()
    val recentReadingBookInformationMap: Map<String, BookInformation> = _recentReadingBookInformationMap
    val recentReadingUserReadingDataMap: Map<String, UserReadingData> = _recentReadingUserReadingDataMap

    private val loadingIds = mutableSetOf<String>()
    private val userReadingDataJobs = mutableMapOf<String, Job>()

    private val _bookVolumesMap = mutableStateMapOf<String, BookVolumes>()
    val bookVolumesMap: Map<String, BookVolumes> = _bookVolumesMap

    var chapterSheetUi by mutableStateOf<ChapterSheetUi?>(null)
        private set

    fun openChapters(bookId: String) {
        val userData = recentReadingUserReadingDataMap[bookId] ?: return
        chapterSheetUi = ChapterSheetUi(bookId, userData.lastReadChapterId)

        if (_bookVolumesMap.containsKey(bookId)) return
        viewModelScope.launch(Dispatchers.IO) {
            bookRepository.getBookVolumesFlow(bookId)
                .collect { volumes ->
                    _bookVolumesMap[bookId] = volumes
                    if (volumes.volumes.isNotEmpty()) cancel()
                }
        }
    }

    fun setVolume(volumeId: String) {
        chapterSheetUi = chapterSheetUi?.copy(selectedVolumeId = volumeId)
    }

    fun closeContents() {
        chapterSheetUi = null
    }

    init {
        viewModelScope.launch {
            readingBooksUserData.getFlowWithDefault(emptyList()).collect {
                val ids = it
                    .reversed()
                    .filter(String::isNotBlank)
                recentReadingBookIds = ids
                cleanupReadingDataObservers(ids.toSet())
            }
        }
    }

    private fun observeUserReadingData(bookId: String) {
        if (userReadingDataJobs[bookId]?.isActive == true) return
        userReadingDataJobs[bookId] = viewModelScope.launch {
            bookRepository.getUserReadingDataFlow(bookId).collect { userReadingData ->
                _recentReadingUserReadingDataMap[bookId] = userReadingData
            }
        }
    }

    private fun cleanupReadingDataObservers(activeBookIds: Set<String>) {
        val removedIds = userReadingDataJobs.keys - activeBookIds
        removedIds.forEach { bookId ->
            userReadingDataJobs.remove(bookId)?.cancel()
            _recentReadingUserReadingDataMap.remove(bookId)
            _bookVolumesMap.remove(bookId)
            _recentReadingBookInformationMap.remove(bookId)
        }
    }

    fun updateReadingBooks() {
        viewModelScope.launch(Dispatchers.IO) {
            val ids = readingBooksUserData
                .getOrDefault(emptyList())
                .reversed()
                .filter(String::isNotBlank)

            withContext(Dispatchers.Main) {
                recentReadingBookIds = ids
                cleanupReadingDataObservers(ids.toSet())
            }
        }
    }

    fun loadBookInfo(id: String) {
        observeUserReadingData(id)
        val hasInfo = _recentReadingBookInformationMap[id] != null

        val canLoad = synchronized(loadingIds) {
            if (loadingIds.contains(id)) {
                false
            } else {
                loadingIds.add(id)
                true
            }
        }
        if (!canLoad) return

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val info: BookInformation? =
                    if (!hasInfo) bookRepository.getStateBookInformation(id, viewModelScope)
                    else bookRepository.getStateBookInformation(id, viewModelScope, WebDataSourcePriority.Low)

                withContext(Dispatchers.Main) {
                    if (info != null) {
                        _recentReadingBookInformationMap[id] = info
                    }
                }
            } finally {
                synchronized(loadingIds) {
                    loadingIds.remove(id)
                }
            }
        }
    }


    fun removeFromReadingList(bookId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            readingBooksUserData.update {
                it.toMutableList().apply { remove(bookId) }
            }
        }
    }

    fun addToReadingList(bookId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            readingBooksUserData.update {
                it + listOf(bookId)
            }
        }
    }
}
