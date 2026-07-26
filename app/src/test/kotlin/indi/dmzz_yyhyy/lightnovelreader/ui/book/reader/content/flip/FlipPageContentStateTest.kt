package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.flip

import androidx.compose.runtime.snapshots.Snapshot
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.get
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.ChapterContentUiState
import io.nightfish.lightnovelreader.api.content.component.AbstractContentComponent
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FlipPageContentStateTest {
    @Test
    fun chapterContentIsPublishedWithItsComponents() {
        val uiState = MutableFlipPageContentUiState(
            loadNextChapter = {},
            loadPrevChapter = {},
            changeChapter = {},
            updatePageState = { _, _, _ -> }
        )
        val components = emptyList<AbstractContentComponent<*>>()
        val content = ChapterContentUiState(
            id = "chapter-b",
            title = "Chapter B",
            content = components,
            sourceContent = buildJsonObject {},
            prevChapter = null,
            nextChapter = null
        )
        var observedPublication = false
        var observedIncompleteState = false
        val observer = Snapshot.registerApplyObserver { _, _ ->
            val published = uiState.readingChapterContent?.get()
            if (published?.id == content.id) {
                observedPublication = true
                if (published.content !== components) {
                    observedIncompleteState = true
                }
            }
        }

        try {
            uiState.readingChapterContent = Ok(content)
            Snapshot.sendApplyNotifications()
        } finally {
            observer.dispose()
        }

        assertTrue(observedPublication)
        assertFalse(observedIncompleteState)
        assertEquals(content, uiState.readingChapterContent?.get())
    }
}
