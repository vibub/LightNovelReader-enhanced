package indi.dmzz_yyhyy.lightnovelreader.data.content.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RubyLineSpacingTest {
    @Test
    fun pageBreakKeepsBorrowedBlankWithItsRubyLine() {
        val lines = listOf(
            RubyPageLine(0, 5, 24, false),
            RubyPageLine(5, 6, 16, true, reclaimedHeight = 12),
            RubyPageLine(6, 12, 36, false)
        )
        assertEquals(listOf(0 until 5, 5 until 12), rubyPageRanges(lines, 60))
        assertEquals(listOf(0 until 12), rubyPageRanges(lines, 76))
    }

    @Test
    fun oversizedBlankGroupUsesConservativeHeightsAndKeepsAllOffsets() {
        val lines = listOf(
            RubyPageLine(0, 5, 24, false),
            RubyPageLine(5, 6, 28, true, reclaimedHeight = 12),
            RubyPageLine(6, 12, 48, false)
        )
        assertEquals(listOf(0 until 5, 5 until 6, 6 until 12), rubyPageRanges(lines, 60))
    }

    @Test
    fun ordinaryPaginationAndOversizedSingleLineStillProgress() {
        assertEquals(
            listOf(0 until 4, 4 until 8),
            rubyPageRanges(listOf(RubyPageLine(0, 4, 24, false), RubyPageLine(4, 8, 24, false)), 30)
        )
        assertEquals(listOf(0 until 4), rubyPageRanges(listOf(RubyPageLine(0, 4, 100, false)), 30))
    }

    @Test
    fun paragraphGapAbsorbsRubyWithoutMovingFollowingBody() {
        val lines = listOf(
            RubyLineSpace(24, false),
            RubyLineSpace(28, true),
            RubyLineSpace(36, false, extraAbove = 12)
        )
        val reclaimed = reusableRubySpacing(lines)
        assertEquals(listOf(0, 12, 0), reclaimed)
        assertEquals(24 + 28 + 24, lines.sumOf { it.height } - reclaimed.sum())
        assertEquals(24 + 28, 24 + (28 - reclaimed[1]) + lines[2].extraAbove)
    }

    @Test
    fun insufficientParagraphSpaceOnlyAddsTheMissingHeight() {
        val lines = listOf(RubyLineSpace(24, false), RubyLineSpace(5, true), RubyLineSpace(36, false, 12))
        val reclaimed = reusableRubySpacing(lines)
        assertEquals(listOf(0, 5, 0), reclaimed)
        assertEquals(7, lines.last().extraAbove - reclaimed.sum())
    }

    @Test
    fun continuousBlankLinesAreUsedFromNearestToFarthest() {
        val lines = listOf(
            RubyLineSpace(24, false), RubyLineSpace(20, true), RubyLineSpace(8, true),
            RubyLineSpace(44, false, 20)
        )
        assertEquals(listOf(0, 12, 8, 0), reusableRubySpacing(lines))
    }

    @Test
    fun borrowingNeverCrossesAVisibleLine() {
        val lines = listOf(
            RubyLineSpace(28, true), RubyLineSpace(24, false), RubyLineSpace(36, false, 12)
        )
        assertEquals(listOf(0, 0, 0), reusableRubySpacing(lines))
    }

    @Test
    fun sufficientLineSpacingLeavesParagraphGapUntouched() {
        val lines = listOf(RubyLineSpace(40, false), RubyLineSpace(40, true), RubyLineSpace(40, false))
        assertEquals(listOf(0, 0, 0), reusableRubySpacing(lines))
    }

    @Test
    fun consecutiveRubyParagraphsOnlyUseTheirOwnBlankLines() {
        val lines = listOf(
            RubyLineSpace(24, false), RubyLineSpace(28, true), RubyLineSpace(36, false, 12),
            RubyLineSpace(28, true), RubyLineSpace(44, false, 20)
        )
        val reclaimed = reusableRubySpacing(lines)
        assertEquals(listOf(0, 12, 0, 20, 0), reclaimed)
        lines.zip(reclaimed).forEach { (line, used) -> assertTrue(line.height - used >= 0) }
    }

    @Test
    fun chapterStartAndZeroHeightBlankNeverProduceNegativeSpacing() {
        assertEquals(listOf(0), reusableRubySpacing(listOf(RubyLineSpace(36, false, 12))))
        assertEquals(listOf(0, 0), reusableRubySpacing(listOf(RubyLineSpace(0, true), RubyLineSpace(36, false, 12))))
    }
}
