package indi.dmzz_yyhyy.lightnovelreader.ui.home.explore

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.android.material.bottomsheet.BottomSheetBehavior.State

@State
interface ExploreUiState {
    val isOffLine: Boolean
    val isRefreshing: Boolean
    val sourceId: Int
}

class MutableExploreUiState : ExploreUiState {
    override var isOffLine: Boolean by mutableStateOf(true)
    override var isRefreshing: Boolean by mutableStateOf(false)
    override var sourceId: Int by mutableStateOf(0)
}