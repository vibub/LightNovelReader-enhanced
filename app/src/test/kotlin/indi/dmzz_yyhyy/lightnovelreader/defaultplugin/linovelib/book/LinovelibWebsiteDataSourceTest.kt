package indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.book

import io.nightfish.lightnovelreader.api.content.component.ImageComponentData
import io.nightfish.lightnovelreader.api.content.component.SimpleTextStyleRange
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinovelibWebsiteDataSourceTest {
    @Test
    fun nextLinovelibChapterPageIdUsesScriptNextPageForSameChapterPage() {
        val document = Jsoup.parse(
            """
            <html><body>
            <script>var prevpage="/novel/8/1843_3.html";var nextpage="/novel/8/1843_5.html";</script>
            </body></html>
            """.trimIndent(),
            "https://www.linovelib.com/novel/8/1843_4.html"
        )

        assertEquals("1843_5", document.nextLinovelibChapterPageId("8", "1843", 5))
    }

    @Test
    fun nextLinovelibChapterPageIdStopsWhenScriptNextPagePointsToNextChapter() {
        val document = Jsoup.parse(
            """
            <html><body>
            <script>var prevpage="/novel/8/1843_4.html";var nextpage="/novel/8/1844.html";</script>
            </body></html>
            """.trimIndent(),
            "https://www.linovelib.com/novel/8/1843_5.html"
        )

        assertNull(document.nextLinovelibChapterPageId("8", "1843", 6))
    }

    @Test
    fun extractLinovelibScriptPageMatchesLinovelibSample() {
        val document = Jsoup.parse(
            """
            <script>
            ${'$'}(document).ready(function(){var prevpage="/novel/8/1843_4.html";var nextpage="/novel/8/1844.html";var bookpage="/novel/8.html";});
            </script>
            """.trimIndent(),
            "https://www.linovelib.com/novel/8/1843_5.html"
        )

        assertEquals("/novel/8/1843_4.html", extractLinovelibScriptPage(document, "prevpage"))
        assertEquals("1843_4", extractLinovelibChapterPageId("8", extractLinovelibScriptPage(document, "prevpage")!!))
        assertEquals("/novel/8/1844.html", extractLinovelibScriptPage(document, "nextpage"))
        assertNull(document.nextLinovelibChapterPageId("8", "1843", 6))
    }

    @Test
    fun nextLinovelibChapterPageIdSupportsEscapedScriptSlashes() {
        val document = Jsoup.parse(
            """
            <html><body>
            <script>var nextpage="\/novel\/8\/1843_2.html";</script>
            </body></html>
            """.trimIndent(),
            "https://www.linovelib.com/novel/8/1843.html"
        )

        assertEquals("1843_2", extractLinovelibChapterPageId("8", extractLinovelibScriptPage(document, "nextpage")!!))
        assertEquals("1843_2", document.nextLinovelibChapterPageId("8", "1843", 2))
    }

    @Test
    fun extractLinovelibChapterPageIdSupportsCommonUrlForms() {
        assertEquals("1843_5", extractLinovelibChapterPageId("8", "https://www.linovelib.com/novel/8/1843_5.html"))
        assertEquals("1843_5", extractLinovelibChapterPageId("8", "/novel/8/1843_5.html"))
        assertEquals("1843_5", extractLinovelibChapterPageId("8", "1843_5.html"))
        assertEquals("1843_5", extractLinovelibChapterPageId("8", "/novel/8/1843_5.html?foo=bar#part"))
    }

    @Test
    fun extractLinovelibChapterPageIdRejectsOtherBookAndKeepsRawChapterContract() {
        assertNull(extractLinovelibChapterPageId("8", "https://www.linovelib.com/novel/9/1843_5.html"))
        assertEquals("18430_2", extractLinovelibChapterPageId("8", "/novel/8/18430_2.html"))
        assertEquals("1844", extractLinovelibChapterPageId("8", "/novel/8/1844.html"))
        assertFalse(isLinovelibPagedChapterId("1843", "18430_2"))
        assertFalse(isLinovelibPagedChapterId("1843", "1844"))
    }

    @Test
    fun nextLinovelibChapterPageIdFallsBackToRelativeAnchor() {
        val document = Jsoup.parse(
            """
            <html><body>
            <a href="1843_5.html">下一页</a>
            </body></html>
            """.trimIndent(),
            "https://www.linovelib.com/novel/8/1843_4.html"
        )

        assertEquals("1843_5", document.nextLinovelibChapterPageId("8", "1843", 5))
    }

    @Test
    fun toLinovelibAdjacentChapterIdKeepsAdjacentChapterBaseId() {
        assertEquals("1842", "1842".toLinovelibAdjacentChapterId("1843"))
        assertEquals("1842", "1842_5".toLinovelibAdjacentChapterId("1843"))
        assertNull("1843".toLinovelibAdjacentChapterId("1843"))
        assertNull("1843_2".toLinovelibAdjacentChapterId("1843"))
    }

    @Test
    fun scriptPrevAndNextCanInferAdjacentChapterIds() {
        val document = Jsoup.parse(
            """
            <script>var prevpage="/novel/8/1842_5.html";var nextpage="/novel/8/1844.html";</script>
            """.trimIndent(),
            "https://www.linovelib.com/novel/8/1843.html"
        )

        val prevPageId = extractLinovelibChapterPageId("8", extractLinovelibScriptPage(document, "prevpage")!!)
        val nextPageId = extractLinovelibChapterPageId("8", extractLinovelibScriptPage(document, "nextpage")!!)
        assertEquals("1842", prevPageId?.toLinovelibAdjacentChapterId("1843"))
        assertEquals("1844", nextPageId?.toLinovelibAdjacentChapterId("1843"))
    }

    @Test
    fun linovelibChapterPageSignatureDiffersWhenOnlyTailDiffers() {
        val samePrefix = "相同前缀".repeat(80)
        val first = Jsoup.parse("<div id=\"TextContent\">${samePrefix}第一页尾部</div>")
            .selectFirst("#TextContent")!!
        val second = Jsoup.parse("<div id=\"TextContent\">${samePrefix}第二页尾部</div>")
            .selectFirst("#TextContent")!!

        assertNotEquals(first.linovelibChapterPageSignature(), second.linovelibChapterPageSignature())
    }

    @Test
    fun mergeLinovelibPagedTextPartsAddsBlankLineBetweenPagedTextParts() {
        val parts = listOf(
            LinovelibChapterContentParser.Part.Text("上一页末段"),
            LinovelibChapterContentParser.Part.Text("下一页首段")
        )

        assertEquals(
            listOf(LinovelibChapterContentParser.Part.Text("上一页末段\n\n下一页首段")),
            parts.mergeLinovelibPagedTextParts()
        )
    }

    @Test
    fun mergeLinovelibPagedTextPartsKeepsSectionBlankLineBetweenPagedTextParts() {
        val parts = listOf(
            LinovelibChapterContentParser.Part.Text("上一页末段"),
            LinovelibChapterContentParser.Part.SectionBreak,
            LinovelibChapterContentParser.Part.Text("下一页新小节")
        )

        assertEquals(
            listOf(LinovelibChapterContentParser.Part.Text("上一页末段\n\n\n下一页新小节")),
            parts.mergeLinovelibPagedTextParts()
        )
    }

    @Test
    fun renderLinovelibSpacingKeepsSectionGapVisiblyLargerThanParagraphGap() {
        assertEquals("　　第一段\n\n　　第二段", "第一段\n\n第二段".renderLinovelibSpacing())
        assertEquals("　　第一段\n\n \n　　第二段", "第一段\n\n\n第二段".renderLinovelibSpacing())
    }

    @Test
    fun toLinovelibSimpleTextComponentDataShiftsStyleRangesAfterParagraphIndent() {
        val data = LinovelibChapterContentParser.Part.Text(
            text = "加粗段\n\n普通段",
            styleRanges = listOf(SimpleTextStyleRange(start = 0, end = 3, fontWeight = 700))
        ).toLinovelibSimpleTextComponentData()

        assertEquals("　　加粗段\n\n　　普通段", data.text)
        assertEquals(listOf(SimpleTextStyleRange(start = 2, end = 5, fontWeight = 700)), data.styleRanges)
    }

    @Test
    fun mergeLinovelibPagedTextPartsDoesNotUseTextSpacingAroundImage() {
        val parts = listOf(
            LinovelibChapterContentParser.Part.Text("插图前"),
            LinovelibChapterContentParser.Part.SectionBreak,
            LinovelibChapterContentParser.Part.Image("https://www.linovelib.com/image.jpg"),
            LinovelibChapterContentParser.Part.SectionBreak,
            LinovelibChapterContentParser.Part.Text("插图后")
        )

        assertEquals(
            listOf(
                LinovelibChapterContentParser.Part.Text("插图前"),
                LinovelibChapterContentParser.Part.Image("https://www.linovelib.com/image.jpg"),
                LinovelibChapterContentParser.Part.Text("插图后")
            ),
            parts.mergeLinovelibPagedTextParts()
        )
    }

    @Test
    fun linovelibImagePaddingKeepsSpacingOnlyAtConsecutiveImageRunEdges() {
        val parts = listOf(
            LinovelibChapterContentParser.Part.Text("插图前"),
            LinovelibChapterContentParser.Part.Image("https://www.linovelib.com/first.jpg"),
            LinovelibChapterContentParser.Part.Image("https://www.linovelib.com/second.jpg"),
            LinovelibChapterContentParser.Part.Image("https://www.linovelib.com/third.jpg"),
            LinovelibChapterContentParser.Part.Text("插图后")
        )

        assertEquals(ImageComponentData.DEFAULT_TOP_PADDING_DP, parts.linovelibImageTopPaddingDp(1))
        assertEquals(0, parts.linovelibImageBottomPaddingDp(1))
        assertEquals(0, parts.linovelibImageTopPaddingDp(2))
        assertEquals(0, parts.linovelibImageBottomPaddingDp(2))
        assertEquals(0, parts.linovelibImageTopPaddingDp(3))
        assertEquals(ImageComponentData.DEFAULT_BOTTOM_PADDING_DP, parts.linovelibImageBottomPaddingDp(3))
    }

    @Test
    fun linovelibImagePaddingKeepsSingleImagesSpacedFromText() {
        val parts = listOf(
            LinovelibChapterContentParser.Part.Text("第一段"),
            LinovelibChapterContentParser.Part.Image("https://www.linovelib.com/first.jpg"),
            LinovelibChapterContentParser.Part.Text("第二段"),
            LinovelibChapterContentParser.Part.Image("https://www.linovelib.com/second.jpg"),
            LinovelibChapterContentParser.Part.Text("第三段")
        )

        assertEquals(ImageComponentData.DEFAULT_TOP_PADDING_DP, parts.linovelibImageTopPaddingDp(1))
        assertEquals(ImageComponentData.DEFAULT_BOTTOM_PADDING_DP, parts.linovelibImageBottomPaddingDp(1))
        assertEquals(ImageComponentData.DEFAULT_TOP_PADDING_DP, parts.linovelibImageTopPaddingDp(3))
        assertEquals(ImageComponentData.DEFAULT_BOTTOM_PADDING_DP, parts.linovelibImageBottomPaddingDp(3))
    }

    @Test
    fun linovelibImagePaddingTreatsImagesSeparatedOnlyBySectionBreakAsConsecutive() {
        val parts = listOf(
            LinovelibChapterContentParser.Part.Image("https://www.linovelib.com/first.jpg"),
            LinovelibChapterContentParser.Part.SectionBreak,
            LinovelibChapterContentParser.Part.Image("https://www.linovelib.com/second.jpg")
        ).mergeLinovelibPagedTextParts()

        assertEquals(
            listOf(
                LinovelibChapterContentParser.Part.Image("https://www.linovelib.com/first.jpg"),
                LinovelibChapterContentParser.Part.Image("https://www.linovelib.com/second.jpg")
            ),
            parts
        )
        assertEquals(0, parts.linovelibImageTopPaddingDp(0))
        assertEquals(0, parts.linovelibImageBottomPaddingDp(0))
        assertEquals(0, parts.linovelibImageTopPaddingDp(1))
        assertEquals(0, parts.linovelibImageBottomPaddingDp(1))
    }

    @Test
    fun mergeLinovelibPagedTextPartsDoesNotMergeTextAcrossImages() {
        val parts = listOf(
            LinovelibChapterContentParser.Part.Text("上一页末段"),
            LinovelibChapterContentParser.Part.Image("https://www.linovelib.com/image.jpg"),
            LinovelibChapterContentParser.Part.Text("图片后正文")
        )

        assertEquals(parts, parts.mergeLinovelibPagedTextParts())
    }

    @Test
    fun mergeLinovelibPagedTextPartsKeepsImageBeforePagedText() {
        val parts = listOf(
            LinovelibChapterContentParser.Part.Image("https://www.linovelib.com/image.jpg"),
            LinovelibChapterContentParser.Part.Text("下一页首段")
        )

        assertEquals(parts, parts.mergeLinovelibPagedTextParts())
    }

    @Test
    fun mergeLinovelibPagedTextPartsMergesEachTextRunAroundImages() {
        val parts = listOf(
            LinovelibChapterContentParser.Part.Text("第一段"),
            LinovelibChapterContentParser.Part.Text("第二段"),
            LinovelibChapterContentParser.Part.Image("https://www.linovelib.com/image.jpg"),
            LinovelibChapterContentParser.Part.Text("第三段"),
            LinovelibChapterContentParser.Part.Text("第四段")
        )

        assertEquals(
            listOf(
                LinovelibChapterContentParser.Part.Text("第一段\n\n第二段"),
                LinovelibChapterContentParser.Part.Image("https://www.linovelib.com/image.jpg"),
                LinovelibChapterContentParser.Part.Text("第三段\n\n第四段")
            ),
            parts.mergeLinovelibPagedTextParts()
        )
    }
}
