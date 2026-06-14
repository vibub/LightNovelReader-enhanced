package indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.account

import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.LinovelibConstants
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.net.LinovelibJsoup
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.Jsoup

class LinovelibAccountDataSource(
    private val jsoup: LinovelibJsoup,
    private val accountStore: LinovelibAccountStore
) {
    suspend fun getRemoteBookshelf(): List<LinovelibRemoteBook> {
        if (!accountStore.hasCookie()) throw IllegalStateException("尚未保存 Linovelib 登录 Cookie")
        val errors = mutableListOf<Throwable>()
        BOOKSHELF_JSON_URLS.forEach { url ->
            runCatching {
                parseBooksFromJson(jsoup.getRaw(url, useCookie = true))
                    .takeIf { it.isNotEmpty() }
            }.onSuccess { books ->
                if (books != null) return books
            }.onFailure(errors::add)
        }
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

    private fun parseBooksFromJson(raw: String): List<LinovelibRemoteBook> {
        val element = Json.parseToJsonElement(raw)
        val result = mutableListOf<LinovelibRemoteBook>()
        collectBooks(element, result)
        return result.distinctBy { it.bookId }
    }

    private fun collectBooks(element: JsonElement, result: MutableList<LinovelibRemoteBook>) {
        when (element) {
            is JsonArray -> element.forEach { collectBooks(it, result) }
            is JsonObject -> {
                element.toRemoteBook()?.let(result::add)
                element.values.forEach { collectBooks(it, result) }
            }
            else -> Unit
        }
    }

    private fun JsonObject.toRemoteBook(): LinovelibRemoteBook? {
        val bookId = firstString("book_id", "bookid", "articleid", "article_id", "novel_id", "novelid", "aid")
            ?: firstString("id")?.takeIf { containsLikelyBookField() }
            ?: return null
        val normalizedBookId = bookId.filter { it.isDigit() }
        if (normalizedBookId.isBlank()) return null
        val chapterId = firstString("last_read_cid", "lastReadCid", "last_read_chapter_id", "chapter_id", "chapterid", "cid")
            ?.filter { it.isDigit() }
            .orEmpty()
        val progress = firstFloat("progress", "reading_progress", "read_progress")
            ?.let { if (it > 1f) it / 100f else it }
            ?.coerceIn(0f, 1f)
            ?: 0f
        return LinovelibRemoteBook(
            bookId = normalizedBookId,
            lastReadChapterId = chapterId,
            progress = progress
        )
    }

    private fun JsonObject.containsLikelyBookField(): Boolean = keys.any {
        it.contains("book", ignoreCase = true) ||
            it.contains("novel", ignoreCase = true) ||
            it.contains("article", ignoreCase = true) ||
            it.contains("chapter", ignoreCase = true) ||
            it.contains("progress", ignoreCase = true)
    }

    private fun JsonObject.firstString(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
        val value = this[key] ?: return@firstNotNullOfOrNull null
        when (value) {
            is JsonPrimitive -> value.contentOrNull?.takeIf { it.isNotBlank() }
            else -> null
        }
    }

    private fun JsonObject.firstFloat(vararg keys: String): Float? = keys.firstNotNullOfOrNull { key ->
        val value = this[key] ?: return@firstNotNullOfOrNull null
        when (value) {
            is JsonPrimitive -> value.floatOrNull ?: value.contentOrNull?.toFloatOrNull()
            else -> null
        }
    }

    private fun parseBooksFromHtml(raw: String): List<LinovelibRemoteBook> = Jsoup.parse(raw, LinovelibConstants.BASE_URL)
        .select("a[href~=/novel/\\d+(?:/\\d+)?\\.html]")
        .mapNotNull { a ->
            val href = a.attr("href")
            val bookId = Regex("/novel/(\\d+)(?:\\.html|/)").find(href)?.groups?.get(1)?.value ?: return@mapNotNull null
            val chapterId = Regex("/novel/\\d+/(\\d+)\\.html").find(href)?.groups?.get(1)?.value.orEmpty()
            LinovelibRemoteBook(bookId, chapterId, 0f)
        }
        .distinctBy { it.bookId }

    data class LinovelibRemoteBook(
        val bookId: String,
        val lastReadChapterId: String = "",
        val progress: Float = 0f
    )

    companion object {
        private val BOOKSHELF_JSON_URLS = listOf(
            LinovelibConstants.bookshelfApiUrl()
        )
        private val BOOKSHELF_HTML_URLS = listOf(
            "${LinovelibConstants.BASE_URL}/user/bookcase",
            "${LinovelibConstants.BASE_URL}/bookshelf",
            "${LinovelibConstants.BASE_URL}/modules/article/bookcase.php"
        )
    }
}

private val JsonPrimitive.contentOrNull: String?
    get() = runCatching { content }.getOrNull()
