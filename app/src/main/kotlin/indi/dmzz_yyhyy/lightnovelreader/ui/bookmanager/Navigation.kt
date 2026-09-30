package indi.dmzz_yyhyy.lightnovelreader.ui.bookmanager

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import indi.dmzz_yyhyy.lightnovelreader.ui.navigation.Navigator
import indi.dmzz_yyhyy.lightnovelreader.ui.navigation.NavEntryScope
import indi.dmzz_yyhyy.lightnovelreader.R
import indi.dmzz_yyhyy.lightnovelreader.ui.LocalNavigator
import indi.dmzz_yyhyy.lightnovelreader.utils.LocalSnackbarHost
import io.nightfish.lightnovelreader.api.Route

fun NavEntryScope.bookManagerDestination() {
    entry<Route.BookManager> {
        val navigator = LocalNavigator.current
        val snackbarHostState = LocalSnackbarHost.current
        val viewModel = hiltViewModel<BookManagerViewModel>()
        val uiState = viewModel.localBookManagerUiState
        val downloadItemList by viewModel.downloadItemListFlow.collectAsStateWithLifecycle(emptyList())
        val clearedItemsText = stringResource(R.string.book_manager_cleared_items)
        LaunchedEffect(viewModel.clearedItemsFlow) {
            viewModel.clearedItemsFlow.collect { count ->
                snackbarHostState.showSnackbar(
                    clearedItemsText.format(count),
                    withDismissAction = true
                )
            }
        }
        uiState.openStorageOverview = {
            navigator.navigate(Route.StorageManager)
        }
        uiState.openBookDetailScreen = { id ->
            navigator.navigate(Route.Book.Detail(id))
        }
        BookManagerScreen(
            onClickBack = navigator::popBackStack,
            downloadItemList = downloadItemList,
            uiState = uiState,
            onClickCancel = viewModel::onClickCancel,
            onClickPause = viewModel::onClickPause,
            onClickResume = viewModel::onClickResume,
            onClickRetry = viewModel::onClickRetry
        )
    }
}

fun Navigator.navigateToDownloadManager() {
    navigate(Route.BookManager)
}
