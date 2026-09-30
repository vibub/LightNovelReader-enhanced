package indi.dmzz_yyhyy.lightnovelreader.ui.home.settings.sourcechange

import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.LinovelibConstants
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.ui.LinovelibSourceSettingsScreen
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.ui.LinovelibSourceSettingsViewModel
import indi.dmzz_yyhyy.lightnovelreader.ui.LocalNavigator
import indi.dmzz_yyhyy.lightnovelreader.ui.navigation.NavEntryScope
import indi.dmzz_yyhyy.lightnovelreader.ui.navigation.Navigator
import io.nightfish.lightnovelreader.api.Route

fun NavEntryScope.settingsSourceChangeDestination() {
    entry<Route.Main.Settings.SourceChange.List> {
        val navigator = LocalNavigator.current
        val viewModel = hiltViewModel<SourceChangeViewModel>()

        SourceChangeScreen(
            uiState = viewModel.uiState,
            onClickBack = navigator::popBackStack,
            onApplyClick = { selectedId ->
                viewModel.changeWebSource(selectedId)
            },
            onSourceSettingsClick = { sourceId ->
                navigator.navigateToSettingsSourceChangeSettingsDestination(sourceId.toString())
            }
        )
    }
    entry<Route.Main.Settings.SourceChange.Settings> { route ->
        val navigator = LocalNavigator.current
        if (route.sourceId == LinovelibConstants.SOURCE_ID.toString()) {
            val viewModel = hiltViewModel<LinovelibSourceSettingsViewModel>()
            LinovelibSourceSettingsScreen(
                uiState = viewModel.uiState.collectAsStateWithLifecycle().value,
                onClickBack = navigator::popBackStack,
                onSaveCookie = viewModel::saveCookie,
                onClearCookie = viewModel::clearSavedCookie,
                onSyncNow = viewModel::syncNow
            )
        }
    }
}

fun Navigator.navigateToSettingsSourceChangeDestination() {
    navigate(Route.Main.Settings.SourceChange.List)
}

fun Navigator.navigateToSettingsSourceChangeSettingsDestination(sourceId: String) {
    navigate(Route.Main.Settings.SourceChange.Settings(sourceId))
}
