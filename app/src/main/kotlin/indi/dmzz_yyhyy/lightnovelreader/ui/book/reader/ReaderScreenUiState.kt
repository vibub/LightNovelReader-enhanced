package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.ContentUiState
import io.nightfish.lightnovelreader.api.book.BookVolumes
import io.nightfish.lightnovelreader.api.book.UserReadingData

@Stable
interface ReaderScreenUiState {
    val bookId: String
    val userReadingData: UserReadingData
    val bookVolumes: BookVolumes
    val contentUiState: ContentUiState
    val bookmarkUiState: ReaderBookmarkUiState
    val chapterCommentsUiState: ChapterCommentsUiState
    val isLinovelibSource: Boolean
}

data class ReaderBookmarkUiState(
    val isAvailable: Boolean = false,
    val chapterId: String = "",
    val chapterTitle: String = "",
    val syncState: String = ""
)

class MutableReaderScreenUiState(
    contentUiState: ContentUiState
): ReaderScreenUiState {
    override var bookId by mutableStateOf("")
    override var userReadingData by mutableStateOf(UserReadingData.empty())
    override var bookVolumes by mutableStateOf(BookVolumes.empty(""))
    override var contentUiState by mutableStateOf(contentUiState)
    override var bookmarkUiState by mutableStateOf(ReaderBookmarkUiState())
    override var chapterCommentsUiState by mutableStateOf(ChapterCommentsUiState())
    override var isLinovelibSource by mutableStateOf(false)
}
