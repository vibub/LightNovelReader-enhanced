package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.scroll

import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
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
import io.nightfish.lightnovelreader.api.content.component.ImageComponentData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    @Volatile private var requestedChapterId: String? = null
    @Volatile private var requestedBookId: String? = null
    private val imageHeightPreloadedKeys = mutableSetOf<String>()

    override val uiState = MutableScrollContentUiSate(
        loadPrevChapter = ::loadPrevChapter,
        loadNextChapter = ::loadNextChapter,
        changeChapter = { changeChapter(it) },
        retryChapter = ::retryChapter,
        setLazyColumnSize = { size ->
            if (lazyColumnSize.width > 0 && lazyColumnSize.width != size.width) imageHeightPreloadedKeys.clear()
            lazyColumnSize = size
        },
        writeProgressRightNow = ::writeProgressRightNow
    )

    init {
        progressScrollLoad()
        coroutineScope.launch(Dispatchers.Main) {
            snapshotFlow {
                val chapterId = uiState.readingChapterId
                val item = uiState.lazyListState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == chapterId }
                uiState.isInitialPositioned to Triple(chapterId, item?.offset, item?.size)
            }.throttleLatest(120L).collect { (positioned, snapshot) ->
                if (!positioned) return@collect
                val (chapterId, offset, size) = snapshot
                chapterId ?: return@collect
                offset ?: return@collect
                size ?: return@collect
                val progress = calculateReadingProgress(offset, size)
                if (progress == uiState.readingProgress) return@collect
                uiState.readingProgress = progress
                val now = System.currentTimeMillis()
                if (uiState.lazyListState.isScrollInProgress && now - lastWriteReadingProgress < 2500 && progress < 1f) {
                    return@collect
                }
                lastWriteReadingProgress = now
                publishReadingProgress(chapterId, progress)
            }
        }
        coroutineScope.launch(Dispatchers.Main) {
            snapshotFlow { uiState.lazyListState.isScrollInProgress }.distinctUntilChanged().collect { scrolling ->
                if (scrolling || !uiState.isInitialPositioned) return@collect
                val chapterId = uiState.readingChapterId ?: return@collect
                val item = uiState.lazyListState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == chapterId }
                    ?: return@collect
                uiState.readingProgress = calculateReadingProgress(item.offset, item.size)
                publishReadingProgress(chapterId, uiState.readingProgress)
                lastWriteReadingProgress = System.currentTimeMillis()
            }
        }
    }

    private suspend fun ChapterContent.toUiState(): ChapterContentUiState {
        val existing = uiState.contentList.firstOrNull { it?.first == id }?.second?.get()
        val prepared = prepareScrollChapter(this, existing) {
            contentComponentRepository.getContentDataFromJson(it).components
        }
        val width = lazyColumnSize.width.takeIf { it > 0 } ?: imagePreloadWidth().coerceAtLeast(0)
        val preloadKey = "${uiState.bookId}/$id/$width"
        if (width > 0 && preloadKey !in imageHeightPreloadedKeys) {
            prepared.content.forEach { component ->
                (component.data as? ImageComponentData)?.let { preloadImageComponentHeight(it, width) }
            }
            imageHeightPreloadedKeys += preloadKey
        }
        return prepared
    }

    private fun writeProgressRightNow() {
        if (!uiState.isInitialPositioned) return
        publishReadingProgress(uiState.readingChapterId ?: return, uiState.readingProgress)
    }

    private fun publishReadingProgress(chapterId: String, progress: Float) {
        if (!uiState.isInitialPositioned) return
        val chapter = uiState.readingChapterContent?.get() ?: return
        if (chapter.id != chapterId) return
        updateReadingProgress(ReadingProgressSnapshot(uiState.bookId, chapterId, chapter.title, progress))
    }

    private fun progressScrollLoad() {
        progressScrollLoadJob?.cancel()
        progressScrollLoadJob = coroutineScope.launch {
            snapshotFlow {
                Triple(
                    uiState.lazyListState.layoutInfo.visibleItemsInfo.firstOrNull(),
                    uiState.contentList.getOrNull(0)?.second?.get() != null,
                    uiState.contentList.getOrNull(2)?.second?.get() != null
                )
            }.collect { (item, prevLoaded, nextLoaded) ->
                if (!uiState.isInitialPositioned || item == null) return@collect
                uiState.readingChapterContent?.onOk { current ->
                    if (item.key == current.id) isPreviousChapterLoadArmed = true
                    val moveBackward = item.key == current.prevChapter && prevLoaded &&
                        isPreviousChapterLoadArmed && uiState.lazyListState.isScrollInProgress &&
                        lazyColumnSize.height > 0 && item.offset <= -lazyColumnSize.height && current.hasPrevChapter()
                    val moveForward = item.key == current.nextChapter && nextLoaded && current.hasNextChapter()
                    if (!moveBackward && !moveForward) return@onOk
                    val newCurrent = uiState.contentList[if (moveBackward) 0 else 2] ?: return@onOk
                    val content = newCurrent.second.get() ?: return@onOk
                    val oldCurrent = uiState.contentList[1]
                    collectPrevChapterJob?.cancel()
                    collectCurrentChapterJob?.cancel()
                    collectNextChapterJob?.cancel()
                    // 由章节稳定 key 保持可见位置，避免主动定位取消正在进行的惯性滚动。
                    Snapshot.withMutableSnapshot {
                        resetContentList()
                        uiState.contentList[if (moveBackward) 2 else 0] = oldCurrent
                        uiState.contentList[1] = newCurrent
                        uiState.readingChapterId = content.id
                    }
                    if (moveBackward) {
                        collectingNextChapterId = current.id
                        collectNextChapterJob = collectChapter(2, current.id)
                    } else {
                        collectingPrevChapterId = current.id
                        collectPrevChapterJob = collectChapter(0, current.id)
                    }
                    collectCurrentChapterJob = collectChapter(1, content.id) { loaded ->
                        updateAdjacentChapterCollectors(loaded.id, loaded.prevChapter, loaded.nextChapter)
                        updateLastReadChapter(loaded.id, loaded.title)
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
        uiState.readingChapterContent?.onOk { it.nextChapter?.takeIf(String::isNotBlank)?.let { id -> changeChapter(id) } }
    }

    override fun loadPrevChapter() {
        uiState.readingChapterContent?.onOk { it.prevChapter?.takeIf(String::isNotBlank)?.let { id -> changeChapter(id) } }
    }

    private fun resetContentList() {
        collectingPrevChapterId = null
        collectingNextChapterId = null
        uiState.contentList.indices.forEach { uiState.contentList[it] = null }
    }

    private fun setChapterRetrying(chapterId: String, retrying: Boolean) {
        uiState.retryingChapterIds = if (retrying) uiState.retryingChapterIds + chapterId
            else uiState.retryingChapterIds - chapterId
    }

    private fun retryChapter(index: Int, chapterId: String) {
        if (uiState.contentList.getOrNull(index)?.first != chapterId) return
        if (index == 1 && uiState.contentList[0] == null && uiState.contentList[2] == null) {
            changeChapter(chapterId)
            return
        }
        setChapterRetrying(chapterId, true)
        when (index) {
            0 -> {
                collectPrevChapterJob?.cancel()
                collectingPrevChapterId = chapterId
                collectPrevChapterJob = collectChapter(0, chapterId)
            }
            1 -> {
                collectCurrentChapterJob?.cancel()
                collectCurrentChapterJob = collectChapter(1, chapterId) {
                    updateAdjacentChapterCollectors(it.id, it.prevChapter, it.nextChapter)
                    updateLastReadChapter(it.id, it.title)
                }
            }
            2 -> {
                collectNextChapterJob?.cancel()
                collectingNextChapterId = chapterId
                collectNextChapterJob = collectChapter(2, chapterId)
            }
        }
    }

    override fun changeChapter(id: String, restoreProgress: Boolean) {
        if (id.isBlank()) return
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
        uiState.lazyListState = createReaderLazyListState()
        val bookId = uiState.bookId
        collectCurrentChapterJob = coroutineScope.launch(Dispatchers.IO) {
            val continuous = settingState.isUsingContinuousScrollingUserData.getOrDefault(true)
            val restored = if (restoreProgress) {
                bookRepository.getUserReadingData(bookId).currentChapterReadingProgressMap[id] ?: 0f
            } else 0f
            if (!isCurrentChapterRequest(id, bookId)) return@launch
            withContext(Dispatchers.Main) { uiState.readingProgress = restored.coerceIn(0f, 1f) }
            bookRepository.getChapterContentFlow(id, bookId).collect { result ->
                if (!isCurrentChapterRequest(id, bookId)) return@collect
                val prepared = result.get()?.toUiState()
                withContext(Dispatchers.Main) {
                    if (!isCurrentChapterRequest(id, bookId)) return@withContext
                    uiState.contentList[1] = id to result.map { prepared!! }
                    prepared?.let { content ->
                        if (continuous) updateAdjacentChapterCollectors(content.id, content.prevChapter, content.nextChapter)
                    }
                }
                if (!isCurrentChapterRequest(id, bookId)) return@collect
                result.onOk { chapter ->
                    updateLastReadChapter(chapter.id, chapter.title)
                    if (!continuous) chapter.nextChapter?.let { bookRepository.preloadChapterContent(it, bookId) }
                }
            }
        }
    }

    private fun isCurrentChapterRequest(id: String, bookId: String): Boolean =
        requestedChapterId == id && requestedBookId == bookId && uiState.bookId == bookId && uiState.readingChapterId == id

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
    ): Job {
        val bookId = uiState.bookId
        return coroutineScope.launch {
            bookRepository.getChapterContentFlow(chapterId, bookId).flowOn(Dispatchers.IO).collect { result ->
                if (uiState.bookId != bookId || !isChapterSlotCurrent(index, chapterId)) return@collect
                val prepared = result.get()?.toUiState()
                if (uiState.bookId != bookId || !isChapterSlotCurrent(index, chapterId)) return@collect
                setChapterRetrying(chapterId, false)
                uiState.contentList[index] = chapterId to result.map { prepared!! }
                prepared?.let { onLoaded(it) }
            }
        }
    }

    private fun updateAdjacentChapterCollectors(currentChapterId: String, prevChapterId: String?, nextChapterId: String?) {
        val prevId = prevChapterId?.takeIf { it.isNotBlank() && it != currentChapterId && it != nextChapterId }
        if (collectingPrevChapterId != prevId) {
            collectPrevChapterJob?.cancel()
            collectingPrevChapterId?.let { setChapterRetrying(it, false) }
            collectingPrevChapterId = prevId
            collectPrevChapterJob = if (prevId == null) {
                uiState.contentList[0] = null
                null
            } else collectChapter(0, prevId)
        }
        val nextId = nextChapterId?.takeIf { it.isNotBlank() && it != currentChapterId && it != prevChapterId }
        if (collectingNextChapterId != nextId) {
            collectNextChapterJob?.cancel()
            collectingNextChapterId?.let { setChapterRetrying(it, false) }
            collectingNextChapterId = nextId
            collectNextChapterJob = if (nextId == null) {
                uiState.contentList[2] = null
                null
            } else collectChapter(2, nextId)
        }
    }

    private suspend fun updateLastReadChapter(chapterId: String, chapterTitle: String?) {
        bookRepository.updateUserReadingData(uiState.bookId) {
            it.copy(lastReadTime = LocalDateTime.now(), lastReadChapterId = chapterId,
                lastReadChapterTitle = chapterTitle ?: it.lastReadChapterTitle)
        }
    }

    private fun calculateReadingProgress(itemOffset: Int, itemSize: Int): Float =
        ((-itemOffset).toFloat() / (itemSize - lazyColumnSize.height).coerceAtLeast(1)).coerceIn(0f, 1f)
}
