package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.flip

import android.util.Log
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.snapshotFlow
import com.github.michaelbull.result.get
import com.github.michaelbull.result.map
import com.github.michaelbull.result.onOk
import indi.dmzz_yyhyy.lightnovelreader.data.book.BookRepository
import indi.dmzz_yyhyy.lightnovelreader.data.content.ContentComponentRepository
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.ReadingProgressSnapshot
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.ChapterContentUiState
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.ContentViewModel
import io.nightfish.lightnovelreader.api.web.WebDataSourcePriority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime

class FlipPageContentViewModel(
    val bookRepository: BookRepository,
    val coroutineScope: CoroutineScope,
    val updateReadingProgress: (ReadingProgressSnapshot) -> Unit,
    val contentComponentRepository: ContentComponentRepository
) : ContentViewModel {
    private val progressSession = FlipPageProgressSession()
    private var collectProgressJob: Job? = null
    private var changeChapterJob: Job? = null
    private var restoreProgressJob: Job? = null
    private var restorePagerJob: Job? = null
    private var activePagerState: PagerState? = null
    private var requestedChapterId = ""
    private var prefetchedNextChapterKey = ""

    override val uiState = MutableFlipPageContentUiState(
        loadPrevChapter = ::loadPrevChapter,
        loadNextChapter = ::loadNextChapter,
        changeChapter = { changeChapter(it) },
        updatePageState = ::updatePagerState
    )

    fun updatePagerState(
        chapterId: String,
        pagerState: PagerState,
        contentPageCount: Int
    ) {
        val installation = progressSession.installPager(
            chapterId = chapterId,
            contentPageCount = contentPageCount
        ) ?: return
        collectProgressJob?.cancel()
        restorePagerJob?.cancel()
        activePagerState = pagerState
        uiState.contentPageCount = installation.session.contentPageCount
        uiState.pagerState = pagerState
        installation.restoreRequest?.let {
            restorePagerPosition(it, pagerState)
        }
    }

    private fun restorePagerPosition(
        request: FlipPageRestoreRequest,
        pagerState: PagerState
    ) {
        collectProgressJob?.cancel()
        restorePagerJob?.cancel()
        restorePagerJob = coroutineScope.launch {
            pagerState.scrollToPage(
                flipRestoreContentPage(
                    progress = request.progress,
                    contentPageCount = request.session.contentPageCount
                )
            )
            if (!progressSession.completeRestore(request)) return@launch
            uiState.readingProgress = request.progress
            collectPagerProgress(request.session, pagerState)
        }
    }

    private fun collectPagerProgress(
        session: FlipPagePagerSession,
        pagerState: PagerState
    ) {
        collectProgressJob?.cancel()
        collectProgressJob = coroutineScope.launch {
            snapshotFlow { pagerState.settledPage }.collect { settledPage ->
                val content = uiState.readingChapterContent?.get() ?: return@collect
                if (content.id != session.chapterId) return@collect
                val progress = flipReadingProgress(
                    settledPage = settledPage,
                    contentPageCount = session.contentPageCount
                )
                if (!progressSession.acceptProgress(session, progress)) return@collect
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

    override fun changeBookId(id: String) {
        uiState.bookId = id
        prefetchedNextChapterKey = ""
    }

    override fun loadNextChapter() {
        uiState.readingChapterContent?.onOk { content ->
            content.nextChapter?.let { changeChapter(it) }
        }
    }

    override fun loadPrevChapter() {
        uiState.readingChapterContent?.onOk { content ->
            content.prevChapter?.let { changeChapter(it) }
        }
    }

    override fun changeChapter(id: String, restoreProgress: Boolean) {
        if (id.isBlank()) {
            Log.e("FlipPageContentViewModel", "a blank chapter id was transferred")
            return
        }
        val targetChapterId = id
        val visitId = progressSession.beginChapter(targetChapterId)
        requestedChapterId = targetChapterId
        prefetchedNextChapterKey = ""
        collectProgressJob?.cancel()
        changeChapterJob?.cancel()
        restoreProgressJob?.cancel()
        restorePagerJob?.cancel()
        activePagerState = null
        uiState.readingProgress = 0f
        uiState.contentPageCount = 0
        uiState.readingChapterId = targetChapterId
        uiState.readingChapterContent = null

        changeChapterJob = coroutineScope.launch {
            bookRepository.getChapterContentFlow(
                targetChapterId,
                uiState.bookId,
                WebDataSourcePriority.High
            ).collect { result ->
                if (requestedChapterId != targetChapterId) return@collect
                uiState.readingChapterContent = result.map { content ->
                    ChapterContentUiState(
                        id = content.id,
                        title = content.title,
                        content = contentComponentRepository.getContentDataFromJson(content.content).components,
                        sourceContent = content.content,
                        prevChapter = content.prevChapter,
                        nextChapter = content.nextChapter
                    )
                }
                result.onOk { content ->
                    bookRepository.updateUserReadingData(uiState.bookId) {
                        it.copy(
                            lastReadTime = LocalDateTime.now(),
                            lastReadChapterId = targetChapterId,
                            lastReadChapterTitle = content.title
                        )
                    }
                    content.nextChapter?.let { nextChapterId ->
                        val prefetchKey = "${uiState.bookId}/$nextChapterId"
                        if (prefetchedNextChapterKey != prefetchKey) {
                            prefetchedNextChapterKey = prefetchKey
                            bookRepository.preloadChapterContent(
                                nextChapterId,
                                uiState.bookId
                            )
                        }
                    }
                }
            }
        }
        restoreProgressJob = coroutineScope.launch {
            val progress = if (restoreProgress) {
                withContext(Dispatchers.IO) {
                    bookRepository.getUserReadingData(uiState.bookId)
                        .currentChapterReadingProgressMap[targetChapterId] ?: 0f
                }
            } else {
                0f
            }
            val restoreRequest = progressSession.loadRestoreProgress(
                visitId = visitId,
                chapterId = targetChapterId,
                progress = progress
            ) ?: return@launch
            val pagerState = activePagerState ?: return@launch
            if (progressSession.isCurrent(restoreRequest.session)) {
                restorePagerPosition(restoreRequest, pagerState)
            }
        }
    }
}
