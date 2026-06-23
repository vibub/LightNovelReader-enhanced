package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.scroll

import org.junit.Assert.assertEquals
import org.junit.Test

class ScrollReadingProgressTest {
    @Test
    fun componentProgressUsesOnlyRealContentComponents() {
        val progress = scrollContentChapterProgress(
            key = ParsedScrollContentItemKey("chapter", ScrollContentItemType.Component, index = 2),
            itemOffset = 0,
            itemSize = 100,
            viewportHeight = 100,
            componentCount = 3
        )

        assertEquals(1f, progress, 0.0001f)
    }

    @Test
    fun headerAndFooterDoNotConsumeProgressRange() {
        val headerProgress = scrollContentChapterProgress(
            key = ParsedScrollContentItemKey("chapter", ScrollContentItemType.Header),
            itemOffset = 50,
            itemSize = 300,
            viewportHeight = 100,
            componentCount = 3
        )
        val footerProgress = scrollContentChapterProgress(
            key = ParsedScrollContentItemKey("chapter", ScrollContentItemType.Footer),
            itemOffset = 99,
            itemSize = 1,
            viewportHeight = 100,
            componentCount = 3
        )

        assertEquals(0f, headerProgress, 0.0001f)
        assertEquals(1f, footerProgress, 0.0001f)
    }

    @Test
    fun componentProgressUsesMeasuredHeightsWhenAvailable() {
        val progress = scrollContentChapterProgress(
            key = ParsedScrollContentItemKey("chapter", ScrollContentItemType.Component, index = 1),
            itemOffset = 0,
            itemSize = 1000,
            viewportHeight = 100,
            componentCount = 3,
            componentHeights = mapOf(
                0 to 100,
                1 to 1000,
                2 to 100
            )
        )

        assertEquals(200f / 1200f, progress, 0.0001f)
    }

    @Test
    fun measuredTallComponentConsumesMoreProgressRange() {
        val progress = scrollContentChapterProgress(
            key = ParsedScrollContentItemKey("chapter", ScrollContentItemType.Component, index = 0),
            itemOffset = 0,
            itemSize = 100,
            viewportHeight = 100,
            componentCount = 3,
            componentHeights = mapOf(
                0 to 100,
                1 to 1000,
                2 to 100
            )
        )

        assertEquals(100f / 1200f, progress, 0.0001f)
    }

    @Test
    fun currentComponentKeepsLargerKnownHeightForConsistentWeights() {
        val progress = scrollContentChapterProgress(
            key = ParsedScrollContentItemKey("chapter", ScrollContentItemType.Component, index = 1),
            itemOffset = 0,
            itemSize = 100,
            viewportHeight = 100,
            componentCount = 3,
            componentHeights = mapOf(
                0 to 100,
                1 to 1000,
                2 to 100
            )
        )

        assertEquals(1100f / 1200f, progress, 0.0001f)
    }

    @Test
    fun unknownComponentHeightsUseStableViewportFallback() {
        val progress = scrollContentChapterProgress(
            key = ParsedScrollContentItemKey("chapter", ScrollContentItemType.Component, index = 1),
            itemOffset = 0,
            itemSize = 1000,
            viewportHeight = 100,
            componentCount = 3,
            componentHeights = mapOf(0 to 1000)
        )

        assertEquals(1100f / 2100f, progress, 0.0001f)
    }

    @Test
    fun restoreTargetUsesComponentScale() {
        val target = scrollContentRestoreTarget(
            progress = 0.75f,
            componentIndices = listOf(11, 12, 13),
            headerIndex = 10,
            footerIndex = 14,
            fallbackIndex = 14
        )

        assertEquals(13, target.itemIndex)
        assertEquals(0.25f, target.itemProgress, 0.0001f)
    }

    @Test
    fun weightedRestoreTargetUsesMeasuredHeights() {
        val target = scrollContentRestoreTarget(
            progress = 0.5f,
            componentIndices = listOf(11, 12, 13),
            headerIndex = 10,
            footerIndex = 14,
            fallbackIndex = 14,
            componentHeights = mapOf(
                0 to 100,
                1 to 1000,
                2 to 100
            )
        )

        assertEquals(12, target.itemIndex)
        assertEquals(0.5f, target.itemProgress, 0.0001f)
    }

    @Test
    fun restoreTargetKeepsStartAndEndOnChromeItems() {
        val start = scrollContentRestoreTarget(
            progress = 0f,
            componentIndices = listOf(11, 12, 13),
            headerIndex = 10,
            footerIndex = 14,
            fallbackIndex = 14
        )
        val end = scrollContentRestoreTarget(
            progress = 1f,
            componentIndices = listOf(11, 12, 13),
            headerIndex = 10,
            footerIndex = 14,
            fallbackIndex = 14
        )

        assertEquals(10, start.itemIndex)
        assertEquals(0f, start.itemProgress, 0.0001f)
        assertEquals(14, end.itemIndex)
        assertEquals(1f, end.itemProgress, 0.0001f)
    }

    @Test
    fun emptyChapterRestoreFallsBackToFooterAfterStart() {
        val target = scrollContentRestoreTarget(
            progress = 0.5f,
            componentIndices = emptyList(),
            headerIndex = 10,
            footerIndex = 11,
            fallbackIndex = 11
        )

        assertEquals(11, target.itemIndex)
        assertEquals(1f, target.itemProgress, 0.0001f)
    }

    @Test
    fun scrollReadingAnchorRoundTrips() {
        val anchor = ScrollReadingAnchor(
            chapterId = "chapter",
            itemType = ScrollContentItemType.Component.name,
            componentIndex = 3,
            itemProgress = 0.46f,
            itemSize = 1200,
            viewportHeight = 900,
            componentHeights = mapOf(0 to 320, 3 to 1200, 5 to 1800),
            componentCount = 8
        )

        val restored = decodeScrollReadingAnchor(encodeScrollReadingAnchor(anchor))

        assertEquals(anchor, restored)
        assertEquals(ScrollContentItemType.Component, restored?.parsedItemType)
    }
}
