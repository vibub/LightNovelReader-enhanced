package indi.dmzz_yyhyy.lightnovelreader.data.content.component

import io.nightfish.lightnovelreader.api.content.component.SimpleTextComponentData
import io.nightfish.lightnovelreader.api.content.component.SimpleTextStyleRange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PreparedTextContentTest {
    @Test
    fun backgroundPreparationAndForegroundShareText() = runBlocking {
        val data = SimpleTextComponentData("原词正文", listOf(SimpleTextStyleRange(0, 2, rubyText = "注释")))
        val prepared = PreparedTextContent(data)
        val background = withContext(Dispatchers.Default) { prepared.text }
        assertTrue(prepared.hasRuby)
        repeat(10) { assertSame(background, prepared.text) }
        assertEquals(data.toAnnotatedString(), prepared.text)
        assertEquals("原词正文", prepared.text.text)
    }

    @Test
    fun preservesStylesAndClampedRanges() {
        val data = SimpleTextComponentData("原词正文", listOf(
            SimpleTextStyleRange(-2, 2, fontWeight = 700, rubyText = "注释"),
            SimpleTextStyleRange(1, 99, italic = true, underline = true),
            SimpleTextStyleRange(99, 100, strikethrough = true)
        ))
        assertEquals(data.toAnnotatedString(), PreparedTextContent(data).text)
    }

    @Test
    fun changedContentGetsIndependentPreparation() {
        val first = PreparedTextContent(SimpleTextComponentData("旧正文"))
        val next = PreparedTextContent(SimpleTextComponentData("新正文"))
        assertNotSame(first.text, next.text)
        assertEquals("旧正文", first.text.text)
        assertEquals("新正文", next.text.text)
    }

    @Test
    fun blankAnnotationsDoNotEnableRubyPath() {
        assertFalse(PreparedTextContent(SimpleTextComponentData("正文")).hasRuby)
        assertFalse(PreparedTextContent(SimpleTextComponentData("正文", listOf(
            SimpleTextStyleRange(0, 2, rubyText = " ")
        ))).hasRuby)
    }
}
