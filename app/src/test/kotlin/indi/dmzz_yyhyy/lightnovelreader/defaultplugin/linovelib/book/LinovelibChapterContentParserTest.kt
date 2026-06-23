package indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.book

import io.nightfish.lightnovelreader.api.content.component.SimpleTextStyleRange
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinovelibChapterContentParserTest {
    @Test
    fun parseKeepsDomOrderWhenNoOrderSignal() {
        val result = parse(
            """
                <div id="TextContent">
                    <p>第一段</p>
                    <p>第二段</p>
                </div>
            """.trimIndent()
        )

        assertNull(result.warning)
        assertEquals(listOf("第一段", "第二段"), result.paragraphs())
    }

    @Test
    fun parseRestoresInlineStyleOrder() {
        val result = parse(
            """
                <div id="TextContent">
                    <p style="order: 2">第二段</p>
                    <p style="order: 1">第一段</p>
                    <p style="order: 3">第三段</p>
                </div>
            """.trimIndent()
        )

        assertNull(result.warning)
        assertEquals(listOf("第一段", "第二段", "第三段"), result.paragraphs())
    }

    @Test
    fun parseRestoresDataOrder() {
        val result = parse(
            """
                <div id="TextContent">
                    <p data-order="20">第二段</p>
                    <p data-order="10">第一段</p>
                </div>
            """.trimIndent()
        )

        assertNull(result.warning)
        assertEquals(listOf("第一段", "第二段"), result.paragraphs())
    }

    @Test
    fun parseDoesNotReturnDomOrderWhenOrderSignalIsIncomplete() {
        val result = parse(
            """
                <div id="TextContent">
                    <p style="order: 2">第二段</p>
                    <p>第一段</p>
                </div>
            """.trimIndent()
        )

        assertNotNull(result.warning)
        assertEquals(listOf(LinovelibChapterContentParser.WARNING_MESSAGE), result.paragraphs())
    }

    @Test
    fun parseKeepsTextAroundImage() {
        val result = parse(
            """
                <div id="TextContent">
                    <p>图片前<img src="/img.jpg">图片后</p>
                </div>
            """.trimIndent()
        )

        assertNull(result.warning)
        assertEquals(
            listOf(
                LinovelibChapterContentParser.Part.Text("图片前"),
                LinovelibChapterContentParser.Part.Image("https://www.linovelib.com/img.jpg"),
                LinovelibChapterContentParser.Part.Text("图片后")
            ),
            result.parts
        )
    }

    @Test
    fun parseKeepsBoldStyleRange() {
        val result = parse(
            """
                <div id="TextContent">
                    <p><b>加粗标题 </b>普通正文<strong>强调</strong></p>
                </div>
            """.trimIndent()
        )

        assertNull(result.warning)
        assertEquals(
            listOf(
                LinovelibChapterContentParser.Part.Text(
                    text = "加粗标题 普通正文强调",
                    styleRanges = listOf(
                        SimpleTextStyleRange(start = 0, end = 5, fontWeight = 700),
                        SimpleTextStyleRange(start = 9, end = 11, fontWeight = 700)
                    )
                )
            ),
            result.parts
        )
    }

    @Test
    fun parseKeepsInlineStyleRangeAfterTrimming() {
        val result = parse(
            """
                <div id="TextContent">
                    <p><b>标题 </b></p>
                </div>
            """.trimIndent()
        )

        assertEquals(
            listOf(
                LinovelibChapterContentParser.Part.Text(
                    text = "标题",
                    styleRanges = listOf(SimpleTextStyleRange(start = 0, end = 2, fontWeight = 700))
                )
            ),
            result.parts
        )
    }

    @Test
    fun parseFiltersNoisePerParagraph() {
        val result = parse(
            """
                <div id="TextContent">
                    <p>正文</p>
                    <p>最新网址：example.com，请收藏</p>
                    <p>后续正文</p>
                </div>
            """.trimIndent()
        )

        assertNull(result.warning)
        assertEquals(listOf("正文", "后续正文"), result.paragraphs())
    }

    @Test
    fun parseRestoresProblemShapeWhenOrderSignalExists() {
        val result = parse(
            """
                <div id="TextContent">
                    <p style="order: 1">「呃，outcome……吗？拼法是……」</p>
                    <p style="order: 3">前往打工书店的途中──</p>
                    <p style="order: 2">我小声念出来，然后翻开单字卡。</p>
                </div>
            """.trimIndent()
        )

        val texts = result.paragraphs()
        assertEquals(
            listOf(
                "「呃，outcome……吗？拼法是……」",
                "我小声念出来，然后翻开单字卡。",
                "前往打工书店的途中──"
            ),
            texts
        )
        assertTrue(
            texts.indexOf("我小声念出来，然后翻开单字卡。") ==
                texts.indexOf("「呃，outcome……吗？拼法是……」") + 1
        )
    }

    @Test
    fun parseRestoresLinovelibSeededOrder() {
        val result = parse(
            """
                <div id="TextContent">
                    ${problemParagraphs()}
                </div>
            """.trimIndent(),
            chapterId = "225115"
        )

        val texts = result.paragraphs()
        assertNull(result.warning)
        assertEquals("「呃，outcome……吗？拼法是……」", texts[19])
        assertEquals("我小声念出来，然后翻开单字卡。", texts[20])
        assertTrue(texts.indexOf("前往打工书店的途中──") > 20)
    }

    @Test
    fun parseRestoresLinovelibSeededOrderWithPagedChapterId() {
        val result = parse(
            """
                <div id="TextContent">
                    ${problemParagraphs()}
                </div>
            """.trimIndent(),
            chapterId = "225115_2"
        )

        val texts = result.paragraphs()
        assertNull(result.warning)
        assertEquals("「呃，outcome……吗？拼法是……」", texts[19])
        assertEquals("我小声念出来，然后翻开单字卡。", texts[20])
        assertTrue(texts.indexOf("前往打工书店的途中──") > 20)
    }

    @Test
    fun parseContinuesToSeededRestoreWhenExplicitOrderIsIncomplete() {
        val result = parse(
            """
                <div id="TextContent">
                    ${problemParagraphs { index -> if (index == 0) " style=\"order: 1\"" else "" }}
                </div>
            """.trimIndent(),
            chapterId = "225115"
        )

        val texts = result.paragraphs()
        assertNull(result.warning)
        assertEquals("「呃，outcome……吗？拼法是……」", texts[19])
        assertEquals("我小声念出来，然后翻开单字卡。", texts[20])
        assertTrue(texts.indexOf("前往打工书店的途中──") > 20)
    }

    @Test
    fun parseRestoresLinovelibSeededOrderForNestedParagraphs() {
        val result = parse(
            """
                <div id="TextContent">
                    <div class="chapter-inner">
                        ${problemParagraphs()}
                    </div>
                </div>
            """.trimIndent(),
            chapterId = "225115"
        )

        val texts = result.paragraphs()
        assertNull(result.warning)
        assertEquals("「呃，outcome……吗？拼法是……」", texts[19])
        assertEquals("我小声念出来，然后翻开单字卡。", texts[20])
        assertTrue(texts.indexOf("前往打工书店的途中──") > 20)
    }

    @Test
    fun parseDoesNotReturnDomOrderWhenSeededRestoreFails() {
        val result = parse(
            """
                <div id="TextContent">
                    ${problemParagraphs()}
                </div>
            """.trimIndent(),
            chapterId = "bad-id"
        )

        assertNotNull(result.warning)
        assertEquals(listOf(LinovelibChapterContentParser.WARNING_MESSAGE), result.paragraphs())
    }

    @Test
    fun parseMergesAdjacentTextParagraphsWithBlankLine() {
        val result = parse(
            """
                <div id="TextContent">
                    <p>第一段</p>
                    <p>第二段</p>
                </div>
            """.trimIndent()
        )

        assertEquals(listOf(LinovelibChapterContentParser.Part.Text("第一段\n\n第二段")), result.parts)
    }

    @Test
    fun parseKeepsSectionBlankLineFromConsecutiveBreaks() {
        val result = parse(
            """
                <div id="TextContent">
                    <p>第一小节<br><br><br>第二小节</p>
                </div>
            """.trimIndent()
        )

        assertEquals(listOf(LinovelibChapterContentParser.Part.Text("第一小节\n\n\n第二小节")), result.parts)
    }

    @Test
    fun parseKeepsSectionBlankLineFromBlankBlock() {
        val result = parse(
            """
                <div id="TextContent">
                    <p>第一小节末段</p>
                    <p><br></p>
                    <p>第二小节首段</p>
                </div>
            """.trimIndent()
        )

        assertEquals(listOf(LinovelibChapterContentParser.Part.Text("第一小节末段\n\n\n第二小节首段")), result.parts)
    }

    @Test
    fun parseKeepsSectionBlankLineFromTopLevelBreak() {
        val result = parse(
            """
                <div id="TextContent">
                    <p>对我们来说是高中最后一次的文化祭开始了。</p>
                    <br>
                    <p>教室以布幕区隔前后，后面一半是后场。当然，桌椅堆在更后方。</p>
                </div>
            """.trimIndent()
        )

        assertEquals(
            listOf(
                LinovelibChapterContentParser.Part.Text(
                    "对我们来说是高中最后一次的文化祭开始了。\n\n\n教室以布幕区隔前后，后面一半是后场。当然，桌椅堆在更后方。"
                )
            ),
            result.parts
        )
    }

    @Test
    fun parseKeepsInlineBreakInsideParagraph() {
        val result = parse(
            """
                <div id="TextContent">
                    <p>第一行<br>第二行</p>
                </div>
            """.trimIndent()
        )

        assertEquals(listOf(LinovelibChapterContentParser.Part.Text("第一行\n第二行")), result.parts)
    }

    @Test
    fun parseKeepsTopLevelBreakDuringSeededRestore() {
        val paragraphs = (0 until 123).joinToString("\n") { index ->
            val text = when (index) {
                32 -> "对我们来说是高中最后一次的文化祭开始了。"
                58 -> "教室以布幕区隔前后，后面一半是后场。当然，桌椅堆在更后方。"
                else -> "段落$index"
            }
            if (index == 37) "<p>$text</p>\n<br>" else "<p>$text</p>"
        }
        val result = parse(
            """
                <div id="TextContent">
                    $paragraphs
                </div>
            """.trimIndent(),
            chapterId = "245421"
        )

        assertNull(result.warning)
        assertTrue(
            (result.parts.single() as LinovelibChapterContentParser.Part.Text).text.contains(
                "对我们来说是高中最后一次的文化祭开始了。\n\n\n教室以布幕区隔前后，后面一半是后场。当然，桌椅堆在更后方。"
            )
        )
    }

    @Test
    fun parseCoalescesConsecutiveBlankBlocksAsSectionBlankLine() {
        val result = parse(
            """
                <div id="TextContent">
                    <p>第一小节末段</p>
                    <p><br></p>
                    <p>&nbsp;</p>
                    <p>第二小节首段</p>
                </div>
            """.trimIndent()
        )

        assertEquals(listOf(LinovelibChapterContentParser.Part.Text("第一小节末段\n\n\n第二小节首段")), result.parts)
    }

    @Test
    fun parseKeepsTrailingSectionBreakForPagedMerge() {
        val result = parse(
            """
                <div id="TextContent">
                    <p>本页末段</p>
                    <p><br></p>
                </div>
            """.trimIndent()
        )

        assertEquals(
            listOf(
                LinovelibChapterContentParser.Part.Text("本页末段"),
                LinovelibChapterContentParser.Part.SectionBreak
            ),
            result.parts
        )
    }

    @Test
    fun parseDoesNotPromoteImageBeforeBreakToSectionSpacing() {
        val result = parse(
            """
                <div id="TextContent">
                    <p>插图前小节</p>
                    <p><br></p>
                    <img src="/img.jpg">
                </div>
            """.trimIndent()
        )

        assertEquals(
            listOf(
                LinovelibChapterContentParser.Part.Text("插图前小节"),
                LinovelibChapterContentParser.Part.Image("https://www.linovelib.com/img.jpg")
            ),
            result.parts
        )
    }

    @Test
    fun parseDoesNotPromoteImageAfterBreakToSectionSpacing() {
        val result = parse(
            """
                <div id="TextContent">
                    <img src="/img.jpg">
                    <p><br></p>
                    <p>插图后小节</p>
                </div>
            """.trimIndent()
        )

        assertEquals(
            listOf(
                LinovelibChapterContentParser.Part.Image("https://www.linovelib.com/img.jpg"),
                LinovelibChapterContentParser.Part.Text("插图后小节")
            ),
            result.parts
        )
    }

    @Test
    fun parseRestoresExplicitOrderWithBlankSectionBreak() {
        val result = parse(
            """
                <div id="TextContent">
                    <p style="order: 2">第二小节首段</p>
                    <p><br></p>
                    <p style="order: 1">第一小节末段</p>
                </div>
            """.trimIndent()
        )

        assertNull(result.warning)
        assertEquals(listOf(LinovelibChapterContentParser.Part.Text("第一小节末段\n\n\n第二小节首段")), result.parts)
    }

    @Test
    fun parseKeepsBlankSectionBreakDuringSeededRestore() {
        val paragraphs = (0..21).joinToString("\n") { index ->
            if (index == 0) {
                "<p>段落0</p>\n<p><br></p>"
            } else {
                "<p>段落$index</p>"
            }
        }
        val result = parse(
            """
                <div id="TextContent">
                    $paragraphs
                </div>
            """.trimIndent(),
            chapterId = "225115"
        )

        assertNull(result.warning)
        assertTrue((result.parts.single() as LinovelibChapterContentParser.Part.Text).text.startsWith("段落0\n\n\n段落1\n\n段落2"))
    }

    @Test
    fun parseKeepsBlankLinesBetweenRestoredParagraphs() {
        val result = parse(
            """
                <div id="TextContent">
                    <p style="order: 2">第二段</p>
                    <p style="order: 1">第一段</p>
                    <p style="order: 3">第三段</p>
                </div>
            """.trimIndent()
        )

        assertEquals(listOf(LinovelibChapterContentParser.Part.Text("第一段\n\n第二段\n\n第三段")), result.parts)
    }

    private fun parse(
        html: String,
        chapterId: String? = null
    ): LinovelibChapterContentParser.ParseResult {
        val document = Jsoup.parse(html, "https://www.linovelib.com/novel/1/1.html")
        val content = document.selectFirst("#TextContent") ?: error("missing content")
        return LinovelibChapterContentParser.parse(content, chapterId) { image ->
            image.absUrl("src").ifBlank { image.attr("src") }.let { src ->
                when {
                    src.startsWith("//") -> "https:$src"
                    src.startsWith("/") -> "https://www.linovelib.com$src"
                    else -> src
                }
            }
        }
    }

    private fun problemParagraphs(attrs: (Int) -> String = { "" }): String = (0 until 123).joinToString("\n") { index ->
        val text = when (index) {
            19 -> "「呃，outcome……吗？拼法是……」"
            20 -> "前往打工书店的途中──"
            103 -> "我小声念出来，然后翻开单字卡。"
            else -> "段落$index"
        }
        "<p${attrs(index)}>$text</p>"
    }

    private fun LinovelibChapterContentParser.ParseResult.paragraphs(): List<String> = parts
        .filterIsInstance<LinovelibChapterContentParser.Part.Text>()
        .flatMap { it.text.split("\n\n") }
}
