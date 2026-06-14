package indi.dmzz_yyhyy.lightnovelreader.ui.home.explore

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import indi.dmzz_yyhyy.lightnovelreader.data.web.WebBookDataSourceProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ExploreViewModel @Inject constructor(
    private val webBookDataSourceProvider: WebBookDataSourceProvider
) : ViewModel() {
    private var _uiState = MutableExploreUiState()
    val uiState: ExploreUiState = _uiState

    init {
        _uiState.sourceId = webBookDataSourceProvider.default.id
        _uiState.isOffLine = webBookDataSourceProvider.default.offLine
        viewModelScope.launch(Dispatchers.IO) {
            webBookDataSourceProvider.default.isOffLineFlow.collect {
                _uiState.isOffLine = it
            }
        }
    }

    fun refresh() {
        _uiState.isRefreshing = true
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.isOffLine = webBookDataSourceProvider.default.isOffLine()
            _uiState.isRefreshing = false
        }
    }
}