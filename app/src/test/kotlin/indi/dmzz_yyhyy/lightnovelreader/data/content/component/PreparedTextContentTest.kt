package indi.dmzz_yyhyy.lightnovelreader.data.content.component

import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.nightfish.lightnovelreader.api.content.component.SimpleTextComponentData
import io.nightfish.lightnovelreader.api.ui.ReaderStyle
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
import kotlin.math.roundToInt

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
    fun paragraphsReplaceSiteIndentAndKeepSectionBreaksWithoutRewritingSource() {
        for (separator in listOf("\n\n", "\n　　\n", "\n \t\r\n")) {
            for (sectionBreak in listOf("\n\n\n", "\n\n \n", "\n　　\n \t\n")) {
                val data = SimpleTextComponentData("　　第一行\n段内换行${separator}　　第二段${sectionBreak}　　第三段")
                val prepared = PreparedTextContent(data)
                val paragraphs = prepared.paragraphs
                assertEquals(listOf("第一行\n段内换行", "第二段", "第三段"),
                    paragraphs.map { data.text.substring(it.contentStart, it.contentEnd) })
                assertEquals(listOf(0, 1, 0), paragraphs.map { it.blankLinesAfter })
                assertEquals(data.text, paragraphs.joinToString("") { data.text.substring(it.start, it.end) })
                assertEquals(data.toAnnotatedString(), prepared.text)
                assertSame(paragraphs, prepared.paragraphs)
                assertTrue(paragraphs.all { it.startsParagraph && it.endsParagraph })
            }
        }
    }

    @Test
    fun leadingBlankLinesKeepSourceCoordinatesWithoutParagraphPadding() {
        val data = SimpleTextComponentData("\n\n　　正文")
        val paragraphs = PreparedTextContent(data).paragraphs
        assertEquals(data.text, paragraphs.joinToString("") { data.text.substring(it.start, it.end) })
        assertEquals(0, paragraphs.first().contentEnd)
        assertEquals(1, paragraphs.first().blankLinesAfter)
        assertFalse(paragraphs.first().startsParagraph)
        assertFalse(paragraphs.first().endsParagraph)
        assertTrue(paragraphs.last().startsParagraph)
        assertEquals("正文", data.text.substring(paragraphs.last().contentStart, paragraphs.last().contentEnd))
    }

    @Test
    fun pageContinuationDoesNotAcquireParagraphIndentOrTrailingSpacing() {
        val data = SimpleTextComponentData("  续段正文\n\n　　新段")
        val paragraphs = PreparedTextContent(data, startsParagraph = false, endsParagraph = false).paragraphs
        assertFalse(paragraphs.first().startsParagraph)
        assertTrue(paragraphs.first().endsParagraph)
        assertEquals(0, paragraphs.first().contentStart)
        assertTrue(paragraphs.last().startsParagraph)
        assertFalse(paragraphs.last().endsParagraph)
        assertEquals("新段", data.text.substring(paragraphs.last().contentStart, paragraphs.last().contentEnd))
    }

    @Test
    fun paragraphOffsetsKeepRubyStylesAndUnicodeInOriginalCoordinates() {
        val data = SimpleTextComponentData("　　𠮷原词\n\n　　正文", listOf(SimpleTextStyleRange(2, 6, rubyText = "注释")))
        val prepared = PreparedTextContent(data)
        val paragraph = prepared.paragraphs.first()
        assertEquals(2, paragraph.contentStart)
        val text = prepared.text.subSequence(paragraph.contentStart, paragraph.contentEnd)
        assertEquals("𠮷原词", text.text)
        assertEquals(0, text.spanStyles.single().start)
        assertEquals(4, text.spanStyles.single().end)
        assertEquals(data.toAnnotatedString(), prepared.text)
        assertEquals(2, data.styleRanges.single().start)
    }

    @Test
    fun paragraphPaddingUsesSharedPixelRoundingAndOnlyRealBoundaries() {
        val spacing = ReaderStyle(fontSize = 20.sp, spacingBeforeParagraph = 3.5.sp,
            spacingAfterParagraph = 1.25.em).paragraphSpacing()
        val density = Density(2f, 1.5f)
        // em 跟随实际字号，不能将非线性字体缩放简单当作 fontScale 乘法。
        val after = (with(density) { 20.sp.toPx() } * 1.25f).roundToInt()
        assertEquals(ReaderParagraphPadding(11, after), spacing.padding(density, true, true))
        assertEquals(ReaderParagraphPadding(0, after), spacing.padding(density, false, true))
        assertEquals(ReaderParagraphPadding(11, 0), spacing.padding(density, true, false))
        assertEquals(ReaderParagraphPadding(0, 0), spacing.padding(density, false, false))
        assertEquals(ReaderParagraphPadding(4, 25), spacing.padding(Density(1f), true, true))
        assertEquals(ReaderParagraphPadding(8, 5), reusableParagraphSpacing(13, before = 8, previousAfter = 16))
        assertEquals(ReaderParagraphPadding(8, 16), reusableParagraphSpacing(40, before = 8, previousAfter = 16))
        assertEquals(ReaderParagraphPadding(0, 0), reusableParagraphSpacing(0, before = 8, previousAfter = 16))
    }

    @Test
    fun continuationIndentChangesLayoutOnlyAndPreservesTextStyles() {
        val text = PreparedTextContent(SimpleTextComponentData("原词\n续行",
            listOf(SimpleTextStyleRange(0, 2, fontWeight = 700)))).text
        val continued = text.withContinuationIndent(TextIndent(2.em, 1.em))
        assertEquals(text.text, continued.text)
        assertEquals(text.spanStyles, continued.spanStyles)
        assertEquals(TextIndent(1.em, 1.em), continued.paragraphStyles.single().item.textIndent)
        assertEquals(3, continued.paragraphStyles.single().end)
    }

    @Test
    fun blankAnnotationsDoNotEnableRubyPath() {
        assertFalse(PreparedTextContent(SimpleTextComponentData("正文")).hasRuby)
        assertFalse(PreparedTextContent(SimpleTextComponentData("正文", listOf(
            SimpleTextStyleRange(0, 2, rubyText = " ")
        ))).hasRuby)
    }
}
