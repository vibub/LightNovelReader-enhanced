package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content

import io.nightfish.lightnovelreader.api.book.ChapterContent
import io.nightfish.lightnovelreader.api.content.component.AbstractContentComponent

interface ContentViewModel {
    val uiState: ContentUiState
    fun changeBookId(id: String)
    fun loadNextChapter()
    fun loadLastChapter()
    fun changeChapter(id: String, restoreProgress: Boolean = true)

    companion object {
        class EmptyContentViewModel: ContentViewModel {
            override val uiState: ContentUiState = object: ContentUiState {
                override val bookId: String = ""
                override val readingChapterContent: ChapterContent = ChapterContent.empty()
                override val readingProgress: Float = 1f
                override val loadNextChapter: () -> Unit = {}
                override val loadLastChapter: () -> Unit = {}
                override val changeChapter: (String) -> Unit = {}
                override val contentComponentsMap: Map<String, List<AbstractContentComponent<*>>> = emptyMap()
            }

            override fun changeBookId(id: String) {
            }

            override fun loadNextChapter() {
            }

            override fun loadLastChapter() {
            }

            override fun changeChapter(id: String, restoreProgress: Boolean) {
            }

        }

        val empty = EmptyContentViewModel()
    }
}