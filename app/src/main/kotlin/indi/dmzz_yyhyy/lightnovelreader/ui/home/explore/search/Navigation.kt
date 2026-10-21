package indi.dmzz_yyhyy.lightnovelreader.ui.home.explore.search

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.LinovelibConstants
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.ui.LinovelibSearchLoader
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.ui.LinovelibWebBookScreen
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.ui.LinovelibWebSearchScreen
import indi.dmzz_yyhyy.lightnovelreader.ui.LocalNavigator
import indi.dmzz_yyhyy.lightnovelreader.ui.navigation.Navigator
import indi.dmzz_yyhyy.lightnovelreader.ui.navigation.NavEntryScope
import indi.dmzz_yyhyy.lightnovelreader.ui.book.detail.navigateToBookDetailDestination
import indi.dmzz_yyhyy.lightnovelreader.ui.dialog.navigateToAddBookToBookshelfDialog
import indi.dmzz_yyhyy.lightnovelreader.ui.home.explore.ExploreUiState
import indi.dmzz_yyhyy.lightnovelreader.ui.home.explore.ExploreViewModel
import indi.dmzz_yyhyy.lightnovelreader.utils.activityHiltViewModel
import io.nightfish.lightnovelreader.api.Route

fun NavEntryScope.exploreSearchDestination() {
    entry<Route.Main.Explore.Search> {
        val navigator = LocalNavigator.current
        val exploreViewModel = activityHiltViewModel<ExploreViewModel>()
        val exploreSearchViewModel = hiltViewModel<ExploreSearchViewModel>()
        val exploreUiState = exploreViewModel.uiState
        val isLinovelib = exploreUiState.sourceId == LinovelibConstants.SOURCE_ID
        val searchExploreUiState = remember(exploreUiState, isLinovelib) {
            if (isLinovelib) {
                // 搜索由当前结果页决定成败，不能被后台首页探测的离线状态遮住。
                object : ExploreUiState by exploreUiState {
                    override val isOffLine: Boolean = false
                }
            } else {
                exploreUiState
            }
        }
        val request = exploreSearchViewModel.uiState.linovelibSearchRequest
        val search: (String) -> Unit = { keyword ->
            exploreSearchViewModel.search(
                keyword = keyword,
                navigateToSingleBook = navigator::navigateToBookDetailDestination,
                openLinovelibWebSearch = navigator::navigateToLinovelibWebSearchDestination
            )
        }
        var showLinovelibSearchBrowser by remember(request?.id) { mutableStateOf(false) }
        Box(Modifier.fillMaxSize()) {
            ExploreSearchScreen(
                exploreUiState = searchExploreUiState,
                exploreSearchUiState = exploreSearchViewModel.uiState,
                refresh = {
                    val keyword = exploreSearchViewModel.uiState.linovelibSearchKeyword
                    if (isLinovelib && keyword != null) search(keyword) else exploreViewModel.refresh()
                },
                requestAddBookToBookshelf = {
                    navigator.navigateToAddBookToBookshelfDialog(it)
                },
                onClickBack = { navigator.popBackStack() },
                init = exploreSearchViewModel::init,
                onChangeSearchType = { exploreSearchViewModel.changeSearchType(it) },
                onSearch = search,
                onClickDeleteHistory = { exploreSearchViewModel.deleteHistory(it) },
                onClickClearAllHistory = exploreSearchViewModel::clearAllHistory,
                onClickBook = navigator::navigateToBookDetailDestination,
                updateSuggestions = exploreSearchViewModel::updateSuggestions,
                openLinovelibWebSearch = { keyword ->
                    request?.let { exploreSearchViewModel.cancelLinovelibSearch(it.id) }
                    navigator.navigateToLinovelibWebSearchDestination(keyword)
                },
                openLinovelibSearchBrowser = { showLinovelibSearchBrowser = true },
                loadMoreLinovelibSearchResults = exploreSearchViewModel::loadMoreLinovelibSearchResults
            )
            request?.let {
                key(request.id) {
                    LinovelibSearchLoader(
                        request = request,
                        showBrowser = showLinovelibSearchBrowser,
                        onShowBrowser = { showLinovelibSearchBrowser = true },
                        onPageReady = { url, html ->
                            exploreSearchViewModel.acceptLinovelibSearchPage(request.id, url, html)
                        },
                        onCancel = { exploreSearchViewModel.cancelLinovelibSearch(request.id) },
                        onSearchStateChanged = { errorMessage ->
                            if (errorMessage != null) showLinovelibSearchBrowser = false
                            exploreSearchViewModel.updateLinovelibSearchState(request.id, errorMessage)
                        },
                        onBookDetected = { bookId ->
                            if (exploreSearchViewModel.acceptLinovelibSearchBook(request.id, bookId)) {
                                navigator.navigateToBookDetailDestination(bookId)
                            }
                        }
                    )
                }
            }
        }
    }
    entry<Route.Main.Explore.LinovelibWebSearch> { route ->
        val navigator = LocalNavigator.current
        LinovelibWebSearchScreen(
            keyword = route.keyword,
            onClickBack = { navigator.popBackStack() },
            onBookDetected = { bookId ->
                navigator.navigateToBookDetailDestination(bookId)
            }
        )
    }
    entry<Route.Main.Explore.LinovelibWebBook> { route ->
        val navigator = LocalNavigator.current
        LinovelibWebBookScreen(
            bookId = route.bookId,
            chapterId = route.chapterId,
            onClickBack = { navigator.popBackStack() }
        )
    }
}

fun Navigator.navigateToSearchDestination() {
    navigate(Route.Main.Explore.Search)
}

fun Navigator.navigateToLinovelibWebSearchDestination(keyword: String) {
    navigate(Route.Main.Explore.LinovelibWebSearch(keyword))
}

fun Navigator.navigateToLinovelibWebBookDestination(
    bookId: String,
    chapterId: String = ""
) {
    navigate(Route.Main.Explore.LinovelibWebBook(bookId, chapterId))
}
