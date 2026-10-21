package indi.dmzz_yyhyy.lightnovelreader.ui.home.explore.expanded

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.LinovelibConstants
import indi.dmzz_yyhyy.lightnovelreader.ui.LocalNavigator
import indi.dmzz_yyhyy.lightnovelreader.ui.navigation.Navigator
import indi.dmzz_yyhyy.lightnovelreader.ui.navigation.NavEntryScope
import indi.dmzz_yyhyy.lightnovelreader.ui.book.detail.navigateToBookDetailDestination
import indi.dmzz_yyhyy.lightnovelreader.ui.dialog.navigateToAddBookToBookshelfDialog
import indi.dmzz_yyhyy.lightnovelreader.ui.home.explore.ExploreUiState
import indi.dmzz_yyhyy.lightnovelreader.ui.home.explore.ExploreViewModel
import indi.dmzz_yyhyy.lightnovelreader.utils.activityHiltViewModel
import io.nightfish.lightnovelreader.api.Route

fun NavEntryScope.exploreExpandDestination() {
    entry<Route.Main.Explore.Expanded> { entry ->
        val navigator = LocalNavigator.current
        val exploreViewModel = activityHiltViewModel<ExploreViewModel>()
        val exploreExpandedPageHomeViewModel = hiltViewModel<ExpandedPageViewModel>()
        val exploreUiState = exploreViewModel.uiState
        val isLinovelib = exploreUiState.sourceId == LinovelibConstants.SOURCE_ID
        val expandedExploreUiState = remember(exploreUiState, isLinovelib) {
            if (isLinovelib) {
                // 筛选页自行展示加载失败，不能被后台首页探测的全局离线状态遮蔽。
                object : ExploreUiState by exploreUiState {
                    override val isOffLine: Boolean = false
                }
            } else {
                exploreUiState
            }
        }
        var dialog: @Composable () -> Unit by remember { mutableStateOf(@Composable {}) }
        ExpandedPageScreen(
            exploreUiState = expandedExploreUiState,
            expandedPageUiState = exploreExpandedPageHomeViewModel.uiState,
            refresh = {
                // 只刷新筛选结果；额外探测首页既非本次筛选，也可能触发共享冷却拒绝。
                if (!isLinovelib) exploreViewModel.refresh()
                exploreExpandedPageHomeViewModel.refreshCurrentResults()
            },
            dialog = { newDialog -> dialog = newDialog },
            expandedPageDataSourceId = entry.expandedPageDataSourceId,
            init = exploreExpandedPageHomeViewModel::init,
            loadMore = exploreExpandedPageHomeViewModel::loadMore,
            retry = exploreExpandedPageHomeViewModel::retry,
            refreshResult = exploreExpandedPageHomeViewModel::loadBookResult,
            requestAddBookToBookshelf = {
                navigator.navigateToAddBookToBookshelfDialog(it)
            },
            onClickBack = {
                exploreExpandedPageHomeViewModel.clear()
                navigator.popBackStack()
            },
            onClickBook = {
                navigator.navigateToBookDetailDestination(it)
            }
        )
        dialog.invoke()
    }
}

fun Navigator.navigateToExploreExpandDestination(expandedPageDataSourceId: String) {
    navigate(Route.Main.Explore.Expanded(expandedPageDataSourceId))
}