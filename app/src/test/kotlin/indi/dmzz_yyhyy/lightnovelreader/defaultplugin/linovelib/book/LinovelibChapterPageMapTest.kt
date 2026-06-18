package indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.book

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Test

class LinovelibChapterPageMapTest {
    @Test
    fun targetLinovelibChapterPageIdFallsBackToBaseChapterIdWhenMetadataMissing() {
        assertEquals(
            "12345",
            emptyContent().targetLinovelibChapterPageId("12345_2", 0.8f)
        )
    }

    @Test
    fun targetLinovelibChapterPageIdUsesRealPageBoundaries() {
        val content = emptyContent().withLinovelibChapterPageMap(
            listOf(
                LinovelibChapterPageBoundary("12345", 0, 100),
                LinovelibChapterPageBoundary("12345_2", 100, 300),
                LinovelibChapterPageBoundary("12345_3", 300, 600)
            )
        )

        assertEquals("12345", content.targetLinovelibChapterPageId("12345", 0f))
        assertEquals("12345_2", content.targetLinovelibChapterPageId("12345", 0.2f))
        assertEquals("12345_3", content.targetLinovelibChapterPageId("12345", 0.9f))
        assertEquals("12345_3", content.targetLinovelibChapterPageId("12345", 1f))
    }

    @Test
    fun targetLinovelibChapterPageIdClampsInvalidProgress() {
        val content = emptyContent().withLinovelibChapterPageMap(
            listOf(
                LinovelibChapterPageBoundary("12345", 0, 100),
                LinovelibChapterPageBoundary("12345_2", 100, 200)
            )
        )

        assertEquals("12345", content.targetLinovelibChapterPageId("12345", Float.NaN))
        assertEquals("12345", content.targetLinovelibChapterPageId("12345", -1f))
        assertEquals("12345_2", content.targetLinovelibChapterPageId("12345", 2f))
    }

    @Test
    fun linovelibChapterPageMapFiltersInvalidBoundaries() {
        val content = emptyContent().withLinovelibChapterPageMap(
            listOf(
                LinovelibChapterPageBoundary("", 0, 100),
                LinovelibChapterPageBoundary("12345", 100, 100),
                LinovelibChapterPageBoundary("12345_2", 100, 200)
            )
        )

        assertEquals(
            listOf(LinovelibChapterPageBoundary("12345_2", 100, 200)),
            content.linovelibChapterPageMap()
        )
    }

    @Test
    fun linovelibContentWeightCountsTextAndImages() {
        val parts = listOf(
            LinovelibChapterContentParser.Part.Text("abc"),
            LinovelibChapterContentParser.Part.Image("https://www.linovelib.com/image.jpg"),
            LinovelibChapterContentParser.Part.SectionBreak
        )

        assertEquals(803, parts.linovelibContentWeight())
    }

    private fun emptyContent() = buildJsonObject {
        putJsonArray("components") {}
    }
}
