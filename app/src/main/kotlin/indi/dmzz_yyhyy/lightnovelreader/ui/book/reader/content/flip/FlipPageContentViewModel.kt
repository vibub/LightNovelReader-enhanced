package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.flip

import android.util.Log
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.snapshotFlow
import indi.dmzz_yyhyy.lightnovelreader.data.book.BookRepository
import indi.dmzz_yyhyy.lightnovelreader.data.content.ContentComponentRepository
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.ContentViewModel
import io.nightfish.lightnovelreader.api.web.WebDataSourcePriority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import kotlin.math.roundToInt

class FlipPageContentViewModel(
    val bookRepository: BookRepository,
    val coroutineScope: CoroutineScope,
    val updateReadingProgress: (String, Float) -> Unit,
    val contentComponentRepository: ContentComponentRepository
) : ContentViewModel {
    private var notRecoveredProgress = 0f
    private var collectProgressJob: Job? = null
    private var changeChapterJob: Job? = null
    private var restoreProgressJob: Job? = null
    private var requestedChapterId = ""
    override val uiState: MutableFlipPageContentUiState = MutableFlipPageContentUiState(
        loadLastChapter = ::loadLastChapter,
        loadNextChapter = ::loadNextChapter,
        changeChapter = ::changeChapter,
        updatePageState = ::updatePagerState
    )

    init {
        coroutineScope.launch(Dispatchers.IO) {
            snapshotFlow { uiState.pagerState }.collect { pagerState ->
                collectProgressJob?.cancel()
                collectProgressJob = coroutineScope.launch(Dispatchers.IO) {
                    snapshotFlow { pagerState.settledPage }.collect {
                        val progress = if (pagerState.pageCount == 0) 0f
                        else ((it + 1) / pagerState.pageCount.toFloat()).coerceIn(0f, 1f)
                        uiState.readingProgress = progress
                        updateReadingProgress(uiState.readingChapterContent.id, progress)
                    }
                }
            }
        }
    }

    fun updatePagerState(pagerState: PagerState) {
        uiState.pagerState = pagerState
        if (pagerState.pageCount == 0) return
        val progressToRestore = when {
            notRecoveredProgress > 0f -> notRecoveredProgress.also { notRecoveredProgress = 0f }
            uiState.readingProgress > 0f -> uiState.readingProgress
            else -> return
        }
        val recovered = progressToRestore.coerceIn(0f, 1f)
        coroutineScope.launch {
            val target = ((pagerState.pageCount * recovered).roundToInt() - 1)
                .coerceIn(0, pagerState.pageCount - 1)
            uiState.pagerState.scrollToPage(target)
        }
    }

    override fun changeBookId(id: String) {
        uiState.bookId = id
    }

    override fun loadNextChapter() {
        if (!uiState.readingChapterContent.hasNextChapter()) return
        changeChapter(
            id = uiState.readingChapterContent.nextChapter
        )
    }

    override fun loadLastChapter() {
        if (!uiState.readingChapterContent.hasPrevChapter()) return
        changeChapter(
            id = uiState.readingChapterContent.lastChapter
        )
    }

    override fun changeChapter(id: String) {
        if (id.isBlank()) {
            Log.e("FlipPageContentViewModel", "a id less than 0 was transferred")
            return
        }
        val targetChapterId = id
        requestedChapterId = targetChapterId
        changeChapterJob?.cancel()
        restoreProgressJob?.cancel()
        notRecoveredProgress = 0f
        uiState.readingProgress = 0f
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
                    bookRepository.getChapterContent(
                        chapterId = content.nextChapter,
                        bookId = uiState.bookId,
                    )
                }
            }
        }
        restoreProgressJob = coroutineScope.launch(Dispatchers.IO) {
            val progress = bookRepository.getUserReadingData(uiState.bookId)
                .currentChapterReadingProgressMap[targetChapterId] ?: 0f
            if (requestedChapterId == targetChapterId) {
                notRecoveredProgress = progress
            }
        }
    }
}