package indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.account

import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.LinovelibConstants
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.net.LinovelibJsoup
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

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

    internal fun parseBooksFromHtml(raw: String): List<LinovelibRemoteBook> {
        val doc = Jsoup.parse(raw, LinovelibConstants.MOBILE_BASE_URL)
        val candidates = linkedMapOf<String, LinovelibRemoteBook>()
        val bookLinks = doc.select("a[href~=/novel/\\d+(?:\\.html|/)]")

        bookLinks.forEach { link ->
            val href = link.absUrl("href").ifBlank { link.attr("href") }
            val bookId = href.extractBookId() ?: return@forEach
            val container = link.bookcaseItemContainer()
            val title = container.extractBookTitle(bookId).ifBlank {
                link.text().cleanBookcaseText()
            }
            val bookmark = container.extractBookmark(bookId)
            val current = LinovelibRemoteBook(
                bookId = bookId,
                title = title,
                bookmarkChapterId = bookmark.chapterId,
                bookmarkChapterTitle = bookmark.title,
                bookmarkHref = bookmark.href,
                progress = 0f
            )
            candidates.mergeRemoteBook(current)
        }

        return candidates.values.toList()
    }

    data class LinovelibRemoteBook(
        val bookId: String,
        val title: String = "",
        val bookmarkChapterId: String = "",
        val bookmarkChapterTitle: String = "",
        val bookmarkHref: String = "",
        val progress: Float = 0f
    )

    private data class ParsedBookmark(
        val chapterId: String = "",
        val title: String = "",
        val href: String = ""
    )

    companion object {
        private val BOOKSHELF_HTML_URLS = listOf(
            LinovelibConstants.bookcaseUrl()
        )

        private val BOOK_ID_REGEX = Regex("/novel/(\\d+)(?:\\.html|/|$)")
        private fun chapterRegex(bookId: String) = Regex("/novel/${Regex.escape(bookId)}/(\\d+(?:_\\d+)?)\\.html")
        private val BOOKMARK_PREFIX_REGEX = Regex("^(?:书签|书签章节|阅读至|读到|看到|继续阅读|上次阅读|最近阅读)[:：\\s]*")

        private fun String.extractBookId(): String? =
            BOOK_ID_REGEX.find(this)?.groups?.get(1)?.value?.takeIf { it.isNotBlank() }

        private fun String.toBaseChapterId(): String = substringBefore('_')

        private fun Element.bookcaseItemContainer(): Element {
            var current = this
            repeat(5) {
                val parent = current.parent() ?: return current
                val className = parent.className().lowercase()
                if (
                    parent.tagName() in setOf("li", "tr") ||
                    listOf("book", "case", "shelf", "item", "novel", "list").any { it in className }
                ) {
                    return parent
                }
                current = parent
            }
            return current
        }

        private fun Element.extractBookTitle(bookId: String): String {
            select("a[href~=/novel/${Regex.escape(bookId)}(?:\\.html|/|$)]")
                .firstOrNull { chapterRegex(bookId).find(it.attr("href")) == null }
                ?.text()
                ?.cleanBookcaseText()
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }
            select("h1, h2, h3, h4, .title, .bookname, .book-title, .name")
                .firstOrNull()
                ?.text()
                ?.cleanBookcaseText()
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }
            return ""
        }

        private fun Element.extractBookmark(bookId: String): ParsedBookmark {
            val chapterLink = select("a[href~=/novel/${Regex.escape(bookId)}/\\d+(?:_\\d+)?\\.html]")
                .firstOrNull()
            if (chapterLink != null) {
                val href = chapterLink.absUrl("href").ifBlank { chapterLink.attr("href") }
                val chapterId = chapterRegex(bookId).find(href)?.groups?.get(1)?.value.orEmpty().toBaseChapterId()
                val title = chapterLink.text().cleanBookmarkTitle()
                return ParsedBookmark(chapterId, title, href)
            }

            val text = listOf(
                ".bookcase-bookmark", ".reading-record", ".bookmark", ".read", ".desc", ".intro", ".info", ".sub", "p", "span", "div"
            ).asSequence()
                .flatMap { selector -> select(selector).asSequence() }
                .map { it.ownText().ifBlank { it.text() }.cleanBookmarkTitle() }
                .firstOrNull { it.looksLikeBookmarkTitle() }
                .orEmpty()
            return ParsedBookmark(title = text)
        }

        private fun String.cleanBookcaseText(): String =
            replace(' ', ' ')
                .replace(Regex("\\s+"), " ")
                .trim()

        private fun String.cleanBookmarkTitle(): String =
            cleanBookcaseText()
                .replace(BOOKMARK_PREFIX_REGEX, "")
                .trim(' ', '　', ':', '：', '-', '—')

        private fun String.looksLikeBookmarkTitle(): Boolean {
            if (isBlank()) return false
            if (length > 80) return false
            if (contains("http", ignoreCase = true)) return false
            return true
        }

        private fun MutableMap<String, LinovelibRemoteBook>.mergeRemoteBook(book: LinovelibRemoteBook) {
            val old = this[book.bookId]
            this[book.bookId] = when {
                old == null -> book
                old.bookmarkChapterId.isBlank() && book.bookmarkChapterId.isNotBlank() -> book.copy(title = book.title.ifBlank { old.title })
                old.bookmarkChapterTitle.isBlank() && book.bookmarkChapterTitle.isNotBlank() -> book.copy(title = book.title.ifBlank { old.title })
                old.title.isBlank() && book.title.isNotBlank() -> old.copy(title = book.title)
                else -> old
            }
        }
    }
}
