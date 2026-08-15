package indi.dmzz_yyhyy.lightnovelreader.ui.home.explore.expanded

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import indi.dmzz_yyhyy.lightnovelreader.R
import indi.dmzz_yyhyy.lightnovelreader.ui.components.BookCardItem
import indi.dmzz_yyhyy.lightnovelreader.ui.components.Component
import indi.dmzz_yyhyy.lightnovelreader.ui.home.explore.ExploreScreen
import indi.dmzz_yyhyy.lightnovelreader.ui.home.explore.ExploreUiState
import indi.dmzz_yyhyy.lightnovelreader.utils.LocalSnackbarHost
import indi.dmzz_yyhyy.lightnovelreader.utils.addToBookshelfAction
import indi.dmzz_yyhyy.lightnovelreader.utils.fadingEdge
import indi.dmzz_yyhyy.lightnovelreader.utils.withHaptic

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpandedPageScreen(
    exploreUiState: ExploreUiState,
    expandedPageUiState: ExpandedPageUiState,
    dialog: (@Composable () -> Unit) -> Unit,
    expandedPageDataSourceId: String,
    init: (String) -> Unit,
    refreshResult: () -> Unit,
    loadMore: () -> Unit,
    retry: () -> Unit,
    requestAddBookToBookshelf: (String) -> Unit,
    onClickBack: () -> Unit,
    onClickBook: (String) -> Unit,
    refresh: () -> Unit,
) {
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        init(expandedPageDataSourceId)
    }

    val listState = rememberLazyListState()
    TopAppBarDefaults.enterAlwaysScrollBehavior().let { scrollBehavior ->
        Scaffold(
            topBar = {
                TopBar(
                    scrollBehavior = scrollBehavior,
                    title = expandedPageUiState.pageTitle,
                    onClickBack = onClickBack
                )
            },
            snackbarHost = {
                SnackbarHost(LocalSnackbarHost.current)
            }
        ) { paddingValues ->
            ExploreScreen(
                modifier = Modifier.padding(paddingValues),
                uiState = exploreUiState,
                refresh = refresh
            ) {
                PullToRefreshBox(
                    modifier = Modifier.fillMaxSize(),
                    isRefreshing = expandedPageUiState.isRefreshing,
                    onRefresh = refresh
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .nestedScroll(scrollBehavior.nestedScrollConnection)
                            .background(MaterialTheme.colorScheme.surface),
                        contentPadding = PaddingValues(vertical = 3.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        item(key = "filters") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                                    .fadingEdge(
                                        Brush.horizontalGradient(
                                            0.02f to Color.Transparent,
                                            0.05f to Color.White,
                                            0.95f to Color.White,
                                            0.98f to Color.Transparent
                                        )
                                    ),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Spacer(Modifier.width(8.dp))
                                expandedPageUiState.filters.forEach {
                                    it.Component(dialog, refreshResult)
                                }
                                Spacer(Modifier.width(8.dp))
                            }
                            Spacer(Modifier.height(4.dp))
                        }

                        item(key = "initial-state") {
                            when {
                                expandedPageUiState.isInitialLoading -> {
                                    LinearProgressIndicator(Modifier.fillMaxWidth())
                                }
                                expandedPageUiState.bookList.isEmpty() &&
                                    expandedPageUiState.errorMessage != null -> {
                                    ExpandedPageMessage(
                                        title = stringResource(R.string.offline),
                                        description = expandedPageUiState.errorMessage,
                                        onRetry = retry
                                    )
                                }
                                expandedPageUiState.bookList.isEmpty() &&
                                    expandedPageUiState.isEmptyResult -> {
                                    ExpandedPageMessage(
                                        title = stringResource(R.string.nothing_here)
                                    )
                                }
                            }
                        }

                        items(
                            items = expandedPageUiState.bookList,
                            key = { it.first }
                        ) { pair ->
                            val addToBookshelf = addToBookshelfAction.toSwipeAction {
                                requestAddBookToBookshelf(pair.first)
                            }
                            BookCardItem(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                bookInformationFlow = pair.second,
                                onClick = { onClickBook(pair.first) },
                                onLongPress = withHaptic {},
                                collected = expandedPageUiState.allBookshelfBookIds.contains(
                                    pair.first
                                ),
                                swipeToRightActions = listOf(addToBookshelf),
                                titleHeight = with(LocalDensity.current) {
                                    (MaterialTheme.typography.titleMedium.lineHeight * 2.2f).toDp()
                                }
                            )
                        }

                        item(key = "paging-state") {
                            when {
                                expandedPageUiState.bookList.isNotEmpty() &&
                                    expandedPageUiState.isLoadingMore -> {
                                    LinearProgressIndicator(Modifier.fillMaxWidth())
                                }
                                expandedPageUiState.bookList.isNotEmpty() &&
                                    expandedPageUiState.errorMessage != null -> {
                                    ExpandedPageMessage(
                                        title = expandedPageUiState.errorMessage.orEmpty(),
                                        onRetry = retry
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(expandedPageUiState.resultVersion) {
        if (expandedPageUiState.resultVersion > 0) {
            listState.scrollToItem(0)
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow {
            val layoutInfo = listState.layoutInfo
            layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        }.collect { lastVisibleIndex ->
            val size = expandedPageUiState.bookList.size
            if (
                size > 0 &&
                !expandedPageUiState.isLoadingMore &&
                expandedPageUiState.errorMessage == null &&
                lastVisibleIndex >= size - 1
            ) {
                loadMore()
            }
        }
    }
}

@Composable
private fun ExpandedPageMessage(
    title: String,
    description: String? = null,
    onRetry: (() -> Unit)? = null
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center
        )
        description?.takeIf(String::isNotBlank)?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
        onRetry?.let {
            TextButton(onClick = it) {
                Text(stringResource(R.string.retry))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopBar(
    scrollBehavior: TopAppBarScrollBehavior,
    title: String,
    onClickBack: () -> Unit
) {
    MediumTopAppBar(
        title = {
            Text(
                text = stringResource(id = R.string.nav_explore_child, title),
                style = MaterialTheme.typography.displayLarge,
                fontWeight = FontWeight.W600,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        navigationIcon = {
            IconButton(onClick = onClickBack) {
                Icon(
                    painter = painterResource(id = R.drawable.arrow_back_24px),
                    contentDescription = "back"
                )
            }
        },
        scrollBehavior = scrollBehavior
    )
}
