package indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib

import io.nightfish.lightnovelreader.api.identifier.Identifier
import java.net.URI

object LinovelibConstants {
    /** 桌面端 URL，用于后台详情、目录、章节、探索、筛选与原生搜索。 */
    const val BASE_URL = "https://www.linovelib.com"
    /** 移动端 URL，用于 WebView 浏览、登录、搜索、书架 */
    const val MOBILE_BASE_URL = "https://m.bilinovel.com"
    /** 移动网页入口站点，仅用于可见网页搜索；后台筛选使用 BASE_URL。 */
    const val FILTER_BASE_URL = "https://www.bilinovel.net"
    const val SOURCE_NAME = "Linovelib"
    const val SEARCH_BLOCKED_MESSAGE = "Linovelib 搜索入口被 Cloudflare 拦截。请在网页中搜索并点进小说详情页。"
    val SOURCE_ID = Identifier("lightnovelreader", SOURCE_NAME)
    const val LEGACY_SOURCE_ID: Int = -1488977864

    const val COOKIE_PATH = "linovelib.cookie"
    const val LAST_SYNC_TIME_PATH = "linovelib.last_sync_time"
    const val LAST_SYNC_ERROR_PATH = "linovelib.last_sync_error"
    const val LAST_SYNC_SUMMARY_PATH = "linovelib.last_sync_summary"

    const val SYNC_BOOKSHELF_ID: Int = 0x4C4E4C42
    const val SYNC_BOOKSHELF_NAME: String = "Linovelib 书架"

    // ── 桌面端 URL（Jsoup 数据抓取）──
    fun detailUrl(bookId: String): String = "$BASE_URL/novel/${bookId.normalizeBookId()}.html"
    fun catalogUrl(bookId: String): String = "$BASE_URL/novel/${bookId.normalizeBookId()}/catalog"
    fun chapterUrl(bookId: String, chapterId: String): String =
        "$BASE_URL/novel/${bookId.normalizeBookId()}/${chapterId.normalizeChapterId()}.html"
    fun addBookcaseUrl(bookId: String, chapterId: String, page: Int): String =
        "$BASE_URL/modules/article/addbookcase.php" +
            "?bid=${bookId.normalizeBookId()}" +
            "&cid=${chapterId.normalizeChapterId().substringBefore('_')}" +
            "&pid=${page.coerceAtLeast(1)}" +
            "&ajax_request=1"

    // ── 移动端 URL（用户可见的 WebView 浏览）──
    fun mobileDetailUrl(bookId: String): String = "$MOBILE_BASE_URL/novel/${bookId.normalizeBookId()}.html"
    fun mobileCatalogUrl(bookId: String): String = "$MOBILE_BASE_URL/novel/${bookId.normalizeBookId()}/catalog"
    fun mobileChapterUrl(bookId: String, chapterId: String): String =
        "$MOBILE_BASE_URL/novel/${bookId.normalizeBookId()}/${chapterId.normalizeChapterId()}.html"
    fun loginUrl(): String = MOBILE_BASE_URL
    // 移动旧域名可能丢弃搜索参数并重定向到首页，直接使用当前搜索站点。
    fun searchUrl(keyword: String = ""): String = if (keyword.isBlank()) {
        "$FILTER_BASE_URL/search.html"
    } else {
        "$FILTER_BASE_URL/search.html?searchkey=$keyword"
    }
    fun isSearchPageUrl(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme == "https" && uri.userInfo == null && uri.port in listOf(-1, 443) &&
            uri.host in listOf(MOBILE_BASE_URL, FILTER_BASE_URL, BASE_URL).map { URI(it).host } &&
            uri.path in listOf("/search.html", "/search.php")
    }.getOrDefault(false)

    fun isDesktopPageUrl(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme == "https" && uri.userInfo == null && uri.port in listOf(-1, 443) &&
            uri.host == URI(BASE_URL).host
    }.getOrDefault(false)

    fun isDesktopSearchPageUrl(url: String): Boolean = runCatching {
        val path = URI(url).path
        isDesktopPageUrl(url) && (
            path in listOf("/search.html", "/search.php", "/modules/article/search.php") ||
                Regex("/S\\d+/?").matches(path) ||
                Regex("/search/[^/]+_\\d+\\.html").matches(path)
            )
    }.getOrDefault(false)

    fun bookcaseUrl(): String = "$MOBILE_BASE_URL/bookcase.php"

    fun extractBookIdFromUrl(url: String): String? {
        val host = Regex("^https?://([^/]+)").find(url)?.groups?.get(1)?.value?.lowercase() ?: return null
        val bilinovelHosts = listOf(MOBILE_BASE_URL, FILTER_BASE_URL)
            .map { it.substringAfter("://") }
        val desktopHost = BASE_URL.substringAfter("://")
        val isMobileHost = bilinovelHosts.any { mobileHost ->
            host == mobileHost || host.endsWith(".${mobileHost.substringAfter('.')}")
        }
        if (!isMobileHost && host != desktopHost) return null
        return Regex("/novel/(\\d+)(?:\\.html|/|$)")
            .find(url)
            ?.groups
            ?.get(1)
            ?.value
            ?.normalizeBookId()
            ?.takeIf { it.isNotBlank() }
    }

    fun String.normalizeBookId(): String = trim().substringBefore('.').substringAfterLast('/').filter { it.isDigit() }

    fun String.normalizeChapterId(): String = trim()
        .substringBefore('.').substringAfterLast('/')
        .removePrefix("cid(").removeSuffix(")")
        .filter { it.isDigit() || it == '_' }
}
