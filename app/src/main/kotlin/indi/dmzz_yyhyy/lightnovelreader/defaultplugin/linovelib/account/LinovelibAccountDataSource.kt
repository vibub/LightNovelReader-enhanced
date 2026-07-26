package indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.account

import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.LinovelibConstants
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.net.LinovelibJsoup
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.LocalDateTime

class LinovelibAccountDataSource(
    private val jsoup: LinovelibJsoup,
    private val accountStore: LinovelibAccountStore
) {
    suspend fun addBookcaseBookmark(
        bookId: String,
        chapterId: String,
        page: Int,
        refererChapterPageId: String
    ): String {
        if (!accountStore.hasStoredCookie()) throw IllegalStateException("尚未保存 Linovelib 登录 Cookie")
        return jsoup.getRaw(
            url = LinovelibConstants.addBookcaseUrl(bookId, chapterId, page),
            referer = LinovelibConstants.chapterUrl(bookId, refererChapterPageId),
            accept = "text/html,text/plain,*/*",
            useCookie = true,
            retryTime = 1
        )
    }

    suspend fun getRemoteBookshelfResult(): LinovelibRemoteBookshelf {
        if (!accountStore.hasStoredCookie()) throw IllegalStateException("尚未保存 Linovelib 登录 Cookie")
        val candidates = linkedMapOf<String, LinovelibRemoteBook>()
        val visitedUrls = mutableSetOf<String>()
        var expectedGroupCount: Int? = null
        var pagesFetched = 0
        var nextUrl: String? = LinovelibConstants.bookcaseUrl()
        var lastError: Throwable? = null

        while (nextUrl != null && pagesFetched < MAX_BOOKSHELF_PAGES) {
            val url = nextUrl
            if (!visitedUrls.add(url)) break
            val page = runCatching {
                parseBookshelfPage(
                    jsoup.getRaw(
                        url = url,
                        referer = LinovelibConstants.MOBILE_BASE_URL,
                        accept = HTML_ACCEPT,
                        useCookie = true,
                        userAgentMode = LinovelibJsoup.UserAgentMode.Mobile
                    )
                )
            }.onFailure { lastError = it }.getOrNull() ?: break

            pagesFetched++
            expectedGroupCount = expectedGroupCount ?: page.expectedGroupCount
            page.books.forEach { candidates.mergeRemoteBook(it) }
            nextUrl = page.nextPageUrl?.takeIf { it !in visitedUrls }
        }

        if (pagesFetched == 0) {
            throw lastError ?: IllegalStateException("未能解析 Linovelib 远端书架")
        }
        if (candidates.isEmpty() && expectedGroupCount != 0) {
            throw lastError ?: IllegalStateException("未检测到 Linovelib 书架条目，可能被跳转到公共页面")
        }
        return LinovelibRemoteBookshelf(
            books = candidates.values.toList(),
            expectedGroupCount = expectedGroupCount,
            pagesFetched = pagesFetched
        )
    }

    data class LinovelibRemoteBook(
        val bookId: String,
        val title: String = "",
        val coverUrl: String = "",
        val lastUpdated: LocalDateTime = LocalDateTime.MIN,
        val bookmarkChapterId: String = "",
        val bookmarkChapterTitle: String = "",
        val bookmarkHref: String = "",
        val progress: Float = 0f
    )

    data class LinovelibRemoteBookshelf(
        val books: List<LinovelibRemoteBook>,
        val expectedGroupCount: Int? = null,
        val pagesFetched: Int = 1
    )

    private data class ParsedBookshelfPage(
        val books: List<LinovelibRemoteBook>,
        val expectedGroupCount: Int?,
        val nextPageUrl: String?
    )

    private data class ParsedBookmark(
        val chapterId: String = "",
        val title: String = "",
        val href: String = ""
    )

    private data class ReadBookcaseCommand(
        val aid: String,
        val chapterId: String,
        val bid: String,
        val hasProgress: Boolean
    )

    companion object {
        private const val HTML_ACCEPT = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
        private const val MAX_BOOKSHELF_PAGES = 10
        private val BOOK_ID_REGEX = Regex("/novel/(\\d+)(?:\\.html|/|$)")
        private val CHAPTER_ID_REGEX = Regex("/novel/\\d+/(\\d+(?:_\\d+)?)\\.html")
        private val BOOKMARK_PREFIX_REGEX = Regex("^(?:书签章节|书签|阅读至|读到|看到|继续阅读|上次阅读|最近阅读)[:：\\s]*")
        private val AID_QUERY_REGEX = Regex("[?&]aid=(\\d+)")
        private val CID_QUERY_REGEX = Regex("[?&]cid=(\\d+(?:_\\d+)?)")
        private val READ_BOOKCASE_REGEX = Regex(
            "(?:javascript:\\s*)?read_bookcase\\(\\s*(\\d+)\\s*,\\s*(\\d+(?:_\\d+)?)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\)\\s*;?",
            RegexOption.IGNORE_CASE
        )
        private val EXPECTED_GROUP_COUNT_REGEX = Regex("本组有\\s*(\\d+)\\s*本")

        internal fun parseBooksFromHtml(raw: String): List<LinovelibRemoteBook> = parseBookshelfPage(raw).books

        internal fun parseBookshelfFromHtml(raw: String): LinovelibRemoteBookshelf {
            val page = parseBookshelfPage(raw)
            return LinovelibRemoteBookshelf(
                books = page.books,
                expectedGroupCount = page.expectedGroupCount,
                pagesFetched = 1
            )
        }

        private fun parseBookshelfPage(raw: String): ParsedBookshelfPage {
            val doc = Jsoup.parse(raw, LinovelibConstants.bookcaseUrl())
            val expectedGroupCount = doc.extractExpectedGroupCount()
            val bookList = doc.selectFirst("ol#bookEditOl")
                ?: doc.selectFirst(".module-merge ol.book-ol-progress")
                ?: doc.selectFirst(".module-merge ol.book-ol")

            if (bookList == null) {
                when {
                    doc.isLoginRequiredPage() -> throw IllegalStateException("Linovelib Cookie 可能已失效，请重新登录并保存 Cookie")
                    doc.hasBookshelfSignals() -> throw IllegalStateException("未检测到 Linovelib 书架列表，站点书架结构可能已变化")
                    else -> throw IllegalStateException("未检测到 Linovelib 书架内容，可能被跳转到公共页面")
                }
            }

            val books = bookList.select("li.book-li")
                .mapNotNull { it.parseBookcaseItem() }
            if (books.isEmpty() && expectedGroupCount != 0) {
                throw IllegalStateException("未检测到 Linovelib 书架条目，站点书架结构可能已变化")
            }

            return ParsedBookshelfPage(
                books = books,
                expectedGroupCount = expectedGroupCount,
                nextPageUrl = doc.findNextPageUrl()
            )
        }

        private fun Element.parseBookcaseItem(): LinovelibRemoteBook? {
            val detailHref = selectFirst("a.mybook-to-detail[href]")?.absOrAttr("href").orEmpty()
            val catalogHref = selectFirst("a[href~=/novel/\\d+/catalog]")?.absOrAttr("href").orEmpty()
            val bookId = detailHref.extractAid()
                ?: catalogHref.extractBookId()
                ?: select("a[href~=/novel/\\d+(?:\\.html|/)]")
                    .asSequence()
                    .mapNotNull { it.absOrAttr("href").extractBookId() }
                    .firstOrNull()
                ?: return null
            val title = selectFirst("h4.book-title")?.text()?.cleanBookcaseText()
                ?: selectFirst(".book-title")?.text()?.cleanBookcaseText()
                ?: selectFirst("img[alt]")?.attr("alt")?.cleanBookcaseText()
                ?: ""
            val coverUrl = selectFirst("img.book-cover, img")?.let { image ->
                image.absOrAttr("data-src").ifBlank { image.absOrAttr("src") }
            }.orEmpty()
            val lastUpdated = selectFirst("a.mybook-to-new time, time.book-meta-r")
                ?.parseBookcaseDate()
                ?: LocalDateTime.MIN
            val bookmark = extractBookcaseBookmark(bookId)
            return LinovelibRemoteBook(
                bookId = bookId,
                title = title,
                coverUrl = coverUrl,
                lastUpdated = lastUpdated,
                bookmarkChapterId = bookmark.chapterId,
                bookmarkChapterTitle = bookmark.title,
                bookmarkHref = bookmark.href,
                progress = 0f
            )
        }

        private fun Element.extractBookcaseBookmark(bookId: String): ParsedBookmark {
            val goon = selectFirst("a.mybook-to-goon") ?: return ParsedBookmark()
            val href = goon.absOrAttr("href").takeIf { it.isNotBlank() }.orEmpty()
            val title = goon.extractBookmarkTitle()
            href.extractReadBookcaseCommand()?.let { command ->
                if (command.aid != bookId || !command.hasProgress || command.chapterId == "0") {
                    return ParsedBookmark(href = href)
                }
                return ParsedBookmark(
                    chapterId = command.chapterId,
                    title = title.takeIf { it.looksLikeBookmarkTitle() }.orEmpty(),
                    href = href
                )
            }

            val chapterId = href.extractCid()
                ?: CHAPTER_ID_REGEX.find(href)?.groups?.get(1)?.value?.toBaseChapterId()
                ?: ""
            return ParsedBookmark(
                chapterId = chapterId,
                title = title.takeIf { it.looksLikeBookmarkTitle() }.orEmpty(),
                href = href
            )
        }

        private fun Element.extractBookmarkTitle(): String =
            selectFirst(".book-meta p.ell")?.text()?.cleanBookmarkTitle()
                ?: selectFirst("p.ell")?.text()?.cleanBookmarkTitle()
                ?: selectFirst(".ell")?.text()?.cleanBookmarkTitle()
                ?: ""

        private fun Document.extractExpectedGroupCount(): Int? =
            EXPECTED_GROUP_COUNT_REGEX.find(text())?.groups?.get(1)?.value?.toIntOrNull()

        private fun Document.findNextPageUrl(): String? =
            select("a[rel=next], a[href*=bookcase.php]")
                .firstOrNull { link ->
                    val label = link.text().cleanBookcaseText()
                    val href = link.absOrAttr("href")
                    href.contains("bookcase.php") && label in setOf("下一页", "下页", ">", "›")
                }
                ?.absOrAttr("href")
                ?.takeIf { it.isNotBlank() }

        private fun Document.isLoginRequiredPage(): Boolean {
            val pageText = text()
            return selectFirst("input[type=password]") != null ||
                listOf("请先登录", "会员登录", "立即登录", "登录去书架").any { it in pageText }
        }

        private fun Document.hasBookshelfSignals(): Boolean {
            val pageText = text()
            return title().contains("书架") ||
                listOf("我的书架", "小说收藏", "本组有", "已收藏").any { it in pageText }
        }

        private fun String.extractBookId(): String? =
            BOOK_ID_REGEX.find(this)?.groups?.get(1)?.value?.takeIf { it.isNotBlank() }

        private fun String.extractAid(): String? =
            AID_QUERY_REGEX.find(this)?.groups?.get(1)?.value?.takeIf { it.isNotBlank() }

        private fun String.extractCid(): String? =
            CID_QUERY_REGEX.find(this)?.groups?.get(1)?.value?.takeIf { it.isNotBlank() }?.toBaseChapterId()

        private fun String.extractReadBookcaseCommand(): ReadBookcaseCommand? {
            val match = READ_BOOKCASE_REGEX.find(trim().replace("&amp;", "&")) ?: return null
            return ReadBookcaseCommand(
                aid = match.groupValues[1],
                chapterId = match.groupValues[2].toBaseChapterId(),
                bid = match.groupValues[3],
                hasProgress = match.groupValues[4] != "0"
            )
        }

        private fun String.toBaseChapterId(): String = substringBefore('_')

        private fun Element.absOrAttr(attribute: String): String =
            absUrl(attribute).ifBlank { attr(attribute) }.replace("&amp;", "&")

        private fun Element.parseBookcaseDate(): LocalDateTime {
            val year = selectFirst("rt")?.text()?.trim()?.toIntOrNull()
                ?: Regex("(?:19|20)\\d{2}").find(text())?.value?.toIntOrNull()
                ?: return LocalDateTime.MIN
            val monthDay = Regex("(\\d{1,2})月(\\d{1,2})日").find(text())
                ?: return LocalDateTime.MIN
            val month = monthDay.groups[1]?.value?.toIntOrNull() ?: return LocalDateTime.MIN
            val day = monthDay.groups[2]?.value?.toIntOrNull() ?: return LocalDateTime.MIN
            return runCatching {
                LocalDateTime.of(year, month, day, 0, 0)
            }.getOrDefault(LocalDateTime.MIN)
        }

        private fun String.cleanBookcaseText(): String =
            replace(' ', ' ')
                .replace('　', ' ')
                .replace(Regex("\\s+"), " ")
                .trim()

        private fun String.cleanBookmarkTitle(): String =
            cleanBookcaseText()
                .replace(BOOKMARK_PREFIX_REGEX, "")
                .trim(' ', '　', ':', '：', '-', '—')

        private fun String.looksLikeBookmarkTitle(): Boolean {
            if (isBlank()) return false
            if (length > 100) return false
            if (contains("http", ignoreCase = true)) return false
            return true
        }

        private fun MutableMap<String, LinovelibRemoteBook>.mergeRemoteBook(book: LinovelibRemoteBook) {
            val old = this[book.bookId]
            this[book.bookId] = if (old == null) {
                book
            } else {
                old.copy(
                    title = old.title.ifBlank { book.title },
                    coverUrl = old.coverUrl.ifBlank { book.coverUrl },
                    lastUpdated = maxOf(old.lastUpdated, book.lastUpdated),
                    bookmarkChapterId = old.bookmarkChapterId.ifBlank { book.bookmarkChapterId },
                    bookmarkChapterTitle = old.bookmarkChapterTitle.ifBlank { book.bookmarkChapterTitle },
                    bookmarkHref = old.bookmarkHref.ifBlank { book.bookmarkHref },
                    progress = maxOf(old.progress, book.progress)
                )
            }
        }
    }
}
