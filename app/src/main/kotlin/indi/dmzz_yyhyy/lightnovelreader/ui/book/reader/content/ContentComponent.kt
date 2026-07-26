package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.github.michaelbull.result.get
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.ChapterEndContext
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.SettingState
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.flip.FlipPageContentComponent
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.flip.FlipPageContentUiState
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.scroll.ScrollContentComponent
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.scroll.ScrollContentUiState
import indi.dmzz_yyhyy.lightnovelreader.ui.components.Loading
import io.nightfish.lightnovelreader.api.error.WebRequestError

@Composable
fun ContentComponent(
    modifier: Modifier = Modifier,
    uiState: ContentUiState?,
    settingState: SettingState,
    paddingValues: PaddingValues,
    changeIsImmersive: () -> Unit,
    onClickPrevChapter: () -> Unit,
    onClickNextChapter: () -> Unit,
    bookId: String,
    chapterTitleById: Map<String, String>,
    onClickChapterComments: ((ChapterEndContext) -> Unit)?
) {
    uiState.let { contentUiState ->
        when(contentUiState) {
            is FlipPageContentUiState -> FlipPageContentComponent(
                modifier,
                contentUiState,
                settingState,
                paddingValues,
                changeIsImmersive,
                onClickPrevChapter,
                onClickNextChapter,
                bookId,
                contentUiState.readingChapterContent?.get()?.nextChapter
                    ?.let(chapterTitleById::get),
                onClickChapterComments
            )
            is ScrollContentUiState -> ScrollContentComponent(
                modifier,
                contentUiState,
                settingState,
                paddingValues,
                changeIsImmersive,
                onClickPrevChapter,
                onClickNextChapter,
                bookId,
                chapterTitleById,
                onClickChapterComments
            )
        }
    }
}

@Composable
fun ChapterContentLoading() {
    Loading()
}

@Composable
fun ChapterContentError(
    error: WebRequestError
) {
    //TODO 错误显示
}