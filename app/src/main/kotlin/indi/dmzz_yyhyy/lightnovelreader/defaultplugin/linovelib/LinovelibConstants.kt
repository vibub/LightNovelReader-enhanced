package indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib

object LinovelibConstants {
    const val BASE_URL = "https://www.linovelib.com"
    const val MOBILE_BASE_URL = "https://m.bilinovel.com"
    const val API_BASE_URL = "https://api.linovelib.com"
    const val SOURCE_NAME = "Linovelib"
    val SOURCE_ID: Int = "linovelib".hashCode()

    const val COOKIE_PATH = "linovelib.cookie"
    const val LAST_SYNC_TIME_PATH = "linovelib.last_sync_time"
    const val LAST_SYNC_ERROR_PATH = "linovelib.last_sync_error"
    const val LAST_SYNC_SUMMARY_PATH = "linovelib.last_sync_summary"

    const val SYNC_BOOKSHELF_ID: Int = 0x4C4E4C42
    const val SYNC_BOOKSHELF_NAME: String = "Linovelib 书架"

    fun detailUrl(bookId: String): String = "$BASE_URL/novel/${bookId.normalizeBookId()}.html"
    fun catalogUrl(bookId: String): String = "$BASE_URL/novel/${bookId.normalizeBookId()}/catalog"
    fun chapterUrl(bookId: String, chapterId: String): String =
        "$BASE_URL/novel/${bookId.normalizeBookId()}/${chapterId.normalizeChapterId()}.html"
    fun loginUrl(): String = "$MOBILE_BASE_URL/login.php"
    fun bookshelfApiUrl(): String = "$API_BASE_URL/api/user/bookshelf"

    fun String.normalizeBookId(): String = trim().substringBefore('.').substringAfterLast('/').filter { it.isDigit() }

    fun String.normalizeChapterId(): String = trim()
        .substringBefore('.').substringAfterLast('/')
        .removePrefix("cid(").removeSuffix(")")
        .filter { it.isDigit() || it == '_' }
}
