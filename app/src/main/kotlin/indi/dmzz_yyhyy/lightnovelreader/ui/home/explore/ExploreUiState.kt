package indi.dmzz_yyhyy.lightnovelreader.ui.home.explore

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.android.material.bottomsheet.BottomSheetBehavior.State
import io.nightfish.lightnovelreader.api.identifier.Identifier

@State
interface ExploreUiState {
    val isOffLine: Boolean
    val isRefreshing: Boolean
    val sourceId: Identifier
}

class MutableExploreUiState(initialSourceId: Identifier) : ExploreUiState {
    override var isOffLine: Boolean by mutableStateOf(true)
    override var isRefreshing: Boolean by mutableStateOf(false)
    override var sourceId: Identifier by mutableStateOf(initialSourceId)
}