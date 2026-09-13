package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.scroll

import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.ChapterContentUiState
import io.nightfish.lightnovelreader.api.book.ChapterContent
import io.nightfish.lightnovelreader.api.content.component.AbstractContentComponent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrollChapterPreparationTest {
    private fun chapter(text: String = "带注释的正文") = ChapterContent(
        id = "chapter", title = "章节", content = JsonObject(mapOf("text" to JsonPrimitive(text))),
        prevChapter = "previous", nextChapter = "next"
    )

    private fun existing(chapter: ChapterContent) = ChapterContentUiState(
        chapter.id, chapter.title, mutableListOf<AbstractContentComponent<*>>(),
        chapter.content, chapter.prevChapter, chapter.nextChapter
    )

    @Test
    fun repeatedSubscriptionReusesEntireUiState() = runBlocking {
        val chapter = chapter()
        val existing = existing(chapter)
        val prepared = prepareScrollChapter(chapter, existing) { error("不应重复创建组件") }
        assertSame(existing, prepared)
    }

    @Test
    fun equivalentDeserializedContentStillReusesUiState() = runBlocking {
        val existing = existing(chapter())
        val prepared = prepareScrollChapter(chapter(), existing) { error("结构相同的正文应命中复用") }
        assertSame(existing, prepared)
    }

    @Test
    fun changedNavigationUpdatesMetadataWithoutRecreatingComponents() = runBlocking {
        val chapter = chapter()
        val existing = existing(chapter)
        val changed = chapter.copy(title = "新标题", prevChapter = null, nextChapter = "new-next")
        val prepared = prepareScrollChapter(changed, existing) { error("只修改章节元数据不应重建正文") }
        assertNotSame(existing, prepared)
        assertSame(existing.content, prepared.content)
        assertEquals(changed.title, prepared.title)
        assertEquals(changed.prevChapter, prepared.prevChapter)
        assertEquals(changed.nextChapter, prepared.nextChapter)
    }

    @Test
    fun changedBodyRebuildsComponents() = runBlocking {
        val existing = existing(chapter())
        val changed = chapter("修订后的正文")
        val components = mutableListOf<AbstractContentComponent<*>>()
        var calls = 0
        val prepared = prepareScrollChapter(changed, existing) {
            calls++
            assertEquals(changed.content, it)
            components
        }
        assertEquals(1, calls)
        assertSame(components, prepared.content)
        assertEquals(changed.content, prepared.sourceContent)
    }

    @Test
    fun differentChapterDoesNotReuseComponentsForIdenticalText() = runBlocking {
        val original = chapter()
        val existing = existing(original)
        var calls = 0
        val prepared = prepareScrollChapter(original.copy(id = "another"), existing) {
            calls++
            emptyList()
        }
        assertEquals(1, calls)
        assertEquals("another", prepared.id)
    }

    @Test
    fun componentCreationRunsAwayFromCollectorThread() = runBlocking {
        val collectorThread = Thread.currentThread()
        var created = false
        prepareScrollChapter(chapter(), null) {
            assertNotSame(collectorThread, Thread.currentThread())
            created = true
            emptyList()
        }
        assertTrue(created)
        assertSame(collectorThread, Thread.currentThread())
    }
}
