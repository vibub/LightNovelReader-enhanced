package indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LinovelibAccountDataSourceTest {
    @Test
    fun parseBooksFromHtmlOnlyReadsBookEditOlItems() {
        val result = LinovelibAccountDataSource.parseBookshelfFromHtml(
            bookshelfHtml(
                expectedCount = 2,
                items = listOf(
                    bookItem(
                        bid = "8369098",
                        aid = "8",
                        title = "欢迎来到实力至上主义的教室",
                        bookmark = "插图",
                        latestCid = "325518",
                        latestTitle = "高圆寺六助＆王美雨特典 看不见的线"
                    ),
                    bookItem(
                        bid = "11843145",
                        aid = "3095",
                        title = "败北女角太多了！",
                        bookmark = "",
                        latestCid = "319760",
                        latestTitle = "Bookwalker电子书特典 敬请投下神圣的一票"
                    )
                ),
                extraHtml = searchPopupHtml()
            )
        )

        assertEquals(2, result.expectedGroupCount)
        assertEquals(listOf("8", "3095"), result.books.map { it.bookId })
        assertTrue(result.books.none { it.bookId in setOf("1", "2", "3") })
    }

    @Test
    fun parseBooksFromHtmlUsesAidInsteadOfBookcaseBid() {
        val book = LinovelibAccountDataSource.parseBooksFromHtml(
            bookshelfHtml(
                items = listOf(
                    bookItem(
                        bid = "9999999",
                        aid = "2547",
                        title = "关于我在无意间被隔壁的天使变成废柴这件事",
                        bookmark = "特典 不论是怎样的你",
                        latestCid = "315611",
                        latestTitle = "后记"
                    )
                )
            )
        ).single()

        assertEquals("2547", book.bookId)
        assertEquals("关于我在无意间被隔壁的天使变成废柴这件事", book.title)
    }

    @Test
    fun parseBooksFromHtmlExtractsBookmarkOnlyFromGoonArea() {
        val book = LinovelibAccountDataSource.parseBooksFromHtml(
            bookshelfHtml(
                items = listOf(
                    bookItem(
                        bid = "9514047",
                        aid = "2727",
                        title = "我们不可能成为恋人！绝对不行。 (※似乎可行？)",
                        bookmark = "Animate特典 间章 第1.2章 快乐的快乐的文化祭！班级会议篇",
                        latestCid = "297832",
                        latestTitle = "间章 第1.4章 快乐的快乐的文化祭 筹备与创造篇"
                    )
                )
            )
        ).single()

        assertEquals("", book.bookmarkChapterId)
        assertEquals("", book.bookmarkHref)
        assertEquals("Animate特典 间章 第1.2章 快乐的快乐的文化祭！班级会议篇", book.bookmarkChapterTitle)
    }

    @Test
    fun parseBooksFromHtmlDoesNotUseLatestUpdateAsBookmark() {
        val book = LinovelibAccountDataSource.parseBooksFromHtml(
            bookshelfHtml(
                items = listOf(
                    bookItem(
                        bid = "12434747",
                        aid = "4601",
                        title = "兼职家事服务的我，意外被校园第一美少女全家人所喜爱",
                        bookmark = "",
                        latestCid = "297955",
                        latestTitle = "后记"
                    )
                )
            )
        ).single()

        assertEquals("", book.bookmarkChapterId)
        assertEquals("", book.bookmarkChapterTitle)
        assertEquals("", book.bookmarkHref)
    }

    @Test
    fun parseBooksFromHtmlRejectsLoginPage() {
        val error = assertThrows(IllegalStateException::class.java) {
            LinovelibAccountDataSource.parseBooksFromHtml(
                """
                    <html><head><title>登录哔哩轻小说</title></head>
                    <body><form><input type="password"><button>立即登录</button></form></body></html>
                """.trimIndent()
            )
        }

        assertTrue(error.message.orEmpty().contains("Cookie"))
    }

    @Test
    fun parseBooksFromHtmlRejectsPublicPageWithNovelLinks() {
        val error = assertThrows(IllegalStateException::class.java) {
            LinovelibAccountDataSource.parseBooksFromHtml(
                """
                    <html><head><title>哔哩轻小说</title></head>
                    <body>
                      <div id="searchPopularWords">
                        <a href="https://m.bilinovel.com/novel/1.html">恶魔高校DxD</a>
                        <a href="https://m.bilinovel.com/novel/2.html">果然我的青春恋爱喜剧搞错了</a>
                      </div>
                    </body></html>
                """.trimIndent()
            )
        }

        assertTrue(error.message.orEmpty().contains("公共页面"))
    }

    private fun bookshelfHtml(
        expectedCount: Int? = null,
        items: List<String>,
        extraHtml: String = ""
    ): String =
        """
            <html>
              <head><title>我的书架_哔哩轻小说</title></head>
              <body>
                <div class="module module-merge">
                  <div><em>您的书架可收藏 600 本，已收藏 13 本${expectedCount?.let { "，本组有 $it 本" } ?: ""}。</em></div>
                  <ol id="bookEditOl" class="book-ol book-ol-progress">
                    ${items.joinToString("\n")}
                  </ol>
                </div>
                $extraHtml
              </body>
            </html>
        """.trimIndent()

    private fun bookItem(
        bid: String,
        aid: String,
        title: String,
        bookmark: String,
        latestCid: String,
        latestTitle: String
    ): String =
        """
            <li class="book-li">
              <div class="book-layout">
                <div class="rel">
                  <a href="https://m.bilinovel.com/modules/article/readbookcase.php?bid=$bid&amp;aid=$aid&amp;acode=test" class="mybook-to-detail">
                    <img src="cover.jpg" class="book-cover" alt="$title">
                  </a>
                  <a href="https://m.bilinovel.com/novel/$aid/catalog" class="book-title-x">
                    <h4 class="book-title">$title</h4>
                  </a>
                </div>
                <a class="mybook-to-goon">
                  <div class="book-meta"><p class="ell">$bookmark</p></div>
                </a>
                <div class="rel">
                  <a href="https://m.bilinovel.com/modules/article/readbookcase.php?bid=$bid&amp;aid=$aid&amp;cid=$latestCid" class="mybook-to-new">
                    <div class="book-meta"><p class="ell">$latestTitle</p></div>
                  </a>
                </div>
              </div>
            </li>
        """.trimIndent()

    private fun searchPopupHtml(): String =
        """
            <div id="searchPopup">
              <div id="searchPopularWords">
                <a href="https://m.bilinovel.com/novel/1.html">恶魔高校DxD</a>
                <a href="https://m.bilinovel.com/novel/2.html">果然我的青春恋爱喜剧搞错了</a>
                <a href="https://m.bilinovel.com/novel/3.html">在地下城寻求邂逅是否搞错了什么</a>
              </div>
            </div>
        """.trimIndent()
}
