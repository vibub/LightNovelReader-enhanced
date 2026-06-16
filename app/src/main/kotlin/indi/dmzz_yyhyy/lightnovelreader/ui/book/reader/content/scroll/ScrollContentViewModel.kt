package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.scroll

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.unit.IntSize
import indi.dmzz_yyhyy.lightnovelreader.data.book.BookRepository
import indi.dmzz_yyhyy.lightnovelreader.data.content.ContentComponentRepository
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.ReadingProgressSnapshot
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.SettingState
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.ContentViewModel
import indi.dmzz_yyhyy.lightnovelreader.utils.throttleLatest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import io.nightfish.lightnovelreader.api.book.ChapterContent
import kotlinx.coroutines.launch
import java.time.LocalDateTime

class ScrollContentViewModel(
    val bookRepository: BookRepository,
    val coroutineScope: CoroutineScope,
    val settingState: SettingState,
    val contentComponentRepository: ContentComponentRepository,
    val updateReadingProgress: (ReadingProgressSnapshot) -> Unit
) : ContentViewModel {
    private var progressScrollLoadJob: Job? = null
    private var lazyColumnSize = IntSize(0, 0)
    private var lastWriteReadingProgress = 0L
    private var collectLastChapterJob: Job? = null
    private var collectCurrentChapterJob: Job? = null
    private var collectNextChapterJob: Job? = null
    private var requestedChapterId = ""
    private var prefetchedNextChapterKey = ""
    private var collectingLastChapterId = ""
    private var collectingNextChapterId = ""
    private var canPersistProgress = false

    override val uiState: MutableScrollContentUiSate = MutableScrollContentUiSate(
        loadLastChapter = ::loadLastChapter,
        loadNextChapter = ::loadNextChapter,
        changeChapter = { changeChapter(it) },
        setLazyColumnSize = {
            lazyColumnSize = it
        },
        writeProgressRightNow = ::writeProgressRightNow,
        completeProgressRestore = ::completeProgressRestore
    )

    init {
        coroutineScope.launch {
            settingState.isUsingContinuousScrollingUserData.getFlowWithDefault(true).collect {
                if (it) {
                    progressScrollLoad()
                    if (uiState.contentList.size == 1) {
                        coroutineScope.launch(Dispatchers.Main) { changeChapter(uiState.readingContentId, restoreProgress = true) }
                    }
                } else {
                    progressScrollLoadJob?.cancel()
                    if (uiState.contentList.size > 1) {
                        coroutineScope.launch(Dispatchers.Main) { changeChapter(uiState.readingContentId, restoreProgress = true) }
                    }
                }
            }
        }
        coroutineScope.launch(Dispatchers.Main) {
            snapshotFlow { uiState.lazyListState.firstVisibleItemScrollOffset }
                .throttleLatest(120L)
                .collect {
                    if (!canPersistProgress) return@collect
                    val visibleProgress = currentVisibleChapterProgress() ?: return@collect
                    if (visibleProgress.content.id != uiState.readingContentId) {
                        uiState.readingContentId = visibleProgress.content.id
                    }
                    if (visibleProgress.progress == uiState.readingProgress) return@collect
                    uiState.readingProgress = visibleProgress.progress

                    val now = System.currentTimeMillis()
                    val scrolling = uiState.lazyListState.isScrollInProgress

                    if (scrolling && now - lastWriteReadingProgress < 2500 && visibleProgress.progress < 1f) return@collect
                    lastWriteReadingProgress = now

                    persistVisibleChapterProgress()
                }
        }

        coroutineScope.launch(Dispatchers.Main) {
            snapshotFlow { uiState.lazyListState.isScrollInProgress }
                .distinctUntilChanged()
                .collect { scrolling ->
                    if (!scrolling) {
                        persistVisibleChapterProgress()
                        lastWriteReadingProgress = System.currentTimeMillis()
                    }
                }
        }
    }


    private data class VisibleChapterProgress(
        val content: ChapterContent,
        val progress: Float
    )

    private fun currentVisibleChapterProgress(): VisibleChapterProgress? {
        if (lazyColumnSize.height <= 0) return null
        val layoutInfo = uiState.lazyListState.layoutInfo
        val visibleItems = layoutInfo.visibleItemsInfo
        if (visibleItems.isEmpty()) return null
        val contentById = uiState.contentList.filterNotNull().associateBy { it.id }
        val item = visibleItems.firstOrNull { item ->
            val key = item.key as? String ?: return@firstOrNull false
            contentById.containsKey(key)
        } ?: return null
        val chapterId = item.key as? String ?: return null
        val content = contentById[chapterId] ?: return null
        val progress = 1f.coerceAtMost((-item.offset + lazyColumnSize.height).toFloat() / item.size.coerceAtLeast(1))
            .coerceIn(0f, 1f)
        return VisibleChapterProgress(content, progress)
    }

    private fun persistVisibleChapterProgress() {
        if (!canPersistProgress) return
        val visible = currentVisibleChapterProgress() ?: return
        if (uiState.readingContentId != visible.content.id) {
            uiState.readingContentId = visible.content.id
        }
        uiState.readingProgress = visible.progress
        uiState.restoreProgress = visible.progress
        updateReadingProgress(
            ReadingProgressSnapshot(
                bookId = uiState.bookId,
                chapterId = visible.content.id,
                chapterTitle = visible.content.title,
                progress = visible.progress
            )
        )
    }

    private fun completeProgressRestore(restoredVersion: Int) {
        if (uiState.restoreVersion != restoredVersion) return
        uiState.consumedRestoreVersion = restoredVersion
        canPersistProgress = true
    }

    private fun writeProgressRightNow() {
        persistVisibleChapterProgress()
    }

    private fun progressScrollLoad() {
        progressScrollLoadJob?.cancel()
        progressScrollLoadJob = coroutineScope.launch {
            snapshotFlow { uiState.lazyListState.layoutInfo.visibleItemsInfo.getOrNull(0) }.collect { itemInfo ->
                val currentContent = uiState.contentList[1] ?: return@collect
                if (itemInfo?.key == currentContent.lastChapter &&
                    lazyColumnSize.height != 0 &&
                    itemInfo.offset <= -lazyColumnSize.height &&
                    currentContent.hasPrevChapter()
                ) {
                    collectNextChapterJob?.cancel()
                    collectCurrentChapterJob?.cancel()
                    collectLastChapterJob?.cancel()
                    val chapter1 = uiState.contentList[1]
                    val chapter0 = uiState.contentList[0]
                    resetContentList()
                    uiState.contentList[2] = chapter1
                    uiState.contentList[1] = chapter0
                    collectingNextChapterId = currentContent.id
                    collectNextChapterJob = collectChapter(2, currentContent.id)
                    collectCurrentChapterJob = collectChapter(1, currentContent.lastChapter)
                    uiState.readingContentId = currentContent.lastChapter
                    requestedChapterId = uiState.readingContentId
                    uiState.contentList[1]?.takeIf { it.hasPrevChapter() }?.let {
                        collectingLastChapterId = it.lastChapter
                        collectLastChapterJob = collectChapter(0, it.lastChapter)
                    }
                    return@collect
                }
                if (itemInfo?.key == currentContent.nextChapter && currentContent.hasNextChapter()) {
                    collectNextChapterJob?.cancel()
                    collectCurrentChapterJob?.cancel()
                    collectLastChapterJob?.cancel()
                    val chapter1 = uiState.contentList[1]
                    val chapter2 = uiState.contentList[2]
                    resetContentList()
                    uiState.contentList[0] = chapter1
                    uiState.contentList[1] = chapter2
                    collectingLastChapterId = currentContent.id
                    collectLastChapterJob = collectChapter(0, currentContent.id)
                    collectCurrentChapterJob = collectChapter(1, currentContent.nextChapter)
                    uiState.readingContentId = currentContent.nextChapter
                    requestedChapterId = uiState.readingContentId
                    uiState.contentList[1]?.takeIf { it.hasNextChapter() }?.let {
                        collectingNextChapterId = it.nextChapter
                        collectNextChapterJob = collectChapter(2, it.nextChapter)
                    }
                }
            }
        }
    }

    override fun changeBookId(id: String) {
        uiState.bookId = id
        prefetchedNextChapterKey = ""
        collectingLastChapterId = ""
        collectingNextChapterId = ""
    }

    override fun loadNextChapter() {
        if (!uiState.readingChapterContent.hasNextChapter()) return
        coroutineScope.launch {
            changeChapter(
                id = uiState.readingChapterContent.nextChapter,
                restoreProgress = true
            )
        }
    }

    override fun loadLastChapter() {
        if (!uiState.readingChapterContent.hasPrevChapter()) return
        coroutineScope.launch {
            changeChapter(
                id = uiState.readingChapterContent.lastChapter,
                restoreProgress = true
            )
        }
    }

    private fun resetContentList() {
        collectingLastChapterId = ""
        collectingNextChapterId = ""
        uiState.contentList.clear()
        uiState.contentList.add(null)
        uiState.contentList.add(null)
        uiState.contentList.add(null)
    }

    override fun changeChapter(id: String, restoreProgress: Boolean) {
        requestedChapterId = id
        prefetchedNextChapterKey = ""
        collectLastChapterJob?.cancel()
        collectCurrentChapterJob?.cancel()
        collectNextChapterJob?.cancel()
        resetContentList()
        canPersistProgress = false
        uiState.readingContentId = id
        uiState.readingProgress = 0f
        uiState.restoreProgress = 0f
        uiState.restoreVersion++
        uiState.lazyListState = LazyListState()
        coroutineScope.launch (Dispatchers.IO) {
            val isUsingContinuousScrolling = settingState.isUsingContinuousScrollingUserData.getOrDefault(true)
            if (isUsingContinuousScrolling) chapterChapterWithContinuousScrolling(id, restoreProgress)
            else chapterChapterWithoutContinuousScrolling(id, restoreProgress)
        }
    }

    private fun chapterChapterWithoutContinuousScrolling(id: String, restoreProgress: Boolean) {
        collectCurrentChapterJob?.cancel()
        collectCurrentChapterJob = coroutineScope.launch(Dispatchers.IO) {
            bookRepository.getChapterContentFlow(id, uiState.bookId).collect { content ->
                if (content.isEmpty() || content.id != id || requestedChapterId != id) return@collect
                val savedProgress = if (restoreProgress) {
                    bookRepository.getUserReadingData(uiState.bookId)
                        .currentChapterReadingProgressMap[id] ?: 0f
                } else 0f
                uiState.readingProgress = savedProgress
                uiState.restoreProgress = savedProgress
                uiState.contentList[1] = content
                uiState.contentComponentsMap[content.id] = contentComponentRepository.getContentDataFromJson(content.content).components
                uiState.restoreVersion++
                bookRepository.updateUserReadingData(uiState.bookId) { userReadingData ->
                    userReadingData.apply {
                        lastReadTime = LocalDateTime.now()
                        lastReadChapterId = id
                        lastReadChapterTitle = content.title
                    }
                }
                if (content.hasNextChapter() && requestedChapterId == id) {
                    val prefetchKey = "${uiState.bookId}/${content.nextChapter}"
                    if (prefetchedNextChapterKey != prefetchKey) {
                        prefetchedNextChapterKey = prefetchKey
                        bookRepository.prefetchChapterContent(content.nextChapter, uiState.bookId)
                    }
                }
            }
        }
    }

    private fun chapterChapterWithContinuousScrolling(id: String, restoreProgress: Boolean) {
        collectCurrentChapterJob?.cancel()
        collectCurrentChapterJob = coroutineScope.launch(Dispatchers.IO) {
            bookRepository.getChapterContentFlow(id, uiState.bookId).collect { content ->
                if (content.isEmpty() || content.id != id || requestedChapterId != id) return@collect
                val savedProgress = if (restoreProgress) {
                    bookRepository.getUserReadingData(uiState.bookId)
                        .currentChapterReadingProgressMap[id] ?: 0f
                } else 0f
                uiState.readingProgress = savedProgress
                uiState.restoreProgress = savedProgress
                uiState.contentList[1] = content
                uiState.contentComponentsMap[content.id] = contentComponentRepository.getContentDataFromJson(content.content).components
                uiState.restoreVersion++
                bookRepository.updateUserReadingData(uiState.bookId) { userReadingData ->
                    userReadingData.apply {
                        lastReadTime = LocalDateTime.now()
                        lastReadChapterId = id
                        lastReadChapterTitle = content.title
                    }
                }
                if (content.hasPrevChapter()) {
                    if (collectingLastChapterId != content.lastChapter) {
                        collectLastChapterJob?.cancel()
                        collectingLastChapterId = content.lastChapter
                        collectLastChapterJob = collectChapter(0, content.lastChapter)
                    }
                } else {
                    collectLastChapterJob?.cancel()
                    collectingLastChapterId = ""
                }
                if (content.hasNextChapter()) {
                    if (collectingNextChapterId != content.nextChapter) {
                        collectNextChapterJob?.cancel()
                        collectingNextChapterId = content.nextChapter
                        collectNextChapterJob = collectChapter(2, content.nextChapter)
                    }
                } else {
                    collectNextChapterJob?.cancel()
                    collectingNextChapterId = ""
                }
            }
        }
    }

    private fun isExpectedChapter(index: Int, chapterId: String): Boolean {
        val currentContent = uiState.contentList.getOrNull(1)
        return when (index) {
            0 -> currentContent?.lastChapter == chapterId
            1 -> uiState.readingContentId == chapterId && requestedChapterId == chapterId
            2 -> currentContent?.nextChapter == chapterId
            else -> false
        }
    }

    private fun collectChapter(index: Int, chapterId: String) = coroutineScope.launch {
            bookRepository.getChapterContentFlow(chapterId, uiState.bookId)
                .collect { content ->
                    if (content.isEmpty() || content.id != chapterId || !isExpectedChapter(index, chapterId)) return@collect
                    uiState.contentList[index] = content
                    uiState.contentComponentsMap[content.id] = contentComponentRepository.getContentDataFromJson(content.content).components
                }
        }
}