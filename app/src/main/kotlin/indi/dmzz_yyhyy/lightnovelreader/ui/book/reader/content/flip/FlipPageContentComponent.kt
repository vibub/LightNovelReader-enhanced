package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.flip

import androidx.compose.foundation.Image
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.rememberTextMeasurer
import com.github.michaelbull.result.onErr
import com.github.michaelbull.result.onOk
import indi.dmzz_yyhyy.lightnovelreader.BuildConfig
import indi.dmzz_yyhyy.lightnovelreader.R
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.testTagsAsResourceId
import indi.dmzz_yyhyy.lightnovelreader.data.content.component.readerContentTextColor
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.ChapterEndContext
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.ReaderChapterEnd
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.SettingState
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.toChapterEndContext
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.ChapterContentError
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.ChapterContentLoading
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.ChapterContentUiState
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.componet.readerRubyTextStyle
import indi.dmzz_yyhyy.lightnovelreader.ui.home.settings.data.MenuOptions
import indi.dmzz_yyhyy.lightnovelreader.utils.LocalSnackbarHost
import indi.dmzz_yyhyy.lightnovelreader.utils.readerTextColor
import indi.dmzz_yyhyy.lightnovelreader.utils.rememberReaderFontFamily
import indi.dmzz_yyhyy.lightnovelreader.utils.rememberReaderBackgroundPainter
import kotlinx.coroutines.flow.first
import indi.dmzz_yyhyy.lightnovelreader.utils.showSnackbar
import io.nightfish.lightnovelreader.api.content.component.AbstractContentComponent
import io.nightfish.lightnovelreader.api.ui.LocalReaderStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.absoluteValue
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun FlipPageContentComponent(
    modifier: Modifier,
    uiState: FlipPageContentUiState,
    settingState: SettingState,
    paddingValues: PaddingValues,
    changeIsImmersive: () -> Unit,
    onClickPrevChapter: () -> Unit,
    onClickNextChapter: () -> Unit,
    bookId: String,
    nextChapterTitle: String?,
    onClickChapterComments: ((ChapterEndContext) -> Unit)?
) {
    uiState.readingChapterContent?.onOk {
        SimpleFlipPageTextComponent(
            modifier, paddingValues, uiState, it, settingState, changeIsImmersive,
            onClickPrevChapter, onClickNextChapter, bookId, nextChapterTitle, onClickChapterComments
        )
    }?.onErr { error ->
        ChapterContentError(error) { uiState.readingChapterId?.let(uiState.changeChapter) }
    } ?: ChapterContentLoading()
}

@Composable
private fun SimpleFlipPageTextComponent(
    modifier: Modifier,
    paddingValues: PaddingValues,
    uiState: FlipPageContentUiState,
    chapterContent: ChapterContentUiState,
    settingState: SettingState,
    changeIsImmersive: () -> Unit,
    onClickPrevChapter: () -> Unit,
    onClickNextChapter: () -> Unit,
    bookId: String,
    nextChapterTitle: String?,
    onClickChapterComments: ((ChapterEndContext) -> Unit)?
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val density = LocalDensity.current
    val readerStyle = LocalReaderStyle.current
    val baseStyle = MaterialTheme.typography.bodyMedium
    val textStyle = readerRubyTextStyle(
        readerStyle,
        rememberReaderFontFamily(settingState.fontUriUserData),
        readerContentTextColor(readerStyle.textColor, readerStyle.textDarkColor)
    )
    val measurer = rememberTextMeasurer()
    val focusRequester = remember { FocusRequester() }
    val windowInfo = LocalWindowInfo.current
    val snackbarHostState = LocalSnackbarHost.current
    val firstPageText = stringResource(R.string.reader_first_page)
    val previousChapterText = stringResource(R.string.previous_chapter)
    val lastPageText = stringResource(R.string.reader_last_page)
    val nextChapterText = stringResource(R.string.next_chapter)

    val layoutDirection = LocalLayoutDirection.current
    val loopBackgroundEnabled = settingState.enableBackgroundImage &&
        settingState.backgroundImageDisplayMode == MenuOptions.ReaderBgImageDisplayModeOptions.Loop
    val backgroundPainter = if (loopBackgroundEnabled) rememberReaderBackgroundPainter(settingState) else null
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val width = (constraints.maxWidth - with(density) {
            paddingValues.calculateStartPadding(layoutDirection).roundToPx() +
                paddingValues.calculateEndPadding(layoutDirection).roundToPx()
        }).coerceAtLeast(0)
        val height = (constraints.maxHeight - with(density) {
            paddingValues.calculateTopPadding().roundToPx() + paddingValues.calculateBottomPadding().roundToPx()
        }).coerceAtLeast(0)
        var pages by remember(chapterContent.id, chapterContent.content, width, height, textStyle, readerStyle, baseStyle, density, measurer) {
            mutableStateOf(emptyList<AbstractContentComponent<*>>())
        }
        LaunchedEffect(chapterContent.id, chapterContent.content, width, height, textStyle, readerStyle, baseStyle, density, measurer) {
            pages = withContext(Dispatchers.Default) {
                paginateReaderComponents(
                    chapterContent.content, height, width, context, readerStyle,
                    baseStyle, textStyle, measurer, density
                )
            }
        }
        val contentPageCount = pages.size
        val totalPageCount = contentPageCount + if (onClickChapterComments != null && pages.isNotEmpty()) 1 else 0
        val pagerState = remember(chapterContent.id, pages, totalPageCount) { PagerState { totalPageCount } }
        LaunchedEffect(chapterContent.id, pagerState, contentPageCount) {
            uiState.updatePageState(chapterContent.id, pagerState, contentPageCount)
        }
        if (pages.isEmpty()) {
            ChapterContentLoading()
            return@BoxWithConstraints
        }
        fun turnPage(direction: Int) {
            if (pagerState.isScrollInProgress) return
            val target = pagerState.currentPage + direction
            if (target in 0 until pagerState.pageCount) {
                scope.launch {
                    if (settingState.flipAnime != MenuOptions.FlipAnimationOptions.None) {
                        pagerState.animateScrollToPage(target)
                    } else pagerState.scrollToPage(target)
                }
            } else if (settingState.fastChapterChange) {
                if (direction > 0) uiState.loadNextChapter() else uiState.loadPrevChapter()
            } else {
                showSnackbar(
                    coroutineScope = scope,
                    hostState = snackbarHostState,
                    duration = SnackbarDuration.Short,
                    message = if (direction > 0) lastPageText else firstPageText,
                    actionLabel = if (direction > 0) nextChapterText else previousChapterText
                ) {
                    if (it == SnackbarResult.ActionPerformed) {
                        if (direction > 0) onClickNextChapter() else onClickPrevChapter()
                    }
                }
            }
        }
        var volumeJob by remember { mutableStateOf<Job?>(null) }
        val intervalMs = (settingState.volumeKeyContinuousFlipInterval * 1000).toLong()
        DisposableEffect(pagerState) {
            onDispose { volumeJob?.cancel() }
        }
        LaunchedEffect(pagerState, settingState.isUsingVolumeKeyFlip, windowInfo.isWindowFocused) {
            if (settingState.isUsingVolumeKeyFlip && windowInfo.isWindowFocused) {
                withFrameNanos { }
                focusRequester.requestFocus()
            }
        }
        val benchmarkChapterModifier = if (BuildConfig.BUILD_TYPE == "benchmark") {
            Modifier.testTag("flip-chapter-${chapterContent.id}").semantics {
                testTagsAsResourceId = true
                contentDescription = "flip-state-page=${pagerState.currentPage};" +
                    "count=${pagerState.pageCount};animating=${pagerState.isScrollInProgress}"
            }
        } else Modifier
        HorizontalPager(
            state = pagerState,
            key = { it },
            userScrollEnabled = settingState.flipAnime != MenuOptions.FlipAnimationOptions.None,
            modifier = Modifier.fillMaxSize()
                .then(benchmarkChapterModifier)
                .focusRequester(focusRequester)
                .focusable()
                .onPreviewKeyEvent { event ->
                    if (!settingState.isUsingVolumeKeyFlip || (event.key != Key.VolumeUp && event.key != Key.VolumeDown)) {
                        false
                    } else {
                        when (event.type) {
                            KeyEventType.KeyDown -> {
                                if (event.nativeKeyEvent.repeatCount == 0) {
                                    val direction = if (event.key == Key.VolumeUp) -1 else 1
                                    turnPage(direction)
                                    if (intervalMs > 0) {
                                        volumeJob?.cancel()
                                        volumeJob = scope.launch {
                                            while (isActive) {
                                                delay(intervalMs.milliseconds)
                                                turnPage(direction)
                                            }
                                        }
                                    }
                                }
                                true
                            }
                            KeyEventType.KeyUp -> {
                                volumeJob?.cancel()
                                volumeJob = null
                                true
                            }
                            else -> false
                        }
                    }
                }
                .draggable(
                    enabled = settingState.isUsingFlipPage,
                    interactionSource = remember { MutableInteractionSource() },
                    orientation = Orientation.Vertical,
                    state = rememberDraggableState {},
                    onDragStopped = { if (it.absoluteValue > 60) changeIsImmersive() }
                )
                .pointerInput(pagerState, layoutDirection, settingState.isUsingFlipPage, settingState.fastChapterChange, settingState.flipAnime) {
                    // 动画模式只观察越界拖动；无动画模式在横向拖动成立后接管翻页，长按仍交给正文。
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        val startPage = pagerState.currentPage
                        val startedAtRest = !pagerState.isScrollInProgress &&
                            pagerState.currentPageOffsetFraction.absoluteValue < 0.01f
                        val withoutAnimation = settingState.flipAnime == MenuOptions.FlipAnimationOptions.None
                        val startedAtBoundary = startPage == 0 || startPage == pagerState.pageCount - 1
                        var displacement = Offset.Zero
                        var dragStarted = false
                        var cancelled = false
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change == null || event.changes.count { it.pressed } > 1) {
                                cancelled = true
                                break
                            }
                            displacement = change.position - down.position
                            if (!dragStarted) {
                                if (change.uptimeMillis - down.uptimeMillis >= viewConfiguration.longPressTimeoutMillis) {
                                    cancelled = true
                                } else if (displacement.getDistance() > viewConfiguration.touchSlop) {
                                    dragStarted = true
                                }
                            }
                            if (withoutAnimation && settingState.isUsingFlipPage && startedAtRest &&
                                dragStarted && !cancelled && displacement.x.absoluteValue > displacement.y.absoluteValue
                            ) change.consume()
                        } while (event.changes.any { it.pressed })
                        val horizontal = if (layoutDirection == androidx.compose.ui.unit.LayoutDirection.Rtl) {
                            -displacement.x
                        } else displacement.x
                        val direction = when {
                            horizontal > size.width / 4f && (withoutAnimation || startPage == 0) -> -1
                            horizontal < -size.width / 4f && (withoutAnimation || startPage == pagerState.pageCount - 1) -> 1
                            else -> 0
                        }
                        if (settingState.isUsingFlipPage && startedAtRest &&
                            (withoutAnimation || startedAtBoundary) && !cancelled &&
                            dragStarted && direction != 0 && horizontal.absoluteValue > displacement.y.absoluteValue
                        ) {
                            scope.launch {
                                withFrameNanos { }
                                snapshotFlow { pagerState.isScrollInProgress }.first { !it }
                                if (uiState.readingChapterId == chapterContent.id &&
                                    uiState.pagerState === pagerState && pagerState.currentPage == startPage
                                ) turnPage(direction)
                            }
                        }
                    }
                }
                .pointerInput(pagerState, settingState.isUsingClickFlipPage, settingState.isUsingFlipPage, settingState.flipAnime, settingState.fastChapterChange) {
                    detectTapGestures { position ->
                        if (settingState.isUsingFlipPage && settingState.isUsingClickFlipPage) {
                            when {
                                position.x < size.width / 3f -> turnPage(-1)
                                position.x > size.width * 2f / 3f -> turnPage(1)
                                else -> changeIsImmersive()
                            }
                        } else changeIsImmersive()
                    }
                }
        ) { page ->
            Box(Modifier.fillMaxSize()) {
                backgroundPainter?.let { painter ->
                    Image(
                        modifier = Modifier.fillMaxSize(),
                        painter = painter,
                        contentDescription = null,
                        contentScale = ContentScale.Crop
                    )
                }
                val benchmarkPageModifier = if (BuildConfig.BUILD_TYPE == "benchmark" && page == pagerState.currentPage) {
                    Modifier.testTag("flip-page-$page-${pages.getOrNull(page)?.data?.hashCode() ?: "end"}")
                } else Modifier
                Box(Modifier.fillMaxSize().padding(paddingValues).then(benchmarkPageModifier)) {
                    pages.getOrNull(page)?.Content(Modifier.fillMaxSize()) ?: onClickChapterComments?.let { onClickComments ->
                        ReaderChapterEnd(
                            context = chapterContent.toChapterEndContext(bookId),
                            nextChapterTitle = nextChapterTitle,
                            contentColor = readerTextColor(settingState),
                            onClickComments = onClickComments
                        )
                    }
                }
            }
        }
    }
}
