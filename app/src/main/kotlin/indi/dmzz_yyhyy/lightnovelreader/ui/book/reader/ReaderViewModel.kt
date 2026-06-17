package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import indi.dmzz_yyhyy.lightnovelreader.data.book.BookRepository
import indi.dmzz_yyhyy.lightnovelreader.data.content.ContentComponentRepository
import indi.dmzz_yyhyy.lightnovelreader.data.statistics.ReadingStatsUpdate
import indi.dmzz_yyhyy.lightnovelreader.data.statistics.StatsRepository
import indi.dmzz_yyhyy.lightnovelreader.data.userdata.UserDataRepository
import indi.dmzz_yyhyy.lightnovelreader.data.web.WebBookDataSourceProvider
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.LinovelibConstants
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.sync.LinovelibBookmarkRepository
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.ContentViewModel
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.flip.FlipPageContentViewModel
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.scroll.ScrollContentViewModel
import io.nightfish.lightnovelreader.api.userdata.UserDataPath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import javax.inject.Inject

data class ReadingProgressSnapshot(
    val bookId: String,
    val chapterId: String,
    val chapterTitle: String,
    val progress: Float
)

@HiltViewModel
class ReaderViewModel @Inject constructor(
    private val statsRepository: StatsRepository,
    private val bookRepository: BookRepository,
    userDataRepository: UserDataRepository,
    val contentComponentRepository: ContentComponentRepository,
    private val linovelibBookmarkRepository: LinovelibBookmarkRepository,
    private val webBookDataSourceProvider: WebBookDataSourceProvider
) : ViewModel() {
    val settingState = SettingState(userDataRepository, viewModelScope)
    private var contentViewModel: ContentViewModel by mutableStateOf(ContentViewModel.empty)
    private val _uiState = MutableReaderScreenUiState(contentViewModel.uiState)
    val uiState: ReaderScreenUiState = _uiState
    private val readingBookListUserData =
        userDataRepository.stringListUserData(UserDataPath.ReadingBooks.path)
    private var bookVolumesJob: Job? = null
    private var bookmarkJob: Job? = null
    var bookId = ""
        set(value) {
            field = value
            _uiState.bookId = value
            contentViewModel.changeBookId(value)
            addToReadingBook(value)
            viewModelScope.launch(Dispatchers.IO) {
                statsRepository.updateReadingStatistics(
                    ReadingStatsUpdate(
                        bookId = value,
                        readEventDelta = 1
                    )
                )
            }

            bookVolumesJob?.cancel()
            bookVolumesJob = viewModelScope.launch(Dispatchers.IO) {
                bookRepository.getBookVolumesFlow(value).collect { _uiState.bookVolumes = it }
            }
            bookmarkJob?.cancel()
            val isLinovelib = webBookDataSourceProvider.default.id == LinovelibConstants.SOURCE_ID
            _uiState.isLinovelibSource = isLinovelib
            _uiState.bookmarkUiState = ReaderBookmarkUiState(isAvailable = isLinovelib)
            if (isLinovelib) {
                bookmarkJob = viewModelScope.launch(Dispatchers.IO) {
                    linovelibBookmarkRepository.getBookmarkFlow(value).collect { bookmark ->
                        _uiState.bookmarkUiState = ReaderBookmarkUiState(
                            isAvailable = true,
                            chapterId = bookmark?.chapterId.orEmpty(),
                            chapterTitle = bookmark?.chapterTitle.orEmpty(),
                            syncState = bookmark?.syncState.orEmpty()
                        )
                    }
                }
            }
        }
    private var chapterId = ""
    val coroutineScope = CoroutineScope(Dispatchers.IO)

    init {
        viewModelScope.launch {
            settingState.isUsingFlipPageUserData.getFlowWithDefault(false).collect {
                if (it && contentViewModel !is FlipPageContentViewModel) {
                    contentViewModel = FlipPageContentViewModel(
                        bookRepository = bookRepository,
                        coroutineScope = viewModelScope,
                        updateReadingProgress = ::saveReadingProgress,
                        contentComponentRepository = contentComponentRepository
                    )
                    contentViewModel.changeBookId(bookId)
                    contentViewModel.changeChapter(chapterId, restoreProgress = true)
                    _uiState.contentUiState = contentViewModel.uiState
                }
                else if (!it && contentViewModel !is ScrollContentViewModel) {
                    contentViewModel = ScrollContentViewModel(
                        bookRepository = bookRepository,
                        coroutineScope = viewModelScope,
                        settingState = settingState,
                        updateReadingProgress = ::saveReadingProgress,
                        contentComponentRepository = contentComponentRepository
                    )
                    contentViewModel.changeBookId(bookId)
                    contentViewModel.changeChapter(chapterId, restoreProgress = true)
                    _uiState.contentUiState = contentViewModel.uiState
                }
            }
        }
    }

    fun prevChapter() = contentViewModel.loadLastChapter()

    fun nextChapter() = contentViewModel.loadNextChapter()

    fun changeChapter(chapterId: String, restoreProgress: Boolean = true) {
        this.chapterId = chapterId
        contentViewModel.changeChapter(chapterId, restoreProgress)
    }

    fun selectChapterFromReaderCatalog(chapterId: String) {
        changeChapter(chapterId, restoreProgress = false)
    }

    fun bookmarkCurrentChapter(): Boolean {
        if (!_uiState.bookmarkUiState.isAvailable || bookId.isBlank()) return false
        val chapter = _uiState.contentUiState.readingChapterContent
        if (chapter.id.isBlank()) return false
        val currentBookId = bookId
        viewModelScope.launch(Dispatchers.IO) {
            linovelibBookmarkRepository.upsertLocalBookmark(
                bookId = currentBookId,
                chapterId = chapter.id,
                chapterTitle = chapter.title
            )
        }
        return true
    }

    private fun saveReadingProgress(snapshot: ReadingProgressSnapshot) {
        if (snapshot.progress.isNaN() || snapshot.progress <= 0f ||
            snapshot.bookId.isBlank() || snapshot.chapterId.isBlank()
        ) return
        viewModelScope.launch(Dispatchers.IO) {
            val currentTime = LocalDateTime.now()

            bookRepository.updateUserReadingData(snapshot.bookId) { userReadingData ->
                Log.v(
                    "ReaderViewModel",
                    "${snapshot.bookId}/${snapshot.chapterId} Saving progress ${snapshot.progress}. (${snapshot.chapterTitle})"
                )
                userReadingData.apply {
                    lastReadTime = currentTime
                    lastReadChapterId = snapshot.chapterId
                    lastReadChapterTitle = snapshot.chapterTitle
                    userReadingData.updateChapterReadingProgress(snapshot.chapterId, snapshot.progress)
                    val total = if (snapshot.bookId == bookId) {
                        _uiState.bookVolumes.volumes.sumOf { it.chapters.size }
                    } else {
                        0
                    }
                    if (total > 0) {
                        readingProgress = (userReadingData.maxChapterReadingProgressMap.values.sum() / total).coerceIn(0f, 1f)
                    }
                }
            }
            val readingData = bookRepository.getUserReadingData(snapshot.bookId)
            if (readingData.readingProgress >= 1f) {
                statsRepository.markBookFinished(snapshot.bookId)
            }
        }
    }


    fun updateTotalReadingTime(bookId: String, totalReadingTime: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            bookRepository.updateUserReadingData(bookId) {
                it.apply {
                    lastReadTime = LocalDateTime.now()
                    totalReadTime = it.totalReadTime + totalReadingTime
                }
            }
        }
    }

    private fun addToReadingBook(bookId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            readingBookListUserData.update {
                val newList = it.toMutableList()
                if (it.contains(bookId))
                    newList.remove(bookId)
                newList.add(bookId)
                return@update newList
            }
        }
    }

    fun accumulateReadingTime(bookId: String, seconds: Int) {
        if (bookId.isBlank()) return
        coroutineScope.launch(Dispatchers.IO) {
            statsRepository.accumulateBookReadTime(bookId, seconds)
        }
    }
}