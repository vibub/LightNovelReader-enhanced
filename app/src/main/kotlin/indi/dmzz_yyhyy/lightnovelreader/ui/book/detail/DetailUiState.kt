package indi.dmzz_yyhyy.lightnovelreader.ui.book.detail

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.android.material.bottomsheet.BottomSheetBehavior.State
import indi.dmzz_yyhyy.lightnovelreader.data.download.DownloadItem
import io.nightfish.lightnovelreader.api.book.BookInformation
import io.nightfish.lightnovelreader.api.book.BookVolumes
import io.nightfish.lightnovelreader.api.book.UserReadingData

@State
interface DetailUiState {
    val isLoading: Boolean
    val bookInformation: BookInformation
    val bookVolumes: BookVolumes
    val userReadingData: UserReadingData
    val bookmarkUiState: DetailBookmarkUiState
    val isCached: Boolean
    val downloadItem: DownloadItem?
    val isInBookshelf: Boolean
}

data class DetailBookmarkUiState(
    val chapterId: String = "",
    val chapterTitle: String = "",
    val syncState: String = ""
)

class MutableDetailUiState: DetailUiState {
    override var isLoading: Boolean by mutableStateOf(true)
    override var bookInformation: BookInformation by mutableStateOf(BookInformation.empty())
    override var bookVolumes: BookVolumes by mutableStateOf(BookVolumes.empty(""))
    override var userReadingData: UserReadingData by mutableStateOf(UserReadingData.empty())
    override var bookmarkUiState: DetailBookmarkUiState by mutableStateOf(DetailBookmarkUiState())
    override var isCached: Boolean by mutableStateOf(false)
    override var downloadItem: DownloadItem? by mutableStateOf(null)
    override var isInBookshelf: Boolean by mutableStateOf(false)
}


