package indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.book

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
    fun parseFallsBackAndWarnsWhenOrderSignalIsIncomplete() {
        val result = parse(
            """
                <div id="TextContent">
                    <p style="order: 2">第二段</p>
                    <p>第一段</p>
                </div>
            """.trimIndent()
        )

        assertNotNull(result.warning)
        assertEquals(listOf("第二段", "第一段"), result.paragraphs())
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
        val paragraphs = (0 until 123).joinToString("\n") { index ->
            val text = when (index) {
                19 -> "「呃，outcome……吗？拼法是……」"
                20 -> "前往打工书店的途中──"
                103 -> "我小声念出来，然后翻开单字卡。"
                else -> "段落$index"
            }
            "<p>$text</p>"
        }
        val result = parse(
            """
                <div id="TextContent">
                    $paragraphs
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

    private fun LinovelibChapterContentParser.ParseResult.paragraphs(): List<String> = parts
        .filterIsInstance<LinovelibChapterContentParser.Part.Text>()
        .flatMap { it.text.split("\n\n") }
}
