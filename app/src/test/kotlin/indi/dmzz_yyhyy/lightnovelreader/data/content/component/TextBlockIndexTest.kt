package indi.dmzz_yyhyy.lightnovelreader.data.content.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class TextBlockIndexTest {
    @Test
    fun prefixHeightsKeepChapterGeometryUnchanged() {
        val index = TextBlockIndex(listOf(24, 16, 36, 28))
        assertEquals(4, index.size)
        assertEquals(104, index.height)
        assertEquals(listOf(0, 24, 40, 76, 104), (0..index.size).map(index::top))
        index.visibleRange(40f, 76f)
        assertEquals(104, index.height)
    }

    @Test
    fun exactEdgesDoNotIncludeNeighbouringBlocks() {
        val index = TextBlockIndex(listOf(20, 20, 20, 20))
        assertEquals(1 until 3, index.visibleRange(20f, 60f))
        assertEquals(0 until 1, index.visibleRange(0f, 20f))
        assertEquals(3 until 4, index.visibleRange(60f, 80f))
    }

    @Test
    fun fractionalPositionsIncludePartiallyVisibleBlocks() {
        val index = TextBlockIndex(listOf(20, 20, 20, 20))
        assertEquals(0 until 4, index.visibleRange(19.5f, 60.5f))
    }

    @Test
    fun overscanKeepsOneScreenAboveAndBelow() {
        val index = TextBlockIndex(List(100) { 20 })
        assertEquals(20 until 50, index.visibleRange(600f, 800f, 200f))
        assertEquals(0 until 20, index.visibleRange(0f, 200f, 200f))
        assertEquals(80 until 100, index.visibleRange(1800f, 2000f, 200f))
    }

    @Test
    fun distantChaptersHaveNoVisibleBlocks() {
        val index = TextBlockIndex(listOf(20, 20, 20))
        assertTrue(index.visibleRange(-100f, -20f, 20f).isEmpty())
        assertTrue(index.visibleRange(80f, 160f, 20f).isEmpty())
    }

    @Test
    fun zeroHeightWhitespaceDoesNotTrapTheSearch() {
        val index = TextBlockIndex(listOf(0, 20, 0, 0, 20, 0))
        assertEquals(1 until 2, index.visibleRange(0f, 20f))
        assertEquals(4 until 5, index.visibleRange(20f, 40f))
        assertEquals(1 until 5, index.visibleRange(19f, 21f))
    }

    @Test
    fun emptyAndUnpositionedWindowsAreSafe() {
        assertTrue(TextBlockIndex(emptyList()).visibleRange(0f, 100f).isEmpty())
        assertTrue(TextBlockIndex(listOf(0, 0)).visibleRange(0f, 100f).isEmpty())
        val index = TextBlockIndex(listOf(20))
        assertTrue(index.visibleRange(0f, 0f).isEmpty())
        assertTrue(index.visibleRange(20f, 10f).isEmpty())
        assertTrue(index.visibleRange(Float.NaN, 100f).isEmpty())
        assertTrue(index.visibleRange(0f, Float.POSITIVE_INFINITY).isEmpty())
    }

    @Test
    fun jumpingToChapterMiddleDoesNotKeepTheBeginningComposed() {
        val index = TextBlockIndex(List(10000) { 24 })
        assertEquals(4950 until 5100, index.visibleRange(120000f, 121200f, 1200f))
        assertEquals(240000, index.height)
    }

    @Test
    fun smallScrollsWithinTheSameBlocksKeepTheSameRange() {
        val index = TextBlockIndex(List(100) { 100 })
        assertEquals(index.visibleRange(101f, 299f, 100f), index.visibleRange(102f, 298f, 100f))
    }

    @Test
    fun veryTallBlockRemainsOneBlock() {
        val index = TextBlockIndex(listOf(20, 500000, 20))
        assertEquals(1 until 2, index.visibleRange(250000f, 251000f, 1000f))
        assertEquals(500040, index.height)
    }

    @Test
    fun binarySearchMatchesLinearReference() {
        val random = Random(42)
        repeat(100) {
            val heights = List(100) { random.nextInt(0, 100) }
            val index = TextBlockIndex(heights)
            repeat(30) {
                val top = random.nextInt(-300, index.height + 300).toFloat()
                val bottom = top + random.nextInt(1, 500)
                val overscan = random.nextInt(0, 200).toFloat()
                val range = index.visibleRange(top, bottom, overscan)
                val expected = heights.indices.filter {
                    heights[it] > 0 && index.top(it) < bottom + overscan && index.top(it + 1) > top - overscan
                }
                assertEquals(expected, range.filter { heights[it] > 0 })
            }
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeHeightsAreRejected() {
        TextBlockIndex(listOf(20, -1))
    }
}
