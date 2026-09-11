package indi.dmzz_yyhyy.lightnovelreader.data.content.component

import io.nightfish.lightnovelreader.api.content.component.SimpleTextComponentData
import io.nightfish.lightnovelreader.api.content.component.SimpleTextStyleRange
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RubyTextLayoutTest {
    @Test
    fun visibleGapExcludesFontBoxPadding() {
        val baseTop = rubyBaseTop(2, 18, 1, annotationBottomInset = 4f, baseTopInset = 5f)
        assertEquals(12, baseTop)
        assertEquals(1f, baseTop + 5f - (2 + 18 - 4f), 0.001f)
    }

    @Test
    fun visibleGapRoundsUpInsteadOfOverlappingGlyphs() {
        val baseTop = rubyBaseTop(2, 18, 1, annotationBottomInset = 2.4f, baseTopInset = 3.2f)
        val gap = baseTop + 3.2f - (2 + 18 - 2.4f)
        assertTrue(gap >= 1f && gap < 2f)
    }

    @Test
    fun fontsWithoutExtraPaddingKeepTheRequestedGap() {
        assertEquals(21, rubyBaseTop(2, 18, 1, 0f, 0f))
    }

    @Test
    fun shortAnnotationSpreadsAcrossBaseWidth() {
        val placement = placeRubyAnnotation(listOf(10, 10, 10), 100)
        assertEquals(1f, placement.scale, 0.001f)
        assertEquals(listOf(5f, 45f, 85f), placement.offsets)
    }

    @Test
    fun variableWidthAnnotationAlignsBothEnds() {
        val widths = listOf(5, 12, 7, 10)
        val placement = placeRubyAnnotation(widths, 100)
        assertEquals(5f, placement.offsets.first(), 0.001f)
        assertEquals(95f, placement.offsets.last() + widths.last(), 0.001f)
        val gaps = (0 until widths.lastIndex).map { index ->
            placement.offsets[index + 1] - placement.offsets[index] - widths[index]
        }
        gaps.forEach { assertEquals(gaps.first(), it, 0.001f) }
    }

    @Test
    fun singleCharacterAnnotationStaysCentered() {
        assertEquals(RubyAnnotationPlacement(1f, listOf(45f)), placeRubyAnnotation(listOf(10), 100))
    }

    @Test
    fun longAnnotationShrinksWithoutExpandingBase() {
        val placement = placeRubyAnnotation(listOf(20, 20, 20), 30)
        assertEquals(0.45f, placement.scale, 0.001f)
        assertEquals(listOf(1.5f, 10.5f, 19.5f), placement.offsets)
        assertEquals(28.5f, placement.offsets.last() + 20 * placement.scale, 0.001f)
    }

    @Test
    fun emptyAnnotationHasNoPositions() {
        assertEquals(RubyAnnotationPlacement(1f, emptyList()), placeRubyAnnotation(emptyList(), 100))
    }

    @Test
    fun annotationCharactersKeepCombiningMarksAndSurrogatePairs() {
        assertEquals(listOf("𠮷", "é", "Ｃ"), "𠮷éＣ".rubyCharacters())
    }

    @Test
    fun singleLatinWordKeepsItsLettersTogetherAndStaysCentered() {
        assertEquals(listOf("Eintagsfliege"), "Eintagsfliege".rubyAnnotationUnits())
        assertEquals(RubyAnnotationPlacement(1f, listOf(20f)), placeRubyAnnotation(listOf(60), 100))
    }

    @Test
    fun multipleWordsOnlyExpandBetweenWords() {
        assertEquals(listOf("Minimum", "Range"), "Minimum Range".rubyAnnotationUnits())
        val placement = placeRubyAnnotation(listOf(30, 20), 100, minimumGap = 4f)
        assertEquals(1f, placement.scale, 0.001f)
        assertEquals(listOf(5f, 75f), placement.offsets)
    }

    @Test
    fun longPhrasePreservesWordSeparationWhenShrunk() {
        val placement = placeRubyAnnotation(listOf(50, 50), 100, minimumGap = 10f)
        val gap = placement.offsets[1] - placement.offsets[0] - 50 * placement.scale
        assertEquals(10 * placement.scale, gap, 0.001f)
        assertEquals(95f, placement.offsets.last() + 50 * placement.scale, 0.001f)
    }

    @Test
    fun coordinatesStillDistributeIndividualCharacters() {
        assertEquals(listOf("Ｃ", "一", "○", "六", "一", "一", "四"), "Ｃ一○六一一四".rubyAnnotationUnits())
    }

    @Test
    fun wordsKeepAccentsConnectorsAndIgnoreExtraWhitespace() {
        assertEquals(
            listOf("l’été", "éclair", "anti-air"),
            "  l’été  éclair\tanti-air  ".rubyAnnotationUnits()
        )
    }

    @Test
    fun rubySurvivesSerialization() {
        val data = SimpleTextComponentData(
            "阻电扰乱型",
            listOf(SimpleTextStyleRange(0, 5, rubyText = "Eintagsfliege"))
        )
        val json = SimpleTextComponentData.jsonSerializer.toJsonElement(data)
        assertEquals(data, SimpleTextComponentData.jsonSerializer.fromJsonElement(json))
    }

    @Test
    fun oldCachedStylesNeedNoRubyField() {
        val data = SimpleTextComponentData.jsonSerializer.fromJsonElement(
            Json.parseToJsonElement("""{"text":"正文","styleRanges":[{"start":0,"end":2,"fontWeight":700}]}""")
        )
        assertNull(data.styleRanges.single().rubyText)
    }

    @Test
    fun pageSlicesKeepOnlyTheirPartOfAnnotation() {
        val range = SimpleTextStyleRange(4, 8, rubyText = "一二三四")
        assertEquals("一二", range.rubySubstring(4, 6))
        assertEquals("三四", range.rubySubstring(6, 8))
        assertEquals("一二三四", range.rubySubstring(4, 8))
    }

    @Test
    fun annotationSlicesDoNotBreakSurrogatePairsOrLoseLetters() {
        val range = SimpleTextStyleRange(0, 3, rubyText = "𠮷abc")
        val pieces = (0 until 3).map { range.rubySubstring(it, it + 1) }
        assertEquals(listOf("𠮷", "a", "bc"), pieces)
        assertEquals(range.rubyText, pieces.joinToString(""))
    }
}
