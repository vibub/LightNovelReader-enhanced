package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import indi.dmzz_yyhyy.lightnovelreader.data.book.BookRepository
import indi.dmzz_yyhyy.lightnovelreader.data.content.ContentComponentRepository
import indi.dmzz_yyhyy.lightnovelreader.data.statistics.ReadingStatsUpdate
import indi.dmzz_yyhyy.lightnovelreader.data.statistics.StatsRepository
import indi.dmzz_yyhyy.lightnovelreader.data.userdata.UserDataRepository
import indi.dmzz_yyhyy.lightnovelreader.data.web.WebBookDataSourceProvider
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.LinovelibConstants
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.book.targetLinovelibChapterPageId
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.comment.LinovelibChapterCommentRepository
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.sync.LinovelibBookmarkRepository
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.sync.LinovelibRemoteBookmarkResult
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.sync.LinovelibSyncRepository
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.ContentViewModel
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.flip.FlipPageContentViewModel
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.scroll.ScrollContentViewModel
import indi.dmzz_yyhyy.lightnovelreader.ui.components.preloadReaderImageHeight
import io.nightfish.lightnovelreader.api.content.component.ImageComponentData
import io.nightfish.lightnovelreader.api.userdata.UserDataPath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.time.LocalDateTime
import kotlin.time.Duration.Companion.seconds
import javax.inject.Inject
import kotlin.math.roundToInt

data class ReadingProgressSnapshot(
    val bookId: String,
    val chapterId: String,
    val chapterTitle: String,
    val progress: Float,
    val restoreAnchor: String? = null
)

private fun scrollReadingAnchorPath(bookId: String, chapterId: String): String =
    UserDataPath.Reader.ScrollRestoreAnchor.chapter(bookId, chapterId)

@HiltViewModel
class ReaderViewModel @Inject constructor(
    private val statsRepository: StatsRepository,
    private val bookRepository: BookRepository,
    private val userDataRepository: UserDataRepository,
    val contentComponentRepository: ContentComponentRepository,
    private val linovelibBookmarkRepository: LinovelibBookmarkRepository,
    private val linovelibSyncRepository: LinovelibSyncRepository,
    linovelibChapterCommentRepository: LinovelibChapterCommentRepository,
    private val webBookDataSourceProvider: WebBookDataSourceProvider,
    @param:ApplicationContext private val applicationContext: Context
) : ViewModel() {
    val settingState = SettingState(userDataRepository, viewModelScope)
    private var contentViewModel: ContentViewModel by mutableStateOf(ContentViewModel.empty)
    private val _uiState = MutableReaderScreenUiState(contentViewModel.uiState)
    val uiState: ReaderScreenUiState = _uiState
    val imageHeader: Map<String, String>
        get() = webBookDataSourceProvider.default.imageHeader
    private val chapterCommentsController = ChapterCommentsController(
        coroutineScope = viewModelScope,
        dataSource = linovelibChapterCommentRepository,
        nowMillis = SystemClock::elapsedRealtime,
        onStateChanged = { _uiState.chapterCommentsUiState = it }
    )
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
            if (!isLinovelib || chapterCommentsController.state.context?.bookId != value) {
                chapterCommentsController.dismiss()
            }
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
    private var restoreProgressOnNextContentViewModelChange = true
    val coroutineScope = CoroutineScope(Dispatchers.IO)

    private fun scrollImagePreloadWidthPx(): Int = applicationContext.resources.displayMetrics.widthPixels.coerceAtLeast(1)

    private suspend fun preloadScrollImageComponentHeight(data: ImageComponentData, widthPx: Int): Int? {
        val imageHeightPx = preloadReaderImageHeight(
            context = applicationContext,
            imageUri = data.uri,
            widthPx = widthPx,
            header = webBookDataSourceProvider.default.imageHeader
        ) ?: return null
        val density = applicationContext.resources.displayMetrics.density
        val verticalPaddingPx = ((data.topPaddingDp + data.bottomPaddingDp) * density).roundToInt()
        return imageHeightPx + verticalPaddingPx
    }

    init {
        _uiState.chapterCommentsUiState = chapterCommentsController.state
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
                    contentViewModel.changeChapter(
                        chapterId,
                        restoreProgress = restoreProgressOnNextContentViewModelChange
                    )
                    restoreProgressOnNextContentViewModelChange = true
                    _uiState.contentUiState = contentViewModel.uiState
                }
                else if (!it && contentViewModel !is ScrollContentViewModel) {
                    contentViewModel = ScrollContentViewModel(
                        bookRepository = bookRepository,
                        coroutineScope = viewModelScope,
                        settingState = settingState,
                        updateReadingProgress = ::saveReadingProgress,
                        contentComponentRepository = contentComponentRepository,
                        loadReadingAnchor = ::loadScrollReadingAnchor,
                        imagePreloadWidth = ::scrollImagePreloadWidthPx,
                        preloadImageComponentHeight = ::preloadScrollImageComponentHeight
                    )
                    contentViewModel.changeBookId(bookId)
                    contentViewModel.changeChapter(
                        chapterId,
                        restoreProgress = restoreProgressOnNextContentViewModelChange
                    )
                    restoreProgressOnNextContentViewModelChange = true
                    _uiState.contentUiState = contentViewModel.uiState
                }
            }
        }
    }

    fun prevChapter() = contentViewModel.loadLastChapter()

    fun nextChapter() = contentViewModel.loadNextChapter()

    fun changeChapter(chapterId: String, restoreProgress: Boolean = true) {
        this.chapterId = chapterId
        restoreProgressOnNextContentViewModelChange = restoreProgress
        val hasContentViewModel = contentViewModel !== ContentViewModel.empty
        contentViewModel.changeChapter(chapterId, restoreProgress)
        if (hasContentViewModel) restoreProgressOnNextContentViewModelChange = true
    }

    fun selectChapterFromReaderCatalog(chapterId: String) {
        changeChapter(chapterId, restoreProgress = true)
    }

    fun openChapterComments(context: ChapterEndContext) {
        if (!_uiState.isLinovelibSource || context.bookId != bookId) return
        chapterCommentsController.open(context)
    }

    fun dismissChapterComments() {
        chapterCommentsController.dismiss()
    }

    fun selectChapterCommentTab(tab: ChapterCommentTab) {
        chapterCommentsController.selectTab(tab)
    }

    fun loadNextChapterComments() {
        chapterCommentsController.loadNextPage()
    }

    fun retryChapterComments(tab: ChapterCommentTab) {
        when (tab) {
            ChapterCommentTab.Hot -> chapterCommentsController.retryHot()
            ChapterCommentTab.All -> chapterCommentsController.retryAll()
        }
    }

    fun bookmarkCurrentChapter(): Boolean {
        if (!_uiState.bookmarkUiState.isAvailable || bookId.isBlank()) {
            showBookmarkToast(success = false, message = "当前数据源不可添加书签")
            return false
        }
        val chapter = _uiState.contentUiState.readingChapterContent
        if (chapter.id.isBlank()) {
            showBookmarkToast(success = false, message = "当前章节不可添加书签")
            return false
        }
        val currentBookId = bookId
        val localChapterId = chapter.id.substringBefore('_')
        val localReadingProgress = _uiState.contentUiState.readingProgress
        val targetWebChapterId = currentLinovelibWebChapterId().ifBlank { chapter.id }
        showToast("正在添加书签…")
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                withTimeout(8.seconds) {
                    linovelibSyncRepository.syncBookmarkToRemote(
                        bookId = currentBookId,
                        chapterPageId = targetWebChapterId
                    )
                }
            }.getOrElse { throwable ->
                if (throwable is TimeoutCancellationException) {
                    showToast("添加书签超时，请稍后重试")
                    return@launch
                }
                LinovelibRemoteBookmarkResult(
                    success = false,
                    message = throwable.message ?: throwable.javaClass.simpleName
                )
            }
            if (result.success) {
                linovelibBookmarkRepository.upsertRemoteBookmark(
                    bookId = currentBookId,
                    chapterId = localChapterId,
                    chapterTitle = chapter.title,
                    resolved = true
                )
                saveReadingProgress(
                    ReadingProgressSnapshot(
                        bookId = currentBookId,
                        chapterId = localChapterId,
                        chapterTitle = chapter.title,
                        progress = localReadingProgress
                    )
                )
            }
            showBookmarkToast(result.success, result.message)
        }
        return true
    }

    private fun showBookmarkToast(success: Boolean, message: String = "") {
        val toastMessage = if (success) {
            "添加书签成功"
        } else {
            "添加书签失败" + message.takeIf { it.isNotBlank() }?.let { "：$it" }.orEmpty()
        }
        showToast(toastMessage)
    }

    private fun showToast(message: String) {
        viewModelScope.launch(Dispatchers.Main) {
            Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    fun currentLinovelibWebChapterId(): String {
        val contentUiState = _uiState.contentUiState
        val chapter = contentUiState.readingChapterContent
        if (chapter.id.isBlank()) return ""
        return chapter.content.targetLinovelibChapterPageId(
            fallbackChapterId = chapter.id,
            readingProgress = contentUiState.readingProgress
        )
    }

    private fun loadScrollReadingAnchor(bookId: String, chapterId: String): String? {
        if (bookId.isBlank() || chapterId.isBlank()) return null
        return userDataRepository.stringUserData(scrollReadingAnchorPath(bookId, chapterId)).get()
    }

    private fun saveReadingProgress(snapshot: ReadingProgressSnapshot) {
        if (snapshot.progress.isNaN() || snapshot.bookId.isBlank() || snapshot.chapterId.isBlank()) return
        if (snapshot.progress <= 0f && snapshot.restoreAnchor.isNullOrBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            snapshot.restoreAnchor
                ?.takeIf { it.isNotBlank() }
                ?.let { userDataRepository.stringUserData(scrollReadingAnchorPath(snapshot.bookId, snapshot.chapterId)).set(it) }
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