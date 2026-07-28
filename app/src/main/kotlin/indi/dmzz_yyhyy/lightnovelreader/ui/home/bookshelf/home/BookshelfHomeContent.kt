package indi.dmzz_yyhyy.lightnovelreader.ui.home.bookshelf.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults.SecondaryIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.promeg.pinyinhelper.Pinyin
import indi.dmzz_yyhyy.lightnovelreader.R
import indi.dmzz_yyhyy.lightnovelreader.ui.components.EmptyPage
import indi.dmzz_yyhyy.lightnovelreader.ui.home.bookshelf.BookshelfCardSnapshot
import indi.dmzz_yyhyy.lightnovelreader.utils.bottomBarPadding
import indi.dmzz_yyhyy.lightnovelreader.utils.bottomBarSpacer
import indi.dmzz_yyhyy.lightnovelreader.utils.navigationBarSpacer
import io.nightfish.lightnovelreader.api.bookshelf.BookshelfSortType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.text.Collator
import java.time.LocalDateTime
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BookshelfHomeContent(
    uiState: BookshelfHomeUiState,
    dataSources: BookshelfHomeDataSources,
    listState: LazyListState,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = !uiState.selectMode,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            if (uiState.bookshelfList.isNotEmpty()) {
                val selectedIndex = uiState.selectedTabIndex
                    .takeIf { it in uiState.bookshelfList.indices } ?: 0

                PrimaryScrollableTabRow(
                    selectedTabIndex = selectedIndex,
                    edgePadding = 0.dp,
                    indicator = {
                        SecondaryIndicator(
                            modifier = Modifier
                                .tabIndicatorOffset(
                                    selectedTabIndex = selectedIndex,
                                    matchContentSize = true
                                )
                                .height(4.dp)
                                .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)),
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                ) {
                    uiState.bookshelfList.forEach { bookshelf ->
                        Tab(
                            selected = uiState.selectedBookshelfId == bookshelf.id,
                            onClick = {
                                if (!uiState.selectMode) uiState.changePage(bookshelf.id)
                            },
                            text = {
                                Text(
                                    text = bookshelf.name,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        )
                    }
                }
            }
        }

        val selectedBookshelfUiState = uiState.selectedBookshelf
        if (selectedBookshelfUiState != null) {
            val locale = LocalConfiguration.current.locales[0]
            val bookSortSnapshotsFlow = remember(
                selectedBookshelfUiState.allBookIds,
                selectedBookshelfUiState.sortType,
                dataSources,
                locale
            ) {
                if (selectedBookshelfUiState.sortType == BookshelfSortType.Default) {
                    flowOf(emptyList())
                } else {
                    createBookSortSnapshotsFlow(
                        bookIds = selectedBookshelfUiState.allBookIds,
                        sortType = selectedBookshelfUiState.sortType,
                        dataSources = dataSources,
                        locale = locale
                    )
                }
            }
            val bookSortSnapshots by bookSortSnapshotsFlow.collectAsStateWithLifecycle(emptyList())
            val sortedAllBookIds = remember(
                selectedBookshelfUiState.allBookIds,
                selectedBookshelfUiState.sortType,
                selectedBookshelfUiState.sortReversed,
                bookSortSnapshots
            ) {
                sortBookIds(
                    source = selectedBookshelfUiState.allBookIds,
                    allBookIds = selectedBookshelfUiState.allBookIds,
                    sortType = selectedBookshelfUiState.sortType,
                    sortReversed = selectedBookshelfUiState.sortReversed,
                    bookSortSnapshots = bookSortSnapshots
                )
            }
            val sortedUpdatedBookIds = remember(
                selectedBookshelfUiState.updatedBookIds,
                selectedBookshelfUiState.allBookIds,
                selectedBookshelfUiState.sortType,
                selectedBookshelfUiState.sortReversed,
                bookSortSnapshots
            ) {
                sortBookIds(
                    source = selectedBookshelfUiState.updatedBookIds,
                    allBookIds = selectedBookshelfUiState.allBookIds,
                    sortType = selectedBookshelfUiState.sortType,
                    sortReversed = selectedBookshelfUiState.sortReversed,
                    bookSortSnapshots = bookSortSnapshots
                )
            }
            val sortedPinnedBookIds = remember(
                selectedBookshelfUiState.pinnedBookIds,
                selectedBookshelfUiState.allBookIds,
                selectedBookshelfUiState.sortType,
                selectedBookshelfUiState.sortReversed,
                bookSortSnapshots
            ) {
                sortBookIds(
                    source = selectedBookshelfUiState.pinnedBookIds,
                    allBookIds = selectedBookshelfUiState.allBookIds,
                    sortType = selectedBookshelfUiState.sortType,
                    sortReversed = selectedBookshelfUiState.sortReversed,
                    bookSortSnapshots = bookSortSnapshots
                )
            }

            val selectedBookIdSet = uiState.selectedBookIds.toHashSet()
            val onLongPress: (String) -> Unit = { bookId ->
                if (!uiState.selectMode) {
                    uiState.onEnableSelectMode()
                }
                uiState.changeBookSelectState(bookId)
            }
            var initialScrollApplied by remember(uiState.selectedBookshelfId) { mutableStateOf(false) }

            LaunchedEffect(uiState.selectedBookshelfId, sortedAllBookIds.isNotEmpty()) {
                if (initialScrollApplied || sortedAllBookIds.isEmpty()) return@LaunchedEffect
                listState.scrollToItem(0)
                initialScrollApplied = true
            }

            val density = LocalDensity.current
            val lineHeight = MaterialTheme.typography.titleMedium.lineHeight
            val titleHeight = with(density) { (lineHeight * 2.2f).toDp() }

            LaunchedEffect(
                selectedBookshelfUiState.allBookIds,
                selectedBookshelfUiState.sortType
            ) {
                dataSources.requestBookInformation(
                    if (selectedBookshelfUiState.sortType == BookshelfSortType.Name ||
                        selectedBookshelfUiState.sortType == BookshelfSortType.WordCount
                    ) {
                        selectedBookshelfUiState.allBookIds
                    } else {
                        emptyList()
                    }
                )
            }

            LaunchedEffect(
                uiState.selectedBookshelfId,
                sortedUpdatedBookIds,
                uiState.updatedExpanded,
                sortedPinnedBookIds,
                uiState.pinnedExpanded,
                sortedAllBookIds,
                uiState.allExpanded,
            ) {
                snapshotFlow {
                    val visibleItemKeys = listState.layoutInfo.visibleItemsInfo.map { it.key }
                    listState.isScrollInProgress to visibleItemKeys
                }.collectLatest { (isScrolling, visibleItemKeys) ->
                    if (isScrolling) return@collectLatest
                    val window = createBookshelfVisibleWindow(
                        visibleItemKeys = visibleItemKeys,
                        updatedBookIds = sortedUpdatedBookIds,
                        updatedExpanded = uiState.updatedExpanded,
                        pinnedBookIds = sortedPinnedBookIds,
                        pinnedExpanded = uiState.pinnedExpanded,
                        allBookIds = sortedAllBookIds,
                        allExpanded = uiState.allExpanded,
                    )
                    if (visibleItemKeys.isNotEmpty()) delay(120.milliseconds)
                    dataSources.updateVisibleWindow(window)
                }
            }

            DisposableEffect(uiState.selectedBookshelfId) {
                onDispose {
                    dataSources.updateVisibleWindow(BookshelfVisibleWindow())
                }
            }

            AnimatedVisibility(
                visible = selectedBookshelfUiState.allBookIds.isEmpty(),
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                EmptyPage(
                    modifier = Modifier
                        .navigationBarsPadding()
                        .bottomBarPadding(),
                    icon = painterResource(R.drawable.bookmarks_90px),
                    title = stringResource(R.string.nothing_here),
                    description = stringResource(R.string.nothing_here_desc_bookshelf)
                )
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                state = listState
            ) {
                bookshelfContent(
                    dataSources = dataSources,
                    selectedBookIdSet = selectedBookIdSet,
                    titleHeight = titleHeight,
                    isSelectMode = uiState.selectMode,
                    onClickBook = uiState.onBookClick,
                    onBookSelect = uiState.changeBookSelectState,
                    onLongPress = onLongPress,
                    updatedBookIds = sortedUpdatedBookIds,
                    updatedExpanded = uiState.updatedExpanded,
                    onToggleUpdateExpand = { uiState.updatedExpanded = !uiState.updatedExpanded },
                    pinnedBookIds = sortedPinnedBookIds,
                    pinnedExpanded = uiState.pinnedExpanded,
                    onTogglePinnedExpand = { uiState.pinnedExpanded = !uiState.pinnedExpanded },
                    allBookIds = sortedAllBookIds,
                    allExpanded = uiState.allExpanded,
                    onToggleAllExpand = { uiState.allExpanded = !uiState.allExpanded }
                )
                navigationBarSpacer()
                bottomBarSpacer()
            }
        }
    }
}

private fun LazyListScope.bookshelfContent(
    dataSources: BookshelfHomeDataSources,
    selectedBookIdSet: Set<String>,
    titleHeight: Dp,
    isSelectMode: Boolean,
    onClickBook: (String) -> Unit,
    onBookSelect: (String) -> Unit,
    onLongPress: (String) -> Unit,
    updatedBookIds: List<String>,
    updatedExpanded: Boolean,
    onToggleUpdateExpand: () -> Unit,
    pinnedBookIds: List<String>,
    pinnedExpanded: Boolean,
    onTogglePinnedExpand: () -> Unit,
    allBookIds: List<String>,
    allExpanded: Boolean,
    onToggleAllExpand: () -> Unit
) {
    if (updatedBookIds.isNotEmpty()) {
        stickyHeader {
            CollapseHeader(
                icon = painterResource(R.drawable.autorenew_24px),
                title = stringResource(R.string.bookshelf_group_title_updated, updatedBookIds.size),
                expanded = updatedExpanded,
                onToggleExpand = onToggleUpdateExpand
            )
        }
        if (updatedExpanded) {
            items(
                updatedBookIds,
                key = { "updated_$it" },
                contentType = { "book_card" }
            ) { id ->
                BookshelfBookCard(
                    id = id,
                    snapshotFlow = dataSources.cardSnapshot(id),
                    selected = selectedBookIdSet.contains(id),
                    selectMode = isSelectMode,
                    titleHeight = titleHeight,
                    onBookClick = onClickBook,
                    onBookSelect = onBookSelect,
                    onLongPress = onLongPress
                )
            }
        }
    }

    if (pinnedBookIds.isNotEmpty()) {
        stickyHeader {
            CollapseHeader(
                icon = painterResource(R.drawable.keep_24px),
                title = stringResource(R.string.bookshelf_group_title_pinned, pinnedBookIds.size),
                expanded = pinnedExpanded,
                onToggleExpand = onTogglePinnedExpand
            )
        }
        if (pinnedExpanded) {
            items(
                pinnedBookIds,
                key = { "pinned_$it" },
                contentType = { "book_card" }
            ) { id ->
                BookshelfBookCard(
                    id = id,
                    snapshotFlow = dataSources.cardSnapshot(id),
                    selected = selectedBookIdSet.contains(id),
                    selectMode = isSelectMode,
                    titleHeight = titleHeight,
                    onBookClick = onClickBook,
                    onBookSelect = onBookSelect,
                    onLongPress = onLongPress
                )
            }
        }
    }

    if (allBookIds.isNotEmpty()) {
        stickyHeader {
            CollapseHeader(
                icon = painterResource(R.drawable.outline_bookmark_24px),
                title = stringResource(R.string.bookshelf_group_title_all, allBookIds.size),
                expanded = allExpanded,
                onToggleExpand = onToggleAllExpand
            )
        }
        if (allExpanded) {
            items(
                allBookIds,
                key = { "book_$it" },
                contentType = { "book_card" }
            ) { id ->
                BookshelfBookCard(
                    id = id,
                    snapshotFlow = dataSources.cardSnapshot(id),
                    selected = selectedBookIdSet.contains(id),
                    selectMode = isSelectMode,
                    titleHeight = titleHeight,
                    onBookClick = onClickBook,
                    onBookSelect = onBookSelect,
                    onLongPress = onLongPress
                )
            }
            item {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        modifier = Modifier.padding(vertical = 18.dp),
                        text = stringResource(R.string.n_books, allBookIds.size),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.W600,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

@Composable
private fun BookshelfBookCard(
    id: String,
    snapshotFlow: StateFlow<BookshelfCardSnapshot>,
    selected: Boolean,
    selectMode: Boolean,
    titleHeight: Dp,
    onBookClick: (String) -> Unit,
    onBookSelect: (String) -> Unit,
    onLongPress: (String) -> Unit,
) {
    val snapshot by snapshotFlow.collectAsStateWithLifecycle()
    val modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp)
        .padding(vertical = 6.dp)
    val bookInformation = snapshot.bookInformation
    if (bookInformation == null) {
        BookCardContentSkeleton(modifier = modifier)
    } else {
        BookCardContent(
            modifier = modifier,
            bookInformation = bookInformation,
            selected = selected,
            collected = false,
            onClick = {
                if (!selectMode) onBookClick(id)
                else onBookSelect(id)
            },
            onLongPress = { onLongPress(id) },
            latestChapterTitle = snapshot.lastUpdatedChapterTitle,
            titleHeight = titleHeight
        )
    }
}

@Composable
private fun CollapseHeader(
    icon: Painter,
    title: String,
    expanded: Boolean,
    onToggleExpand: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shadowElevation = 0.dp
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .clickable(onClick = onToggleExpand)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.secondaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        modifier = Modifier.size(16.dp),
                        painter = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.W600,
                    modifier = Modifier.weight(1f)
                )
                val rotation by animateFloatAsState(if (expanded) 0f else 180f)
                Icon(
                    modifier = Modifier
                        .rotate(rotation)
                        .padding(8.dp),
                    painter = painterResource(R.drawable.keyboard_arrow_up_24px),
                    contentDescription = "expand",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            HorizontalDivider(
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.surfaceContainerHighest
            )
        }
    }
}

internal data class BookSortSnapshot(
    val id: String,
    val lastUpdated: LocalDateTime?,
    val titleSortKey: String,
    val wordCount: Int,
)

private fun createBookSortSnapshotsFlow(
    bookIds: List<String>,
    sortType: BookshelfSortType,
    dataSources: BookshelfHomeDataSources,
    locale: Locale,
): Flow<List<BookSortSnapshot>> {
    if (bookIds.isEmpty()) return flowOf(emptyList())

    return combine(bookIds.map { id ->
        when (sortType) {
            BookshelfSortType.Latest -> combine(
                dataSources.cardSnapshot(id),
                dataSources.metadataFlow(id)
            ) { snapshot, metadata ->
                BookSortSnapshot(
                    id = id,
                    lastUpdated = metadata?.lastUpdate ?: snapshot.bookInformation?.lastUpdated,
                    titleSortKey = "",
                    wordCount = 0,
                )
            }
            BookshelfSortType.Name,
            BookshelfSortType.WordCount -> dataSources.cardSnapshot(id).map { snapshot ->
                val bookInformation = snapshot.bookInformation
                BookSortSnapshot(
                    id = id,
                    lastUpdated = null,
                    titleSortKey = bookInformation?.title?.let { titleSortKey(it, locale) }.orEmpty(),
                    wordCount = bookInformation?.wordCount?.count ?: 0,
                )
            }
            BookshelfSortType.Default -> flowOf(
                BookSortSnapshot(id, null, "", 0)
            )
        }
    }) { it.toList() }
}

internal fun sortBookIds(
    source: List<String>,
    allBookIds: List<String>,
    sortType: BookshelfSortType,
    sortReversed: Boolean,
    bookSortSnapshots: List<BookSortSnapshot>,
): List<String> {
    val stableIndexMap = allBookIds.withIndex().associate { it.value to it.index }
    val snapshotById = bookSortSnapshots.associateBy { it.id }
    val sorted = when (sortType) {
        BookshelfSortType.Default -> source.sortedBy { stableIndexMap[it] ?: Int.MAX_VALUE }
        BookshelfSortType.Latest -> source.sortedWith(
            compareByDescending<String> { snapshotById[it]?.lastUpdated }
                .thenBy { stableIndexMap[it] ?: Int.MAX_VALUE }
        )
        BookshelfSortType.Name -> {
            val collator = Collator.getInstance(Locale.getDefault())
            source.sortedWith(
                Comparator { left, right ->
                    val nameCompare = collator.compare(
                        snapshotById[left]?.titleSortKey.orEmpty(),
                        snapshotById[right]?.titleSortKey.orEmpty()
                    )
                    if (nameCompare != 0) {
                        nameCompare
                    } else {
                        (stableIndexMap[left] ?: Int.MAX_VALUE).compareTo(
                            stableIndexMap[right] ?: Int.MAX_VALUE
                        )
                    }
                }
            )
        }
        BookshelfSortType.WordCount -> source.sortedWith(
            compareByDescending<String> { snapshotById[it]?.wordCount ?: 0 }
                .thenBy { stableIndexMap[it] ?: Int.MAX_VALUE }
        )
    }
    return if (sortType != BookshelfSortType.Default && sortReversed) sorted.reversed() else sorted
}

private fun titleSortKey(
    title: String,
    locale: Locale
): String {
    if (title.any { Pinyin.isChinese(it) }) {
        return Pinyin.toPinyin(title, "").lowercase(locale)
    }
    return title.lowercase(locale)
}
