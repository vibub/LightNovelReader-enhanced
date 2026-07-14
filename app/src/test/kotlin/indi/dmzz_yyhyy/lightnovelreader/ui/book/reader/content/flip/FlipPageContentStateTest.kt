package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.flip

import androidx.compose.runtime.snapshots.Snapshot
import io.nightfish.lightnovelreader.api.book.MutableChapterContent
import io.nightfish.lightnovelreader.api.content.component.AbstractContentComponent
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FlipPageContentStateTest {
    @Test
    fun chapterContentIsNeverPublishedBeforeItsComponents() {
        val uiState = MutableFlipPageContentUiState(
            loadNextChapter = {},
            loadLastChapter = {},
            changeChapter = {},
            updatePageState = { _, _, _ -> }
        )
        val content = MutableChapterContent(
            id = "chapter-b",
            title = "Chapter B",
            content = buildJsonObject {
                put("components", buildJsonArray {})
            }
        )
        val components = emptyList<AbstractContentComponent<*>>()
        var observedPublication = false
        var observedIncompleteState = false
        val observer = Snapshot.registerApplyObserver { _, _ ->
            if (uiState.readingChapterContent.id == content.id) {
                observedPublication = true
                if (!uiState.contentComponentsMap.containsKey(content.id)) {
                    observedIncompleteState = true
                }
            }
        }

        try {
            uiState.publishChapterContent(content, components)
            Snapshot.sendApplyNotifications()
        } finally {
            observer.dispose()
        }

        assertTrue(observedPublication)
        assertFalse(observedIncompleteState)
        assertEquals(content, uiState.readingChapterContent)
        assertTrue(uiState.contentComponentsMap.containsKey(content.id))
    }
}
