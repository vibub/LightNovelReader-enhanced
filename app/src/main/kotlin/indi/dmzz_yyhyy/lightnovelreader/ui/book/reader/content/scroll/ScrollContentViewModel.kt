package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.scroll

import android.util.Log
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.unit.IntSize
import indi.dmzz_yyhyy.lightnovelreader.BuildConfig
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
import kotlin.math.abs

private const val DEBUG_READER_SCROLL = false
private const val SCROLL_VM_LOG_TAG = "ScrollContentVM"
private const val PROGRESS_UPDATE_EPSILON = 0.001f

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
    private var lastRotationFirstIndex: Int? = null
    private var lastRotationFirstOffset: Int? = null
    private val lastRotationVisibleItemSizes: MutableMap<Any?, Int> = mutableMapOf()
    private var lastProgressFirstIndex: Int? = null
    private var lastProgressFirstOffset: Int? = null
    private val lastProgressVisibleItemSizes: MutableMap<Any?, Int> = mutableMapOf()
    private val componentHeightsByChapterId: MutableMap<String, MutableMap<Int, Int>> = mutableMapOf()

    override val uiState: MutableScrollContentUiSate = MutableScrollContentUiSate(
        loadLastChapter = ::loadLastChapter,
        loadNextChapter = ::loadNextChapter,
        changeChapter = { changeChapter(it) },
        setLazyColumnSize = {
            lazyColumnSize = it
        },
        writeProgressRightNow = ::writeProgressRightNow,
        completeProgressRestore = ::completeProgressRestore,
        componentHeightsByChapterId = componentHeightsByChapterId
    )

    @Suppress("SimplifyBooleanWithConstants")
    private inline fun debugLog(message: () -> String) {
        if (BuildConfig.DEBUG && DEBUG_READER_SCROLL) Log.d(SCROLL_VM_LOG_TAG, message())
    }

    private fun slotsSummary(): String = uiState.contentList
        .mapIndexed { index, content -> "$index:${content?.id ?: "null"}" }
        .joinToString(prefix = "[", postfix = "]")

    private fun visibleItemsSummary(limit: Int = 8): String {
        val items = uiState.lazyListState.layoutInfo.visibleItemsInfo
        val suffix = if (items.size > limit) ",...+${items.size - limit}" else ""
        return items.take(limit)
            .joinToString(prefix = "[", postfix = "$suffix]") { item ->
                "${item.index}:${item.key}@${item.offset}+${item.size}"
            }
    }

    private fun firstVisibleItemSummary(): String = uiState.lazyListState.layoutInfo.visibleItemsInfo
        .firstOrNull()
        ?.let { "${it.index}:${it.key}@${it.offset}+${it.size}" }
        ?: "null"

    private fun scrollDirectionFrom(lastIndex: Int?, lastOffset: Int?): Int {
        val firstIndex = uiState.lazyListState.firstVisibleItemIndex
        val firstOffset = uiState.lazyListState.firstVisibleItemScrollOffset
        return when {
            lastIndex == null || lastOffset == null -> 0
            firstIndex > lastIndex -> 1
            firstIndex < lastIndex -> -1
            firstOffset > lastOffset -> 1
            firstOffset < lastOffset -> -1
            else -> 0
        }
    }

    private fun updateVisibleItemSizes(
        target: MutableMap<Any?, Int>,
        items: List<LazyListItemInfo>
    ) {
        target.clear()
        items.forEach { target[it.key] = it.size }
    }

    private fun updateVisibleComponentHeights(items: List<LazyListItemInfo>) {
        items.forEach { item ->
            val key = item.key.scrollContentItemKeyOrNull() ?: return@forEach
            if (key.type != ScrollContentItemType.Component || item.size <= 0) return@forEach
            if (contentByChapterId(key.chapterId) == null) return@forEach
            componentHeightsByChapterId
                .getOrPut(key.chapterId) { mutableMapOf() }[key.index] = item.size
        }
    }

    private fun contentByChapterId(chapterId: String): ChapterContent? =
        uiState.contentList.firstOrNull { it?.id == chapterId }

    private fun pruneContentComponentsMap() {
        val visibleChapterIds = uiState.contentList.mapNotNull { it?.id }.toSet()
        if (visibleChapterIds.isEmpty()) return
        val staleKeys = uiState.contentComponentsMap.keys.filterNot { it in visibleChapterIds }
        if (staleKeys.isEmpty()) return
        staleKeys.forEach(uiState.contentComponentsMap::remove)
        debugLog {
            "contentComponentsPruned book=${uiState.bookId} kept=${visibleChapterIds.joinToString(prefix = "[", postfix = "]")} " +
                    "removed=${staleKeys.size} slots=${slotsSummary()}"
        }
    }

    private fun resetScrollTracking() {
        lastRotationFirstIndex = null
        lastRotationFirstOffset = null
        lastRotationVisibleItemSizes.clear()
        lastProgressFirstIndex = null
        lastProgressFirstOffset = null
        lastProgressVisibleItemSizes.clear()
    }

    init {
        coroutineScope.launch {
            settingState.isUsingContinuousScrollingUserData.getFlowWithDefault(true).collect {
                debugLog {
                    "continuousSetting enabled=$it book=${uiState.bookId} reading=${uiState.readingContentId} " +
                            "progress=${uiState.readingProgress} restore=${uiState.restoreProgress} slots=${slotsSummary()}"
                }
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
                .throttleLatest(200L)
                .collect {
                    val visibleItems = uiState.lazyListState.layoutInfo.visibleItemsInfo
                    updateVisibleComponentHeights(visibleItems)
                    val progressScrollDirection = scrollDirectionFrom(lastProgressFirstIndex, lastProgressFirstOffset)
                    lastProgressFirstIndex = uiState.lazyListState.firstVisibleItemIndex
                    lastProgressFirstOffset = uiState.lazyListState.firstVisibleItemScrollOffset

                    if (!canPersistProgress) {
                        lastProgressVisibleItemSizes.clear()
                        return@collect
                    }
                    val anchor = viewportBottomChapterItem(visibleItems) ?: run {
                        updateVisibleItemSizes(lastProgressVisibleItemSizes, visibleItems)
                        return@collect
                    }
                    val previousAnchorSize = lastProgressVisibleItemSizes[anchor.itemInfo.key]
                    val visibleProgress = chapterProgressOf(anchor)
                    updateVisibleItemSizes(lastProgressVisibleItemSizes, visibleItems)
                    val readingContentIdBefore = uiState.readingContentId
                    val readingProgressBefore = uiState.readingProgress
                    val readingSlot = uiState.contentList.indexOfFirst { it?.id == uiState.readingContentId }
                    val visibleSlot = uiState.contentList.indexOfFirst { it?.id == visibleProgress.content.id }
                    val requiredDirection = when {
                        visibleSlot > readingSlot -> 1
                        visibleSlot < readingSlot -> -1
                        else -> 0
                    }
                    val anchorSizeChanged = previousAnchorSize
                        ?.let { it != anchor.itemInfo.size } == true
                    val isChapterChange = visibleProgress.content.id != uiState.readingContentId
                    if (isChapterChange && (
                            !uiState.lazyListState.isScrollInProgress ||
                                    requiredDirection == 0 ||
                                    progressScrollDirection != requiredDirection ||
                                    anchorSizeChanged
                            )
                    ) {
                        debugLog {
                            "progressChapterChangeSkipped book=${uiState.bookId} candidate=${visibleProgress.content.id} " +
                                    "current=${uiState.readingContentId} progress=${visibleProgress.progress} " +
                                    "requiredDirection=$requiredDirection scrollDirection=$progressScrollDirection " +
                                    "anchorSizeChanged=$anchorSizeChanged previousSize=$previousAnchorSize " +
                                    "anchor=${anchor.itemInfo.index}:${anchor.itemInfo.key}@${anchor.itemInfo.offset}+${anchor.itemInfo.size} " +
                                    "readingSlot=$readingSlot visibleSlot=$visibleSlot firstIndex=${uiState.lazyListState.firstVisibleItemIndex} " +
                                    "firstOffset=${uiState.lazyListState.firstVisibleItemScrollOffset} firstItem=${firstVisibleItemSummary()} " +
                                    "slots=${slotsSummary()} visible=${visibleItemsSummary()}"
                        }
                        return@collect
                    }
                    val isSameChapter = visibleProgress.content.id == uiState.readingContentId
                    val progressDelta = abs(visibleProgress.progress - uiState.readingProgress)
                    if (isSameChapter && progressDelta < PROGRESS_UPDATE_EPSILON && visibleProgress.progress < 1f) return@collect
                    debugLog {
                        "progressSample book=${uiState.bookId} visible=${visibleProgress.content.id} " +
                                "progress=${visibleProgress.progress} readingBefore=$readingContentIdBefore/$readingProgressBefore " +
                                "restore=${uiState.restoreProgress} firstIndex=${uiState.lazyListState.firstVisibleItemIndex} " +
                                "firstOffset=${uiState.lazyListState.firstVisibleItemScrollOffset} firstItem=${firstVisibleItemSummary()} " +
                                "lazyHeight=${lazyColumnSize.height} scrolling=${uiState.lazyListState.isScrollInProgress} " +
                                "scrollDirection=$progressScrollDirection anchorSizeChanged=$anchorSizeChanged " +
                                "canPersist=$canPersistProgress slots=${slotsSummary()}"
                    }
                    applyVisibleChapterProgress(visibleProgress)

                    val now = System.currentTimeMillis()
                    val scrolling = uiState.lazyListState.isScrollInProgress

                    if (scrolling && now - lastWriteReadingProgress < 2500 && visibleProgress.progress < 1f) return@collect
                    lastWriteReadingProgress = now

                    persistVisibleChapterProgress(visibleProgress)
                }
        }

        coroutineScope.launch(Dispatchers.Main) {
            snapshotFlow { uiState.lazyListState.isScrollInProgress }
                .distinctUntilChanged()
                .collect { scrolling ->
                    if (!scrolling) {
                        updateVisibleComponentHeights(uiState.lazyListState.layoutInfo.visibleItemsInfo)
                        debugLog {
                            "scrollIdle book=${uiState.bookId} reading=${uiState.readingContentId} " +
                                    "progress=${uiState.readingProgress} restore=${uiState.restoreProgress} " +
                                    "firstIndex=${uiState.lazyListState.firstVisibleItemIndex} " +
                                    "firstOffset=${uiState.lazyListState.firstVisibleItemScrollOffset} " +
                                    "visible=${visibleItemsSummary()} slots=${slotsSummary()} canPersist=$canPersistProgress"
                        }
                        persistVisibleChapterProgress()
                        lastWriteReadingProgress = System.currentTimeMillis()
                    }
                }
        }
    }


    private data class VisibleChapterItem(
        val content: ChapterContent,
        val itemInfo: LazyListItemInfo
    )

    private data class VisibleChapterProgress(
        val content: ChapterContent,
        val progress: Float
    )

    private fun LazyListItemInfo.isVisibleInViewport(): Boolean =
        offset + size > 0 && offset < lazyColumnSize.height

    private fun viewportTopChapterItem(
        visibleItems: List<LazyListItemInfo> = uiState.lazyListState.layoutInfo.visibleItemsInfo
    ): VisibleChapterItem? {
        if (lazyColumnSize.height <= 0 || visibleItems.isEmpty()) return null
        val item = visibleItems.firstOrNull { item ->
            val chapterId = item.key.scrollContentChapterId() ?: return@firstOrNull false
            item.isVisibleInViewport() && contentByChapterId(chapterId) != null
        } ?: return null
        val chapterId = item.key.scrollContentChapterId() ?: return null
        val content = contentByChapterId(chapterId) ?: return null
        return VisibleChapterItem(content, item)
    }

    private fun viewportBottomChapterItem(
        visibleItems: List<LazyListItemInfo> = uiState.lazyListState.layoutInfo.visibleItemsInfo
    ): VisibleChapterItem? {
        if (lazyColumnSize.height <= 0 || visibleItems.isEmpty()) return null
        val item = visibleItems.lastOrNull { item ->
            val chapterId = item.key.scrollContentChapterId() ?: return@lastOrNull false
            item.isVisibleInViewport() && contentByChapterId(chapterId) != null
        } ?: return null
        val chapterId = item.key.scrollContentChapterId() ?: return null
        val content = contentByChapterId(chapterId) ?: return null
        return VisibleChapterItem(content, item)
    }

    private fun chapterProgressOf(visible: VisibleChapterItem): VisibleChapterProgress {
        val item = visible.itemInfo
        val progress = scrollContentChapterProgress(
            key = item.key.scrollContentItemKeyOrNull(),
            itemOffset = item.offset,
            itemSize = item.size,
            viewportHeight = lazyColumnSize.height,
            componentCount = uiState.contentComponentsMap[visible.content.id]?.size ?: 0,
            componentHeights = componentHeightsByChapterId[visible.content.id].orEmpty()
        )
        return VisibleChapterProgress(visible.content, progress)
    }

    private fun currentVisibleChapterProgress(): VisibleChapterProgress? =
        viewportBottomChapterItem()?.let(::chapterProgressOf)

    private fun applyVisibleChapterProgress(visibleProgress: VisibleChapterProgress) {
        if (uiState.readingContentId != visibleProgress.content.id) {
            uiState.readingContentId = visibleProgress.content.id
        }
        uiState.readingProgress = visibleProgress.progress
    }

    private fun persistVisibleChapterProgress(
        visibleProgress: VisibleChapterProgress? = currentVisibleChapterProgress()
    ) {
        if (!canPersistProgress) return
        val visible = visibleProgress ?: return
        if (!uiState.lazyListState.isScrollInProgress && visible.content.id != uiState.readingContentId) {
            debugLog {
                "persistProgressSkipped book=${uiState.bookId} reason=idleChapterChange candidate=${visible.content.id} " +
                        "current=${uiState.readingContentId} progress=${visible.progress} firstIndex=${uiState.lazyListState.firstVisibleItemIndex} " +
                        "firstOffset=${uiState.lazyListState.firstVisibleItemScrollOffset} firstItem=${firstVisibleItemSummary()} " +
                        "slots=${slotsSummary()} visible=${visibleItemsSummary()}"
            }
            return
        }
        val readingContentIdBefore = uiState.readingContentId
        val readingProgressBefore = uiState.readingProgress
        val restoreProgressBefore = uiState.restoreProgress
        debugLog {
            "persistProgress book=${uiState.bookId} chapter=${visible.content.id} progress=${visible.progress} " +
                    "readingBefore=$readingContentIdBefore/$readingProgressBefore restoreBefore=$restoreProgressBefore " +
                    "firstIndex=${uiState.lazyListState.firstVisibleItemIndex} " +
                    "firstOffset=${uiState.lazyListState.firstVisibleItemScrollOffset} firstItem=${firstVisibleItemSummary()} " +
                    "slots=${slotsSummary()}"
        }
        applyVisibleChapterProgress(visible)
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
        if (uiState.restoreVersion != restoredVersion) {
            debugLog {
                "restoreComplete ignored book=${uiState.bookId} restoredVersion=$restoredVersion " +
                        "currentVersion=${uiState.restoreVersion} consumed=${uiState.consumedRestoreVersion} " +
                        "reading=${uiState.readingContentId}/${uiState.readingProgress} restore=${uiState.restoreProgress} " +
                        "canPersistBefore=$canPersistProgress slots=${slotsSummary()}"
            }
            return
        }
        debugLog {
            "restoreComplete accepted book=${uiState.bookId} restoredVersion=$restoredVersion " +
                    "consumedBefore=${uiState.consumedRestoreVersion} reading=${uiState.readingContentId}/${uiState.readingProgress} " +
                    "restore=${uiState.restoreProgress} canPersistBefore=$canPersistProgress slots=${slotsSummary()}"
        }
        uiState.consumedRestoreVersion = restoredVersion
        canPersistProgress = true
    }

    private fun writeProgressRightNow() {
        persistVisibleChapterProgress()
    }

    private fun progressScrollLoad() {
        progressScrollLoadJob?.cancel()
        progressScrollLoadJob = coroutineScope.launch {
            snapshotFlow { uiState.lazyListState.layoutInfo.visibleItemsInfo }
                .throttleLatest(64L)
                .collect { visibleItems ->
                updateVisibleComponentHeights(visibleItems)
                val firstIndex = uiState.lazyListState.firstVisibleItemIndex
                val firstOffset = uiState.lazyListState.firstVisibleItemScrollOffset
                val scrollDirection = scrollDirectionFrom(lastRotationFirstIndex, lastRotationFirstOffset)
                lastRotationFirstIndex = firstIndex
                lastRotationFirstOffset = firstOffset
                if (!canPersistProgress || uiState.shouldRestoreProgress || !uiState.lazyListState.isScrollInProgress) {
                    lastRotationVisibleItemSizes.clear()
                    return@collect
                }
                val currentContent = uiState.contentList[1] ?: run {
                    updateVisibleItemSizes(lastRotationVisibleItemSizes, visibleItems)
                    return@collect
                }
                val topAnchor = viewportTopChapterItem(visibleItems) ?: run {
                    updateVisibleItemSizes(lastRotationVisibleItemSizes, visibleItems)
                    return@collect
                }
                val bottomAnchor = viewportBottomChapterItem(visibleItems) ?: run {
                    updateVisibleItemSizes(lastRotationVisibleItemSizes, visibleItems)
                    return@collect
                }
                val previousTopAnchorSize = lastRotationVisibleItemSizes[topAnchor.itemInfo.key]
                val previousBottomAnchorSize = lastRotationVisibleItemSizes[bottomAnchor.itemInfo.key]
                updateVisibleItemSizes(lastRotationVisibleItemSizes, visibleItems)
                val topAnchorSizeChanged = previousTopAnchorSize?.let { it != topAnchor.itemInfo.size } == true
                val bottomAnchorSizeChanged = previousBottomAnchorSize?.let { it != bottomAnchor.itemInfo.size } == true
                if (scrollDirection == 0) {
                    debugLog {
                        "rotationSkipped book=${uiState.bookId} reason=noScrollDelta " +
                                "top=${topAnchor.itemInfo.index}:${topAnchor.itemInfo.key}@${topAnchor.itemInfo.offset}+${topAnchor.itemInfo.size} " +
                                "bottom=${bottomAnchor.itemInfo.index}:${bottomAnchor.itemInfo.key}@${bottomAnchor.itemInfo.offset}+${bottomAnchor.itemInfo.size} " +
                                "lazyHeight=${lazyColumnSize.height} slots=${slotsSummary()} current=${currentContent.id} " +
                                "visible=${visibleItemsSummary()}"
                    }
                    return@collect
                }
                if (topAnchor.content.id == currentContent.lastChapter && currentContent.hasPrevChapter() && scrollDirection < 0) {
                    val itemInfo = topAnchor.itemInfo
                    if (topAnchorSizeChanged || bottomAnchor.content.id == currentContent.nextChapter) {
                        debugLog {
                            "rotationSkipped book=${uiState.bookId} direction=prev " +
                                    "reason=${if (topAnchorSizeChanged) "anchorSizeChanged" else "nextStillVisible"} " +
                                    "top=${itemInfo.index}:${itemInfo.key}@${itemInfo.offset}+${itemInfo.size} previousTopSize=$previousTopAnchorSize " +
                                    "bottom=${bottomAnchor.itemInfo.index}:${bottomAnchor.itemInfo.key}@${bottomAnchor.itemInfo.offset}+${bottomAnchor.itemInfo.size} " +
                                    "lazyHeight=${lazyColumnSize.height} slots=${slotsSummary()} current=${currentContent.id} " +
                                    "visible=${visibleItemsSummary()}"
                        }
                        return@collect
                    }
                    debugLog {
                        "rotationTrigger direction=prev book=${uiState.bookId} anchor=viewportTop " +
                                "item=${itemInfo.index}:${itemInfo.key}@${itemInfo.offset}+${itemInfo.size} " +
                                "lazyHeight=${lazyColumnSize.height} slotsBefore=${slotsSummary()} current=${currentContent.id} " +
                                "last=${currentContent.lastChapter} next=${currentContent.nextChapter} " +
                                "readingBefore=${uiState.readingContentId} requestedBefore=$requestedChapterId " +
                                "collectingLast=$collectingLastChapterId collectingNext=$collectingNextChapterId " +
                                "visible=${visibleItemsSummary()}"
                    }
                    val activeProgressAfterRotation = chapterProgressOf(bottomAnchor)
                    collectNextChapterJob?.cancel()
                    collectCurrentChapterJob?.cancel()
                    collectLastChapterJob?.cancel()
                    val chapter1 = uiState.contentList[1]
                    val chapter0 = uiState.contentList[0]
                    resetContentList()
                    uiState.contentList[2] = chapter1
                    uiState.contentList[1] = chapter0
                    pruneContentComponentsMap()
                    collectingNextChapterId = currentContent.id
                    collectNextChapterJob = collectChapter(2, currentContent.id)
                    requestedChapterId = currentContent.lastChapter
                    collectCurrentChapterJob = collectChapter(1, requestedChapterId)
                    applyVisibleChapterProgress(activeProgressAfterRotation)
                    uiState.contentList[1]?.takeIf { it.hasPrevChapter() }?.let {
                        collectingLastChapterId = it.lastChapter
                        collectLastChapterJob = collectChapter(0, it.lastChapter)
                    }
                    debugLog {
                        "rotationApplied direction=prev book=${uiState.bookId} slotsAfter=${slotsSummary()} " +
                                "readingAfter=${uiState.readingContentId} requestedAfter=$requestedChapterId " +
                                "collectingLast=$collectingLastChapterId collectingNext=$collectingNextChapterId"
                    }
                    return@collect
                }
                if (bottomAnchor.content.id == currentContent.nextChapter && currentContent.hasNextChapter() && scrollDirection > 0) {
                    val itemInfo = bottomAnchor.itemInfo
                    if (bottomAnchorSizeChanged || topAnchor.content.id == currentContent.lastChapter) {
                        debugLog {
                            "rotationSkipped book=${uiState.bookId} direction=next " +
                                    "reason=${if (bottomAnchorSizeChanged) "anchorSizeChanged" else "prevStillVisible"} " +
                                    "top=${topAnchor.itemInfo.index}:${topAnchor.itemInfo.key}@${topAnchor.itemInfo.offset}+${topAnchor.itemInfo.size} " +
                                    "bottom=${itemInfo.index}:${itemInfo.key}@${itemInfo.offset}+${itemInfo.size} previousBottomSize=$previousBottomAnchorSize " +
                                    "lazyHeight=${lazyColumnSize.height} slots=${slotsSummary()} current=${currentContent.id} " +
                                    "visible=${visibleItemsSummary()}"
                        }
                        return@collect
                    }
                    debugLog {
                        "rotationTrigger direction=next book=${uiState.bookId} anchor=viewportBottom " +
                                "item=${itemInfo.index}:${itemInfo.key}@${itemInfo.offset}+${itemInfo.size} " +
                                "lazyHeight=${lazyColumnSize.height} slotsBefore=${slotsSummary()} current=${currentContent.id} " +
                                "last=${currentContent.lastChapter} next=${currentContent.nextChapter} " +
                                "readingBefore=${uiState.readingContentId} requestedBefore=$requestedChapterId " +
                                "collectingLast=$collectingLastChapterId collectingNext=$collectingNextChapterId " +
                                "visible=${visibleItemsSummary()}"
                    }
                    val activeProgressAfterRotation = chapterProgressOf(bottomAnchor)
                    collectNextChapterJob?.cancel()
                    collectCurrentChapterJob?.cancel()
                    collectLastChapterJob?.cancel()
                    val chapter1 = uiState.contentList[1]
                    val chapter2 = uiState.contentList[2]
                    resetContentList()
                    uiState.contentList[0] = chapter1
                    uiState.contentList[1] = chapter2
                    pruneContentComponentsMap()
                    collectingLastChapterId = currentContent.id
                    collectLastChapterJob = collectChapter(0, currentContent.id)
                    requestedChapterId = currentContent.nextChapter
                    collectCurrentChapterJob = collectChapter(1, requestedChapterId)
                    applyVisibleChapterProgress(activeProgressAfterRotation)
                    uiState.contentList[1]?.takeIf { it.hasNextChapter() }?.let {
                        collectingNextChapterId = it.nextChapter
                        collectNextChapterJob = collectChapter(2, it.nextChapter)
                    }
                    debugLog {
                        "rotationApplied direction=next book=${uiState.bookId} slotsAfter=${slotsSummary()} " +
                                "readingAfter=${uiState.readingContentId} requestedAfter=$requestedChapterId " +
                                "collectingLast=$collectingLastChapterId collectingNext=$collectingNextChapterId"
                    }
                }
            }
        }
    }

    override fun changeBookId(id: String) {
        debugLog {
            "changeBookId old=${uiState.bookId} new=$id reading=${uiState.readingContentId} slots=${slotsSummary()}"
        }
        uiState.bookId = id
        prefetchedNextChapterKey = ""
        collectingLastChapterId = ""
        collectingNextChapterId = ""
        componentHeightsByChapterId.clear()
        resetScrollTracking()
    }

    override fun loadNextChapter() {
        debugLog {
            "loadNextChapter reading=${uiState.readingChapterContent.id} hasNext=${uiState.readingChapterContent.hasNextChapter()} " +
                    "next=${uiState.readingChapterContent.nextChapter} slots=${slotsSummary()}"
        }
        if (!uiState.readingChapterContent.hasNextChapter()) return
        coroutineScope.launch {
            changeChapter(
                id = uiState.readingChapterContent.nextChapter,
                restoreProgress = true
            )
        }
    }

    override fun loadLastChapter() {
        debugLog {
            "loadLastChapter reading=${uiState.readingChapterContent.id} hasPrev=${uiState.readingChapterContent.hasPrevChapter()} " +
                    "prev=${uiState.readingChapterContent.lastChapter} slots=${slotsSummary()}"
        }
        if (!uiState.readingChapterContent.hasPrevChapter()) return
        coroutineScope.launch {
            changeChapter(
                id = uiState.readingChapterContent.lastChapter,
                restoreProgress = true
            )
        }
    }

    private fun resetContentList() {
        debugLog {
            "resetContentList before slots=${slotsSummary()} reading=${uiState.readingContentId} " +
                    "requested=$requestedChapterId collectingLast=$collectingLastChapterId collectingNext=$collectingNextChapterId"
        }
        collectingLastChapterId = ""
        collectingNextChapterId = ""
        while (uiState.contentList.size < 3) uiState.contentList.add(null)
        uiState.contentList[0] = null
        uiState.contentList[1] = null
        uiState.contentList[2] = null
        while (uiState.contentList.size > 3) uiState.contentList.removeAt(uiState.contentList.lastIndex)
        debugLog { "resetContentList after slots=${slotsSummary()}" }
    }

    override fun changeChapter(id: String, restoreProgress: Boolean) {
        debugLog {
            "changeChapter entry book=${uiState.bookId} id=$id restoreArg=$restoreProgress " +
                    "prevReading=${uiState.readingContentId}/${uiState.readingProgress} " +
                    "prevRestore=${uiState.restoreProgress} prevVersion=${uiState.restoreVersion} " +
                    "slotsBefore=${slotsSummary()} requestedBefore=$requestedChapterId"
        }
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
        resetScrollTracking()
        debugLog {
            "changeChapter reset book=${uiState.bookId} id=$id newVersion=${uiState.restoreVersion} " +
                    "reading=${uiState.readingContentId}/${uiState.readingProgress} restore=${uiState.restoreProgress} " +
                    "slotsAfter=${slotsSummary()} canPersist=$canPersistProgress"
        }
        coroutineScope.launch (Dispatchers.IO) {
            val isUsingContinuousScrolling = settingState.isUsingContinuousScrollingUserData.getOrDefault(true)
            debugLog {
                "changeChapter mode book=${uiState.bookId} id=$id continuous=$isUsingContinuousScrolling restoreArg=$restoreProgress"
            }
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
                val components = contentComponentRepository.getContentDataFromJson(content.content).components
                debugLog {
                    "currentLoaded mode=single book=${uiState.bookId} chapter=${content.id} restoreArg=$restoreProgress " +
                            "savedProgress=$savedProgress requested=$requestedChapterId readingBefore=${uiState.readingContentId} " +
                            "restoreVersionBefore=${uiState.restoreVersion} hasPrev=${content.hasPrevChapter()} prev=${content.lastChapter} " +
                            "hasNext=${content.hasNextChapter()} next=${content.nextChapter} componentCount=${components.size}"
                }
                uiState.readingProgress = savedProgress
                uiState.restoreProgress = savedProgress
                uiState.contentList[1] = content
                uiState.contentComponentsMap[content.id] = components
                pruneContentComponentsMap()
                uiState.restoreVersion++
                debugLog {
                    "currentLoadedApplied mode=single book=${uiState.bookId} chapter=${content.id} restoreVersionAfter=${uiState.restoreVersion} " +
                            "reading=${uiState.readingContentId}/${uiState.readingProgress} restore=${uiState.restoreProgress} slots=${slotsSummary()}"
                }
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
                val components = contentComponentRepository.getContentDataFromJson(content.content).components
                debugLog {
                    "currentLoaded mode=continuous book=${uiState.bookId} chapter=${content.id} restoreArg=$restoreProgress " +
                            "savedProgress=$savedProgress requested=$requestedChapterId readingBefore=${uiState.readingContentId} " +
                            "restoreVersionBefore=${uiState.restoreVersion} hasPrev=${content.hasPrevChapter()} prev=${content.lastChapter} " +
                            "hasNext=${content.hasNextChapter()} next=${content.nextChapter} componentCount=${components.size}"
                }
                uiState.readingProgress = savedProgress
                uiState.restoreProgress = savedProgress
                uiState.contentList[1] = content
                uiState.contentComponentsMap[content.id] = components
                pruneContentComponentsMap()
                uiState.restoreVersion++
                debugLog {
                    "currentLoadedApplied mode=continuous book=${uiState.bookId} chapter=${content.id} restoreVersionAfter=${uiState.restoreVersion} " +
                            "reading=${uiState.readingContentId}/${uiState.readingProgress} restore=${uiState.restoreProgress} slots=${slotsSummary()}"
                }
                bookRepository.updateUserReadingData(uiState.bookId) { userReadingData ->
                    userReadingData.apply {
                        lastReadTime = LocalDateTime.now()
                        lastReadChapterId = id
                        lastReadChapterTitle = content.title
                    }
                }
                if (content.hasPrevChapter()) {
                    if (collectingLastChapterId != content.lastChapter) {
                        debugLog {
                            "adjacentCollectStart slot=0 book=${uiState.bookId} chapter=${content.lastChapter} " +
                                    "current=${content.id} previousCollecting=$collectingLastChapterId slots=${slotsSummary()}"
                        }
                        collectLastChapterJob?.cancel()
                        collectingLastChapterId = content.lastChapter
                        collectLastChapterJob = collectChapter(0, content.lastChapter)
                    }
                } else {
                    debugLog { "adjacentCollectCancel slot=0 book=${uiState.bookId} current=${content.id} noPrev slots=${slotsSummary()}" }
                    collectLastChapterJob?.cancel()
                    collectingLastChapterId = ""
                }
                if (content.hasNextChapter()) {
                    if (collectingNextChapterId != content.nextChapter) {
                        debugLog {
                            "adjacentCollectStart slot=2 book=${uiState.bookId} chapter=${content.nextChapter} " +
                                    "current=${content.id} previousCollecting=$collectingNextChapterId slots=${slotsSummary()}"
                        }
                        collectNextChapterJob?.cancel()
                        collectingNextChapterId = content.nextChapter
                        collectNextChapterJob = collectChapter(2, content.nextChapter)
                    }
                } else {
                    debugLog { "adjacentCollectCancel slot=2 book=${uiState.bookId} current=${content.id} noNext slots=${slotsSummary()}" }
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
            1 -> requestedChapterId == chapterId
            2 -> currentContent?.nextChapter == chapterId
            else -> false
        }
    }

    private fun collectChapter(index: Int, chapterId: String) = coroutineScope.launch {
            debugLog {
                "adjacentCollect begin book=${uiState.bookId} slot=$index chapter=$chapterId " +
                        "currentSlot1=${uiState.contentList.getOrNull(1)?.id} requested=$requestedChapterId slots=${slotsSummary()}"
            }
            bookRepository.getChapterContentFlow(chapterId, uiState.bookId)
                .collect { content ->
                    val expected = isExpectedChapter(index, chapterId)
                    if (content.isEmpty() || content.id != chapterId || !expected) {
                        debugLog {
                            "adjacentCollect rejected book=${uiState.bookId} slot=$index requestedChapter=$chapterId " +
                                    "emitted=${content.id} isEmpty=${content.isEmpty()} expected=$expected " +
                                    "currentSlot1=${uiState.contentList.getOrNull(1)?.id} reading=${uiState.readingContentId} " +
                                    "requested=$requestedChapterId last=${uiState.contentList.getOrNull(1)?.lastChapter} " +
                                    "next=${uiState.contentList.getOrNull(1)?.nextChapter} slots=${slotsSummary()}"
                        }
                        return@collect
                    }
                    val components = contentComponentRepository.getContentDataFromJson(content.content).components
                    debugLog {
                        "adjacentCollect accepted book=${uiState.bookId} slot=$index requestedChapter=$chapterId " +
                                "emitted=${content.id} componentCount=${components.size} currentSlot1=${uiState.contentList.getOrNull(1)?.id} " +
                                "reading=${uiState.readingContentId} requested=$requestedChapterId slotsBefore=${slotsSummary()}"
                    }
                    uiState.contentList[index] = content
                    uiState.contentComponentsMap[content.id] = components
                    pruneContentComponentsMap()
                    debugLog { "adjacentCollect applied book=${uiState.bookId} slot=$index chapter=${content.id} slotsAfter=${slotsSummary()}" }
                }
        }
}