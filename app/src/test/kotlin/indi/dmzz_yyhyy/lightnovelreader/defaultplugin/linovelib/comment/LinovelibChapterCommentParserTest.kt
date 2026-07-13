package indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.comment

import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.LinovelibConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LinovelibChapterCommentParserTest {
    @Test
    fun parsePageReadsFieldsAndOrderedReplyQuotes() {
        val page = LinovelibChapterCommentParser.parsePage(
            raw = """
                {
                  "err_msg": "success",
                  "data": [
                    {
                      "plid": "90001",
                      "userpic": "/files/system/avatar/999/999999.jpg",
                      "userinfo": "/user/999999.html",
                      "plusername": "测试用户",
                      "formattime": "2026年01月02日 03:04",
                      "saytext": "<div class='ecomment'><span class='ecommentauthor'>＠用户甲：第一层<br>第二行</span><span class='ecommentauthor'>@用户乙: 第二层</span></div>当前<br>正文<script>alert(1)</script>",
                      "zcnum": "12",
                      "fdnum": 3,
                      "honor": "呆萌",
                      "ispoiler": "1"
                    }
                  ],
                  "total": "21",
                  "pageTotal": 3,
                  "pageSize": "20",
                  "pageIndex": "1",
                  "onclick": 9,
                  "hasmore": 1
                }
            """.trimIndent(),
            requestedPageIndex = 1
        )

        assertEquals(21, page.totalCount)
        assertEquals(9, page.participantCount)
        assertEquals(1, page.pageIndex)
        assertEquals(3, page.pageTotal)
        assertTrue(page.hasMore)
        assertEquals(1, page.comments.size)

        val comment = page.comments.single()
        assertEquals("90001", comment.id)
        assertEquals("测试用户", comment.username)
        assertEquals("${LinovelibConstants.BASE_URL}/files/system/avatar/999/999999.jpg", comment.avatarUrl)
        assertEquals("${LinovelibConstants.BASE_URL}/user/999999.html", comment.userProfileUrl)
        assertEquals("2026年01月02日 03:04", comment.publishedAt)
        assertEquals("呆萌", comment.honor)
        assertEquals("当前\n正文", comment.body)
        assertFalse(comment.body.contains("alert"))
        assertEquals(12, comment.likeCount)
        assertEquals(3, comment.dislikeCount)
        assertTrue(comment.isSpoiler)
        assertEquals(
            listOf(
                LinovelibCommentQuote("用户甲", "第一层\n第二行"),
                LinovelibCommentQuote("用户乙", "第二层")
            ),
            comment.quotedReplies
        )
    }

    @Test
    fun parsePageUsesDefaultsSkipsEmptyCommentsAndCreatesStableLocalId() {
        val raw = """
            {
              "err_msg": "success",
              "data": [
                {
                  "plusername": "",
                  "saytext": "<b>保留文字</b>",
                  "zcnum": "bad",
                  "fdnum": null,
                  "ispoiler": 0
                },
                {
                  "plid": "empty",
                  "saytext": "<script>onlyScript()</script>"
                }
              ],
              "total": 1,
              "pageTotal": "not-a-number",
              "onclick": "bad",
              "hasmore": "0"
            }
        """.trimIndent()

        val first = LinovelibChapterCommentParser.parsePage(raw, requestedPageIndex = 2)
        val second = LinovelibChapterCommentParser.parsePage(raw, requestedPageIndex = 2)

        assertEquals(1, first.comments.size)
        val comment = first.comments.single()
        assertTrue(comment.id.startsWith("local-"))
        assertEquals(comment.id, second.comments.single().id)
        assertEquals("", comment.username)
        assertEquals("保留文字", comment.body)
        assertEquals(0, comment.likeCount)
        assertEquals(0, comment.dislikeCount)
        assertFalse(comment.isSpoiler)
        assertEquals(2, first.pageIndex)
        assertEquals(1, first.pageTotal)
        assertEquals(0, first.participantCount)
        assertFalse(first.hasMore)
    }

    @Test
    fun parsePageKeepsMalformedQuoteAsQuoteText() {
        val page = LinovelibChapterCommentParser.parsePage(
            raw = """
                {
                  "err_msg": "success",
                  "data": [{
                    "plid": "1",
                    "plusername": "用户",
                    "saytext": "<div class='ecomment'><span class='ecommentauthor'>无法拆分的引用</span></div>回复正文"
                  }]
                }
            """.trimIndent(),
            requestedPageIndex = 1
        )

        assertEquals(
            listOf(LinovelibCommentQuote("", "无法拆分的引用")),
            page.comments.single().quotedReplies
        )
        assertEquals("回复正文", page.comments.single().body)
    }

    @Test
    fun parsePageRejectsFailedResponse() {
        assertThrows(LinovelibCommentProtocolException::class.java) {
            LinovelibChapterCommentParser.parsePage(
                raw = """{"err_msg":"error","info":"读取失败"}""",
                requestedPageIndex = 1
            )
        }
    }

    @Test
    fun parsePageRejectsInvalidJson() {
        assertThrows(LinovelibCommentProtocolException::class.java) {
            LinovelibChapterCommentParser.parsePage("not-json", requestedPageIndex = 1)
        }
    }
}
