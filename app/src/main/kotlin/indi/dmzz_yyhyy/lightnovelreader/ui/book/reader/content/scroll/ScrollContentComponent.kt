package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.scroll

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.github.michaelbull.result.get
import com.github.michaelbull.result.map
import com.github.michaelbull.result.onErr
import com.github.michaelbull.result.onOk
import indi.dmzz_yyhyy.lightnovelreader.R
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.ChapterEndContext
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.ReaderChapterEnd
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.SettingState
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.toChapterEndContext
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.ChapterContentError
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.ChapterContentLoading
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.ChapterContentUiState
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.componet.LocalReaderRubyTextCache
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.componet.LocalReaderTextScrolling
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.componet.LocalReaderTextViewport
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.componet.ReaderTextViewport
import indi.dmzz_yyhyy.lightnovelreader.ui.components.Loading
import indi.dmzz_yyhyy.lightnovelreader.ui.home.settings.data.MenuOptions
import indi.dmzz_yyhyy.lightnovelreader.utils.LocalSnackbarHost
import indi.dmzz_yyhyy.lightnovelreader.utils.readerTextColor
import indi.dmzz_yyhyy.lightnovelreader.utils.rememberReaderBackgroundPainter
import indi.dmzz_yyhyy.lightnovelreader.utils.rememberReaderFontFamily
import indi.dmzz_yyhyy.lightnovelreader.utils.showSnackbar
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
fun ScrollContentComponent(
    modifier: Modifier,
    uiState: ScrollContentUiState,
    settingState: SettingState,
    paddingValues: PaddingValues,
    changeIsImmersive: () -> Unit,
    onClickPrevChapter: () -> Unit,
    onClickNextChapter: () -> Unit,
    bookId: String,
    chapterTitleById: Map<String, String>,
    onClickChapterComments: ((ChapterEndContext) -> Unit)?
) = ScrollContentTextComponent(
    modifier, uiState, settingState, paddingValues, changeIsImmersive,
    onClickPrevChapter, onClickNextChapter, bookId, chapterTitleById, onClickChapterComments
)

@Composable
fun ScrollContentTextComponent(
    modifier: Modifier,
    uiState: ScrollContentUiState,
    settingState: SettingState,
    paddingValues: PaddingValues,
    changeIsImmersive: () -> Unit,
    onClickPrevChapter: () -> Unit,
    onClickNextChapter: () -> Unit,
    bookId: String,
    chapterTitleById: Map<String, String>,
    onClickChapterComments: ((ChapterEndContext) -> Unit)?
) {
    val snackbarHostState = LocalSnackbarHost.current
    val density = LocalDensity.current
    val screenHeight = LocalResources.current.displayMetrics.heightPixels
    val listState = uiState.lazyListState
    val currentResult = uiState.contentList.getOrNull(1)?.second
    val chapterLoadFailed = currentResult != null && currentResult.get() == null
    val isTextScrolling = remember(listState) { { listState.isScrollInProgress } }
    var lazyColumnSize by remember { mutableStateOf(IntSize(0, 0)) }
    val rubyTextCache = rememberReaderRubyTextCache(uiState, settingState, lazyColumnSize.width)
    var textViewport by remember(screenHeight) { mutableStateOf(ReaderTextViewport(0f, screenHeight.toFloat())) }
    val loopBackgroundEnabled = settingState.enableBackgroundImage &&
        settingState.backgroundImageDisplayMode == MenuOptions.ReaderBgImageDisplayModeOptions.Loop
    var backgroundViewportHeightPx by remember { mutableIntStateOf(0) }
    var backgroundPhasePx by remember { mutableFloatStateOf(0f) }
    val backgroundScrollConnection = remember(loopBackgroundEnabled) {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (loopBackgroundEnabled && backgroundViewportHeightPx > 0 && consumed.y != 0f) {
                    backgroundPhasePx = positiveModulo(backgroundPhasePx + consumed.y, backgroundViewportHeightPx.toFloat())
                }
                return Offset.Zero
            }
        }
    }
    LaunchedEffect(loopBackgroundEnabled) { backgroundPhasePx = 0f }
    val reachedTopMsg = stringResource(R.string.reader_reached_top)
    val prevChapterLabel = stringResource(R.string.previous_chapter)
    val reachedBottomMsg = stringResource(R.string.reader_reached_bottom)
    val nextChapterLabel = stringResource(R.string.next_chapter)
    val confirmLabel = stringResource(R.string.confirm)
    val reachedStartMsg = stringResource(R.string.reader_reached_start)
    val reachedEndMsg = stringResource(R.string.reader_reached_end)

    LaunchedEffect(listState) {
        val chapterId = uiState.readingChapterId ?: return@LaunchedEffect
        val continuous = settingState.isUsingContinuousScrolling
        // 等上一章槽位就绪后才定位，避免占位内容补齐时将阅读位置推回首项。
        snapshotFlow {
            val current = uiState.contentList.getOrNull(1)?.second?.get()
            val prevId = current?.prevChapter?.takeIf { it.isNotBlank() && it != current.id && it != current.nextChapter }
            Triple(current?.id, prevId, uiState.contentList.getOrNull(0)?.first)
        }.first { (currentId, prevId, loadedPrevId) ->
            currentId == chapterId && (!continuous || prevId == null || loadedPrevId == prevId)
        }
        withFrameNanos { }
        listState.scrollToItem(1)
        val item = snapshotFlow {
            listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == chapterId }
        }.first { it != null } ?: return@LaunchedEffect
        snapshotFlow { lazyColumnSize }.first { it.height > 0 }
        if (uiState.readingChapterId != chapterId) return@LaunchedEffect
        val offset = ((item.size - lazyColumnSize.height).coerceAtLeast(0) * uiState.readingProgress).toInt()
        listState.scrollToItem(item.index, offset)
        withFrameNanos { }
        uiState.isInitialPositioned = true
    }
    LaunchedEffect(listState) {
        var atTop = false
        var atBottom = false
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (scrolling || !uiState.isInitialPositioned) return@collect
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()
            val isAtTop = listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
            val isAtBottom = lastVisible != null && lastVisible.index == info.totalItemsCount - 1 &&
                lastVisible.offset + lastVisible.size <= info.viewportEndOffset
            when {
                isAtTop -> {
                    if (atTop) launch {
                        val hasPrev = uiState.readingChapterContent?.map { it.hasPrevChapter() }?.get() == true
                        showSnackbar(this, snackbarHostState,
                            message = if (hasPrev) reachedTopMsg else reachedStartMsg,
                            actionLabel = if (hasPrev) prevChapterLabel else confirmLabel
                        ) { if (hasPrev && it == SnackbarResult.ActionPerformed) onClickPrevChapter() }
                    }
                    atTop = true
                    atBottom = false
                }
                isAtBottom -> {
                    if (atBottom) launch {
                        val hasNext = uiState.readingChapterContent?.map { it.hasNextChapter() }?.get() == true
                        showSnackbar(this, snackbarHostState,
                            message = if (hasNext) reachedBottomMsg else reachedEndMsg,
                            actionLabel = if (hasNext) nextChapterLabel else confirmLabel
                        ) { if (hasNext && it == SnackbarResult.ActionPerformed) onClickNextChapter() }
                    }
                    atBottom = true
                    atTop = false
                }
                else -> {
                    snackbarHostState.currentSnackbarData?.dismiss()
                    atTop = false
                    atBottom = false
                }
            }
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { uiState.writeProgressRightNow() }
    val backgroundPainter = if (loopBackgroundEnabled) rememberReaderBackgroundPainter(settingState) else null
    Box(
        modifier = Modifier.fillMaxSize().clipToBounds()
            .onGloballyPositioned { backgroundViewportHeightPx = it.size.height }
    ) {
        if (backgroundPainter != null && backgroundViewportHeightPx > 0) {
            val backgroundHeight = with(density) { backgroundViewportHeightPx.toDp() }
            Image(
                modifier = Modifier.fillMaxWidth().height(backgroundHeight)
                    .graphicsLayer { translationY = backgroundPhasePx - backgroundViewportHeightPx },
                painter = backgroundPainter,
                contentDescription = null,
                contentScale = ContentScale.Crop
            )
            Image(
                modifier = Modifier.fillMaxWidth().height(backgroundHeight)
                    .graphicsLayer { translationY = backgroundPhasePx },
                painter = backgroundPainter,
                contentDescription = null,
                contentScale = ContentScale.Crop
            )
        }
        AnimatedVisibility(
            uiState.contentList.getOrNull(1) == null || (!uiState.isInitialPositioned && !chapterLoadFailed),
            enter = fadeIn(), exit = fadeOut()
        ) { Loading() }
        AnimatedVisibility(uiState.contentList.getOrNull(1) != null, enter = fadeIn(), exit = fadeOut()) {
            LazyColumn(
                modifier = modifier.alpha(if (uiState.isInitialPositioned || chapterLoadFailed) 1f else 0f)
                    .nestedScroll(backgroundScrollConnection)
                    .padding(paddingValues)
                    .pointerInput(Unit) { detectTapGestures(onTap = { changeIsImmersive() }) }
                    .onGloballyPositioned {
                        if (lazyColumnSize != it.size) {
                            uiState.setLazyColumnSize(it.size)
                            lazyColumnSize = it.size
                        }
                        val bounds = it.boundsInWindow()
                        val viewport = ReaderTextViewport(bounds.top, bounds.bottom)
                        if (textViewport != viewport) textViewport = viewport
                    },
                state = listState
            ) {
                itemsIndexed(uiState.contentList, key = { index, pair -> pair?.first ?: "placeholder-$index" }) { index, pair ->
                    val result = pair?.second ?: return@itemsIndexed
                    uiState.contentList.getOrNull(index + 1)?.second?.get()?.let {
                        if (!it.hasPrevChapter()) return@itemsIndexed
                    }
                    uiState.contentList.getOrNull(index - 1)?.second?.get()?.let {
                        if (!it.hasNextChapter()) return@itemsIndexed
                    }
                    val chapterId = pair.first
                    if (chapterId in uiState.retryingChapterIds) {
                        ChapterContentLoading()
                    } else {
                        result.onOk {
                            CompositionLocalProvider(
                                LocalReaderRubyTextCache provides rubyTextCache,
                                LocalReaderTextViewport provides textViewport,
                                LocalReaderTextScrolling provides isTextScrolling
                            ) {
                                TextContent(Modifier, settingState, it, bookId, chapterTitleById[it.nextChapter], onClickChapterComments)
                            }
                        }.onErr { error ->
                            ChapterContentError(error) { uiState.retryChapter(index, chapterId) }
                        }
                    }
                }
            }
        }
    }
}

private fun positiveModulo(value: Float, modulus: Float): Float =
    if (modulus <= 0f) 0f else ((value % modulus) + modulus) % modulus

@Composable
private fun TextContent(
    modifier: Modifier,
    settingState: SettingState,
    content: ChapterContentUiState,
    bookId: String,
    nextChapterTitle: String?,
    onClickChapterComments: ((ChapterEndContext) -> Unit)?
) {
    val density = LocalDensity.current
    val screenHeight = LocalResources.current.displayMetrics.heightPixels
    val textColor = readerTextColor(settingState)
    val fontFamily = rememberReaderFontFamily(settingState.fontUriUserData)
    Column(modifier.defaultMinSize(minHeight = with(density) { screenHeight.toDp() })) {
        val match = Regex("^(第[一二三四五六七八九十]+卷)\\s+(.*)").find(content.title)
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 36.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (match != null) {
                Text(
                    text = match.groupValues[1], textAlign = TextAlign.Center,
                    fontSize = (settingState.fontSize + 2).sp, fontWeight = FontWeight.Medium,
                    fontFamily = fontFamily, color = textColor, modifier = Modifier.fillMaxWidth()
                )
            }
            Text(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                text = match?.groupValues?.get(2) ?: content.title,
                textAlign = TextAlign.Center,
                fontSize = (settingState.fontSize + 6).sp,
                lineHeight = ((settingState.fontSize + 6) * settingState.lineHeight).sp,
                fontWeight = FontWeight((settingState.fontWeigh.toInt() + 100).coerceIn(1, 1000)),
                fontFamily = fontFamily, color = textColor
            )
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                HorizontalDivider(modifier = Modifier.width(48.dp), color = textColor)
            }
            Spacer(Modifier.height(16.dp))
        }
        content.content.forEach { it.Content(Modifier.fillMaxWidth()) }
        onClickChapterComments?.let { onClickComments ->
            ReaderChapterEnd(content.toChapterEndContext(bookId), nextChapterTitle, textColor, onClickComments)
        }
    }
}
