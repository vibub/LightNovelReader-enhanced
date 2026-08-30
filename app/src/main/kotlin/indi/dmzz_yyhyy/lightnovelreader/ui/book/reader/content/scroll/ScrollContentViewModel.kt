package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.scroll

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.unit.IntSize
import com.github.michaelbull.result.get
import com.github.michaelbull.result.map
import com.github.michaelbull.result.onOk
import indi.dmzz_yyhyy.lightnovelreader.data.book.BookRepository
import indi.dmzz_yyhyy.lightnovelreader.data.content.ContentComponentRepository
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.ReadingProgressSnapshot
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.SettingState
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.ChapterContentUiState
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.ContentViewModel
import indi.dmzz_yyhyy.lightnovelreader.utils.throttleLatest
import io.nightfish.lightnovelreader.api.book.ChapterContent
import io.nightfish.lightnovelreader.api.content.component.AbstractContentComponent
import io.nightfish.lightnovelreader.api.content.component.ImageComponentData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.time.LocalDateTime

class ScrollContentViewModel(
    val bookRepository: BookRepository,
    val coroutineScope: CoroutineScope,
    val settingState: SettingState,
    val contentComponentRepository: ContentComponentRepository,
    val updateReadingProgress: (ReadingProgressSnapshot) -> Unit,
    val imagePreloadWidth: () -> Int = { 0 },
    val preloadImageComponentHeight: suspend (ImageComponentData, Int) -> Int? = { _, _ -> null }
) : ContentViewModel {
    private var progressScrollLoadJob: Job? = null
    private var lazyColumnSize = IntSize(0, 0)
    private var lastWriteReadingProgress = 0L
    private var collectPrevChapterJob: Job? = null
    private var collectCurrentChapterJob: Job? = null
    private var collectNextChapterJob: Job? = null
    private var collectingPrevChapterId: String? = null
    private var collectingNextChapterId: String? = null
    private var isPreviousChapterLoadArmed = false
    @Volatile
    private var requestedChapterId: String? = null
    @Volatile
    private var requestedBookId: String? = null
    private val imageHeightPreloadedKeys = mutableSetOf<String>()

    override val uiState: MutableScrollContentUiSate = MutableScrollContentUiSate(
        loadPrevChapter = ::loadPrevChapter,
        loadNextChapter = ::loadNextChapter,
        changeChapter = { changeChapter(it) },
        retryChapter = ::retryChapter,
        setLazyColumnSize = { size ->
            if (lazyColumnSize.width > 0 && lazyColumnSize.width != size.width) {
                imageHeightPreloadedKeys.clear()
            }
            lazyColumnSize = size
        },
        writeProgressRightNow = ::writeProgressRightNow
    )

    init {
        coroutineScope.launch {
            settingState.isUsingContinuousScrollingUserData.getFlowWithDefault(true).collect {
                if (it) {
                    progressScrollLoad()
                    val hasAdjacentChapters = uiState.contentList.getOrNull(0) != null || uiState.contentList.getOrNull(2) != null
                    if (!hasAdjacentChapters) {
                        coroutineScope.launch(Dispatchers.Main) {
                            uiState.readingChapterId?.let { id -> changeChapter(id) }
                        }
                    }
                } else {
                    progressScrollLoadJob?.cancel()
                    val hasAdjacentChapters = uiState.contentList.getOrNull(0) != null || uiState.contentList.getOrNull(2) != null
                    if (hasAdjacentChapters) {
                        coroutineScope.launch(Dispatchers.Main) {
                            uiState.readingChapterId?.let { id -> changeChapter(id) }
                        }
                    }
                }
            }
        }
        coroutineScope.launch(Dispatchers.Main) {
            snapshotFlow {
                val chapterId = uiState.readingChapterId
                val item = chapterId?.let { id ->
                    uiState.lazyListState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == id }
                }
                uiState.isInitialPositioned to Triple(chapterId, item?.offset, item?.size)
            }
                .throttleLatest(120L)
                .collect { (isInitialPositioned, progressSnapshot) ->
                    if (!isInitialPositioned) return@collect
                    val (chapterId, itemOffset, itemSize) = progressSnapshot
                    chapterId ?: return@collect
                    itemOffset ?: return@collect
                    itemSize ?: return@collect

                    val newProgress = calculateReadingProgress(itemOffset, itemSize)
                    if (newProgress == uiState.readingProgress) return@collect
                    uiState.readingProgress = newProgress

                    val now = System.currentTimeMillis()
                    val scrolling = uiState.lazyListState.isScrollInProgress

                    if (scrolling && now - lastWriteReadingProgress < 2500 && newProgress < 1f) return@collect
                    lastWriteReadingProgress = now

                    coroutineScope.launch(Dispatchers.IO) { publishReadingProgress(chapterId, newProgress) }
                }
        }

        coroutineScope.launch(Dispatchers.Main) {
            snapshotFlow { uiState.lazyListState.isScrollInProgress }
                .distinctUntilChanged()
                .collect { scrolling ->
                    if (!scrolling) {
                        if (!uiState.isInitialPositioned) return@collect
                        val layoutInfo = uiState.lazyListState.layoutInfo
                        val chapterId = uiState.readingChapterId ?: return@collect
                        val item = layoutInfo.visibleItemsInfo.firstOrNull { it.key == chapterId } ?: return@collect

                        val finalProgress = calculateReadingProgress(item.offset, item.size)

                        if (uiState.readingProgress != finalProgress) {
                            uiState.readingProgress = finalProgress
                        }
                        coroutineScope.launch(Dispatchers.IO) { publishReadingProgress(chapterId, uiState.readingProgress) }
                        lastWriteReadingProgress = System.currentTimeMillis()
                    }
                }
        }
    }

    private fun imagePreloadWidthPx(): Int = lazyColumnSize.width
        .takeIf { it > 0 }
        ?: imagePreloadWidth().coerceAtLeast(0)

    private suspend fun preloadChapterImageHeights(
        chapterId: String,
        components: List<AbstractContentComponent<*>>
    ) {
        val widthPx = imagePreloadWidthPx()
        if (uiState.bookId.isBlank() || chapterId.isBlank() || widthPx <= 0) return
        val preloadKey = "${uiState.bookId}/$chapterId/$widthPx"
        if (preloadKey in imageHeightPreloadedKeys) return

        components.forEach { component ->
            val data = component.data as? ImageComponentData ?: return@forEach
            preloadImageComponentHeight(data, widthPx)
        }
        imageHeightPreloadedKeys.add(preloadKey)
    }

    private suspend fun ChapterContent.toUiState(): ChapterContentUiState {
        val components = contentComponentRepository.getContentDataFromJson(content).components
        preloadChapterImageHeights(id, components)
        return ChapterContentUiState(
            id = id,
            title = title,
            content = components,
            sourceContent = content,
            prevChapter = prevChapter,
            nextChapter = nextChapter
        )
    }

    private fun writeProgressRightNow() {
        if (!uiState.isInitialPositioned) return
        publishReadingProgress(uiState.readingChapterId ?: return, uiState.readingProgress)
    }

    private fun publishReadingProgress(chapterId: String, progress: Float) {
        if (!uiState.isInitialPositioned) return
        val chapter = uiState.readingChapterContent?.get() ?: return
        updateReadingProgress(
            ReadingProgressSnapshot(
                bookId = uiState.bookId,
                chapterId = chapterId,
                chapterTitle = chapter.title,
                progress = progress
            )
        )
    }

    private fun progressScrollLoad() {
        progressScrollLoadJob?.cancel()
        progressScrollLoadJob = coroutineScope.launch {
            snapshotFlow {
                Triple(
                    uiState.lazyListState.layoutInfo.visibleItemsInfo.getOrNull(0),
                    uiState.contentList.getOrNull(0)?.second?.get() != null,
                    uiState.contentList.getOrNull(2)?.second?.get() != null
                )
            }.collect { (itemInfo, isPrevChapterLoaded, isNextChapterLoaded) ->
                uiState.readingChapterContent?.onOk { readingChapterContent ->
                    if (itemInfo?.key == readingChapterContent.id) {
                        isPreviousChapterLoadArmed = true
                    }
                    if (
                        itemInfo != null &&
                        itemInfo.key == readingChapterContent.prevChapter &&
                        isPrevChapterLoaded &&
                        isPreviousChapterLoadArmed &&
                        uiState.lazyListState.isScrollInProgress &&
                        lazyColumnSize.height != 0 &&
                        itemInfo.offset <= -lazyColumnSize.height &&
                        readingChapterContent.hasPrevChapter()
                    ) {
                        collectNextChapterJob?.cancel()
                        collectCurrentChapterJob?.cancel()
                        collectPrevChapterJob?.cancel()
                        val nextChapter = uiState.contentList[1]
                        val currentChapter = uiState.contentList[0]
                        val currentChapterId = readingChapterContent.prevChapter
                        val currentChapterContent = currentChapter?.second?.get()
                        resetContentList()
                        uiState.contentList[2] = nextChapter
                        uiState.contentList[1] = currentChapter
                        collectingNextChapterId = readingChapterContent.id
                        collectNextChapterJob = collectChapter(2, readingChapterContent.id)
                        collectCurrentChapterJob = collectChapter(1, currentChapterId) { chapterContent ->
                            updateAdjacentChapterCollectors(chapterContent)
                            updateLastReadChapter(chapterContent.id, chapterContent.title)
                        }
                        uiState.readingChapterId = currentChapterId
                        currentChapterContent?.let {
                            updateLastReadChapter(it.id, it.title)
                        }
                    }
                    if (
                        itemInfo != null &&
                        itemInfo.key == readingChapterContent.nextChapter &&
                        isNextChapterLoaded &&
                        readingChapterContent.hasNextChapter()
                    ) {
                        collectNextChapterJob?.cancel()
                        collectCurrentChapterJob?.cancel()
                        collectPrevChapterJob?.cancel()
                        val prevChapter = uiState.contentList[1]
                        val currentChapter = uiState.contentList[2]
                        val currentChapterId = readingChapterContent.nextChapter
                        val currentChapterContent = currentChapter?.second?.get()
                        resetContentList()
                        uiState.contentList[0] = prevChapter
                        uiState.contentList[1] = currentChapter
                        collectingPrevChapterId = readingChapterContent.id
                        collectPrevChapterJob = collectChapter(0, readingChapterContent.id)
                        collectCurrentChapterJob = collectChapter(1, currentChapterId) { chapterContent ->
                            updateAdjacentChapterCollectors(chapterContent)
                            updateLastReadChapter(chapterContent.id, chapterContent.title)
                        }
                        uiState.readingChapterId = currentChapterId
                        currentChapterContent?.let {
                            updateLastReadChapter(it.id, it.title)
                        }
                    }
                }
            }
        }
    }

    override fun changeBookId(id: String) {
        if (uiState.bookId != id) {
            collectPrevChapterJob?.cancel()
            collectCurrentChapterJob?.cancel()
            collectNextChapterJob?.cancel()
            imageHeightPreloadedKeys.clear()
            collectingPrevChapterId = null
            collectingNextChapterId = null
            isPreviousChapterLoadArmed = false
            requestedChapterId = null
            requestedBookId = null
            uiState.isInitialPositioned = false
            uiState.retryingChapterIds = emptySet()
        }
        uiState.bookId = id
    }

    override fun loadNextChapter() {
        uiState.readingChapterContent?.onOk { readingChapterContent ->
            if (!readingChapterContent.hasNextChapter()) return
            coroutineScope.launch {
                changeChapter(
                    id = readingChapterContent.nextChapter ?: return@launch
                )
            }
        }
    }

    override fun loadPrevChapter() {
        uiState.readingChapterContent?.onOk { readingChapterContent ->
            if (!readingChapterContent.hasPrevChapter()) return
            coroutineScope.launch {
                changeChapter(
                    id = readingChapterContent.prevChapter ?: return@launch
                )
            }
        }
    }

    private fun resetContentList() {
        collectingPrevChapterId = null
        collectingNextChapterId = null
        // 保持三个槽位始终存在，避免相邻章节流在清空列表的瞬间访问越界。
        uiState.contentList.indices.forEach { index ->
            uiState.contentList[index] = null
        }
    }

    private fun setChapterRetrying(chapterId: String, retrying: Boolean) {
        uiState.retryingChapterIds = if (retrying) {
            uiState.retryingChapterIds + chapterId
        } else {
            uiState.retryingChapterIds - chapterId
        }
    }

    private fun retryChapter(index: Int, chapterId: String) {
        if (uiState.contentList.getOrNull(index)?.first != chapterId) return
        if (index == 1 && uiState.contentList[0] == null && uiState.contentList[2] == null) {
            changeChapter(chapterId)
            return
        }

        when (index) {
            0 -> {
                collectPrevChapterJob?.cancel()
                collectingPrevChapterId = chapterId
                setChapterRetrying(chapterId, true)
                collectPrevChapterJob = collectChapter(0, chapterId)
            }
            1 -> {
                collectCurrentChapterJob?.cancel()
                setChapterRetrying(chapterId, true)
                collectCurrentChapterJob = collectChapter(1, chapterId) { chapterContent ->
                    updateAdjacentChapterCollectors(chapterContent)
                    updateLastReadChapter(chapterContent.id, chapterContent.title)
                }
            }
            2 -> {
                collectNextChapterJob?.cancel()
                collectingNextChapterId = chapterId
                setChapterRetrying(chapterId, true)
                collectNextChapterJob = collectChapter(2, chapterId)
            }
        }
    }

    override fun changeChapter(id: String, restoreProgress: Boolean) {
        requestedChapterId = id
        requestedBookId = uiState.bookId
        collectPrevChapterJob?.cancel()
        collectCurrentChapterJob?.cancel()
        collectNextChapterJob?.cancel()
        uiState.retryingChapterIds = emptySet()
        isPreviousChapterLoadArmed = false
        uiState.isInitialPositioned = false
        resetContentList()
        uiState.readingChapterId = id
        uiState.readingProgress = 0f
        uiState.lazyListState = LazyListState()
        coroutineScope.launch(Dispatchers.IO) {
            val isUsingContinuousScrolling = settingState.isUsingContinuousScrollingUserData.getOrDefault(true)
            if (isUsingContinuousScrolling) {
                changeChapterWithContinuousScrolling(id, restoreProgress)
            } else {
                changeChapterWithoutContinuousScrolling(id, restoreProgress)
            }
        }
    }

    private fun isCurrentChapterRequest(
        chapterId: String,
        bookId: String = uiState.bookId
    ): Boolean = requestedChapterId == chapterId &&
        requestedBookId == bookId &&
        uiState.bookId == bookId &&
        uiState.readingChapterId == chapterId

    private fun changeChapterWithoutContinuousScrolling(
        id: String,
        restoreProgress: Boolean
    ) {
        if (!isCurrentChapterRequest(id)) return
        collectCurrentChapterJob = coroutineScope.launch(Dispatchers.IO) {
            val bookId = uiState.bookId
            val restoredProgress = if (restoreProgress) {
                bookRepository.getUserReadingData(bookId)
                    .currentChapterReadingProgressMap[id] ?: 0f
            } else {
                0f
            }
            if (!isCurrentChapterRequest(id)) return@launch
            uiState.readingProgress = restoredProgress

            bookRepository.getChapterContentFlow(id, bookId).collect { result ->
                if (!isCurrentChapterRequest(id)) return@collect
                val chapterContentUiState = result.get()?.toUiState()
                if (!isCurrentChapterRequest(id) || uiState.contentList.size <= 1) {
                    return@collect
                }
                uiState.contentList[1] = id to result.map { chapterContentUiState!! }
                result.onOk { chapterContent ->
                    bookRepository.updateUserReadingData(bookId) { userReadingData ->
                        userReadingData.copy(
                            lastReadTime = LocalDateTime.now(),
                            lastReadChapterId = id,
                            lastReadChapterTitle = chapterContent.title,
                        )
                    }
                    chapterContent.nextChapter?.let {
                        bookRepository.preloadChapterContent(it, bookId)
                    }
                }
            }
        }
    }

    private fun changeChapterWithContinuousScrolling(
        id: String,
        restoreProgress: Boolean
    ) {
        if (!isCurrentChapterRequest(id)) return
        collectCurrentChapterJob = coroutineScope.launch(Dispatchers.IO) {
            val bookId = uiState.bookId
            val restoredProgress = if (restoreProgress) {
                bookRepository.getUserReadingData(bookId)
                    .currentChapterReadingProgressMap[id] ?: 0f
            } else {
                0f
            }
            if (!isCurrentChapterRequest(id)) return@launch
            uiState.readingProgress = restoredProgress

            bookRepository.getChapterContentFlow(id, bookId).collect { result ->
                if (!isCurrentChapterRequest(id)) return@collect
                val chapterContentUiState = result.get()?.toUiState()
                if (!isCurrentChapterRequest(id) || uiState.contentList.size <= 1) {
                    return@collect
                }
                uiState.contentList[1] = id to result.map { chapterContentUiState!! }
                result.onOk { chapterContent ->
                    updateAdjacentChapterCollectors(chapterContent)
                    bookRepository.updateUserReadingData(bookId) { userReadingData ->
                        userReadingData.copy(
                            lastReadTime = LocalDateTime.now(),
                            lastReadChapterId = id,
                            lastReadChapterTitle = chapterContent.title,
                        )
                    }
                }
            }
        }
    }

    private fun isChapterSlotCurrent(index: Int, chapterId: String): Boolean = when (index) {
        0 -> collectingPrevChapterId == chapterId
        1 -> uiState.contentList.getOrNull(1)?.first == chapterId
        2 -> collectingNextChapterId == chapterId
        else -> false
    }

    private fun collectChapter(
        index: Int,
        chapterId: String,
        onLoaded: suspend (ChapterContentUiState) -> Unit = {}
    ) = coroutineScope.launch {
            bookRepository.getChapterContentFlow(chapterId, uiState.bookId)
                .collect { content ->
                    if (!isChapterSlotCurrent(index, chapterId)) return@collect
                    val loadedContent = content.get()?.toUiState()
                    if (!isChapterSlotCurrent(index, chapterId)) return@collect
                    setChapterRetrying(chapterId, false)
                    uiState.contentList[index] = chapterId to content.map { loadedContent!! }
                    loadedContent?.let { onLoaded(it) }
                }
        }

    private fun updateAdjacentChapterCollectors(chapterContent: ChapterContentUiState) =
        updateAdjacentChapterCollectors(
            currentChapterId = chapterContent.id,
            prevChapterId = chapterContent.prevChapter,
            nextChapterId = chapterContent.nextChapter
        )

    private fun updateAdjacentChapterCollectors(chapterContent: ChapterContent) =
        updateAdjacentChapterCollectors(
            currentChapterId = chapterContent.id,
            prevChapterId = chapterContent.prevChapter,
            nextChapterId = chapterContent.nextChapter
        )

    private fun updateAdjacentChapterCollectors(
        currentChapterId: String,
        prevChapterId: String?,
        nextChapterId: String?
    ) {
        val validPrevChapterId = prevChapterId
            ?.takeIf { it != currentChapterId }
            ?.takeIf { it != nextChapterId }
        if (collectingPrevChapterId != validPrevChapterId) {
            collectPrevChapterJob?.cancel()
            collectingPrevChapterId?.let { setChapterRetrying(it, false) }
            collectingPrevChapterId = validPrevChapterId
            collectPrevChapterJob = if (validPrevChapterId == null) {
                uiState.contentList[0] = null
                null
            } else {
                collectChapter(0, validPrevChapterId)
            }
        }

        val validNextChapterId = nextChapterId
            ?.takeIf { it != currentChapterId }
            ?.takeIf { it != prevChapterId }
        if (collectingNextChapterId != validNextChapterId) {
            collectNextChapterJob?.cancel()
            collectingNextChapterId?.let { setChapterRetrying(it, false) }
            collectingNextChapterId = validNextChapterId
            collectNextChapterJob = if (validNextChapterId == null) {
                uiState.contentList[2] = null
                null
            } else {
                collectChapter(2, validNextChapterId)
            }
        }
    }

    private suspend fun updateLastReadChapter(chapterId: String, chapterTitle: String?) {
        bookRepository.updateUserReadingData(uiState.bookId) {
            it.copy(
                lastReadTime = LocalDateTime.now(),
                lastReadChapterId = chapterId,
                lastReadChapterTitle = chapterTitle ?: it.lastReadChapterTitle
            )
        }
    }

    private fun calculateReadingProgress(itemOffset: Int, itemSize: Int): Float =
        ((-itemOffset + lazyColumnSize.height).toFloat() / itemSize.coerceAtLeast(1)).coerceIn(0f, 1f)
}
