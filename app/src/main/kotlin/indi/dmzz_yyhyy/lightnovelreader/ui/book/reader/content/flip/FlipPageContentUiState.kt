package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.flip

import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.github.michaelbull.result.Result
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.ChapterContentUiState
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.ContentUiState
import io.nightfish.lightnovelreader.api.error.WebRequestError

interface FlipPageContentUiState: ContentUiState {
    val updatePageState: (String, PagerState, Int) -> Unit
    val pagerState: PagerState
    val contentPageCount: Int
}

class MutableFlipPageContentUiState(
    override val loadNextChapter: () -> Unit,
    override val loadPrevChapter: () -> Unit,
    override val changeChapter: (String) -> Unit,
    override val updatePageState: (String, PagerState, Int) -> Unit,
): FlipPageContentUiState {
    override var pagerState by mutableStateOf(PagerState { 0 })
    override var contentPageCount by mutableStateOf(0)
    override var bookId by mutableStateOf("")
    override var readingChapterId: String? by mutableStateOf(null)
    override var readingChapterContent: Result<ChapterContentUiState, WebRequestError>? by mutableStateOf(null)
    override var readingProgress by mutableFloatStateOf(0f)
}