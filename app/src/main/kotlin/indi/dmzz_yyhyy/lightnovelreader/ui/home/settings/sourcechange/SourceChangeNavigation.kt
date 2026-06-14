package indi.dmzz_yyhyy.lightnovelreader.ui.home.settings.sourcechange

import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.LinovelibConstants
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.ui.LinovelibSourceSettingsScreen
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.ui.LinovelibSourceSettingsViewModel
import io.nightfish.lightnovelreader.api.Route
import indi.dmzz_yyhyy.lightnovelreader.utils.isResumed
import indi.dmzz_yyhyy.lightnovelreader.utils.popBackStackIfResumed
import io.nightfish.lightnovelreader.api.ui.LocalNavController

fun NavGraphBuilder.settingsSourceChangeDestination() {
    composable<Route.Main.Settings.SourceChange> {
        val navController = LocalNavController.current
        val viewModel = hiltViewModel<SourceChangeViewModel>()

        SourceChangeScreen(
            uiState = viewModel.uiState,
            onClickBack = navController::popBackStackIfResumed,
            onApplyClick = { selectedId ->
                viewModel.changeWebSource(selectedId)
            },
            onSourceSettingsClick = { sourceId ->
                navController.navigateToSettingsSourceChangeSettingsDestination(sourceId.toString())
            }
        )
    }
    composable<Route.Main.Settings.SourceChange.Settings> { entry ->
        val navController = LocalNavController.current
        val route = entry.toRoute<Route.Main.Settings.SourceChange.Settings>()
        if (route.sourceId == LinovelibConstants.SOURCE_ID.toString()) {
            val viewModel = hiltViewModel<LinovelibSourceSettingsViewModel>()
            LinovelibSourceSettingsScreen(
                uiState = viewModel.uiState.collectAsStateWithLifecycle().value,
                onClickBack = navController::popBackStackIfResumed,
                onSaveCookie = viewModel::saveCookie,
                onClearCookie = viewModel::clearSavedCookie,
                onSyncNow = viewModel::syncNow
            )
        }
    }
}

fun NavController.navigateToSettingsSourceChangeDestination() {
    if (!this.isResumed()) return
    navigate(Route.Main.Settings.SourceChange)
}

fun NavController.navigateToSettingsSourceChangeSettingsDestination(sourceId: String) {
    if (!this.isResumed()) return
    navigate(Route.Main.Settings.SourceChange.Settings(sourceId))
}
