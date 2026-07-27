package indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib

import io.nightfish.lightnovelreader.api.identifier.Identifier

object LinovelibConstants {
    /** 桌面端 URL，用于 Jsoup 数据抓取（目录、章节、详情） */
    const val BASE_URL = "https://www.linovelib.com"
    /** 移动端 URL，用于 WebView 浏览、登录、搜索、书架 */
    const val MOBILE_BASE_URL = "https://m.bilinovel.com"
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

    // ── 移动端 URL（WebView 浏览与解析回退）──
    fun mobileDetailUrl(bookId: String): String = "$MOBILE_BASE_URL/novel/${bookId.normalizeBookId()}.html"
    fun mobileCatalogUrl(bookId: String): String = "$MOBILE_BASE_URL/novel/${bookId.normalizeBookId()}/catalog"
    fun mobileChapterUrl(bookId: String, chapterId: String): String =
        "$MOBILE_BASE_URL/novel/${bookId.normalizeBookId()}/${chapterId.normalizeChapterId()}.html"
    fun loginUrl(): String = MOBILE_BASE_URL
    fun searchUrl(keyword: String = ""): String = if (keyword.isBlank()) {
        "$MOBILE_BASE_URL/search.html"
    } else {
        "$MOBILE_BASE_URL/search.html?searchkey=$keyword"
    }
    fun bookcaseUrl(): String = "$MOBILE_BASE_URL/bookcase.php"

    fun extractBookIdFromUrl(url: String): String? {
        val host = Regex("^https?://([^/]+)").find(url)?.groups?.get(1)?.value?.lowercase() ?: return null
        val mobileHost = MOBILE_BASE_URL.substringAfter("://")
        val desktopHost = BASE_URL.substringAfter("://")
        val mobileRootDomain = mobileHost.substringAfter('.')
        val isMobileHost = host == mobileHost || host.endsWith(".$mobileRootDomain")
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
