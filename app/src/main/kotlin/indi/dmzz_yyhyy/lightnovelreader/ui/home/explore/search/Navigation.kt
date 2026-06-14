package indi.dmzz_yyhyy.lightnovelreader.ui.home.explore.search

import androidx.compose.runtime.remember
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.ui.LinovelibWebBookScreen
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.ui.LinovelibWebSearchScreen
import indi.dmzz_yyhyy.lightnovelreader.ui.book.detail.navigateToBookDetailDestination
import indi.dmzz_yyhyy.lightnovelreader.ui.dialog.navigateToAddBookToBookshelfDialog
import indi.dmzz_yyhyy.lightnovelreader.ui.home.explore.ExploreViewModel
import io.nightfish.lightnovelreader.api.Route
import indi.dmzz_yyhyy.lightnovelreader.utils.isResumed
import indi.dmzz_yyhyy.lightnovelreader.utils.popBackStackIfResumed
import io.nightfish.lightnovelreader.api.ui.LocalNavController

fun NavGraphBuilder.exploreSearchDestination() {
    composable<Route.Main.Explore.Search> { entry ->
        val navController = LocalNavController.current
        val parentEntry = remember(entry) { navController.getBackStackEntry(Route.Main) }
        val exploreViewModel = hiltViewModel<ExploreViewModel>(parentEntry)
        val exploreSearchViewModel = hiltViewModel<ExploreSearchViewModel>()
        ExploreSearchScreen(
            exploreUiState = exploreViewModel.uiState,
            exploreSearchUiState = exploreSearchViewModel.uiState,
            refresh = exploreViewModel::refresh,
            requestAddBookToBookshelf = {
                navController.navigateToAddBookToBookshelfDialog(it)
            },
            onClickBack = { navController.popBackStackIfResumed() },
            init = exploreSearchViewModel::init,
            onChangeSearchType = { exploreSearchViewModel.changeSearchType(it) },
            onSearch = {
                exploreSearchViewModel.search(
                    keyword = it,
                    navigateToSingleBook = navController::navigateToBookDetailDestination,
                    openLinovelibWebSearch = navController::navigateToLinovelibWebSearchDestination
                )
            },
            onClickDeleteHistory = { exploreSearchViewModel.deleteHistory(it) },
            onClickClearAllHistory = exploreSearchViewModel::clearAllHistory,
            onClickBook = {
                navController.navigateToBookDetailDestination(it)
            },
            updateSuggestions = exploreSearchViewModel::updateSuggestions
        )
    }
    composable<Route.Main.Explore.LinovelibWebSearch> { entry ->
        val navController = LocalNavController.current
        val route = entry.toRoute<Route.Main.Explore.LinovelibWebSearch>()
        LinovelibWebSearchScreen(
            keyword = route.keyword,
            onClickBack = { navController.popBackStackIfResumed() },
            onBookDetected = { bookId ->
                navController.navigateToBookDetailDestination(bookId)
            }
        )
    }
    composable<Route.Main.Explore.LinovelibWebBook> { entry ->
        val navController = LocalNavController.current
        val route = entry.toRoute<Route.Main.Explore.LinovelibWebBook>()
        LinovelibWebBookScreen(
            bookId = route.bookId,
            chapterId = route.chapterId,
            autoBookmark = route.autoBookmark,
            onClickBack = { navController.popBackStackIfResumed() }
        )
    }
}

fun NavController.navigateToSearchDestination() {
    if (!this.isResumed()) return
    navigate(Route.Main.Explore.Search)
}

fun NavController.navigateToLinovelibWebSearchDestination(keyword: String) {
    if (!this.isResumed()) return
    navigate(Route.Main.Explore.LinovelibWebSearch(keyword))
}

fun NavController.navigateToLinovelibWebBookDestination(
    bookId: String,
    chapterId: String = "",
    autoBookmark: Boolean = false
) {
    if (!this.isResumed()) return
    navigate(Route.Main.Explore.LinovelibWebBook(bookId, chapterId, autoBookmark))
}
