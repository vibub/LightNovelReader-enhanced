package indi.dmzz_yyhyy.lightnovelreader.ui.book.detail

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.github.michaelbull.result.Result
import com.google.android.material.bottomsheet.BottomSheetBehavior.State
import indi.dmzz_yyhyy.lightnovelreader.data.download.DownloadItem
import io.nightfish.lightnovelreader.api.book.BookInformation
import io.nightfish.lightnovelreader.api.book.BookVolumes
import io.nightfish.lightnovelreader.api.book.UserReadingData
import io.nightfish.lightnovelreader.api.error.WebRequestError

@State
interface DetailUiState {
    val bookInformation: Result<BookInformation, WebRequestError>?
    val bookVolumes: Result<BookVolumes, WebRequestError>?
    val userReadingData: UserReadingData?
    val bookmarkUiState: DetailBookmarkUiState
    val isLinovelibSource: Boolean
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
    override var bookInformation: Result<BookInformation, WebRequestError>? by mutableStateOf(null)
    override var bookVolumes: Result<BookVolumes, WebRequestError>? by mutableStateOf(null)
    override var userReadingData: UserReadingData? by mutableStateOf(null)
    override var bookmarkUiState by mutableStateOf(DetailBookmarkUiState())
    override var isLinovelibSource by mutableStateOf(false)
    override var isCached: Boolean by mutableStateOf(false)
    override var downloadItem: DownloadItem? by mutableStateOf(null)
    override var isInBookshelf: Boolean by mutableStateOf(false)
}