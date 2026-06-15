package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.scroll

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.IntSize
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.ContentUiState
import io.nightfish.lightnovelreader.api.book.ChapterContent
import io.nightfish.lightnovelreader.api.content.component.AbstractContentComponent

interface ScrollContentUiState: ContentUiState {
    val lazyListState: LazyListState
    val readingContentId: String
    val contentList: List<ChapterContent?>
    val setLazyColumnSize: (IntSize) -> Unit
    val writeProgressRightNow: () -> Unit
    val completeProgressRestore: (Int) -> Unit
    val restoreProgress: Float
    val restoreVersion: Int
    val shouldRestoreProgress: Boolean
    override val readingChapterContent: ChapterContent
        get() = contentList.firstOrNull { it?.id == readingContentId } ?: ChapterContent.empty()
}

class MutableScrollContentUiSate(
    override val loadNextChapter: () -> Unit,
    override val loadLastChapter: () -> Unit,
    override val changeChapter: (String) -> Unit,
    override val setLazyColumnSize: (IntSize) -> Unit,
    override val writeProgressRightNow: () -> Unit,
    override val completeProgressRestore: (Int) -> Unit
) : ScrollContentUiState {
    override var bookId by mutableStateOf("")
    override var readingProgress by mutableFloatStateOf(0f)
    override var lazyListState: LazyListState by mutableStateOf(LazyListState())
    override var readingContentId by mutableStateOf("")
    override var restoreProgress by mutableFloatStateOf(0f)
    override var restoreVersion by mutableIntStateOf(0)
    var consumedRestoreVersion by mutableIntStateOf(0)
    override val shouldRestoreProgress: Boolean
        get() = restoreVersion != 0 && consumedRestoreVersion != restoreVersion
    override val contentList = mutableStateListOf<ChapterContent?>(null, null, null)
    override val contentComponentsMap = mutableStateMapOf<String, List<AbstractContentComponent<*>>>()
}