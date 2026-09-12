package indi.dmzz_yyhyy.lightnovelreader.data.content.component

import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class RetainedLayoutCacheTest {
    @Test
    fun keepsInitialLayoutWhenReadingWindowArrives() {
        val cache = RetainedLayoutCache<String, Any>()
        val layout = Any()
        cache.put("current", layout)
        cache.retain(setOf("previous", "current", "next"))
        assertSame(layout, cache["current"])
    }

    @Test
    fun chapterShiftRetainsOverlappingLayouts() {
        val cache = RetainedLayoutCache<String, Any>()
        val current = Any()
        val next = Any()
        cache.retain(setOf("previous", "current", "next"))
        cache.put("previous", Any())
        cache.put("current", current)
        cache.put("next", next)
        cache.retain(setOf("current", "next", "following"))
        assertNull(cache["previous"])
        assertSame(current, cache["current"])
        assertSame(next, cache["next"])
    }

    @Test
    fun reorderingForReverseScrollingDoesNotDiscardLayouts() {
        val cache = RetainedLayoutCache<String, Any>()
        val layout = Any()
        cache.retain(linkedSetOf("next", "previous", "current"))
        cache.put("previous", layout)
        cache.retain(linkedSetOf("previous", "current", "next"))
        assertSame(layout, cache["previous"])
    }

    @Test
    fun rejectsLateResultsForRemovedContent() {
        val cache = RetainedLayoutCache<String, Any>()
        cache.retain(setOf("old"))
        cache.retain(setOf("new"))
        cache.put("old", Any())
        assertNull(cache["old"])
    }

    @Test
    fun emptyWindowReleasesAllLayouts() {
        val cache = RetainedLayoutCache<String, Any>()
        cache.put("chapter", Any())
        cache.retain(emptySet())
        assertNull(cache["chapter"])
    }

    @Test
    fun copiesRetainedKeysInsteadOfFollowingCallerMutations() {
        val cache = RetainedLayoutCache<String, Any>()
        val keys = mutableSetOf("chapter")
        cache.retain(keys)
        keys.clear()
        val layout = Any()
        cache.put("chapter", layout)
        assertSame(layout, cache["chapter"])
    }
}
