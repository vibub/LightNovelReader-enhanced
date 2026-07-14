package indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.book

import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.LinovelibConstants
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.net.LinovelibJsoup
import io.nightfish.lightnovelreader.api.content.component.ImageComponentData
import io.nightfish.lightnovelreader.api.content.component.SimpleTextStyleRange
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinovelibWebsiteDataSourceTest {
    @Test
    fun parseBookDescriptionPrefersOpenGraphDescription() {
        val document = Jsoup.parse(
            """
            <html><head>
              <meta name="description" content="义妹生活内容简介：SEO 简介">
              <meta property="og:description" content="页面简介">
            </head></html>
            """.trimIndent()
        )

        val description = LinovelibWebsiteDataSource(LinovelibJsoup()).parseBookDescription(document)

        assertEquals("页面简介", description)
    }

    @Test
    fun parseBookDescriptionFallsBackToMetaDescription() {
        val document = Jsoup.parse(
            """
            <html><head>
              <meta name="description" content="内容简介：兜底简介">
            </head></html>
            """.trimIndent()
        )

        val description = LinovelibWebsiteDataSource(LinovelibJsoup()).parseBookDescription(document)

        assertEquals("兜底简介", description)
    }

    @Test
    fun parseBookDescriptionFallsBackToVisibleIntroduction() {
        val document = Jsoup.parse(
            """
            <html><body>
              <div id="bookIntro">内容简介：可见简介</div>
            </body></html>
            """.trimIndent()
        )

        val description = LinovelibWebsiteDataSource(LinovelibJsoup()).parseBookDescription(document)

        assertEquals("可见简介", description)
    }

    @Test
    fun parseBookCoverUrlPrefersVisibleCoverAndKeepsQuery() {
        val document = Jsoup.parse(
            """
            <html><head>
              <meta name="pic" content="https://www.linovelib.com/files/article/image/3/3095/3095s.jpg">
              <meta property="og:image" content="https://www.linovelib.com/files/article/image/3/3095/og.jpg">
            </head><body>
              <div class="book-img"><img src="https://www.linovelib.com/files/article/image/3/3095/3095s.jpg?33311"></div>
            </body></html>
            """.trimIndent()
        )

        val coverUrl = LinovelibWebsiteDataSource(LinovelibJsoup()).parseBookCoverUrl(document)

        assertEquals("https://www.linovelib.com/files/article/image/3/3095/3095s.jpg?33311", coverUrl)
    }

    @Test
    fun parseBookCoverUrlFallsBackToPicMetadata() {
        val document = Jsoup.parse(
            """
            <html><head>
              <meta name="pic" content="https://www.linovelib.com/files/article/image/3/3095/pic.jpg">
              <meta property="og:image" content="https://www.linovelib.com/files/article/image/3/3095/og.jpg">
            </head></html>
            """.trimIndent()
        )

        val coverUrl = LinovelibWebsiteDataSource(LinovelibJsoup()).parseBookCoverUrl(document)

        assertEquals("https://www.linovelib.com/files/article/image/3/3095/pic.jpg", coverUrl)
    }

    @Test
    fun parseBookCoverUrlFallsBackToOpenGraphMetadata() {
        val document = Jsoup.parse(
            """
            <html><head>
              <meta property="og:image" content="https://www.linovelib.com/files/article/image/3/3095/og.jpg">
            </head></html>
            """.trimIndent()
        )

        val coverUrl = LinovelibWebsiteDataSource(LinovelibJsoup()).parseBookCoverUrl(document)

        assertEquals("https://www.linovelib.com/files/article/image/3/3095/og.jpg", coverUrl)
    }

    @Test
    fun parseBookCoverUrlIgnoresPlaceholderVisibleCover() {
        val document = Jsoup.parse(
            """
            <html><head>
              <meta name="pic" content="https://www.linovelib.com/files/article/image/3/3095/pic.jpg">
            </head><body>
              <div class="book-img"><img src="https://www.linovelib.com/images/placeholder.png"></div>
            </body></html>
            """.trimIndent()
        )

        val coverUrl = LinovelibWebsiteDataSource(LinovelibJsoup()).parseBookCoverUrl(document)

        assertEquals("https://www.linovelib.com/files/article/image/3/3095/pic.jpg", coverUrl)
    }

    @Test
    fun parseVolumesResolvesJavascriptCidChapterFromNextChapter() = runBlocking {
        val document = Jsoup.parse(
            """
            <html><body>
            <div class="volume-item clearfix">
              <a href="/novel/2890/vol_186486.html" class="volume-cover">封面</a>
              <div class="volume-info"><h2><a href="/novel/2890/vol_186486.html">义妹生活 8</a></h2></div>
              <ul class="chapter-list clearfix">
                <li><a href="/novel/2890/186487.html">插图</a></li>
                <li><a href="javascript:cid(0)">序幕 浅村悠太</a></li>
                <li><a href="/novel/2890/186489.html">4月19日（星期一）浅村悠太</a></li>
              </ul>
            </div>
            </body></html>
            """.trimIndent(),
            "https://www.linovelib.com/novel/2890/catalog"
        )
        val dataSource = LinovelibWebsiteDataSource(LinovelibJsoup())

        val volumes = dataSource.parseVolumes(document, "2890") { previousChapterId, nextChapterId ->
            assertEquals("186487", previousChapterId)
            assertEquals("186489", nextChapterId)
            "186488"
        }

        assertEquals("义妹生活 8", volumes.single().volumeTitle)
        assertEquals(listOf("186487", "186488", "186489"), volumes.single().chapters.map { it.id })
        assertEquals("序幕 浅村悠太", volumes.single().chapters[1].title)
    }

    @Test
    fun parseVolumesDropsUnresolvedJavascriptCidChapter() = runBlocking {
        val document = Jsoup.parse(
            """
            <html><body>
            <ul class="chapter-list clearfix">
              <li><a href="/novel/2890/186487.html">插图</a></li>
              <li><a href="javascript:cid(0)">序幕 浅村悠太</a></li>
              <li><a href="/novel/2890/186489.html">4月19日（星期一）浅村悠太</a></li>
            </ul>
            </body></html>
            """.trimIndent(),
            "https://www.linovelib.com/novel/2890/catalog"
        )
        val dataSource = LinovelibWebsiteDataSource(LinovelibJsoup())

        val volumes = dataSource.parseVolumes(document, "2890") { _, _ -> null }

        assertEquals(listOf("186487", "186489"), volumes.single().chapters.map { it.id })
    }

    @Test
    fun parseVolumesResolvesJavascriptCidChapterFromPreviousWhenNoNextChapter() = runBlocking {
        val document = Jsoup.parse(
            """
            <html><body>
            <ul class="chapter-list clearfix">
              <li><a href="/novel/2890/186489.html">后记</a></li>
              <li><a href="javascript:cid(0)">尾声 浅村悠太</a></li>
            </ul>
            </body></html>
            """.trimIndent(),
            "https://www.linovelib.com/novel/2890/catalog"
        )
        val dataSource = LinovelibWebsiteDataSource(LinovelibJsoup())

        val volumes = dataSource.parseVolumes(document, "2890") { previousChapterId, nextChapterId ->
            assertEquals("186489", previousChapterId)
            assertNull(nextChapterId)
            "186490"
        }

        assertEquals(listOf("186489", "186490"), volumes.single().chapters.map { it.id })
        assertEquals("尾声 浅村悠太", volumes.single().chapters[1].title)
    }

    @Test
    fun parseVolumesIgnoresVolumeAndPagedLinks() = runBlocking {
        val document = Jsoup.parse(
            """
            <html><body>
            <div class="volume-item clearfix">
              <a href="/novel/2890/vol_186486.html">义妹生活 8</a>
              <ul class="chapter-list clearfix">
                <li><a href="/novel/2890/186487.html">插图</a></li>
                <li><a href="/novel/2890/186487_2.html">插图 第二页</a></li>
                <li><a href="/novel/2890/186489.html">4月19日（星期一）浅村悠太</a></li>
              </ul>
            </div>
            </body></html>
            """.trimIndent(),
            "https://www.linovelib.com/novel/2890/catalog"
        )
        val dataSource = LinovelibWebsiteDataSource(LinovelibJsoup())

        val volumes = dataSource.parseVolumes(document, "2890") { _, _ -> error("不应解析缺失章节") }

        assertEquals(listOf("186487", "186489"), volumes.single().chapters.map { it.id })
    }

    @Test
    fun parseVolumesResolvesJavascriptCidChapterAcrossVolumeBoundary() = runBlocking {
        val document = Jsoup.parse(
            """
            <html><body>
            <div class="volume-item clearfix">
              <h2>义妹生活 8</h2>
              <ul class="chapter-list clearfix">
                <li><a href="/novel/2890/186487.html">后记</a></li>
                <li><a href="javascript:cid(0)">尾声 浅村悠太</a></li>
              </ul>
            </div>
            <div class="volume-item clearfix">
              <h2>义妹生活 9</h2>
              <ul class="chapter-list clearfix">
                <li><a href="/novel/2890/186489.html">序章 绫濑沙季</a></li>
              </ul>
            </div>
            </body></html>
            """.trimIndent(),
            "https://www.linovelib.com/novel/2890/catalog"
        )
        val dataSource = LinovelibWebsiteDataSource(LinovelibJsoup())

        val volumes = dataSource.parseVolumes(document, "2890") { previousChapterId, nextChapterId ->
            assertEquals("186487", previousChapterId)
            assertEquals("186489", nextChapterId)
            "186488"
        }

        assertEquals(listOf("186487", "186488"), volumes[0].chapters.map { it.id })
        assertEquals(listOf("186489"), volumes[1].chapters.map { it.id })
    }

    @Test
    fun parseVolumesResolvesLeadingConsecutiveJavascriptCidChaptersFromNextChapter() = runBlocking {
        val document = Jsoup.parse(
            """
            <html><body>
            <ul class="chapter-list clearfix">
              <li><a href="javascript:cid(0)">序幕 浅村悠太</a></li>
              <li><a href="javascript:cid(0)">序幕 绫濑沙季</a></li>
              <li><a href="/novel/2890/186489.html">4月19日（星期一）浅村悠太</a></li>
            </ul>
            </body></html>
            """.trimIndent(),
            "https://www.linovelib.com/novel/2890/catalog"
        )
        val dataSource = LinovelibWebsiteDataSource(LinovelibJsoup())

        val volumes = dataSource.parseVolumes(document, "2890") { previousChapterId, nextChapterId ->
            when {
                previousChapterId == null && nextChapterId == "186489" -> "186488"
                previousChapterId == null && nextChapterId == "186488" -> "186487"
                else -> null
            }
        }

        assertEquals(listOf("186487", "186488", "186489"), volumes.single().chapters.map { it.id })
        assertEquals("序幕 浅村悠太", volumes.single().chapters[0].title)
        assertEquals("序幕 绫濑沙季", volumes.single().chapters[1].title)
    }

    @Test
    fun parseExploreBooksUsesCoverFromMatchingImageAnchor() {
        val document = Jsoup.parse(
            """
            <html><body>
            <div id="index_tpic">
              <div id="index_tpic_big">
                <a href="/novel/4800.html"><img src="/files/article/image/4/4800/4800s.jpg" alt="在贞操逆转的世界中为所欲为"></a>
                <a href="/novel/2906.html"><img src="/files/article/image/2/2906/2906s.jpg" alt="让声称女性之间不可能的女孩，在百日内彻底沦陷的百合故事。"></a>
              </div>
              <div id="index_tpic_binfo">
                <div class="index_tpic_info"><h3><a class="title" href="/novel/4800.html">在贞操逆转的世界中为所欲为</a></h3></div>
                <div class="index_tpic_info"><h3><a class="title" href="/novel/2906.html">让声称女性之间不可能的女孩，在百日内彻底沦陷的百合故事。</a></h3></div>
              </div>
            </div>
            </body></html>
            """.trimIndent(),
            "https://www.linovelib.com"
        )
        val books = LinovelibWebsiteDataSource(LinovelibJsoup()).parseExploreBooks(document)

        assertEquals(listOf("4800", "2906"), books.map { it.id })
        assertEquals("https://www.linovelib.com/files/article/image/4/4800/4800s.jpg", books[0].coverUrl)
        assertEquals("https://www.linovelib.com/files/article/image/2/2906/2906s.jpg", books[1].coverUrl)
    }

    @Test
    fun parseExploreBooksSupportsMobileBannerAndNormalizesCoverHosts() {
        val mobileHost = LinovelibConstants.MOBILE_BASE_URL.substringAfter("://")
        val rootDomain = mobileHost.substringAfter('.')
        val thirdPartyCover = "https://img3.readpai.com/cover/2211/2211l.jpg"
        val document = Jsoup.parse(
            """
            <html><body>
              <ul class="slide-ul">
                <li class="slide-li">
                  <a href="/novel/3080.html">
                    <img src="https://legacy.$rootDomain/files/article/image/3/3080/3080l.jpg?v=2" alt="我当备胎女友也没关系。">
                  </a>
                </li>
                <li class="slide-li">
                  <a href="/novel/2211.html">
                    <img src="$thirdPartyCover" alt="精灵幻想记">
                  </a>
                </li>
              </ul>
            </body></html>
            """.trimIndent(),
            LinovelibConstants.MOBILE_BASE_URL
        )
        val books = LinovelibWebsiteDataSource(LinovelibJsoup()).parseExploreBooks(document)

        assertEquals(listOf("3080", "2211"), books.map { it.id })
        assertEquals(listOf("我当备胎女友也没关系。", "精灵幻想记"), books.map { it.title })
        assertEquals(
            "${LinovelibConstants.BASE_URL}/files/article/image/3/3080/3080l.jpg?v=2",
            LinovelibJsoup.normalizeCoverUrl(books[0].coverUrl)
        )
        assertEquals(thirdPartyCover, LinovelibJsoup.normalizeCoverUrl(books[1].coverUrl))
    }

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
