package indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.explore

import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.LinovelibConstants
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.book.LinovelibWebsiteDataSource
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.net.LinovelibJsoup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

internal enum class LinovelibExplorePage(
    val pageId: String,
    val title: String
) {
    Home("home", "首页"),
    Ranking("ranking", "榜单"),
    PublishingHouse("publishing-house", "文库")
}

internal enum class LinovelibExploreSection(
    val page: LinovelibExplorePage,
    val title: String
) {
    HomeRecommended(LinovelibExplorePage.Home, "首页推荐"),
    NewBooks(LinovelibExplorePage.Home, "新书精选"),
    Popular(LinovelibExplorePage.Home, "热门小说"),
    ClassicCompleted(LinovelibExplorePage.Home, "经典完本"),
    CompletedRecommended(LinovelibExplorePage.Home, "完本推荐"),
    RecentUpdates(LinovelibExplorePage.Home, "最近更新"),
    StrongRecommended(LinovelibExplorePage.Ranking, "强推榜"),
    NewBookRanking(LinovelibExplorePage.Ranking, "新书榜"),
    HotRanking(LinovelibExplorePage.Ranking, "热点榜")
}

internal data class LinovelibExplorePublishingHouse(
    val id: String,
    val title: String,
    val books: List<LinovelibWebsiteDataSource.LinovelibExploreBook>
) {
    val expandedPageDataSourceId: String = "linovelib-wenku-$id"
}

internal data class LinovelibExploreSnapshot(
    private val booksBySection: Map<LinovelibExploreSection, List<LinovelibWebsiteDataSource.LinovelibExploreBook>>,
    val publishingHouses: List<LinovelibExplorePublishingHouse> = emptyList()
) {
    operator fun get(section: LinovelibExploreSection): List<LinovelibWebsiteDataSource.LinovelibExploreBook> =
        booksBySection[section].orEmpty()

    fun sections(page: LinovelibExplorePage): List<Pair<LinovelibExploreSection, List<LinovelibWebsiteDataSource.LinovelibExploreBook>>> =
        LinovelibExploreSection.entries
            .filter { it.page == page }
            .map { it to get(it) }

    val hasBooks: Boolean
        get() = booksBySection.values.any { it.isNotEmpty() } || publishingHouses.any { it.books.isNotEmpty() }

    companion object {
        fun empty(): LinovelibExploreSnapshot = LinovelibExploreSnapshot(emptyMap())

        fun recommended(
            books: List<LinovelibWebsiteDataSource.LinovelibExploreBook>
        ): LinovelibExploreSnapshot = LinovelibExploreSnapshot(
            mapOf(LinovelibExploreSection.HomeRecommended to books)
        )
    }
}

internal object LinovelibExploreHomepageParser {
    fun parse(document: Document): LinovelibExploreSnapshot {
        val popularContainer = document.findSectionContainer("热门小说", "tab-lists-two")
        return LinovelibExploreSnapshot(
            booksBySection = linkedMapOf(
                LinovelibExploreSection.HomeRecommended to listOfNotNull(
                    document.selectFirst("#index_tpic")
                ).parseExploreBooks(),
                LinovelibExploreSection.NewBooks to listOfNotNull(
                    document.findSectionContainer("新书精选", "tab-lists-two")
                ).parseExploreBooks(),
                LinovelibExploreSection.Popular to listOfNotNull(
                    popularContainer?.selectFirst(".pic-blank-scroll")
                ).parseExploreBooks(),
                LinovelibExploreSection.ClassicCompleted to listOfNotNull(
                    document.findSectionContainer("经典完本", "top-two-blank-left")
                ).parseExploreBooks(),
                LinovelibExploreSection.CompletedRecommended to listOfNotNull(
                    document.findSectionContainer("完本推荐", "tab-lists-two")
                ).parseExploreBooks(),
                LinovelibExploreSection.RecentUpdates to listOfNotNull(
                    document.selectFirst(".new_chapter")
                ).parseExploreBooks(),
                LinovelibExploreSection.StrongRecommended to listOfNotNull(
                    document.findSectionContainer("强推榜", "tab-lists")
                ).parseExploreBooks(),
                LinovelibExploreSection.NewBookRanking to listOfNotNull(
                    document.findSectionContainer("新书榜", "tab-lists")
                ).parseExploreBooks(),
                LinovelibExploreSection.HotRanking to listOfNotNull(
                    document.findSectionContainer("热点榜", "tab-lists")
                ).parseExploreBooks()
            ),
            publishingHouses = popularContainer.parseExplorePublishingHouses()
        )
    }
}

internal class LinovelibExploreHomepageLoader(
    private val loadDesktopSnapshot: suspend () -> LinovelibExploreSnapshot,
    private val loadMobileRecommendations: suspend () -> List<LinovelibWebsiteDataSource.LinovelibExploreBook>,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
    private val cacheDurationMillis: Long = DEFAULT_CACHE_DURATION_MILLIS,
    private val onError: (Throwable) -> Unit = { it.printStackTrace() }
) {
    private val mutex = Mutex()
    private var cachedSnapshot: LinovelibExploreSnapshot? = null
    private var cacheExpiresAtMillis = 0L

    @Volatile
    private var invalidated = false

    fun invalidate() {
        invalidated = true
    }

    suspend fun getSnapshot(): LinovelibExploreSnapshot = mutex.withLock {
        val now = currentTimeMillis()
        cachedSnapshot
            ?.takeIf { !invalidated && now < cacheExpiresAtMillis }
            ?.let { return@withLock it }

        val previousSnapshot = cachedSnapshot
        val snapshot = try {
            loadDesktopSnapshot().also {
                check(it.hasBooks) { "Linovelib desktop homepage has no recognized explore books" }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            onError(error)
            previousSnapshot ?: loadMobileFallback()
        }

        cachedSnapshot = snapshot
        cacheExpiresAtMillis = now + cacheDurationMillis
        invalidated = false
        snapshot
    }

    private suspend fun loadMobileFallback(): LinovelibExploreSnapshot = try {
        LinovelibExploreSnapshot.recommended(
            loadMobileRecommendations()
                .filter { it.id.isNotBlank() }
                .distinctBy { it.id }
                .take(MOBILE_RECOMMENDATION_LIMIT)
        )
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        onError(error)
        LinovelibExploreSnapshot.empty()
    }

    companion object {
        internal const val DEFAULT_CACHE_DURATION_MILLIS = 10 * 60 * 1000L
        private const val MOBILE_RECOMMENDATION_LIMIT = 12
    }
}

internal fun inferLinovelibCoverUrl(bookId: String): String {
    val normalizedId = bookId.filter(Char::isDigit).takeIf { it.isNotBlank() } ?: return ""
    val imageGroup = normalizedId.toLongOrNull()?.div(1000) ?: return ""
    return "${LinovelibConstants.BASE_URL}/files/article/image/$imageGroup/$normalizedId/${normalizedId}s.jpg"
}

private fun Document.findSectionContainer(title: String, ancestorClass: String): Element? =
    select(".title")
        .firstOrNull { element ->
            element.ownText().cleanExploreText() == title || element.text().cleanExploreText() == title
        }
        ?.ancestors()
        ?.firstOrNull { it.hasClass(ancestorClass) }

private fun Element?.parseExplorePublishingHouses(): List<LinovelibExplorePublishingHouse> = this
    ?.select(".cate-blank .cate-cell")
    ?.mapNotNull { cell ->
        val pageAnchor = cell.select("a[href]")
            .firstOrNull { PUBLISHING_HOUSE_PAGE_REGEX.containsMatchIn(it.attr("href")) }
            ?: return@mapNotNull null
        val pageMatch = PUBLISHING_HOUSE_PAGE_REGEX.find(pageAnchor.attr("href"))
            ?: return@mapNotNull null
        val id = pageMatch.groups[1]?.value.orEmpty()
        val title = cell.selectFirst(".top-title-two .title, .title")
            ?.text()
            ?.cleanExploreText()
            .orEmpty()
        val books = listOfNotNull(cell.selectFirst(".lists")).parseExploreBooks()
        if (id.isBlank() || title.isBlank() || books.isEmpty()) return@mapNotNull null
        LinovelibExplorePublishingHouse(
            id = id,
            title = title,
            books = books
        )
    }
    ?.distinctBy { it.id }
    .orEmpty()

private fun List<Element>.parseExploreBooks(): List<LinovelibWebsiteDataSource.LinovelibExploreBook> {
    val anchors = flatMap { container ->
        container.select("a[href]").filter { it.exploreBookId() != null }
    }
    return anchors
        .mapNotNull(Element::exploreBookId)
        .distinct()
        .mapNotNull { bookId ->
            val bookAnchors = anchors.filter { it.exploreBookId() == bookId }
            val title = bookAnchors.exploreBookTitle() ?: return@mapNotNull null
            LinovelibWebsiteDataSource.LinovelibExploreBook(
                id = bookId,
                title = title,
                author = bookAnchors.exploreBookAuthor(bookId),
                coverUrl = bookAnchors.exploreBookCoverUrl().ifBlank {
                    inferLinovelibCoverUrl(bookId)
                }
            )
        }
}

private fun List<Element>.exploreBookTitle(): String? {
    asSequence()
        .mapNotNull { it.selectFirst("img")?.attr("alt")?.cleanExploreText()?.takeIf(String::isNotBlank) }
        .firstOrNull()
        ?.let { return it }
    asSequence()
        .filter(Element::isExplicitBookTitleAnchor)
        .map { it.text().cleanExploreText() }
        .firstOrNull { it.isNotBlank() && it !in IGNORED_BOOK_LINK_TEXT }
        ?.let { return it }
    asSequence()
        .map { it.attr("title").cleanExploreText() }
        .firstOrNull { it.isNotBlank() && it !in IGNORED_BOOK_LINK_TEXT }
        ?.let { return it }
    return asSequence()
        .map { it.text().cleanExploreText() }
        .firstOrNull { it.isNotBlank() && it !in IGNORED_BOOK_LINK_TEXT }
}

private fun Element.isExplicitBookTitleAnchor(): Boolean =
    hasClass("title") ||
        hasClass("name") ||
        hasClass("bookname") ||
        parent()?.hasClass("bookname") == true ||
        attr("title").isNotBlank()

private fun List<Element>.exploreBookAuthor(bookId: String): String = asSequence()
    .flatMap(Element::ancestors)
    .filter { context -> context.exploreBookIds() == setOf(bookId) }
    .mapNotNull(Element::authorText)
    .firstOrNull()
    .orEmpty()

private fun Element.authorText(): String? = AUTHOR_LINK_SELECTORS.firstNotNullOfOrNull { selector ->
    selectFirst(selector)
        ?.text()
        ?.cleanExploreText()
        ?.takeIf(String::isNotBlank)
} ?: selectFirst(".author")
    ?.text()
    ?.cleanExploreText()
    ?.removePrefix("作者")
    ?.trimStart('：', ':', ' ')
    ?.takeIf(String::isNotBlank)

private fun List<Element>.exploreBookCoverUrl(): String = asSequence()
    .mapNotNull { it.selectFirst("img") }
    .map(Element::realExploreImageUrl)
    .firstOrNull(String::isNotBlank)
    .orEmpty()

private fun Element.realExploreImageUrl(): String {
    EXPLORE_IMAGE_URL_ATTRS.forEach { attrName ->
        val url = absUrl(attrName).ifBlank { attr(attrName) }.toRealExploreImageUrl()
        if (url.isNotBlank()) return url
    }
    return ""
}

private fun String.toRealExploreImageUrl(): String {
    val normalizedUrl = LinovelibJsoup.normalizeUrl(this)
    val lowerUrl = normalizedUrl.lowercase()
    return normalizedUrl.takeIf {
        lowerUrl.isNotBlank() &&
            "loading." !in lowerUrl &&
            "placeholder" !in lowerUrl &&
            "book-cover-no" !in lowerUrl &&
            !lowerUrl.endsWith("/blank.gif") &&
            !lowerUrl.endsWith("/spacer.gif") &&
            !lowerUrl.endsWith("/transparent.png")
    }.orEmpty()
}

private fun Element.exploreBookId(): String? = BOOK_PAGE_REGEX
    .find(attr("href"))
    ?.groups
    ?.get(1)
    ?.value

private fun Element.exploreBookIds(): Set<String> = select("a[href]")
    .mapNotNull(Element::exploreBookId)
    .toSet()

private fun Element.ancestors(): Sequence<Element> = generateSequence(parent()) { it.parent() }

private fun String.cleanExploreText(): String = replace(' ', ' ')
    .replace(Regex("\\s+"), " ")
    .trim()

private val BOOK_PAGE_REGEX = Regex("/novel/(\\d+)\\.html(?:[?#].*)?$")
private val PUBLISHING_HOUSE_PAGE_REGEX = Regex("/wenku/([^/?#]+)/1\\.html(?:[?#].*)?$")
private val IGNORED_BOOK_LINK_TEXT = setOf("立即阅读", "更多")
private val AUTHOR_LINK_SELECTORS = listOf(
    ".author a[href]",
    "a[href*=/authorarticle/]",
    "a.author-text",
    ".author-text",
    "span.author a[href]"
)
private val EXPLORE_IMAGE_URL_ATTRS = listOf(
    "data-src",
    "data-original",
    "data-original-url",
    "data-lazy-src",
    "data-echo",
    "data-cover",
    "src"
)
