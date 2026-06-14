package indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.account

import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.LinovelibConstants
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.net.LinovelibJsoup
import org.jsoup.Jsoup

class LinovelibAccountDataSource(
    private val jsoup: LinovelibJsoup,
    private val accountStore: LinovelibAccountStore
) {
    suspend fun getRemoteBookshelf(): List<LinovelibRemoteBook> {
        if (!accountStore.hasCookie()) throw IllegalStateException("尚未保存 Linovelib 登录 Cookie")
        val errors = mutableListOf<Throwable>()
        BOOKSHELF_HTML_URLS.forEach { url ->
            runCatching {
                parseBooksFromHtml(jsoup.getRaw(url, useCookie = true))
                    .takeIf { it.isNotEmpty() }
            }.onSuccess { books ->
                if (books != null) return books
            }.onFailure(errors::add)
        }
        throw errors.firstOrNull() ?: IllegalStateException("未能解析 Linovelib 远端书架")
    }

    private fun parseBooksFromHtml(raw: String): List<LinovelibRemoteBook> {
        val doc = Jsoup.parse(raw, LinovelibConstants.MOBILE_BASE_URL)
        // 解析书架中的书籍链接：/novel/{bookId}.html
        val bookLinks = doc.select("a[href~=/novel/\\d+(?:\\.html|/)]")
        val books = bookLinks.mapNotNull { a ->
            val href = a.attr("href")
            val bookId = Regex("/novel/(\\d+)(?:\\.html|/)").find(href)?.groups?.get(1)?.value
                ?: return@mapNotNull null
            // 提取书名：优先用链接文本，其次用相邻元素
            val title = a.text().takeIf { it.isNotBlank() }
                ?: a.parent()?.text()?.takeIf { it.isNotBlank() }
                ?: ""
            // 尝试提取章节 ID（/novel/{bookId}/{chapterId}.html）
            val chapterId = Regex("/novel/\\d+/(\\d+)\\.html").find(href)?.groups?.get(1)?.value.orEmpty()
            LinovelibRemoteBook(
                bookId = bookId,
                title = title,
                lastReadChapterId = chapterId,
                progress = 0f
            )
        }.distinctBy { it.bookId }

        // 同时解析书签/阅读记录区域（可能包含阅读进度）
        // bookcase.php 页面通常包含 "阅读记录" 或 "书签" 区域
        val bookmarkBooks = doc.select(".bookcase-bookmark a[href~=/novel/\\d+], .reading-record a[href~=/novel/\\d+]")
            .mapNotNull { a ->
                val href = a.attr("href")
                val bookId = Regex("/novel/(\\d+)(?:\\.html|/)").find(href)?.groups?.get(1)?.value
                    ?: return@mapNotNull null
                val chapterId = Regex("/novel/\\d+/(\\d+)\\.html").find(href)?.groups?.get(1)?.value.orEmpty()
                val title = a.text().takeIf { it.isNotBlank() } ?: ""
                bookId to LinovelibRemoteBook(bookId, title, chapterId, 0f)
            }.toMap()

        // 合并书架和书签数据：书签中的阅读进度覆盖书架
        return books.map { book ->
            bookmarkBooks[book.bookId]?.let { bookmark ->
                if (bookmark.lastReadChapterId.isNotBlank()) bookmark else book
            } ?: book
        }
    }

    data class LinovelibRemoteBook(
        val bookId: String,
        val title: String = "",
        val lastReadChapterId: String = "",
        val progress: Float = 0f
    )

    companion object {
        private val BOOKSHELF_HTML_URLS = listOf(
            LinovelibConstants.bookcaseUrl()
        )
    }
}
