package indi.dmzz_yyhyy.lightnovelreader.utils.md

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class HtmlToMdUtilTest {
    @Test
    fun inlineCodeAndHtmlLiteralsStayInsideReleaseNoteItems() {
        val html = """
            <div class="markdown-body">
              <h2>新增 Ruby 注音与正文排版</h2>
              <ul>
                <li>为 <code>SimpleTextStyleRange</code> 增加 ruby 注音数据支持。</li>
                <li>支持将 ruby 注音转换为 HTML <code>&lt;ruby&gt;</code> / <code>&lt;rt&gt;</code> 标记。</li>
                <li>改进 Linovelib/Bilinovel 网页中的 ruby 注音解析。</li>
              </ul>
            </div>
        """.trimIndent()

        assertEquals(
            """
                ## 新增 Ruby 注音与正文排版

                * 为 `SimpleTextStyleRange` 增加 ruby 注音数据支持。
                * 支持将 ruby 注音转换为 HTML `<ruby>` / `<rt>` 标记。
                * 改进 Linovelib/Bilinovel 网页中的 ruby 注音解析。
            """.trimIndent(),
            HtmlToMdUtil.convertHtml(html)
        )
    }

    @Test
    fun inlineCodeUsesLongerDelimiterAndPreservesBoundarySpaces() {
        assertEquals("``  a`b  ``", HtmlToMdUtil.convertHtml("<code> a`b </code>"))
        assertEquals("`` `value` ``", HtmlToMdUtil.convertHtml("<code>`value`</code>"))
        assertEquals("`a b`", HtmlToMdUtil.convertHtml("<code>a\nb</code>"))
    }

    @Test
    fun fencedCodePreservesIndentationBlankLinesAndEmbeddedBackticks() {
        assertEquals(
            "````\n    first\n\n  ```\n&lt;literal&gt;\n````",
            HtmlToMdUtil.convertHtml("<pre><code>    first\n\n  ```\n&amp;lt;literal&amp;gt;\n</code></pre>")
        )
    }

    @Test
    fun nestedListDoesNotChangeParentListType() {
        assertEquals(
            "* 外层一\n\n  1. 内层一\n  2. 内层二\n* 外层二",
            HtmlToMdUtil.convertHtml(
                "<ul><li>外层一<ol><li>内层一</li><li>内层二</li></ol></li><li>外层二</li></ul>"
            )
        )
        assertEquals("* 独立列表", HtmlToMdUtil.convertHtml("<ul><li>独立列表</li></ul>"))
    }

    @Test
    fun listContinuationParagraphsAndCodeRemainIndented() {
        assertEquals(
            "1. 第一段\n\n   第二段\n\n   ```\n     code\n   ```\n2. 下一项",
            HtmlToMdUtil.convertHtml(
                "<ol><li><p>第一段</p><p>第二段</p><pre><code>  code</code></pre></li><li>下一项</li></ol>"
            )
        )
    }

    @Test
    fun ordinaryTextCannotBecomeMarkdownOrRawHtml() {
        assertEquals(
            "\\# 标题 \\*文本\\* \\[链接\\] &lt;ruby&gt; &amp;lt;",
            HtmlToMdUtil.convertHtml("<p># 标题 *文本* [链接] &lt;ruby&gt; &amp;lt;</p>")
        )
    }

    @Test
    fun preservesHeadingsEmphasisLinksImagesAndLineBreaks() {
        assertEquals(
            "#### 标题\n\n**粗体**和*斜体*  \n[链接](<https://example.com/a(b)> \"说明\")\n\n![图片](<https://example.com/a.png>)\n\n---",
            HtmlToMdUtil.convertHtml(
                "<h4>标题</h4><p><strong>粗体</strong>和<em>斜体</em><br>" +
                    "<a href='https://example.com/a(b)' title='说明'>链接</a></p>" +
                    "<p><img src='https://example.com/a.png' alt='图片'></p><hr>"
            )
        )
    }

    @Test
    fun orderedListStartControlsContinuationIndentation() {
        assertEquals(
            "10. 第一段\n\n    第二段\n11. 下一项",
            HtmlToMdUtil.convertHtml("<ol start='10'><li><p>第一段</p><p>第二段</p></li><li>下一项</li></ol>")
        )
    }

    @Test
    fun blockquotePreservesParagraphBoundaries() {
        assertEquals(
            "> 第一段\n>\n> 第二段",
            HtmlToMdUtil.convertHtml("<blockquote><p>第一段</p><p>第二段</p></blockquote>")
        )
    }

    @Test
    fun handlesEmptyContentAndRemovesUnsafeElements() {
        assertEquals("", HtmlToMdUtil.convertHtml(""))
        assertEquals("", HtmlToMdUtil.convertHtml("<div> </div>"))
        val result = HtmlToMdUtil.convertHtml("<script>alert(1)</script><p>正文</p>")
        assertEquals("正文", result)
        assertFalse(result.contains("alert"))
    }
}
