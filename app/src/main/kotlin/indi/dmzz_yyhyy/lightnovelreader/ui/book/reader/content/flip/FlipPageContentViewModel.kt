package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.flip

import android.util.Log
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import indi.dmzz_yyhyy.lightnovelreader.data.book.BookRepository
import indi.dmzz_yyhyy.lightnovelreader.data.content.ContentComponentRepository
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.ReadingProgressSnapshot
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.ContentViewModel
import io.nightfish.lightnovelreader.api.book.ChapterContent
import io.nightfish.lightnovelreader.api.content.component.AbstractContentComponent
import io.nightfish.lightnovelreader.api.web.WebDataSourcePriority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime

internal fun MutableFlipPageContentUiState.publishChapterContent(
    content: ChapterContent,
    contentComponents: List<AbstractContentComponent<*>>
) {
    Snapshot.withMutableSnapshot {
        contentComponentsMap[content.id] = contentComponents
        readingChapterContent = content
    }
}

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
    override val uiState: MutableFlipPageContentUiState = MutableFlipPageContentUiState(
        loadLastChapter = ::loadLastChapter,
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
                val content = uiState.readingChapterContent
                if (content.isEmpty() || content.id != session.chapterId) return@collect
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
        changeChapterJob = coroutineScope.launch {
            bookRepository.getChapterContentFlow(
                targetChapterId,
                uiState.bookId,
                WebDataSourcePriority.High
            ).collect { content ->
                if (content.isEmpty() || content.id != targetChapterId || requestedChapterId != targetChapterId) return@collect
                val contentComponents = contentComponentRepository
                    .getContentDataFromJson(content.content)
                    .components
                uiState.publishChapterContent(content, contentComponents)
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
        restoreProgressJob = coroutineScope.launch {
            val progress = if (restoreProgress) {
                withContext(Dispatchers.IO) {
                    bookRepository.getUserReadingData(uiState.bookId)
                        .currentChapterReadingProgressMap[targetChapterId] ?: 0f
                }
            } else 0f
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
