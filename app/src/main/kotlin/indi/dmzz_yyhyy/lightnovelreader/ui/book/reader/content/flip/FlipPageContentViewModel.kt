package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.flip

import android.util.Log
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.snapshotFlow
import indi.dmzz_yyhyy.lightnovelreader.data.book.BookRepository
import indi.dmzz_yyhyy.lightnovelreader.data.content.ContentComponentRepository
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.ReadingProgressSnapshot
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.ContentViewModel
import io.nightfish.lightnovelreader.api.web.WebDataSourcePriority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.LocalDateTime

class FlipPageContentViewModel(
    val bookRepository: BookRepository,
    val coroutineScope: CoroutineScope,
    val updateReadingProgress: (ReadingProgressSnapshot) -> Unit,
    val contentComponentRepository: ContentComponentRepository
) : ContentViewModel {
    private var pendingRestoreProgress: Float? = null
    private var canPersistProgress = false
    private var restoredChapterId = ""
    private var collectProgressJob: Job? = null
    private var changeChapterJob: Job? = null
    private var restoreProgressJob: Job? = null
    private var requestedChapterId = ""
    private var prefetchedNextChapterKey = ""
    override val uiState: MutableFlipPageContentUiState = MutableFlipPageContentUiState(
        loadLastChapter = ::loadLastChapter,
        loadNextChapter = ::loadNextChapter,
        changeChapter = { changeChapter(it) },
        updatePageState = ::updatePagerState
    )

    init {
        coroutineScope.launch(Dispatchers.IO) {
            snapshotFlow { uiState.pagerState }.collect { pagerState ->
                collectProgressJob?.cancel()
                collectProgressJob = coroutineScope.launch(Dispatchers.IO) {
                    snapshotFlow { pagerState.settledPage }.collect {
                        if (!canPersistProgress || uiState.contentPageCount == 0) return@collect
                        val content = uiState.readingChapterContent
                        if (content.isEmpty() || content.id != requestedChapterId) return@collect
                        val progress = flipReadingProgress(
                            settledPage = it,
                            contentPageCount = uiState.contentPageCount
                        )
                        uiState.readingProgress = progress
                        updateReadingProgress(
                            ReadingProgressSnapshot(
                                bookId = uiState.bookId,
                                chapterId = content.id,
                                chapterTitle = content.title,
                                progress = progress
                            )
                        )
                    }
                }
            }
        }
    }

    fun updatePagerState(pagerState: PagerState, contentPageCount: Int) {
        uiState.contentPageCount = contentPageCount.coerceAtLeast(0)
        uiState.pagerState = pagerState
        tryRestorePagerPosition()
    }

    private fun tryRestorePagerPosition() {
        val progressToRestore = pendingRestoreProgress ?: return
        val pagerState = uiState.pagerState
        val contentPageCount = uiState.contentPageCount
        if (contentPageCount == 0 || requestedChapterId.isBlank() || restoredChapterId == requestedChapterId) return
        val recovered = progressToRestore.coerceIn(0f, 1f)
        restoredChapterId = requestedChapterId
        pendingRestoreProgress = null
        coroutineScope.launch {
            if (recovered > 0f) {
                pagerState.scrollToPage(
                    flipRestoreContentPage(
                        progress = recovered,
                        contentPageCount = contentPageCount
                    )
                )
            }
            if (restoredChapterId == requestedChapterId) {
                canPersistProgress = true
                uiState.readingProgress = recovered
            }
        }
    }

    override fun changeBookId(id: String) {
        uiState.bookId = id
        prefetchedNextChapterKey = ""
    }

    override fun loadNextChapter() {
        if (!uiState.readingChapterContent.hasNextChapter()) return
        changeChapter(
            id = uiState.readingChapterContent.nextChapter,
            restoreProgress = true
        )
    }

    override fun loadLastChapter() {
        if (!uiState.readingChapterContent.hasPrevChapter()) return
        changeChapter(
            id = uiState.readingChapterContent.lastChapter,
            restoreProgress = true
        )
    }

    override fun changeChapter(id: String, restoreProgress: Boolean) {
        if (id.isBlank()) {
            Log.e("FlipPageContentViewModel", "a id less than 0 was transferred")
            return
        }
        val targetChapterId = id
        requestedChapterId = targetChapterId
        prefetchedNextChapterKey = ""
        changeChapterJob?.cancel()
        restoreProgressJob?.cancel()
        canPersistProgress = false
        pendingRestoreProgress = null
        restoredChapterId = ""
        uiState.readingProgress = 0f
        uiState.contentPageCount = 0
        changeChapterJob = coroutineScope.launch {
            bookRepository.getChapterContentFlow(
                targetChapterId,
                uiState.bookId,
                WebDataSourcePriority.High
            ).collect { content ->
                if (content.isEmpty() || content.id != targetChapterId || requestedChapterId != targetChapterId) return@collect
                uiState.readingChapterContent = content
                uiState.contentComponentsMap[content.id] = contentComponentRepository.getContentDataFromJson(content.content).components
                bookRepository.updateUserReadingData(uiState.bookId) {
                    it.apply {
                        lastReadTime = LocalDateTime.now()
                        lastReadChapterId = targetChapterId
                        lastReadChapterTitle = content.title
                    }
                }
                if (content.hasNextChapter() && requestedChapterId == targetChapterId) {
                    val prefetchKey = "${uiState.bookId}/${content.nextChapter}"
                    if (prefetchedNextChapterKey != prefetchKey) {
                        prefetchedNextChapterKey = prefetchKey
                        bookRepository.prefetchChapterContent(
                            chapterId = content.nextChapter,
                            bookId = uiState.bookId,
                        )
                    }
                }
            }
        }
        restoreProgressJob = coroutineScope.launch(Dispatchers.IO) {
            val progress = if (restoreProgress) {
                bookRepository.getUserReadingData(uiState.bookId)
                    .currentChapterReadingProgressMap[targetChapterId] ?: 0f
            } else 0f
            if (requestedChapterId == targetChapterId) {
                pendingRestoreProgress = progress
                tryRestorePagerPosition()
            }
        }
    }
}