package indi.dmzz_yyhyy.lightnovelreader.data.content.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class TextBlockSelectionTest {
    @Test
    fun firstIdleOnlyCreatesVisibleBlocks() {
        val index = TextBlockIndex(List(1000) { 20 })
        assertEquals(50..59, index.selectableRange(IntRange.EMPTY, 1000f, 1200f))
    }

    @Test
    fun remainingIdleDoesNotGrowSelectionBuffer() {
        val index = TextBlockIndex(List(1000) { 20 })
        var active = index.selectableRange(IntRange.EMPTY, 1000f, 1200f)
        repeat(100) {
            active = index.selectableRange(active, 1000f, 1200f)
            assertEquals(50..59, active)
        }
    }

    @Test
    fun nearbyReversalReusesPreviouslyVisibleNodes() {
        val index = TextBlockIndex(List(1000) { 20 })
        val first = index.selectableRange(IntRange.EMPTY, 1000f, 1200f)
        val moved = index.selectableRange(first, 1100f, 1300f)
        assertEquals(50..64, moved)
        assertEquals(moved, index.selectableRange(moved, 1080f, 1280f))
    }

    @Test
    fun jumpingDoesNotCreateTheGapToExistingNodes() {
        val index = TextBlockIndex(List(1000) { 20 })
        assertEquals(70..79, index.selectableRange(50..59, 1400f, 1600f))
        assertEquals(50..59, index.selectableRange(70..79, 1000f, 1200f))
    }

    @Test
    fun distantAndInvalidViewportsReleaseSelectionNodes() {
        val index = TextBlockIndex(List(1000) { 20 })
        assertTrue(index.selectableRange(50..59, -1000f, -800f).isEmpty())
        assertTrue(index.selectableRange(50..59, 21000f, 21200f).isEmpty())
        assertTrue(index.selectableRange(50..59, Float.NaN, 1200f).isEmpty())
        assertTrue(TextBlockIndex(emptyList()).selectableRange(IntRange.EMPTY, 0f, 200f).isEmpty())
    }

    @Test
    fun mixedScrollsOnlyAddVisibleBlocksAndPreserveExactHeight() {
        val random = Random(42)
        val heights = List(1000) { random.nextInt(0, 100) }
        val index = TextBlockIndex(heights)
        var active = IntRange.EMPTY
        repeat(500) {
            val top = random.nextInt(-1000, index.height + 1000).toFloat()
            val bottom = top + 600f
            val visible = index.visibleRange(top, bottom)
            val target = index.retainedRange(active, top, bottom)
            val next = index.selectableRange(active, top, bottom)
            assertTrue("全部可见块都能选择", visible.all { it in next })
            assertTrue("新增节点只能来自可见区", next.all { it in active || it in visible })
            assertTrue("旧节点仍受保留窗口约束", next.all { it in target })
            assertEquals(next, index.selectableRange(next, top, bottom))
            assertEquals(heights.sum(), index.height)
            active = next
        }
    }
}
