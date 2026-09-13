package indi.dmzz_yyhyy.lightnovelreader.data.content.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class TextBlockPrefetchTest {
    @Test
    fun shortReversalsKeepTheSameRetainedBlocks() {
        val index = TextBlockIndex(List(1000) { 20 })
        val retained = index.retainedRange(IntRange.EMPTY, 1000f, 1200f)
        assertEquals(30 until 80, retained)
        for (top in listOf(1020f, 1000f, 980f, 1000f, 1100f, 900f)) {
            assertEquals(retained, index.retainedRange(retained, top, top + 200f))
        }
    }

    @Test
    fun reversingAfterARefillDoesNotImmediatelyEvictTheNewBlocks() {
        val index = TextBlockIndex(List(1000) { 20 })
        val first = index.retainedRange(IntRange.EMPTY, 1000f, 1200f)
        val next = index.retainedRange(first, 1320f, 1520f)
        assertTrue(first != next)
        assertEquals(next, index.retainedRange(next, 1300f, 1500f))
        assertEquals(next, index.retainedRange(next, 1320f, 1520f))
    }

    @Test
    fun continuousScrollingDoesNotMoveTheCacheAtEveryBlock() {
        val index = TextBlockIndex(List(1000) { 20 })
        var retained = index.retainedRange(IntRange.EMPTY, 1000f, 1200f)
        var changes = 0
        repeat(100) {
            val top = 1000f + it * 20
            val next = index.retainedRange(retained, top, top + 200f)
            if (next != retained) changes++
            retained = next
        }
        assertTrue("跨过100个块时不应调整缓存100次", changes in 1..9)
    }

    @Test
    fun prefetchCreatesAtMostOneBlockPerFrame() {
        val target = 10..50
        val required = 25..30
        var active = required
        repeat(35) {
            val next = prefetchTextBlock(active, required, target)
            assertEquals(active.count() + 1, next.count())
            assertTrue(active.all { it in next })
            assertTrue(next.all { it in target })
            active = next
        }
        assertEquals(target, active)
        assertEquals(target, prefetchTextBlock(active, required, target))
    }

    @Test
    fun prefetchPrioritizesTheLessBufferedSide() {
        assertEquals(19..30, prefetchTextBlock(20..30, 20..22, 10..40))
        assertEquals(20..31, prefetchTextBlock(20..30, 28..30, 10..40))
    }

    @Test
    fun fastScrollingAddsVisibleBlocksWithoutWaitingForPrefetch() {
        assertEquals(20..40, retainTextBlocks(20..30, 28..40, 10..50))
        assertEquals(10..30, retainTextBlocks(20..30, 10..22, 0..50))
    }

    @Test
    fun jumpingDoesNotCreateAllInterveningBlocks() {
        assertEquals(5000..5010, retainTextBlocks(0..50, 5000..5010, 4950..5050))
        assertEquals(5..15, retainTextBlocks(4950..5050, 5..15, 0..50))
    }

    @Test
    fun overlappingTargetDoesNotFillTheGapBetweenDisjointWindows() {
        assertEquals(60..80, retainTextBlocks(0..30, 60..80, 20..100))
        assertEquals(20..40, retainTextBlocks(70..100, 20..40, 0..80))
    }

    @Test
    fun adjacentWindowsStillReuseExistingBlocks() {
        assertEquals(20..50, retainTextBlocks(20..30, 31..50, 10..60))
        assertEquals(10..40, retainTextBlocks(20..40, 10..19, 0..60))
    }

    @Test
    fun shiftingTheTargetKeepsExistingBlocksAndDropsOnlyTheFarEnd() {
        assertEquals(30..80, retainTextBlocks(20..80, 50..60, 30..90))
        assertEquals(20..70, retainTextBlocks(20..80, 40..50, 10..70))
    }

    @Test
    fun emptyWindowsStopPrefetchAndReleaseDistantContent() {
        assertTrue(retainTextBlocks(0..50, IntRange.EMPTY, IntRange.EMPTY).isEmpty())
        assertTrue(prefetchTextBlock(IntRange.EMPTY, IntRange.EMPTY, IntRange.EMPTY).isEmpty())
        assertEquals(10..10, prefetchTextBlock(IntRange.EMPTY, IntRange.EMPTY, 10..20))
        val index = TextBlockIndex(List(100) { 20 })
        assertTrue(index.retainedRange(0..50, -1000f, -800f).isEmpty())
        assertTrue(index.retainedRange(50..99, 3000f, 3200f).isEmpty())
        assertTrue(index.retainedRange(0..50, Float.NaN, 3200f).isEmpty())
    }

    @Test
    fun zeroHeightWhitespaceAndChapterEndsRemainBounded() {
        val index = TextBlockIndex(listOf(0, 20, 0, 20, 0))
        assertEquals(0..4, index.retainedRange(IntRange.EMPTY, 0f, 20f))
        assertEquals(0..4, index.retainedRange(0..4, 20f, 40f))
        assertTrue(TextBlockIndex(emptyList()).retainedRange(IntRange.EMPTY, 0f, 100f).isEmpty())
    }

    @Test
    fun reversingWithinAWarmedWindowCreatesNoNewBlocks() {
        val index = TextBlockIndex(List(1000) { 20 })
        var target = index.retainedRange(IntRange.EMPTY, 1000f, 1200f)
        var active = target
        val warmed = active
        repeat(100) {
            val top = if (it % 2 == 0) 1040f else 960f
            val required = index.visibleRange(top, top + 200f, 50f)
            target = index.retainedRange(target, top, top + 200f)
            active = retainTextBlocks(active, required, target)
            active = prefetchTextBlock(active, required, target)
            assertEquals(warmed, active)
        }
    }

    @Test
    fun mixedScrollsAlwaysCoverVisibleTextAndKeepPrefetchBounded() {
        val random = Random(42)
        val index = TextBlockIndex(List(1000) { random.nextInt(0, 100) })
        var target = IntRange.EMPTY
        var active = IntRange.EMPTY
        repeat(500) {
            val top = random.nextInt(-1000, index.height + 1000).toFloat()
            val required = index.visibleRange(top, top + 600f, 150f)
            target = index.retainedRange(target, top, top + 600f)
            active = retainTextBlocks(active, required, target)
            assertTrue(required.all { it in active })
            val next = prefetchTextBlock(active, required, target)
            assertTrue(next.count() <= active.count() + 1)
            assertTrue(next.all { it in target })
            active = next
        }
    }
}
